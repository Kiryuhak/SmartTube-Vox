package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class VoxMediaKind {
    LIVE_DVR,
    ARCHIVED_VOD,
    STANDARD_VOD
}

enum class VoxSeekState {
    PENDING,
    BUFFERING,
    SETTLED,
    CLAMPED,
    RECOVERING,
    FAILED,
    CANCELLED
}

enum class VoxSeekOutcome {
    SUCCESS_EXACT,
    SUCCESS_CLAMPED,
    FAILED_RECOVERABLE,
    FAILED_UNRECOVERABLE,
    USER_CANCELLED
}

data class VoxPendingSeek(
    val seekRequestId: String,
    val generation: Long,
    val mediaKind: VoxMediaKind,
    val requestedTargetMs: Long,
    var clampedTargetMs: Long,
    val fromPositionMs: Long,
    val requestedAtMs: Long,
    val videoId: String? = null,
    var windowStartMs: Long = 0L,
    var windowDurationMs: Long = 0L,
    var state: VoxSeekState = VoxSeekState.PENDING,
    var outcome: VoxSeekOutcome? = null,
    var recoveryAttemptCount: Int = 0,
    var manifestRefreshCount: Int = 0,
    var settledPositionMs: Long = -1L,
    var errorCategory: String? = null
)

/**
 * Единый координатор и источник правды для команд перемотки (Seek / Live DVR Recovery).
 *
 * Гарантии:
 * 1. Явная команда пользователя имеет строгий приоритет над устаревшей позицией из БД истории.
 * 2. Устаревшие callback (с несовпадающим generation) отвергаются.
 * 3. Bounded recovery: ограничение попыток восстановления и защита от циклических рестартов.
 * 4. Zero-PII структурированная диагностика.
 */
class VoxPendingSeekCoordinator {

    companion object {
        const val MIN_LIVE_WINDOW_CLAMP_BUFFER_MS = 2_000L
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val SETTLE_TOLERANCE_MS = 4_000L
        const val SEEK_TIMEOUT_MS = 25_000L

        @Volatile
        private var globalInstance: VoxPendingSeekCoordinator? = null

        @JvmStatic
        fun instance(): VoxPendingSeekCoordinator {
            return globalInstance ?: synchronized(this) {
                globalInstance ?: VoxPendingSeekCoordinator().also { globalInstance = it }
            }
        }
    }

    private val generationCounter = AtomicLong(0L)
    @Volatile
    private var activeSeek: VoxPendingSeek? = null

    @Synchronized
    fun startSeek(
        requestedTargetMs: Long,
        fromPositionMs: Long,
        mediaKind: VoxMediaKind,
        windowStartMs: Long = 0L,
        windowDurationMs: Long = 0L,
        videoId: String? = null
    ): VoxPendingSeek {
        // Отменяем предыдущую незавершенную операцию
        activeSeek?.let { prev ->
            if (prev.state == VoxSeekState.PENDING || prev.state == VoxSeekState.BUFFERING || prev.state == VoxSeekState.RECOVERING) {
                prev.state = VoxSeekState.CANCELLED
                prev.outcome = VoxSeekOutcome.USER_CANCELLED
                logDiagnostic(VoxLogCode.DVR_SEEK_REPLACED, prev, "Отменено новым запросом перемотки (last-intent-wins)")
            }
        }

        val gen = generationCounter.incrementAndGet()
        val requestId = "seek_" + System.currentTimeMillis() + "_" + gen

        var clampedTarget = requestedTargetMs
        var isClamped = false

        if (mediaKind == VoxMediaKind.LIVE_DVR) {
            val minSafePos = if (windowStartMs > 0L) windowStartMs + MIN_LIVE_WINDOW_CLAMP_BUFFER_MS else 0L
            val maxSafePos = if (windowDurationMs > 0L) {
                Math.max(minSafePos, windowDurationMs - MIN_LIVE_WINDOW_CLAMP_BUFFER_MS)
            } else {
                Long.MAX_VALUE
            }

            if (requestedTargetMs < minSafePos) {
                clampedTarget = minSafePos
                isClamped = true
            } else if (requestedTargetMs > maxSafePos) {
                clampedTarget = maxSafePos
                isClamped = true
            }
        }

        if (clampedTarget < 0L) {
            clampedTarget = 0L
            isClamped = true
        }

        val seek = VoxPendingSeek(
            seekRequestId = requestId,
            generation = gen,
            mediaKind = mediaKind,
            requestedTargetMs = requestedTargetMs,
            clampedTargetMs = clampedTarget,
            fromPositionMs = fromPositionMs,
            requestedAtMs = System.currentTimeMillis(),
            videoId = videoId,
            windowStartMs = windowStartMs,
            windowDurationMs = windowDurationMs,
            state = if (isClamped) VoxSeekState.CLAMPED else VoxSeekState.PENDING
        )

        activeSeek = seek

        val logCode = if (isClamped) VoxLogCode.DVR_SEEK_CLAMPED else VoxLogCode.DVR_SEEK_REQUEST
        logDiagnostic(logCode, seek, if (isClamped) "Смещение скорректировано под границы окна эфира" else "Запрос перемотки принят")

        return seek
    }

    @Synchronized
    fun onBuffering(generation: Long) {
        val current = activeSeek ?: return
        if (current.generation != generation) return

        if (current.state == VoxSeekState.PENDING || current.state == VoxSeekState.CLAMPED) {
            current.state = VoxSeekState.BUFFERING
        }
    }

    @Synchronized
    fun onPlaybackProgress(currentPosMs: Long, generation: Long): Boolean {
        val current = activeSeek ?: return false
        if (current.generation != generation) return false
        if (current.state == VoxSeekState.SETTLED || current.state == VoxSeekState.CANCELLED || current.state == VoxSeekState.FAILED) {
            return false
        }

        val target = current.clampedTargetMs
        val isSettled = when {
            // Позиция достаточно близка к цели
            Math.abs(currentPosMs - target) <= SETTLE_TOLERANCE_MS -> true
            // Для прямого эфира: воспроизведение продвинулось вперед от цели
            current.mediaKind == VoxMediaKind.LIVE_DVR && currentPosMs > target && (currentPosMs - target) <= (SETTLE_TOLERANCE_MS * 2) -> true
            else -> false
        }

        if (isSettled) {
            current.state = VoxSeekState.SETTLED
            current.settledPositionMs = currentPosMs
            current.outcome = if (current.requestedTargetMs == current.clampedTargetMs) {
                VoxSeekOutcome.SUCCESS_EXACT
            } else {
                VoxSeekOutcome.SUCCESS_CLAMPED
            }
            val durationMs = System.currentTimeMillis() - current.requestedAtMs
            logDiagnostic(VoxLogCode.DVR_SEEK_SETTLED, current, "Перемотка успешно зафиксирована (${durationMs}мс)")
            return true
        }

        return false
    }

    @Synchronized
    fun onBehindLiveWindow(windowStartMs: Long, windowDurationMs: Long, generation: Long): Long {
        val current = activeSeek
        if (current == null || current.generation != generation) {
            return -1L
        }

        current.windowStartMs = windowStartMs
        current.windowDurationMs = windowDurationMs
        current.recoveryAttemptCount++
        current.manifestRefreshCount++

        if (current.recoveryAttemptCount > MAX_RECOVERY_ATTEMPTS) {
            current.state = VoxSeekState.FAILED
            current.outcome = VoxSeekOutcome.FAILED_UNRECOVERABLE
            current.errorCategory = "BEHIND_LIVE_WINDOW_EXHAUSTED"
            logDiagnostic(VoxLogCode.LIVE_BEHIND_WINDOW_RECOVERED, current, "Лимит попыток восстановления BehindLiveWindow исчерпан")
            return -1L
        }

        current.state = VoxSeekState.RECOVERING

        // Вычисляем допустимый диапазон в новом окне
        val minSafePos = if (windowStartMs > 0L) windowStartMs + MIN_LIVE_WINDOW_CLAMP_BUFFER_MS else 0L
        val maxSafePos = if (windowDurationMs > 0L) {
            Math.max(minSafePos, windowDurationMs - MIN_LIVE_WINDOW_CLAMP_BUFFER_MS)
        } else {
            Long.MAX_VALUE
        }

        val target = when {
            current.requestedTargetMs in minSafePos..maxSafePos -> current.requestedTargetMs
            current.requestedTargetMs < minSafePos && minSafePos > 0L -> minSafePos
            current.requestedTargetMs > maxSafePos && maxSafePos < Long.MAX_VALUE -> maxSafePos
            else -> -1L
        }

        if (target > 0L) {
            current.clampedTargetMs = target
            current.outcome = VoxSeekOutcome.SUCCESS_CLAMPED
            logDiagnostic(VoxLogCode.LIVE_DVR_SEEK_CLAMP, current, "BehindLiveWindow: повторная перемотка в безопасное окно ($target)")
        } else {
            current.state = VoxSeekState.FAILED
            current.outcome = VoxSeekOutcome.FAILED_RECOVERABLE
            logDiagnostic(VoxLogCode.DVR_SEEK_UNAVAILABLE, current, "Окно эфира не позволяет восстановить DVR; возврат к прямому эфиру")
        }

        return target
    }

    @Synchronized
    fun onRecoveryAttempt(generation: Long, errorCategory: String): Boolean {
        val current = activeSeek ?: return false
        if (current.generation != generation) return false

        current.recoveryAttemptCount++
        current.errorCategory = errorCategory
        if (current.recoveryAttemptCount > MAX_RECOVERY_ATTEMPTS) {
            current.state = VoxSeekState.FAILED
            current.outcome = VoxSeekOutcome.FAILED_UNRECOVERABLE
            logDiagnostic("LIVE_DVR_SEEK_FAILED", current, "Превышен лимит восстановления при ошибке: $errorCategory")
            return false
        }

        current.state = VoxSeekState.RECOVERING
        logDiagnostic(VoxLogCode.LIVE_DVR_SEEK_RECOVERY, current, "Восстановление перемотки (попытка ${current.recoveryAttemptCount}/$MAX_RECOVERY_ATTEMPTS)")
        return true
    }

    @Synchronized
    fun hasActivePendingSeek(): Boolean {
        val current = activeSeek ?: return false
        if (current.state == VoxSeekState.PENDING || current.state == VoxSeekState.CLAMPED ||
            current.state == VoxSeekState.BUFFERING || current.state == VoxSeekState.RECOVERING) {
            val elapsed = System.currentTimeMillis() - current.requestedAtMs
            if (elapsed > SEEK_TIMEOUT_MS) {
                current.state = VoxSeekState.FAILED
                current.outcome = VoxSeekOutcome.FAILED_UNRECOVERABLE
                return false
            }
            return true
        }
        return false
    }

    @Synchronized
    fun getActiveSeek(): VoxPendingSeek? = activeSeek

    @Synchronized
    fun cancel(reason: String) {
        activeSeek?.let { current ->
            if (current.state == VoxSeekState.PENDING || current.state == VoxSeekState.CLAMPED ||
                current.state == VoxSeekState.BUFFERING || current.state == VoxSeekState.RECOVERING) {
                current.state = VoxSeekState.CANCELLED
                current.outcome = VoxSeekOutcome.USER_CANCELLED
                logDiagnostic("LIVE_DVR_SEEK_CANCELLED", current, reason)
            }
        }
    }

    @Synchronized
    fun reset() {
        activeSeek = null
    }

    fun logSeekUnavailable(videoId: String?, targetMs: Long, reason: String) {
        val ctx = mapOf(
            "video_id" to (videoId ?: "NONE"),
            "target_ms" to targetMs.toString(),
            "reason" to reason
        )
        VoxSafeLogger.w(VoxLogCategory.PLAYER, VoxLogCode.DVR_SEEK_UNAVAILABLE, "Перемотка DVR недоступна: $reason", ctx)
    }

    private fun logDiagnostic(logCode: String, seek: VoxPendingSeek, message: String) {
        val duration = System.currentTimeMillis() - seek.requestedAtMs
        val ctx = mapOf(
            "seek_request_id" to seek.seekRequestId,
            "generation" to seek.generation.toString(),
            "media_kind" to seek.mediaKind.name,
            "video_id" to (seek.videoId ?: "NONE"),
            "origin_pos_ms" to seek.fromPositionMs.toString(),
            "requested_target_ms" to seek.requestedTargetMs.toString(),
            "clamped_target_ms" to seek.clampedTargetMs.toString(),
            "window_start_ms" to seek.windowStartMs.toString(),
            "window_duration_ms" to seek.windowDurationMs.toString(),
            "recovery_attempt_count" to seek.recoveryAttemptCount.toString(),
            "manifest_refresh_count" to seek.manifestRefreshCount.toString(),
            "settled_position_ms" to seek.settledPositionMs.toString(),
            "state" to seek.state.name,
            "outcome" to (seek.outcome?.name ?: "IN_PROGRESS"),
            "duration_ms" to duration.toString(),
            "error_category" to (seek.errorCategory ?: "NONE")
        )
        VoxSafeLogger.i(VoxLogCategory.PLAYER, logCode, message, ctx)
    }
}

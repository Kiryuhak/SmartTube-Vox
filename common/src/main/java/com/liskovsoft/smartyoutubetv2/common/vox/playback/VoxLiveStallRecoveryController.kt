package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.misc.NetworkEngineRecoveryPolicy
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Состояния контролируемого автомата восстановления прямого эфира (Live Stall Recovery FSM).
 */
enum class VoxLiveStallState {
    IDLE,
    BUFFERING_OBSERVED,
    STALL_DETECTED,
    REFRESHING_MANIFEST,
    CLAMPING_LIVE_WINDOW,
    RESEEKING,
    RECREATING_SOURCE,
    NETWORK_FALLBACK,
    RECOVERED,
    FAILED;
}

/**
 * Контроллер защиты от зависаний и сбоев перемотки прямого эфира (Live Random Stalls & DVR Seek Recovery).
 *
 * Patch #17 / Prompt 023 Hardening:
 * 1. Отслеживает зависания live-буфера через таймер Watchdog (3.5с классификация, 6.0с запуск восстановления).
 * 2. Выполняет автоматическое ступенчатое восстановление: Refresh Manifest -> Reseek (с сохранением DVR позиции) -> Return to Live Edge -> Dialog.
 * 3. Предотвращает циклические переключения сетевых движков (Cronet/OkHttp).
 * 4. Валидирует границы DVR live window и восстанавливает воспроизведение при BehindLiveWindowException без зависаний.
 * 5. Нулевая телеметрия (Zero-PII) при логировании диагностических событий.
 */
class VoxLiveStallRecoveryController @JvmOverloads constructor(
    private val actionHandler: LiveRecoveryActionHandler,
    private val looper: Looper = Looper.getMainLooper()
) {

    interface LiveRecoveryActionHandler {
        fun onRefreshManifestRequested()
        fun onReseekRequested(targetPositionMs: Long)
        fun onRecreateSourceRequested()
        fun onNetworkFallbackRequested(nextEngine: Int)
        fun onReturnToLiveRequested()
        fun onPositionClamped(clampedPositionMs: Long) {}
        fun showMessage(message: String)
        fun hideBufferingSpinner() {}
        fun onRecoveryExhausted(
            targetPositionMs: Long,
            onReturnToLive: Runnable,
            onRetry: Runnable
        ) {}
    }

    companion object {
        const val STALL_CLASSIFICATION_DELAY_MS = 3_500L
        const val STALL_RECOVERY_TRIGGER_DELAY_MS = 6_000L
        const val STALL_STAGE_INTERVAL_MS = 5_000L
        const val MAX_WALL_CLOCK_RECOVERY_MS = 20_000L
        const val SAFE_LIVE_OFFSET_MS = 15_000L
        const val MIN_LIVE_WINDOW_CLAMP_BUFFER_MS = 2_000L
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val STALE_POSITION_MESSAGE = "Эта часть эфира уже недоступна"
        const val REFRESHING_MESSAGE = "Обновляем прямой эфир…"
    }

    private val handler = Handler(looper)
    private val networkRecoveryPolicy = NetworkEngineRecoveryPolicy()

    private var currentState: VoxLiveStallState = VoxLiveStallState.IDLE
    private var isLiveSession: Boolean = false
    private var recoveryAttemptsCount: Int = 0
    private var bufferingStartMs: Long = 0L
    private var recoveryStartWallClockMs: Long = 0L
    private var lastObservedLiveOffsetMs: Long = 0L
    private var lastObservedDurationMs: Long = 0L
    private var lastRequestedSeekPosMs: Long = -1L
    private var lastObservedWindowStartMs: Long = 0L
    private var currentGeneration: Long = 0L

    fun getState(): VoxLiveStallState = currentState
    fun getRecoveryAttempts(): Int = recoveryAttemptsCount
    fun getCurrentGeneration(): Long = currentGeneration
    fun getLastRequestedSeekPos(): Long = lastRequestedSeekPosMs

    private var lastObservedOffsetWasInvalid: Boolean = false

    @Synchronized
    fun onPlaybackStart(isLive: Boolean) {
        currentGeneration++
        reset()
        isLiveSession = isLive
        currentState = VoxLiveStallState.IDLE
    }

    @Synchronized
    fun onBufferingStarted(
        isLive: Boolean,
        currentPositionMs: Long,
        durationMs: Long,
        liveOffsetMs: Long
    ) {
        if (!isLive) return

        isLiveSession = true
        val sanity = VoxLiveOffsetSanityPolicy.validateLiveOffset(
            rawOffsetMs = liveOffsetMs,
            windowDurationMs = durationMs,
            isSeekable = true,
            isDynamic = true,
            currentPositionMs = currentPositionMs
        )
        lastObservedLiveOffsetMs = liveOffsetMs
        lastObservedOffsetWasInvalid = !sanity.isValid
        lastObservedDurationMs = durationMs
        bufferingStartMs = System.currentTimeMillis()

        if (currentState == VoxLiveStallState.IDLE) {
            currentState = VoxLiveStallState.BUFFERING_OBSERVED
            recoveryStartWallClockMs = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            val gen = currentGeneration
            handler.postDelayed({
                synchronized(this@VoxLiveStallRecoveryController) {
                    if (gen == currentGeneration) {
                        onStallClassified()
                    }
                }
            }, STALL_CLASSIFICATION_DELAY_MS)
            handler.postDelayed({
                synchronized(this@VoxLiveStallRecoveryController) {
                    if (gen == currentGeneration) {
                        onStallRecoveryTriggered(gen)
                    }
                }
            }, STALL_RECOVERY_TRIGGER_DELAY_MS)
        }
    }

    @Synchronized
    fun onBufferingEnded() {
        if (!isLiveSession) return

        handler.removeCallbacksAndMessages(null)

        if (currentState != VoxLiveStallState.IDLE) {
            val wasStalled = currentState != VoxLiveStallState.BUFFERING_OBSERVED
            currentState = VoxLiveStallState.RECOVERED
            networkRecoveryPolicy.onPlaybackProgress()
            if (wasStalled) {
                VoxSafeLogger.info(
                    VoxLogCategory.PLAYER,
                    VoxLogCode.LIVE_STALL_RECOVERY_SUCCESS,
                    "Live playback recovered from stall (attempts=$recoveryAttemptsCount)"
                )
            }
            recoveryAttemptsCount = 0
            recoveryStartWallClockMs = 0L
        }
        currentState = VoxLiveStallState.IDLE
    }

    @Synchronized
    fun onSeekRequested(
        requestedPosMs: Long,
        windowDurationMs: Long,
        windowStartMs: Long,
        isLive: Boolean
    ): Long {
        if (!isLive || windowDurationMs <= 0L) {
            return requestedPosMs
        }

        currentGeneration++
        handler.removeCallbacksAndMessages(null)

        currentState = VoxLiveStallState.CLAMPING_LIVE_WINDOW
        val minSafePos = if (windowStartMs > 0L) windowStartMs + 1_000L else 0L
        val maxSafePos = Math.max(minSafePos, windowDurationMs - MIN_LIVE_WINDOW_CLAMP_BUFFER_MS)

        val clamped = when {
            requestedPosMs < minSafePos -> {
                VoxSafeLogger.w(
                    VoxLogCategory.PLAYER,
                    VoxLogCode.LIVE_DVR_SEEK_CLAMP,
                    "DVR seek clamped to window start: requested=$requestedPosMs, minSafe=$minSafePos"
                )
                try {
                    actionHandler.showMessage(STALE_POSITION_MESSAGE)
                } catch (ignored: Exception) {}
                actionHandler.onPositionClamped(minSafePos)
                minSafePos
            }
            requestedPosMs > maxSafePos -> {
                VoxSafeLogger.w(
                    VoxLogCategory.PLAYER,
                    VoxLogCode.LIVE_DVR_SEEK_CLAMP,
                    "DVR seek clamped to window end: requested=$requestedPosMs, maxSafe=$maxSafePos"
                )
                actionHandler.onPositionClamped(maxSafePos)
                maxSafePos
            }
            else -> requestedPosMs
        }

        lastRequestedSeekPosMs = clamped
        lastObservedWindowStartMs = windowStartMs
        lastObservedDurationMs = windowDurationMs

        currentState = VoxLiveStallState.IDLE
        return clamped
    }

    @Synchronized
    fun getPendingSeekTarget(windowStartMs: Long, windowDurationMs: Long): Long {
        val coordinator = VoxPendingSeekCoordinator.instance()
        val active = coordinator.getActiveSeek()
        val gen = active?.generation ?: currentGeneration
        val target = coordinator.onBehindLiveWindow(windowStartMs, windowDurationMs, gen)
        if (target > 0L) {
            lastRequestedSeekPosMs = target
            return target
        }
        if (lastRequestedSeekPosMs > 0L && windowDurationMs > 0L) {
            val minSafePos = if (windowStartMs > 0L) windowStartMs + MIN_LIVE_WINDOW_CLAMP_BUFFER_MS else 0L
            val maxSafePos = Math.max(minSafePos, windowDurationMs - MIN_LIVE_WINDOW_CLAMP_BUFFER_MS)
            if (lastRequestedSeekPosMs in minSafePos..maxSafePos) {
                return lastRequestedSeekPosMs
            } else if (lastRequestedSeekPosMs < minSafePos && minSafePos > 0L) {
                return minSafePos
            }
        }
        return -1L
    }

    fun interface ReseekCallback {
        fun onReseek(targetPos: Long)
    }

    @Synchronized
    fun handleBehindLiveWindow(context: Context?, onReturnToLive: Runnable) {
        handleBehindLiveWindow(context, lastObservedWindowStartMs, lastObservedDurationMs, null, onReturnToLive)
    }

    @Synchronized
    fun handleBehindLiveWindow(
        context: Context?,
        windowStartMs: Long,
        windowDurationMs: Long,
        onReseek: ReseekCallback?,
        onReturnToLive: Runnable
    ) {
        currentGeneration++
        handler.removeCallbacksAndMessages(null)

        val pendingTarget = getPendingSeekTarget(windowStartMs, windowDurationMs)
        if (pendingTarget > 0L && onReseek != null) {
            VoxSafeLogger.w(
                VoxLogCategory.PLAYER,
                VoxLogCode.LIVE_DVR_SEEK_CLAMP,
                "BehindLiveWindow detected; reseeking to safe DVR position: $pendingTarget"
            )
            context?.let {
                actionHandler.showMessage(STALE_POSITION_MESSAGE)
            }
            actionHandler.onReseekRequested(pendingTarget)
            onReseek.onReseek(pendingTarget)
            return
        }

        VoxSafeLogger.w(
            VoxLogCategory.PLAYER,
            VoxLogCode.LIVE_BEHIND_WINDOW_RECOVERED,
            "BehindLiveWindow detected; recovering to live edge"
        )
        context?.let {
            val msg = it.getString(R.string.vox_live_return_to_live_prompt)
            actionHandler.showMessage(msg)
        }
        onReturnToLive.run()
    }

    @Synchronized
    fun handleNetworkTransportError(
        error: Throwable,
        currentEngine: Int,
        availableEngines: IntArray
    ): Boolean {
        if (!isLiveSession) return false

        val isTransportError = isConfirmedTransportError(error)
        if (!isTransportError) {
            return false
        }

        val nowMs = System.currentTimeMillis()
        val nextEngine = networkRecoveryPolicy.selectNextEngine(currentEngine, availableEngines, nowMs)
        if (nextEngine != currentEngine) {
            currentState = VoxLiveStallState.NETWORK_FALLBACK
            VoxSafeLogger.w(
                VoxLogCategory.PLAYER,
                VoxLogCode.LIVE_NETWORK_ENGINE_SWITCH,
                "Live network engine switched on confirmed transport error: $currentEngine -> $nextEngine"
            )
            actionHandler.onNetworkFallbackRequested(nextEngine)
            return true
        }

        return false
    }

    private fun onStallClassified() {
        if (currentState != VoxLiveStallState.BUFFERING_OBSERVED) return

        currentState = VoxLiveStallState.STALL_DETECTED
        val snapshot = VoxLivePlaybackMonitor.getMetricsSummary()
        VoxSafeLogger.w(
            VoxLogCategory.PLAYER,
            VoxLogCode.LIVE_STALL_CLASSIFIED,
            "Live stall classified at offsetMs=${snapshot.liveOffsetMs}, errorCategory=${snapshot.lastErrorCategory}"
        )
    }

    private fun onStallRecoveryTriggered(scheduledGen: Long = currentGeneration) {
        if (scheduledGen != currentGeneration) {
            return
        }
        if (currentState == VoxLiveStallState.IDLE || currentState == VoxLiveStallState.RECOVERED || currentState == VoxLiveStallState.FAILED) {
            return
        }

        val now = System.currentTimeMillis()
        val elapsedWallClockMs = if (recoveryStartWallClockMs > 0L) now - recoveryStartWallClockMs else 0L

        recoveryAttemptsCount++
        if (recoveryAttemptsCount > MAX_RECOVERY_ATTEMPTS || (elapsedWallClockMs >= MAX_WALL_CLOCK_RECOVERY_MS && recoveryAttemptsCount > 1)) {
            currentState = VoxLiveStallState.FAILED
            VoxSafeLogger.e(
                VoxLogCategory.PLAYER,
                VoxLogCode.LIVE_STALL_RECOVERY_FAILED,
                "Live stall recovery exceeded maximum attempts ($MAX_RECOVERY_ATTEMPTS) or wall-clock budget (${elapsedWallClockMs}ms)",
                null,
                null
            )
            actionHandler.hideBufferingSpinner()
            val exhaustedTarget = lastRequestedSeekPosMs
            actionHandler.onRecoveryExhausted(
                targetPositionMs = exhaustedTarget,
                onReturnToLive = Runnable {
                    synchronized(this@VoxLiveStallRecoveryController) {
                        reset()
                        actionHandler.onReturnToLiveRequested()
                    }
                },
                onRetry = Runnable {
                    synchronized(this@VoxLiveStallRecoveryController) {
                        reset()
                        if (exhaustedTarget > 0L) {
                            actionHandler.onReseekRequested(exhaustedTarget)
                        } else {
                            actionHandler.onRefreshManifestRequested()
                        }
                    }
                }
            )
            actionHandler.onRecreateSourceRequested()
            return
        }

        VoxSafeLogger.w(
            VoxLogCategory.PLAYER,
            VoxLogCode.LIVE_STALL_RECOVERY_ATTEMPT,
            "Executing live stall recovery step attempt=$recoveryAttemptsCount"
        )

        when (recoveryAttemptsCount) {
            1 -> {
                currentState = VoxLiveStallState.REFRESHING_MANIFEST
                try {
                    actionHandler.showMessage(REFRESHING_MESSAGE)
                } catch (ignored: Exception) {}
                actionHandler.onRefreshManifestRequested()
                val gen = currentGeneration
                handler.postDelayed({
                    synchronized(this@VoxLiveStallRecoveryController) {
                        if (gen == currentGeneration) {
                            onStallRecoveryTriggered(gen)
                        }
                    }
                }, STALL_STAGE_INTERVAL_MS)
            }
            2 -> {
                currentState = VoxLiveStallState.RESEEKING
                try {
                    actionHandler.showMessage("Восстанавливаем трансляцию…")
                } catch (ignored: Exception) {}
                val minSafePos = if (lastObservedWindowStartMs > 0L) lastObservedWindowStartMs + 1_000L else 0L
                val maxSafePos = Math.max(minSafePos, lastObservedDurationMs - MIN_LIVE_WINDOW_CLAMP_BUFFER_MS)
                if (lastRequestedSeekPosMs in minSafePos..maxSafePos) {
                    VoxSafeLogger.w(
                        VoxLogCategory.PLAYER,
                        VoxLogCode.LIVE_DVR_SEEK_CLAMP,
                        "Live stall step 2: reseeking to preserved DVR position $lastRequestedSeekPosMs"
                    )
                    actionHandler.onReseekRequested(lastRequestedSeekPosMs)
                } else {
                    val sanity = VoxLiveOffsetSanityPolicy.validateLiveOffset(
                        rawOffsetMs = lastObservedLiveOffsetMs,
                        windowDurationMs = lastObservedDurationMs,
                        isSeekable = true,
                        isDynamic = true,
                        currentPositionMs = 0L
                    )
                    if (!sanity.isValid || lastObservedOffsetWasInvalid) {
                        VoxSafeLogger.w(
                            VoxLogCategory.PLAYER,
                            VoxLogCode.LIVE_OFFSET_INVALID,
                            "Sanity check failed for reseek offset: returning directly to live edge"
                        )
                        actionHandler.onReturnToLiveRequested()
                    } else {
                        val target = VoxLiveOffsetSanityPolicy.computeSafeReseekTarget(
                            windowDurationMs = lastObservedDurationMs,
                            windowStartMs = 0L,
                            safeLiveOffsetMs = SAFE_LIVE_OFFSET_MS
                        )
                        actionHandler.onReseekRequested(target)
                    }
                }
                val gen = currentGeneration
                handler.postDelayed({
                    synchronized(this@VoxLiveStallRecoveryController) {
                        if (gen == currentGeneration) {
                            onStallRecoveryTriggered(gen)
                        }
                    }
                }, STALL_STAGE_INTERVAL_MS)
            }
            3 -> {
                currentState = VoxLiveStallState.RECREATING_SOURCE
                try {
                    actionHandler.showMessage("Перезагружаем источник…")
                } catch (ignored: Exception) {}
                actionHandler.onRecreateSourceRequested()
                val gen = currentGeneration
                handler.postDelayed({
                    synchronized(this@VoxLiveStallRecoveryController) {
                        if (gen == currentGeneration) {
                            onStallRecoveryTriggered(gen)
                        }
                    }
                }, 4_000L)
            }
            else -> {
                // Handled in bounds check
            }
        }
    }

    @Synchronized
    fun reset() {
        currentGeneration++
        handler.removeCallbacksAndMessages(null)
        currentState = VoxLiveStallState.IDLE
        recoveryAttemptsCount = 0
        bufferingStartMs = 0L
        recoveryStartWallClockMs = 0L
        lastObservedLiveOffsetMs = 0L
        lastObservedOffsetWasInvalid = false
        lastObservedDurationMs = 0L
        lastRequestedSeekPosMs = -1L
        lastObservedWindowStartMs = 0L
        networkRecoveryPolicy.reset()
    }

    private fun isConfirmedTransportError(error: Throwable?): Boolean {
        var curr = error
        while (curr != null) {
            if (curr is UnknownHostException || curr is SocketTimeoutException || curr is ConnectException) {
                return true
            }
            curr = curr.cause
        }
        return false
    }
}

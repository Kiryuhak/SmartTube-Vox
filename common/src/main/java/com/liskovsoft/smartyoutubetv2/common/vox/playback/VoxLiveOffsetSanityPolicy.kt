package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger

/**
 * Валидация вычисленного liveOffsetMs.
 * Предотвращает принятие некорректных решений восстановления при многодневных окнах трансляций (например 38 часов).
 */
object VoxLiveOffsetSanityPolicy {

    const val MAX_SANE_LIVE_OFFSET_MS = 86_400_000L // 24 hours
    const val DEFAULT_FALLBACK_LIVE_OFFSET_MS = 15_000L

    data class SanityResult(
        val isValid: Boolean,
        val sanitizedOffsetMs: Long,
        val invalidBucket: String?,
        val safeContext: Map<String, String>
    )

    fun validateLiveOffset(
        rawOffsetMs: Long,
        windowDurationMs: Long,
        isSeekable: Boolean,
        isDynamic: Boolean,
        currentPositionMs: Long
    ): SanityResult {
        val bucket = when {
            rawOffsetMs < 0L -> "NEGATIVE"
            rawOffsetMs > MAX_SANE_LIVE_OFFSET_MS -> "EXCESSIVE_OVER_24H"
            windowDurationMs in 1 until rawOffsetMs -> "EXCEEDS_WINDOW"
            else -> null
        }

        val isValid = (bucket == null)
        val sanitized = if (isValid) rawOffsetMs else DEFAULT_FALLBACK_LIVE_OFFSET_MS

        val context = mapOf(
            "offsetBucket" to (bucket ?: "VALID"),
            "windowDurationMs" to Math.max(0L, windowDurationMs).toString(),
            "seekable" to isSeekable.toString(),
            "dynamic" to isDynamic.toString(),
            "currentPositionMs" to Math.max(0L, currentPositionMs).toString()
        )

        if (!isValid) {
            VoxSafeLogger.w(
                VoxLogCategory.PLAYER,
                VoxLogCode.LIVE_OFFSET_INVALID,
                "Invalid live offset detected ($rawOffsetMs ms); ignored for recovery decisions",
                context
            )
        }

        return SanityResult(
            isValid = isValid,
            sanitizedOffsetMs = sanitized,
            invalidBucket = bucket,
            safeContext = context
        )
    }

    /**
     * Безопасная целевая позиция для re-seek к прямому эфиру.
     * Не допускает перемотки на недоступные участки за пределами доступного DVR-окна.
     */
    fun computeSafeReseekTarget(
        windowDurationMs: Long,
        windowStartMs: Long,
        safeLiveOffsetMs: Long = DEFAULT_FALLBACK_LIVE_OFFSET_MS
    ): Long {
        if (windowDurationMs <= 0L) return 0L
        val minSafe = if (windowStartMs > 0L) windowStartMs + 1_000L else 0L
        val target = windowDurationMs - safeLiveOffsetMs
        return target.coerceIn(minSafe, windowDurationMs)
    }
}

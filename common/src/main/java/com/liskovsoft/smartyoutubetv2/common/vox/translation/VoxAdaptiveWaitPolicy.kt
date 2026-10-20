/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Adaptive wait and timeout policy for VOT video translation jobs.
 *
 * Distinguishes between:
 * - HEALTHY_LONG_SYNTHESIS: Provider is actively computing, ETA decreasing or progress increasing.
 * - PROVIDER_STALLED: Provider accepted the job but progress has frozen for too long.
 * - UNSUPPORTED_DURATION: Video duration exceeds provider capabilities (e.g. > 4 hours).
 * - HARD_TIMEOUT: Job has exceeded the absolute upper bound duration.
 */
class VoxAdaptiveWaitPolicy(
    val videoDurationSec: Double,
    val initialTimeMs: Long = System.currentTimeMillis()
) {

    enum class Verdict {
        HEALTHY_WAITING,
        HEALTHY_LONG_SYNTHESIS,
        PROVIDER_STALLED,
        UNSUPPORTED_DURATION,
        HARD_TIMEOUT
    }

    enum class ProgressType {
        REAL_PROGRESS,
        HEARTBEAT_ONLY,
        STALL_DETECTED
    }

    data class EvaluationResult(
        val verdict: Verdict,
        val progressType: ProgressType,
        val currentSoftDeadlineMs: Long,
        val hardDeadlineMs: Long,
        val timeSinceLastMeaningfulProgressMs: Long,
        val consecutiveStalledCount: Int,
        val reason: String
    )

    companion object {
        /** Videos longer than 4 hours (14,400s) are not supported by the Yandex backend. */
        const val MAX_SUPPORTED_VIDEO_DURATION_SEC: Double = 14400.0

        /** Base stall timeout: if no real progress for 90s, the job is declared stalled. */
        const val BASE_STALL_TIMEOUT_MS: Long = 90_000L

        /** Default soft deadline for short video startup before synthesis begins. */
        const val DEFAULT_SOFT_DEADLINE_MS: Long = 60_000L

        /** Maximum consecutive identical ETA polls before declaring stall. */
        const val MAX_CONSECUTIVE_IDENTICAL_ETA_POLLS: Int = 12

        /** Maximum hard deadline bounds by video length. */
        const val HARD_DEADLINE_SHORT_MS: Long = 180_000L   // <= 15 min videos (3 min)
        const val HARD_DEADLINE_MEDIUM_MS: Long = 360_000L  // 15 - 60 min videos (6 min)
        const val HARD_DEADLINE_LONG_MS: Long = 600_000L    // 60 - 240 min videos (10 min)

        @JvmStatic
        fun computeHardDeadlineMs(durationSec: Double, startTimeMs: Long): Long {
            val maxAllowedMs = when {
                durationSec <= 900.0 -> HARD_DEADLINE_SHORT_MS
                durationSec <= 3600.0 -> HARD_DEADLINE_MEDIUM_MS
                else -> HARD_DEADLINE_LONG_MS
            }
            return startTimeMs + maxAllowedMs
        }

        @JvmStatic
        fun isDurationSupported(durationSec: Double): Boolean {
            return durationSec in 1.0..MAX_SUPPORTED_VIDEO_DURATION_SEC
        }
    }

    val hardDeadlineMs: Long = computeHardDeadlineMs(videoDurationSec, initialTimeMs)
    var softDeadlineMs: Long = initialTimeMs + DEFAULT_SOFT_DEADLINE_MS
        private set

    var lastMeaningfulProgressMs: Long = initialTimeMs
        private set
    var lastProgressPercent: Int = -1
        private set
    var lowestEtaSec: Int = Int.MAX_VALUE
        private set
    var lastEtaSec: Int = -1
        private set
    var lastStage: String? = null
        private set
    var consecutiveIdenticalEtaCount: Int = 0
        private set
    var isHealthyLongSynthesis: Boolean = false
        private set

    init {
        // Long videos naturally expect longer initial synthesis times
        if (videoDurationSec > 3600.0) {
            softDeadlineMs = Math.min(hardDeadlineMs, initialTimeMs + 180_000L)
        }
    }

    /**
     * Evaluates a progress poll update from the provider.
     */
    fun onPoll(
        percent: Int,
        remainingSec: Int,
        stage: String?,
        nowMs: Long = System.currentTimeMillis()
    ): EvaluationResult {
        // 1. Extreme video check: unsupported duration
        if (!isDurationSupported(videoDurationSec)) {
            return EvaluationResult(
                Verdict.UNSUPPORTED_DURATION,
                ProgressType.STALL_DETECTED,
                softDeadlineMs,
                hardDeadlineMs,
                nowMs - lastMeaningfulProgressMs,
                consecutiveIdenticalEtaCount,
                "Video duration (${videoDurationSec.toLong()}s) exceeds maximum supported limit (${MAX_SUPPORTED_VIDEO_DURATION_SEC.toLong()}s)"
            )
        }

        // 2. Hard absolute deadline check
        if (nowMs >= hardDeadlineMs) {
            return EvaluationResult(
                Verdict.HARD_TIMEOUT,
                ProgressType.STALL_DETECTED,
                softDeadlineMs,
                hardDeadlineMs,
                nowMs - lastMeaningfulProgressMs,
                consecutiveIdenticalEtaCount,
                "Hard deadline exceeded (${(nowMs - initialTimeMs) / 1000}s >= ${(hardDeadlineMs - initialTimeMs) / 1000}s)"
            )
        }

        // 3. Meaningful progress detection
        var hasMeaningfulProgress = false
        var reason = "Heartbeat poll"

        // Rule A: Progress percent increased
        if (percent in 1..98 && percent > lastProgressPercent) {
            hasMeaningfulProgress = true
            reason = "Progress increased from $lastProgressPercent% to $percent%"
            lastProgressPercent = percent
        }

        // Rule B: Remaining ETA decreased meaningfully
        if (remainingSec > 0) {
            if (lastEtaSec > 0 && remainingSec == lastEtaSec) {
                consecutiveIdenticalEtaCount++
            } else if (remainingSec < lowestEtaSec) {
                hasMeaningfulProgress = true
                reason = "ETA decreased to ${remainingSec}s (previous lowest: ${if (lowestEtaSec == Int.MAX_VALUE) "none" else lowestEtaSec.toString() + "s"})"
                lowestEtaSec = remainingSec
                consecutiveIdenticalEtaCount = 0
            } else if (remainingSec > lastEtaSec && lastEtaSec > 0) {
                // ETA increased: do not treat as progress, count towards stall if persistent
                consecutiveIdenticalEtaCount++
            } else {
                consecutiveIdenticalEtaCount = 0
            }
            lastEtaSec = remainingSec
        }

        // Rule C: Stage changed meaningfully (e.g. from WAITING to PROCESSING or UPLOADING)
        if (!stage.isNullOrEmpty() && stage != lastStage) {
            if (lastStage != null) {
                hasMeaningfulProgress = true
                reason = "Stage transitioned: $lastStage -> $stage"
            }
            lastStage = stage
        }

        // 4. Update deadlines if meaningful progress occurred
        if (hasMeaningfulProgress) {
            lastMeaningfulProgressMs = nowMs
            consecutiveIdenticalEtaCount = 0

            // If video is long and ETA is active, mark healthy long synthesis
            if (videoDurationSec >= 1800.0 || (remainingSec > 60)) {
                isHealthyLongSynthesis = true
            }

            // Extend soft deadline bounded by hard deadline
            val extendedDeltaMs = if (remainingSec > 0) {
                Math.max(BASE_STALL_TIMEOUT_MS, remainingSec * 1000L + 30_000L)
            } else {
                BASE_STALL_TIMEOUT_MS
            }
            softDeadlineMs = Math.min(hardDeadlineMs, nowMs + extendedDeltaMs)

            val verdict = if (isHealthyLongSynthesis) Verdict.HEALTHY_LONG_SYNTHESIS else Verdict.HEALTHY_WAITING
            return EvaluationResult(
                verdict,
                ProgressType.REAL_PROGRESS,
                softDeadlineMs,
                hardDeadlineMs,
                0L,
                0,
                reason
            )
        }

        // 5. Stalled check: no meaningful progress for too long or identical ETA polls exceeded
        val idleMs = nowMs - lastMeaningfulProgressMs
        if (idleMs >= BASE_STALL_TIMEOUT_MS || consecutiveIdenticalEtaCount >= MAX_CONSECUTIVE_IDENTICAL_ETA_POLLS) {
            val stallReason = if (consecutiveIdenticalEtaCount >= MAX_CONSECUTIVE_IDENTICAL_ETA_POLLS) {
                "Provider ETA frozen at ${lastEtaSec}s across $consecutiveIdenticalEtaCount polls"
            } else {
                "No meaningful progress for ${idleMs / 1000}s (threshold: ${BASE_STALL_TIMEOUT_MS / 1000}s)"
            }
            return EvaluationResult(
                Verdict.PROVIDER_STALLED,
                ProgressType.STALL_DETECTED,
                softDeadlineMs,
                hardDeadlineMs,
                idleMs,
                consecutiveIdenticalEtaCount,
                stallReason
            )
        }

        // 6. Healthy heartbeat
        val verdict = if (isHealthyLongSynthesis) Verdict.HEALTHY_LONG_SYNTHESIS else Verdict.HEALTHY_WAITING
        return EvaluationResult(
            verdict,
            ProgressType.HEARTBEAT_ONLY,
            softDeadlineMs,
            hardDeadlineMs,
            idleMs,
            consecutiveIdenticalEtaCount,
            "Heartbeat within active window (${idleMs / 1000}s since last progress)"
        )
    }
}

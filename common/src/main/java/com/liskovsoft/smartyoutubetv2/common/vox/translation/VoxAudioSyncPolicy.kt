/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import kotlin.math.abs

/**
 * Sync action decision types produced by [VoxAudioSyncPolicy].
 */
enum class VoxSyncActionType {
    /**
     * Follower clock is within acceptable tolerance; no action needed.
     */
    NOOP,

    /**
     * Master player is buffering; follower audio must pause immediately to avoid drifting ahead.
     */
    HOLD_BUFFERING,

    /**
     * Master player is paused; follower audio must pause.
     */
    PAUSE,

    /**
     * Master player is playing; follower audio must resume.
     */
    RESUME,

    /**
     * Master player changed playback speed; follower must update its playback rate.
     */
    SPEED_UPDATE,

    /**
     * Moderate drift requiring gentle realignment seek with cooldown hysteresis.
     */
    SOFT_SEEK,

    /**
     * Large drift or user seek requiring immediate realignment seek.
     */
    HARD_SEEK
}

/**
 * Result of evaluating audio synchronization between master (video) and follower (VOT audio).
 */
data class VoxAudioSyncDecision(
    val action: VoxSyncActionType,
    val targetPositionMs: Long = 0L,
    val targetSpeed: Float = 1.0f,
    val driftMs: Long = 0L,
    val reason: String
)

/**
 * Pure, deterministic AV sync and drift correction policy for VOT audio follower.
 *
 * Corresponds to VOT userscript 1.11.17 audio lifecycle and lipSync:
 * - Master is ALWAYS the main video clock.
 * - Follower audio tracks master position, speed, and playback state.
 * - Buffering on master immediately holds/pauses follower.
 * - Drift <= toleranceMs (default 150ms) is accepted without stutter (NOOP).
 * - Drift > hardSeekThresholdMs (default 1500ms) triggers immediate hard seek.
 * - Moderate drift (150ms..1500ms) triggers soft seek guarded by cooldown hysteresis.
 * - Target positions are always clamped to [0, durationMs].
 */
class VoxAudioSyncPolicy(
    val toleranceMs: Long = DEFAULT_TOLERANCE_MS,
    val hardSeekThresholdMs: Long = DEFAULT_HARD_SEEK_THRESHOLD_MS,
    val seekCooldownMs: Long = DEFAULT_SEEK_COOLDOWN_MS
) {
    companion object {
        const val DEFAULT_TOLERANCE_MS = 150L
        const val DEFAULT_HARD_SEEK_THRESHOLD_MS = 1500L
        const val DEFAULT_SEEK_COOLDOWN_MS = 3000L
    }

    /**
     * Evaluates sync between main video and follower VOT audio.
     */
    fun evaluate(
        mainPositionMs: Long,
        audioPositionMs: Long,
        mainIsPlaying: Boolean,
        mainIsBuffering: Boolean,
        audioIsPlaying: Boolean,
        videoDurationMs: Long = Long.MAX_VALUE,
        mainSpeed: Float = 1.0f,
        audioSpeed: Float = 1.0f,
        lastSeekTimeMs: Long = 0L,
        currentTimeMs: Long = 0L
    ): VoxAudioSyncDecision {
        val driftMs = mainPositionMs - audioPositionMs
        val absDrift = abs(driftMs)
        val clampedTargetMs = clampPosition(mainPositionMs, videoDurationMs)

        // 1. Buffering hold: follower must hold/pause when master is buffering
        if (mainIsBuffering) {
            return VoxAudioSyncDecision(
                action = if (audioIsPlaying) VoxSyncActionType.HOLD_BUFFERING else VoxSyncActionType.NOOP,
                driftMs = driftMs,
                targetPositionMs = clampedTargetMs,
                reason = "Master video is buffering - hold translation audio"
            )
        }

        // 2. Play/Pause state sync
        if (!mainIsPlaying) {
            return if (audioIsPlaying) {
                VoxAudioSyncDecision(
                    action = VoxSyncActionType.PAUSE,
                    driftMs = driftMs,
                    targetPositionMs = clampedTargetMs,
                    reason = "Master video is paused - pause translation audio"
                )
            } else {
                VoxAudioSyncDecision(
                    action = VoxSyncActionType.NOOP,
                    driftMs = driftMs,
                    targetPositionMs = clampedTargetMs,
                    reason = "Both master and translation audio are paused"
                )
            }
        }

        // Master is playing: if audio is not playing, resume follower
        if (!audioIsPlaying) {
            return VoxAudioSyncDecision(
                action = VoxSyncActionType.RESUME,
                driftMs = driftMs,
                targetPositionMs = clampedTargetMs,
                reason = "Master video is playing but translation audio is paused - resume"
            )
        }

        // 3. Playback speed matching
        if (abs(mainSpeed - audioSpeed) > 0.01f) {
            return VoxAudioSyncDecision(
                action = VoxSyncActionType.SPEED_UPDATE,
                targetSpeed = mainSpeed,
                driftMs = driftMs,
                targetPositionMs = clampedTargetMs,
                reason = "Speed mismatch: master=$mainSpeed, audio=$audioSpeed"
            )
        }

        // 4. Tolerance window: perfectly in sync
        if (absDrift <= toleranceMs) {
            return VoxAudioSyncDecision(
                action = VoxSyncActionType.NOOP,
                driftMs = driftMs,
                targetPositionMs = clampedTargetMs,
                reason = "Drift ($driftMs ms) within tolerance ($toleranceMs ms)"
            )
        }

        // 5. Large drift (> hardSeekThresholdMs) -> immediate hard seek
        if (absDrift > hardSeekThresholdMs) {
            return VoxAudioSyncDecision(
                action = VoxSyncActionType.HARD_SEEK,
                targetPositionMs = clampedTargetMs,
                driftMs = driftMs,
                reason = "Drift ($driftMs ms) exceeds hard threshold ($hardSeekThresholdMs ms)"
            )
        }

        // 6. Moderate drift (tolerance < absDrift <= hardSeekThreshold) with cooldown
        val timeSinceLastSeek = currentTimeMs - lastSeekTimeMs
        if (timeSinceLastSeek < seekCooldownMs && lastSeekTimeMs > 0L) {
            return VoxAudioSyncDecision(
                action = VoxSyncActionType.NOOP,
                driftMs = driftMs,
                targetPositionMs = clampedTargetMs,
                reason = "Cooldown active ($timeSinceLastSeek ms < $seekCooldownMs ms) - suppressing soft seek"
            )
        }

        return VoxAudioSyncDecision(
            action = VoxSyncActionType.SOFT_SEEK,
            targetPositionMs = clampedTargetMs,
            driftMs = driftMs,
            reason = "Moderate drift ($driftMs ms) requires soft realignment seek to $clampedTargetMs ms"
        )
    }

    /**
     * Clamps target seek position to valid range [0, durationMs].
     */
    fun clampPosition(targetPositionMs: Long, durationMs: Long): Long {
        val maxPos = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return targetPositionMs.coerceIn(0L, maxPos)
    }
}

/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoxAudioSyncPolicyTest {

    private lateinit var policy: VoxAudioSyncPolicy

    @Before
    fun setUp() {
        policy = VoxAudioSyncPolicy(
            toleranceMs = 150L,
            hardSeekThresholdMs = 1500L,
            seekCooldownMs = 3000L
        )
    }

    @Test
    fun test01_driftWithinTolerance_isNoOp() {
        val decision = policy.evaluate(
            mainPositionMs = 10000L,
            audioPositionMs = 10100L, // 100ms drift <= 150ms
            mainIsPlaying = true,
            mainIsBuffering = false,
            audioIsPlaying = true,
            videoDurationMs = 60000L
        )
        assertEquals(VoxSyncActionType.NOOP, decision.action)
        assertEquals(-100L, decision.driftMs)
    }

    @Test
    fun test02_mainBuffering_holdsFollowerAudio() {
        val decision = policy.evaluate(
            mainPositionMs = 25000L,
            audioPositionMs = 25000L,
            mainIsPlaying = true,
            mainIsBuffering = true, // Main video starts buffering
            audioIsPlaying = true,
            videoDurationMs = 60000L
        )
        assertEquals(VoxSyncActionType.HOLD_BUFFERING, decision.action)
    }

    @Test
    fun test03_mainBufferingWhenAudioAlreadyPaused_isNoOp() {
        val decision = policy.evaluate(
            mainPositionMs = 25000L,
            audioPositionMs = 25000L,
            mainIsPlaying = true,
            mainIsBuffering = true,
            audioIsPlaying = false, // Follower already paused
            videoDurationMs = 60000L
        )
        assertEquals(VoxSyncActionType.NOOP, decision.action)
    }

    @Test
    fun test04_mainPaused_pausesFollowerAudio() {
        val decision = policy.evaluate(
            mainPositionMs = 15000L,
            audioPositionMs = 15000L,
            mainIsPlaying = false, // Main video paused
            mainIsBuffering = false,
            audioIsPlaying = true,
            videoDurationMs = 60000L
        )
        assertEquals(VoxSyncActionType.PAUSE, decision.action)
    }

    @Test
    fun test05_mainPlayingFollowerPaused_resumesFollowerAudio() {
        val decision = policy.evaluate(
            mainPositionMs = 15000L,
            audioPositionMs = 15000L,
            mainIsPlaying = true, // Main playing
            mainIsBuffering = false,
            audioIsPlaying = false, // Follower was paused
            videoDurationMs = 60000L
        )
        assertEquals(VoxSyncActionType.RESUME, decision.action)
    }

    @Test
    fun test06_speedMismatch_triggersSpeedUpdate() {
        val decision = policy.evaluate(
            mainPositionMs = 20000L,
            audioPositionMs = 20050L,
            mainIsPlaying = true,
            mainIsBuffering = false,
            audioIsPlaying = true,
            videoDurationMs = 60000L,
            mainSpeed = 1.5f,
            audioSpeed = 1.0f
        )
        assertEquals(VoxSyncActionType.SPEED_UPDATE, decision.action)
        assertEquals(1.5f, decision.targetSpeed, 0.001f)
    }

    @Test
    fun test07_largeDrift_triggersHardSeek() {
        val decision = policy.evaluate(
            mainPositionMs = 45000L,
            audioPositionMs = 40000L, // 5000ms drift > 1500ms
            mainIsPlaying = true,
            mainIsBuffering = false,
            audioIsPlaying = true,
            videoDurationMs = 60000L
        )
        assertEquals(VoxSyncActionType.HARD_SEEK, decision.action)
        assertEquals(45000L, decision.targetPositionMs)
        assertEquals(5000L, decision.driftMs)
    }

    @Test
    fun test08_moderateDrift_outsideCooldown_triggersSoftSeek() {
        val decision = policy.evaluate(
            mainPositionMs = 30500L,
            audioPositionMs = 30000L, // 500ms drift (tolerance 150 < 500 <= 1500)
            mainIsPlaying = true,
            mainIsBuffering = false,
            audioIsPlaying = true,
            videoDurationMs = 60000L,
            lastSeekTimeMs = 1000L,
            currentTimeMs = 5000L // 4000ms elapsed > 3000ms cooldown
        )
        assertEquals(VoxSyncActionType.SOFT_SEEK, decision.action)
        assertEquals(30500L, decision.targetPositionMs)
    }

    @Test
    fun test09_moderateDrift_insideCooldown_suppressedWithNoOp() {
        val decision = policy.evaluate(
            mainPositionMs = 30500L,
            audioPositionMs = 30000L, // 500ms drift
            mainIsPlaying = true,
            mainIsBuffering = false,
            audioIsPlaying = true,
            videoDurationMs = 60000L,
            lastSeekTimeMs = 4000L,
            currentTimeMs = 5000L // only 1000ms elapsed < 3000ms cooldown
        )
        assertEquals(VoxSyncActionType.NOOP, decision.action)
        assertTrue(decision.reason.contains("Cooldown active"))
    }

    @Test
    fun test10_clamping_toVideoDuration() {
        val decision = policy.evaluate(
            mainPositionMs = 65000L, // Beyond video duration of 60000ms
            audioPositionMs = 50000L,
            mainIsPlaying = true,
            mainIsBuffering = false,
            audioIsPlaying = true,
            videoDurationMs = 60000L
        )
        assertEquals(VoxSyncActionType.HARD_SEEK, decision.action)
        assertEquals(60000L, decision.targetPositionMs) // Clamped to max duration
    }

    @Test
    fun test11_clamping_negativeToZero() {
        val clamped = policy.clampPosition(-500L, 60000L)
        assertEquals(0L, clamped)
    }
}

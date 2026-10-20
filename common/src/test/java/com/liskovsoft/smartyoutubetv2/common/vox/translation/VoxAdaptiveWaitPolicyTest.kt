/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxAdaptiveWaitPolicyTest {

    @Test
    fun testLongProviderProgressStaysHealthy() {
        val startTime = 1000000L
        val policy = VoxAdaptiveWaitPolicy(videoDurationSec = 9300.0, initialTimeMs = startTime)

        val progressSequence = listOf(10, 20, 45, 70, 90, 92, 94, 96, 98)
        var currentTime = startTime

        for (progress in progressSequence) {
            currentTime += 20_000L // 20s between polls
            val eval = policy.onPoll(percent = progress, remainingSec = -1, stage = "PROCESSING", nowMs = currentTime)
            assertTrue("Progress $progress% should be healthy",
                eval.verdict == VoxAdaptiveWaitPolicy.Verdict.HEALTHY_WAITING ||
                eval.verdict == VoxAdaptiveWaitPolicy.Verdict.HEALTHY_LONG_SYNTHESIS)
            assertEquals(VoxAdaptiveWaitPolicy.ProgressType.REAL_PROGRESS, eval.progressType)
        }
    }

    @Test
    fun testProviderStallDetectedWhenProgressFreezes() {
        val startTime = 1000000L
        val policy = VoxAdaptiveWaitPolicy(videoDurationSec = 600.0, initialTimeMs = startTime)

        // Initial progress at 45%
        var currentTime = startTime + 10_000L
        var eval = policy.onPoll(percent = 45, remainingSec = -1, stage = "PROCESSING", nowMs = currentTime)
        assertEquals(VoxAdaptiveWaitPolicy.Verdict.HEALTHY_WAITING, eval.verdict)

        // Multiple heartbeat polls with unchanged progress at 45%
        // Advance past BASE_STALL_TIMEOUT_MS (90s)
        currentTime += 95_000L
        eval = policy.onPoll(percent = 45, remainingSec = -1, stage = "PROCESSING", nowMs = currentTime)

        assertEquals(VoxAdaptiveWaitPolicy.Verdict.PROVIDER_STALLED, eval.verdict)
        assertEquals(VoxAdaptiveWaitPolicy.ProgressType.STALL_DETECTED, eval.progressType)
    }

    @Test
    fun testEtaDecreasingExtendsSoftDeadlineHealthily() {
        val startTime = 1000000L
        // 2.5 hour podcast
        val policy = VoxAdaptiveWaitPolicy(videoDurationSec = 9300.0, initialTimeMs = startTime)

        val etaSequence = listOf(300, 240, 180, 120, 60, 20)
        var currentTime = startTime

        for (eta in etaSequence) {
            currentTime += 25_000L
            val eval = policy.onPoll(percent = -1, remainingSec = eta, stage = "WAITING", nowMs = currentTime)
            assertEquals(VoxAdaptiveWaitPolicy.Verdict.HEALTHY_LONG_SYNTHESIS, eval.verdict)
            assertEquals(VoxAdaptiveWaitPolicy.ProgressType.REAL_PROGRESS, eval.progressType)
            // Soft deadline must be greater than current time
            assertTrue(eval.currentSoftDeadlineMs > currentTime)
            // But never exceed hard deadline
            assertTrue(eval.currentSoftDeadlineMs <= eval.hardDeadlineMs)
        }
    }

    @Test
    fun testEtaGrowingOrFreezingDetectsStall() {
        val startTime = 1000000L
        val policy = VoxAdaptiveWaitPolicy(videoDurationSec = 1200.0, initialTimeMs = startTime)

        var currentTime = startTime
        // Initial ETA
        policy.onPoll(percent = -1, remainingSec = 60, stage = "WAITING", nowMs = currentTime)

        // ETA increases: 120 -> 240 -> 480 without progress
        val growingEtas = listOf(120, 240, 480, 600, 720, 800, 900, 1000, 1100, 1200, 1300, 1400, 1500)
        var lastEval: VoxAdaptiveWaitPolicy.EvaluationResult? = null
        for (eta in growingEtas) {
            currentTime += 10_000L
            lastEval = policy.onPoll(percent = -1, remainingSec = eta, stage = "WAITING", nowMs = currentTime)
        }

        assertEquals(VoxAdaptiveWaitPolicy.Verdict.PROVIDER_STALLED, lastEval?.verdict)
    }

    @Test
    fun testExtremeDurationRejectedImmediately() {
        val startTime = 1000000L
        // 16 hours video (57600s)
        val policy = VoxAdaptiveWaitPolicy(videoDurationSec = 57600.0, initialTimeMs = startTime)

        val eval = policy.onPoll(percent = 0, remainingSec = -1, stage = "REQUESTING", nowMs = startTime + 1000L)
        assertEquals(VoxAdaptiveWaitPolicy.Verdict.UNSUPPORTED_DURATION, eval.verdict)
        assertEquals(VoxAdaptiveWaitPolicy.ProgressType.STALL_DETECTED, eval.progressType)
    }

    @Test
    fun testHardDeadlineIsNeverExceeded() {
        val startTime = 1000000L
        val policy = VoxAdaptiveWaitPolicy(videoDurationSec = 300.0, initialTimeMs = startTime)
        val hardDeadline = policy.hardDeadlineMs

        // Attempting to poll after hard deadline
        val eval = policy.onPoll(percent = 50, remainingSec = 10, stage = "PROCESSING", nowMs = hardDeadline + 1000L)
        assertEquals(VoxAdaptiveWaitPolicy.Verdict.HARD_TIMEOUT, eval.verdict)
    }

    @Test
    fun testDurationBoundaryFourHours() {
        // Exactly 4 hours (14,400s) is allowed
        assertTrue(VoxAdaptiveWaitPolicy.isDurationSupported(14400.0))
        assertTrue(VoxAdaptiveWaitPolicy.isDurationSupported(14399.0))
        assertTrue(VoxAdaptiveWaitPolicy.isDurationSupported(1.0))

        // Exceeding 4 hours is rejected
        assertFalse(VoxAdaptiveWaitPolicy.isDurationSupported(14400.1))
        assertFalse(VoxAdaptiveWaitPolicy.isDurationSupported(14401.0))
        assertFalse(VoxAdaptiveWaitPolicy.isDurationSupported(57600.0))
        assertFalse(VoxAdaptiveWaitPolicy.isDurationSupported(0.0))
        assertFalse(VoxAdaptiveWaitPolicy.isDurationSupported(-1.0))

        val policyBoundary = VoxAdaptiveWaitPolicy(videoDurationSec = 14400.0, initialTimeMs = 1000L)
        val evalBoundary = policyBoundary.onPoll(percent = 10, remainingSec = -1, stage = "PROCESSING", nowMs = 2000L)
        assertEquals(VoxAdaptiveWaitPolicy.Verdict.HEALTHY_LONG_SYNTHESIS, evalBoundary.verdict)

        val policyExceeded = VoxAdaptiveWaitPolicy(videoDurationSec = 14401.0, initialTimeMs = 1000L)
        val evalExceeded = policyExceeded.onPoll(percent = 10, remainingSec = -1, stage = "PROCESSING", nowMs = 2000L)
        assertEquals(VoxAdaptiveWaitPolicy.Verdict.UNSUPPORTED_DURATION, evalExceeded.verdict)
    }
}

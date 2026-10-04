package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxLiveTranslationDelayPolicyTest {

    @Test
    fun testDefaultBoundsForModes() {
        val autoBounds = VoxLiveTranslationDelayPolicy.getBounds(VoxLiveDelayMode.AUTO)
        assertEquals(12_000L, autoBounds.minDelayMs)
        assertEquals(18_000L, autoBounds.targetDelayMs)
        assertEquals(35_000L, autoBounds.maxDelayMs)
        assertEquals(4_000L, autoBounds.rebufferThresholdMs)
        assertEquals(10_000L, autoBounds.resumeBufferThresholdMs)

        val lowLatencyBounds = VoxLiveTranslationDelayPolicy.getBounds(VoxLiveDelayMode.LOW_LATENCY)
        assertEquals(8_000L, lowLatencyBounds.minDelayMs)
        assertEquals(12_000L, lowLatencyBounds.targetDelayMs)
        assertEquals(25_000L, lowLatencyBounds.maxDelayMs)

        val stableBounds = VoxLiveTranslationDelayPolicy.getBounds(VoxLiveDelayMode.STABLE)
        assertEquals(20_000L, stableBounds.minDelayMs)
        assertEquals(30_000L, stableBounds.targetDelayMs)
        assertEquals(60_000L, stableBounds.maxDelayMs)
    }

    @Test
    fun testDynamicDelayIncreaseOnLowBufferOrUnderruns() {
        // Low buffer increases delay by 3s
        val newDelay = VoxLiveTranslationDelayPolicy.calculateNextDelay(
            currentDelayMs = 18_000L,
            bufferAheadMs = 2_000L, // below 4s rebuffer threshold
            mode = VoxLiveDelayMode.AUTO,
            consecutiveUnderruns = 0
        )
        assertEquals(21_000L, newDelay)

        // Consecutive underruns penalty
        val penaltyDelay = VoxLiveTranslationDelayPolicy.calculateNextDelay(
            currentDelayMs = 18_000L,
            bufferAheadMs = 1_000L,
            mode = VoxLiveDelayMode.AUTO,
            consecutiveUnderruns = 2
        )
        assertEquals(26_000L, penaltyDelay)
    }

    @Test
    fun testDynamicDelayDecreaseTowardsTarget() {
        val reducedDelay = VoxLiveTranslationDelayPolicy.calculateNextDelay(
            currentDelayMs = 25_000L,
            bufferAheadMs = 22_000L, // well above resumeBufferThreshold + 10s
            mode = VoxLiveDelayMode.AUTO,
            consecutiveUnderruns = 0
        )
        assertEquals(24_000L, reducedDelay)
    }

    @Test
    fun testDelayBoundsEnforcement() {
        val cappedMax = VoxLiveTranslationDelayPolicy.calculateNextDelay(
            currentDelayMs = 34_000L,
            bufferAheadMs = 1_000L,
            mode = VoxLiveDelayMode.AUTO,
            consecutiveUnderruns = 5
        )
        assertEquals(35_000L, cappedMax)

        val cappedMin = VoxLiveTranslationDelayPolicy.calculateNextDelay(
            currentDelayMs = 5_000L,
            bufferAheadMs = 25_000L,
            mode = VoxLiveDelayMode.AUTO,
            consecutiveUnderruns = 0
        )
        assertEquals(12_000L, cappedMin)
    }

    @Test
    fun testRebufferPredicates() {
        assertTrue(VoxLiveTranslationDelayPolicy.shouldRebuffer(2_000L, VoxLiveDelayMode.AUTO))
        assertFalse(VoxLiveTranslationDelayPolicy.shouldRebuffer(5_000L, VoxLiveDelayMode.AUTO))

        assertFalse(VoxLiveTranslationDelayPolicy.canResumeAfterRebuffer(8_000L, VoxLiveDelayMode.AUTO))
        assertTrue(VoxLiveTranslationDelayPolicy.canResumeAfterRebuffer(11_000L, VoxLiveDelayMode.AUTO))
    }
}

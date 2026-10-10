package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LiveOffsetSanityTest {

    private lateinit var controller: VoxLiveStallRecoveryController
    private var returnToLiveCalled = false
    private var reseekTarget = -1L

    private val actionHandler = object : VoxLiveStallRecoveryController.LiveRecoveryActionHandler {
        override fun onRefreshManifestRequested() {}
        override fun onReseekRequested(targetPositionMs: Long) {
            reseekTarget = targetPositionMs
        }
        override fun onRecreateSourceRequested() {}
        override fun onNetworkFallbackRequested(nextEngine: Int) {}
        override fun onReturnToLiveRequested() {
            returnToLiveCalled = true
        }
        override fun showMessage(message: String) {}
    }

    @Before
    fun setUp() {
        controller = VoxLiveStallRecoveryController(actionHandler, Looper.getMainLooper())
        returnToLiveCalled = false
        reseekTarget = -1L
    }

    @Test
    fun testValidLiveOffsetAccepted() {
        val res = VoxLiveOffsetSanityPolicy.validateLiveOffset(
            rawOffsetMs = 20_000L,
            windowDurationMs = 120_000L,
            isSeekable = true,
            isDynamic = true,
            currentPositionMs = 100_000L
        )
        assertTrue(res.isValid)
        assertEquals(20_000L, res.sanitizedOffsetMs)
        assertEquals(null, res.invalidBucket)
    }

    @Test
    fun testNegativeLiveOffsetSanitized() {
        val res = VoxLiveOffsetSanityPolicy.validateLiveOffset(
            rawOffsetMs = -5_000L,
            windowDurationMs = 120_000L,
            isSeekable = true,
            isDynamic = true,
            currentPositionMs = 100_000L
        )
        assertFalse(res.isValid)
        assertEquals("NEGATIVE", res.invalidBucket)
        assertEquals(VoxLiveOffsetSanityPolicy.DEFAULT_FALLBACK_LIVE_OFFSET_MS, res.sanitizedOffsetMs)
    }

    @Test
    fun testExcessiveLiveOffsetOver24hSanitized() {
        // Real bug from report VOX-A-7C4082: 137,000,000 ms (~38 hours)
        val excessiveOffset = 137_000_000L
        val res = VoxLiveOffsetSanityPolicy.validateLiveOffset(
            rawOffsetMs = excessiveOffset,
            windowDurationMs = 200_000_000L,
            isSeekable = true,
            isDynamic = true,
            currentPositionMs = 63_000_000L
        )
        assertFalse(res.isValid)
        assertEquals("EXCESSIVE_OVER_24H", res.invalidBucket)
        assertEquals(VoxLiveOffsetSanityPolicy.DEFAULT_FALLBACK_LIVE_OFFSET_MS, res.sanitizedOffsetMs)
    }

    @Test
    fun testOffsetExceedingWindowSanitized() {
        val res = VoxLiveOffsetSanityPolicy.validateLiveOffset(
            rawOffsetMs = 50_000L,
            windowDurationMs = 30_000L,
            isSeekable = true,
            isDynamic = true,
            currentPositionMs = 0L
        )
        assertFalse(res.isValid)
        assertEquals("EXCEEDS_WINDOW", res.invalidBucket)
        assertEquals(VoxLiveOffsetSanityPolicy.DEFAULT_FALLBACK_LIVE_OFFSET_MS, res.sanitizedOffsetMs)
    }

    @Test
    fun testComputeSafeReseekTarget() {
        // Normal window: duration 120s, offset 15s -> target 105s
        val target = VoxLiveOffsetSanityPolicy.computeSafeReseekTarget(
            windowDurationMs = 120_000L,
            windowStartMs = 0L,
            safeLiveOffsetMs = 15_000L
        )
        assertEquals(105_000L, target)

        // Clamped by window start
        val targetClamped = VoxLiveOffsetSanityPolicy.computeSafeReseekTarget(
            windowDurationMs = 20_000L,
            windowStartMs = 10_000L,
            safeLiveOffsetMs = 15_000L
        )
        // 20_000 - 15_000 = 5_000, but minSafe is 10_000 + 1_000 = 11_000
        assertEquals(11_000L, targetClamped)

        // Zero duration
        val targetZero = VoxLiveOffsetSanityPolicy.computeSafeReseekTarget(0L, 0L)
        assertEquals(0L, targetZero)
    }

    @Test
    fun testControllerRecoversToLiveEdgeWhenOffsetIsExcessive() {
        controller.onPlaybackStart(isLive = true)

        // Step 1: refresh manifest
        controller.onBufferingStarted(true, 100_000L, 120_000L, 137_000_000L)
        shadowOf(Looper.getMainLooper()).idleFor(6_100L, TimeUnit.MILLISECONDS)
        assertEquals(VoxLiveStallState.REFRESHING_MANIFEST, controller.getState())

        // Step 2: reseek attempt with 137M ms offset should NOT reseek into the void, but call onReturnToLiveRequested
        controller.onBufferingStarted(true, 100_000L, 120_000L, 137_000_000L)
        shadowOf(Looper.getMainLooper()).idleFor(6_100L, TimeUnit.MILLISECONDS)
        assertEquals(VoxLiveStallState.RESEEKING, controller.getState())
        assertTrue(returnToLiveCalled)
        assertEquals(-1L, reseekTarget)
    }
}

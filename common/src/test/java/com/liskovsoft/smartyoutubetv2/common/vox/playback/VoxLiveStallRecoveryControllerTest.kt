package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.content.Context
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class VoxLiveStallRecoveryControllerTest {

    private lateinit var context: Context
    private lateinit var controller: VoxLiveStallRecoveryController
    private lateinit var actionHandler: TestLiveRecoveryActionHandler

    private class TestLiveRecoveryActionHandler : VoxLiveStallRecoveryController.LiveRecoveryActionHandler {
        var refreshManifestCallCount = 0
        var reseekCallCount = 0
        var reseekTargetPos = -1L
        var recreateSourceCallCount = 0
        var networkFallbackCallCount = 0
        var networkFallbackEngine = -1
        var returnToLiveCallCount = 0
        var lastMessage: String? = null

        override fun onRefreshManifestRequested() {
            refreshManifestCallCount++
        }

        override fun onReseekRequested(targetPositionMs: Long) {
            reseekCallCount++
            reseekTargetPos = targetPositionMs
        }

        override fun onRecreateSourceRequested() {
            recreateSourceCallCount++
        }

        override fun onNetworkFallbackRequested(nextEngine: Int) {
            networkFallbackCallCount++
            networkFallbackEngine = nextEngine
        }

        override fun onReturnToLiveRequested() {
            returnToLiveCallCount++
        }

        override fun showMessage(message: String) {
            lastMessage = message
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        actionHandler = TestLiveRecoveryActionHandler()
        controller = VoxLiveStallRecoveryController(actionHandler, Looper.getMainLooper())
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(VoxLiveStallState.IDLE, controller.getState())
        assertEquals(0, controller.getRecoveryAttempts())
    }

    @Test
    fun testBufferingTransitionsAndTimers() {
        controller.onPlaybackStart(isLive = true)
        assertEquals(VoxLiveStallState.IDLE, controller.getState())

        controller.onBufferingStarted(
            isLive = true,
            currentPositionMs = 100_000L,
            durationMs = 120_000L,
            liveOffsetMs = 20_000L
        )
        assertEquals(VoxLiveStallState.BUFFERING_OBSERVED, controller.getState())

        // Advance looper to classify stall at 3.5s
        shadowOf(Looper.getMainLooper()).idleFor(3_600L, TimeUnit.MILLISECONDS)
        assertEquals(VoxLiveStallState.STALL_DETECTED, controller.getState())

        // Advance looper to trigger first recovery step at 6.0s (total +2.5s)
        shadowOf(Looper.getMainLooper()).idleFor(2_500L, TimeUnit.MILLISECONDS)
        assertEquals(VoxLiveStallState.REFRESHING_MANIFEST, controller.getState())
        assertEquals(1, actionHandler.refreshManifestCallCount)
        assertEquals(1, controller.getRecoveryAttempts())

        // On playback resume
        controller.onBufferingEnded()
        assertEquals(VoxLiveStallState.IDLE, controller.getState())
        assertEquals(0, controller.getRecoveryAttempts())
    }

    @Test
    fun testStagedRecoveryStepsOnPersistentStall() {
        controller.onPlaybackStart(isLive = true)

        // Trigger step 1
        controller.onBufferingStarted(true, 100_000L, 120_000L, 20_000L)
        shadowOf(Looper.getMainLooper()).idleFor(6_100L, TimeUnit.MILLISECONDS)
        assertEquals(1, actionHandler.refreshManifestCallCount)
        assertEquals(VoxLiveStallState.REFRESHING_MANIFEST, controller.getState())

        // Re-buffer for step 2 (Reseek)
        controller.onBufferingStarted(true, 100_000L, 120_000L, 20_000L)
        shadowOf(Looper.getMainLooper()).idleFor(6_100L, TimeUnit.MILLISECONDS)
        assertEquals(1, actionHandler.reseekCallCount)
        assertEquals(120_000L - VoxLiveStallRecoveryController.SAFE_LIVE_OFFSET_MS, actionHandler.reseekTargetPos)
        assertEquals(VoxLiveStallState.RESEEKING, controller.getState())

        // Re-buffer for step 3 (Recreate source)
        controller.onBufferingStarted(true, 100_000L, 120_000L, 20_000L)
        shadowOf(Looper.getMainLooper()).idleFor(6_100L, TimeUnit.MILLISECONDS)
        assertEquals(1, actionHandler.recreateSourceCallCount)
        assertEquals(VoxLiveStallState.RECREATING_SOURCE, controller.getState())
    }

    @Test
    fun testLiveDvrSeekClampingWindowBoundaries() {
        val windowDuration = 100_000L
        val windowStart = 10_000L

        // In bounds seek
        val validSeek = controller.onSeekRequested(50_000L, windowDuration, windowStart, true)
        assertEquals(50_000L, validSeek)

        // Seek before start -> clamped to windowStart + 1000
        val clampedStart = controller.onSeekRequested(5_000L, windowDuration, windowStart, true)
        assertEquals(windowStart + 1_000L, clampedStart)

        // Seek beyond end -> clamped to windowDuration - 2000
        val clampedEnd = controller.onSeekRequested(99_500L, windowDuration, windowStart, true)
        assertEquals(windowDuration - VoxLiveStallRecoveryController.MIN_LIVE_WINDOW_CLAMP_BUFFER_MS, clampedEnd)

        // Non-live seek remains unclamped
        val nonLiveSeek = controller.onSeekRequested(99_500L, windowDuration, windowStart, false)
        assertEquals(99_500L, nonLiveSeek)
    }

    @Test
    fun testBehindLiveWindowHandling() {
        var returnToLiveInvoked = false
        controller.handleBehindLiveWindow(context) {
            returnToLiveInvoked = true
        }

        assertTrue(returnToLiveInvoked)
        val expectedMsg = context.getString(com.liskovsoft.smartyoutubetv2.common.R.string.vox_live_return_to_live_prompt)
        assertEquals(expectedMsg, actionHandler.lastMessage)
    }

    @Test
    fun testNetworkTransportErrorFallback() {
        controller.onPlaybackStart(isLive = true)

        val engines = intArrayOf(0, 1, 2)
        val transportError = SocketTimeoutException("Read timed out")
        // First error attempts in-place reconnect to avoid thrashing
        val firstHandled = controller.handleNetworkTransportError(transportError, 0, engines)
        assertFalse(firstHandled)

        // Repeated transport error triggers fallback to next engine
        val secondHandled = controller.handleNetworkTransportError(transportError, 0, engines)
        assertTrue(secondHandled)
        assertEquals(1, actionHandler.networkFallbackCallCount)
        assertEquals(VoxLiveStallState.NETWORK_FALLBACK, controller.getState())

        // Non-transport error should not trigger network engine switch
        val genericError = IllegalArgumentException("Bad argument")
        val notHandled = controller.handleNetworkTransportError(genericError, 0, engines)
        assertFalse(notHandled)
    }
}

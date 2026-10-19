package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.net.ConnectException

@RunWith(RobolectricTestRunner::class)
class LiveSeekRecoveryPolicyTest {

    private lateinit var controller: VoxLiveStallRecoveryController
    private var manifestRefreshed = false
    private var reseekTargetMs: Long = -1L
    private var sourceRecreated = false
    private var fallbackEngine: Int = -1
    private var lastMessage: String? = null

    private var clampedPosition: Long = -1L

    private val actionHandler = object : VoxLiveStallRecoveryController.LiveRecoveryActionHandler {
        override fun onRefreshManifestRequested() {
            manifestRefreshed = true
        }

        override fun onReseekRequested(targetPositionMs: Long) {
            reseekTargetMs = targetPositionMs
        }

        override fun onRecreateSourceRequested() {
            sourceRecreated = true
        }

        override fun onNetworkFallbackRequested(nextEngine: Int) {
            fallbackEngine = nextEngine
        }

        override fun onReturnToLiveRequested() {}

        override fun onPositionClamped(clampedPositionMs: Long) {
            clampedPosition = clampedPositionMs
        }

        override fun showMessage(message: String) {
            lastMessage = message
        }
    }

    @Before
    fun setUp() {
        controller = VoxLiveStallRecoveryController(actionHandler, Looper.getMainLooper())
        manifestRefreshed = false
        reseekTargetMs = -1L
        sourceRecreated = false
        fallbackEngine = -1
        clampedPosition = -1L
        lastMessage = null
    }

    // --- Scenario A: seek target inside live window -> no clamp ---
    @Test
    fun testA_seekTargetInsideLiveWindow_noClamp() {
        val validPos = 200_000L
        val clamped = controller.onSeekRequested(
            requestedPosMs = validPos,
            windowDurationMs = 300_000L,
            windowStartMs = 100_000L,
            isLive = true
        )
        assertEquals(validPos, clamped)
        assertEquals(-1L, clampedPosition)
        assertNull(lastMessage)
    }

    // --- Scenario B: target before window start -> nearest available (start + safety margin) ---
    @Test
    fun testB_targetBeforeWindowStart_clampedToStartWithSafetyMargin() {
        val requestedBeforeWindow = 50_000L
        val clamped = controller.onSeekRequested(
            requestedPosMs = requestedBeforeWindow,
            windowDurationMs = 300_000L,
            windowStartMs = 100_000L,
            isLive = true
        )
        // Must clamp to minSafePos = 100_000 + 1_000 = 101_000ms
        assertEquals(101_000L, clamped)
        assertEquals(101_000L, clampedPosition)
        assertEquals(VoxLiveStallRecoveryController.STALE_POSITION_MESSAGE, lastMessage)
    }

    // --- Scenario C: target after allowed end -> clamp to maxSafePos ---
    @Test
    fun testC_targetAfterAllowedEnd_clampedToMaxSafePos() {
        val requestedPastEnd = 310_000L
        val clamped = controller.onSeekRequested(
            requestedPosMs = requestedPastEnd,
            windowDurationMs = 300_000L,
            windowStartMs = 100_000L,
            isLive = true
        )
        // Must clamp to windowDurationMs - MIN_LIVE_WINDOW_CLAMP_BUFFER_MS (300_000 - 2_000 = 298_000ms)
        assertEquals(298_000L, clamped)
        assertEquals(298_000L, clampedPosition)
    }

    // --- Scenario D: timeline refresh changes window -> recalculates new window bounds ---
    @Test
    fun testD_timelineRefreshChangesWindow_recalculatesNewWindowBounds() {
        // Window 1: 100_000 to 300_000
        val pos = 120_000L
        val clamped1 = controller.onSeekRequested(pos, 300_000L, 100_000L, true)
        assertEquals(pos, clamped1)

        // After timeline refresh: window advances forward to 150_000..350_000
        // Seeking to old position 120_000 is now before windowStart!
        val clamped2 = controller.onSeekRequested(pos, 350_000L, 150_000L, true)
        assertEquals(151_000L, clamped2)
        assertEquals(151_000L, clampedPosition)
        assertEquals(VoxLiveStallRecoveryController.STALE_POSITION_MESSAGE, lastMessage)
    }

    // --- Scenario E: stale generation callback -> ignored ---
    @Test
    fun testE_staleGenerationCallback_ignored() {
        controller.onPlaybackStart(true)
        controller.onBufferingStarted(true, 10_000L, 60_000L, 5_000L)
        val genBeforeSeek = controller.getCurrentGeneration()

        // User performs seek before timer fires -> increments generation
        controller.onSeekRequested(30_000L, 60_000L, 0L, true)
        assertTrue(controller.getCurrentGeneration() > genBeforeSeek)

        // Advance looper to when old timer would have fired
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()

        // Manifest refresh from stale callback must NOT be invoked
        assertFalse(manifestRefreshed)
        assertEquals(0, controller.getRecoveryAttempts())
    }

    // --- Scenario F: recovery attempts exceed limit -> terminate, no endless loop ---
    @Test
    fun testF_recoveryAttemptsExceedLimit_terminateNoEndlessLoop() {
        controller.onPlaybackStart(true)

        // Attempt 1 -> REFRESHING_MANIFEST
        controller.onBufferingStarted(true, 10_000L, 60_000L, 5_000L)
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertEquals(1, controller.getRecoveryAttempts())
        assertTrue(manifestRefreshed)

        // Attempt 2 -> RESEEKING
        controller.onBufferingStarted(true, 10_000L, 60_000L, 5_000L)
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertEquals(2, controller.getRecoveryAttempts())
        assertTrue(reseekTargetMs > 0)

        // Attempt 3 -> RECREATING_SOURCE
        controller.onBufferingStarted(true, 10_000L, 60_000L, 5_000L)
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertEquals(3, controller.getRecoveryAttempts())
        assertTrue(sourceRecreated)

        // Attempt 4 -> FAILED (bounded retry terminates, no endless loop)
        controller.onBufferingStarted(true, 10_000L, 60_000L, 5_000L)
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertEquals(VoxLiveStallState.FAILED, controller.getState())
        assertEquals(4, controller.getRecoveryAttempts())
    }

    // --- Scenario G: successful recovery -> watchdog cancelled ---
    @Test
    fun testG_successfulRecovery_watchdogCancelled() {
        controller.onPlaybackStart(true)
        controller.onBufferingStarted(true, 10_000L, 60_000L, 5_000L)
        assertEquals(VoxLiveStallState.BUFFERING_OBSERVED, controller.getState())

        // Playback recovers within 1 second
        controller.onBufferingEnded()
        assertEquals(VoxLiveStallState.IDLE, controller.getState())

        // Looper finishes without any recovery actions firing
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertFalse(manifestRefreshed)
        assertEquals(0, controller.getRecoveryAttempts())
    }

    // --- Scenario H: video switched during recovery -> old recovery ignored ---
    @Test
    fun testH_videoSwitchedDuringRecovery_oldRecoveryIgnored() {
        controller.onPlaybackStart(true)
        controller.onBufferingStarted(true, 10_000L, 60_000L, 5_000L)
        val genOld = controller.getCurrentGeneration()

        // Switch to new video
        controller.onPlaybackStart(true)
        val genNew = controller.getCurrentGeneration()
        assertTrue(genNew > genOld)
        assertEquals(VoxLiveStallState.IDLE, controller.getState())

        // Run old tasks
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertFalse(manifestRefreshed)
        assertEquals(0, controller.getRecoveryAttempts())
    }

    // --- Scenario I: VOD playback -> completely untouched ---
    @Test
    fun testI_vodPlayback_completelyUntouched() {
        controller.onPlaybackStart(isLive = false)

        // Seek outside duration on VOD is not clamped by Live policy
        val vodSeek = controller.onSeekRequested(500_000L, 300_000L, 0L, isLive = false)
        assertEquals(500_000L, vodSeek)
        assertEquals(-1L, clampedPosition)
        assertNull(lastMessage)

        // Buffering on VOD does not trigger Live recovery FSM
        controller.onBufferingStarted(isLive = false, 10_000L, 300_000L, 0L)
        assertEquals(VoxLiveStallState.IDLE, controller.getState())

        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertFalse(manifestRefreshed)
        assertEquals(0, controller.getRecoveryAttempts())
    }

    @Test
    fun handlesBehindLiveWindowRecoveryGracefully() {
        val context = RuntimeEnvironment.getApplication()
        var returnToLiveTriggered = false
        controller.handleBehindLiveWindow(context) {
            returnToLiveTriggered = true
        }
        assertTrue(returnToLiveTriggered)
        assertNotNull(lastMessage)
    }

    @Test
    fun networkTransportErrorRespectsCooldownPolicy() {
        controller.onPlaybackStart(true)
        val engines = intArrayOf(0, 1) // Cronet, OkHttp
        val switched = controller.handleNetworkTransportError(ConnectException("Connection refused"), 0, engines)
        assertFalse(switched)
        assertNotNull(controller.getState())
    }
}

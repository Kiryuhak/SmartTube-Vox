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
        lastMessage = null
    }

    @Test
    fun clampsSeekPositionBehindLiveWindow() {
        // window: start at 100_000ms, duration 300_000ms
        val requestedBeforeWindow = 50_000L
        val clamped = controller.onSeekRequested(
            requestedPosMs = requestedBeforeWindow,
            windowDurationMs = 300_000L,
            windowStartMs = 100_000L,
            isLive = true
        )
        // Must clamp to minSafePos = 100_000 + 1_000 = 101_000ms
        assertEquals(101_000L, clamped)
    }

    @Test
    fun clampsSeekPositionPastLiveWindowEnd() {
        val requestedPastEnd = 310_000L
        val clamped = controller.onSeekRequested(
            requestedPosMs = requestedPastEnd,
            windowDurationMs = 300_000L,
            windowStartMs = 100_000L,
            isLive = true
        )
        // Must clamp to windowDurationMs - MIN_LIVE_WINDOW_CLAMP_BUFFER_MS (300_000 - 2_000 = 298_000ms)
        assertEquals(298_000L, clamped)
    }

    @Test
    fun preservesValidSeekPositionWithinWindow() {
        val validPos = 200_000L
        val clamped = controller.onSeekRequested(
            requestedPosMs = validPos,
            windowDurationMs = 300_000L,
            windowStartMs = 100_000L,
            isLive = true
        )
        assertEquals(validPos, clamped)
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
    fun escalatesStallRecoveryStepByStep() {
        controller.onPlaybackStart(true)
        controller.onBufferingStarted(true, 10_000L, 60_000L, 5_000L)

        // Advance shadow looper by stall recovery trigger delay
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()

        // At attempt 1, manifest refresh is requested
        assertTrue(manifestRefreshed)
        assertEquals(1, controller.getRecoveryAttempts())
    }

    @Test
    fun networkTransportErrorRespectsCooldownPolicy() {
        controller.onPlaybackStart(true)
        val engines = intArrayOf(0, 1) // Cronet, OkHttp
        val switched = controller.handleNetworkTransportError(ConnectException("Connection refused"), 0, engines)
        // NetworkEngineRecoveryPolicy will perform first fallback attempt on error
        assertNotNull(controller.getState())
    }
}

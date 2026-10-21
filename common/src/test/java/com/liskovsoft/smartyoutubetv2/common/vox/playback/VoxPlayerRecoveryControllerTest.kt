package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.os.Looper
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class VoxPlayerRecoveryControllerTest {

    private lateinit var controller: VoxPlayerRecoveryController
    private lateinit var mockActionHandler: TestPlayerRecoveryActionHandler

    private class TestPlayerRecoveryActionHandler : VoxPlayerRecoveryController.PlayerRecoveryActionHandler {
        var sourceRefreshCount = 0
        var engineSwitchCount = 0
        var recoverySucceededCount = 0
        var recoveryFailedCount = 0
        var showOverlayCount = 0
        var hideOverlayCount = 0
        var hideSpinnerCount = 0

        var lastTargetPositionMs: Long = -1L
        var lastPlayWhenReady: Boolean = false
        var lastNextEngine: Int = -1
        var lastGen: Long = -1L
        var lastRetryRunnable: Runnable? = null

        override fun onSourceRefreshRequested(targetPositionMs: Long, playWhenReady: Boolean, gen: Long) {
            sourceRefreshCount++
            lastTargetPositionMs = targetPositionMs
            lastPlayWhenReady = playWhenReady
            lastGen = gen
        }

        override fun onEngineSwitchRequested(nextEngine: Int, targetPositionMs: Long, playWhenReady: Boolean, gen: Long) {
            engineSwitchCount++
            lastNextEngine = nextEngine
            lastTargetPositionMs = targetPositionMs
            lastPlayWhenReady = playWhenReady
            lastGen = gen
        }

        override fun onRecoverySucceeded(gen: Long) {
            recoverySucceededCount++
            lastGen = gen
        }

        override fun onRecoveryFailed(gen: Long, onRetry: Runnable) {
            recoveryFailedCount++
            lastGen = gen
            lastRetryRunnable = onRetry
        }

        override fun showRecoveryOverlay(messageResId: Int) {
            showOverlayCount++
        }

        override fun hideRecoveryOverlay() {
            hideOverlayCount++
        }

        override fun hideBufferingSpinner() {
            hideSpinnerCount++
        }

        fun reset() {
            sourceRefreshCount = 0
            engineSwitchCount = 0
            recoverySucceededCount = 0
            recoveryFailedCount = 0
            showOverlayCount = 0
            hideOverlayCount = 0
            hideSpinnerCount = 0
            lastTargetPositionMs = -1L
            lastPlayWhenReady = false
            lastNextEngine = -1
            lastGen = -1L
            lastRetryRunnable = null
        }
    }

    @Before
    fun setUp() {
        VoxPlayerRecoveryTestHooks.reset()
        mockActionHandler = TestPlayerRecoveryActionHandler()
        controller = VoxPlayerRecoveryController(mockActionHandler, Looper.getMainLooper())
    }

    @After
    fun tearDown() {
        VoxPlayerRecoveryTestHooks.reset()
        controller.reset()
    }

    // 1. frozen buffer starts recovery
    @Test
    fun test1_FrozenBufferStartsRecovery() {
        controller.onPlaybackStart(sessionId = 100L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 11_000L, playWhenReady = true)
        assertEquals(VoxPlayerRecoveryState.BUFFERING, controller.getState())

        // Buffer is frozen: no buffer progress delivered. Advance looper past watchdog timeout (4000ms)
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)

        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, mockActionHandler.sourceRefreshCount)
        assertEquals(8_000L, mockActionHandler.lastTargetPositionMs) // 10000 - 2000 safe offset
        assertTrue(mockActionHandler.lastPlayWhenReady)
        assertEquals(1, mockActionHandler.showOverlayCount)
    }

    // 2. growing buffer does not escalate
    @Test
    fun test2_GrowingBufferDoesNotEscalate() {
        controller.onPlaybackStart(sessionId = 101L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 11_000L, playWhenReady = true)

        // At 2000ms: buffer advances by 1500ms (meaningful progress)
        shadowOf(Looper.getMainLooper()).idleFor(2_000L, TimeUnit.MILLISECONDS)
        controller.onBufferProgress(bufferedPositionMs = 12_500L, playWhenReady = true)

        // Advance 2500ms (total 4500ms from start, but only 2500ms from last progress)
        shadowOf(Looper.getMainLooper()).idleFor(2_500L, TimeUnit.MILLISECONDS)
        assertEquals(VoxPlayerRecoveryState.BUFFERING, controller.getState())
        assertEquals(0, mockActionHandler.sourceRefreshCount)

        // Advance buffer again
        controller.onBufferProgress(bufferedPositionMs = 14_000L, playWhenReady = true)
        shadowOf(Looper.getMainLooper()).idleFor(2_500L, TimeUnit.MILLISECONDS)

        assertEquals(VoxPlayerRecoveryState.BUFFERING, controller.getState())
        assertEquals(0, mockActionHandler.sourceRefreshCount)
    }

    // 3. heartbeat != progress
    @Test
    fun test3_HeartbeatIsNotProgress() {
        controller.onPlaybackStart(sessionId = 102L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 11_000L, playWhenReady = true)

        // At 2000ms: buffer progress called with identical buffer (heartbeat/spinner tick)
        shadowOf(Looper.getMainLooper()).idleFor(2_000L, TimeUnit.MILLISECONDS)
        controller.onBufferProgress(bufferedPositionMs = 11_000L, playWhenReady = true) // delta = 0 < 1000ms

        // Advance 2200ms (total 4200ms from start, watchdog must NOT have been extended)
        shadowOf(Looper.getMainLooper()).idleFor(2_200L, TimeUnit.MILLISECONDS)

        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, mockActionHandler.sourceRefreshCount)
    }

    // 4. HTTP403 -> source refresh
    @Test
    fun test4_Http403TriggersSourceRefresh() {
        controller.onPlaybackStart(sessionId = 103L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)

        val dataSpec = DataSpec(android.net.Uri.parse("https://googlevideo.com/videoplayback?expire=123"))
        val error403 = HttpDataSource.InvalidResponseCodeException(403, emptyMap(), dataSpec)

        val handled = controller.handlePlayerError(error403, currentPositionMs = 25_000L, playWhenReady = true)

        assertTrue(handled)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, mockActionHandler.sourceRefreshCount)
        assertEquals(23_000L, mockActionHandler.lastTargetPositionMs) // 25000 - 2000
    }

    // 5. same URL not retried forever
    @Test
    fun test5_SameUrlNotRetriedForever() {
        controller.onPlaybackStart(sessionId = 104L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)

        val err = IOException("Response code: 403 Forbidden")

        // Attempt 1: Source recovery 1
        controller.handlePlayerError(err, 30_000L, true)
        assertEquals(1, controller.getSourceRecoveryCount())
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Attempt 2: Source recovery 2
        controller.handlePlayerError(err, 30_000L, true)
        assertEquals(2, controller.getSourceRecoveryCount())
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Attempt 3: Source recoveries exhausted (max 2) -> failover to engine switch
        controller.handlePlayerError(err, 30_000L, true)
        assertEquals(1, controller.getEngineSwitchCount())
        assertEquals(VoxPlayerRecoveryState.RECOVERING_NETWORK, controller.getState())

        // Attempt 4: Engine switches exhausted (max 1) -> terminal FAILED
        controller.handlePlayerError(err, 30_000L, true)
        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())
        assertEquals(1, mockActionHandler.hideSpinnerCount)
        assertEquals(1, mockActionHandler.recoveryFailedCount)
    }

    // 6. DNS switch bounded
    @Test
    fun test6_DnsSwitchBounded() {
        controller.onPlaybackStart(sessionId = 105L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)

        val dnsError = UnknownHostException("unable to resolve host googlevideo.com")

        // First DNS error: switches engine from DEFAULT to OKHTTP
        val handled1 = controller.handlePlayerError(dnsError, 15_000L, true)
        assertTrue(handled1)
        assertEquals(1, controller.getEngineSwitchCount())
        assertEquals(VoxPlayerRecoveryState.RECOVERING_NETWORK, controller.getState())
        assertEquals(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, mockActionHandler.lastNextEngine)

        // Second DNS error: max engine switches (1) is exhausted -> terminal failure, NO loop
        val handled2 = controller.handlePlayerError(dnsError, 15_000L, true)
        assertTrue(handled2)
        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())
        assertEquals(1, controller.getEngineSwitchCount()) // Still 1, did not switch again
    }

    // 7. engine switch rebuilds MediaSource
    @Test
    fun test7_EngineSwitchRebuildsMediaSource() {
        controller.onPlaybackStart(sessionId = 106L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)

        val dnsError = UnknownHostException("no address associated with hostname")
        controller.handlePlayerError(dnsError, 50_000L, true)

        assertEquals(1, mockActionHandler.engineSwitchCount)
        assertEquals(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, mockActionHandler.lastNextEngine)
        assertEquals(48_000L, mockActionHandler.lastTargetPositionMs) // 50000 - 2000
        assertTrue(mockActionHandler.lastGen > 0L)
    }

    // 8. position restored
    @Test
    fun test8_PositionRestored() {
        controller.onPlaybackStart(sessionId = 107L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)

        // Case A: normal position
        controller.handlePlayerError(IOException("Response code: 403"), 60_000L, true)
        assertEquals(58_000L, mockActionHandler.lastTargetPositionMs)

        // Reset and check near-start position (clamped to 0)
        controller.onPlaybackStart(sessionId = 108L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        controller.handlePlayerError(IOException("Response code: 403"), 1_000L, true)
        assertEquals(0L, mockActionHandler.lastTargetPositionMs)
    }

    // 9. playWhenReady restored
    @Test
    fun test9_PlayWhenReadyRestored() {
        controller.onPlaybackStart(sessionId = 109L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)

        // When paused during failure
        controller.handlePlayerError(IOException("Response code: 403"), 10_000L, playWhenReady = false)
        assertFalse(mockActionHandler.lastPlayWhenReady)

        // When playing during failure
        controller.onPlaybackStart(sessionId = 110L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        controller.handlePlayerError(IOException("Response code: 403"), 10_000L, playWhenReady = true)
        assertTrue(mockActionHandler.lastPlayWhenReady)
    }

    // 10. success -> RECOVERED
    @Test
    fun test10_SuccessTransitionsToRecovered() {
        controller.onPlaybackStart(sessionId = 111L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        controller.handlePlayerError(IOException("Response code: 403"), 20_000L, true)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Playback progresses successfully after refresh
        controller.onPlaybackProgress(positionMs = 21_000L, bufferedPositionMs = 26_000L)

        assertEquals(VoxPlayerRecoveryState.RECOVERED, controller.getState())
        assertEquals(1, mockActionHandler.hideOverlayCount)
        assertEquals(1, mockActionHandler.recoverySucceededCount)
        assertEquals(0, controller.getSourceRecoveryCount())
        assertEquals(0, controller.getEngineSwitchCount())
    }

    // 11. hard max -> FAILED
    @Test
    fun test11_HardMaxAttemptsTransitionsToFailed() {
        controller.onPlaybackStart(sessionId = 112L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 11_000L, playWhenReady = true)

        // Cycle 1: Source recovery 1
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Cycle 2: Stall persists -> Source recovery 2
        controller.onBufferingStarted(10_000L, 11_000L, true)
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertEquals(2, controller.getSourceRecoveryCount())

        // Cycle 3: Stall persists -> Engine switch 1
        controller.onBufferingStarted(10_000L, 11_000L, true)
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertEquals(1, controller.getEngineSwitchCount())

        // Cycle 4: Stall persists -> Limits exhausted -> FAILED
        controller.onBufferingStarted(10_000L, 11_000L, true)
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)

        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())
        assertEquals(1, mockActionHandler.hideSpinnerCount) // Spinner MUST be hidden
        assertEquals(1, mockActionHandler.recoveryFailedCount) // Failure callback with retry runnable
        assertNotNull(mockActionHandler.lastRetryRunnable)

        // Invoking manual retry must restart recovery
        mockActionHandler.lastRetryRunnable?.run()
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
    }

    // 12. stale callback dropped
    @Test
    fun test12_StaleCallbackDropped() {
        controller.onPlaybackStart(sessionId = 113L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 11_000L, playWhenReady = true)

        // Session changes (new video loaded) before watchdog fires
        controller.onPlaybackStart(sessionId = 114L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)

        // Advance looper to when old watchdog would have fired
        shadowOf(Looper.getMainLooper()).idleFor(4_500L, TimeUnit.MILLISECONDS)

        // Old watchdog callback was fenced and dropped
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
        assertEquals(0, mockActionHandler.sourceRefreshCount)
    }

    // 13. release cancels retry
    @Test
    fun test13_ReleaseCancelsRetry() {
        controller.onPlaybackStart(sessionId = 115L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 11_000L, playWhenReady = true)

        // User backs out / player releases
        controller.reset()
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())

        // Advance looper
        shadowOf(Looper.getMainLooper()).idleFor(10_000L, TimeUnit.MILLISECONDS)

        // No recovery ever fired
        assertEquals(0, mockActionHandler.sourceRefreshCount)
        assertEquals(0, mockActionHandler.engineSwitchCount)
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
    }

    // 14. no engine ping-pong
    @Test
    fun test14_NoEnginePingPong() {
        controller.onPlaybackStart(sessionId = 116L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT)

        val transportError = IOException("Unable to connect to host: connection reset by peer")

        // Exhaust source recoveries
        controller.handlePlayerError(transportError, 10_000L, true)
        controller.handlePlayerError(transportError, 10_000L, true)

        // Fallback to OkHttp (engine switch 1)
        controller.handlePlayerError(transportError, 10_000L, true)
        assertEquals(1, controller.getEngineSwitchCount())
        assertEquals(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, mockActionHandler.lastNextEngine)

        // Next error occurs: engine switch must NOT switch back to DEFAULT (no ping-pong)
        mockActionHandler.reset()
        controller.handlePlayerError(transportError, 10_000L, true)

        assertEquals(0, mockActionHandler.engineSwitchCount) // NO ping-pong switch!
        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())
    }


    // 15. internal reload preserves recovery attempts and generation
    @Test
    fun test15_InternalReloadPreservesRecoveryAttemptsAndGeneration() {
        val videoId = "vid_stall_123"
        controller.onPlaybackStart(sessionId = 200L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = videoId)
        controller.onBufferingStarted(positionMs = 15_000L, bufferedPositionMs = 15_000L, playWhenReady = true)

        // Watchdog fires -> attempt 1
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, controller.getSourceRecoveryCount())
        val gen1 = controller.getCurrentGeneration()

        // Internal reload triggered by onSourceRefreshRequested calls onPlaybackStart with the SAME videoId
        controller.onPlaybackStart(sessionId = 201L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = videoId)

        // Generation and recovery attempt must NOT be wiped!
        assertEquals(1, controller.getSourceRecoveryCount())
        assertEquals(gen1, controller.getCurrentGeneration())
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Another failure/stall occurs -> advances to attempt 2
        val err = IOException("403 Forbidden")
        controller.handlePlayerError(err, 15_000L, true)
        assertEquals(2, controller.getSourceRecoveryCount())

        // Another internal reload occurs
        controller.onPlaybackStart(sessionId = 202L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = videoId)
        assertEquals(2, controller.getSourceRecoveryCount())

        // Third failure -> advances to engine failover (Stage 2)
        controller.handlePlayerError(err, 15_000L, true)
        assertEquals(1, controller.getEngineSwitchCount())
        assertEquals(VoxPlayerRecoveryState.RECOVERING_NETWORK, controller.getState())

        // Engine restart occurs
        controller.onPlaybackStart(sessionId = 203L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, videoId = videoId)
        assertEquals(1, controller.getEngineSwitchCount())

        // Fourth failure -> terminal failure! (Strictly bounded, no infinite reload loop)
        controller.handlePlayerError(err, 15_000L, true)
        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())
        assertEquals(1, mockActionHandler.recoveryFailedCount)
    }

    // 16. no byte progress while playing triggers watchdog
    @Test
    fun test16_NoByteProgressWhilePlayingTriggersWatchdog() {
        val videoId = "vid_nobytes_456"
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 10_000L
        controller.onPlaybackStart(sessionId = 210L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = videoId)

        // Player starts playing at pos 5000, buffer 5500
        controller.onPlaybackCheck(positionMs = 5_000L, bufferedPositionMs = 5_500L, isPlaying = true, playWhenReady = true)

        // At 12000ms: position advanced to 5400ms (playing from buffer), but buffer did not grow
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 12_000L
        controller.onPlaybackCheck(positionMs = 5_400L, bufferedPositionMs = 5_500L, isPlaying = true, playWhenReady = true)
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())

        // At 13000ms: position reached buffer end (5500ms) and FROZE
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 13_000L
        controller.onPlaybackCheck(positionMs = 5_500L, bufferedPositionMs = 5_500L, isPlaying = false, playWhenReady = true)
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())

        // At 18000ms: 5000ms has elapsed with position completely frozen at 5500ms and buffer empty
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 18_000L
        controller.onPlaybackCheck(positionMs = 5_500L, bufferedPositionMs = 5_500L, isPlaying = false, playWhenReady = true)

        // Stall watchdog must trigger source refresh!
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, mockActionHandler.sourceRefreshCount)
        assertEquals(3_500L, mockActionHandler.lastTargetPositionMs) // 5500 - 2000
    }

    // 17. user pause does not trigger stall watchdog
    @Test
    fun test17_UserPauseDoesNotTriggerStallWatchdog() {
        val videoId = "vid_pause_789"
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 10_000L
        controller.onPlaybackStart(sessionId = 220L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = videoId)

        // User pauses the video
        controller.onPaused()
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 30_000L // 20s later
        controller.onPlaybackCheck(positionMs = 10_000L, bufferedPositionMs = 10_000L, isPlaying = false, playWhenReady = false)

        // No stall recovery triggered while paused!
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
        assertEquals(0, mockActionHandler.sourceRefreshCount)
    }

    // 18. user seek updates observed position and avoids clobbering
    @Test
    fun test18_UserSeekUpdatesObservedPosition() {
        val videoId = "vid_seek_101"
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 10_000L
        controller.onPlaybackStart(sessionId = 230L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = videoId)

        // User seeks to 120_000L
        controller.onSeekProcessed(positionMs = 120_000L, bufferedPositionMs = 120_000L)

        // Advance time 5s with frozen buffer
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 15_100L
        controller.onPlaybackCheck(positionMs = 120_000L, bufferedPositionMs = 120_000L, isPlaying = false, playWhenReady = true)

        // Target position must be based on the seeked position (120_000 - 2000 = 118_000), not 0
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(118_000L, mockActionHandler.lastTargetPositionMs)
    }

    // 19. new video reset clears recovery budget
    @Test
    fun test19_NewVideoResetsRecoveryBudget() {
        controller.onPlaybackStart(sessionId = 240L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_old")
        val err = IOException("403 Forbidden")
        controller.handlePlayerError(err, 10_000L, true)
        assertEquals(1, controller.getSourceRecoveryCount())

        // A DIFFERENT video is opened by user
        controller.onPlaybackStart(sessionId = 241L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_new")

        // Fresh session: budget reset
        assertEquals(0, controller.getSourceRecoveryCount())
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
    }

    // 20. controlled reproduction matrix S1 to S20
    @Test
    fun test20_ControlledReproductionMatrixS1ToS20() {
        // S1: Video starts then network data stops
        VoxPlayerRecoveryTestHooks.reset()
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 1_000L
        controller.onPlaybackStart(sessionId = 301L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "s1")
        controller.onPlaybackCheck(5_000L, 5_000L, false, true)
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 6_000L
        controller.onPlaybackCheck(5_000L, 5_000L, false, true)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // S2: HTTP connection alive but no bytes arrive
        mockActionHandler.reset()
        controller.reset()
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 1_000L
        controller.onPlaybackStart(sessionId = 302L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "s2")
        controller.onPlaybackCheck(10_000L, 10_000L, false, true)
        VoxPlayerRecoveryTestHooks.bufferFreeze = true
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 6_000L
        controller.onPlaybackCheck(10_000L, 10_000L, false, true)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // S3: HTTP 403 midway
        mockActionHandler.reset()
        controller.reset()
        controller.onPlaybackStart(sessionId = 303L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "s3")
        val isHandled = controller.handlePlayerError(IOException("HTTP 403 Forbidden"), 20_000L, true)
        assertTrue(isHandled)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // S7: Engine failover
        mockActionHandler.reset()
        controller.reset()
        controller.onPlaybackStart(sessionId = 307L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "s7")
        val dnsErr = UnknownHostException("DNS resolution failed")
        controller.handlePlayerError(dnsErr, 15_000L, true)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_NETWORK, controller.getState())
        assertEquals(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, mockActionHandler.lastNextEngine)

        // S8: Both engines fail -> terminal FAILED
        controller.handlePlayerError(dnsErr, 15_000L, true)
        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())

        // S11 & S12: Pause while buffering and resume
        mockActionHandler.reset()
        controller.reset()
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 1_000L
        controller.onPlaybackStart(sessionId = 311L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "s11")
        controller.onPaused()
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = 10_000L
        controller.onPlaybackCheck(5_000L, 5_000L, false, false) // paused
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState()) // no false trigger
        controller.onPlaybackCheck(5_000L, 5_000L, true, true) // resumed
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
    }
}


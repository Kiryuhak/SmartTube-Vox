package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JUnit 4 deterministic regression tests for PROMPT №081:
 * - Seek during RECOVERY_VERIFYING window
 * - CDN timeout and stall watchdog escalation
 * - Quality fallback gating for 1440p streams
 * - Video switch reset
 * - Sustained playback verification confirmation
 *
 * Runs deterministically in milliseconds without Robolectric or Android bytecode manipulation.
 */
class Prompt081PlayerRecoveryRegressionTest {

    private lateinit var controller: VoxPlayerRecoveryController
    private lateinit var mockActionHandler: TestPlayerRecoveryActionHandler
    private lateinit var testScheduler: TestScheduler

    private class TestScheduler(private var currentTime: Long = 10_000L) : VoxPlayerRecoveryController.Scheduler {
        private data class ScheduledTask(val runnable: Runnable, val dueTimeMs: Long, val id: Long)

        private val tasks = mutableListOf<ScheduledTask>()
        private var taskIdCounter = 0L

        init {
            VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = currentTime
        }

        fun getCurrentTime(): Long = currentTime

        fun advanceTimeBy(deltaMs: Long) {
            val targetTime = currentTime + deltaMs
            while (true) {
                val nextTask = tasks.filter { it.dueTimeMs <= targetTime }
                    .minByOrNull { it.dueTimeMs } ?: break
                tasks.remove(nextTask)
                currentTime = nextTask.dueTimeMs
                VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = currentTime
                nextTask.runnable.run()
            }
            currentTime = targetTime
            VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = currentTime
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            val dueTime = currentTime + Math.max(0L, delayMs)
            tasks.add(ScheduledTask(runnable, dueTime, ++taskIdCounter))
        }

        override fun removeCallbacks(runnable: Runnable) {
            tasks.removeAll { it.runnable == runnable }
        }

        override fun removeCallbacksAndMessages(token: Any?) {
            tasks.clear()
        }
    }

    private class TestPlayerRecoveryActionHandler : VoxPlayerRecoveryController.PlayerRecoveryActionHandler {
        var sourceRefreshCount = 0
        var engineSwitchCount = 0
        var qualityFallbackCount = 0
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
        var lastFailureReason: String? = null
        var qualityFallbackAvailable: Boolean = true

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

        override fun onQualityFallbackRequested(targetPositionMs: Long, playWhenReady: Boolean, gen: Long): Boolean {
            if (!qualityFallbackAvailable) return false
            qualityFallbackCount++
            lastTargetPositionMs = targetPositionMs
            lastPlayWhenReady = playWhenReady
            lastGen = gen
            return true
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

        override fun onRecoveryFailed(gen: Long, failureReason: String, onRetry: Runnable) {
            recoveryFailedCount++
            lastGen = gen
            lastFailureReason = failureReason
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
    }

    @Before
    fun setUp() {
        VoxPlayerRecoveryTestHooks.reset()
        mockActionHandler = TestPlayerRecoveryActionHandler()
        testScheduler = TestScheduler(currentTime = 10_000L)
        controller = VoxPlayerRecoveryController(mockActionHandler, testScheduler)
    }

    @After
    fun tearDown() {
        VoxPlayerRecoveryTestHooks.reset()
        controller.reset()
    }

    @Test
    fun test1_CdnTimeoutTriggersBoundedSourceRefreshAndEngineFailover() {
        controller.onPlaybackStart(sessionId = 100L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_cdn_timeout")
        controller.updateMediaTrackInfo("vp9", 1080, 5_000_000L)

        // Initial buffering
        controller.onBufferingStarted(positionMs = 20_000L, bufferedPositionMs = 20_500L, playWhenReady = true)
        assertEquals(VoxPlayerRecoveryState.BUFFERING, controller.getState())

        // CDN stall: 4100ms pass without buffer progress -> triggers stall watchdog -> source refresh #1
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, mockActionHandler.sourceRefreshCount)
        assertEquals(18_000L, mockActionHandler.lastTargetPositionMs) // 20000 - safe offset 2000

        // Buffer stalls again during reload 1:
        controller.onBufferingStarted(positionMs = 18_000L, bufferedPositionMs = 18_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(2, mockActionHandler.sourceRefreshCount)

        // Buffer stalls again during reload 2: MAX_SOURCE_RECOVERIES (2) reached -> engine switch #1
        controller.onBufferingStarted(positionMs = 16_000L, bufferedPositionMs = 16_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(1, mockActionHandler.engineSwitchCount)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_NETWORK, controller.getState())
    }

    @Test
    fun test2_SeekDuringVerificationWindowMustCancelVerificationCleanly() {
        controller.onPlaybackStart(sessionId = 101L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_seek_test")
        controller.updateMediaTrackInfo("vp9", 1080, 5_000_000L)

        // Enter recovery via stall
        controller.onBufferingStarted(positionMs = 15_000L, bufferedPositionMs = 15_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Playback resumes after recovery reload -> controller enters RECOVERY_VERIFYING
        controller.onPlaybackProgress(positionMs = 15_000L, bufferedPositionMs = 20_000L)
        assertEquals(VoxPlayerRecoveryState.RECOVERY_VERIFYING, controller.getState())

        // User initiates a seek while in the verification window
        controller.onSeekRequested(targetPositionMs = 50_000L)
        controller.onSeekProcessed(positionMs = 50_000L, bufferedPositionMs = 50_000L)

        // Crucial check: seek must have reset the verifying state to IDLE
        assertEquals("State must be IDLE after seek processed", VoxPlayerRecoveryState.IDLE, controller.getState())

        // Now ExoPlayer begins buffering at the seek target
        controller.onBufferingStarted(positionMs = 50_000L, bufferedPositionMs = 50_000L, playWhenReady = true)

        // Buffering the seek must NOT be falsely identified as REBUFFER_DURING_VERIFICATION
        assertEquals(VoxPlayerRecoveryState.BUFFERING, controller.getState())
        assertEquals("Seek buffering must not trigger false recovery failure", 0, mockActionHandler.recoveryFailedCount)
    }

    @Test
    fun test3_QualityFallbackGatingFor1440pStream() {
        controller.onPlaybackStart(sessionId = 102L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_1440p")
        // Stream resolution 1440p (height = 1440, bitrate = 14 Mbps)
        controller.updateMediaTrackInfo("vp9", 1440, 14_000_000L)

        // Stall 1 -> source refresh #1
        controller.onBufferingStarted(positionMs = 30_000L, bufferedPositionMs = 30_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(1, mockActionHandler.sourceRefreshCount)

        // Stall 2 -> source refresh #2
        controller.onBufferingStarted(positionMs = 28_000L, bufferedPositionMs = 28_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(2, mockActionHandler.sourceRefreshCount)

        // Stall 3 -> engine switch #1
        controller.onBufferingStarted(positionMs = 26_000L, bufferedPositionMs = 26_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(1, mockActionHandler.engineSwitchCount)

        // Stall 4 -> For 1440p, controller MUST attempt quality fallback instead of immediate terminal failure!
        controller.onBufferingStarted(positionMs = 24_000L, bufferedPositionMs = 24_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals("1440p stream should trigger quality fallback", 1, mockActionHandler.qualityFallbackCount)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_QUALITY, controller.getState())
        assertEquals("Terminal failure should not occur when quality fallback is available", 0, mockActionHandler.recoveryFailedCount)
    }

    @Test
    fun test4_VideoSwitchResetsLimiterAndStateCleanly() {
        controller.onPlaybackStart(sessionId = 103L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_old")
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 10_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertTrue(controller.isRecovering())

        // User switches to a different video
        controller.onPlaybackStart(sessionId = 104L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_new")

        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
        assertFalse(controller.isRecovering())
    }

    @Test
    fun test5_SustainedPlaybackConfirmsRecoverySuccess() {
        controller.onPlaybackStart(sessionId = 105L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_success")
        controller.updateMediaTrackInfo("vp9", 1080, 4_000_000L)

        // Stall watchdog triggers recovery
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 10_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Playback resumes
        controller.onPlaybackProgress(positionMs = 10_000L, bufferedPositionMs = 15_000L)
        assertEquals(VoxPlayerRecoveryState.RECOVERY_VERIFYING, controller.getState())

        // Playback advances steadily for 4500ms (VERIFICATION_WINDOW_MS = 4000L, progress delta >= 2000L)
        testScheduler.advanceTimeBy(2_000L)
        controller.onPlaybackProgress(positionMs = 12_000L, bufferedPositionMs = 17_000L)

        testScheduler.advanceTimeBy(2_500L) // Total elapsed = 4500ms > 4000ms
        controller.onPlaybackProgress(positionMs = 14_500L, bufferedPositionMs = 19_500L)

        assertEquals("State must be RECOVERED after verified sustained playback", VoxPlayerRecoveryState.RECOVERED, controller.getState())
        assertEquals(1, mockActionHandler.recoverySucceededCount)
        assertTrue(mockActionHandler.hideOverlayCount >= 1)
    }

    @Test
    fun test6_RepeatedSeekDuringRecoveryVerificationMustMaintainIdleStateAndPreventRebufferEscalation() {
        controller.onPlaybackStart(sessionId = 106L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_multi_seek")
        controller.updateMediaTrackInfo("vp9", 1080, 5_000_000L)

        // Trigger recovery reload
        controller.onBufferingStarted(positionMs = 15_000L, bufferedPositionMs = 15_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Playback resumes -> RECOVERY_VERIFYING
        controller.onPlaybackProgress(positionMs = 15_000L, bufferedPositionMs = 20_000L)
        assertEquals(VoxPlayerRecoveryState.RECOVERY_VERIFYING, controller.getState())

        // User rapidly seeks twice: 30s then 60s
        controller.onSeekRequested(targetPositionMs = 30_000L)
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())

        controller.onSeekRequested(targetPositionMs = 60_000L)
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())

        controller.onSeekProcessed(positionMs = 60_000L, bufferedPositionMs = 60_000L)
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())

        // Buffering at seek destination
        controller.onBufferingStarted(positionMs = 60_000L, bufferedPositionMs = 60_000L, playWhenReady = true)
        assertEquals(VoxPlayerRecoveryState.BUFFERING, controller.getState())
        assertEquals("Multi-seek buffering must not trigger false recovery failure", 0, mockActionHandler.recoveryFailedCount)
    }

    @Test
    fun test7_QualityFallbackRejectedForLowResolutionStreamEscalatesToTerminalFailureCleanly() {
        controller.onPlaybackStart(sessionId = 107L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_1080p_stall")
        // Stream resolution 1080p (height = 1080 < 1440, bitrate = 5 Mbps < 12 Mbps)
        controller.updateMediaTrackInfo("vp9", 1080, 5_000_000L)

        // Stall 1 -> source refresh 1
        controller.onBufferingStarted(positionMs = 30_000L, bufferedPositionMs = 30_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(1, mockActionHandler.sourceRefreshCount)

        // Stall 2 -> source refresh 2
        controller.onBufferingStarted(positionMs = 28_000L, bufferedPositionMs = 28_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(2, mockActionHandler.sourceRefreshCount)

        // Stall 3 -> engine switch 1
        controller.onBufferingStarted(positionMs = 26_000L, bufferedPositionMs = 26_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals(1, mockActionHandler.engineSwitchCount)

        // Stall 4 -> For 1080p stream, quality fallback must be REJECTED and cleanly trigger terminal failure!
        controller.onBufferingStarted(positionMs = 24_000L, bufferedPositionMs = 24_000L, playWhenReady = true)
        testScheduler.advanceTimeBy(4_100L)
        assertEquals("1080p stream must not trigger quality fallback", 0, mockActionHandler.qualityFallbackCount)
        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())
        assertEquals(1, mockActionHandler.recoveryFailedCount)
    }
}

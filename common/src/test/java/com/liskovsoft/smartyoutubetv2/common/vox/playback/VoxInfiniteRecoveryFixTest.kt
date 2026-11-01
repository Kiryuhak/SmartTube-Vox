package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.os.Looper
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
import java.util.concurrent.TimeUnit

/**
 * Targeted verification suite for Infinite Video Recovery fix (VOX-28070F7A).
 *
 * Covers:
 * 1. Normal successful recovery with sustained verification.
 * 2. Source refresh without resumed playback (fails safely).
 * 3. Immediate rebuffering during verification window (escalates, no false success).
 * 4. Exhaustion of all attempts terminates recovery loop (bounded).
 * 5. Concurrent manual retry and watchdog coalesced (no race conditions).
 * 6. Internal playback session change for same video preserves attempt limiter.
 * 7. Back button exit during recovery cleanly terminates without crash/freeze.
 * 8. High bitrate 4K VP9 root cause classification & bounded quality fallback.
 * 9. Absence of infinite spinner on failure.
 * 10. Stable playback resets attempt limiter after verified success.
 */
@RunWith(RobolectricTestRunner::class)
class VoxInfiniteRecoveryFixTest {

    private lateinit var controller: VoxPlayerRecoveryController
    private lateinit var mockActionHandler: TestRecoveryActionHandler

    private class TestRecoveryActionHandler : VoxPlayerRecoveryController.PlayerRecoveryActionHandler {
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
        var qualityFallbackAllowed: Boolean = true

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
            if (!qualityFallbackAllowed) return false
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
            qualityFallbackCount = 0
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
            qualityFallbackAllowed = true
        }
    }

    @Before
    fun setUp() {
        VoxPlayerRecoveryTestHooks.reset()
        mockActionHandler = TestRecoveryActionHandler()
        controller = VoxPlayerRecoveryController(mockActionHandler, Looper.getMainLooper())
    }

    @After
    fun tearDown() {
        VoxPlayerRecoveryTestHooks.reset()
        controller.reset()
    }

    // 1. Обычное успешное восстановление
    @Test
    fun test1_NormalSuccessfulRecoveryWithSustainedVerification() {
        controller.onPlaybackStart(sessionId = 1000L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_success")
        controller.onBufferingStarted(positionMs = 30_000L, bufferedPositionMs = 30_000L, playWhenReady = true)

        // Advance looper to fire stall watchdog
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, mockActionHandler.sourceRefreshCount)
        assertEquals(28_000L, mockActionHandler.lastTargetPositionMs) // 30000 - 2000 safe offset

        // Playback resumes -> enters RECOVERY_VERIFYING (not RECOVERED yet!)
        controller.onPlaybackProgress(positionMs = 28_500L, bufferedPositionMs = 33_000L)
        assertEquals(VoxPlayerRecoveryState.RECOVERY_VERIFYING, controller.getState())
        assertEquals(0, mockActionHandler.recoverySucceededCount) // Must NOT declare premature success

        // Playback continues stably for verification window (4000ms & >= 2000ms progress)
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        controller.onPlaybackProgress(positionMs = 31_000L, bufferedPositionMs = 36_000L)

        // Success verified
        assertEquals(VoxPlayerRecoveryState.RECOVERED, controller.getState())
        assertEquals(1, mockActionHandler.recoverySucceededCount)
        assertEquals(1, mockActionHandler.hideOverlayCount)
        assertEquals(0, controller.getSourceRecoveryCount()) // Limiter reset after verified success
    }

    // 2. Обновление источника без возобновления воспроизведения
    @Test
    fun test2_SourceRefreshWithoutResumedPlayback() {
        controller.onPlaybackStart(sessionId = 1001L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_no_resume")
        controller.onBufferingStarted(positionMs = 15_000L, bufferedPositionMs = 15_000L, playWhenReady = true)

        // Watchdog triggers attempt 1
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertEquals(1, mockActionHandler.sourceRefreshCount)

        // Source is refreshed, but playback NEVER resumes. Buffering continues with no buffer progress.
        controller.onBufferingStarted(positionMs = 15_000L, bufferedPositionMs = 15_000L, playWhenReady = true)
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)

        // Escalates to attempt 2
        assertEquals(2, mockActionHandler.sourceRefreshCount)
        assertEquals(0, mockActionHandler.recoverySucceededCount)
    }

    // 3. Повторная буферизация сразу после recovery
    @Test
    fun test3_ImmediateRebufferingDuringVerificationWindow() {
        controller.onPlaybackStart(sessionId = 1002L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_rebuffer")
        controller.onBufferingStarted(positionMs = 50_000L, bufferedPositionMs = 50_000L, playWhenReady = true)

        // Trigger recovery 1
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertEquals(1, mockActionHandler.sourceRefreshCount)

        // Playback starts briefly -> enters RECOVERY_VERIFYING
        controller.onPlaybackProgress(positionMs = 48_500L, bufferedPositionMs = 50_000L)
        assertEquals(VoxPlayerRecoveryState.RECOVERY_VERIFYING, controller.getState())

        // Rebuffering occurs immediately at 1200ms before 4000ms verification completes
        shadowOf(Looper.getMainLooper()).idleFor(1_200L, TimeUnit.MILLISECONDS)
        controller.onBufferingStarted(positionMs = 49_000L, bufferedPositionMs = 49_000L, playWhenReady = true)

        // Verification must FAIL and escalate to attempt 2 without false success!
        assertEquals(0, mockActionHandler.recoverySucceededCount)
        assertEquals(2, mockActionHandler.sourceRefreshCount) // Escalated to source recovery 2
    }

    // 4. Исчерпание попыток
    @Test
    fun test4_ExhaustionOfAttemptsTerminatesLoop() {
        controller.onPlaybackStart(sessionId = 1003L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_exhaust")
        controller.updateMediaTrackInfo("vp9", 2160, 58_000_000L) // 4K 58 Mbps
        val timeout = VoxPlayerRecoveryPolicy.getStallWatchdogTimeoutMs(2160, 58_000_000L) + 100L

        // Attempt 1: Source refresh 1
        controller.onBufferingStarted(positionMs = 20_000L, bufferedPositionMs = 20_000L, playWhenReady = true)
        shadowOf(Looper.getMainLooper()).idleFor(timeout, TimeUnit.MILLISECONDS)
        assertEquals(1, controller.getSourceRecoveryCount())

        // Attempt 2: Source refresh 2
        controller.onBufferingStarted(positionMs = 20_000L, bufferedPositionMs = 20_000L, playWhenReady = true)
        shadowOf(Looper.getMainLooper()).idleFor(timeout, TimeUnit.MILLISECONDS)
        assertEquals(2, controller.getSourceRecoveryCount())

        // Attempt 3: Engine switch 1
        controller.onBufferingStarted(positionMs = 20_000L, bufferedPositionMs = 20_000L, playWhenReady = true)
        shadowOf(Looper.getMainLooper()).idleFor(timeout, TimeUnit.MILLISECONDS)
        assertEquals(1, controller.getEngineSwitchCount())

        // Attempt 4: Quality fallback 1
        controller.onBufferingStarted(positionMs = 20_000L, bufferedPositionMs = 20_000L, playWhenReady = true)
        shadowOf(Looper.getMainLooper()).idleFor(timeout, TimeUnit.MILLISECONDS)
        assertEquals(1, controller.getQualityFallbackCount())

        // Attempt 5: All exhausted -> FAILED terminal state
        controller.onBufferingStarted(positionMs = 20_000L, bufferedPositionMs = 20_000L, playWhenReady = true)
        shadowOf(Looper.getMainLooper()).idleFor(timeout, TimeUnit.MILLISECONDS)

        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())
        assertTrue(controller.isTerminalFailed())
        assertEquals(1, mockActionHandler.recoveryFailedCount)
        assertEquals(1, mockActionHandler.hideSpinnerCount)

        // Subsequent buffer events MUST NOT trigger new recoveries
        controller.onBufferingStarted(positionMs = 20_000L, bufferedPositionMs = 20_000L, playWhenReady = true)
        shadowOf(Looper.getMainLooper()).idleFor(10_000L, TimeUnit.MILLISECONDS)
        assertEquals(2, mockActionHandler.sourceRefreshCount) // Unchanged
        assertEquals(1, mockActionHandler.engineSwitchCount) // Unchanged
    }

    // 5. Одновременный ручной retry и watchdog
    @Test
    fun test5_ConcurrentManualRetryAndWatchdog() {
        controller.onPlaybackStart(sessionId = 1004L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_concur")
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 10_000L, playWhenReady = true)

        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertEquals(1, mockActionHandler.sourceRefreshCount)
        assertTrue(controller.isRecovering())

        // While recovering, concurrent manual retry is called -> coalesced without duplicate action
        controller.retryManual()
        assertEquals(1, mockActionHandler.sourceRefreshCount) // Still exactly 1
    }

    // 6. Смена внутренней playback session для того же ролика
    @Test
    fun test6_InternalPlaybackSessionPreservesLimiter() {
        val videoId = "vid_persist_limiter"
        controller.onPlaybackStart(sessionId = 2001L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = videoId)
        controller.onBufferingStarted(positionMs = 12_000L, bufferedPositionMs = 12_000L, playWhenReady = true)

        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertEquals(1, controller.getSourceRecoveryCount())
        val initialSeq = controller.getRecoverySequenceId()
        assertNotNull(initialSeq)

        // Internal reload creates a new internal session for the SAME video
        controller.onPlaybackStart(sessionId = 2002L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = videoId)

        // Attempt limiter & recovery sequence MUST be preserved!
        assertEquals(1, controller.getSourceRecoveryCount())
        assertEquals(initialSeq, controller.getRecoverySequenceId())

        // Explicit new video opening resets limiter
        controller.onPlaybackStart(sessionId = 3001L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_different_new")
        assertEquals(0, controller.getSourceRecoveryCount())
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
    }

    // 7. Выход через Back во время recovery
    @Test
    fun test7_BackNavigationExitsCleanlyDuringRecovery() {
        controller.onPlaybackStart(sessionId = 1005L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_back")
        controller.onBufferingStarted(positionMs = 10_000L, bufferedPositionMs = 10_000L, playWhenReady = true)
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        assertTrue(controller.isRecovering())

        // User presses Back on remote -> onFinish() invokes reset()
        controller.reset()
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
        assertFalse(controller.isRecovering())

        // Run pending loopers -> zero ghost actions or crashes
        shadowOf(Looper.getMainLooper()).idleFor(10_000L, TimeUnit.MILLISECONDS)
        assertEquals(1, mockActionHandler.sourceRefreshCount) // No extra fires
        assertEquals(0, mockActionHandler.engineSwitchCount)
    }

    // 8. Высокий битрейт 4K VP9
    @Test
    fun test8_HighBitrate4KVp9ClassificationAndFallback() {
        // 1. Bitrate too high when measured network bandwidth (50 Mbps) struggles against 58 Mbps stream
        val causeBandwidthDeficit = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 57_983_293L,
            bufferAheadMs = 0L,
            measuredBandwidthBps = 50_000_000L,
            droppedFrames = 0,
            is4K = true,
            targetBufferBytes = 96 * 1024 * 1024
        )
        assertEquals(VoxBufferRootCause.BITRATE_TOO_HIGH, causeBandwidthDeficit)

        // 2. Decoder struggling with high bitrate frames (elevated drops on 4K) -> BITRATE_TOO_HIGH
        val causeDecoderStruggle = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 57_983_293L,
            bufferAheadMs = 0L,
            measuredBandwidthBps = 0L,
            droppedFrames = 30,
            is4K = true,
            targetBufferBytes = 96 * 1024 * 1024
        )
        assertEquals(VoxBufferRootCause.BITRATE_TOO_HIGH, causeDecoderStruggle)

        // 3. Lack of data (measuredBandwidthBps = 0, droppedFrames = 0) must return UNKNOWN, not claim false root cause
        val causeUnknownNoData = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 57_983_293L,
            bufferAheadMs = 0L,
            measuredBandwidthBps = 0L,
            droppedFrames = 0,
            is4K = true,
            targetBufferBytes = 96 * 1024 * 1024
        )
        assertEquals(VoxBufferRootCause.UNKNOWN, causeUnknownNoData)

        // 4. Plentiful bandwidth (100 Mbps) with no drops must NOT blame bitrate -> UNKNOWN
        val causeAbundantBandwidth = VoxPlaybackBufferPolicy.classifyRebuffer(
            bitrate = 57_983_293L,
            bufferAheadMs = 0L,
            measuredBandwidthBps = 100_000_000L,
            droppedFrames = 0,
            is4K = true,
            targetBufferBytes = 96 * 1024 * 1024
        )
        assertEquals(VoxBufferRootCause.UNKNOWN, causeAbundantBandwidth)

        // Test quality fallback trigger
        controller.onPlaybackStart(sessionId = 1006L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_4k")
        controller.updateMediaTrackInfo("vp9", 2160, 57_983_293L)

        // Exhaust source recoveries using recognizable network transport errors
        val err = IOException("Unable to connect to host: connection reset by peer")
        controller.handlePlayerError(err, 40_000L, true) // attempt 1
        controller.handlePlayerError(err, 40_000L, true) // attempt 2
        controller.handlePlayerError(err, 40_000L, true) // attempt 3 (engine failover)
        assertEquals(1, controller.getEngineSwitchCount())

        // Fourth failure on 4K -> triggers quality fallback
        controller.handlePlayerError(err, 40_000L, true)
        assertEquals(1, controller.getQualityFallbackCount())
        assertEquals(1, mockActionHandler.qualityFallbackCount)
    }

    // 9. Отсутствие бесконечного spinner
    @Test
    fun test9_AbsenceOfInfiniteSpinnerOnFailure() {
        controller.onPlaybackStart(sessionId = 1007L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_spinner")
        mockActionHandler.qualityFallbackAllowed = false

        // Exhaust attempts with recognized transport errors
        val err = IOException("Unable to connect to host: socket timeout")
        controller.handlePlayerError(err, 10_000L, true)
        controller.handlePlayerError(err, 10_000L, true)
        controller.handlePlayerError(err, 10_000L, true)
        controller.handlePlayerError(err, 10_000L, true)

        // Terminal state reached
        assertTrue(controller.isTerminalFailed())
        // Spinner MUST be hidden
        assertEquals(1, mockActionHandler.hideSpinnerCount)
        assertEquals(1, mockActionHandler.hideOverlayCount)
        assertNotNull("Retry action must be available", mockActionHandler.lastRetryRunnable)
    }

    // 10. Стабильное воспроизведение сбрасывает счётчик ограничений
    @Test
    fun test10_StablePlaybackResetsAttemptLimiter() {
        controller.onPlaybackStart(sessionId = 1008L, isLive = false, engine = PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, videoId = "vid_reset_limiter")
        val err = IOException("Unable to connect to host: socket closed")

        // First glitch recovers via source refresh
        controller.handlePlayerError(err, 100_000L, true)
        assertEquals(1, controller.getSourceRecoveryCount())

        // Playback resumes and verifies sustained playback
        controller.onPlaybackProgress(positionMs = 98_500L, bufferedPositionMs = 105_000L)
        shadowOf(Looper.getMainLooper()).idleFor(4_100L, TimeUnit.MILLISECONDS)
        controller.onPlaybackProgress(positionMs = 101_000L, bufferedPositionMs = 110_000L)

        // Succeeded! Counters reset to 0
        assertEquals(VoxPlayerRecoveryState.RECOVERED, controller.getState())
        assertEquals(0, controller.getSourceRecoveryCount())
        assertEquals(0, controller.getEngineSwitchCount())

        // A second independent network drop occurs 10 minutes later -> allowed to recover again
        controller.handlePlayerError(err, 200_000L, true)
        assertEquals(1, controller.getSourceRecoveryCount())
    }
}

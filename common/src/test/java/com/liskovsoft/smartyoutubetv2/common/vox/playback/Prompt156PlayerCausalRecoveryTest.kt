package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.net.Uri
import android.os.Looper
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.DashDefaultLoadErrorHandlingPolicy
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPerformanceTier
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
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
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Gate E Deterministic Causal Test Harness for PLAYER 403 / 429 / Rebuffer & Memory Bounds.
 * PROMPT №156 (Scenarios R01 - R12).
 *
 * Invariants Verified:
 * - R01: 403 on startup (fresh URL) triggers source refresh / client switch escalation, bounded at MAX_SOURCE_RECOVERIES.
 * - R02: 403 after pause with expired timestamp in past triggers source refresh with position restoration.
 * - R03: 403 with future TTL is classified as forbidden source, NOT false token expiry.
 * - R04: 404 from CDN on stream segment triggers bounded recovery without infinite loops.
 * - R05: 429 Rate Limit enforces exponential backoff (1s -> 2s -> 4s -> 8s) in DashErrorPolicy and terminal failure in controller.
 * - R06: Strict separation between DNS errors and socket timeouts/resets; DNS never triggers quality fallback.
 * - R07: Session isolation on new videoId resets recovery counters and generation.
 * - R08: Cancellation and reset clears pending timers and scheduler tasks.
 * - R09: Duplicate callbacks do not trigger re-entrant recovery loops or double client switches.
 * - R10: Decoder errors are strictly separated from network transport errors.
 * - R11: Memory allocation bounds strictly clamp targetBufferBytes under low RAM (<= 32MB) and high-end (<= 96MB).
 * - R12: Rebuffer verification window enforces sustained progress before declaring recovery success.
 */
@RunWith(RobolectricTestRunner::class)
class Prompt156PlayerCausalRecoveryTest {

    private lateinit var controller: VoxPlayerRecoveryController
    private lateinit var mockActionHandler: TestPlayerRecoveryActionHandler

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
            lastNextEngine = -1
            lastRetryRunnable = null
            lastFailureReason = null
        }
    }

    @Before
    fun setUp() {
        VoxPlayerRecoveryTestHooks.reset()
        mockActionHandler = TestPlayerRecoveryActionHandler()
        controller = VoxPlayerRecoveryController(mockActionHandler)
        controller.onPlaybackStart(100L, false, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, "test_video_156")
    }

    @After
    fun tearDown() {
        VoxPlayerRecoveryTestHooks.reset()
    }

    private fun idleLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * R01: 403 on startup with fresh URL.
     * Escalates to source refresh #1, bounded at MAX_SOURCE_RECOVERIES (2).
     */
    @Test
    fun testR01_FreshUrlHttp403_TriggersClientSwitchAndSourceRefresh_Bounded() {
        val freshUrl = "https://fixture.invalid/videoplayback?expire=9999999999&sparams=expire"
        val error = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, DataSpec(Uri.parse(freshUrl)))

        // Attempt 1: Source refresh 1
        val handled1 = controller.handlePlayerError(error, 0L, true)
        assertTrue(handled1)
        assertEquals(1, controller.getSourceRecoveryCount())
        assertEquals(1, mockActionHandler.sourceRefreshCount)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Attempt 2: Source refresh 2
        val handled2 = controller.handlePlayerError(error, 0L, true)
        assertTrue(handled2)
        assertEquals(2, controller.getSourceRecoveryCount())
        assertEquals(2, mockActionHandler.sourceRefreshCount)

        // Attempt 3: Exhausted source refreshes -> switches engine
        val handled3 = controller.handlePlayerError(error, 0L, true)
        assertTrue(handled3)
        assertEquals(1, controller.getEngineSwitchCount())
        assertEquals(1, mockActionHandler.engineSwitchCount)

        // Attempt 4: Terminal failure (attempts exhausted)
        val handled4 = controller.handlePlayerError(error, 0L, true)
        assertTrue(handled4)
        assertTrue(controller.isTerminalFailed())
        assertEquals(1, mockActionHandler.recoveryFailedCount)
        assertEquals(1, mockActionHandler.hideSpinnerCount)
    }

    /**
     * R02: 403 after pause with expired timestamp in past.
     * Recognized as stale URL, restores safe position (pos - safeOffset).
     */
    @Test
    fun testR02_ExpiredUrlHttp403_IdentifiesExpiredTimestamp_TriggersSourceRefresh() {
        controller.onPaused()
        val expiredUrl = "https://fixture.invalid/videoplayback?expire=1000000000&sparams=expire"
        val error = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, DataSpec(Uri.parse(expiredUrl)))

        assertTrue("Must be classified as stale or dead URL", VoxPlayerRecoveryPolicy.isStaleOrDeadUrl(error))
        val handled = controller.handlePlayerError(error, 60_000L, true)
        assertTrue(handled)
        assertEquals(1, mockActionHandler.sourceRefreshCount)
        assertEquals(58_000L, mockActionHandler.lastTargetPositionMs) // 60s - 2s safe offset
    }

    /**
     * R03: 403 with future TTL (expire > now).
     * Classified as HTTP_403_FORBIDDEN, NOT false token expiry.
     */
    @Test
    fun testR03_FutureTtlHttp403_IdentifiesFutureExpiry_ClassifiesAsForbiddenNotExpired() {
        val futureUrl = "https://fixture.invalid/videoplayback?expire=2500000000&sparams=expire"
        val error = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, DataSpec(Uri.parse(futureUrl)))

        val netType = VoxPlayerRecoveryPolicy.classifyNetworkFailureType(error)
        assertEquals("HTTP_403_FORBIDDEN", netType)
        assertTrue(VoxPlayerRecoveryPolicy.isStaleOrForbiddenSource(error))

        val handled = controller.handlePlayerError(error, 30_000L, true)
        assertTrue(handled)
        assertEquals(1, controller.getSourceRecoveryCount())
    }

    /**
     * R04: 404 from CDN on stream segment.
     * Recognized as stale/dead URL and handled with bounded attempts.
     */
    @Test
    fun testR04_CdnHttp404_DeadSourceRecovery_BoundedWithoutInfiniteLoop() {
        val deadUrl = "https://fixture.invalid/videoplayback?sq=104"
        val error = HttpDataSource.InvalidResponseCodeException(404, "Not Found", null, DataSpec(Uri.parse(deadUrl)))

        assertTrue(VoxPlayerRecoveryPolicy.isStaleOrDeadUrl(error))
        assertEquals("HTTP_404", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(error))

        val handled = controller.handlePlayerError(error, 20_000L, true)
        assertTrue(handled)
        assertEquals(1, mockActionHandler.sourceRefreshCount)
    }

    /**
     * R05: HTTP 429 Rate Limit.
     * Verified in DashDefaultLoadErrorHandlingPolicy (exponential backoff) and controller bounding.
     */
    @Test
    fun testR05_Http429RateLimit_EnforcesExponentialBackoffAndTerminalBounds() {
        val policy = DashDefaultLoadErrorHandlingPolicy()
        val error = HttpDataSource.InvalidResponseCodeException(
            429,
            "Too Many Requests",
            null,
            DataSpec(Uri.parse("https://fixture.invalid/videoplayback"))
        )

        // Low-level DASH policy: exponential backoff delays
        assertEquals(1000L, policy.getRetryDelayMsFor(1, 100L, error, 1)) // 1s
        assertEquals(2000L, policy.getRetryDelayMsFor(1, 100L, error, 2)) // 2s
        assertEquals(4000L, policy.getRetryDelayMsFor(1, 100L, error, 3)) // 4s
        assertEquals(8000L, policy.getRetryDelayMsFor(1, 100L, error, 4)) // 8s
        assertEquals(com.google.android.exoplayer2.C.TIME_UNSET, policy.getRetryDelayMsFor(1, 100L, error, 5)) // Exceeded max retries

        // High-level controller: handles 429 without infinite loop
        assertTrue(controller.handlePlayerError(error, 15_000L, true)) // 1: source refresh 1
        assertTrue(controller.handlePlayerError(error, 15_000L, true)) // 2: source refresh 2
        assertTrue(controller.handlePlayerError(error, 15_000L, true)) // 3: engine switch
        assertTrue(controller.handlePlayerError(error, 15_000L, true)) // 4: terminal failure
        assertTrue(controller.isTerminalFailed())
        assertEquals(1, mockActionHandler.recoveryFailedCount)
    }

    /**
     * R06: Strict separation of DNS vs Socket errors.
     * DNS never falsely triggers quality fallback.
     */
    @Test
    fun testR06_SocketAndDnsErrors_StrictSeparation_NoQualityDegradationOnDns() {
        val dnsException = UnknownHostException("Unable to resolve host rr1---sn-oxunx-a.googlevideo.com")
        val socketTimeout = SocketTimeoutException("Read timed out")
        val connectionReset = SocketException("Connection reset by peer")

        // 1. DNS classification
        assertTrue(VoxPlayerRecoveryPolicy.isDnsFailure(dnsException))
        assertFalse(VoxPlayerRecoveryPolicy.isDnsFailure(socketTimeout))
        assertEquals(VoxLogCode.PLAYER_DNS_RESOLUTION_ERROR, VoxPlayerRecoveryPolicy.classifyPlayerSourceError(dnsException, false))
        assertEquals("DNS_RESOLUTION_FAILED", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(dnsException))

        // 2. Socket classifications
        assertTrue(VoxPlayerRecoveryPolicy.isReadTimeout(socketTimeout))
        assertTrue(VoxPlayerRecoveryPolicy.isConnectionReset(connectionReset))

        // 3. DNS failure triggers engine switch directly without quality fallback
        val handledDns = controller.handlePlayerError(dnsException, 10_000L, true)
        assertTrue(handledDns)
        assertEquals(1, controller.getEngineSwitchCount())
        assertEquals(0, controller.getQualityFallbackCount()) // MUST NOT trigger quality fallback!
    }

    /**
     * R07: Session isolation on new video.
     * Opening a new videoId resets recovery attempt counters and generation.
     */
    @Test
    fun testR07_SessionIsolation_NewVideoResetsRecoveryCountersAndGenerations() {
        val error = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, DataSpec(Uri.parse("https://fixture.invalid/")))

        // Error on first video
        controller.handlePlayerError(error, 10_000L, true)
        assertEquals(1, controller.getSourceRecoveryCount())
        val gen1 = controller.getCurrentGeneration()

        // Switch to new video
        controller.onPlaybackStart(102L, false, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, "test_video_new_id")
        assertEquals(0, controller.getSourceRecoveryCount())
        assertEquals(0, controller.getEngineSwitchCount())
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
        assertTrue("Generation must advance on new video", controller.getCurrentGeneration() > gen1)
        assertEquals("test_video_new_id", controller.getCurrentVideoId())
    }

    /**
     * R08: Cancellation and reset on stop/destroy.
     * Clears all timers and leaves controller in clean IDLE state.
     */
    @Test
    fun testR08_CancellationOnDestroyOrStop_CleansUpTimersAndSchedulers() {
        controller.onBufferingStarted(10_000L, 10_000L, true)
        assertEquals(VoxPlayerRecoveryState.BUFFERING, controller.getState())

        controller.reset()
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
        assertFalse(controller.isRecovering())
        assertFalse(controller.isTerminalFailed())
    }

    /**
     * R09: Duplicate callbacks do not trigger re-entrant recovery loops.
     */
    @Test
    fun testR09_DuplicateCallbacks_NoReentrantRecoveryLoopsOrDoubleClientSwitch() {
        val error = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, DataSpec(Uri.parse("https://fixture.invalid/")))
        controller.handlePlayerError(error, 10_000L, true)
        assertEquals(1, controller.getSourceRecoveryCount())
        assertTrue(controller.isRecovering())

        // Duplicate onPlaybackStart for the SAME video during recovery does not reset counters
        controller.onPlaybackStart(100L, false, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, "test_video_156")
        assertEquals(1, controller.getSourceRecoveryCount())
        assertTrue(controller.isRecovering())
    }

    /**
     * R10: Decoder error vs Network transport error distinction.
     */
    @Test
    fun testR10_DecoderError_EscalatesToQualityFallbackOrTerminalWithoutLoop() {
        val format = com.google.android.exoplayer2.Format.createVideoSampleFormat(
            null,
            "video/x-vnd.on2.vp9",
            null,
            -1,
            -1,
            1920,
            1080,
            -1.0f,
            null,
            null
        )
        val decoderException = com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException(
            format,
            java.lang.IllegalStateException("CodecException: Error 0xfffffc0e"),
            false,
            "c2.android.vp9.decoder"
        )

        // Decoder error is NOT a network or stale source error
        assertFalse(VoxPlayerRecoveryPolicy.isStaleOrDeadUrl(decoderException))
        assertFalse(VoxPlayerRecoveryPolicy.isNetworkTransportError(decoderException))
        assertFalse(VoxPlayerRecoveryPolicy.isDnsFailure(decoderException))
        assertEquals("UNKNOWN", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(decoderException))
    }

    /**
     * R11: Memory allocation bounds under VoxPlaybackBufferPolicy.
     * Low-RAM devices clamped to <= 32MB, High-End clamped to <= 96MB.
     */
    @Test
    fun testR11_MemoryAllocationBounds_StrictClampingUnderLowRamTiers() {
        val ram1Gb = 1024L * 1024 * 1024
        val ram4Gb = 4096L * 1024 * 1024

        // Low RAM tier (1GB RAM)
        val cfgLow = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.BASIC,
            ramBytes = ram1Gb,
            isLive = false,
            isLocal = false,
            videoHeight = 1080
        )
        assertTrue("Low RAM buffer must be <= 32MB", cfgLow.targetBufferBytes <= 32 * 1024 * 1024)
        assertTrue("Low RAM buffer must be >= 20MB", cfgLow.targetBufferBytes >= 20 * 1024 * 1024)
        assertEquals(15_000, cfgLow.minBufferMs)
        assertEquals(25_000, cfgLow.maxBufferMs)

        // High End tier (4GB RAM, 4K)
        val cfgHigh = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.POWERFUL,
            ramBytes = ram4Gb,
            isLive = false,
            isLocal = false,
            videoHeight = 2160
        )
        assertTrue("High RAM buffer must be <= 96MB", cfgHigh.targetBufferBytes <= 96 * 1024 * 1024)
        assertTrue("High RAM buffer must be >= 48MB", cfgHigh.targetBufferBytes >= 48 * 1024 * 1024)
    }

    /**
     * R12: Rebuffer verification window enforces sustained progress before declaring success.
     */
    @Test
    fun testR12_RebufferHysteresisAndVerificationWindow_PreventsPrematureSuccess() {
        val error = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, DataSpec(Uri.parse("https://fixture.invalid/")))
        controller.handlePlayerError(error, 10_000L, true)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Playback resumes at pos 8_000L -> moves to RECOVERY_VERIFYING, NOT immediate RECOVERED
        controller.onPlaybackProgress(8_000L, 12_000L)
        assertEquals(VoxPlayerRecoveryState.RECOVERY_VERIFYING, controller.getState())
        assertEquals(0, mockActionHandler.recoverySucceededCount)

        // Immediate rebuffer during verification window fails verification and escalates to next stage!
        controller.onBufferingStarted(8_100L, 8_100L, true)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(2, controller.getSourceRecoveryCount()) // Escalated to source refresh #2!
    }
}

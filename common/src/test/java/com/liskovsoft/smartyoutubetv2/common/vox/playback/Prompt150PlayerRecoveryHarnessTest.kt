package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.net.Uri
import android.os.Looper
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
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
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Gate B Deterministic Offline Test Harness for PLAYER HTTP and Recovery.
 * PROMPT №150 (Scenarios H01 - H10).
 *
 * Invariants Verified:
 * - H01: HTTP 403 on startup (fresh URL) triggers source refresh / client switch escalation.
 * - H02: HTTP 403 after pause with expired URL triggers source refresh without claiming "all 403 are expiry".
 * - H03: HTTP 403 with future expire classified as forbidden source without false expiry conclusion.
 * - H04: HTTP 404 from CDN triggers recovery policy with attempt limits, avoiding infinite loops.
 * - H05: HTTP 429 rate limit is escalated and bounded to prevent hot infinite looping.
 * - H06: Socket reset / OpenException without HTTP code is classified as connection reset, NOT HTTP 403.
 * - H07: Timeout / offline is classified as connect/read timeout with clean backoff.
 * - H08: Repeat 403 exceeding MAX_SOURCE_RECOVERIES strictly escalates to engine failover and terminal failure.
 * - H09: Cross-session isolation: new videoId resets recovery attempt counters.
 * - H10: Incomplete exception chain is handled safely without NullPointerException.
 */
@RunWith(RobolectricTestRunner::class)
class Prompt150PlayerRecoveryHarnessTest {

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
        controller.onPlaybackStart(100L, false, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, "test_video_150")
    }

    @After
    fun tearDown() {
        VoxPlayerRecoveryTestHooks.reset()
    }

    private fun idleLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * H01: HTTP 403 on startup (fresh URL, position 0).
     * Invariant: Triggers source refresh Stage 1 (calling switchNextClientNow & reloadVideo in prod).
     * Refutes: "Always SABR issue".
     */
    @Test
    fun testH01_Http403OnStartup_EscalatesToSourceRefresh() {
        val fixtureUrl = "https://fixture.invalid/videoplayback?expire=9999999999&sparams=expire"
        val dataSpec = DataSpec(Uri.parse(fixtureUrl))
        val error = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, dataSpec)

        // 1. Classification contract
        val logCode = VoxPlayerRecoveryPolicy.classifyPlayerSourceError(error, false)
        val failureType = VoxPlayerRecoveryPolicy.classifyNetworkFailureType(error)
        assertEquals(VoxLogCode.PLAYER_HTTP_ERROR, logCode)
        assertEquals("HTTP_403_FORBIDDEN", failureType)

        // 2. Recovery controller decision
        val handled = controller.handlePlayerError(error, 0L, true)
        assertTrue("HTTP 403 must be handled by VoxPlayerRecoveryController", handled)
        assertEquals("Must be in RECOVERING_SOURCE state", VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals("Source refresh must be requested exactly once", 1, mockActionHandler.sourceRefreshCount)
        assertEquals("Engine switch must NOT be triggered on first 403", 0, mockActionHandler.engineSwitchCount)
        assertEquals("Recovery overlay must be displayed", 1, mockActionHandler.showOverlayCount)
    }

    /**
     * H02: HTTP 403 after pause with expired URL in past.
     * Invariant: Recovery policy recognizes stale URL and triggers bounded source refresh.
     * Refutes: "All 403 are caused by token expiration".
     */
    @Test
    fun testH02_Http403AfterPause_ExpiredUrl_TriggersSourceRefresh() {
        controller.onPaused()
        val expiredUrl = "https://fixture.invalid/videoplayback?expire=1000000000&sparams=expire"
        val dataSpec = DataSpec(Uri.parse(expiredUrl))
        val error = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, dataSpec)

        assertTrue("isStaleOrDeadUrl must detect 403", VoxPlayerRecoveryPolicy.isStaleOrDeadUrl(error))
        assertTrue("isStaleOrForbiddenSource must detect 403", VoxPlayerRecoveryPolicy.isStaleOrForbiddenSource(error))

        val handled = controller.handlePlayerError(error, 120_000L, true)
        assertTrue(handled)
        assertEquals(1, mockActionHandler.sourceRefreshCount)
        // Safe position restore offset (currentPos - 2000ms)
        assertEquals(118_000L, mockActionHandler.lastTargetPositionMs)
    }

    /**
     * H03: HTTP 403 with future expire (valid TTL).
     * Invariant: Code does NOT assume token is expired; treats as forbidden source and rotates client.
     */
    @Test
    fun testH03_Http403WithFutureExpire_ClassifiedAsForbiddenSourceNotExpiry() {
        val futureExpireUrl = "https://fixture.invalid/videoplayback?expire=2500000000&sparams=expire"
        val dataSpec = DataSpec(Uri.parse(futureExpireUrl))
        val error = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, dataSpec)

        val netType = VoxPlayerRecoveryPolicy.classifyNetworkFailureType(error)
        assertEquals("HTTP_403_FORBIDDEN", netType)

        val handled = controller.handlePlayerError(error, 45_000L, true)
        assertTrue(handled)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, controller.getSourceRecoveryCount())
    }

    /**
     * H04: HTTP 404 from CDN.
     * Invariant: Handled via isStaleOrDeadUrl, bounded attempts, no infinite loop.
     */
    @Test
    fun testH04_Http404FromCdn_TriggersSourceRefresh_NoInfiniteLoop() {
        val deadUrl = "https://fixture.invalid/videoplayback?id=missing_chunk"
        val dataSpec = DataSpec(Uri.parse(deadUrl))
        val error = HttpDataSource.InvalidResponseCodeException(404, "Not Found", null, dataSpec)

        assertTrue("HTTP 404 must be recognized as stale/dead URL", VoxPlayerRecoveryPolicy.isStaleOrDeadUrl(error))
        assertEquals("HTTP_404", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(error))

        val handled = controller.handlePlayerError(error, 30_000L, true)
        assertTrue(handled)
        assertEquals(1, mockActionHandler.sourceRefreshCount)
    }

    /**
     * H05: HTTP 429 rate-limit.
     * Invariant: Escalates via network transport path, enforces attempt limit, prevents hot infinite retry.
     */
    @Test
    fun testH05_Http429RateLimit_EnforcesRateLimitAndBounds_NoInfiniteHotLoop() {
        val rateLimitUrl = "https://fixture.invalid/videoplayback?rate_limit"
        val dataSpec = DataSpec(Uri.parse(rateLimitUrl))
        val error = HttpDataSource.InvalidResponseCodeException(429, "Too Many Requests", null, dataSpec)

        // 429 is not a dead url (not 403, 404, 410), but is an HttpDataSourceException
        assertFalse(VoxPlayerRecoveryPolicy.isStaleOrDeadUrl(error))
        assertTrue(VoxPlayerRecoveryPolicy.isNetworkTransportError(error))
        assertEquals("HTTP_429", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(error))

        // First 429 -> Stage 1 source refresh
        assertTrue(controller.handlePlayerError(error, 10_000L, true))
        assertEquals(1, controller.getSourceRecoveryCount())

        // Second 429 -> Stage 1 source refresh #2
        assertTrue(controller.handlePlayerError(error, 10_000L, true))
        assertEquals(2, controller.getSourceRecoveryCount())

        // Third 429 -> Stage 2 engine switch
        assertTrue(controller.handlePlayerError(error, 10_000L, true))
        assertEquals(1, controller.getEngineSwitchCount())

        // Fourth 429 -> Terminal failure (bounded, no hot loop)
        val handled = controller.handlePlayerError(error, 10_000L, true)
        assertTrue(handled)
        assertTrue("Controller must reach terminal failure", controller.isTerminalFailed())
        assertEquals(1, mockActionHandler.recoveryFailedCount)
        assertEquals(1, mockActionHandler.hideSpinnerCount)
    }

    /**
     * H06: Socket reset / OpenException without HTTP code.
     * Invariant: Correctly classified as CONNECTION_RESET, NOT confused with CDN HTTP 403.
     */
    @Test
    fun testH06_SocketResetOpenException_ClassifiedAsConnectionResetNot403() {
        val dataSpec = DataSpec(Uri.parse("https://fixture.invalid/videoplayback"))
        val socketReset = SocketException("Connection reset by peer")
        val error = HttpDataSource.HttpDataSourceException(
            "Unable to connect",
            socketReset,
            dataSpec,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN
        )

        // Must classify as CONNECTION_RESET, NOT HTTP 403
        assertTrue(VoxPlayerRecoveryPolicy.isConnectionReset(error))
        assertFalse(VoxPlayerRecoveryPolicy.isStaleOrForbiddenSource(error))
        assertEquals(VoxLogCode.PLAYER_CONNECTION_RESET, VoxPlayerRecoveryPolicy.classifyPlayerSourceError(error, false))
        assertEquals("CONNECTION_RESET", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(error))

        val handled = controller.handlePlayerError(error, 15_000L, true)
        assertTrue(handled)
        assertEquals(1, mockActionHandler.sourceRefreshCount)
    }

    /**
     * H07: Timeout / offline after established connection.
     * Invariant: Correctly classified as READ_TIMEOUT or CONNECT_TIMEOUT, clean backoff.
     */
    @Test
    fun testH07_TimeoutOffline_ClassifiedAsTimeout_CleanEscalation() {
        val dataSpec = DataSpec(Uri.parse("https://fixture.invalid/videoplayback"))
        val readTimeout = SocketTimeoutException("Read timed out")
        val error = HttpDataSource.HttpDataSourceException(
            "Read error",
            readTimeout,
            dataSpec,
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )

        assertTrue(VoxPlayerRecoveryPolicy.isReadTimeout(error))
        assertFalse(VoxPlayerRecoveryPolicy.isConnectTimeout(error))
        assertEquals(VoxLogCode.PLAYER_READ_TIMEOUT, VoxPlayerRecoveryPolicy.classifyPlayerSourceError(error, false))
        assertEquals("READ_TIMEOUT", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(error))

        val handled = controller.handlePlayerError(error, 25_000L, true)
        assertTrue(handled)
        assertEquals(1, mockActionHandler.sourceRefreshCount)
    }

    /**
     * H08: Repeat 403 exceeding MAX_SOURCE_RECOVERIES.
     * Invariant: Strictly enforces bounds: max 2 source refreshes, max 1 engine switch, terminal failure.
     */
    @Test
    fun testH08_Repeat403_ExceedingMaxAttempts_EscalatesToTerminalFailure() {
        val error = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            DataSpec(Uri.parse("https://fixture.invalid/videoplayback"))
        )

        // Attempt 1: Source refresh 1
        assertTrue(controller.handlePlayerError(error, 10_000L, true))
        assertEquals(1, controller.getSourceRecoveryCount())
        assertEquals(0, controller.getEngineSwitchCount())

        // Attempt 2: Source refresh 2
        assertTrue(controller.handlePlayerError(error, 10_000L, true))
        assertEquals(2, controller.getSourceRecoveryCount())
        assertEquals(0, controller.getEngineSwitchCount())

        // Attempt 3: Engine failover
        assertTrue(controller.handlePlayerError(error, 10_000L, true))
        assertEquals(2, controller.getSourceRecoveryCount())
        assertEquals(1, controller.getEngineSwitchCount())
        assertEquals(1, mockActionHandler.engineSwitchCount)

        // Attempt 4: Terminal failure (attempts exhausted)
        assertTrue(controller.handlePlayerError(error, 10_000L, true))
        assertTrue(controller.isTerminalFailed())
        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())
        assertEquals(1, mockActionHandler.recoveryFailedCount)
        assertEquals(1, mockActionHandler.hideSpinnerCount)

        // Attempt 5: Terminal state rejects further recovery triggers
        assertFalse(controller.handlePlayerError(error, 10_000L, true))
    }

    /**
     * H09: Cross-session isolation.
     * Invariant: Counters reset when opening a new video (videoId changes), preventing cross-video poisoning.
     */
    @Test
    fun testH09_CrossSessionIsolation_CountersResetOnNewVideoId() {
        val error = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            DataSpec(Uri.parse("https://fixture.invalid/videoplayback"))
        )

        // Fail once on video A
        assertTrue(controller.handlePlayerError(error, 5_000L, true))
        assertEquals(1, controller.getSourceRecoveryCount())
        assertEquals("test_video_150", controller.getCurrentVideoId())

        // User switches to new video B
        controller.onPlaybackStart(101L, false, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, "test_video_new")
        assertEquals(0, controller.getSourceRecoveryCount())
        assertEquals(0, controller.getEngineSwitchCount())
        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
        assertEquals("test_video_new", controller.getCurrentVideoId())

        // First error on video B starts fresh at attempt 1
        assertTrue(controller.handlePlayerError(error, 5_000L, true))
        assertEquals(1, controller.getSourceRecoveryCount())
    }

    /**
     * H10: Incomplete exception chain.
     * Invariant: Handles null, empty, or custom exceptions safely without throwing NPE.
     */
    @Test
    fun testH10_IncompleteExceptionChain_NullSafeGracefulFallback() {
        // 1. null error
        assertFalse(VoxPlayerRecoveryPolicy.isStaleOrForbiddenSource(null))
        assertFalse(VoxPlayerRecoveryPolicy.isStaleOrDeadUrl(null))
        assertFalse(VoxPlayerRecoveryPolicy.isDnsFailure(null))
        assertFalse(VoxPlayerRecoveryPolicy.isConnectionReset(null))
        assertFalse(VoxPlayerRecoveryPolicy.isConnectTimeout(null))
        assertFalse(VoxPlayerRecoveryPolicy.isReadTimeout(null))
        assertFalse(VoxPlayerRecoveryPolicy.isNetworkTransportError(null))
        assertEquals(VoxLogCode.PLAYER_SOURCE_ERROR, VoxPlayerRecoveryPolicy.classifyPlayerSourceError(null, false))
        assertEquals("UNKNOWN", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(null))
        assertFalse(controller.handlePlayerError(null, 0L, true))

        // 2. Exception with null message and no cause
        val opaqueException = RuntimeException()
        assertFalse(VoxPlayerRecoveryPolicy.isStaleOrForbiddenSource(opaqueException))
        assertEquals(VoxLogCode.PLAYER_SOURCE_ERROR, VoxPlayerRecoveryPolicy.classifyPlayerSourceError(opaqueException, false))
        assertEquals("UNKNOWN", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(opaqueException))
        assertFalse(controller.handlePlayerError(opaqueException, 0L, true))
    }
}

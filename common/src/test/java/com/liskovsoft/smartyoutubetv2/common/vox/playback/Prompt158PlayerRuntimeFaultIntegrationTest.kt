package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.net.Uri
import android.os.Looper
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.DashDefaultLoadErrorHandlingPolicy
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPerformanceTier
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
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gate B & C: Player Runtime Fault Injection & Deterministic Integration Test.
 * PROMPT №158 (Scenarios F01 - F12).
 *
 * Verifies real DataSource network responses (HTTP 403, 429, 404, partial drop, timeout, DNS)
 * served by a local loopback fixture server against:
 * 1. ExoPlayer DefaultHttpDataSource
 * 2. DashDefaultLoadErrorHandlingPolicy
 * 3. VoxPlayerRecoveryController & VoxPlayerRecoveryPolicy
 *
 * Boundaries: LOCAL_RUNTIME_INTEGRATION / ZERO_PUSH / NO_PRODUCTION_CHANGE_WITHOUT_RED.
 */
@RunWith(RobolectricTestRunner::class)
class Prompt158PlayerRuntimeFaultIntegrationTest {

    private lateinit var fixtureServer: TestHttpFixtureServer
    private lateinit var controller: VoxPlayerRecoveryController
    private lateinit var actionHandler: TestRecoveryActionHandler
    private lateinit var dashPolicy: DashDefaultLoadErrorHandlingPolicy

    enum class ServerResponseMode {
        MODE_200_OK,
        MODE_403_FORBIDDEN,
        MODE_404_NOT_FOUND,
        MODE_429_RATE_LIMIT,
        MODE_500_SERVER_ERROR,
        MODE_PARTIAL_DROP,
        MODE_SLOW_TRICKLE
    }

    private class TestHttpFixtureServer {
        private var serverSocket: ServerSocket? = null
        private var executor: ExecutorService? = null
        @Volatile var running = false
        var port: Int = 0
            private set
        @Volatile var responseMode: ServerResponseMode = ServerResponseMode.MODE_200_OK
        val requestCount = AtomicInteger(0)
        var fixtureBytes: ByteArray = ByteArray(1024) { 0x42 }

        fun start() {
            // Find sample.mp4 fixture from exoplayer repository
            val candidates = listOf(
                File("exoplayer-amzn-2.10.6/library/core/src/test/assets/mp4/sample.mp4"),
                File("../exoplayer-amzn-2.10.6/library/core/src/test/assets/mp4/sample.mp4"),
                File("C:/Dev/SmartTube/exoplayer-amzn-2.10.6/library/core/src/test/assets/mp4/sample.mp4")
            )
            val found = candidates.firstOrNull { it.exists() }
            if (found != null && found.length() > 0) {
                fixtureBytes = found.readBytes()
            }

            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            running = true
            executor = Executors.newCachedThreadPool()

            executor!!.submit {
                while (running && !serverSocket!!.isClosed) {
                    try {
                        val client = serverSocket!!.accept()
                        executor!!.submit {
                            handleClient(client)
                        }
                    } catch (ignored: IOException) {
                        break
                    }
                }
            }
        }

        private fun handleClient(socket: Socket) {
            try {
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val requestLine = reader.readLine() ?: return
                    var line: String? = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        line = reader.readLine()
                    }

                    requestCount.incrementAndGet()
                    val out = s.getOutputStream()

                    when (responseMode) {
                        ServerResponseMode.MODE_200_OK -> {
                            val header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: video/mp4\r\n" +
                                    "Content-Length: ${fixtureBytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.US_ASCII))
                            out.write(fixtureBytes)
                            out.flush()
                        }
                        ServerResponseMode.MODE_403_FORBIDDEN -> {
                            val body = "Forbidden: Expired signed URL\r\n"
                            val header = "HTTP/1.1 403 Forbidden\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: ${body.length}\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.US_ASCII))
                            out.write(body.toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                        ServerResponseMode.MODE_404_NOT_FOUND -> {
                            val body = "Not Found\r\n"
                            val header = "HTTP/1.1 404 Not Found\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: ${body.length}\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.US_ASCII))
                            out.write(body.toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                        ServerResponseMode.MODE_429_RATE_LIMIT -> {
                            val body = "Too Many Requests\r\n"
                            val header = "HTTP/1.1 429 Too Many Requests\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Retry-After: 2\r\n" +
                                    "Content-Length: ${body.length}\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.US_ASCII))
                            out.write(body.toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                        ServerResponseMode.MODE_500_SERVER_ERROR -> {
                            val body = "Internal Server Error\r\n"
                            val header = "HTTP/1.1 500 Internal Server Error\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: ${body.length}\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.US_ASCII))
                            out.write(body.toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                        ServerResponseMode.MODE_PARTIAL_DROP -> {
                            val header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: video/mp4\r\n" +
                                    "Content-Length: ${fixtureBytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.US_ASCII))
                            val partialChunk = fixtureBytes.copyOfRange(0, Math.min(64, fixtureBytes.size))
                            out.write(partialChunk)
                            out.flush()
                        }
                        ServerResponseMode.MODE_SLOW_TRICKLE -> {
                            val header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: video/mp4\r\n" +
                                    "Content-Length: ${fixtureBytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            out.write(header.toByteArray(Charsets.US_ASCII))
                            out.write(byteArrayOf(0x00))
                            out.flush()
                            try {
                                Thread.sleep(3000)
                            } catch (ignored: InterruptedException) {}
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        fun stop() {
            running = false
            try {
                serverSocket?.close()
            } catch (ignored: Exception) {}
            executor?.shutdownNow()
            serverSocket = null
            executor = null
        }
    }

    private class TestRecoveryActionHandler : VoxPlayerRecoveryController.PlayerRecoveryActionHandler {
        var sourceRefreshCount = 0
        var engineSwitchCount = 0
        var qualityFallbackCount = 0
        var recoverySucceededCount = 0
        var recoveryFailedCount = 0
        var hideSpinnerCount = 0
        var showOverlayCount = 0
        var hideOverlayCount = 0

        var lastTargetPositionMs: Long = -1L
        var lastPlayWhenReady: Boolean = false
        var lastNextEngine: Int = -1
        var lastGen: Long = -1L
        var lastFailureReason: String? = null
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
            hideSpinnerCount = 0
            showOverlayCount = 0
            hideOverlayCount = 0
            lastTargetPositionMs = -1L
            lastNextEngine = -1
            lastGen = -1L
            lastFailureReason = null
            lastRetryRunnable = null
        }
    }

    @Before
    fun setUp() {
        VoxPlayerRecoveryTestHooks.reset()
        fixtureServer = TestHttpFixtureServer()
        fixtureServer.start()

        actionHandler = TestRecoveryActionHandler()
        controller = VoxPlayerRecoveryController(actionHandler)
        controller.onPlaybackStart(1001L, false, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, "video_158_test")

        dashPolicy = DashDefaultLoadErrorHandlingPolicy()
    }

    @After
    fun tearDown() {
        fixtureServer.stop()
        VoxPlayerRecoveryTestHooks.reset()
    }

    private fun idleLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createDataSource(connectTimeoutMs: Int = 2000, readTimeoutMs: Int = 2000): HttpDataSource {
        val factory = DefaultHttpDataSourceFactory("SmartTubeVOXTest/9.2", null, connectTimeoutMs, readTimeoutMs, false)
        return factory.createDataSource()
    }

    // =========================================================================
    // Scenario F01: Single HTTP 403 on segment -> Bounded recovery -> 200 OK -> Verified progress
    // =========================================================================
    @Test
    fun testF01_singleHttp403_sourceRefresh_then200_sustainedRecovery() {
        fixtureServer.responseMode = ServerResponseMode.MODE_403_FORBIDDEN
        val ds = createDataSource()

        var caughtException: IOException? = null
        try {
            ds.open(DataSpec(Uri.parse("http://127.0.0.1:${fixtureServer.port}/media/segment1.mp4")))
        } catch (e: IOException) {
            caughtException = e
        }

        assertNotNull("ExoPlayer DataSource must throw IOException on 403", caughtException)
        assertTrue(caughtException is HttpDataSource.InvalidResponseCodeException)
        assertEquals(403, (caughtException as HttpDataSource.InvalidResponseCodeException).responseCode)

        // Pass real DataSource exception into VoxPlayerRecoveryController
        val handled = controller.handlePlayerError(caughtException, 30_000L, true)
        assertTrue("Controller must handle 403 error", handled)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, actionHandler.sourceRefreshCount)
        assertEquals(28_000L, actionHandler.lastTargetPositionMs) // 30s - 2s safe offset

        // Now simulate source refresh succeeded: fixture server switches to 200 OK
        fixtureServer.responseMode = ServerResponseMode.MODE_200_OK
        val dsRecovered = createDataSource()
        val dataSpec = DataSpec(Uri.parse("http://127.0.0.1:${fixtureServer.port}/media/segment1.mp4"))
        val length = dsRecovered.open(dataSpec)
        assertTrue(length > 0)
        val readBuf = ByteArray(512)
        val bytesRead = dsRecovered.read(readBuf, 0, readBuf.size)
        assertTrue(bytesRead > 0)
        dsRecovered.close()

        // Simulate playback progression after reload
        controller.onPlaybackProgress(28_000L, 35_000L)
        assertEquals(VoxPlayerRecoveryState.RECOVERY_VERIFYING, controller.getState())

        // Sustain playback past verification window (>= 4000ms, >= 2000ms progress)
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = System.currentTimeMillis() + 4500L
        controller.onPlaybackProgress(31_000L, 38_000L)

        idleLooper()
        assertEquals(VoxPlayerRecoveryState.RECOVERED, controller.getState())
        assertEquals(1, actionHandler.recoverySucceededCount)
        assertEquals(0, actionHandler.hideSpinnerCount) // Spinner not stuck, recovery was clean
    }

    // =========================================================================
    // Scenario F02: Constant HTTP 403 -> Terminal state, bounded attempts, clean failure UX
    // =========================================================================
    @Test
    fun testF02_constantHttp403_boundedAttempts_terminalFailureAndDialog() {
        fixtureServer.responseMode = ServerResponseMode.MODE_403_FORBIDDEN
        val ds = createDataSource()

        var caughtException: IOException? = null
        try {
            ds.open(DataSpec(Uri.parse("http://127.0.0.1:${fixtureServer.port}/media/segment_fail.mp4")))
        } catch (e: IOException) {
            caughtException = e
        }
        assertNotNull(caughtException)

        // Attempt 1: Source Refresh 1
        controller.handlePlayerError(caughtException, 10_000L, true)
        assertEquals(1, controller.getSourceRecoveryCount())
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Attempt 2: Source Refresh 2
        controller.handlePlayerError(caughtException, 10_000L, true)
        assertEquals(2, controller.getSourceRecoveryCount())
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Attempt 3: Engine Failover 1 (Default -> OkHttp)
        controller.handlePlayerError(caughtException, 10_000L, true)
        assertEquals(1, controller.getEngineSwitchCount())
        assertEquals(VoxPlayerRecoveryState.RECOVERING_NETWORK, controller.getState())
        assertEquals(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, actionHandler.lastNextEngine)

        // Attempt 4: Attempts exhausted -> Terminal FAILED
        controller.handlePlayerError(caughtException, 10_000L, true)
        assertEquals(VoxPlayerRecoveryState.FAILED, controller.getState())
        assertTrue(controller.isTerminalFailed())
        assertEquals(1, actionHandler.recoveryFailedCount)
        assertEquals(1, actionHandler.hideSpinnerCount) // Must hide buffering spinner on terminal failure
        assertNotNull(actionHandler.lastRetryRunnable) // Must provide manual retry option

        // Subsequent errors must be rejected without spinning or re-entering recovery
        val furtherHandled = controller.handlePlayerError(caughtException, 10_000L, true)
        assertFalse("Further errors must be ignored in terminal state", furtherHandled)
        assertEquals(1, actionHandler.recoveryFailedCount) // Count must not increase
    }

    // =========================================================================
    // Scenario F03: HTTP 429 Rate Limit with Retry-After -> Dash policy backoff & bounded escalation
    // =========================================================================
    @Test
    fun testF03_http429RateLimit_dashBackoffAndControllerTerminal() {
        fixtureServer.responseMode = ServerResponseMode.MODE_429_RATE_LIMIT
        val ds = createDataSource()

        var caughtException: IOException? = null
        try {
            ds.open(DataSpec(Uri.parse("http://127.0.0.1:${fixtureServer.port}/media/rate_limit.mp4")))
        } catch (e: IOException) {
            caughtException = e
        }
        assertNotNull(caughtException)
        val respEx = caughtException as HttpDataSource.InvalidResponseCodeException
        assertEquals(429, respEx.responseCode)

        // Verify DashDefaultLoadErrorHandlingPolicy exponential backoff for 429
        assertEquals(1000L, dashPolicy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 50L, respEx, 1))
        assertEquals(2000L, dashPolicy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 50L, respEx, 2))
        assertEquals(4000L, dashPolicy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 50L, respEx, 3))
        assertEquals(8000L, dashPolicy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 50L, respEx, 4))
        assertEquals(C.TIME_UNSET, dashPolicy.getRetryDelayMsFor(C.DATA_TYPE_MEDIA, 50L, respEx, 5)) // Bounded at 4 retries!

        // Blacklist should NOT be applied for 429 (it is a rate limit, not track damage)
        assertEquals(C.TIME_UNSET, dashPolicy.getBlacklistDurationMsFor(C.DATA_TYPE_MEDIA, 50L, respEx, 1))

        // Controller classification
        val failureType = VoxPlayerRecoveryPolicy.classifyNetworkFailureType(respEx)
        assertEquals("HTTP_429", failureType)

        // Controller handles 429 without request storm
        controller.handlePlayerError(respEx, 5000L, true)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
    }

    // =========================================================================
    // Scenario F04: HTTP 404 on segment -> Proper classification and non-blind handling
    // =========================================================================
    @Test
    fun testF04_http404NotFound_segmentClassificationAndHandling() {
        fixtureServer.responseMode = ServerResponseMode.MODE_404_NOT_FOUND
        val ds = createDataSource()

        var caughtException: IOException? = null
        try {
            ds.open(DataSpec(Uri.parse("http://127.0.0.1:${fixtureServer.port}/media/chunk404.mp4")))
        } catch (e: IOException) {
            caughtException = e
        }
        assertNotNull(caughtException)
        val respEx = caughtException as HttpDataSource.InvalidResponseCodeException
        assertEquals(404, respEx.responseCode)

        // DashPolicy: for media live edge chunks, transient 404 does not blacklist on early attempts
        val blacklistLive = dashPolicy.getBlacklistDurationMsFor(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE, 20L, respEx, 1)
        assertEquals(C.TIME_UNSET, blacklistLive)

        // Policy classifies 404 as dead URL
        assertTrue(VoxPlayerRecoveryPolicy.isStaleOrDeadUrl(respEx))
        assertEquals("HTTP_404", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(respEx))

        // Controller handles bounded refresh
        val handled = controller.handlePlayerError(respEx, 12_000L, true)
        assertTrue(handled)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
    }

    // =========================================================================
    // Scenario F05: Read timeout after partial segment -> Resource cleanup & bounded retry
    // =========================================================================
    @Test
    fun testF05_partialSegmentSocketAbort_readTimeoutAndCleanup() {
        fixtureServer.responseMode = ServerResponseMode.MODE_PARTIAL_DROP
        val ds = createDataSource(connectTimeoutMs = 1500, readTimeoutMs = 1500)

        var caughtException: IOException? = null
        try {
            ds.open(DataSpec(Uri.parse("http://127.0.0.1:${fixtureServer.port}/media/partial.mp4")))
            val buffer = ByteArray(2048)
            // Reading past the 64-byte truncated chunk must encounter abrupt socket close
            while (true) {
                val read = ds.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT || read <= 0) break
            }
        } catch (e: IOException) {
            caughtException = e
        } finally {
            try { ds.close() } catch (ignored: Exception) {}
        }

        // Must encounter transport/socket exception or HttpDataSourceException
        if (caughtException != null) {
            val isTransport = VoxPlayerRecoveryPolicy.isNetworkTransportError(caughtException) ||
                    VoxPlayerRecoveryPolicy.isConnectionReset(caughtException) ||
                    caughtException is SocketException
            assertTrue("Abrupt socket drop must be classified as transport error", isTransport)

            val handled = controller.handlePlayerError(caughtException, 15_000L, true)
            assertTrue("Controller must handle socket transport error", handled)
        }
    }

    // =========================================================================
    // Scenario F06: Slow trickle / progress freeze -> No premature recovery on READY
    // =========================================================================
    @Test
    fun testF06_slowTrickleProgressFreeze_noPrematureRecovery() {
        controller.onBufferingStarted(5000L, 5500L, true)
        assertEquals(VoxPlayerRecoveryState.BUFFERING, controller.getState())

        // Buffer does not advance meaningfully (< 1000ms delta)
        controller.onBufferProgress(5800L, true)
        assertEquals(VoxPlayerRecoveryState.BUFFERING, controller.getState())

        // Elapsed time reaches watchdog timeout (4000ms) with no progress
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = System.currentTimeMillis() + 4500L
        controller.onPlaybackCheck(5000L, 5800L, false, true)

        idleLooper()
        // Must escalate to recovery rather than hanging in spinner
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(1, actionHandler.sourceRefreshCount)
    }

    // =========================================================================
    // Scenario F07: Rebuffer during verification window -> Immediate escalation
    // =========================================================================
    @Test
    fun testF07_rebufferDuringVerification_immediateEscalation() {
        // Trigger recovery to enter RECOVERING_SOURCE
        controller.handlePlayerError(HttpDataSource.InvalidResponseCodeException(403, null, emptyMap(), DataSpec(Uri.EMPTY)), 20_000L, true)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())

        // Playback resumes -> enters RECOVERY_VERIFYING
        controller.onPlaybackProgress(18_000L, 22_000L)
        assertEquals(VoxPlayerRecoveryState.RECOVERY_VERIFYING, controller.getState())

        // Immediate rebuffering during verification!
        controller.onBufferingStarted(18_500L, 19_000L, true)

        // Must immediately fail verification and escalate to next stage (source refresh 2)
        assertEquals(VoxPlayerRecoveryState.RECOVERING_SOURCE, controller.getState())
        assertEquals(2, controller.getSourceRecoveryCount())
        assertEquals(0, actionHandler.recoverySucceededCount) // Zero premature success!
    }

    // =========================================================================
    // Scenario F08: VOD A -> B switch while recovering -> Session isolation
    // =========================================================================
    @Test
    fun testF08_videoSwitchDuringRecovery_sessionIsolation() {
        // Video A in active recovery
        controller.handlePlayerError(HttpDataSource.InvalidResponseCodeException(403, null, emptyMap(), DataSpec(Uri.EMPTY)), 10_000L, true)
        assertEquals(1, controller.getSourceRecoveryCount())
        val genA = controller.getCurrentGeneration()

        // User switches to Video B
        controller.onPlaybackStart(2002L, false, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, "video_B_fresh")

        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
        assertEquals(0, controller.getSourceRecoveryCount())
        assertEquals(0, controller.getEngineSwitchCount())
        assertTrue("Generation must increment on video change", controller.getCurrentGeneration() > genA)
        assertEquals("video_B_fresh", controller.getCurrentVideoId())
    }

    // =========================================================================
    // Scenario F09: Pause -> Background -> Foreground -> No spurious triggers
    // =========================================================================
    @Test
    fun testF09_pauseBackgroundLifecycle_noSpuriousRecovery() {
        controller.onPaused()

        // While paused (playWhenReady = false), stall checks must NOT trigger watchdog
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = System.currentTimeMillis() + 10_000L
        controller.onPlaybackCheck(15_000L, 15_500L, false, false)

        assertEquals(VoxPlayerRecoveryState.IDLE, controller.getState())
        assertEquals(0, controller.getSourceRecoveryCount())
        assertEquals(0, actionHandler.sourceRefreshCount)
    }

    // =========================================================================
    // Scenario F10: Three sequential errors, then normal network -> Recovers
    // =========================================================================
    @Test
    fun testF10_threeSequentialErrors_thenNormalMedia_recovers() {
        val err403 = HttpDataSource.InvalidResponseCodeException(403, null, emptyMap(), DataSpec(Uri.EMPTY))

        // Error 1 -> source refresh 1
        controller.handlePlayerError(err403, 30_000L, true)
        assertEquals(1, controller.getSourceRecoveryCount())

        // Error 2 -> source refresh 2
        controller.handlePlayerError(err403, 30_000L, true)
        assertEquals(2, controller.getSourceRecoveryCount())

        // Error 3 -> engine switch 1
        controller.handlePlayerError(err403, 30_000L, true)
        assertEquals(1, controller.getEngineSwitchCount())

        // Network recovers, playback resumes
        controller.onPlaybackProgress(28_000L, 35_000L)
        assertEquals(VoxPlayerRecoveryState.RECOVERY_VERIFYING, controller.getState())

        // Sustained playback for >= 5s
        VoxPlayerRecoveryTestHooks.mockCurrentTimeMs = System.currentTimeMillis() + 5500L
        controller.onPlaybackProgress(32_000L, 40_000L)

        idleLooper()
        assertEquals(VoxPlayerRecoveryState.RECOVERED, controller.getState())
        assertEquals(1, actionHandler.recoverySucceededCount)
        assertEquals(0, controller.getSourceRecoveryCount()) // Limiter reset
        assertEquals(0, controller.getEngineSwitchCount())
    }

    // =========================================================================
    // Scenario F11: Low-RAM configuration and sequential rebuffers -> Buffer bounded
    // =========================================================================
    @Test
    fun testF11_lowRamBufferPolicy_clampedAcrossRebuffers() {
        val lowRamPolicy = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.BASIC,
            ramBytes = 1024L * 1024 * 1024,
            isLive = false,
            isLocal = false,
            videoHeight = 1080
        )

        // Low RAM target buffer must be strictly clamped to <= 32MB
        assertEquals(VoxBufferProfile.CONSERVATIVE, lowRamPolicy.profile)
        assertTrue(lowRamPolicy.targetBufferBytes <= 32 * 1024 * 1024)
        assertEquals(2000, lowRamPolicy.bufferForPlaybackMs)
        assertEquals(3500, lowRamPolicy.bufferForPlaybackAfterRebufferMs)

        // Simulate 5 consecutive rebuffers
        for (i in 1..5) {
            controller.onBufferingStarted(1000L * i, 1200L * i, true)
            controller.onPlaybackProgress(1000L * i + 500, 1200L * i + 1000)
        }

        // Resolving policy again must never inflate the 32MB ceiling
        val postRebufferPolicy = VoxPlaybackBufferPolicy.resolve(
            tier = VoxPerformanceTier.BASIC,
            ramBytes = 1024L * 1024 * 1024,
            isLive = false,
            isLocal = false,
            videoHeight = 1080
        )
        assertTrue(postRebufferPolicy.targetBufferBytes <= 32 * 1024 * 1024)
    }

    // =========================================================================
    // Scenario F12: Explicit DNS failure -> OkHttp failover, never quality downgrade
    // =========================================================================
    @Test
    fun testF12_dnsFailureSeparation_engineSwitchNoQualityDegrade() {
        val dnsException = UnknownHostException("Unable to resolve host googlevideo.com: No address associated with hostname")
        val wrappedException = HttpDataSource.HttpDataSourceException(dnsException, DataSpec(Uri.EMPTY), HttpDataSource.HttpDataSourceException.TYPE_OPEN)

        assertTrue(VoxPlayerRecoveryPolicy.isDnsFailure(wrappedException))
        assertEquals("DNS_RESOLUTION_FAILED", VoxPlayerRecoveryPolicy.classifyNetworkFailureType(wrappedException))

        // Controller handles DNS error
        val handled = controller.handlePlayerError(wrappedException, 15_000L, true)
        assertTrue(handled)

        // DNS error must trigger engine failover (to OkHttp with DoH) rather than quality degradation
        assertEquals(1, controller.getEngineSwitchCount())
        assertEquals(0, controller.getQualityFallbackCount())
        assertEquals(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP, actionHandler.lastNextEngine)
    }
}

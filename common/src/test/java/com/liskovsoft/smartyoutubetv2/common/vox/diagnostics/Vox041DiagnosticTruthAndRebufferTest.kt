package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.robolectric.RuntimeEnvironment
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxOtaErrorCode
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxOtaStage
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxOtaUpdateManager
import com.liskovsoft.smartyoutubetv2.common.vox.playback.RebufferIncident
import com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxBufferRootCause
import com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlaybackBufferPolicy
import com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxUiStateTracer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * P0 Comprehensive Test Suite for Prompt #041:
 * - Synthetic 31-event fixture (10 HUD duplicates, 2160p VP9 rebuffer, OTA 404->403, lag, consent).
 * - Measured rebuffer classification (HTTP 4xx/5xx, DNS, TLS, Timeout, Decoder, Window, Throughput, Unknown).
 * - OTA distinction (Manifest 404, Forbidden 403 vs Rate Limit 429/403).
 * - UI state tracer deduplication.
 * - Privacy safe telemetry & mode OFF suppression.
 */
@RunWith(RobolectricTestRunner::class)
class Vox041DiagnosticTruthAndRebufferTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        VoxUserJourneyRecorder.resetForTests()
        VoxLogStore.instance(context).clearLogs()
        VoxUiStateTracer.resetForTesting()
    }

    @Test
    fun testSynthetic31EventFixtureAndLagCorrelation() {
        // Construct synthetic 31 events mirroring the structure of case VOX-6769FCA9 without any real user PII
        val syntheticEvents = mutableListOf<VoxLogEvent>()
        val baseTimestamp = 1729424700000L // 11:45 UTC

        // 1. App Startup & Initial Configuration (events 0..4)
        for (i in 0..4) {
            syntheticEvents.add(
                VoxLogEvent(
                    timestamp = baseTimestamp + (i * 100L),
                    level = VoxLogLevel.INFO,
                    category = VoxLogCategory.DIAGNOSTICS,
                    code = "APP_INIT_STAGE_$i",
                    message = "Synthetic startup stage $i"
                )
            )
        }

        // 2. Playback start 2160p VP9 on 1080p display (events 5..9)
        syntheticEvents.add(
            VoxLogEvent(
                timestamp = baseTimestamp + 1000L,
                level = VoxLogLevel.INFO,
                category = VoxLogCategory.PLAYER,
                code = VoxLogCode.PLAYER_TRACK_FALLBACK,
                message = "Выбран видеопоток 2160p VP9",
                context = mapOf(
                    "selectedHeight" to "2160",
                    "codec" to "vp9",
                    "bitrate" to "17600000",
                    "display" to "1920x1080"
                )
            )
        )
        for (i in 6..9) {
            syntheticEvents.add(
                VoxLogEvent(
                    timestamp = baseTimestamp + (i * 300L),
                    level = VoxLogLevel.INFO,
                    category = VoxLogCategory.PLAYER,
                    code = "PLAYBACK_TRACK_STATE_$i",
                    message = "Synthetic track state $i"
                )
            )
        }

        // 3. Exactly 10 twin HUD_FOCUS_RESTORED events (events 10..19)
        for (i in 10..19) {
            syntheticEvents.add(
                VoxLogEvent(
                    timestamp = baseTimestamp + 4000L + ((i - 10) * 50L),
                    level = VoxLogLevel.INFO,
                    category = VoxLogCategory.UI,
                    code = VoxLogCode.HUD_FOCUS_RESTORED,
                    message = "Фокус ввода восстановлен на PlaybackFragment",
                    context = mapOf(
                        "focusTarget" to "PlaybackFragment",
                        "eventSource" to "onFocusRestored",
                        "reason" to "ensureFocus"
                    )
                )
            )
        }

        // 4. PLAYER_REBUFFER event (event 20) with 2160p VP9
        syntheticEvents.add(
            VoxLogEvent(
                timestamp = baseTimestamp + 5000L,
                level = VoxLogLevel.WARNING,
                category = VoxLogCategory.PLAYER,
                code = VoxLogCode.PLAYER_REBUFFER,
                message = "Буферизация потока",
                context = mapOf(
                    "selectedHeight" to "2160",
                    "selectedCodec" to "vp9",
                    "bitrate" to "17600000",
                    "bufferAheadMs" to "120",
                    "rootCause" to "UNKNOWN"
                )
            )
        )

        // 5. OTA check events: 404 manifest missing and 403 forbidden (events 21..24)
        syntheticEvents.add(
            VoxLogEvent(
                timestamp = baseTimestamp + 6000L,
                level = VoxLogLevel.INFO,
                category = VoxLogCategory.OTA,
                code = VoxLogCode.OTA_ENDPOINT_REQUEST,
                message = "Запрос манифеста обновления",
                context = mapOf("endpoint" to "https://example.com/manifest.json", "stage" to "HTTP")
            )
        )
        syntheticEvents.add(
            VoxLogEvent(
                timestamp = baseTimestamp + 6100L,
                level = VoxLogLevel.WARNING,
                category = VoxLogCategory.OTA,
                code = VoxLogCode.OTA_MANIFEST_MISSING,
                message = "Манифест обновления не найден на сервере (404)",
                context = mapOf("httpStatus" to "404", "endpoint" to "https://example.com/manifest.json")
            )
        )
        syntheticEvents.add(
            VoxLogEvent(
                timestamp = baseTimestamp + 6500L,
                level = VoxLogLevel.INFO,
                category = VoxLogCategory.OTA,
                code = VoxLogCode.OTA_ENDPOINT_REQUEST,
                message = "Резервный запрос GitHub Releases API",
                context = mapOf("endpoint" to "https://api.github.com/repos/example/releases", "stage" to "HTTP")
            )
        )
        syntheticEvents.add(
            VoxLogEvent(
                timestamp = baseTimestamp + 6800L,
                level = VoxLogLevel.ERROR,
                category = VoxLogCategory.OTA,
                code = VoxLogCode.OTA_FORBIDDEN,
                message = "Доступ к серверу обновлений ограничен (403 Forbidden)",
                context = mapOf("httpStatus" to "403", "stage" to "HTTP")
            )
        )

        // 6. Remaining application lifecycle events (events 25..30)
        for (i in 25..30) {
            syntheticEvents.add(
                VoxLogEvent(
                    timestamp = baseTimestamp + 7000L + ((i - 25) * 200L),
                    level = VoxLogLevel.INFO,
                    category = VoxLogCategory.DIAGNOSTICS,
                    code = "APP_CYCLE_EVENT_$i",
                    message = "Synthetic lifecycle $i"
                )
            )
        }

        // Verify total fixture size is exactly 31 events
        assertEquals(31, syntheticEvents.size)

        // Count HUD focus restored events
        val hudEvents = syntheticEvents.filter { it.code == VoxLogCode.HUD_FOCUS_RESTORED }
        assertEquals(10, hudEvents.size)

        // Verify OTA events
        assertTrue(syntheticEvents.any { it.code == VoxLogCode.OTA_MANIFEST_MISSING })
        assertTrue(syntheticEvents.any { it.code == VoxLogCode.OTA_FORBIDDEN })

        // Check timestamp lag: event occurred at baseTimestamp (11:45 UTC), submitted at 19:29 UTC (+7h 43m)
        val drainTimestamp = baseTimestamp + (7 * 3600 + 43 * 60) * 1000L + 845L // 27,780,845 ms later
        val policyMap = VoxDiagnosticsPolicy.createAutoSubmissionPolicy(
            context = context,
            reportPurpose = VoxDiagnosticReport.PURPOSE_AUTO_BETA,
            reportTimestamp = baseTimestamp,
            submissionTimestamp = drainTimestamp
        )

        assertEquals(27780845L, policyMap["queueLagMs"])
        assertEquals(true, policyMap["isRetroactive"])
        assertEquals(baseTimestamp, policyMap["eventTimeMs"])
        assertEquals(drainTimestamp, policyMap["submittedAtMs"])

        // Assemble diagnostic report with the 31 synthetic events
        val report = VoxDiagnosticReport(
            schema = VoxDiagnosticReport.SCHEMA_V3,
            reportId = "VOX-SYNTHETIC-31",
            timestamp = baseTimestamp,
            appVersion = "32.56-vox.8-rc",
            appVersionCode = 2446009,
            platform = "Android TV",
            manufacturer = "SyntheticOEM",
            model = "SyntheticBox",
            osName = "Android",
            osVersion = "11",
            sdkInt = 30,
            deviceTier = "Стандартный TV",
            videoCodecs = mapOf("VP9" to "SUPPORTED [HW]"),
            audioCodecs = mapOf("OPUS" to "SUPPORTED"),
            display = mapOf("resolution" to "1920x1080", "refreshRateHz" to 60),
            currentPolicy = mapOf("mode" to "AUTO", "maxQualityHeight" to 2160),
            recommendedSettings = mapOf("tier" to "STANDARD"),
            safeRecentEvents = syntheticEvents,
            reportPurpose = VoxDiagnosticReport.PURPOSE_AUTO_BETA,
            autoSubmitted = true,
            triggerEvent = VoxLogCode.PLAYER_REBUFFER,
            channel = "rc",
            buildChannel = "rc",
            updateChannel = "beta",
            autoSubmissionPolicy = policyMap
        )

        val jsonStr = report.toJson()
        val parsedJson = JSONObject(jsonStr)

        // Assert 31 events preserved
        val evArr = parsedJson.getJSONArray("safeRecentEvents")
        assertEquals(31, evArr.length())

        // Assert build channel and update channel transparency
        assertEquals("rc", parsedJson.getString("buildChannel"))
        assertEquals("beta", parsedJson.getString("updateChannel"))

        // Assert autoSubmissionPolicy is populated
        val asp = parsedJson.getJSONObject("autoSubmissionPolicy")
        assertEquals(true, asp.getBoolean("isRetroactive"))
        assertEquals(27780845L, asp.getLong("queueLagMs"))

        // Assert zero raw user PII in JSON
        assertFalse(jsonStr.contains("VOX-6769FCA9"))
        assertFalse(jsonStr.contains("26511a221f7a111a"))
    }

    @Test
    fun testRebufferDetailedRootCauseClassification() {
        // 1. Measured Decoder Error
        val decErr = VoxPlaybackBufferPolicy.classifyRebufferDetailed(
            bitrate = 17_600_000L,
            bufferAheadMs = 100L,
            isDecoderError = true,
            lastErrorClass = "DecoderInitializationException"
        )
        assertEquals(VoxBufferRootCause.DECODER_ERROR, decErr.rootCause)
        assertNull(decErr.evidenceMissing)

        // 2. HTTP 4xx Stale URL
        val http4xx = VoxPlaybackBufferPolicy.classifyRebufferDetailed(
            bitrate = 17_600_000L,
            bufferAheadMs = 50L,
            lastHttpStatus = 404
        )
        assertEquals(VoxBufferRootCause.HTTP_4XX_STALE_URL, http4xx.rootCause)

        // 3. HTTP 5xx Server Error
        val http5xx = VoxPlaybackBufferPolicy.classifyRebufferDetailed(
            bitrate = 17_600_000L,
            bufferAheadMs = 50L,
            lastHttpStatus = 503
        )
        assertEquals(VoxBufferRootCause.HTTP_5XX, http5xx.rootCause)

        // 4. DNS Failure
        val dnsErr = VoxPlaybackBufferPolicy.classifyRebufferDetailed(
            bitrate = 17_600_000L,
            bufferAheadMs = 0L,
            lastErrorClass = UnknownHostException::class.java.simpleName
        )
        assertEquals(VoxBufferRootCause.DNS_FAILURE, dnsErr.rootCause)

        // 5. TLS Failure
        val tlsErr = VoxPlaybackBufferPolicy.classifyRebufferDetailed(
            bitrate = 17_600_000L,
            bufferAheadMs = 0L,
            lastErrorClass = SSLHandshakeException::class.java.simpleName
        )
        assertEquals(VoxBufferRootCause.TLS_FAILURE, tlsErr.rootCause)

        // 6. Timeout
        val timeoutErr = VoxPlaybackBufferPolicy.classifyRebufferDetailed(
            bitrate = 17_600_000L,
            bufferAheadMs = 0L,
            lastErrorClass = SocketTimeoutException::class.java.simpleName
        )
        assertEquals(VoxBufferRootCause.TIMEOUT, timeoutErr.rootCause)

        // 7. Behind Live / Seek Outside Window
        val windowErr = VoxPlaybackBufferPolicy.classifyRebufferDetailed(
            bitrate = 17_600_000L,
            bufferAheadMs = 0L,
            isBehindLiveWindow = true
        )
        assertEquals(VoxBufferRootCause.SEEK_OUTSIDE_WINDOW, windowErr.rootCause)

        // 8. Measured Bandwidth Deficit: 10 Mbps measured for 17.6 Mbps video (< 80%)
        val netDeficit = VoxPlaybackBufferPolicy.classifyRebufferDetailed(
            bitrate = 17_600_000L,
            bufferAheadMs = 120L,
            measuredBandwidthBps = 10_000_000L,
            droppedFrames = 0,
            is4K = true
        )
        assertEquals(VoxBufferRootCause.INSUFFICIENT_BUFFER_OR_THROUGHPUT, netDeficit.rootCause)
        assertNull(netDeficit.evidenceMissing)

        // 9. Unknown with Missing Evidence: 0 bps measured, 0 drops, no error reported
        val unknownMissing = VoxPlaybackBufferPolicy.classifyRebufferDetailed(
            bitrate = 17_600_000L,
            bufferAheadMs = 2000L,
            measuredBandwidthBps = 0L,
            droppedFrames = 0,
            is4K = true
        )
        assertEquals(VoxBufferRootCause.UNKNOWN, unknownMissing.rootCause)
        assertNotNull(unknownMissing.evidenceMissing)
        assertTrue(unknownMissing.evidenceMissing!!.contains("throughputEstimateBps"))
    }

    @Test
    fun testRebufferIncidentModelAndLifecycle() {
        val incident = RebufferIncident(
            incidentId = "test-inc-1",
            startTimeMs = 1000L,
            positionMs = 12345L,
            bufferedDurationMs = 200L,
            selectedHeight = 2160,
            codec = "vp9",
            bitrate = 17_600_000L,
            networkEngine = "OkHttp",
            mediaSourceType = "VOD",
            isLive = false,
            playbackSessionId = "sess-1",
            operationId = "op-1",
            networkType = "WIFI",
            throughputEstimateBps = 12_000_000L,
            rootCause = VoxBufferRootCause.INSUFFICIENT_BUFFER_OR_THROUGHPUT,
            outcome = "IN_PROGRESS"
        )

        val map = incident.toMap()
        assertEquals("test-inc-1", map["incidentId"])
        assertEquals("2160", map["selectedHeight"])
        assertEquals("vp9", map["codec"])
        assertEquals("WIFI", map["networkType"])
        assertEquals("INSUFFICIENT_BUFFER_OR_THROUGHPUT", map["rootCause"])

        // End incident (Recovered)
        incident.endTimeMs = 2500L
        incident.rebufferDurationMs = 1500L
        incident.outcome = "RECOVERED"

        val finalMap = incident.toMap()
        assertEquals("1500", finalMap["rebufferDurationMs"])
        assertEquals("RECOVERED", finalMap["outcome"])
    }

    @Test
    fun testOtaClassificationForbiddenVsRateLimit() {
        // 1. General 403 Forbidden without rate limit headers -> FORBIDDEN
        val exForbidden = VoxOtaUpdateManager.classifyException(
            Exception("HTTP 403 Forbidden from update server"),
            "Forbidden test",
            VoxOtaStage.HTTP
        )
        assertEquals(VoxOtaErrorCode.FORBIDDEN, exForbidden.code)
        assertFalse(exForbidden.isRetryable)

        // 2. 403 Forbidden with rate limit indication -> RATE_LIMIT
        val exRateLimit403 = VoxOtaUpdateManager.classifyException(
            Exception("HTTP 403 API rate limit exceeded"),
            "Rate limit test",
            VoxOtaStage.HTTP
        )
        assertEquals(VoxOtaErrorCode.RATE_LIMIT, exRateLimit403.code)
        assertTrue(exRateLimit403.isRetryable)

        // 3. HTTP 429 -> RATE_LIMIT
        val exRateLimit429 = VoxOtaUpdateManager.classifyException(
            Exception("HTTP 429 Too Many Requests"),
            "429 test",
            VoxOtaStage.HTTP
        )
        assertEquals(VoxOtaErrorCode.RATE_LIMIT, exRateLimit429.code)
        assertTrue(exRateLimit429.isRetryable)

        // 4. HTTP 404 -> RELEASE_NOT_FOUND
        val ex404 = VoxOtaUpdateManager.classifyException(
            Exception("HTTP 404 Not Found"),
            "404 test",
            VoxOtaStage.HTTP
        )
        assertEquals(VoxOtaErrorCode.RELEASE_NOT_FOUND, ex404.code)
    }

    @Test
    fun testHudFocusRestoredDeduplication() {
        VoxUiStateTracer.resetForTesting()
        val initialTraceCount = VoxUiStateTracer.getRecentTraces().size

        // 1. First event should be recorded in tracer
        VoxUiStateTracer.onFocusRestored(
            target = "PlaybackFragment",
            eventSource = "ensureFocus",
            reason = "dpad_navigation"
        )
        assertEquals(initialTraceCount + 1, VoxUiStateTracer.getRecentTraces().size)

        // 2. Immediate second event with identical target (< 500ms) should be suppressed
        VoxUiStateTracer.onFocusRestored(
            target = "PlaybackFragment",
            eventSource = "ensureFocus",
            reason = "dpad_navigation"
        )
        assertEquals("Duplicate HUD focus restored within 500ms must be suppressed", initialTraceCount + 1, VoxUiStateTracer.getRecentTraces().size)

        // 3. Different focus target should NOT be suppressed
        VoxUiStateTracer.onFocusRestored(
            target = "PlayerOverlayView",
            eventSource = "ensureFocus",
            reason = "overlay_opened"
        )
        assertEquals(initialTraceCount + 2, VoxUiStateTracer.getRecentTraces().size)
    }

    @Test
    fun testModeOffSuppressesAllAutoDiagnostics() {
        val recorder = VoxUserJourneyRecorder.instance(context)
        recorder.setMode(VoxUserJourneyMode.OFF)

        val canPeriodic = VoxDiagnosticsPolicy.canSendPeriodicDiagnostics(context)
        assertFalse("Mode OFF must completely suppress periodic diagnostics", canPeriodic)

        val canIncident = VoxDiagnosticsPolicy.canSendIncidentDiagnostics(context)
        assertFalse("Mode OFF must completely suppress incident diagnostics", canIncident)
    }

    @Test
    fun testSafeNetworkTypeContainsNoPii() {
        val safeType = VoxPlaybackBufferPolicy.getSafeNetworkType(context)
        assertNotNull(safeType)
        assertTrue(
            listOf("ETHERNET", "WIFI", "CELLULAR", "VPN", "OTHER", "NONE", "UNKNOWN").contains(safeType)
        )
        assertFalse(safeType.contains("."))
        assertFalse(safeType.contains(":"))
    }
}

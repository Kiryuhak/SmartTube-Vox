package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JUnit 4 deterministic verification for VOT attach latency ms telemetry
 * and HTTP status code tracking introduced in PROMPT №081.
 */
class Prompt081VotAttachLatencyTest {

    private lateinit var stateMachine: VoxTranslationStateMachine

    @Before
    fun setUp() {
        stateMachine = VoxTranslationStateMachine()
    }

    @Test
    fun testAttachLatencyMonotonicMeasurementOnReady() {
        // Transition from LOOKUP -> ATTACHING at t=10_000 ms
        stateMachine.transitionTo(VoxTranslationStateMachine.State.LOOKUP, 9_000L)
        stateMachine.transitionTo(VoxTranslationStateMachine.State.ATTACHING, 10_000L)
        assertEquals(VoxTranslationStateMachine.State.ATTACHING, stateMachine.currentState)

        // Initial sync completes, audio ready at t=12_450 ms (attach latency = 2,450 ms)
        stateMachine.transitionTo(VoxTranslationStateMachine.State.READY, 12_450L)
        assertEquals(VoxTranslationStateMachine.State.READY, stateMachine.currentState)

        assertEquals(2_450L, stateMachine.audioAttachElapsedMs)
        val diag = stateMachine.toDiagnosticsContext()
        assertEquals("2450", diag["attachLatencyMs"])
        assertEquals("2450", diag["audioAttachElapsedMs"])
        assertEquals("READY", diag["state"])

        val summary = stateMachine.getDiagnosticsSummary()
        assertEquals(2_450L, summary["attachLatencyMs"])
        assertEquals(2_450L, summary["translationAttachMs"])
    }

    @Test
    fun testAttachLatencyMeasurementOnTimeout() {
        // Audio attach starts at t=5_000 ms
        stateMachine.transitionTo(VoxTranslationStateMachine.State.ATTACHING, 5_000L)

        // Postprocess watchdog fires after 30s at t=35_000 ms
        stateMachine.transitionTo(VoxTranslationStateMachine.State.TIMEOUT, 35_000L)
        assertEquals(VoxTranslationStateMachine.State.TIMEOUT, stateMachine.currentState)

        assertEquals(30_000L, stateMachine.audioAttachElapsedMs)
        val diag = stateMachine.toDiagnosticsContext()
        assertEquals("30000", diag["attachLatencyMs"])
        assertEquals("TIMEOUT", diag["state"])
    }

    @Test
    fun testHttpStatusTrackingAndClassification() {
        stateMachine.recordHttpStatus(429)
        assertEquals(429, stateMachine.lastHttpStatusCode)
        assertEquals("429_RATE_LIMIT", stateMachine.lastHttpStatusClass)
        assertEquals("429", stateMachine.toDiagnosticsContext()["httpStatus"])
        assertEquals("429_RATE_LIMIT", stateMachine.toDiagnosticsContext()["httpClass"])

        stateMachine.recordHttpStatus(504)
        assertEquals(504, stateMachine.lastHttpStatusCode)
        assertEquals("504_GATEWAY_TIMEOUT", stateMachine.lastHttpStatusClass)

        stateMachine.recordHttpStatus(500)
        assertEquals(500, stateMachine.lastHttpStatusCode)
        assertEquals("5xx_SERVER_ERROR", stateMachine.lastHttpStatusClass)

        stateMachine.recordHttpStatus(403)
        assertEquals(403, stateMachine.lastHttpStatusCode)
        assertEquals("4xx_CLIENT_ERROR", stateMachine.lastHttpStatusClass)

        stateMachine.recordHttpStatus(200)
        assertEquals(200, stateMachine.lastHttpStatusCode)
        assertEquals("2xx_SUCCESS", stateMachine.lastHttpStatusClass)

        stateMachine.reset()
        assertEquals(0, stateMachine.lastHttpStatusCode)
        assertEquals("NONE", stateMachine.lastHttpStatusClass)
        assertFalse(stateMachine.toDiagnosticsContext().containsKey("httpStatus"))
    }

    @Test
    fun testExtractHttpStatusCodeFromErrorAndCategory() {
        assertEquals(429, VoiceTranslateController.extractHttpStatusCode("HTTP 429 for /video-translation/translate", "rate_limited"))
        assertEquals(504, VoiceTranslateController.extractHttpStatusCode("HTTP 504 Gateway Timeout", "http_server_error"))
        assertEquals(500, VoiceTranslateController.extractHttpStatusCode("HTTP 500 Internal Server Error", null))
        assertEquals(401, VoiceTranslateController.extractHttpStatusCode("HTTP 401 Unauthorized", "auth"))
        assertEquals(429, VoiceTranslateController.extractHttpStatusCode(null, "rate_limited"))
        assertEquals(401, VoiceTranslateController.extractHttpStatusCode(null, "auth"))
        assertEquals(404, VoiceTranslateController.extractHttpStatusCode(null, "not_found"))
        assertEquals(500, VoiceTranslateController.extractHttpStatusCode(null, "http_server_error"))
        assertEquals(0, VoiceTranslateController.extractHttpStatusCode("Socket timeout", "timeout"))
        assertEquals(0, VoiceTranslateController.extractHttpStatusCode(null, null))
    }

    @Test
    fun testSanitizationGuaranteesNoTokensOrUrlsInContext() {
        stateMachine.transitionTo(VoxTranslationStateMachine.State.REQUESTING, 1_000L)
        stateMachine.transitionTo(VoxTranslationStateMachine.State.ATTACHING, 2_000L)
        stateMachine.transitionTo(VoxTranslationStateMachine.State.READY, 3_000L)
        stateMachine.recordHttpStatus(429)

        val diag = stateMachine.toDiagnosticsContext()
        for ((key, value) in diag) {
            assertFalse("Diagnostic key '$key' must not expose secrets", key.contains("token", ignoreCase = true) || key.contains("cookie", ignoreCase = true))
            assertFalse("Diagnostic value '$value' must not expose URLs", value.startsWith("http://") || value.startsWith("https://"))
            assertFalse("Diagnostic value '$value' must not expose tokens", value.contains("secret", ignoreCase = true) || value.contains("signature", ignoreCase = true))
        }
    }

    @Test
    fun testAttachCancellationOnNewVideoOrReset() {
        stateMachine.transitionTo(VoxTranslationStateMachine.State.LOOKUP, 1_000L)
        stateMachine.transitionTo(VoxTranslationStateMachine.State.ATTACHING, 2_000L)
        assertEquals(VoxTranslationStateMachine.State.ATTACHING, stateMachine.currentState)

        // User switches video or cancels translation before attach completes
        stateMachine.reset()

        assertEquals(VoxTranslationStateMachine.State.IDLE, stateMachine.currentState)
        assertEquals(0L, stateMachine.audioAttachElapsedMs)
        assertEquals(0, stateMachine.lastHttpStatusCode)
        assertEquals("NONE", stateMachine.lastHttpStatusClass)

        val diag = stateMachine.toDiagnosticsContext()
        assertEquals("IDLE", diag["state"])
        assertEquals("0", diag["attachLatencyMs"])
        assertFalse(diag.containsKey("httpStatus"))
    }

    @Test
    fun testFallbackLatencyAndHttpStatusExtractionOnDelayed504() {
        // Request started at t=10_000ms
        stateMachine.transitionTo(VoxTranslationStateMachine.State.REQUESTING, 10_000L)
        stateMachine.transitionTo(VoxTranslationStateMachine.State.WAITING_PROVIDER, 11_000L)

        // Gateway timeout 504 arrives at t=25_000ms
        val code = VoiceTranslateController.extractHttpStatusCode("HTTP 504 Gateway Timeout", "http_server_error")
        assertEquals(504, code)
        stateMachine.recordHttpStatus(code)
        stateMachine.recordFallbackAttempt("gateway_timeout_504")

        assertTrue(stateMachine.fallbackAttempted)
        assertEquals(504, stateMachine.lastHttpStatusCode)
        assertEquals("504_GATEWAY_TIMEOUT", stateMachine.lastHttpStatusClass)

        val diag = stateMachine.toDiagnosticsContext()
        assertEquals("504", diag["httpStatus"])
        assertEquals("504_GATEWAY_TIMEOUT", diag["httpClass"])
        assertEquals("true", diag["fallbackAttempted"])
    }
}

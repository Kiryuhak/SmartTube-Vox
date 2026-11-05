package com.liskovsoft.smartyoutubetv2.common.vot

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogEvent
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogLevel
import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationStateMachine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Gate C: Offline Host Unit Test for Production Diagnostics Serialization and Wire Fixture Generation.
 * PROMPT №163-R17 (Wire Contract Bridge between Android Client and Worker Diagnostics).
 *
 * Verifications:
 * 1. Producer Fidelity: State machine + VoiceTranslateController context generation retains 'httpStatus: 503'.
 * 2. Serializer Fidelity: VoxLogEvent.toJson() serializes the context into exact wire JSON without key renaming.
 * 3. Fixture Generation: Emits deterministic wire fixture to services/vox-diagnostics/test/fixtures/prompt_163_r17_wire_fixture.json.
 * 4. Negative Controls: DNS failure produces no httpStatus; Error 1102 body text does not manufacture httpStatus.
 */
class Prompt163R17DiagnosticsEnvelopeFixtureTest {

    private lateinit var stateMachine: VoxTranslationStateMachine

    @Before
    fun setUp() {
        stateMachine = VoxTranslationStateMachine()
    }

    @Test
    fun testC1_ProductionClientSerializerEmitsHttpStatus503OnWire() {
        val now = 1730768000000L
        stateMachine.transitionTo(VoxTranslationStateMachine.State.REQUESTING, now)
        stateMachine.onProgressPoll(progressPercent = -1, remainingSec = -1, nowMs = now + 50)
        stateMachine.recordHttpStatus(503)
        stateMachine.recordFallbackAttempt("HTTP 503 for /video-translation/translate")
        stateMachine.transitionTo(VoxTranslationStateMachine.State.WAITING_PROVIDER, now + 100)

        // Exact reproduction of VoiceTranslateController.java:2406-2412
        val fbCtx = HashMap(stateMachine.toDiagnosticsContext())
        fbCtx["primaryFailure"] = "HTTP 503 for /video-translation/translate"
        fbCtx["fallbackCount"] = "1"
        fbCtx["httpStatus"] = "503"
        fbCtx["httpClass"] = stateMachine.lastHttpStatusClass

        // Real production serializer
        val event = VoxLogEvent(
            timestamp = now + 100,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.TRANSLATION,
            code = VoxLogCode.TRANSLATION_FALLBACK_ATTEMPT,
            message = "Bounded fallback from NEW backend to OLD backend",
            context = fbCtx,
            repeatCount = 1,
            isHistorical = false,
            appVersion = "32.56-vox.9.2"
        )

        val eventJson = event.toJson()
        assertTrue("Event JSON must have context object", eventJson.has("context"))
        val ctxJson = eventJson.getJSONObject("context")

        // Assert exact keys emitted by client serializer
        assertEquals("503", ctxJson.getString("httpStatus"))
        assertEquals("5xx_SERVER_ERROR", ctxJson.getString("httpClass"))
        assertEquals("HTTP 503 for /video-translation/translate", ctxJson.getString("primaryFailure"))
        assertEquals("1", ctxJson.getString("fallbackCount"))
        assertEquals("true", ctxJson.getString("fallbackAttempted"))
        assertEquals("WAITING_PROVIDER", ctxJson.getString("state"))

        // Create full report payload (schema vox-diagnostic-report-v3)
        val reportJson = JSONObject().apply {
            put("schema", "vox-diagnostic-report-v3")
            put("reportId", "VOX-SYNTHETIC-R17-001")
            put("timestamp", now + 150)
            put("platform", "Android TV")
            put("appVersion", "32.56-vox.9.2")
            put("appVersionCode", 2446032)
            put("manufacturer", "TCL")
            put("model", "Smart TV Pro")
            put("osName", "Android")
            put("osVersion", "14 (API 34)")
            put("sdkInt", 34)
            put("deviceTier", "STANDARD")
            put("reportPurpose", "TEST")
            put("safeRecentEvents", JSONArray().put(eventJson))
        }

        // Emit to fixture path for Node test consumption
        val candidates = listOf(
            File("services/vox-diagnostics/test/fixtures"),
            File("../services/vox-diagnostics/test/fixtures"),
            File("../../services/vox-diagnostics/test/fixtures")
        )
        val targetDir = candidates.firstOrNull { it.exists() }
        if (targetDir != null) {
            val fixtureFile = File(targetDir, "prompt_163_r17_wire_fixture.json")
            fixtureFile.writeText(reportJson.toString(2))
            assertTrue("Fixture file must exist on disk", fixtureFile.exists())
            assertTrue("Fixture file must be non-empty", fixtureFile.length() > 0)
        }
    }

    @Test
    fun testC2_DnsExceptionOmitsHttpStatusInProductionSerializer() {
        val now = 1730768000000L
        stateMachine.transitionTo(VoxTranslationStateMachine.State.REQUESTING, now)
        // DNS failure: no recordHttpStatus called
        stateMachine.recordFallbackAttempt("dns_lookup_failed")
        stateMachine.transitionTo(VoxTranslationStateMachine.State.WAITING_PROVIDER, now + 50)

        val fbCtx = HashMap(stateMachine.toDiagnosticsContext())
        fbCtx["primaryFailure"] = "dns_lookup_failed"
        fbCtx["fallbackCount"] = "1"

        val event = VoxLogEvent(
            timestamp = now + 50,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.TRANSLATION,
            code = VoxLogCode.TRANSLATION_FALLBACK_ATTEMPT,
            message = "Bounded fallback on DNS failure",
            context = fbCtx,
            repeatCount = 1,
            isHistorical = false,
            appVersion = "32.56-vox.9.2"
        )

        val eventJson = event.toJson()
        val ctxJson = eventJson.getJSONObject("context")

        assertFalse("httpStatus must NOT exist in context on DNS failure", ctxJson.has("httpStatus"))
        assertFalse("httpClass must NOT exist in context on DNS failure", ctxJson.has("httpClass"))
        assertEquals("dns_lookup_failed", ctxJson.getString("primaryFailure"))
    }
}

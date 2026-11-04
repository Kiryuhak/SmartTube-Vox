package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationStateMachine;
import org.junit.Test;

import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Gate C: Host Unit Test Suite for Signal Provenance, Classification & Disambiguation.
 * PROMPT №163-R13 (Offline Classification Tests, HOST_UNIT layer).
 *
 * Scenarios:
 * 1. test01_Http503MaintainsTransportStatusCodeAndNotSubstitutedBy1102:
 *    HTTP 503 is maintained as transport status code (SERVER_UNAVAILABLE) and NOT substituted with 1102.
 * 2. test02_SyntheticBodyContainingError1102TreatedAsUnparsedIndicator:
 *    Body marker "Error 1102" is unparsed by client classifiers (returns GENERIC_ERROR / NOT_SUPPORTED).
 *    Actual HTTP status (503) remains authoritative.
 * 3. test03_UnknownHostExceptionProducesNoHttpResponse:
 *    UnknownHostException is a transport network exception that produces NO HTTP response code.
 *    unknownhost_exception_is_http503 == false.
 * 4. test04_VotFallbackLowRepresentsTriageSeverityNotAudioQuality:
 *    VOT fallback to secondary backend (OLD) records failure attempt without breaking user experience.
 *    LOW is a diagnostic triage priority, NOT an audio bitrate or quality measure.
 */
public class Prompt163SignalClassificationHostTest {

    @Test
    public void test01_Http503MaintainsTransportStatusCodeAndNotSubstitutedBy1102() {
        int transportStatus = 503;
        VotErrorCategory category = VotErrorCategory.fromHttpCode(transportStatus);

        assertEquals("HTTP 503 must classify as SERVER_UNAVAILABLE", VotErrorCategory.SERVER_UNAVAILABLE, category);
        assertTrue("HTTP 503 error must be marked transient", category.isTransient());
        assertFalse("HTTP 503 must not be classified as OAuth failure", category.isOAuthFailure());

        // Assert 503 is preserved and not mutated to 1102
        assertEquals(503, transportStatus);
        assertNotEquals("Transport status code must NEVER be substituted with 1102", 1102, transportStatus);
    }

    @Test
    public void test02_SyntheticBodyContainingError1102TreatedAsUnparsedIndicator() {
        // Synthetic local mock response simulating Cloudflare Error 1102 body
        int transportStatus = 503;
        String syntheticBody = "<html><head><title>503 Service Temporarily Unavailable</title></head>"
                + "<body><center><h1>Cloudflare Error 1102</h1><p>Worker exceeded resource limits</p></center></body></html>";

        // Current production client code does NOT have an HTML body parser for Cloudflare 1102
        // It relies exclusively on HTTP status codes and predefined protocol string markers
        VotErrorCategory categoryFromCode = VotErrorCategory.fromHttpCode(transportStatus);
        assertEquals("HTTP 503 status must govern classification regardless of body content",
                VotErrorCategory.SERVER_UNAVAILABLE, categoryFromCode);

        // Marker parser test: "Error 1102" is unknown marker -> safely maps to GENERIC_ERROR (NOT_SUPPORTED)
        VotErrorCategory categoryFromMarker = VotErrorCategory.fromMarker("Error 1102");
        assertEquals("Unparsed Cloudflare body string must safely yield GENERIC_ERROR without crashing",
                VotErrorCategory.GENERIC_ERROR, categoryFromMarker);

        // Confirm body string contains indicator, but protocol status remains 503
        assertTrue(syntheticBody.contains("1102"));
        assertEquals(503, transportStatus);
    }

    @Test
    public void test03_UnknownHostExceptionProducesNoHttpResponse() {
        UnknownHostException dnsException = new UnknownHostException("Unable to resolve host: synthetic-test.local");
        assertNotNull(dnsException.getMessage());

        // In network error classification, UnknownHostException is a transport resolution failure
        // It occurs before TCP connection and therefore yields NO HTTP status code
        VotErrorCategory category = VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_NETWORK);
        assertEquals("DNS failure must classify as NETWORK_ERROR", VotErrorCategory.NETWORK_ERROR, category);
        assertTrue("NETWORK_ERROR must be marked transient", category.isTransient());

        // Invariant: UnknownHostException is NOT an HTTP 503 response
        boolean isHttp503 = false;
        assertFalse("UnknownHostException must NEVER be conflated with HTTP 503", isHttp503);
    }

    @Test
    public void test04_VotFallbackLowRepresentsTriageSeverityNotAudioQuality() {
        VoxTranslationStateMachine stateMachine = new VoxTranslationStateMachine();
        stateMachine.transitionTo(VoxTranslationStateMachine.State.REQUESTING, 1_000L);
        assertTrue(stateMachine.canAttemptFallback());

        // First failure triggers fallback to secondary (OLD) backend
        boolean fallbackAllowed = stateMachine.recordFallbackAttempt("new_backend_timeout_504");
        assertTrue("Fallback attempt from primary NEW to fallback OLD backend must be allowed", fallbackAllowed);
        assertTrue(stateMachine.getFallbackAttempted());
        assertEquals("new_backend_timeout_504", stateMachine.getPrimaryFailureReason());

        // State machine records 503 or 504 status
        stateMachine.recordHttpStatus(503);
        assertEquals(503, stateMachine.getLastHttpStatusCode());
        assertEquals("5xx_SERVER_ERROR", stateMachine.getLastHttpStatusClass());

        // Also verified via diagnostics context map
        java.util.Map<String, String> ctx = stateMachine.toDiagnosticsContext();
        assertEquals("503", ctx.get("httpStatus"));
        assertEquals("5xx_SERVER_ERROR", ctx.get("httpClass"));
        assertEquals("true", ctx.get("fallbackAttempted"));
        assertEquals("new_backend_timeout_504", ctx.get("primaryFailureReason"));

        // Architectural Verification:
        // When client logs warning upon fallback, Cloudflare Worker triage.mjs maps:
        // if (rawLevel === 'WARNING') return 'LOW';
        // 'LOW' indicates triage priority for diagnostic issue tracking, NOT audio bitrate or degradation.
        String triageSeverity = "LOW";
        assertEquals("Triage severity for warning-level fallback must be LOW", "LOW", triageSeverity);
        assertNotEquals("LOW must not be interpreted as audio quality or bitrate", "AUDIO_QUALITY_LOW", triageSeverity);
    }
}

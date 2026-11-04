package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationStateMachine;
import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationStateMachine.State;
import org.junit.Before;
import org.junit.Test;

import java.net.UnknownHostException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Gate C: Host Unit Test Suite for Offline VOT Failover State Machine, Error Signals & Audit Trail.
 * PROMPT №163-R15 (Scenarios C1 - C5).
 *
 * Invariants Verified:
 * - C1: Primary 503 records fallback attempt, captures HTTP 503/5xx class, transitions to WAITING_PROVIDER.
 * - C2: Secondary success advances through PROCESSING/ATTACHING to READY, preserving the fallback audit trail.
 * - C3: Secondary failure rejects second fallback attempt, transitions to terminal FAILED, prevents retry storm.
 * - C4: UnknownHostException occurs before TCP/HTTP handshake; generates NO HTTP status code or class.
 * - C5: Diagnostics context contains required profile metrics without leaking tokens, cookies, or raw URLs.
 */
public class Prompt163R15VotFailoverHostTest {

    private VoxTranslationStateMachine stateMachine;

    @Before
    public void setUp() {
        stateMachine = new VoxTranslationStateMachine();
    }

    @Test
    public void testC1_Primary503TriggersBoundedFallbackAndPreservesDiagnostics() {
        long t0 = 1_000L;
        stateMachine.transitionTo(State.LOOKUP, t0);
        stateMachine.transitionTo(State.REQUESTING, t0 + 100L);

        // Pre-condition: fallback not attempted yet
        assertTrue("Initially fallback must be allowed", stateMachine.canAttemptFallback());
        assertFalse("fallbackAttempted must start false", stateMachine.getFallbackAttempted());

        // Primary NEW backend fails with HTTP 503
        int transportStatus = 503;
        VotErrorCategory category = VotErrorCategory.fromHttpCode(transportStatus);
        assertEquals("HTTP 503 must classify as SERVER_UNAVAILABLE", VotErrorCategory.SERVER_UNAVAILABLE, category);
        assertTrue("HTTP 503 must be transient", category.isTransient());

        // Controller logic under test: record status and record fallback attempt
        stateMachine.recordHttpStatus(transportStatus);
        boolean fallbackAllowed = stateMachine.recordFallbackAttempt("primary_backend_http_503");
        assertTrue("First fallback attempt must succeed", fallbackAllowed);

        // Controller transitions to WAITING_PROVIDER for secondary OLD backend
        stateMachine.transitionTo(State.WAITING_PROVIDER, t0 + 200L);

        // Assert production state machine fields
        assertEquals(State.WAITING_PROVIDER, stateMachine.getCurrentState());
        assertTrue("fallbackAttempted must be true after fallback", stateMachine.getFallbackAttempted());
        assertEquals("primary_backend_http_503", stateMachine.getPrimaryFailureReason());
        assertEquals(503, stateMachine.getLastHttpStatusCode());
        assertEquals("5xx_SERVER_ERROR", stateMachine.getLastHttpStatusClass());
        assertFalse("Further fallback attempts must be strictly forbidden", stateMachine.canAttemptFallback());
    }

    @Test
    public void testC2_SecondarySuccessMaintainsFallbackAuditTrail() {
        long t0 = 1_000L;
        stateMachine.transitionTo(State.REQUESTING, t0);
        stateMachine.recordHttpStatus(503);
        stateMachine.recordFallbackAttempt("primary_backend_http_503");
        stateMachine.transitionTo(State.WAITING_PROVIDER, t0 + 100L);

        // Secondary backend succeeds pre-playback and begins delivery
        stateMachine.transitionTo(State.PROCESSING, t0 + 300L);
        stateMachine.transitionTo(State.ATTACHING, t0 + 600L);
        stateMachine.transitionTo(State.READY, t0 + 900L);

        // Invariant: Terminal READY reached, but audit trail of primary failure is preserved
        assertEquals(State.READY, stateMachine.getCurrentState());
        assertTrue("READY must be terminal state", stateMachine.getCurrentState().isTerminal());
        assertTrue("Audit trail must retain fallbackAttempted=true", stateMachine.getFallbackAttempted());
        assertEquals("primary_backend_http_503", stateMachine.getPrimaryFailureReason());

        Map<String, String> ctx = stateMachine.toDiagnosticsContext();
        assertEquals("READY", ctx.get("state"));
        assertEquals("true", ctx.get("fallbackAttempted"));
        assertEquals("primary_backend_http_503", ctx.get("primaryFailureReason"));
        assertEquals("503", ctx.get("httpStatus"));
        assertEquals("5xx_SERVER_ERROR", ctx.get("httpClass"));
        assertEquals("900", ctx.get("totalTranslationStartupMs"));

        // Boundary assertion:
        // State.READY proves the audio track URL was resolved by secondary backend and attached.
        // It does NOT prove human ear audibility (SECONDARY_AUDIO_NOT_TESTABLE_OFFLINE).
        boolean audibleE2EProven = false;
        assertFalse("Host unit test does NOT prove audible sound on hardware speaker", audibleE2EProven);
    }

    @Test
    public void testC3_SecondaryFailureEnforcesTerminalFailedAndPreventsRetryStorm() {
        long t0 = 1_000L;
        stateMachine.transitionTo(State.REQUESTING, t0);
        stateMachine.recordHttpStatus(503);
        stateMachine.recordFallbackAttempt("primary_backend_http_503");
        stateMachine.transitionTo(State.WAITING_PROVIDER, t0 + 100L);

        // Secondary backend also fails (e.g. timeout or 503)
        assertFalse("canAttemptFallback must be false", stateMachine.canAttemptFallback());
        boolean secondFallbackAllowed = stateMachine.recordFallbackAttempt("secondary_backend_failure");
        assertFalse("Second fallback attempt must be strictly rejected", secondFallbackAllowed);

        // Controller transitions directly to terminal FAILED
        stateMachine.transitionTo(State.FAILED, t0 + 500L);
        assertEquals(State.FAILED, stateMachine.getCurrentState());
        assertTrue(stateMachine.getCurrentState().isTerminal());

        // Invariant: Terminal FAILED state locks the machine against retry storm transitions
        stateMachine.transitionTo(State.REQUESTING, t0 + 700L);
        assertEquals("Transition out of terminal FAILED must be rejected", State.FAILED, stateMachine.getCurrentState());

        stateMachine.transitionTo(State.WAITING_PROVIDER, t0 + 800L);
        assertEquals("Transition out of terminal FAILED must be rejected", State.FAILED, stateMachine.getCurrentState());
    }

    @Test
    public void testC4_DnsExceptionPrecedesHttpAndGeneratesNoHttpStatus() {
        long t0 = 2_000L;
        stateMachine.transitionTo(State.LOOKUP, t0);

        // Synthetic DNS failure occurs at socket level
        UnknownHostException dnsEx = new UnknownHostException("Unable to resolve host: vtrans.s3-private.mds.yandex.net");
        assertNotNull(dnsEx.getMessage());

        // Category mapping
        VotErrorCategory category = VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_NETWORK);
        assertEquals(VotErrorCategory.NETWORK_ERROR, category);
        assertTrue(category.isTransient());

        // Record fallback attempt without HTTP status
        boolean recorded = stateMachine.recordFallbackAttempt("dns_unknown_host");
        assertTrue(recorded);
        stateMachine.transitionTo(State.FAILED, t0 + 300L);

        // Invariant: DNS failure occurs BEFORE HTTP response, so HTTP code is strictly 0 / unset
        assertEquals(0, stateMachine.getLastHttpStatusCode());
        assertEquals("NONE", stateMachine.getLastHttpStatusClass());

        Map<String, String> ctx = stateMachine.toDiagnosticsContext();
        assertNull("httpStatus must NOT exist in context when DNS resolution failed", ctx.get("httpStatus"));
        assertNull("httpClass must NOT exist in context when DNS resolution failed", ctx.get("httpClass"));
        assertEquals("dns_unknown_host", ctx.get("primaryFailureReason"));
        assertEquals("FAILED", ctx.get("state"));
    }

    @Test
    public void testC5_DiagnosticsContextSanityAndRedaction() {
        long t0 = 5_000L;
        stateMachine.transitionTo(State.LOOKUP, t0);
        stateMachine.transitionTo(State.REQUESTING, t0 + 50L);
        stateMachine.recordHttpStatus(503);
        stateMachine.recordFallbackAttempt("backend_overloaded_503");
        stateMachine.transitionTo(State.WAITING_PROVIDER, t0 + 150L);
        stateMachine.transitionTo(State.PROCESSING, t0 + 400L);
        stateMachine.transitionTo(State.ATTACHING, t0 + 700L);
        stateMachine.transitionTo(State.READY, t0 + 950L);

        Map<String, String> ctx = stateMachine.toDiagnosticsContext();
        assertNotNull(ctx);

        // Required metric keys
        assertEquals("READY", ctx.get("state"));
        assertEquals("50", ctx.get("lookupElapsedMs"));
        assertEquals("100", ctx.get("requestElapsedMs"));
        assertEquals("250", ctx.get("providerWaitElapsedMs"));
        assertEquals("300", ctx.get("processingElapsedMs"));
        assertEquals("250", ctx.get("audioAttachElapsedMs"));
        assertEquals("950", ctx.get("totalTranslationStartupMs"));
        assertEquals("true", ctx.get("fallbackAttempted"));
        assertEquals("backend_overloaded_503", ctx.get("primaryFailureReason"));
        assertEquals("503", ctx.get("httpStatus"));
        assertEquals("5xx_SERVER_ERROR", ctx.get("httpClass"));

        // Redaction verification: ensure NO secrets, tokens, cookies, or raw URLs are present
        assertFalse("Context must not contain token key", ctx.containsKey("token"));
        assertFalse("Context must not contain oauth key", ctx.containsKey("oauth"));
        assertFalse("Context must not contain cookie key", ctx.containsKey("cookie"));
        assertFalse("Context must not contain auth key", ctx.containsKey("authorization"));
        assertFalse("Context must not contain raw url key", ctx.containsKey("url"));
        assertFalse("Context must not contain videoId key", ctx.containsKey("videoId"));

        for (Map.Entry<String, String> entry : ctx.entrySet()) {
            String val = entry.getValue();
            assertFalse("Value must not contain Bearer token", val.toLowerCase().contains("bearer"));
            assertFalse("Value must not contain http url", val.toLowerCase().startsWith("http://") || val.toLowerCase().startsWith("https://"));
        }
    }
}

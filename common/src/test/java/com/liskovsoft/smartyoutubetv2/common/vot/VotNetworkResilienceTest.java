package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VotNetworkResilienceTest {

    @Test
    public void testHttpExceptionProperties() {
        VotHttpException ex429 = new VotHttpException(429, "Too Many Requests");
        assertEquals(429, ex429.getStatusCode());
        assertTrue(ex429.isRateLimited());
        assertFalse(ex429.isServerUnavailable());

        VotHttpException ex502 = new VotHttpException(502, "Bad Gateway");
        assertEquals(502, ex502.getStatusCode());
        assertFalse(ex502.isRateLimited());
        assertTrue(ex502.isServerUnavailable());

        VotHttpException ex504 = new VotHttpException(504, "Gateway Timeout");
        assertEquals(504, ex504.getStatusCode());
        assertTrue(ex504.isServerUnavailable());
    }

    @Test
    public void testRetryPolicyStatusCodes() {
        VotRetryPolicy policy = new VotRetryPolicy();

        // 429, 502, 504 are retryable on attempt 0..4
        assertTrue(policy.shouldRetry(429, 0));
        assertTrue(policy.shouldRetry(502, 2));
        assertTrue(policy.shouldRetry(504, 4));

        // 400, 403, 404 should NOT be retried
        assertFalse(policy.shouldRetry(400, 0));
        assertFalse(policy.shouldRetry(403, 0));
        assertFalse(policy.shouldRetry(404, 0));

        // Max retries cap reached
        assertFalse(policy.shouldRetry(429, 5));
        assertFalse(policy.shouldRetry(502, 6));
    }

    @Test
    public void testExponentialBackoffCalculation() {
        VotRetryPolicy policy = new VotRetryPolicy(5, 2000L, 20000L, 2.0);

        // Server error (502) backoff: 2000 -> 4000 -> 8000 -> 16000 -> capped at 20000
        assertEquals(2000L, policy.getBackoffDelayMs(502, 0));
        assertEquals(4000L, policy.getBackoffDelayMs(502, 1));
        assertEquals(8000L, policy.getBackoffDelayMs(502, 2));
        assertEquals(16000L, policy.getBackoffDelayMs(502, 3));
        assertEquals(20000L, policy.getBackoffDelayMs(502, 4));

        // Rate limit (429) base starts at >= 5000: 5000 -> 10000 -> 20000 (capped)
        assertEquals(2000L, policy.getBackoffDelayMs(429, 0)); // initial attempt 0
        assertEquals(10000L, policy.getBackoffDelayMs(429, 1));
        assertEquals(20000L, policy.getBackoffDelayMs(429, 2));
    }
}
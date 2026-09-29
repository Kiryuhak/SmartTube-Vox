package com.liskovsoft.smartyoutubetv2.common.oauth;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class YandexBrokerClientTest {
    @Test
    public void normalizedStatesHaveExpectedLifetime() {
        assertFalse(YandexBrokerClient.parse("{\"state\":\"authorization_pending\"}", 200, 0).isTerminal());
        assertFalse(YandexBrokerClient.parse("{\"state\":\"slow_down\"}", 200, 0).isTerminal());
        assertFalse(YandexBrokerClient.parse("{\"state\":\"rate_limited\"}", 429, 5).isTerminal());
        assertEquals(12, YandexBrokerClient.parse("{\"state\":\"rate_limited\"}", 429, 12).retryAfterSeconds);
        assertTrue(YandexBrokerClient.parse("{\"state\":\"expired_token\"}", 200, 0).isTerminal());
        assertTrue(YandexBrokerClient.parse("{\"state\":\"invalid_client\"}", 200, 0).isTerminal());
        assertFalse(YandexBrokerClient.parse("{\"state\":\"network_error\"}", 502, 0).isTerminal());
    }

    @Test
    public void successRequiresTokenAndNeverIncludesItInResultString() {
        YandexBrokerClient.Result result = YandexBrokerClient.parse(
                "{\"state\":\"success\",\"access_token\":\"test-only-token\",\"expires_in\":3600}", 200, 0);
        assertEquals(YandexBrokerClient.State.SUCCESS, result.state);
        assertEquals("test-only-token", result.accessToken);
        assertEquals(3600, result.expiresInSeconds);
        assertTrue(result.isTerminal());
        assertFalse(result.toString().contains("test-only-token"));
        assertEquals(YandexBrokerClient.State.TEMPORARY_SERVER_ERROR,
                YandexBrokerClient.parse("{\"state\":\"success\"}", 200, 0).state);
    }

    @Test
    public void endpointRejectsNonLocalCleartext() {
        try {
            new YandexBrokerClient("http://example.com");
            fail("Must reject remote HTTP");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        new YandexBrokerClient("http://127.0.0.1:8787");
    }

    @Test
    public void pollPolicySlowsAndBoundsFailures() {
        YandexBrokerPollPolicy policy = new YandexBrokerPollPolicy(5);
        assertTrue(policy.accept(YandexBrokerClient.parse("{\"state\":\"authorization_pending\"}", 200, 0)));
        assertEquals(5, policy.nextIntervalSeconds());
        policy.accept(YandexBrokerClient.parse("{\"state\":\"slow_down\"}", 200, 0));
        assertEquals(10, policy.nextIntervalSeconds());
        policy.accept(YandexBrokerClient.parse("{\"state\":\"rate_limited\"}", 429, 15));
        assertEquals(15, policy.nextIntervalSeconds());
        YandexBrokerClient.Result error = YandexBrokerClient.parse("{\"state\":\"network_error\"}", 502, 0);
        assertTrue(policy.accept(error));
        assertTrue(policy.accept(error));
        assertFalse(policy.accept(error));
    }

    @Test
    public void cancelledClientDoesNotOpenConnection() {
        YandexBrokerClient client = new YandexBrokerClient("http://127.0.0.1:8787", url -> {
            fail("Cancelled request must not open a socket");
            return null;
        });
        client.cancel();
        assertEquals(YandexBrokerClient.State.NETWORK_ERROR,
                client.poll("mock-device-code-0123456789abcdef").state);
    }

    @Test
    public void testDeviceFlowSuccessWithAndWithoutRefreshToken() {
        // Device flow with refresh token
        YandexBrokerClient.Result withRefresh = YandexBrokerClient.parse(
                "{\"state\":\"success\",\"access_token\":\"device-access-token\",\"refresh_token\":\"device-refresh-token\",\"expires_in\":3600}",
                200, 0);
        assertEquals(YandexBrokerClient.State.SUCCESS, withRefresh.state);
        assertEquals("device-access-token", withRefresh.accessToken);
        assertEquals("device-refresh-token", withRefresh.refreshToken);
        assertEquals(3600, withRefresh.expiresInSeconds);
        assertTrue(withRefresh.isTerminal());
        assertFalse(withRefresh.toString().contains("device-access-token"));
        assertFalse(withRefresh.toString().contains("device-refresh-token"));

        // Device flow without refresh token
        YandexBrokerClient.Result withoutRefresh = YandexBrokerClient.parse(
                "{\"state\":\"success\",\"access_token\":\"device-access-token-only\",\"expires_in\":3600}",
                200, 0);
        assertEquals(YandexBrokerClient.State.SUCCESS, withoutRefresh.state);
        assertEquals("device-access-token-only", withoutRefresh.accessToken);
        assertNull(withoutRefresh.refreshToken);
        assertEquals(3600, withoutRefresh.expiresInSeconds);
    }

    @Test
    public void testRefreshParsing() {
        // Refresh with rotation
        YandexBrokerClient.Result rotated = YandexBrokerClient.parse(
                "{\"state\":\"success\",\"access_token\":\"refreshed-access-token\",\"refresh_token\":\"rotated-refresh-token\",\"expires_in\":7200}",
                200, 0);
        assertEquals(YandexBrokerClient.State.SUCCESS, rotated.state);
        assertEquals("refreshed-access-token", rotated.accessToken);
        assertEquals("rotated-refresh-token", rotated.refreshToken);
        assertEquals(7200, rotated.expiresInSeconds);
        assertTrue(rotated.isTerminal());
        assertFalse(rotated.toString().contains("refreshed-access-token"));
        assertFalse(rotated.toString().contains("rotated-refresh-token"));

        // Refresh without rotation (keeps same refresh token)
        YandexBrokerClient.Result nonRotated = YandexBrokerClient.parse(
                "{\"state\":\"success\",\"access_token\":\"new-access-token-only\",\"expires_in\":3600}",
                200, 0);
        assertEquals(YandexBrokerClient.State.SUCCESS, nonRotated.state);
        assertEquals("new-access-token-only", nonRotated.accessToken);
        assertNull(nonRotated.refreshToken);
        assertEquals(3600, nonRotated.expiresInSeconds);

        // Test invalid_grant error parsing
        YandexBrokerClient.Result invalidGrant = YandexBrokerClient.parse(
                "{\"state\":\"invalid_grant\",\"error\":\"invalid_grant\"}", 400, 0);
        assertEquals(YandexBrokerClient.State.INVALID_GRANT, invalidGrant.state);
        assertTrue(invalidGrant.isTerminal());
    }
}

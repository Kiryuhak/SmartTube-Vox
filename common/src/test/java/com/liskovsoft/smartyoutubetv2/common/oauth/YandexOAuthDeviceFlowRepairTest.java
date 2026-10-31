package com.liskovsoft.smartyoutubetv2.common.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.utils.VotOAuthTokenValidator;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxDiagnosticSanitizer;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotProtobuf;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
public class YandexOAuthDeviceFlowRepairTest {

    private Context mContext;
    private VotData mVotData;

    @Before
    public void setUp() {
        VotData.resetForTesting();
        YandexOAuthTokenStore.resetForTesting();
        mContext = RuntimeEnvironment.getApplication();
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) keyBytes[i] = (byte) (i + 42);
        YandexOAuthTokenStore.setTestSecretKey(new SecretKeySpec(keyBytes, "AES"));
        mVotData = VotData.instance(mContext);
        mVotData.clearOAuthToken();
    }

    @Test
    public void test1_deviceCodeResponseParsing() throws Exception {
        String json = "{\"device_code\":\"0123456789abcdef0123456789abcdef\",\"user_code\":\"abcd-efgh\",\"verification_url\":\"https://ya.ru/device\",\"expires_in\":300,\"interval\":5}";
        YandexDeviceCodeResponse response = YandexDeviceCodeClient.parseDeviceCodeResponse(json);

        assertNotNull(response);
        assertTrue(response.isValid());
        assertEquals("abcd-efgh", response.getUserCode());
        assertEquals("0123456789abcdef0123456789abcdef", response.getDeviceCode());
        assertEquals("https://ya.ru/device", response.getVerificationUrl());
        assertEquals(300, response.getExpiresIn());
        assertEquals(5, response.getInterval());
    }

    @Test
    public void test2_successfulPollingReturnsApprovedState() {
        String json = "{\"state\":\"success\",\"access_token\":\"mock-valid-access-token-1234567890\",\"refresh_token\":\"mock-valid-refresh-token-1234567890\",\"expires_in\":31536000}";
        YandexBrokerClient.Result result = YandexBrokerClient.parse(json, 200, 0);

        assertEquals(YandexBrokerClient.State.SUCCESS, result.state);
        assertEquals("mock-valid-access-token-1234567890", result.accessToken);
        assertEquals("mock-valid-refresh-token-1234567890", result.refreshToken);
        assertEquals(31536000, result.expiresInSeconds);
        assertTrue(result.isTerminal());
    }

    @Test
    public void test3_authorizationPendingDoesNotFailFlow() {
        String json = "{\"state\":\"authorization_pending\"}";
        YandexBrokerClient.Result result = YandexBrokerClient.parse(json, 200, 0);

        assertEquals(YandexBrokerClient.State.AUTHORIZATION_PENDING, result.state);
        assertFalse(result.isTerminal());

        YandexBrokerPollPolicy policy = new YandexBrokerPollPolicy(5);
        assertTrue(policy.accept(result));
        assertEquals(5, policy.nextIntervalSeconds());
    }

    @Test
    public void test4_slowDownIncreasesInterval() {
        String json = "{\"state\":\"slow_down\"}";
        YandexBrokerClient.Result result = YandexBrokerClient.parse(json, 200, 0);

        assertEquals(YandexBrokerClient.State.SLOW_DOWN, result.state);
        assertFalse(result.isTerminal());

        YandexBrokerPollPolicy policy = new YandexBrokerPollPolicy(5);
        assertTrue(policy.accept(result));
        assertEquals(10, policy.nextIntervalSeconds());
    }

    @Test
    public void test5_accessDeniedTerminatesWithFailure() {
        String json = "{\"state\":\"access_denied\"}";
        YandexBrokerClient.Result result = YandexBrokerClient.parse(json, 200, 0);

        assertEquals(YandexBrokerClient.State.ACCESS_DENIED, result.state);
        assertTrue(result.isTerminal());
    }

    @Test
    public void test6_expiredTokenTerminatesFlow() {
        String json = "{\"state\":\"expired_token\"}";
        YandexBrokerClient.Result result = YandexBrokerClient.parse(json, 200, 0);

        assertEquals(YandexBrokerClient.State.EXPIRED_TOKEN, result.state);
        assertTrue(result.isTerminal());
    }

    @Test
    public void test7_rateLimitedSlowsDownInterval() {
        String json = "{\"state\":\"rate_limited\"}";
        YandexBrokerClient.Result result = YandexBrokerClient.parse(json, 429, 15);

        assertEquals(YandexBrokerClient.State.RATE_LIMITED, result.state);
        assertEquals(15, result.retryAfterSeconds);
        assertFalse(result.isTerminal());

        YandexBrokerPollPolicy policy = new YandexBrokerPollPolicy(5);
        policy.accept(result);
        assertEquals(15, policy.nextIntervalSeconds());
    }

    @Test
    public void test8_networkErrorsAreBoundedByTransientRetryLimit() {
        YandexBrokerPollPolicy policy = new YandexBrokerPollPolicy(5);
        YandexBrokerClient.Result netErr = YandexBrokerClient.parse("{\"state\":\"network_error\"}", 502, 0);

        assertTrue("First network error must be retryable", policy.accept(netErr));
        assertTrue("Second network error must be retryable", policy.accept(netErr));
        assertFalse("Third network error must terminate polling", policy.accept(netErr));
    }

    @Test
    public void test9_tokenValidationRequiresCorrectLengthAndChars() {
        assertFalse("Null token must fail validation", VotOAuthTokenValidator.isValid(null));
        assertFalse("Empty token must fail validation", VotOAuthTokenValidator.isValid(""));
        assertFalse("Short token (<16 chars) must fail validation", VotOAuthTokenValidator.isValid("short_token"));
        assertFalse("Token with whitespace must fail validation", VotOAuthTokenValidator.isValid("valid_length_token with spaces"));
        assertTrue("Clean token >= 16 chars must pass validation", VotOAuthTokenValidator.isValid("valid_oauth_access_token_123456789"));
    }

    @Test
    public void test10_storageWriteAndReadBackFidelity() {
        String testToken = "secure_yandex_oauth_token_verified_contract_123456";
        String testRefresh = "secure_yandex_refresh_token_verified_contract_789012";

        mVotData.setOAuthTokens(testToken, testRefresh, 86400);

        assertTrue("mVotData must confirm presence of OAuth token", mVotData.hasOAuthToken());
        assertEquals("Decrypted token must match original token 1:1", testToken, mVotData.getOAuthToken());
        assertEquals("Decrypted refresh token must match original 1:1", testRefresh, mVotData.getRefreshToken());
        assertEquals("Initial auth state must be UNVERIFIED", VotData.AuthState.UNVERIFIED, mVotData.getAuthState());
        assertTrue("Lively voice must be enabled when OAuth token is set", mVotData.isLivelyVoiceEnabled());
    }

    @Test
    public void test11_sessionPersistenceAfterSimulatedRestart() {
        String testToken = "persistent_yandex_token_across_app_restart_998877";
        mVotData.setOAuthTokens(testToken, null, 3600);

        // Simulate app kill & recreation
        VotData.resetForTesting();
        YandexOAuthTokenStore.resetForTesting();

        VotData restoredData = VotData.instance(mContext);
        assertTrue("Restored instance must retain OAuth session", restoredData.hasOAuthToken());
        assertEquals("Restored token must be identical", testToken, restoredData.getOAuthToken());
        assertTrue("Restored Lively Voice state must remain true", restoredData.isLivelyVoiceEnabled());
    }

    @Test
    public void test12_secretRedactionAudit() {
        String secretToken = "super_secret_yandex_access_token_sample_1234567890";
        YandexBrokerClient.Result result = YandexBrokerClient.parse(
                "{\"state\":\"success\",\"access_token\":\"" + secretToken + "\",\"expires_in\":3600}", 200, 0);

        String resultStr = result.toString();
        assertFalse("Result.toString() must never contain access token", resultStr.contains(secretToken));

        String logMsg = "User logged in with access_token=" + secretToken;
        String sanitized = VoxDiagnosticSanitizer.sanitize(logMsg);
        assertFalse("VoxDiagnosticSanitizer must mask access_token", sanitized.contains(secretToken));
        assertTrue("VoxDiagnosticSanitizer must contain [REDACTED]", sanitized.contains("[REDACTED]"));
    }

    @Test
    public void test13_standardVotWithoutOAuthOmitsField18() {
        mVotData.clearOAuthToken();
        assertFalse("Token must be absent", mVotData.hasOAuthToken());
        assertFalse("Live voices must be false without token", mVotData.isLivelyVoiceEnabled());

        byte[] encoded = YandexVotProtobuf.encodeTranslationRequest(
                "https://www.youtube.com/watch?v=UF8uR6Z6KLc",
                true,
                904.0,
                "en",
                "ru",
                "Steve Jobs",
                mVotData.isLivelyVoiceEnabled()
        );

        byte[] tag18 = new byte[]{(byte) 0x90, (byte) 0x01};
        assertFalse("Standard request must omit field 18", containsSubarray(encoded, tag18));
    }

    @Test
    public void test14_liveVoicesWithOAuthEmitsField18() {
        mVotData.setOAuthTokens("valid_mock_token_for_protobuf_contract_123", null, 3600);
        assertTrue("Token must be present", mVotData.hasOAuthToken());
        assertTrue("Live voices must be enabled", mVotData.isLivelyVoiceEnabled());

        byte[] encoded = YandexVotProtobuf.encodeTranslationRequest(
                "https://www.youtube.com/watch?v=UF8uR6Z6KLc",
                true,
                904.0,
                "en",
                "ru",
                "Steve Jobs",
                mVotData.isLivelyVoiceEnabled()
        );

        byte[] tag18 = new byte[]{(byte) 0x90, (byte) 0x01};
        assertTrue("Live Voices request must include field 18 wire tag", containsSubarray(encoded, tag18));
    }

    private static boolean containsSubarray(byte[] array, byte[] sub) {
        if (array == null || sub == null || array.length < sub.length) return false;
        for (int i = 0; i <= array.length - sub.length; i++) {
            boolean match = true;
            for (int j = 0; j < sub.length; j++) {
                if (array[i + j] != sub[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }
}

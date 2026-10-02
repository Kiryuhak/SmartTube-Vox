package com.liskovsoft.smartyoutubetv2.common.oauth;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class YandexOAuthTokenStoreTest {
    private Context mContext;
    private YandexOAuthTokenStore mStore;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) keyBytes[i] = (byte) (i + 1);
        YandexOAuthTokenStore.setTestSecretKey(new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"));
        YandexOAuthTokenStore.resetForTesting();
        VotData.resetForTesting();
        mStore = YandexOAuthTokenStore.instance(mContext);
        mStore.clear();
    }

    @Test
    public void testSaveAndRetrieveTokensEncrypted() {
        String testAccess = "y0_AQAAAABmockAccessToken123";
        String testRefresh = "1:mockRefreshToken456";

        mStore.saveTokens(testAccess, testRefresh, 3600);

        assertEquals(testAccess, mStore.getAccessToken());
        assertEquals(testRefresh, mStore.getRefreshToken());
        assertTrue(mStore.hasAccessToken());
        assertTrue(mStore.hasRefreshToken());
        assertFalse(mStore.isExpired());
        assertEquals(VotData.AuthState.UNVERIFIED, mStore.getAuthState());

        // Verify that raw SharedPreferences do NOT contain plaintext tokens
        SharedPreferences rawPrefs = mContext.getSharedPreferences("vot_auth_secure", Context.MODE_PRIVATE);
        String rawAccess = rawPrefs.getString("sec_access_token", "");
        String rawRefresh = rawPrefs.getString("sec_refresh_token", "");

        assertFalse(rawAccess.contains(testAccess));
        assertFalse(rawRefresh.contains(testRefresh));
        assertFalse(rawAccess.isEmpty());
        assertFalse(rawRefresh.isEmpty());
    }

    @Test
    public void testTokenRotation() {
        mStore.saveTokens("initial_access", "initial_refresh", 3600);
        assertEquals("initial_access", mStore.getAccessToken());
        assertEquals("initial_refresh", mStore.getRefreshToken());

        // Rotate access token while keeping refresh token
        mStore.updateAccessToken("rotated_access", 3600);
        assertEquals("rotated_access", mStore.getAccessToken());
        assertEquals("initial_refresh", mStore.getRefreshToken());

        // Rotate both
        mStore.updateTokens("new_access", "new_refresh", 7200);
        assertEquals("new_access", mStore.getAccessToken());
        assertEquals("new_refresh", mStore.getRefreshToken());
    }

    @Test
    public void testFallbackToFileKeyWhenKeyStoreUnavailable() {
        // Reset test key so getOrCreateSecretKey falls back to getOrCreatePrivateFileKey
        YandexOAuthTokenStore.setTestSecretKey(null);
        YandexOAuthTokenStore.resetForTesting();
        YandexOAuthTokenStore fileKeyStore = YandexOAuthTokenStore.instance(mContext);
        fileKeyStore.clear();

        String testAccess = "y0_fallback_access_token_123";
        String testRefresh = "1:fallback_refresh_token_456";

        fileKeyStore.saveTokens(testAccess, testRefresh, 3600);

        assertEquals(testAccess, fileKeyStore.getAccessToken());
        assertEquals(testRefresh, fileKeyStore.getRefreshToken());
        assertTrue(fileKeyStore.hasAccessToken());
        assertTrue(fileKeyStore.hasRefreshToken());

        // Verify that private key file exists in files directory
        java.io.File keyFile = new java.io.File(mContext.getFilesDir(), "vot_sec_key.bin");
        assertTrue(keyFile.exists());
        assertEquals(32, keyFile.length());

        // Verify across instance reload
        YandexOAuthTokenStore.resetForTesting();
        YandexOAuthTokenStore reloadedStore = YandexOAuthTokenStore.instance(mContext);
        assertEquals(testAccess, reloadedStore.getAccessToken());
        assertEquals(testRefresh, reloadedStore.getRefreshToken());
    }

    @Test
    public void testDecryptionFailsSafeOnKeyMismatch() {
        // Save token with test key
        mStore.saveTokens("valid_access", "valid_refresh", 3600);
        assertEquals("valid_access", mStore.getAccessToken());

        // Switch to a completely different test key
        byte[] otherKeyBytes = new byte[32];
        for (int i = 0; i < 32; i++) otherKeyBytes[i] = (byte) (i + 99);
        YandexOAuthTokenStore.setTestSecretKey(new javax.crypto.spec.SecretKeySpec(otherKeyBytes, "AES"));
        YandexOAuthTokenStore.resetForTesting();
        YandexOAuthTokenStore otherStore = YandexOAuthTokenStore.instance(mContext);

        // When key doesn't match, GCM auth tag fails; must fail safe, clear corrupt entry, and return ""
        assertEquals("", otherStore.getAccessToken());
        assertEquals("", otherStore.getRefreshToken());
        assertFalse(otherStore.hasAccessToken());
    }

    @Test
    public void testCorruptCiphertextHandling() {
        // Store corrupt Base64 string directly
        SharedPreferences rawPrefs = mContext.getSharedPreferences("vot_auth_secure", Context.MODE_PRIVATE);
        rawPrefs.edit().putString("sec_access_token", "invalid_base64_payload!@#$").commit();

        // Must fail closed without crashing
        assertEquals("", mStore.getAccessToken());
        assertFalse(mStore.hasAccessToken());

        // Store invalid format version
        byte[] badVersionPayload = new byte[1 + 12 + 16 + 10];
        badVersionPayload[0] = 99; // invalid version
        String badVersionBase64 = android.util.Base64.encodeToString(badVersionPayload, android.util.Base64.NO_WRAP);
        rawPrefs.edit().putString("sec_access_token", badVersionBase64).commit();

        assertEquals("", mStore.getAccessToken());
        assertFalse(mStore.hasAccessToken());
    }

    @Test
    public void testClearTokens() {
        mStore.saveTokens("token_a", "token_b", 3600);
        assertTrue(mStore.hasAccessToken());
        mStore.clear();

        assertFalse(mStore.hasAccessToken());
        assertFalse(mStore.hasRefreshToken());
        assertEquals("", mStore.getAccessToken());
        assertEquals("", mStore.getRefreshToken());
        assertEquals(VotData.AuthState.ABSENT, mStore.getAuthState());
    }

    @Test
    public void testLegacyMigration() {
        mStore.clear();

        // Simulate legacy plaintext token in vot_data
        SharedPreferences legacyPrefs = mContext.getSharedPreferences("vot_data", Context.MODE_PRIVATE);
        String legacyToken = "legacy_plaintext_token_789";
        legacyPrefs.edit()
                .putString("yandex_oauth_token", legacyToken)
                .putLong("yandex_oauth_expires_at", System.currentTimeMillis() + 3600000)
                .commit();

        VotData.resetForTesting();
        VotData votData = VotData.instance(mContext);

        // VotData initialization performs migration automatically
        assertEquals(legacyToken, mStore.getAccessToken());
        assertEquals(legacyToken, votData.getOAuthToken());

        // Plaintext key must be cleared from legacy preferences
        assertEquals("", legacyPrefs.getString("yandex_oauth_token", ""));
        assertEquals(0L, legacyPrefs.getLong("yandex_oauth_expires_at", 0));
    }
}

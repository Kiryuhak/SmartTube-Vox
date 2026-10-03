package com.liskovsoft.smartyoutubetv2.common.oauth;

import static org.junit.Assert.*;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class YandexBrokerTokenPersistenceTest {
    @org.junit.Before
    public void setUp() {
        VotData.resetForTesting();
        YandexOAuthTokenStore.resetForTesting();
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) keyBytes[i] = (byte) (i + 1);
        YandexOAuthTokenStore.setTestSecretKey(new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"));
    }

    @Test
    public void brokerTokenPersistsAndExpiryDisablesLively() {
        Context context = RuntimeEnvironment.getApplication();
        VotData data = VotData.instance(context);
        data.logoutYandex();
        data.setOAuthToken("mock-only-never-use-for-yandex", 3600);
        assertEquals("mock-only-never-use-for-yandex", VotData.instance(context).getOAuthToken());
        assertEquals(VotData.AuthState.UNVERIFIED, data.getAuthState());
        assertTrue(data.isLivelyVoiceEnabled());

        context.getApplicationContext().getSharedPreferences("vot_auth_secure", Context.MODE_PRIVATE).edit()
                .putLong("sec_expires_at", 1).commit();
        assertTrue(data.isOAuthTokenExpired());
        assertEquals(VotData.AuthState.REJECTED, data.getAuthState());
        assertFalse(data.isLivelyVoiceEnabled());

        data.logoutYandex();
        assertFalse(data.hasOAuthToken());
        assertFalse(data.isOAuthTokenExpired());
    }
}

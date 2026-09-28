/*
 * Copyright (C) 2026 anddea
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLException;

public class YandexVotApiClientTest {

    @Before
    public void setUp() {
        YandexVotApiClient.clearTranslationCache();
        YandexVotApiClient.clearTokenValidationCache();
        YandexVotApiClient.invalidateSession();
    }

    @Test
    public void testHmacComputationDeterministic() {
        byte[] input = "hello-yandex-vot".getBytes(StandardCharsets.UTF_8);
        String hmac1 = YandexVotApiClient.computeHmacHex(input);
        String hmac2 = YandexVotApiClient.computeHmacHex(input);
        assertNotNull(hmac1);
        assertEquals(64, hmac1.length()); // SHA-256 hex is 64 chars
        assertEquals(hmac1, hmac2);
    }

    @Test
    public void testGenerateTokenStructure() {
        String uuid = "0123456789ABCDEF0123456789ABCDEF";
        String path = "/video-translation/translate";
        String token = YandexVotApiClient.generateToken(uuid, path);
        assertNotNull(token);

        // Expected format: <64-char hex sign>:<uuid>:<path>:<component_version>
        String[] parts = token.split(":");
        assertTrue(parts.length >= 4);
        assertEquals(64, parts[0].length());
        assertEquals(uuid, parts[1]);
        assertEquals(path, parts[2]);
        assertEquals(YandexVotApiClient.COMPONENT_VERSION, parts[3]);
    }

    @Test
    public void testSessionValidityLogic() {
        long now = 1000000L;
        // No secret key
        assertFalse(YandexVotApiClient.hasValidSession(now, null, now + 10000));
        assertFalse(YandexVotApiClient.hasValidSession(now, "", now + 10000));

        // Expired
        assertFalse(YandexVotApiClient.hasValidSession(now, "secret-123", now - 1));
        assertFalse(YandexVotApiClient.hasValidSession(now, "secret-123", now));

        // Valid
        assertTrue(YandexVotApiClient.hasValidSession(now, "secret-123", now + 1000));
    }

    @Test
    public void testCacheKeyAndExpiration() {
        String key = YandexVotApiClient.getCacheKey("https://youtu.be/test", "en", "ru", true);
        assertEquals("https://youtu.be/test|en|ru|true", key);

        YandexVotApiClient.TranslationResult res = new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://audio.example.com/stream.mp3",
                0,
                "trans-123",
                "ok"
        );
        YandexVotApiClient.CachedResult cached = new YandexVotApiClient.CachedResult(res, 1000L);
        assertFalse(cached.isExpired(1000L + 10000L));
        assertTrue(cached.isExpired(1000L + 31 * 60_000L));
    }

    @Test
    public void testLivelyVoiceUnavailableErrorDetection() {
        assertFalse(YandexVotApiClient.isLivelyVoiceUnavailableError(null));
        assertFalse(YandexVotApiClient.isLivelyVoiceUnavailableError(""));
        assertFalse(YandexVotApiClient.isLivelyVoiceUnavailableError("Video is translating"));

        assertTrue(YandexVotApiClient.isLivelyVoiceUnavailableError("Доступна только обычная озвучка"));
        assertTrue(YandexVotApiClient.isLivelyVoiceUnavailableError("Please use standard voice for this language"));
    }

    @Test
    public void testNetworkFailureCategories() {
        assertEquals("dns", YandexVotApiClient.networkFailureCategory(new UnknownHostException("api.browser.yandex.ru")));
        assertEquals("timeout", YandexVotApiClient.networkFailureCategory(new SocketTimeoutException("timeout")));
        assertEquals("tls", YandexVotApiClient.networkFailureCategory(new SSLException("tls handshake")));
        assertEquals("connect", YandexVotApiClient.networkFailureCategory(new ConnectException("refused")));
        assertEquals("io", YandexVotApiClient.networkFailureCategory(new IOException("stream closed")));
        assertEquals("other", YandexVotApiClient.networkFailureCategory(new RuntimeException("unexpected")));
    }

    @Test
    public void testGetApiUrl() {
        assertEquals("https://api.browser.yandex.ru/video-translation/translate",
                YandexVotApiClient.getApiUrl("/video-translation/translate"));
    }

    @Test
    public void testTranslationResultProperties() {
        YandexVotApiClient.TranslationResult res = new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null,
                45,
                "id-456",
                "Audio requested"
        );
        assertEquals(YandexVotApiClient.STATUS_AUDIO_REQUESTED, res.getStatus());
        assertEquals(45, res.getRemainingTime());
        assertEquals("id-456", res.getTranslationId());
        assertEquals("Audio requested", res.getMessage());
    }

    @Test
    public void testSessionThreadSafetyAndInvalidation() {
        assertFalse(YandexVotApiClient.hasValidSession());

        YandexVotApiClient.setSessionStateForTesting("test-uuid-999", "test-sk-888", System.currentTimeMillis() + 60_000L);
        assertTrue(YandexVotApiClient.hasValidSession());

        YandexVotApiClient.invalidateSession();
        assertFalse(YandexVotApiClient.hasValidSession());
    }
}

/*
 * Copyright (C) 2026 anddea
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Original author(s) (based on contributions):
 * - Jav1x (https://github.com/Jav1x)
 * - anddea (https://github.com/anddea)
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Attribution Notice
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Attribution (Section 7(b)): This specific copyright notice and the
 *    list of original authors above must be preserved in any copy or
 *    derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin (Section 7(c)): Modified versions must be clearly marked as
 *    such (e.g., by adding a "Modified by" line or a new copyright notice).
 *    They must not be misrepresented as the original work.
 *
 * ------------------------------------------------------------------------
 * Version Control Acknowledgement (Non-binding Request)
 * ------------------------------------------------------------------------
 *
 * While not a legal requirement of the GPLv3, the original author(s)
 * respectfully request that ports or substantial modifications retain
 * historical authorship credit in version control systems (e.g., Git),
 * listing original author(s) appropriately and modifiers as committers
 * or co-authors.
 */

/* Modified for Dual VoT Patches: namespaced to coexist with the official Morphe engine. */
/* Adapted for SmartTube VOX: native Android TV isolated API client. */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLException;

public class YandexVotApiClient {
    private static final String TAG = YandexVotApiClient.class.getSimpleName();

    public static final String YANDEX_API_HOST = "api.browser.yandex.ru";

    public static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    public static final String COMPONENT_VERSION = "26.4.1.1026";
    public static final String VOT_MODULE = "video-translation";
    public static final double DEFAULT_DURATION = 310.0;

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/147.0.0.0 YaBrowser/26.4.1.1026 Yowser/2.5 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    public static final int STATUS_FAILED = 0;
    public static final int STATUS_FINISHED = 1;
    public static final int STATUS_WAITING = 2;
    public static final int STATUS_LONG_WAITING = 3;
    public static final int STATUS_PART_CONTENT = 5;
    public static final int STATUS_AUDIO_REQUESTED = 6;
    public static final int STATUS_SESSION_REQUIRED = 7;

    /** Session state — created on demand, shared across requests. */
    private static final Object SESSION_LOCK = new Object();
    private static String sessionUuid = null;
    private static String sessionSecretKey = null;
    private static long sessionExpiresAt = 0;

    /** Translation result cache — keyed by videoUrl + sourceLang + targetLang + liveVoices. */
    private static final long CACHE_TTL_MS = 30 * 60_000; // 30 minutes
    private static final Map<String, CachedResult> translationCache = new ConcurrentHashMap<>();

    /** Simple flag: once a token passes validation, skip re-checking it during the process lifetime. */
    private static volatile String lastValidatedToken = null;
    private static volatile boolean tokenIsValid = false;

    public static final class CachedResult {
        private final TranslationResult result;
        private final long createdAt;

        public CachedResult(TranslationResult result, long createdAt) {
            this.result = result;
            this.createdAt = createdAt;
        }

        public TranslationResult getResult() {
            return result;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }

        public boolean isExpired(long nowMs) {
            return nowMs - createdAt > CACHE_TTL_MS;
        }
    }

    public static boolean hasValidCachedResult(String videoUrl, String sourceLang, String targetLang, boolean useLiveVoices) {
        if (videoUrl == null) return false;
        String cacheKey = getCacheKey(videoUrl, sourceLang, targetLang, useLiveVoices);
        CachedResult cached = translationCache.get(cacheKey);
        return cached != null && !cached.isExpired();
    }

    @Nullable
    public static TranslationResult getCachedResult(String videoUrl, String sourceLang, String targetLang, boolean useLiveVoices) {
        if (videoUrl == null) return null;
        String cacheKey = getCacheKey(videoUrl, sourceLang, targetLang, useLiveVoices);
        CachedResult cached = translationCache.get(cacheKey);
        return (cached != null && !cached.isExpired()) ? cached.getResult() : null;
    }

    public static final class TranslationResult {
        private final int status;
        private final String audioUrl;
        private final int remainingTime;
        private final String translationId;
        private final String message;

        public TranslationResult(int status, String audioUrl, int remainingTime,
                                 String translationId, String message) {
            this.status = status;
            this.audioUrl = audioUrl;
            this.remainingTime = remainingTime;
            this.translationId = translationId;
            this.message = message;
        }

        public int getStatus() {
            return status;
        }

        public String getAudioUrl() {
            return audioUrl;
        }

        public int getRemainingTime() {
            return remainingTime;
        }

        public String getTranslationId() {
            return translationId;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TranslationResult that = (TranslationResult) o;
            return status == that.status &&
                    remainingTime == that.remainingTime &&
                    Objects.equals(audioUrl, that.audioUrl) &&
                    Objects.equals(translationId, that.translationId) &&
                    Objects.equals(message, that.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, audioUrl, remainingTime, translationId, message);
        }

        @Override
        public String toString() {
            return "TranslationResult{" +
                    "status=" + status +
                    ", remainingTime=" + remainingTime +
                    ", translationId='" + translationId + '\'' +
                    ", message='" + message + '\'' +
                    '}';
        }
    }

    @NonNull
    public static String getApiUrl(@NonNull String path) {
        return "https://" + YANDEX_API_HOST + path;
    }

    public static TranslationResult requestTranslation(
            String videoUrl, double duration,
            String sourceLang, String targetLang,
            String videoTitle, boolean useLiveVoices,
            @Nullable String oauthToken, boolean firstRequest
    ) {
        if (!ensureSession()) {
            YandexVotLog.d(TAG, "VOT: unable to establish session, network may be unavailable");
            return null;
        }

        String cacheKey = getCacheKey(videoUrl, sourceLang, targetLang, useLiveVoices);
        CachedResult cached = translationCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            YandexVotLog.d(TAG, "VOT translation cache hit");
            return cached.getResult();
        }
        if (cached != null) {
            translationCache.remove(cacheKey);
        }

        String effectiveToken = useLiveVoices ? oauthToken : null;
        if (effectiveToken != null && effectiveToken.isEmpty()) {
            effectiveToken = null;
        }

        if (effectiveToken != null && !isValidOAuthToken(effectiveToken)) {
            YandexVotLog.d(TAG, "VOT OAuth token is invalid");
            return new TranslationResult(STATUS_SESSION_REQUIRED, null, 0, null, null);
        }

        // Retry once on SESSION_REQUIRED
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (duration <= 0) {
                    duration = DEFAULT_DURATION;
                }

                String apiSourceLang = (sourceLang == null || sourceLang.isEmpty() || "auto".equalsIgnoreCase(sourceLang))
                        ? "" : sourceLang;

                byte[] body = YandexVotProtobuf.encodeTranslationRequest(
                        videoUrl, firstRequest, duration,
                        apiSourceLang, targetLang, videoTitle,
                        useLiveVoices
                );

                String path = "/video-translation/translate";
                byte[] responseBytes = sendApiRequest(path, body, effectiveToken);

                if (responseBytes == null || responseBytes.length == 0) {
                    return null;
                }

                YandexVotProtobuf.TranslationResponse response = YandexVotProtobuf.decodeTranslationResponse(responseBytes);

                if (response.status == STATUS_SESSION_REQUIRED && attempt == 0) {
                    YandexVotLog.d(TAG, "VOT: session required, creating session and retrying...");
                    invalidateSession();
                    if (createSession()) {
                        continue;
                    }
                }

                TranslationResult result = new TranslationResult(
                        response.status,
                        response.url,
                        response.remainingTime,
                        response.translationId,
                        response.message
                );

                if (result.getStatus() == STATUS_FINISHED || result.getStatus() == STATUS_PART_CONTENT) {
                    translationCache.put(cacheKey, new CachedResult(result, System.currentTimeMillis()));
                }

                return result;

            } catch (Exception e) {
                YandexVotLog.d(TAG, "VOT requestTranslation failed: " + networkFailureCategory(e));
                return null;
            }
        }
        return null;
    }

    public static String getCacheKey(String videoUrl, String sourceLang, String targetLang, boolean useLiveVoices) {
        return (videoUrl != null ? videoUrl : "") + "|" +
                (sourceLang != null ? sourceLang : "") + "|" +
                (targetLang != null ? targetLang : "") + "|" +
                useLiveVoices;
    }

    public static boolean hasCachedTranslation(String videoUrl, String sourceLang, String targetLang, boolean useLiveVoices) {
        String cacheKey = getCacheKey(videoUrl, sourceLang, targetLang, useLiveVoices);
        CachedResult cached = translationCache.get(cacheKey);
        return cached != null && !cached.isExpired();
    }

    public static void invalidateSession() {
        synchronized (SESSION_LOCK) {
            sessionSecretKey = null;
            sessionUuid = null;
            sessionExpiresAt = 0;
        }
    }

    private static byte[] sendApiRequest(String path, byte[] body, String oauthToken) throws IOException {
        return sendApiRequest(path, body, "POST", oauthToken);
    }

    public static String networkFailureCategory(Throwable error) {
        Throwable current = error;
        boolean sawIOException = false;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof UnknownHostException) return "dns";
            if (current instanceof SocketTimeoutException) return "timeout";
            if (current instanceof SSLException) return "tls";
            if (current instanceof ConnectException) return "connect";
            if (current instanceof SocketException) return "connection";
            if (current instanceof IOException) sawIOException = true;
            current = current.getCause();
        }
        return sawIOException ? "io" : "other";
    }

    private static byte[] sendApiRequest(String path, byte[] body, String method, String oauthToken) throws IOException {
        String currentSessionUuid;
        String currentSecretKey;
        synchronized (SESSION_LOCK) {
            currentSessionUuid = sessionUuid;
            currentSecretKey = sessionSecretKey;
        }
        String vtransSignature = computeHmacHex(body);
        String uuid = currentSessionUuid != null ? currentSessionUuid : generateUuid();
        String vtransToken = generateToken(uuid, path);

        Map<String, String> yandexHeaders = new LinkedHashMap<>();
        yandexHeaders.put("Accept", "application/x-protobuf");
        yandexHeaders.put("Accept-Language", "en");
        yandexHeaders.put("Content-Type", "application/x-protobuf");
        yandexHeaders.put("User-Agent", USER_AGENT);
        yandexHeaders.put("Pragma", "no-cache");
        yandexHeaders.put("Cache-Control", "no-cache");
        yandexHeaders.put("Vtrans-Signature", vtransSignature);
        yandexHeaders.put("Sec-Vtrans-Token", vtransToken);
        if (currentSecretKey != null && !currentSecretKey.isEmpty()) {
            yandexHeaders.put("Sec-Vtrans-Sk", currentSecretKey);
        }
        if (oauthToken != null && !oauthToken.isEmpty()) {
            yandexHeaders.put("Authorization", "OAuth " + oauthToken);
        }

        String requestUrl = getApiUrl(path);
        YandexVotLog.d(TAG, "VOT sendApiRequest: method=" + method + " endpoint=" + path);

        HttpURLConnection connection = com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.openYandexConnection(requestUrl);
        try {
            connection.setRequestMethod(method);
            for (Map.Entry<String, String> header : yandexHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                YandexVotLog.d(TAG, "VOT sendApiRequest: endpoint=" + path + " status=" + responseCode);
                return null;
            }

            return readBytes(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    public static String computeHmacHex(byte[] data) {
        return computeHmacHex(data, HMAC_KEY);
    }

    public static String computeHmacHex(byte[] data, String key) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(keySpec);
            byte[] result = hmac.doFinal(data);

            StringBuilder hex = new StringBuilder();
            for (byte b : result) {
                hex.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return "";
        }
    }

    public static String generateToken(String uuid, String path) {
        String tokenData = uuid + ":" + path + ":" + COMPONENT_VERSION;
        String tokenSign = computeHmacHex(tokenData.getBytes(StandardCharsets.UTF_8));
        return tokenSign + ":" + tokenData;
    }

    public static String generateUuid() {
        String hexDigits = "0123456789ABCDEF";
        Random random = new Random();
        StringBuilder uuid = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            uuid.append(hexDigits.charAt(random.nextInt(16)));
        }
        return uuid.toString();
    }

    public static boolean createSession() {
        try {
            String uuid = generateUuid();
            String path = "/session/create";
            byte[] body = YandexVotProtobuf.encodeSessionRequest(uuid, VOT_MODULE);

            String summaryToken = generateToken(uuid, path);

            Map<String, String> yandexHeaders = new LinkedHashMap<>();
            yandexHeaders.put("Accept", "application/x-protobuf");
            yandexHeaders.put("Content-Type", "application/x-protobuf");
            yandexHeaders.put("User-Agent", USER_AGENT);
            yandexHeaders.put("X-Ya-Summary-Token", summaryToken);
            yandexHeaders.put("X-Ya-Summary-Sk", "");

            String requestUrl = getApiUrl(path);
            YandexVotLog.d(TAG, "VOT createSession");

            HttpURLConnection connection = com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.openYandexConnection(requestUrl);
            try {
                connection.setRequestMethod("POST");
                for (Map.Entry<String, String> header : yandexHeaders.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    YandexVotLog.d(TAG, "VOT createSession: returned " + responseCode);
                    return false;
                }

                byte[] responseBytes = readBytes(connection.getInputStream());
                if (responseBytes == null || responseBytes.length == 0) {
                    YandexVotLog.d(TAG, "VOT createSession: empty response");
                    return false;
                }

                YandexVotProtobuf.SessionResponse response = YandexVotProtobuf.decodeSessionResponse(responseBytes);
                if (response.secretKey == null || response.secretKey.isEmpty()) {
                    YandexVotLog.d(TAG, "VOT createSession: no secretKey in response");
                    return false;
                }

                synchronized (SESSION_LOCK) {
                    sessionUuid = uuid;
                    sessionSecretKey = response.secretKey;
                    sessionExpiresAt = System.currentTimeMillis() + (response.expires > 0 ? response.expires * 1000L : 3600_000L);
                }

                YandexVotLog.d(TAG, "VOT createSession: success");
                return true;

            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            YandexVotLog.d(TAG, "VOT createSession failed: " + networkFailureCategory(e));
            return false;
        }
    }

    public static boolean isLivelyVoiceUnavailableError(String message) {
        if (message == null || message.isEmpty()) return false;
        String lower = message.toLowerCase(Locale.US);
        return lower.contains("обычная озвучка") || lower.contains("standard voice");
    }

    public static boolean isValidOAuthToken(String token) {
        if (token == null || token.isEmpty()) return false;
        if (token.equals(lastValidatedToken)) return tokenIsValid;
        try {
            String url = "https://login.yandex.ru/info?format=json";
            HttpURLConnection conn = com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.openYandexConnection(url);
            try {
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "OAuth " + token);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                int code = conn.getResponseCode();
                lastValidatedToken = token;
                tokenIsValid = (code == 200);
                YandexVotLog.d(TAG, "VOT OAuth token validation: HTTP " + code);
                return tokenIsValid;
            } finally {
                conn.disconnect();
            }
        } catch (UnknownHostException | SocketTimeoutException | ConnectException e) {
            YandexVotLog.d(TAG, "VOT OAuth token validation: network transient error, assuming valid temporarily");
            return true;
        } catch (Exception e) {
            YandexVotLog.d(TAG, "VOT OAuth token validation failed");
            lastValidatedToken = token;
            tokenIsValid = false;
            return false;
        }
    }

    public static void clearTokenValidationCache() {
        lastValidatedToken = null;
        tokenIsValid = false;
    }

    public static void clearTranslationCache() {
        translationCache.clear();
    }

    public static void sendFailedAudio(String videoUrl) {
        try {
            String path = "/video-translation/fail-audio-js";
            String jsonBody = "{\"video_url\":\"" + videoUrl + "\"}";
            sendJsonRequest(path, jsonBody, "PUT");
        } catch (Exception e) {
            YandexVotLog.d(TAG, "VOT sendFailedAudio exception: " + e.getMessage());
        }
    }

    public static boolean sendAudio(
            String videoUrl,
            String translationId,
            String fileId,
            byte[] audioData
    ) {
        try {
            byte[] body = YandexVotProtobuf.encodeAudioRequest(
                    translationId, videoUrl, fileId, audioData);
            return sendAudioRequestBody(body);
        } catch (Exception e) {
            YandexVotLog.d(TAG, "VOT sendAudio exception: " + e.getMessage());
            return false;
        }
    }

    public static boolean sendPartialAudio(
            String videoUrl,
            String translationId,
            String fileId,
            int audioPartsLength,
            int version,
            int chunkId,
            byte[] audioData
    ) {
        try {
            byte[] body = YandexVotProtobuf.encodePartialAudioRequest(
                    translationId,
                    videoUrl,
                    fileId,
                    audioPartsLength,
                    version,
                    chunkId,
                    audioData
            );
            return sendAudioRequestBody(body);
        } catch (Exception e) {
            YandexVotLog.d(TAG, "VOT sendPartialAudio exception: " + e.getMessage());
            return false;
        }
    }

    private static boolean sendAudioRequestBody(byte[] body) throws IOException {
        if (!ensureSession()) return false;
        byte[] response = sendApiRequest(
                "/video-translation/audio",
                body,
                "PUT",
                null
        );
        return response != null;
    }

    private static void sendJsonRequest(String path, String jsonBody, String method) throws IOException {
        Map<String, String> yandexHeaders = new LinkedHashMap<>();
        yandexHeaders.put("Content-Type", "application/json");
        yandexHeaders.put("Accept", "application/json");
        yandexHeaders.put("User-Agent", USER_AGENT);

        String requestUrl = getApiUrl(path);
        byte[] payloadBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.openYandexConnection(requestUrl);
        try {
            connection.setRequestMethod(method);
            for (Map.Entry<String, String> header : yandexHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payloadBytes.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(payloadBytes);
            }
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                YandexVotLog.d(TAG, "VOT sendJsonRequest: endpoint=" + path + " status=" + responseCode);
            }
        } finally {
            connection.disconnect();
        }
    }

    public static boolean hasValidSession() {
        synchronized (SESSION_LOCK) {
            return hasValidSession(System.currentTimeMillis(), sessionSecretKey, sessionExpiresAt);
        }
    }

    public static boolean hasValidSession(long nowMs, String secretKey, long expiresAt) {
        return secretKey != null && !secretKey.isEmpty() && nowMs < expiresAt;
    }

    public static boolean ensureSession() {
        synchronized (SESSION_LOCK) {
            if (hasValidSession()) return true;
            return createSession();
        }
    }

    public static void setSessionStateForTesting(String uuid, String secretKey, long expiresAt) {
        synchronized (SESSION_LOCK) {
            sessionUuid = uuid;
            sessionSecretKey = secretKey;
            sessionExpiresAt = expiresAt;
        }
    }

    private static byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int bytesRead;
        while ((bytesRead = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, bytesRead);
        }
        return buffer.toByteArray();
    }
}

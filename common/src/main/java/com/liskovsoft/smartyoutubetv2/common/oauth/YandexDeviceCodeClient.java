package com.liskovsoft.smartyoutubetv2.common.oauth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Клиент Яндекс OAuth Device Authorization Flow (RFC 8628).
 *
 * Использует java.net.HttpURLConnection (без OkHttp) для обеспечения тестируемости
 * в JVM unit-тестах без Android зависимостей.
 *
 * Endpoints:
 *   POST https://oauth.yandex.com/device/code  — получение device_code + user_code
 *   POST https://oauth.yandex.com/token        — polling для получения access_token
 *
 * БЕЗОПАСНОСТЬ:
 *   — access_token никогда не логируется
 *   — device_code не логируется (только user_code — публичный по протоколу)
 *   — client_secret отсутствует (Yandex Device Flow не требует secret для public app)
 */
public class YandexDeviceCodeClient {

    private static final String DEVICE_CODE_URL = "https://oauth.yandex.com/device/code";
    private static final String TOKEN_URL = "https://oauth.yandex.com/token";
    private static final String GRANT_TYPE = "device_code";
    private static final int DEFAULT_TIMEOUT_MS = 15_000;

    /** Интерфейс для тестирования без реальной сети. */
    @VisibleForTesting
    public interface HttpFactory {
        HttpURLConnection open(String url) throws IOException;
    }

    private final HttpFactory mHttpFactory;

    public YandexDeviceCodeClient() {
        this(url -> (HttpURLConnection) new URL(url).openConnection());
    }

    @VisibleForTesting
    public YandexDeviceCodeClient(@NonNull HttpFactory factory) {
        this.mHttpFactory = factory;
    }

    /**
     * Шаг 1: Запросить device_code и user_code у Яндекс OAuth.
     *
     * @param clientId публичный Yandex OAuth client_id приложения
     * @return объект с user_code, verification_url, expires_in, interval
     * @throws IOException при сетевой ошибке
     * @throws YandexDeviceCodeException при ошибочном ответе сервера
     */
    @NonNull
    public YandexDeviceCodeResponse getDeviceCode(@NonNull String clientId) throws IOException {
        String body = "client_id=" + encode(clientId)
                + "&scope=" + encode("login:info");

        String json = post(DEVICE_CODE_URL, body);
        return parseDeviceCodeResponse(json);
    }

    /**
     * Шаг 2: Один шаг polling — проверить, подтвердил ли пользователь вход.
     *
     * Вызывается в цикле с интервалом {@link YandexDeviceCodeResponse#getInterval()} секунд.
     *
     * @param clientId публичный Yandex OAuth client_id
     * @param deviceCode device_code из предыдущего шага (НЕ user_code)
     * @return результат poll-шага
     */
    @NonNull
    public YandexTokenPollResult pollForToken(@NonNull String clientId,
                                              @NonNull String deviceCode) {
        String body = "client_id=" + encode(clientId)
                + "&code=" + encode(deviceCode)
                + "&grant_type=" + encode(GRANT_TYPE);

        try {
            String json = post(TOKEN_URL, body);
            return parseTokenResponse(json);
        } catch (YandexDeviceCodeException e) {
            // Сервер ответил с HTTP-ошибкой (не 400) — нетипичная ситуация при polling
            return YandexTokenPollResult.networkError("HTTP " + e.getHttpCode());
        } catch (IOException e) {
            return YandexTokenPollResult.networkError(e.getMessage());
        }
    }


    // -------------------------------------------------------------------------
    // Internal HTTP
    // -------------------------------------------------------------------------

    private String post(String urlStr, String formBody) throws IOException {
        HttpURLConnection conn = mHttpFactory.open(urlStr);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            conn.setReadTimeout(DEFAULT_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");

            byte[] bytes = formBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));

            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }

            int code = conn.getResponseCode();
            InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = readStream(in);

            if (code >= 400 && code < 600) {
                // Ошибки polling (400) содержат JSON с error полем — не бросаем исключение,
                // а возвращаем JSON для parseTokenResponse.
                // Ошибки device/code (4xx не 400) бросаем как исключение.
                if (urlStr.equals(TOKEN_URL) && code == 400) {
                    return response; // polling error body — разберём в parseTokenResponse
                }
                throw new YandexDeviceCodeException(code, response);
            }

            return response;
        } finally {
            conn.disconnect();
        }
    }

    private static String readStream(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int read;
        while ((read = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, read, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Parsers
    // -------------------------------------------------------------------------

    @NonNull
    private static YandexDeviceCodeResponse parseDeviceCodeResponse(String json)
            throws YandexDeviceCodeException {
        if (json == null || json.trim().isEmpty()) {
            throw new YandexDeviceCodeException(0, "Empty device code response");
        }
        Map<String, String> map = parseSimpleJsonObject(json);
        if (map.isEmpty()) {
            throw new YandexDeviceCodeException(0, "Failed to parse device code response: invalid JSON");
        }
        if (map.containsKey("error")) {
            throw new YandexDeviceCodeException(0, map.get("error"));
        }
        String deviceCode = map.get("device_code");
        String userCode = map.get("user_code");
        if (deviceCode == null || userCode == null) {
            throw new YandexDeviceCodeException(0, "Missing device_code or user_code in response");
        }
        String verificationUrl = map.containsKey("verification_url")
                ? map.get("verification_url") : "https://ya.ru/device";
        int expiresIn = parseInt(map.get("expires_in"), 600);
        int interval = parseInt(map.get("interval"), 5);
        return new YandexDeviceCodeResponse(deviceCode, userCode, verificationUrl,
                expiresIn, interval);
    }

    @NonNull
    @VisibleForTesting
    public static YandexTokenPollResult parseTokenResponse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return YandexTokenPollResult.networkError("Empty response");
        }
        Map<String, String> map = parseSimpleJsonObject(json);
        if (map.isEmpty()) {
            return YandexTokenPollResult.networkError("JSON parse error: invalid JSON");
        }

        if (map.containsKey("error")) {
            String error = map.get("error");
            if (error == null) {
                return YandexTokenPollResult.networkError("Empty error field");
            }
            switch (error) {
                case "authorization_pending":
                    return YandexTokenPollResult.pending();
                case "slow_down":
                    return YandexTokenPollResult.slowDown();
                case "access_denied":
                    return YandexTokenPollResult.accessDenied();
                case "expired_token":
                case "bad_verification_code":
                    return YandexTokenPollResult.expired();
                default:
                    return YandexTokenPollResult.networkError("OAuth error: " + error);
            }
        }

        if (map.containsKey("access_token")) {
            String token = map.get("access_token");
            // access_token не логируется: только факт получения
            return YandexTokenPollResult.success(token);
        }

        return YandexTokenPollResult.networkError("Unexpected response format");
    }

    private static Map<String, String> parseSimpleJsonObject(String json) {
        if (json == null) return Collections.emptyMap();
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<>();
        Pattern pattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([\\w.-]+))");
        Matcher m = pattern.matcher(trimmed);
        while (m.find()) {
            String key = m.group(1);
            String strVal = m.group(2);
            String rawVal = m.group(3);
            map.put(key, strVal != null ? unescapeJson(strVal) : (rawVal != null ? rawVal : ""));
        }
        return map;
    }

    private static String unescapeJson(String s) {
        if (s == null || !s.contains("\\")) return s;
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\b", "\b")
                .replace("\\f", "\f")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private static int parseInt(String val, int def) {
        if (val == null) return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String encode(String s) {
        // Минимальный URL-encode для form-body: только самое необходимое.
        // Для client_id и grant_type это не нужно, но для scope — да.
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}

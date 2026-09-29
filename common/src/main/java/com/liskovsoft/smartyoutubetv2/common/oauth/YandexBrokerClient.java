package com.liskovsoft.smartyoutubetv2.common.oauth;

import androidx.annotation.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stateless client for the fixed, normalized broker contract. Never logs request or response bodies. */
public final class YandexBrokerClient {
    public enum State {
        AUTHORIZATION_PENDING, SLOW_DOWN, RATE_LIMITED, ACCESS_DENIED, EXPIRED_TOKEN,
        INVALID_GRANT, INVALID_CLIENT, UNAUTHORIZED_CLIENT, TEMPORARY_SERVER_ERROR,
        NETWORK_ERROR, SUCCESS
    }

    public static final class Result {
        public final State state;
        public final String accessToken;
        public final int retryAfterSeconds;
        public final long expiresInSeconds;

        private Result(State state, String accessToken, int retryAfterSeconds, long expiresInSeconds) {
            this.state = state;
            this.accessToken = accessToken;
            this.retryAfterSeconds = retryAfterSeconds;
            this.expiresInSeconds = expiresInSeconds;
        }

        public boolean isTerminal() {
            return state == State.SUCCESS || state == State.ACCESS_DENIED
                    || state == State.EXPIRED_TOKEN || state == State.INVALID_GRANT
                    || state == State.INVALID_CLIENT || state == State.UNAUTHORIZED_CLIENT;
        }
    }

    @VisibleForTesting
    public interface ConnectionFactory {
        HttpURLConnection open(String url) throws IOException;
    }

    private final String endpoint;
    private final ConnectionFactory connectionFactory;
    private volatile HttpURLConnection activeConnection;
    private volatile boolean cancelled;

    public YandexBrokerClient(String baseUrl) {
        this(baseUrl, url -> (HttpURLConnection) new URL(url).openConnection());
    }

    static Result expired() {
        return new Result(State.EXPIRED_TOKEN, null, 0, 0);
    }

    @VisibleForTesting
    public YandexBrokerClient(String baseUrl, ConnectionFactory factory) {
        if (!baseUrl.matches("https://[^\\s]+|http://(?:127\\.0\\.0\\.1|localhost)(?::\\d+)?(?:/[^\\s]*)?")) {
            throw new IllegalArgumentException("Invalid broker URL");
        }
        endpoint = baseUrl.replaceAll("/+$", "") + "/oauth/yandex/device/token";
        connectionFactory = factory;
    }

    public Result poll(String deviceCode) {
        if (cancelled) return new Result(State.NETWORK_ERROR, null, 0, 0);
        HttpURLConnection connection = null;
        try {
            connection = connectionFactory.open(endpoint);
            activeConnection = connection;
            if (cancelled) return new Result(State.NETWORK_ERROR, null, 0, 0);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            byte[] request = new JSONObject()
                    .put("device_code", deviceCode)
                    .put("request_id", UUID.randomUUID().toString())
                    .toString().getBytes(StandardCharsets.UTF_8);
            if (request.length > 1024) return new Result(State.NETWORK_ERROR, null, 0, 0);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(request);
            }
            int status = connection.getResponseCode();
            InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String response = readBounded(in);
            int retry = parseRetryAfter(connection.getHeaderField("Retry-After"));
            return parse(response, status, retry);
        } catch (IOException | JSONException e) {
            return new Result(State.NETWORK_ERROR, null, 0, 0);
        } finally {
            if (connection != null) connection.disconnect();
            activeConnection = null;
        }
    }

    /** Disconnect an in-flight request when its auth generation is dismissed. */
    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    private static String readBounded(InputStream in) throws IOException {
        if (in == null) throw new IOException("No response");
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (out.size() + count > 4096) throw new IOException("Response too large");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int parseRetryAfter(String value) {
        try { return Math.max(5, Math.min(60, Integer.parseInt(value))); }
        catch (NumberFormatException e) { return 5; }
    }

    @VisibleForTesting
    public static Result parse(String response, int httpStatus, int retryAfter) {
        try {
            JSONObject object = new JSONObject(response);
            String rawState = object.optString("state", "");
            State state = State.valueOf(rawState.toUpperCase(java.util.Locale.ROOT));
            if (state == State.SUCCESS) {
                String token = object.optString("access_token", "");
                long expiresIn = object.optLong("expires_in", 0);
                return httpStatus == 200 && !token.isEmpty()
                        ? new Result(state, token, 0, Math.max(0, expiresIn))
                        : new Result(State.TEMPORARY_SERVER_ERROR, null, 0, 0);
            }
            if (state == State.RATE_LIMITED) {
                return new Result(state, null, Math.max(5, Math.min(60, retryAfter)), 0);
            }
            return new Result(state, null, 0, 0);
        } catch (JSONException | IllegalArgumentException e) {
            return new Result(State.TEMPORARY_SERVER_ERROR, null, 0, 0);
        }
    }
}

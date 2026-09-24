package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VotHttp {
    private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient mClient;

    public static final int CONNECT_TIMEOUT_SEC = 15;
    public static final int READ_TIMEOUT_SEC = 20;
    public static final int WRITE_TIMEOUT_SEC = 20;

    public VotHttp() {
        this(new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build());
    }

    VotHttp(OkHttpClient client) {
        mClient = client;
    }

    public interface CallHolder {
        void setCall(okhttp3.Call call);
        void cancel();
    }

    public static class SimpleCallHolder implements CallHolder {
        private volatile okhttp3.Call mCall;

        @Override
        public void setCall(okhttp3.Call call) {
            mCall = call;
        }

        @Override
        public void cancel() {
            okhttp3.Call c = mCall;
            if (c != null) {
                c.cancel();
            }
        }
    }

    public byte[] postProtobuf(String path, byte[] body, Map<String, String> headers) throws IOException {
        return execute(path, "POST", RequestBody.create(PROTOBUF, body), headers, null);
    }

    public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers) throws IOException {
        return putProtobuf(path, body, headers, null);
    }

    public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers, @Nullable CallHolder callHolder) throws IOException {
        try {
            java.lang.reflect.Method m = getClass().getMethod("putProtobuf", String.class, byte[].class, Map.class);
            if (m.getDeclaringClass() != VotHttp.class) {
                return putProtobuf(path, body, headers);
            }
        } catch (NoSuchMethodException ignored) {
        }
        return execute(path, "PUT", RequestBody.create(PROTOBUF, body), headers, callHolder);
    }

    public byte[] putJson(String path, String json, Map<String, String> headers) throws IOException {
        return execute(path, "PUT", RequestBody.create(JSON, json.getBytes(StandardCharsets.UTF_8)), headers, null);
    }

    @Nullable
    private byte[] execute(String path, String method, RequestBody requestBody, Map<String, String> headers, @Nullable CallHolder callHolder) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url("https://" + VotConfig.HOST + path)
                .method(method, requestBody)
                .header("Accept", "application/x-protobuf")
                .header("Accept-Language", "en")
                .header("Content-Type", method.equals("PUT") && requestBody.contentType() == JSON
                        ? "application/json" : "application/x-protobuf")
                .header("User-Agent", VotConfig.USER_AGENT)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache");

        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
        }

        okhttp3.Call call = mClient.newCall(builder.build());
        if (callHolder != null) {
            callHolder.setCall(call);
        }
        long startTimeMs = System.currentTimeMillis();
        try (Response response = call.execute()) {
            long durationMs = System.currentTimeMillis() - startTimeMs;
            if (!response.isSuccessful()) {
                String retryAfterHeader = response.header("Retry-After");
                int retryAfterSec = -1;
                if (retryAfterHeader != null) {
                    try {
                        retryAfterSec = Integer.parseInt(retryAfterHeader.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                logW("VOT HTTP non-success: path=%s, code=%d, duration=%dms, retryAfter=%d",
                        path, response.code(), durationMs, retryAfterSec);
                throw new VotHttpException(response.code(), response.message(), retryAfterSec);
            }
            if (response.body() == null) {
                return null;
            }
            return response.body().bytes();
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startTimeMs;
            logE("VOT HTTP transport failure: path=%s, exception=%s, duration=%dms",
                    path, e.getClass().getSimpleName(), durationMs);
            throw e;
        }
    }

    private static void logW(String msg, Object... args) {
        try {
            com.liskovsoft.sharedutils.mylogger.Log.w("VotHttp", msg, args);
        } catch (Throwable ignored) {
        }
    }

    private static void logE(String msg, Object... args) {
        try {
            com.liskovsoft.sharedutils.mylogger.Log.e("VotHttp", msg, args);
        } catch (Throwable ignored) {
        }
    }
}

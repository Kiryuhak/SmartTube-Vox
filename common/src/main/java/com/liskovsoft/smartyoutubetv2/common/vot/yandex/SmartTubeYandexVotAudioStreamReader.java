/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Production implementation of {@link YandexVotAudioStreamReader} backed by OkHttp range streaming.
 */
public class SmartTubeYandexVotAudioStreamReader implements YandexVotAudioStreamReader {
    private static final String TAG = SmartTubeYandexVotAudioStreamReader.class.getSimpleName();
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private static volatile OkHttpClient sDefaultClient;

    private final YandexVotAudioSource source;
    private final OkHttpClient httpClient;
    private volatile Call activeCall;
    private volatile boolean cancelled = false;

    public SmartTubeYandexVotAudioStreamReader(@NonNull YandexVotAudioSource source) {
        this(source, null);
    }

    public SmartTubeYandexVotAudioStreamReader(@NonNull YandexVotAudioSource source, @Nullable OkHttpClient httpClient) {
        this.source = Objects.requireNonNull(source, "source cannot be null");
        this.httpClient = httpClient != null ? httpClient : getDefaultClient();
    }

    private static OkHttpClient getDefaultClient() {
        return com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.getYandexAudioHttpClient();
    }

    public void cancel() {
        cancelled = true;
        Call call = activeCall;
        if (call != null) {
            call.cancel();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    @NonNull
    @Override
    public byte[] readRange(long startByte, int length) throws Exception {
        if (cancelled) {
            throw new IOException("Read operation cancelled");
        }
        if (startByte < 0 || length <= 0) {
            throw new IllegalArgumentException("Invalid range: start=" + startByte + ", length=" + length);
        }

        long endByte = startByte + length - 1;
        String rangeHeader = "bytes=" + startByte + "-" + endByte;

        Request.Builder requestBuilder = new Request.Builder()
                .url(source.getStreamUrl())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Range", rangeHeader);

        for (Map.Entry<String, String> header : source.getHeaders().entrySet()) {
            if (header.getKey() != null && header.getValue() != null && !header.getKey().equalsIgnoreCase("Range")) {
                requestBuilder.header(header.getKey(), header.getValue());
            }
        }

        Request request = requestBuilder.build();
        Call call = httpClient.newCall(request);
        activeCall = call;

        if (cancelled) {
            call.cancel();
            throw new IOException("Read operation cancelled");
        }

        try (Response response = call.execute()) {
            activeCall = null;
            int code = response.code();

            if (code != 206 && code != 200) {
                throw new IOException("HTTP error " + code + " for range " + rangeHeader);
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response body for range " + rangeHeader);
            }

            String contentRangeHeader = response.header("Content-Range");
            if (contentRangeHeader != null && code == 206) {
                YandexVotContentRange.require(contentRangeHeader, startByte, endByte, source.getContentLength());
            }

            try (InputStream is = body.byteStream()) {
                byte[] buffer = new byte[length];
                int totalRead = 0;
                while (totalRead < length) {
                    if (cancelled) {
                        throw new IOException("Read operation cancelled");
                    }
                    int read = is.read(buffer, totalRead, length - totalRead);
                    if (read == -1) {
                        break;
                    }
                    totalRead += read;
                }

                if (totalRead != length) {
                    throw new IOException("Short read: expected=" + length + ", actual=" + totalRead);
                }

                return buffer;
            }
        } finally {
            activeCall = null;
        }
    }
}

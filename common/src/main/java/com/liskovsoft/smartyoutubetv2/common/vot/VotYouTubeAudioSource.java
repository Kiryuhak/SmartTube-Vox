package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Production implementation of {@link VotAudioSource} backed by a direct YouTube media stream URL.
 * Streams bytes progressively from YouTube's CDN using OkHttp without buffering the whole file in memory.
 *
 * Features:
 * - Direct HTTP streaming via progressive byte chunks.
 * - Enforces clean network isolation: YouTube requests never contain Yandex session headers or auth tokens.
 * - HTTP status code classification: explicitly identifies HTTP 403 (expired media URL), 404, 5xx.
 * - Rejects non-audio responses (e.g. HTML error/challenge pages with HTTP 200).
 * - Tracks content length and validates stream integrity.
 * - Thread-safe cancellation and resource cleanup.
 */
public class VotYouTubeAudioSource implements VotAudioSource {
    private static volatile OkHttpClient sDefaultClient;

    private final String mMediaUrl;
    private final long mDeclaredContentLength;
    private final OkHttpClient mHttpClient;

    private volatile Call mActiveCall;
    private volatile Response mResponse;
    private volatile ResponseBody mResponseBody;
    private volatile InputStream mInputStream;
    private volatile long mActualContentLength = -1;
    private volatile long mBytesRead = 0;
    private volatile boolean mOpened = false;
    private volatile boolean mClosed = false;

    public VotYouTubeAudioSource(String mediaUrl) {
        this(mediaUrl, -1, getDefaultClient());
    }

    public VotYouTubeAudioSource(String mediaUrl, long declaredContentLength) {
        this(mediaUrl, declaredContentLength, getDefaultClient());
    }

    public VotYouTubeAudioSource(String mediaUrl, long declaredContentLength, @Nullable OkHttpClient httpClient) {
        if (mediaUrl == null || mediaUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("mediaUrl must not be null or empty");
        }
        mMediaUrl = mediaUrl.trim();
        mDeclaredContentLength = declaredContentLength > 0 ? declaredContentLength : -1;
        mActualContentLength = mDeclaredContentLength;
        mHttpClient = httpClient != null ? httpClient : getDefaultClient();
    }

    public static VotYouTubeAudioSource fromMediaFormat(@NonNull MediaFormat format) {
        return fromMediaFormat(format, getDefaultClient());
    }

    public static VotYouTubeAudioSource fromMediaFormat(@NonNull MediaFormat format, @Nullable OkHttpClient httpClient) {
        if (format == null) {
            throw new IllegalArgumentException("format must not be null");
        }
        String url = format.getUrl();
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("format does not contain a valid URL");
        }
        long clen = -1;
        String clenStr = format.getClen();
        if (clenStr != null && !clenStr.trim().isEmpty()) {
            try {
                clen = Long.parseLong(clenStr.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return new VotYouTubeAudioSource(url, clen, httpClient);
    }

    @Override
    public void open() throws IOException {
        if (mClosed) {
            throw new VotAudioSourceException("Audio source is already closed");
        }
        if (mOpened) {
            return;
        }

        Request request = new Request.Builder()
                .url(mMediaUrl)
                .header("User-Agent", VotConfig.USER_AGENT)
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .build();

        Call call = mHttpClient.newCall(request);
        mActiveCall = call;

        if (mClosed) {
            call.cancel();
            throw new VotCancellationException("Opening audio source was cancelled");
        }

        Response response;
        try {
            response = call.execute();
        } catch (IOException e) {
            if (mClosed) {
                throw new VotCancellationException("Opening audio source was cancelled");
            }
            throw new VotAudioSourceException("Failed to connect to YouTube media stream: " + e.getMessage(), e);
        }

        mResponse = response;
        int code = response.code();

        if (code == 403) {
            closeQuietly();
            throw new VotAudioSourceException("YouTube media stream access forbidden (HTTP 403 - likely expired URL)");
        } else if (code == 404) {
            closeQuietly();
            throw new VotAudioSourceException("YouTube media stream not found (HTTP 404)");
        } else if (code >= 500 && code < 600) {
            closeQuietly();
            throw new VotAudioSourceException("YouTube media stream server error (HTTP " + code + ")");
        } else if (!response.isSuccessful()) {
            closeQuietly();
            throw new VotAudioSourceException("YouTube media stream request failed with HTTP " + code + ": " + response.message());
        }

        ResponseBody body = response.body();
        if (body == null) {
            closeQuietly();
            throw new VotAudioSourceException("YouTube media stream returned empty response body");
        }
        mResponseBody = body;

        // Check Content-Type for error / bot-check / captive portal HTML pages returning HTTP 200
        okhttp3.MediaType contentType = body.contentType();
        if (contentType != null) {
            String mime = contentType.toString().toLowerCase(Locale.US);
            if (mime.contains("text/html") || mime.contains("application/xhtml") || mime.contains("text/plain")) {
                closeQuietly();
                throw new VotAudioSourceException("Unexpected response content type: " + mime + " (received HTML/text instead of audio stream)");
            }
        }

        long responseLen = body.contentLength();
        if (responseLen > 0) {
            mActualContentLength = responseLen;
        } else if (mDeclaredContentLength > 0) {
            mActualContentLength = mDeclaredContentLength;
        }

        mInputStream = body.byteStream();
        mBytesRead = 0;
        mOpened = true;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        if (!mOpened) {
            throw new VotAudioSourceException("Audio source not opened");
        }
        if (mClosed) {
            throw new VotCancellationException("Audio source is closed / cancelled");
        }
        if (buffer == null) {
            throw new NullPointerException("buffer is null");
        }
        if (off < 0 || len < 0 || len > buffer.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }

        InputStream in = mInputStream;
        if (in == null) {
            return -1;
        }

        int r;
        try {
            r = in.read(buffer, off, len);
        } catch (IOException e) {
            if (mClosed) {
                throw new VotCancellationException("Read cancelled");
            }
            throw new VotAudioSourceException("Error reading from YouTube media stream: " + e.getMessage(), e);
        }

        if (r > 0) {
            mBytesRead += r;
        }
        return r;
    }

    @Override
    public long getContentLength() {
        return mActualContentLength;
    }

    public long getBytesRead() {
        return mBytesRead;
    }

    public String getMediaUrl() {
        return mMediaUrl;
    }

    @Override
    public void close() throws IOException {
        mClosed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        Call call = mActiveCall;
        if (call != null) {
            try {
                call.cancel();
            } catch (Throwable ignored) {
            }
            mActiveCall = null;
        }
        InputStream in = mInputStream;
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            mInputStream = null;
        }
        ResponseBody body = mResponseBody;
        if (body != null) {
            try {
                body.close();
            } catch (Throwable ignored) {
            }
            mResponseBody = null;
        }
        Response resp = mResponse;
        if (resp != null) {
            try {
                resp.close();
            } catch (Throwable ignored) {
            }
            mResponse = null;
        }
    }

    private static OkHttpClient getDefaultClient() {
        if (sDefaultClient == null) {
            synchronized (VotYouTubeAudioSource.class) {
                if (sDefaultClient == null) {
                    sDefaultClient = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return sDefaultClient;
    }
}

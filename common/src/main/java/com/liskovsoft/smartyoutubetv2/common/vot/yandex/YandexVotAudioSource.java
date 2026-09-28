/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable descriptor of an original audio track stream for Yandex VOT upload.
 */
public final class YandexVotAudioSource {
    private final String videoId;
    private final String streamUrl;
    private final String mimeType;
    private final String codec;
    private final int bitrate;
    private final long contentLength;
    private final int itag;
    private final boolean rangeSupported;
    private final Map<String, String> headers;

    public YandexVotAudioSource(
            @Nullable String videoId,
            @NonNull String streamUrl,
            @NonNull String mimeType,
            @Nullable String codec,
            int bitrate,
            long contentLength,
            int itag,
            boolean rangeSupported,
            @Nullable Map<String, String> headers
    ) {
        this.videoId = videoId;
        this.streamUrl = Objects.requireNonNull(streamUrl, "streamUrl cannot be null");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType cannot be null");
        this.codec = codec != null ? codec : "";
        this.bitrate = bitrate;
        this.contentLength = contentLength;
        this.itag = itag;
        this.rangeSupported = rangeSupported;
        this.headers = headers != null ? Collections.unmodifiableMap(headers) : Collections.emptyMap();
    }

    @Nullable
    public String getVideoId() {
        return videoId;
    }

    @NonNull
    public String getStreamUrl() {
        return streamUrl;
    }

    @NonNull
    public String getMimeType() {
        return mimeType;
    }

    @NonNull
    public String getCodec() {
        return codec;
    }

    public int getBitrate() {
        return bitrate;
    }

    public long getContentLength() {
        return contentLength;
    }

    public int getItag() {
        return itag;
    }

    public boolean isRangeSupported() {
        return rangeSupported;
    }

    @NonNull
    public Map<String, String> getHeaders() {
        return headers;
    }

    public boolean isOpus() {
        return (codec != null && codec.toLowerCase().contains("opus")) ||
                (mimeType != null && mimeType.toLowerCase().contains("opus"));
    }

    public boolean isWebm() {
        return mimeType != null && mimeType.toLowerCase().startsWith("audio/webm");
    }

    public boolean isMp4() {
        return mimeType != null && mimeType.toLowerCase().startsWith("audio/mp4");
    }

    public String safeFormatDescription() {
        return "itag=" + itag + ", mime=" + mimeType + ", codec=" + codec +
                ", bitrate=" + bitrate + ", clen=" + contentLength;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        YandexVotAudioSource that = (YandexVotAudioSource) o;
        return bitrate == that.bitrate &&
                contentLength == that.contentLength &&
                itag == that.itag &&
                rangeSupported == that.rangeSupported &&
                Objects.equals(videoId, that.videoId) &&
                Objects.equals(streamUrl, that.streamUrl) &&
                Objects.equals(mimeType, that.mimeType) &&
                Objects.equals(codec, that.codec) &&
                Objects.equals(headers, that.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(videoId, streamUrl, mimeType, codec, bitrate, contentLength, itag, rangeSupported, headers);
    }

    @Override
    public String toString() {
        return "YandexVotAudioSource{" +
                "videoId='" + videoId + '\'' +
                ", streamUrl=[PROTECTED]" +
                ", mimeType='" + mimeType + '\'' +
                ", codec='" + codec + '\'' +
                ", bitrate=" + bitrate +
                ", contentLength=" + contentLength +
                ", itag=" + itag +
                ", rangeSupported=" + rangeSupported +
                ", headers=[PROTECTED]" +
                '}';
    }
}

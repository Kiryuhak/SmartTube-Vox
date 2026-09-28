/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Isolated selection policy for picking the optimal original audio source for Yandex upload.
 */
public final class YandexVotAudioSourceSelector {

    public static final class Candidate {
        private final String videoId;
        private final String streamUrl;
        private final String mimeType;
        private final String codec;
        private final int bitrate;
        private final long contentLength;
        private final int itag;
        private final boolean rangeSupported;
        private final boolean isSabr;
        private final String audioTrackType; // e.g. "original", "dubbed", "auto-dubbed"
        private final String language;

        public Candidate(
                @Nullable String videoId,
                @Nullable String streamUrl,
                @Nullable String mimeType,
                @Nullable String codec,
                int bitrate,
                long contentLength,
                int itag,
                boolean rangeSupported,
                boolean isSabr,
                @Nullable String audioTrackType,
                @Nullable String language
        ) {
            this.videoId = videoId;
            this.streamUrl = streamUrl;
            this.mimeType = mimeType;
            this.codec = codec;
            this.bitrate = bitrate;
            this.contentLength = contentLength;
            this.itag = itag;
            this.rangeSupported = rangeSupported;
            this.isSabr = isSabr;
            this.audioTrackType = audioTrackType;
            this.language = language;
        }

        @Nullable public String getVideoId() { return videoId; }
        @Nullable public String getStreamUrl() { return streamUrl; }
        @Nullable public String getMimeType() { return mimeType; }
        @Nullable public String getCodec() { return codec; }
        public int getBitrate() { return bitrate; }
        public long getContentLength() { return contentLength; }
        public int getItag() { return itag; }
        public boolean isRangeSupported() { return rangeSupported; }
        public boolean isSabr() { return isSabr; }
        @Nullable public String getAudioTrackType() { return audioTrackType; }
        @Nullable public String getLanguage() { return language; }
    }

    private YandexVotAudioSourceSelector() {
    }

    @Nullable
    public static YandexVotAudioSource fromMediaItemFormatInfo(@Nullable MediaItemFormatInfo formatInfo) {
        if (formatInfo == null || formatInfo.getAdaptiveFormats() == null) {
            return null;
        }
        return fromMediaFormats(formatInfo.getVideoId(), formatInfo.getAdaptiveFormats());
    }

    @Nullable
    public static YandexVotAudioSource fromMediaFormats(@Nullable String videoId, @Nullable List<MediaFormat> formats) {
        if (formats == null || formats.isEmpty()) {
            return null;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (MediaFormat f : formats) {
            Candidate c = toCandidate(videoId, f);
            if (c != null) {
                candidates.add(c);
            }
        }
        return selectBestAudioSource(candidates);
    }

    @Nullable
    public static Candidate toCandidate(@Nullable String videoId, @Nullable MediaFormat f) {
        if (f == null) return null;
        String url = f.getUrl();
        if (url == null || url.trim().isEmpty()) return null;

        String rawMime = f.getMimeType();
        if (rawMime == null || !rawMime.toLowerCase(Locale.US).startsWith("audio/")) {
            return null;
        }

        boolean isSabr = (f.getFormatType() == MediaFormat.FORMAT_TYPE_SABR);
        boolean isOtf = f.isOtf();
        boolean rangeSupported = (!isSabr && !isOtf);

        int bitrate = parseBitrate(f.getBitrate());
        long clen = parseContentLength(f.getClen());
        int itag = parseItag(f.getITag());

        String mimeType = extractBaseMime(rawMime);
        String codec = extractCodec(rawMime);

        return new Candidate(
                videoId,
                url.trim(),
                mimeType,
                codec,
                bitrate,
                clen,
                itag,
                rangeSupported,
                isSabr,
                f.getAudioTrackId(),
                f.getLanguage()
        );
    }

    @Nullable
    public static YandexVotAudioSource selectBestAudioSource(@Nullable List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        List<Candidate> valid = new ArrayList<>();
        boolean hasNonRussian = false;
        boolean hasNonDubbed = false;

        for (Candidate c : candidates) {
            if (!isValidAudioCandidate(c)) {
                continue;
            }

            if (!isRussian(c)) {
                hasNonRussian = true;
            }
            if (!isDubbed(c)) {
                hasNonDubbed = true;
            }
            valid.add(c);
        }

        if (valid.isEmpty()) {
            return null;
        }

        List<Candidate> filtered = new ArrayList<>();
        for (Candidate c : valid) {
            if (hasNonRussian && isRussian(c)) {
                continue;
            }
            if (hasNonDubbed && isDubbed(c)) {
                continue;
            }
            filtered.add(c);
        }

        if (filtered.isEmpty()) {
            filtered.addAll(valid);
        }

        Collections.sort(filtered, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                boolean compatA = isCompatibleMime(a.getMimeType());
                boolean compatB = isCompatibleMime(b.getMimeType());
                if (compatA != compatB) {
                    return compatA ? -1 : 1;
                }

                // Prefer lower bitrate
                if (a.getBitrate() != b.getBitrate()) {
                    return Integer.compare(a.getBitrate(), b.getBitrate());
                }

                // Prefer Opus / WebM over AAC / MP4 at equal bitrate
                boolean opusA = isOpus(a);
                boolean opusB = isOpus(b);
                if (opusA != opusB) {
                    return opusA ? -1 : 1;
                }

                return 0;
            }
        });

        Candidate best = filtered.get(0);
        return new YandexVotAudioSource(
                best.getVideoId(),
                best.getStreamUrl(),
                best.getMimeType(),
                best.getCodec(),
                best.getBitrate(),
                best.getContentLength(),
                best.getItag(),
                best.isRangeSupported(),
                null
        );
    }

    public static boolean isValidAudioCandidate(@Nullable Candidate c) {
        if (c == null) return false;
        if (c.getStreamUrl() == null || c.getStreamUrl().isEmpty()) return false;
        if (c.isSabr()) return false; // Reject SABR / segmented non-direct streams
        if (!c.isRangeSupported()) return false; // Must support range requests
        if (c.getMimeType() == null || !c.getMimeType().toLowerCase(Locale.US).startsWith("audio/")) return false;
        return true;
    }

    private static boolean isCompatibleMime(@Nullable String mime) {
        if (mime == null) return false;
        String lower = mime.toLowerCase(Locale.US);
        return lower.startsWith("audio/webm") || lower.startsWith("audio/mp4");
    }

    private static boolean isOpus(@NonNull Candidate c) {
        String codec = c.getCodec();
        String mime = c.getMimeType();
        return (codec != null && codec.toLowerCase(Locale.US).contains("opus")) ||
                (mime != null && mime.toLowerCase(Locale.US).contains("opus"));
    }

    private static boolean isRussian(@NonNull Candidate c) {
        String lang = c.getLanguage();
        if (lang != null && (lang.equalsIgnoreCase("ru") || lang.toLowerCase(Locale.US).startsWith("ru-") || lang.equalsIgnoreCase("rus") || lang.equalsIgnoreCase("russian"))) {
            return true;
        }
        return false;
    }

    private static boolean isDubbed(@NonNull Candidate c) {
        String trackType = c.getAudioTrackType();
        if (trackType != null) {
            String lower = trackType.toLowerCase(Locale.US);
            if (lower.contains("dubbed") || lower.contains("auto_dubbed") || lower.contains("secondary")) {
                return true;
            }
        }
        return false;
    }

    private static String extractBaseMime(String rawMime) {
        if (rawMime == null) return "unknown";
        int semicolon = rawMime.indexOf(';');
        return semicolon > 0 ? rawMime.substring(0, semicolon).trim() : rawMime.trim();
    }

    private static String extractCodec(String rawMime) {
        if (rawMime == null) return "";
        String lower = rawMime.toLowerCase(Locale.US);
        if (lower.contains("opus")) return "opus";
        if (lower.contains("mp4a")) return "mp4a";
        if (lower.contains("aac")) return "aac";
        return "";
    }

    private static int parseBitrate(String bitrateStr) {
        if (bitrateStr == null) return 0;
        try {
            return Integer.parseInt(bitrateStr.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseContentLength(String clenStr) {
        if (clenStr == null) return 0;
        try {
            return Long.parseLong(clenStr.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseItag(String itagStr) {
        if (itagStr == null) return 0;
        try {
            return Integer.parseInt(itagStr.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

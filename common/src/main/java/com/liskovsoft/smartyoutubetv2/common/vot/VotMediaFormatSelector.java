package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Selects the most compact and compatible original YouTube audio-only stream format
 * for Yandex VOT audio upload.
 *
 * Requirements:
 * - Selects audio-only formats (MIME starts with "audio/").
 * - Excludes Russian auto-dubbed and dubbed audio tracks.
 * - Prefers compact formats (lowest bitrate) to minimize TV network and memory footprint.
 * - Prefers verified compatible containers (audio/webm with Opus, audio/mp4 with AAC).
 * - Leaves player's active audio track selection completely untouched.
 */
public final class VotMediaFormatSelector {
    public static final String MIME_WEBM = "audio/webm";
    public static final String MIME_MP4 = "audio/mp4";

    private VotMediaFormatSelector() {
    }

    /**
     * Inspects a list of adaptive media formats and selects the optimal original audio format.
     *
     * @param formats list of adaptive formats from MediaItemFormatInfo.getAdaptiveFormats()
     * @return selected MediaFormat for Yandex upload, or null if no compatible audio stream exists
     */
    @Nullable
    public static MediaFormat selectBestAudioFormat(@Nullable List<MediaFormat> formats) {
        if (formats == null || formats.isEmpty()) {
            return null;
        }

        List<MediaFormat> candidates = new ArrayList<>();
        boolean hasNonRussian = false;
        boolean hasNonDubbed = false;

        for (MediaFormat f : formats) {
            if (!isValidAudioFormat(f)) {
                continue;
            }

            if (!isRussianFormat(f)) {
                hasNonRussian = true;
            }
            if (!isDubbedFormat(f) && !isAutoDubbedFormat(f)) {
                hasNonDubbed = true;
            }
            candidates.add(f);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Filter out Russian and dubbed tracks
        List<MediaFormat> filtered = new ArrayList<>();
        for (MediaFormat f : candidates) {
            // Exclude Russian formats if non-Russian formats exist
            if (hasNonRussian && (isRussianFormat(f) || isDubbedFormat(f) || isAutoDubbedFormat(f))) {
                continue;
            }
            // Exclude dubbed and auto-dubbed formats if non-dubbed originals exist
            if (hasNonDubbed && (isDubbedFormat(f) || isAutoDubbedFormat(f))) {
                continue;
            }
            filtered.add(f);
        }

        if (filtered.isEmpty()) {
            // Fallback: keep candidates only if not explicit auto-dub
            for (MediaFormat f : candidates) {
                if (!isAutoDubbedFormat(f)) {
                    filtered.add(f);
                }
            }
        }

        if (filtered.isEmpty()) {
            filtered.addAll(candidates);
        }

        if (filtered.isEmpty()) {
            return null;
        }

        // Sort candidates:
        // 1. Prefer compatible codecs (audio/webm, audio/mp4)
        // 2. Prefer lower bitrate (saves TV bandwidth and upload time)
        // 3. Prefer webm/opus over mp4/aac at equal bitrates
        Collections.sort(filtered, new Comparator<MediaFormat>() {
            @Override
            public int compare(MediaFormat a, MediaFormat b) {
                boolean compatA = isCompatibleMime(a.getMimeType());
                boolean compatB = isCompatibleMime(b.getMimeType());
                if (compatA != compatB) {
                    return compatA ? -1 : 1;
                }

                int bitrateA = parseBitrate(a.getBitrate());
                int bitrateB = parseBitrate(b.getBitrate());
                if (bitrateA != bitrateB) {
                    return Integer.compare(bitrateA, bitrateB); // lower bitrate first
                }

                boolean webmA = isWebm(a.getMimeType());
                boolean webmB = isWebm(b.getMimeType());
                if (webmA != webmB) {
                    return webmA ? -1 : 1;
                }

                return 0;
            }
        });

        return filtered.get(0);
    }

    public static boolean isValidAudioFormat(@Nullable MediaFormat format) {
        if (format == null) {
            return false;
        }
        String url = format.getUrl();
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String lowerUrl = url.trim().toLowerCase(Locale.US);
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return false;
        }

        String mime = format.getMimeType();
        if (mime == null || mime.trim().isEmpty()) {
            return false;
        }
        String lowerMime = mime.trim().toLowerCase(Locale.US);
        if (!lowerMime.startsWith("audio/")) {
            return false; // exclude video-only or subtitles
        }

        // OTF formats without direct URLs are not streaming-capable for direct HTTP
        if (format.isOtf() && (format.getUrl() == null || format.getUrl().isEmpty())) {
            return false;
        }

        return true;
    }

    public static boolean isCompatibleMime(@Nullable String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase(Locale.US);
        return lower.contains("webm") || lower.contains("opus")
                || lower.contains("mp4") || lower.contains("mp4a") || lower.contains("aac");
    }

    public static boolean isWebm(@Nullable String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase(Locale.US);
        return lower.contains("webm") || lower.contains("opus");
    }

    public static boolean isRussianFormat(@Nullable MediaFormat format) {
        if (format == null) {
            return false;
        }
        if (VotAudioTrackHelper.isRussianLang(format.getLanguage())) {
            return true;
        }
        String trackId = format.getAudioTrackId();
        if (trackId != null) {
            String lower = trackId.toLowerCase(Locale.US);
            if (lower.startsWith("ru") || lower.contains(".ru.") || lower.contains("russian")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDubbedFormat(@Nullable MediaFormat format) {
        if (format == null) {
            return false;
        }
        String trackId = format.getAudioTrackId();
        if (trackId != null) {
            String lower = trackId.toLowerCase(Locale.US);
            if (lower.contains("dubbed") || lower.contains(".d.") || lower.endsWith(".d")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAutoDubbedFormat(@Nullable MediaFormat format) {
        if (format == null) {
            return false;
        }
        String trackId = format.getAudioTrackId();
        if (trackId != null) {
            String lower = trackId.toLowerCase(Locale.US);
            if (lower.contains("dubbed-auto") || lower.contains("autodub")) {
                return true;
            }
        }
        return false;
    }

    public static int parseBitrate(@Nullable String bitrateStr) {
        if (bitrateStr == null || bitrateStr.trim().isEmpty()) {
            return 999_999_999;
        }
        try {
            int val = Integer.parseInt(bitrateStr.trim());
            return val > 0 ? val : 999_999_999;
        } catch (NumberFormatException ignored) {
            return 999_999_999;
        }
    }
}

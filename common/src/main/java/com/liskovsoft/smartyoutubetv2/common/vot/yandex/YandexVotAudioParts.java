/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure part arithmetic for uploading the original audio track to Yandex.
 *
 * <p>The Yandex upload protocol requires every part except the final remainder to be
 * exactly {@link #PART_SIZE_BYTES} bytes. Do not change that constant without protocol
 * evidence.
 */
public final class YandexVotAudioParts {
    /** Yandex upload protocol part size in bytes. */
    public static final int PART_SIZE_BYTES = 5_295_308;

    private YandexVotAudioParts() {
    }

    /** A contiguous byte range of the source track. {@code length} is at most PART_SIZE_BYTES. */
    public static final class Part {
        private final long start;
        private final int length;

        public Part(long start, int length) {
            this.start = start;
            this.length = length;
        }

        public long start() {
            return start;
        }

        public int length() {
            return length;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Part part = (Part) o;
            return start == part.start && length == part.length;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, length);
        }

        @Override
        public String toString() {
            return "Part[start=" + start + ", length=" + length + "]";
        }
    }

    /**
     * Partitions {@code [0, fileSize)} into ascending, non-overlapping parts that cover the
     * whole track. Every part except the last has length {@link #PART_SIZE_BYTES}; the last
     * part holds the exact remainder (which may also be a full part).
     */
    public static List<Part> parts(long fileSize) {
        if (fileSize <= 0) return Collections.emptyList();

        List<Part> result = new ArrayList<>();
        long start = 0;
        while (start < fileSize) {
            long remaining = fileSize - start;
            int length = remaining >= PART_SIZE_BYTES
                    ? PART_SIZE_BYTES
                    : (int) remaining;
            result.add(new Part(start, length));
            start += length;
        }
        return result;
    }

    public static int partCount(long fileSize) {
        if (fileSize <= 0) return 0;
        long parts = (fileSize - 1L) / PART_SIZE_BYTES + 1L;
        return parts > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) parts;
    }
}

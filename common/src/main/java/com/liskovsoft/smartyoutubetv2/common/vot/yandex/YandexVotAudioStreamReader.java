/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;

/**
 * Interface for reading byte ranges from the selected audio stream.
 */
public interface YandexVotAudioStreamReader {
    /**
     * Reads exactly {@code length} bytes starting at {@code startByte}.
     *
     * @param startByte zero-based offset in the stream
     * @param length number of bytes to read
     * @return byte array containing exactly length bytes
     * @throws Exception if reading fails, network drops, or truncated data is returned
     */
    @NonNull
    byte[] readRange(long startByte, int length) throws Exception;
}

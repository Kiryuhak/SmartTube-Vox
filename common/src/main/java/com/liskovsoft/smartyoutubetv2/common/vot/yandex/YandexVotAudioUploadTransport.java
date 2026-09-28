/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;

/**
 * Transport interface for uploading audio chunk parts to the Yandex VOT service.
 */
public interface YandexVotAudioUploadTransport {
    enum UploadOutcome {
        SUCCESS,
        TRANSIENT_ERROR,
        PERMANENT_ERROR,
        CANCELLED
    }

    UploadOutcome uploadPart(
            @NonNull String videoUrl,
            @NonNull String translationId,
            @NonNull String fileId,
            int totalParts,
            int version,
            int chunkId,
            @NonNull byte[] audioData
    );

    YandexVotAudioUploadTransport DEFAULT = new YandexVotAudioUploadTransport() {
        @Override
        public UploadOutcome uploadPart(
                @NonNull String videoUrl,
                @NonNull String translationId,
                @NonNull String fileId,
                int totalParts,
                int version,
                int chunkId,
                @NonNull byte[] audioData
        ) {
            boolean ok = YandexVotApiClient.sendPartialAudio(
                    videoUrl, translationId, fileId, totalParts, version, chunkId, audioData
            );
            return ok ? UploadOutcome.SUCCESS : UploadOutcome.TRANSIENT_ERROR;
        }
    };
}

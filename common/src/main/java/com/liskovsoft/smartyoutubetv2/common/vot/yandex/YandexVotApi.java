/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Minimal interface exposing Yandex VOT translation API methods for the orchestrator.
 */
public interface YandexVotApi {
    YandexVotApi DEFAULT = new YandexVotApi() {
        @Override
        public YandexVotApiClient.TranslationResult requestTranslation(
                @NonNull String videoUrl,
                double duration,
                @Nullable String sourceLang,
                @NonNull String targetLang,
                @Nullable String videoTitle,
                boolean useLiveVoices,
                @Nullable String oauthToken,
                boolean firstRequest
        ) {
            return YandexVotApiClient.requestTranslation(
                    videoUrl, duration, sourceLang, targetLang,
                    videoTitle, useLiveVoices, oauthToken, firstRequest
            );
        }
    };

    @Nullable
    YandexVotApiClient.TranslationResult requestTranslation(
            @NonNull String videoUrl,
            double duration,
            @Nullable String sourceLang,
            @NonNull String targetLang,
            @Nullable String videoTitle,
            boolean useLiveVoices,
            @Nullable String oauthToken,
            boolean firstRequest
    );
}

/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Provider abstraction supplying the optimal original audio source and stream reader
 * for a YouTube video when Yandex VOT requests original audio.
 */
public interface YandexVotAudioSourceProvider {
    @Nullable
    YandexVotAudioSource getAudioSource(@Nullable String videoId, @NonNull String videoUrl) throws Exception;

    @Nullable
    YandexVotAudioStreamReader getStreamReader(@NonNull YandexVotAudioSource source) throws Exception;
}

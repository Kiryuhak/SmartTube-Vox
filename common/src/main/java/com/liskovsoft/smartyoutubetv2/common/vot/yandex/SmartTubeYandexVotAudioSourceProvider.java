/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import java.util.Objects;

import okhttp3.OkHttpClient;

/**
 * Production implementation of {@link YandexVotAudioSourceProvider} bridging SmartTube
 * media format metadata into isolated {@link YandexVotAudioSource} and stream readers.
 */
public class SmartTubeYandexVotAudioSourceProvider implements YandexVotAudioSourceProvider {

    public interface FormatInfoProvider {
        @Nullable
        MediaItemFormatInfo getFormatInfo(@Nullable String videoId, @NonNull String videoUrl) throws Exception;
    }

    private final FormatInfoProvider formatInfoProvider;
    private final OkHttpClient httpClient;

    public SmartTubeYandexVotAudioSourceProvider(@NonNull FormatInfoProvider formatInfoProvider) {
        this(formatInfoProvider, null);
    }

    public SmartTubeYandexVotAudioSourceProvider(@NonNull FormatInfoProvider formatInfoProvider, @Nullable OkHttpClient httpClient) {
        this.formatInfoProvider = Objects.requireNonNull(formatInfoProvider, "formatInfoProvider cannot be null");
        this.httpClient = httpClient;
    }

    @Nullable
    @Override
    public YandexVotAudioSource getAudioSource(@Nullable String videoId, @NonNull String videoUrl) throws Exception {
        MediaItemFormatInfo info = formatInfoProvider.getFormatInfo(videoId, videoUrl);
        if (info == null) {
            return null;
        }
        return YandexVotAudioSourceSelector.fromMediaItemFormatInfo(info);
    }

    @Nullable
    @Override
    public YandexVotAudioStreamReader getStreamReader(@NonNull YandexVotAudioSource source) throws Exception {
        return new SmartTubeYandexVotAudioStreamReader(source, httpClient);
    }
}

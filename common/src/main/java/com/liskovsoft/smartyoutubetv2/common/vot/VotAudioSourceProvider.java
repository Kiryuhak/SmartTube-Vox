package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

/**
 * Functional interface providing a {@link VotAudioSource} for Yandex VOT audio upload.
 * Decouples {@link VotClient} from YouTube metadata extraction and TV UI controllers.
 */
public interface VotAudioSourceProvider {
    /**
     * Resolves and returns a {@link VotAudioSource} for the specified YouTube video URL,
     * or null if no compatible audio stream is available.
     *
     * @param videoUrl YouTube video URL requested for translation
     * @return audio stream source ready to open, or null
     */
    @Nullable
    VotAudioSource getAudioSource(String videoUrl);
}

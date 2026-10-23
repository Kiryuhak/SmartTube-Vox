package com.liskovsoft.smartyoutubetv2.common.vot.compat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.liskovsoft.smartyoutubetv2.common.vot.VotTranslationResponse;

/**
 * Common abstraction for Voice-Over-Translation (VOT) backend adapters.
 *
 * Provides a clean boundary separating the production-proven Legacy VOT (1.11.17)
 * from the experimental VOT 1.12.1 compatibility adapter.
 */
public interface VotTranslationAdapter {

    /**
     * Identifies the protocol and adapter version (e.g. "1.11.17" or "1.12.1").
     */
    @NonNull
    String getVersion();

    /**
     * Whether this adapter is enabled and ready to handle translation requests.
     */
    boolean isEnabled();

    /**
     * Asynchronous callback for translation requests.
     */
    interface Callback {
        void onReady(@NonNull VotTranslationResponse response);
        void onWaiting(int remainingSeconds);
        void onError(@NonNull Exception error);
    }

    /**
     * Requests video voiceover translation.
     */
    void requestTranslation(
            @NonNull String videoUrl,
            long durationMs,
            @Nullable String sourceLanguage,
            @NonNull String targetLanguage,
            @NonNull Callback callback
    );

    /**
     * Cancels any pending in-flight requests.
     */
    void cancel();
}

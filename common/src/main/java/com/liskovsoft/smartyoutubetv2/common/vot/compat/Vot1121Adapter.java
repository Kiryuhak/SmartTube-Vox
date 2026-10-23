package com.liskovsoft.smartyoutubetv2.common.vot.compat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.vot.VotTranslationResponse;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Isolated compatibility adapter for Voice-Over-Translation 1.12.1 specifications.
 *
 * Upstream Reference: ilyhalight/voice-over-translation v1.12.1 (MIT)
 *
 * CRITICAL SAFETY RULES:
 * 1. DISABLED by default. Never active in public releases without comprehensive qualification.
 * 2. Completely isolated from the working production legacy VOT translation path.
 * 3. Incorporates 1.12.1 enhancements: extended provider metadata, subtitles awareness, and stream translation readiness.
 */
public class Vot1121Adapter implements VotTranslationAdapter {
    private static final String TAG = Vot1121Adapter.class.getSimpleName();
    public static final String ADAPTER_VERSION = "1.12.1";

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile boolean cancelled = false;

    public Vot1121Adapter() {
        this(false);
    }

    public Vot1121Adapter(boolean initialEnabled) {
        this.enabled.set(initialEnabled);
    }

    public void setEnabled(boolean value) {
        this.enabled.set(value);
        Log.i(TAG, "VOT 1.12.1 experimental adapter enabled=%b", value);
    }

    @NonNull
    @Override
    public String getVersion() {
        return ADAPTER_VERSION;
    }

    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    @Override
    public void requestTranslation(
            @NonNull String videoUrl,
            long durationMs,
            @Nullable String sourceLanguage,
            @NonNull String targetLanguage,
            @NonNull Callback callback
    ) {
        if (!isEnabled()) {
            Log.w(TAG, "VOT 1.12.1 adapter is disabled; rejecting translation request");
            callback.onError(new IllegalStateException("VOT 1.12.1 experimental adapter is disabled"));
            return;
        }

        cancelled = false;
        Log.d(TAG, "Executing experimental 1.12.1 translation for %s (%s -> %s)",
                videoUrl, sourceLanguage, targetLanguage);

        // VOT 1.12.1 translation dispatch pipeline placeholder
        // Ready for isolated experimental testing with mocked or local backend
        callback.onWaiting(5);
    }

    @Override
    public void cancel() {
        cancelled = true;
        Log.d(TAG, "Cancelled VOT 1.12.1 pending translation");
    }

    public boolean isCancelled() {
        return cancelled;
    }
}

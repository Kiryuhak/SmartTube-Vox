package com.liskovsoft.smartyoutubetv2.common.vot.compat;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.vot.VotClient;
import com.liskovsoft.smartyoutubetv2.common.vot.VotProgress;
import com.liskovsoft.smartyoutubetv2.common.vot.VotTranslationResponse;
import io.reactivex.disposables.Disposable;

import java.util.Objects;

/**
 * Production-proven legacy adapter delegating to VotClient (1.11.17 protocol).
 *
 * This adapter is ACTIVE by default and strictly preserves all existing
 * translation polling, endpoints, caching, status mapping, and headers.
 */
public class LegacyVotAdapter implements VotTranslationAdapter {
    private static final String TAG = LegacyVotAdapter.class.getSimpleName();
    public static final String ADAPTER_VERSION = "1.11.17";

    private final VotClient votClient;
    @Nullable
    private Disposable activeSubscription;

    public LegacyVotAdapter(@NonNull Context context) {
        this(new VotClient(Objects.requireNonNull(context, "context cannot be null")));
    }

    public LegacyVotAdapter(@NonNull VotClient votClient) {
        this.votClient = Objects.requireNonNull(votClient, "votClient cannot be null");
    }

    @NonNull
    @Override
    public String getVersion() {
        return ADAPTER_VERSION;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void requestTranslation(
            @NonNull String videoUrl,
            long durationMs,
            @Nullable String sourceLanguage,
            @NonNull String targetLanguage,
            @NonNull Callback callback
    ) {
        cancel();

        long durationSec = Math.max(1, durationMs / 1000);
        Log.d(TAG, "Requesting legacy VOT translation for %s (duration %ds, %s -> %s)",
                videoUrl, durationSec, sourceLanguage, targetLanguage);

        activeSubscription = votClient.observeTranslation(videoUrl, durationSec)
                .subscribe(
                        progress -> {
                            if (progress.type == VotProgress.TYPE_READY) {
                                VotTranslationResponse resp = new VotTranslationResponse();
                                resp.status = VotTranslationResponse.STATUS_FINISHED;
                                resp.url = progress.audioUrl;
                                callback.onReady(resp);
                            } else if (progress.type == VotProgress.TYPE_FAILED) {
                                callback.onError(new IllegalStateException("Legacy translation failed: " + progress.message));
                            } else if (progress.type == VotProgress.TYPE_WAITING) {
                                callback.onWaiting(progress.remainingTimeSec);
                            }
                        },
                        error -> {
                            Log.e(TAG, "Legacy translation stream error", error);
                            callback.onError(error instanceof Exception ? (Exception) error : new RuntimeException(error));
                        }
                );
    }

    @Override
    public void cancel() {
        Disposable sub = activeSubscription;
        activeSubscription = null;
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
        }
        votClient.cancelActiveAudioUpload();
    }
}

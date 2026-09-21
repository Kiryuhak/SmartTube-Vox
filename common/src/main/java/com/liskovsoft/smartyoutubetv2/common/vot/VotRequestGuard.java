package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.Nullable;

/** Rejects asynchronous callbacks that no longer belong to the active VOT request. */
public final class VotRequestGuard {
    private VotRequestGuard() {
    }

    public static boolean isCurrent(int callbackGeneration,
                                    @Nullable String callbackVideoUrl,
                                    int activeGeneration,
                                    @Nullable String activeVideoUrl,
                                    @Nullable String playerVideoUrl) {
        return callbackGeneration == activeGeneration
                && callbackVideoUrl != null
                && callbackVideoUrl.equals(activeVideoUrl)
                && callbackVideoUrl.equals(playerVideoUrl);
    }
}

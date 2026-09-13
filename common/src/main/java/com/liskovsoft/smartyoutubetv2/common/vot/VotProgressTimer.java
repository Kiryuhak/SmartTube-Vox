package com.liskovsoft.smartyoutubetv2.common.vot;

import java.util.Locale;

/**
 * Keeps a local VOT countdown between backend updates.
 * Repeated identical ETA values are treated as the same backend snapshot so
 * polling cannot restart and freeze the countdown.
 */
public final class VotProgressTimer {
    private long mRequestStartedAtMs;
    private long mExpectedReadyAtMs;
    private int mLastBackendEtaSec = -1;

    public void start(long nowMs) {
        mRequestStartedAtMs = nowMs;
        mExpectedReadyAtMs = 0;
        mLastBackendEtaSec = -1;
    }

    public void clear() {
        mRequestStartedAtMs = 0;
        mExpectedReadyAtMs = 0;
        mLastBackendEtaSec = -1;
    }

    public void reconcileEta(int backendRemainingSec, long nowMs) {
        int safeRemainingSec = Math.max(0, backendRemainingSec);
        if (mExpectedReadyAtMs > 0 && safeRemainingSec == mLastBackendEtaSec) {
            return;
        }
        mLastBackendEtaSec = safeRemainingSec;
        mExpectedReadyAtMs = nowMs + safeRemainingSec * 1000L;
    }

    public int getRemainingTimeSec(long nowMs) {
        if (mExpectedReadyAtMs <= 0 || nowMs >= mExpectedReadyAtMs) {
            return 0;
        }
        return (int) ((mExpectedReadyAtMs - nowMs + 999L) / 1000L);
    }

    public long getElapsedAfterEtaSec(long nowMs) {
        if (mExpectedReadyAtMs > 0) {
            return Math.max(0, (nowMs - mExpectedReadyAtMs + 999L) / 1000L);
        }
        return Math.max(0, (nowMs - mRequestStartedAtMs) / 1000L);
    }

    public static String formatMmSs(long totalSec) {
        long safeTotalSec = Math.max(0, totalSec);
        long minutes = safeTotalSec / 60;
        long seconds = safeTotalSec % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}

package com.liskovsoft.smartyoutubetv2.common.misc;

/**
 * Bounds runtime player cache recovery attempts.
 * Prevents infinite recovery loops by allowing at most one cache invalidation attempt
 * per video session upon encountering repeated HTTP 403 source errors before playback begins.
 */
public final class PlayerCacheRecoveryPolicy {
    static final int SOURCE_403_THRESHOLD = 2;

    private int mSource403Count = 0;
    private boolean mRecoveryAttempted = false;

    public void reset() {
        mSource403Count = 0;
        mRecoveryAttempted = false;
    }

    public void onPlaybackStarted() {
        mSource403Count = 0;
        mRecoveryAttempted = false;
    }

    /**
     * Determines whether player cache invalidation should be triggered.
     * Triggers only when repeated 403 errors occur before the stream becomes playable.
     *
     * @param isPlayable true if the stream has already started playing
     * @return true if one-shot recovery should be executed
     */
    public boolean shouldRecoverOnSource403(boolean isPlayable) {
        if (isPlayable) {
            return false;
        }

        mSource403Count++;
        if (mSource403Count >= SOURCE_403_THRESHOLD && !mRecoveryAttempted) {
            mRecoveryAttempted = true;
            return true;
        }
        return false;
    }

    public boolean isRecoveryAttempted() {
        return mRecoveryAttempted;
    }

    public int getSource403Count() {
        return mSource403Count;
    }
}

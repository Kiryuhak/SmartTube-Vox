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

    public enum Error403Type {
        RECOVERABLE_403,
        FINAL_403
    }

    /**
     * Evaluates a player source HTTP 403 error.
     * Returns RECOVERABLE_403 for the first encountered 403 (enabling one-shot bounded refresh),
     * and FINAL_403 once recovery has already been attempted for this video session to prevent infinite retries.
     */
    public Error403Type evaluate403Error(boolean isPlayable) {
        mSource403Count++;
        if (!mRecoveryAttempted) {
            mRecoveryAttempted = true;
            return Error403Type.RECOVERABLE_403;
        }
        return Error403Type.FINAL_403;
    }

    public boolean shouldAttempt403Recovery() {
        return evaluate403Error(false) == Error403Type.RECOVERABLE_403;
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

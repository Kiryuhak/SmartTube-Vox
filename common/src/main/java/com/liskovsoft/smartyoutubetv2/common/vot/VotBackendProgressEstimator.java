package com.liskovsoft.smartyoutubetv2.common.vot;

/**
 * Deterministic, event-driven VOT progress. It never advances from wall-clock ticks:
 * only backend stage/progress/ETA changes or confirmed audio attachment can move it.
 */
public final class VotBackendProgressEstimator {
    private int mDisplayProgress;
    private int mInitialRemainingSec = -1;
    private int mLowestRemainingSec = Integer.MAX_VALUE;
    private boolean mProcessingStarted;

    public int onLookup() {
        return mDisplayProgress;
    }

    public int onProcessingHeartbeat(int remainingSec) {
        if (!mProcessingStarted) {
            mProcessingStarted = true;
            if (remainingSec > 0) {
                mInitialRemainingSec = remainingSec;
                mLowestRemainingSec = remainingSec;
            }
            return mDisplayProgress; // The collapsed circle is first shown at exactly 0%.
        }
        int candidate = 10;
        if (remainingSec > 0) {
            if (mInitialRemainingSec < 0) mInitialRemainingSec = remainingSec;
            if (remainingSec < mLowestRemainingSec) {
                mLowestRemainingSec = remainingSec;
                if (mInitialRemainingSec > 0) {
                    float completed = 1f - (remainingSec / (float) mInitialRemainingSec);
                    candidate = 10 + Math.round(85f * Math.max(0f, Math.min(1f, completed)));
                }
            } else {
                candidate = mDisplayProgress;
            }
        }
        return advanceTo(Math.min(95, candidate));
    }

    public int onUploadPart(int currentPart, int totalParts) {
        if (totalParts <= 0 || currentPart < 0) return advanceTo(10);
        int candidate = 10 + Math.round(85f * (currentPart + 1) / totalParts);
        return advanceTo(Math.min(95, candidate));
    }

    public int onBackendProgress(int percent) {
        return advanceTo(Math.min(95, percent));
    }

    public int onArtifactReady() {
        return advanceTo(99);
    }

    public int onAudioAttachedAndSelected() {
        return advanceTo(100);
    }

    public int getDisplayProgress() {
        return mDisplayProgress;
    }

    public void reset() {
        mDisplayProgress = 0;
        mInitialRemainingSec = -1;
        mLowestRemainingSec = Integer.MAX_VALUE;
        mProcessingStarted = false;
    }

    private int advanceTo(int candidate) {
        int clamped = Math.max(0, Math.min(100, candidate));
        mDisplayProgress = Math.max(mDisplayProgress, clamped);
        return mDisplayProgress;
    }
}

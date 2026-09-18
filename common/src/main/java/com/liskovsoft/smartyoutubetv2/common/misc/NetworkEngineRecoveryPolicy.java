package com.liskovsoft.smartyoutubetv2.common.misc;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounds automatic network-engine changes for one playback session.
 * A new engine receives a grace period, the previous engine is temporarily
 * quarantined, and old switch timestamps form a rolling retry budget.
 */
public final class NetworkEngineRecoveryPolicy {
    static final long SWITCH_GRACE_PERIOD_MS = 60_000;
    static final long PREVIOUS_ENGINE_COOLDOWN_MS = 120_000;
    static final long SWITCH_BUDGET_WINDOW_MS = 5 * 60_000;
    static final int MAX_SWITCHES_PER_WINDOW = 2;

    private final Deque<Long> mSwitchTimesMs = new ArrayDeque<>();
    private long mLastSwitchTimeMs = Long.MIN_VALUE;
    private int mPreviousEngine = -1;
    private boolean mReconnectAttempted;

    public void reset() {
        mSwitchTimesMs.clear();
        mLastSwitchTimeMs = Long.MIN_VALUE;
        mPreviousEngine = -1;
        mReconnectAttempted = false;
    }

    /** A successful READY state makes the next isolated failure transient again. */
    public void onPlaybackProgress() {
        mReconnectAttempted = false;
    }

    /** Returns an eligible fallback, or {@code currentEngine} when reconnecting in-place is safer. */
    public int selectNextEngine(int currentEngine, int[] engines, long nowMs) {
        pruneBudget(nowMs);

        if (!mReconnectAttempted) {
            mReconnectAttempted = true;
            return currentEngine;
        }

        if (elapsedSinceLastSwitch(nowMs) < SWITCH_GRACE_PERIOD_MS
                || mSwitchTimesMs.size() >= MAX_SWITCHES_PER_WINDOW) {
            return currentEngine;
        }

        int currentIndex = indexOf(engines, currentEngine);
        for (int offset = 1; offset < engines.length; offset++) {
            int candidate = engines[(currentIndex + offset) % engines.length];
            boolean previousStillCoolingDown = candidate == mPreviousEngine
                    && elapsedSinceLastSwitch(nowMs) < PREVIOUS_ENGINE_COOLDOWN_MS;
            if (!previousStillCoolingDown) {
                mPreviousEngine = currentEngine;
                mLastSwitchTimeMs = nowMs;
                mSwitchTimesMs.addLast(nowMs);
                mReconnectAttempted = false;
                return candidate;
            }
        }

        return currentEngine;
    }

    public long elapsedSinceLastSwitch(long nowMs) {
        return mLastSwitchTimeMs == Long.MIN_VALUE ? Long.MAX_VALUE : Math.max(0, nowMs - mLastSwitchTimeMs);
    }

    public int getSwitchCount(long nowMs) {
        pruneBudget(nowMs);
        return mSwitchTimesMs.size();
    }

    private void pruneBudget(long nowMs) {
        while (!mSwitchTimesMs.isEmpty() && nowMs - mSwitchTimesMs.peekFirst() >= SWITCH_BUDGET_WINDOW_MS) {
            mSwitchTimesMs.removeFirst();
        }
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) {
                return i;
            }
        }
        return -1;
    }
}

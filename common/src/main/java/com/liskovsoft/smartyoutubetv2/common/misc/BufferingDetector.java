package com.liskovsoft.smartyoutubetv2.common.misc;

import androidx.annotation.NonNull;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class BufferingDetector {
    private static final long BUFFERING_DURATION_MS = 20_000;

    private final Runnable mOnLongBuffering = this::onLongBuffering;
    private final OnLongBuffering mCallback;
    private final Scheduler mScheduler;
    private boolean mIsBuffering;
    private boolean mIsPlayable;

    public interface OnLongBuffering {
        void onLongBuffering();
    }

    interface Scheduler {
        void postDelayed(Runnable callback, long delayMs);
        void removeCallbacks(Runnable callback);
    }

    public BufferingDetector(@NonNull OnLongBuffering callback) {
        this(callback, new Scheduler() {
            @Override
            public void postDelayed(Runnable callback, long delayMs) {
                Utils.postDelayed(callback, delayMs);
            }

            @Override
            public void removeCallbacks(Runnable callback) {
                Utils.removeCallbacks(callback);
            }
        });
    }

    BufferingDetector(@NonNull OnLongBuffering callback, @NonNull Scheduler scheduler) {
        mCallback = callback;
        mScheduler = scheduler;
    }

    public void onStartBuffering() {
        // Player.STATE_BUFFERING may be delivered more than once for the same episode.
        // A recovered READY interval is real playback progress and must start a fresh
        // stall window. Accumulating unrelated episodes caused healthy live streams
        // with intermittent jitter to be restarted in a loop.
        if (mIsBuffering) {
            return;
        }

        mIsBuffering = true;
        mScheduler.postDelayed(mOnLongBuffering, BUFFERING_DURATION_MS);
    }

    public void onStopBuffering() {
        mScheduler.removeCallbacks(mOnLongBuffering);
        mIsBuffering = false;
        mIsPlayable = true;
    }

    public void start() {
        mIsPlayable = false;
        reset();
    }

    /**
     * Reset buffering stats
     */
    public void reset() {
        mIsBuffering = false;
        mScheduler.removeCallbacks(mOnLongBuffering);
    }

    public boolean isPlayable() {
        return mIsPlayable;
    }

    private void onLongBuffering() {
        reset();
        mCallback.onLongBuffering();
    }
}

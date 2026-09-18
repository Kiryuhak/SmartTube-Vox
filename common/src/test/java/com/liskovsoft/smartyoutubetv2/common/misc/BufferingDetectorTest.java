package com.liskovsoft.smartyoutubetv2.common.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BufferingDetectorTest {
    @Test
    public void recoveredBufferingEpisodesAreNotAccumulated() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] stalls = {0};
        BufferingDetector detector = new BufferingDetector(() -> stalls[0]++, scheduler);

        detector.start();
        detector.onStartBuffering();
        scheduler.advanceBy(12_000);
        detector.onStopBuffering();
        detector.onStartBuffering();
        scheduler.advanceBy(12_000);

        assertEquals(0, stalls[0]);

        scheduler.advanceBy(8_000);
        assertEquals(1, stalls[0]);
    }

    @Test
    public void duplicateBufferingSignalDoesNotPostAnotherWatchdog() {
        FakeScheduler scheduler = new FakeScheduler();
        int[] stalls = {0};
        BufferingDetector detector = new BufferingDetector(() -> stalls[0]++, scheduler);

        detector.onStartBuffering();
        scheduler.advanceBy(10_000);
        detector.onStartBuffering();
        scheduler.advanceBy(10_000);

        assertEquals(1, stalls[0]);
    }

    private static final class FakeScheduler implements BufferingDetector.Scheduler {
        private Runnable mCallback;
        private long mNowMs;
        private long mDueTimeMs = Long.MAX_VALUE;

        @Override
        public void postDelayed(Runnable callback, long delayMs) {
            mCallback = callback;
            mDueTimeMs = mNowMs + delayMs;
        }

        @Override
        public void removeCallbacks(Runnable callback) {
            if (mCallback == callback) {
                mCallback = null;
                mDueTimeMs = Long.MAX_VALUE;
            }
        }

        void advanceBy(long durationMs) {
            mNowMs += durationMs;
            if (mCallback != null && mNowMs >= mDueTimeMs) {
                Runnable callback = mCallback;
                mCallback = null;
                mDueTimeMs = Long.MAX_VALUE;
                callback.run();
            }
        }
    }
}

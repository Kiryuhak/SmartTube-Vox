package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VotSeekStabilityTest {

    public static class SeekCalculator {
        public static long computeSeekTarget(long currentPosMs, long offsetMs, long durationMs) {
            long target = currentPosMs + offsetMs;
            if (target < 0) {
                return 0;
            }
            if (durationMs > 0 && target > durationMs) {
                return durationMs;
            }
            return target;
        }

        public static boolean isInitialGracePeriod(long targetPosMs, long graceThresholdMs) {
            return targetPosMs <= graceThresholdMs;
        }
    }

    @Test
    public void testForwardSeeksShortOffsets() {
        long duration = 600_000L; // 10 minutes

        // +15 seconds
        long pos = 30_000L;
        long target15 = SeekCalculator.computeSeekTarget(pos, 15_000L, duration);
        assertEquals(45_000L, target15);

        // +60 seconds (1 minute)
        long target60 = SeekCalculator.computeSeekTarget(pos, 60_000L, duration);
        assertEquals(90_000L, target60);
    }

    @Test
    public void testBackwardSeeksAndZeroClamping() {
        long duration = 600_000L;

        // -15 seconds
        long pos = 20_000L;
        long target15 = SeekCalculator.computeSeekTarget(pos, -15_000L, duration);
        assertEquals(5_000L, target15);

        // Seek backward past 0 clamps to 0
        long targetPastZero = SeekCalculator.computeSeekTarget(pos, -30_000L, duration);
        assertEquals(0L, targetPastZero);
    }

    @Test
    public void testLongVideoSeekStability() {
        long longDuration = 2 * 3600 * 1000L; // 2 hours = 7,200,000 ms

        long midPos = 3600 * 1000L; // 1 hour in
        long seek1Min = SeekCalculator.computeSeekTarget(midPos, 60_000L, longDuration);
        assertEquals(3660 * 1000L, seek1Min);

        // Seek near end clamps to duration
        long nearEnd = longDuration - 10_000L;
        long seekPastEnd = SeekCalculator.computeSeekTarget(nearEnd, 30_000L, longDuration);
        assertEquals(longDuration, seekPastEnd);
    }

    @Test
    public void testInitialSyncGracePeriod() {
        assertTrue(SeekCalculator.isInitialGracePeriod(0L, 200L));
        assertTrue(SeekCalculator.isInitialGracePeriod(150L, 200L));
        assertTrue(SeekCalculator.isInitialGracePeriod(200L, 200L));
        assertFalse(SeekCalculator.isInitialGracePeriod(201L, 200L));
        assertFalse(SeekCalculator.isInitialGracePeriod(5000L, 200L));
    }
}
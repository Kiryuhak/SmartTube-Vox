/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import com.liskovsoft.smartyoutubetv2.common.vot.VotProgressTimer;

import org.junit.Test;

import static org.junit.Assert.*;

public class YandexVotSyncAndCountdownTest {

    @Test
    public void test01_ProgressTimerMonotonicCountdown() {
        VotProgressTimer timer = new VotProgressTimer();
        long startMs = 100_000L;
        timer.start(startMs);
        timer.reconcileEta(60, startMs);

        // At start (0s elapsed): 60s remaining
        assertEquals(60, timer.getRemainingTimeSec(startMs));

        // After 1s (1000ms): 59s remaining
        assertEquals(59, timer.getRemainingTimeSec(startMs + 1000L));

        // After 25s: 35s remaining
        assertEquals(35, timer.getRemainingTimeSec(startMs + 25000L));

        // After 59s: 1s remaining
        assertEquals(1, timer.getRemainingTimeSec(startMs + 59000L));

        // At 60s: 0s remaining
        assertEquals(0, timer.getRemainingTimeSec(startMs + 60000L));

        // After 60s: remaining is 0
        assertEquals(0, timer.getRemainingTimeSec(startMs + 70000L));
    }

    @Test
    public void test02_ProgressTimerSmoothReconciliation() {
        VotProgressTimer timer = new VotProgressTimer();
        long startMs = 100_000L;
        timer.start(startMs);
        timer.reconcileEta(60, startMs);

        // After 10s: local time remaining is 50s
        long t10 = startMs + 10000L;
        assertEquals(50, timer.getRemainingTimeSec(t10));

        // Backend poll returns 50s (same as local) -> no jump
        timer.reconcileEta(50, t10);
        assertEquals(50, timer.getRemainingTimeSec(t10));

        // Backend poll returns 49s (delta within threshold <= 2s) -> no sudden jump
        timer.reconcileEta(49, t10);
        assertEquals(50, timer.getRemainingTimeSec(t10));

        // After 1s more (t=11s): local ticks down to 49s smoothly
        assertEquals(49, timer.getRemainingTimeSec(t10 + 1000L));
    }

    @Test
    public void test03_ProgressTimerElapsedAfterEta() {
        VotProgressTimer timer = new VotProgressTimer();
        long startMs = 100_000L;
        timer.start(startMs);
        timer.reconcileEta(10, startMs);

        // At t=10s: 0s remaining
        assertEquals(0, timer.getRemainingTimeSec(startMs + 10000L));

        // At t=15s: 5s overtime
        assertEquals(5, timer.getElapsedAfterEtaSec(startMs + 15000L));
        assertEquals("00:05", VotProgressTimer.formatMmSs(timer.getElapsedAfterEtaSec(startMs + 15000L)));

        // At t=75s: 65s overtime -> "01:05"
        assertEquals(65, timer.getElapsedAfterEtaSec(startMs + 75000L));
        assertEquals("01:05", VotProgressTimer.formatMmSs(timer.getElapsedAfterEtaSec(startMs + 75000L)));
    }

    @Test
    public void test04_ProgressTimerHardTimeout() {
        VotProgressTimer timer = new VotProgressTimer();
        long startMs = 100_000L;
        timer.start(startMs);

        assertFalse(timer.isHardTimeoutReached(startMs + 1000L, 25 * 60 * 1000L));
        assertTrue(timer.isHardTimeoutReached(startMs + 25 * 60 * 1000L + 1L, 25 * 60 * 1000L));
    }

    @Test
    public void test05_ProgressTimerClearResetsAll() {
        VotProgressTimer timer = new VotProgressTimer();
        long startMs = 100_000L;
        timer.start(startMs);
        timer.reconcileEta(30, startMs);
        assertEquals(30, timer.getRemainingTimeSec(startMs));

        timer.clear();
        assertEquals(0, timer.getRemainingTimeSec(startMs));
        assertEquals(0, timer.getElapsedAfterEtaSec(startMs + 10000L));
    }

    // Helper class simulating VoiceTranslateController position calculation
    private static class SyncPositionCalculator {
        private static final long MAX_REWIND_START_POSITION_MS = 15000L;
        private static final long MIN_REWIND_ELAPSED_DELTA_MS = 3000L;

        long requestStartPositionMs;
        boolean requestWasAutoTranslate;
        boolean userSeekedDuringPreparation;
        long currentMainPos;
        long seekedMainPosTo = -1;

        long determineInitialPlaybackPosition() {
            boolean shouldRewind = !userSeekedDuringPreparation
                    && (requestWasAutoTranslate || requestStartPositionMs <= MAX_REWIND_START_POSITION_MS);
            long deltaMs = currentMainPos - requestStartPositionMs;

            if (shouldRewind && deltaMs > MIN_REWIND_ELAPSED_DELTA_MS) {
                seekedMainPosTo = requestStartPositionMs;
                return requestStartPositionMs;
            } else {
                return currentMainPos;
            }
        }
    }

    @Test
    public void test06_LateTranslationRewindsNearStartManual() {
        SyncPositionCalculator calc = new SyncPositionCalculator();
        calc.requestStartPositionMs = 2000L; // Started at 2s
        calc.requestWasAutoTranslate = false;
        calc.userSeekedDuringPreparation = false;
        calc.currentMainPos = 35000L; // Video played to 35s while waiting

        long target = calc.determineInitialPlaybackPosition();
        assertEquals(2000L, target);
        assertEquals(2000L, calc.seekedMainPosTo);
    }

    @Test
    public void test07_LateTranslationRewindsAutoTranslate() {
        SyncPositionCalculator calc = new SyncPositionCalculator();
        calc.requestStartPositionMs = 0L;
        calc.requestWasAutoTranslate = true;
        calc.userSeekedDuringPreparation = false;
        calc.currentMainPos = 40000L;

        long target = calc.determineInitialPlaybackPosition();
        assertEquals(0L, target);
        assertEquals(0L, calc.seekedMainPosTo);
    }

    @Test
    public void test08_MidVideoManualStartDoesNotRewind() {
        SyncPositionCalculator calc = new SyncPositionCalculator();
        calc.requestStartPositionMs = 120_000L; // 2:00 into video
        calc.requestWasAutoTranslate = false;
        calc.userSeekedDuringPreparation = false;
        calc.currentMainPos = 145_000L; // 2:25 into video

        long target = calc.determineInitialPlaybackPosition();
        assertEquals(145_000L, target);
        assertEquals(-1L, calc.seekedMainPosTo); // No main video seek
    }

    @Test
    public void test09_UserSeekDuringPreparationDisablesRewind() {
        SyncPositionCalculator calc = new SyncPositionCalculator();
        calc.requestStartPositionMs = 0L;
        calc.requestWasAutoTranslate = true;
        calc.userSeekedDuringPreparation = true; // User jumped to 10:00
        calc.currentMainPos = 600_000L;

        long target = calc.determineInitialPlaybackPosition();
        assertEquals(600_000L, target);
        assertEquals(-1L, calc.seekedMainPosTo); // No rewind
    }

    @Test
    public void test10_InstantReadyDoesNotSeekJitter() {
        SyncPositionCalculator calc = new SyncPositionCalculator();
        calc.requestStartPositionMs = 1000L;
        calc.requestWasAutoTranslate = true;
        calc.userSeekedDuringPreparation = false;
        calc.currentMainPos = 2500L; // Only 1.5s delta (<= 3s)

        long target = calc.determineInitialPlaybackPosition();
        assertEquals(2500L, target);
        assertEquals(-1L, calc.seekedMainPosTo); // No seek jitter
    }
}

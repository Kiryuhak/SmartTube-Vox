package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VotProgressTimerTest {
    @Test
    public void identicalBackendEtaDoesNotRestartCountdown() {
        VotProgressTimer timer = new VotProgressTimer();
        timer.start(1_000L);
        timer.reconcileEta(84, 1_000L);

        assertEquals(79, timer.getRemainingTimeSec(6_000L));

        timer.reconcileEta(84, 6_000L);

        assertEquals(78, timer.getRemainingTimeSec(7_000L));
    }

    @Test
    public void changedBackendEtaBecomesNewSourceOfTruth() {
        VotProgressTimer timer = new VotProgressTimer();
        timer.start(0L);
        timer.reconcileEta(30, 0L);
        timer.reconcileEta(10, 5_000L);

        assertEquals(10, timer.getRemainingTimeSec(5_000L));
        assertEquals(9, timer.getRemainingTimeSec(6_000L));
    }

    @Test
    public void elapsedStartsWhenEtaExpires() {
        VotProgressTimer timer = new VotProgressTimer();
        timer.start(10_000L);
        timer.reconcileEta(2, 10_000L);

        assertEquals(0, timer.getElapsedAfterEtaSec(12_000L));
        assertEquals(1, timer.getElapsedAfterEtaSec(12_001L));
        assertEquals(3, timer.getElapsedAfterEtaSec(14_001L));
    }

    @Test
    public void timeNeverBecomesNegative() {
        VotProgressTimer timer = new VotProgressTimer();
        timer.start(5_000L);
        timer.reconcileEta(-10, 5_000L);

        assertEquals(0, timer.getRemainingTimeSec(50_000L));
        assertEquals("00:00", VotProgressTimer.formatMmSs(-1));
        assertEquals("01:24", VotProgressTimer.formatMmSs(84));
    }
}

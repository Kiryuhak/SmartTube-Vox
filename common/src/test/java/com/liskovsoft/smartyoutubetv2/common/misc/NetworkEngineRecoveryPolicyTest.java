package com.liskovsoft.smartyoutubetv2.common.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NetworkEngineRecoveryPolicyTest {
    private static final int[] ENGINES = {2, 0, 1};

    @Test
    public void preventsImmediateSwitchAfterFallback() {
        NetworkEngineRecoveryPolicy policy = new NetworkEngineRecoveryPolicy();

        assertEquals(2, policy.selectNextEngine(2, ENGINES, 0));
        assertEquals(0, policy.selectNextEngine(2, ENGINES, 1_000));
        assertEquals(0, policy.selectNextEngine(0, ENGINES, 30_000));
        assertEquals(0, policy.selectNextEngine(0, ENGINES, 31_000));
        assertEquals(1, policy.selectNextEngine(0, ENGINES, 61_000));
    }

    @Test
    public void preventsImmediateReturnToPreviousEngine() {
        NetworkEngineRecoveryPolicy policy = new NetworkEngineRecoveryPolicy();
        int[] twoEngines = {0, 1};

        assertEquals(0, policy.selectNextEngine(0, twoEngines, 0));
        assertEquals(1, policy.selectNextEngine(0, twoEngines, 1_000));
        assertEquals(1, policy.selectNextEngine(1, twoEngines, 60_000));
        assertEquals(1, policy.selectNextEngine(1, twoEngines, 61_000));
        assertEquals(0, policy.selectNextEngine(1, twoEngines, 121_000));
    }

    @Test
    public void enforcesRollingSwitchBudget() {
        NetworkEngineRecoveryPolicy policy = new NetworkEngineRecoveryPolicy();

        assertEquals(2, policy.selectNextEngine(2, ENGINES, 0));
        assertEquals(0, policy.selectNextEngine(2, ENGINES, 1_000));
        assertEquals(0, policy.selectNextEngine(0, ENGINES, 61_000));
        assertEquals(1, policy.selectNextEngine(0, ENGINES, 62_000));
        assertEquals(1, policy.selectNextEngine(1, ENGINES, 180_000));
        assertEquals(1, policy.selectNextEngine(1, ENGINES, 181_000));
        assertEquals(2, policy.selectNextEngine(1, ENGINES, 301_000));
    }
}

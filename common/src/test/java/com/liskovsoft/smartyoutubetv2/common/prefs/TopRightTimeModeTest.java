package com.liskovsoft.smartyoutubetv2.common.prefs;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TopRightTimeModeTest {
    @Test
    public void migratesAllLegacyFlagCombinations() {
        assertEquals(TopRightTimeMode.OFF, TopRightTimeMode.fromStored(-1, false, false));
        assertEquals(TopRightTimeMode.CURRENT_TIME_ONLY, TopRightTimeMode.fromStored(-1, true, false));
        assertEquals(TopRightTimeMode.END_TIME_ONLY, TopRightTimeMode.fromStored(-1, false, true));
        assertEquals(TopRightTimeMode.CURRENT_TIME_AND_END_TIME, TopRightTimeMode.fromStored(-1, true, true));
    }

    @Test
    public void storedModeWinsAndEveryIdRoundTrips() {
        for (TopRightTimeMode mode : TopRightTimeMode.values()) {
            assertEquals(mode, TopRightTimeMode.fromStored(mode.id, false, false));
        }
    }
}

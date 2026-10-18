package com.liskovsoft.smartyoutubetv2.common.vox.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VoxAudioBoosterTest {

    @Test
    public void testCalculateGainMb_standardLevels() {
        // 100% (unity gain) -> 0 mB
        assertEquals(0, VoxAudioBooster.calculateGainMb(100));

        // 150% -> 20 * log10(1.5) * 100 = ~352 mB (+3.5 dB)
        assertEquals(352, VoxAudioBooster.calculateGainMb(150));

        // 200% -> 20 * log10(2.0) * 100 = ~602 mB (+6.0 dB)
        assertEquals(602, VoxAudioBooster.calculateGainMb(200));

        // 250% -> 20 * log10(2.5) * 100 = ~796 mB (+8.0 dB)
        assertEquals(796, VoxAudioBooster.calculateGainMb(250));

        // 300% -> 20 * log10(3.0) * 100 = ~954 mB (+9.5 dB)
        assertEquals(954, VoxAudioBooster.calculateGainMb(300));
    }

    @Test
    public void testCalculateGainMb_boundaryClamping() {
        // Values <= 100 produce 0
        assertEquals(0, VoxAudioBooster.calculateGainMb(50));
        assertEquals(0, VoxAudioBooster.calculateGainMb(0));
        assertEquals(0, VoxAudioBooster.calculateGainMb(-10));

        // Values exceeding safe limit are clamped to MAX_GAIN_MB (1200 mB = +12 dB)
        assertEquals(VoxAudioBooster.MAX_GAIN_MB, VoxAudioBooster.calculateGainMb(500));
        assertEquals(VoxAudioBooster.MAX_GAIN_MB, VoxAudioBooster.calculateGainMb(1000));
    }

    @Test
    public void testBoosterState_clampsBoostPercent() {
        VoxAudioBooster booster = new VoxAudioBooster(true, 150);
        assertTrue(booster.isEnabled());
        assertEquals(150, booster.getBoostPercent());
        assertEquals(352, booster.getTargetGainMb());

        // Clamp below MIN_BOOST_PERCENT (100)
        booster.setBoostPercent(40);
        assertEquals(VoxAudioBooster.MIN_BOOST_PERCENT, booster.getBoostPercent());
        assertEquals(0, booster.getTargetGainMb());

        // Clamp above MAX_SAFE_BOOST_PERCENT (300)
        booster.setBoostPercent(450);
        assertEquals(VoxAudioBooster.MAX_SAFE_BOOST_PERCENT, booster.getBoostPercent());
        assertEquals(954, booster.getTargetGainMb());

        booster.setEnabled(false);
        assertFalse(booster.isEnabled());

        // Safe release
        booster.release();
    }
}

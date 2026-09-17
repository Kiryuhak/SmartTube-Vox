package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.vot.ui.VoxQuickMixer;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class VoxQuickMixerTest {

    @Test
    public void testDefaults() {
        VoxQuickMixer mixer = new VoxQuickMixer();
        assertEquals(VoxQuickMixer.DEFAULT_ORIGINAL_VOLUME_PERCENT, mixer.getOriginalVolumePercent());
        assertEquals(VoxQuickMixer.DEFAULT_TRANSLATION_VOLUME_PERCENT, mixer.getTranslationVolumePercent());
        assertEquals(0.05f, mixer.getOriginalVolumeMultiplier(), 0.001f);
        assertEquals(1.00f, mixer.getTranslationVolumeMultiplier(), 0.001f);
    }

    @Test
    public void testStepIncrementsAndClamping() {
        VoxQuickMixer mixer = new VoxQuickMixer();

        // Increase original volume from 5% to 10%
        mixer.increaseOriginalVolume();
        assertEquals(10, mixer.getOriginalVolumePercent());

        // Decrease original volume twice: 10% -> 5% -> 0%
        mixer.decreaseOriginalVolume();
        mixer.decreaseOriginalVolume();
        assertEquals(0, mixer.getOriginalVolumePercent());

        // Clamp at minimum 0%
        mixer.decreaseOriginalVolume();
        assertEquals(0, mixer.getOriginalVolumePercent());

        // Translation volume upper clamp at 100%
        mixer.increaseTranslationVolume();
        assertEquals(100, mixer.getTranslationVolumePercent());

        // Decrease translation volume: 100% -> 95%
        mixer.decreaseTranslationVolume();
        assertEquals(95, mixer.getTranslationVolumePercent());
        assertEquals(0.95f, mixer.getTranslationVolumeMultiplier(), 0.001f);
    }

    @Test
    public void testListenerNotification() {
        VoxQuickMixer mixer = new VoxQuickMixer();
        AtomicInteger notifyCount = new AtomicInteger();

        mixer.setListener((orig, trans) -> notifyCount.incrementAndGet());

        mixer.setOriginalVolumePercent(15);
        assertEquals(1, notifyCount.get());

        // Same value does not trigger listener
        mixer.setOriginalVolumePercent(15);
        assertEquals(1, notifyCount.get());

        mixer.resetToDefaults();
        assertEquals(2, notifyCount.get());
        assertEquals(5, mixer.getOriginalVolumePercent());
        assertEquals(100, mixer.getTranslationVolumePercent());
    }
}
package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.vot.ui.VoxSettingsModel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VoxSettingsModelTest {

    @Test
    public void testDefaultSettings() {
        VoxSettingsModel model = new VoxSettingsModel();
        assertFalse(model.isAutoTranslateEnabled());
        assertEquals(VoxSettingsModel.YandexAuthStatus.NOT_AUTHORIZED, model.getYandexAuthStatus());
        assertFalse(model.isLivelyVoiceEnabled());
        assertTrue(model.isAutomaticFallbackEnabled());
        assertEquals(5, model.getOriginalVolumePercent());
        assertEquals(100, model.getTranslationVolumePercent());
        assertEquals(VoxSettingsModel.YoutubeTrackPreference.PREFER_YOUTUBE_DUB, model.getYoutubeTrackPreference());
    }

    @Test
    public void testLivelyRequiresAuthorization() {
        VoxSettingsModel model = new VoxSettingsModel();
        model.setLivelyVoiceEnabled(true);
        // Cannot enable lively if not authorized
        assertFalse(model.isLivelyVoiceEnabled());

        model.setYandexAuthStatus(VoxSettingsModel.YandexAuthStatus.AUTHORIZED);
        model.setLivelyVoiceEnabled(true);
        assertTrue(model.isLivelyVoiceEnabled());

        // Logging out automatically disables lively voice
        model.setYandexAuthStatus(VoxSettingsModel.YandexAuthStatus.NOT_AUTHORIZED);
        assertFalse(model.isLivelyVoiceEnabled());
    }

    @Test
    public void testVolumeClamping() {
        VoxSettingsModel model = new VoxSettingsModel();
        model.setOriginalVolumePercent(-10);
        assertEquals(0, model.getOriginalVolumePercent());

        model.setOriginalVolumePercent(150);
        assertEquals(100, model.getOriginalVolumePercent());

        model.setTranslationVolumePercent(-50);
        assertEquals(0, model.getTranslationVolumePercent());

        model.setTranslationVolumePercent(200);
        assertEquals(100, model.getTranslationVolumePercent());
    }

    @Test
    public void testSecurityStringRepresentation() {
        VoxSettingsModel model = new VoxSettingsModel();
        model.setYandexAuthStatus(VoxSettingsModel.YandexAuthStatus.AUTHORIZED);
        model.setLivelyVoiceEnabled(true);
        String rep = model.toString();

        assertFalse(rep.toLowerCase().contains("token"));
        assertFalse(rep.toLowerCase().contains("secret"));
        assertFalse(rep.toLowerCase().contains("cookie"));
        assertFalse(rep.toLowerCase().contains("password"));
        assertTrue(rep.contains("AUTHORIZED"));
    }
}
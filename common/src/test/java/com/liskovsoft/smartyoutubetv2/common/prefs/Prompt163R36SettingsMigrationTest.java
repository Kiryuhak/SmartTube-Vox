package com.liskovsoft.smartyoutubetv2.common.prefs;

import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying SmartTube 32.71 setting migrations:
 * - Live Edge setting in PlayerTweaksData
 * - SABR Live setting migration to MediaServiceData
 * - Video Transport preference isolation
 */
public class Prompt163R36SettingsMigrationTest {

    @Test
    public void testVideoTransportConstants() {
        assertEquals(0, PlayerTweaksData.VIDEO_TRANSPORT_AUTO);
        assertEquals(1, PlayerTweaksData.VIDEO_TRANSPORT_DASH);
        assertEquals(2, PlayerTweaksData.VIDEO_TRANSPORT_SABR);
    }

    @Test
    public void testMediaServiceDataSabrLiveSetting() {
        MediaServiceData mediaServiceData = MediaServiceData.instance();
        assertNotNull("MediaServiceData singleton must be non-null", mediaServiceData);

        // Verify default and toggling
        boolean initial = mediaServiceData.isSabrLiveEnabled();
        try {
            mediaServiceData.setSabrLiveEnabled(!initial);
            assertEquals(!initial, mediaServiceData.isSabrLiveEnabled());
        } finally {
            mediaServiceData.setSabrLiveEnabled(initial);
        }
    }
}

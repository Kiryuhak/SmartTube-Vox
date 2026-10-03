package com.liskovsoft.smartyoutubetv2.common.prefs;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for PlayerTweaksData PLAYER_BUTTON_DOWNLOAD bitmask and integration.
 */
public class PlayerTweaksDataDownloadButtonTest {

    @Test
    public void testPlayerButtonDownloadBitmask() {
        int downloadBit = PlayerTweaksData.PLAYER_BUTTON_DOWNLOAD;
        assertEquals("PLAYER_BUTTON_DOWNLOAD bitmask should be 1 << 29", 1 << 29, downloadBit);
        assertNotEquals(0, downloadBit);

        // Must not collide with voice translate or other buttons
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE, downloadBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_PLAY_PAUSE, downloadBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_PREVIOUS, downloadBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_NEXT, downloadBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_VIDEO_SPEED, downloadBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_PIP, downloadBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_CHAT, downloadBit);
    }

    @Test
    public void testDownloadButtonDefaultEnabledInMask() {
        int defaultMask = PlayerTweaksData.PLAYER_BUTTON_DEFAULT;
        assertTrue("PLAYER_BUTTON_DOWNLOAD should be enabled by default in PLAYER_BUTTON_DEFAULT",
                (defaultMask & PlayerTweaksData.PLAYER_BUTTON_DOWNLOAD) != 0);
    }

    @Test
    public void testBitmaskToggleLogic() {
        int mask = 0;
        int downloadBit = PlayerTweaksData.PLAYER_BUTTON_DOWNLOAD;

        // Initially disabled
        assertFalse((mask & downloadBit) != 0);

        // Enable
        mask |= downloadBit;
        assertTrue((mask & downloadBit) != 0);

        // Disable
        mask &= ~downloadBit;
        assertFalse((mask & downloadBit) != 0);
    }
}

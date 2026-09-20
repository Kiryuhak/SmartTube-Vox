package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Batch #9 — Тесты миграции и доступности кнопки перевода в HUD SmartTube VOX.
 */
public class VotPlayerButtonMigrationTest {

    @Test
    public void testPlayerButtonVoiceTranslateBitmask() {
        // PLAYER_BUTTON_VOICE_TRANSLATE must be distinct and non-zero
        int translateBit = PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE;
        assertEquals("PLAYER_BUTTON_VOICE_TRANSLATE bitmask should be 1 << 28", 1 << 28, translateBit);
        assertNotEquals(0, translateBit);

        // Must not collide with primary or secondary buttons
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_PLAY_PAUSE, translateBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_PREVIOUS, translateBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_NEXT, translateBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_VIDEO_SPEED, translateBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_PIP, translateBit);
        assertNotEquals(PlayerTweaksData.PLAYER_BUTTON_CHAT, translateBit);
    }

    @Test
    public void testBitmaskToggleLogic() {
        int mask = 0;
        int translateBit = PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE;

        // Initially disabled
        assertFalse((mask & translateBit) != 0);

        // Enable
        mask |= translateBit;
        assertTrue((mask & translateBit) != 0);

        // Disable
        mask &= ~translateBit;
        assertFalse((mask & translateBit) != 0);
    }

    @Test
    public void testBitmaskPreservesOtherButtons() {
        int initialMask = PlayerTweaksData.PLAYER_BUTTON_PLAY_PAUSE | PlayerTweaksData.PLAYER_BUTTON_PREVIOUS;
        int translateBit = PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE;

        int withTranslate = initialMask | translateBit;
        assertEquals(initialMask, withTranslate & ~translateBit);
        assertTrue((withTranslate & PlayerTweaksData.PLAYER_BUTTON_PLAY_PAUSE) != 0);
        assertTrue((withTranslate & PlayerTweaksData.PLAYER_BUTTON_PREVIOUS) != 0);
        assertTrue((withTranslate & translateBit) != 0);
    }
}

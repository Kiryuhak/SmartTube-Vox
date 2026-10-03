package com.liskovsoft.smartyoutubetv2.common.prefs;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ChatModePreferenceTest {

    @Test
    public void testChatModeConstants() {
        assertEquals(0, PlayerData.CHAT_MODE_TOP);
        assertEquals(1, PlayerData.CHAT_MODE_LIVE);
        assertNotEquals(PlayerData.CHAT_MODE_TOP, PlayerData.CHAT_MODE_LIVE);
    }
}

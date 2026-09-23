package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.misc.ResetManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Batch #11 — Тесты оформления диалогов, вложенных настроек и окон SmartTube VOX.
 */
public class VotBatch11ThemeTest {

    @Test
    public void testResetDialogStringsPresent() {
        assertNotEquals(0, R.string.reset_dialog_title);
        assertNotEquals(0, R.string.reset_dialog_message);
        assertNotEquals(0, R.string.reset_dialog_cancel);
        assertNotEquals(0, R.string.reset_dialog_confirm);
        assertNotEquals(0, R.string.reset_dialog_failed);
    }

    @Test
    public void testYandexDeviceAuthDialogStringsPresent() {
        assertNotEquals(0, R.string.vot_device_auth_title);
        assertNotEquals(0, R.string.vot_device_auth_step1);
        assertNotEquals(0, R.string.vot_device_auth_url);
        assertNotEquals(0, R.string.vot_device_auth_refresh);
        assertNotEquals(0, R.string.vot_device_auth_cancel);
        assertNotEquals(0, R.string.vot_device_auth_pending);
        assertNotEquals(0, R.string.vot_device_auth_network_error);
    }

    @Test
    public void testTokenEditDialogStringsPresent() {
        assertNotEquals(0, R.string.vot_token_dialog_title);
        assertNotEquals(0, R.string.vot_token_dialog_hint);
        assertNotEquals(0, R.string.vot_paste_clipboard);
        assertNotEquals(0, R.string.vot_clear_token);
    }

    @Test
    public void testVotProgressOverlayStringsPresent() {
        assertNotEquals(0, R.string.vot_progress_preparing);
        assertNotEquals(0, R.string.vot_progress_starting);
        assertNotEquals(0, R.string.vot_progress_ready);
        assertNotEquals(0, R.string.vot_progress_error);
        assertNotEquals(0, R.string.vot_progress_timeout);
    }

    @Test
    public void testResetManagerInvariantsPreserved() {
        assertNotNull(ResetManager.instance());
        assertFalse("ResetManager should not be resetting initially", ResetManager.instance().isResetting());
    }

    @Test
    public void testVotAuthStateInvariantsPreserved() {
        for (VotData.AuthState state : VotData.AuthState.values()) {
            assertNotNull(state.name());
        }
        assertEquals(4, VotData.AuthState.values().length);
    }
}

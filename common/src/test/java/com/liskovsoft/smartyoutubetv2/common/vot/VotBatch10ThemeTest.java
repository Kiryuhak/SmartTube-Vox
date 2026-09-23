package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.utils.VotOnboardingHelper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Batch #10 — Тесты изоляции темы, разделов меню и настроек SmartTube VOX.
 */
public class VotBatch10ThemeTest {

    @Test
    public void testVotOnboardingHelperNullSafety() {
        assertFalse(VotOnboardingHelper.isStvot(null));
    }

    @Test
    public void testSettingsResetResourcePresent() {
        assertNotEquals(0, R.string.settings_reset);
        assertNotEquals(0, R.string.settings_about);
    }

    @Test
    public void testVotSettingsStringsPresent() {
        assertNotEquals(0, R.string.vot_settings_category);
        assertNotEquals(0, R.string.vot_auto_translate);
        assertNotEquals(0, R.string.vot_player_button_switch);
    }

    @Test
    public void testAuthStatePreservation() {
        // Проверяем сохранность состояний авторизации при редизайне
        assertEquals(4, VotData.AuthState.values().length);
        assertNotNull(VotData.AuthState.valueOf("CONFIRMED"));
        assertNotNull(VotData.AuthState.valueOf("UNVERIFIED"));
        assertNotNull(VotData.AuthState.valueOf("REJECTED"));
        assertNotNull(VotData.AuthState.valueOf("ABSENT"));
    }
}

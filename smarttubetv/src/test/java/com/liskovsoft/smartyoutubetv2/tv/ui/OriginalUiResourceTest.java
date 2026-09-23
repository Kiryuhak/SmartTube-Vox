package com.liskovsoft.smartyoutubetv2.tv.ui;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class OriginalUiResourceTest {
    @Test
    public void stvotUsesOriginalSmartTubeLayoutsAndKeepsTranslationAction() {
        assertEquals("stvot", BuildConfig.FLAVOR);
        assertNotEquals(0, R.layout.lb_playback_transport_controls_row);
        assertNotEquals(0, R.layout.icon_header_item);
        assertNotEquals(0, R.layout.settings_card);
        assertNotEquals(0, R.drawable.action_voice_translate);
    }

    @Test
    public void redesignOnlyResourcesAreNotPackaged() {
        assertResourceMissing(R.drawable.class, "vox_hud_primary_panel");
        assertResourceMissing(R.drawable.class, "vox_hud_secondary_panel");
        assertResourceMissing(R.drawable.class, "vox_sidebar_item_bg");
        assertResourceMissing(R.drawable.class, "vox_settings_card_bg");
    }

    private static void assertResourceMissing(Class<?> resourceClass, String fieldName) {
        try {
            resourceClass.getField(fieldName);
            fail("Redesign resource must not be packaged: " + fieldName);
        } catch (NoSuchFieldException expected) {
            // Expected: the flavor falls back to the original SmartTube resource set.
        }
    }
}

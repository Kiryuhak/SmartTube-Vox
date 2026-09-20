package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tweaks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TopEdgeFocusHandlerTest {
    @Test
    public void voxNotifiesWithoutClearingFocusRecursively() {
        List<String> actions = new ArrayList<>();

        TopEdgeFocusHandler.onFocusGained(
                true,
                () -> actions.add("clear"),
                () -> actions.add("notify"));

        assertEquals(Arrays.asList("notify"), actions);
    }

    @Test
    public void otherFlavorsKeepExistingFocusBehavior() {
        List<String> actions = new ArrayList<>();

        TopEdgeFocusHandler.onFocusGained(
                false,
                () -> actions.add("clear"),
                () -> actions.add("notify"));

        assertEquals(Arrays.asList("clear", "notify"), actions);
    }
}

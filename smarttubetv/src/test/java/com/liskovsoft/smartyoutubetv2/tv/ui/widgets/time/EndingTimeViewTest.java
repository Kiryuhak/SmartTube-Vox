package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.time;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class EndingTimeViewTest {
    @Test
    public void formatEndingTimeShowsOnlyCalculatedTime() {
        String result = EndingTimeView.formatEndingTime("23:25");

        assertEquals("23:25", result);
        assertFalse(result.contains("⌛"));
        assertFalse(result.contains("⏳"));
        assertFalse(result.contains("?"));
    }

    @Test
    public void formatEndingTimeKeepsEmptyValueHidden() {
        assertNull(EndingTimeView.formatEndingTime(null));
        assertNull(EndingTimeView.formatEndingTime(""));
    }
}

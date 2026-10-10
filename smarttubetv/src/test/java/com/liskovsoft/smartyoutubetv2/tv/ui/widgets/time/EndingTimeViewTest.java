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
    public void formatEndingTimePreserves24HourLeadingZero() {
        assertEquals("00:09", EndingTimeView.formatEndingTime("00:09"));
        assertEquals("00:09", EndingTimeView.formatEndingTime("0:09"));
        assertEquals("01:03", EndingTimeView.formatEndingTime("01:03"));
        assertEquals("01:03", EndingTimeView.formatEndingTime("1:03"));
        assertEquals("09:07", EndingTimeView.formatEndingTime("09:07"));
        assertEquals("09:07", EndingTimeView.formatEndingTime("9:07"));
        assertEquals("18:44", EndingTimeView.formatEndingTime("18:44"));
        assertEquals("23:59", EndingTimeView.formatEndingTime("23:59"));
    }

    @Test
    public void formatEndingTimeKeepsEmptyValueHidden() {
        assertNull(EndingTimeView.formatEndingTime(null));
        assertNull(EndingTimeView.formatEndingTime(""));
    }

    @Test
    public void remainingTimeUsesActualPositionAndFormatsHours() {
        assertEquals("01:01", RemainingTimeFormatter.format(122_000, 61_000, false));
        assertEquals("00:20", RemainingTimeFormatter.format(122_000, 102_000, false));
        assertEquals("01:11", RemainingTimeFormatter.format(122_000, 51_000, false));
        assertEquals("1:01:01", RemainingTimeFormatter.format(3_661_000, 0, false));
        assertEquals("00:00", RemainingTimeFormatter.format(122_000, 124_000, false));
    }

    @Test
    public void remainingTimeHidesLiveAndUnknownDuration() {
        assertNull(RemainingTimeFormatter.format(122_000, 0, true));
        assertNull(RemainingTimeFormatter.format(0, 0, false));
        assertNull(RemainingTimeFormatter.format(-1, 0, false));
        assertNull(RemainingTimeFormatter.format(Long.MAX_VALUE, 0, false));
    }
}

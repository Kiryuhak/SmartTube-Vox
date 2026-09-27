package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.time;

import java.util.Locale;

final class RemainingTimeFormatter {
    private RemainingTimeFormatter() {
    }

    static String format(long durationMs, long positionMs, boolean isLive) {
        if (isLive || durationMs <= 0 || durationMs >= Long.MAX_VALUE / 2) {
            return null;
        }

        long remainingSeconds = Math.max(0, durationMs - Math.max(0, positionMs)) / 1_000;
        long hours = remainingSeconds / 3_600;
        long minutes = remainingSeconds / 60 % 60;
        long seconds = remainingSeconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}

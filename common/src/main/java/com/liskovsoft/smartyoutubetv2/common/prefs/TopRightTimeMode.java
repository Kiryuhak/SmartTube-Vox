package com.liskovsoft.smartyoutubetv2.common.prefs;

public enum TopRightTimeMode {
    OFF(0, false, false, false),
    CURRENT_TIME_AND_END_TIME(1, true, true, false),
    REMAINING_TIME(2, false, true, true),
    CURRENT_TIME_AND_REMAINING_TIME(3, true, true, true),
    CURRENT_TIME_ONLY(4, true, false, false),
    END_TIME_ONLY(5, false, true, false);

    public final int id;
    public final boolean showClock;
    public final boolean showSecondTime;
    public final boolean showRemaining;

    TopRightTimeMode(int id, boolean showClock, boolean showSecondTime, boolean showRemaining) {
        this.id = id;
        this.showClock = showClock;
        this.showSecondTime = showSecondTime;
        this.showRemaining = showRemaining;
    }

    public static TopRightTimeMode fromStored(int id, boolean legacyClock, boolean legacyEndTime) {
        for (TopRightTimeMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return fromLegacy(legacyClock, legacyEndTime);
    }

    public static TopRightTimeMode fromLegacy(boolean clock, boolean endTime) {
        if (clock) {
            return endTime ? CURRENT_TIME_AND_END_TIME : CURRENT_TIME_ONLY;
        }
        return endTime ? END_TIME_ONLY : OFF;
    }
}

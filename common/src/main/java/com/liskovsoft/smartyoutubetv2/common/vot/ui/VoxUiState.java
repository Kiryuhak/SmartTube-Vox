package com.liskovsoft.smartyoutubetv2.common.vot.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Immutable UI state model for the VOX HUD on Android TV.
 * Designed for 10-foot TV readability without emoji glyphs.
 */
public final class VoxUiState {
    public enum Type {
        OFF,
        LOADING,
        STANDARD_ACTIVE,
        LIVELY_ACTIVE,
        FALLBACK,
        ERROR
    }

    public static final String COLOR_ACCENT_VOX = "#FF2A54";
    public static final String COLOR_ACCENT_LIVELY = "#7B2CBF";
    public static final String COLOR_ACCENT_FALLBACK = "#E65100";
    public static final String COLOR_MUTED = "#888888";

    public static final String BADGE_STANDARD = "VOX";
    public static final String BADGE_LIVELY = "ЖИВОЙ ГОЛОС";
    public static final String BADGE_FALLBACK = "FALLBACK";
    public static final String BADGE_LOADING = "ЗАГРУЗКА";
    public static final String BADGE_ERROR = "ОШИБКА";

    @NonNull
    private final Type mType;
    @Nullable
    private final String mBadgeText;
    @NonNull
    private final String mColorHex;
    private final boolean mVisible;
    private final int mRemainingTimeSec;

    public VoxUiState(@NonNull Type type, @Nullable String badgeText, @NonNull String colorHex, boolean visible, int remainingTimeSec) {
        mType = type;
        mBadgeText = badgeText;
        mColorHex = colorHex;
        mVisible = visible;
        mRemainingTimeSec = Math.max(0, remainingTimeSec);
    }

    public static VoxUiState off() {
        return new VoxUiState(Type.OFF, null, COLOR_MUTED, false, 0);
    }

    public static VoxUiState loading(int remainingTimeSec) {
        return new VoxUiState(Type.LOADING, BADGE_LOADING, COLOR_ACCENT_VOX, true, remainingTimeSec);
    }

    public static VoxUiState standardActive() {
        return new VoxUiState(Type.STANDARD_ACTIVE, BADGE_STANDARD, COLOR_ACCENT_VOX, true, 0);
    }

    public static VoxUiState livelyActive() {
        return new VoxUiState(Type.LIVELY_ACTIVE, BADGE_LIVELY, COLOR_ACCENT_LIVELY, true, 0);
    }

    public static VoxUiState fallback() {
        return new VoxUiState(Type.FALLBACK, BADGE_FALLBACK, COLOR_ACCENT_FALLBACK, true, 0);
    }

    public static VoxUiState error() {
        return new VoxUiState(Type.ERROR, BADGE_ERROR, COLOR_MUTED, true, 0);
    }

    @NonNull
    public Type getType() {
        return mType;
    }

    @Nullable
    public String getBadgeText() {
        return mBadgeText;
    }

    @NonNull
    public String getColorHex() {
        return mColorHex;
    }

    public boolean isVisible() {
        return mVisible;
    }

    public int getRemainingTimeSec() {
        return mRemainingTimeSec;
    }

    public boolean isActivePlayback() {
        return mType == Type.STANDARD_ACTIVE || mType == Type.LIVELY_ACTIVE || mType == Type.FALLBACK;
    }

    /**
     * Formats TV display badge string without emojis (e.g. "[VOX]" or "[ЖИВОЙ ГОЛОС]").
     */
    @NonNull
    public String getFormattedBadge() {
        if (mBadgeText == null || mBadgeText.isEmpty()) {
            return "";
        }
        if (mType == Type.LOADING && mRemainingTimeSec > 0) {
            int min = mRemainingTimeSec / 60;
            int sec = mRemainingTimeSec % 60;
            return String.format("[%s %02d:%02d]", mBadgeText, min, sec);
        }
        return "[" + mBadgeText + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VoxUiState that = (VoxUiState) o;
        return mVisible == that.mVisible &&
                mRemainingTimeSec == that.mRemainingTimeSec &&
                mType == that.mType &&
                Objects.equals(mBadgeText, that.mBadgeText) &&
                Objects.equals(mColorHex, that.mColorHex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mBadgeText, mColorHex, mVisible, mRemainingTimeSec);
    }

    @Override
    public String toString() {
        return "VoxUiState{" +
                "type=" + mType +
                ", badge='" + mBadgeText + '\'' +
                ", color='" + mColorHex + '\'' +
                ", visible=" + mVisible +
                ", remainingSec=" + mRemainingTimeSec +
                '}';
    }
}
package com.liskovsoft.smartyoutubetv2.common.vot.ui;

import androidx.annotation.NonNull;

import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import java.util.Objects;

/**
 * Clean domain model representing VOX player settings on Android TV.
 * Security Invariant: Absolutely no OAuth tokens, cookies, or raw secrets are stored or exposed.
 */
public class VoxSettingsModel {
    public enum YandexAuthStatus {
        NOT_AUTHORIZED,
        AUTHORIZED
    }

    public enum YoutubeTrackPreference {
        PREFER_YOUTUBE_DUB,
        PREFER_VOX
    }

    private boolean mAutoTranslateEnabled;
    @NonNull
    private YandexAuthStatus mYandexAuthStatus;
    private boolean mLivelyVoiceEnabled;
    private boolean mAutomaticFallbackEnabled;
    private int mOriginalVolumePercent;
    private int mTranslationVolumePercent;
    @NonNull
    private YoutubeTrackPreference mYoutubeTrackPreference;

    public VoxSettingsModel() {
        mAutoTranslateEnabled = false;
        mYandexAuthStatus = YandexAuthStatus.NOT_AUTHORIZED;
        mLivelyVoiceEnabled = false;
        mAutomaticFallbackEnabled = true;
        mOriginalVolumePercent = VoxQuickMixer.DEFAULT_ORIGINAL_VOLUME_PERCENT;
        mTranslationVolumePercent = VoxQuickMixer.DEFAULT_TRANSLATION_VOLUME_PERCENT;
        mYoutubeTrackPreference = YoutubeTrackPreference.PREFER_YOUTUBE_DUB;
    }

    public static VoxSettingsModel fromPrefs(VotData votData) {
        VoxSettingsModel model = new VoxSettingsModel();
        if (votData != null) {
            model.setAutoTranslateEnabled(votData.isAutoTranslateEnabled());
            model.setYandexAuthStatus(votData.hasOAuthToken() ? YandexAuthStatus.AUTHORIZED : YandexAuthStatus.NOT_AUTHORIZED);
            model.setLivelyVoiceEnabled(votData.isLivelyVoiceEnabled());
            model.setAutomaticFallbackEnabled(true);
            model.setOriginalVolumePercent(votData.getOriginalVolumePercent());
            model.setTranslationVolumePercent(votData.getTranslationVolumePercent());
            model.setYoutubeTrackPreference(votData.isPreferYoutubeAutoDub()
                    ? YoutubeTrackPreference.PREFER_YOUTUBE_DUB
                    : YoutubeTrackPreference.PREFER_VOX);
        }
        return model;
    }

    public boolean isAutoTranslateEnabled() {
        return mAutoTranslateEnabled;
    }

    public void setAutoTranslateEnabled(boolean enabled) {
        mAutoTranslateEnabled = enabled;
    }

    @NonNull
    public YandexAuthStatus getYandexAuthStatus() {
        return mYandexAuthStatus;
    }

    public void setYandexAuthStatus(@NonNull YandexAuthStatus status) {
        mYandexAuthStatus = Objects.requireNonNull(status);
        if (status == YandexAuthStatus.NOT_AUTHORIZED) {
            mLivelyVoiceEnabled = false;
        }
    }

    public boolean isLivelyVoiceEnabled() {
        return mLivelyVoiceEnabled && mYandexAuthStatus == YandexAuthStatus.AUTHORIZED;
    }

    public void setLivelyVoiceEnabled(boolean enabled) {
        if (enabled && mYandexAuthStatus != YandexAuthStatus.AUTHORIZED) {
            mLivelyVoiceEnabled = false;
        } else {
            mLivelyVoiceEnabled = enabled;
        }
    }

    public boolean isAutomaticFallbackEnabled() {
        return mAutomaticFallbackEnabled;
    }

    public void setAutomaticFallbackEnabled(boolean enabled) {
        mAutomaticFallbackEnabled = enabled;
    }

    public int getOriginalVolumePercent() {
        return mOriginalVolumePercent;
    }

    public void setOriginalVolumePercent(int percent) {
        mOriginalVolumePercent = Math.max(0, Math.min(100, percent));
    }

    public int getTranslationVolumePercent() {
        return mTranslationVolumePercent;
    }

    public void setTranslationVolumePercent(int percent) {
        mTranslationVolumePercent = Math.max(0, Math.min(100, percent));
    }

    @NonNull
    public YoutubeTrackPreference getYoutubeTrackPreference() {
        return mYoutubeTrackPreference;
    }

    public void setYoutubeTrackPreference(@NonNull YoutubeTrackPreference preference) {
        mYoutubeTrackPreference = Objects.requireNonNull(preference);
    }

    @Override
    public String toString() {
        // Safe string representation - guarantees zero secrets or tokens
        return "VoxSettingsModel{" +
                "autoTranslate=" + mAutoTranslateEnabled +
                ", yandexAuth=" + mYandexAuthStatus +
                ", lively=" + mLivelyVoiceEnabled +
                ", fallback=" + mAutomaticFallbackEnabled +
                ", origVol=" + mOriginalVolumePercent + "%" +
                ", transVol=" + mTranslationVolumePercent + "%" +
                ", youtubeDubPref=" + mYoutubeTrackPreference +
                '}';
    }
}
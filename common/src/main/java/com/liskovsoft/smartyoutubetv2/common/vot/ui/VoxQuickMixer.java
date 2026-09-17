package com.liskovsoft.smartyoutubetv2.common.vot.ui;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

/**
 * Controller-level audio mixer for balancing original YouTube audio and VOX translation track.
 * Supports fine-grained 5% D-Pad remote control steps and persistent preference storage.
 */
public class VoxQuickMixer {
    public static final int DEFAULT_ORIGINAL_VOLUME_PERCENT = 5;
    public static final int DEFAULT_TRANSLATION_VOLUME_PERCENT = 100;
    public static final int VOLUME_STEP_PERCENT = 5;
    public static final int MIN_VOLUME_PERCENT = 0;
    public static final int MAX_VOLUME_PERCENT = 100;

    public interface OnMixerChangeListener {
        void onVolumeChanged(int originalVolumePercent, int translationVolumePercent);
    }

    @Nullable
    private final VotData mVotData;
    private int mOriginalVolumePercent;
    private int mTranslationVolumePercent;
    @Nullable
    private OnMixerChangeListener mListener;

    public VoxQuickMixer() {
        this(null);
    }

    public VoxQuickMixer(@Nullable Context context) {
        if (context != null) {
            mVotData = VotData.instance(context);
            mOriginalVolumePercent = mVotData.getOriginalVolumePercent();
            mTranslationVolumePercent = mVotData.getTranslationVolumePercent();
        } else {
            mVotData = null;
            mOriginalVolumePercent = DEFAULT_ORIGINAL_VOLUME_PERCENT;
            mTranslationVolumePercent = DEFAULT_TRANSLATION_VOLUME_PERCENT;
        }
    }

    public void setListener(@Nullable OnMixerChangeListener listener) {
        mListener = listener;
    }

    public int getOriginalVolumePercent() {
        return mOriginalVolumePercent;
    }

    public int getTranslationVolumePercent() {
        return mTranslationVolumePercent;
    }

    public float getOriginalVolumeMultiplier() {
        return mOriginalVolumePercent / 100.0f;
    }

    public float getTranslationVolumeMultiplier() {
        return mTranslationVolumePercent / 100.0f;
    }

    public void setOriginalVolumePercent(int percent) {
        int clamped = clamp(percent);
        if (mOriginalVolumePercent != clamped) {
            mOriginalVolumePercent = clamped;
            persist();
            notifyListener();
        }
    }

    public void setTranslationVolumePercent(int percent) {
        int clamped = clamp(percent);
        if (mTranslationVolumePercent != clamped) {
            mTranslationVolumePercent = clamped;
            persist();
            notifyListener();
        }
    }

    public void increaseOriginalVolume() {
        setOriginalVolumePercent(mOriginalVolumePercent + VOLUME_STEP_PERCENT);
    }

    public void decreaseOriginalVolume() {
        setOriginalVolumePercent(mOriginalVolumePercent - VOLUME_STEP_PERCENT);
    }

    public void increaseTranslationVolume() {
        setTranslationVolumePercent(mTranslationVolumePercent + VOLUME_STEP_PERCENT);
    }

    public void decreaseTranslationVolume() {
        setTranslationVolumePercent(mTranslationVolumePercent - VOLUME_STEP_PERCENT);
    }

    public void resetToDefaults() {
        mOriginalVolumePercent = DEFAULT_ORIGINAL_VOLUME_PERCENT;
        mTranslationVolumePercent = DEFAULT_TRANSLATION_VOLUME_PERCENT;
        persist();
        notifyListener();
    }

    private void persist() {
        if (mVotData != null) {
            mVotData.setOriginalVolumePercent(mOriginalVolumePercent);
            mVotData.setTranslationVolumePercent(mTranslationVolumePercent);
        }
    }

    private void notifyListener() {
        if (mListener != null) {
            mListener.onVolumeChanged(mOriginalVolumePercent, mTranslationVolumePercent);
        }
    }

    private static int clamp(int val) {
        return Math.max(MIN_VOLUME_PERCENT, Math.min(MAX_VOLUME_PERCENT, val));
    }
}
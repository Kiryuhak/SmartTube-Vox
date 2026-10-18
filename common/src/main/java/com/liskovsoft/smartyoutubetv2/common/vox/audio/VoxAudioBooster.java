package com.liskovsoft.smartyoutubetv2.common.vox.audio;

import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.audio.AudioListener;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotLog;

/**
 * Native hardware/DSP-accelerated audio booster for translated voice track.
 * Uses Android's LoudnessEnhancer on the translation AudioTrack session to achieve
 * gain amplification beyond 100% (e.g. 150% - 300%) with built-in platform dynamic range compression
 * and distortion limiting.
 */
public class VoxAudioBooster implements AudioListener {
    private static final String TAG = "VoxAudioBooster";
    public static final int MIN_BOOST_PERCENT = 100;
    public static final int MAX_SAFE_BOOST_PERCENT = 300;
    public static final int MAX_GAIN_MB = 1200; // +12 dB maximum safe threshold

    private boolean mIsEnabled = false;
    private int mBoostPercent = 100;
    private int mCurrentSessionId = -1;
    @Nullable private LoudnessEnhancer mEnhancer;
    private boolean mIsSupported = true;
    private int mTargetGainMb = 0;

    public VoxAudioBooster(boolean enabled, int boostPercent) {
        mIsEnabled = enabled;
        setBoostPercent(boostPercent);
    }

    public synchronized void setBoostPercent(int boostPercent) {
        mBoostPercent = Math.max(MIN_BOOST_PERCENT, Math.min(MAX_SAFE_BOOST_PERCENT, boostPercent));
        mTargetGainMb = calculateGainMb(mBoostPercent);
        applyTargetGainLocked();
    }

    public synchronized int getBoostPercent() {
        return mBoostPercent;
    }

    public synchronized void setEnabled(boolean enabled) {
        mIsEnabled = enabled;
        if (mEnhancer != null && mIsSupported) {
            try {
                mEnhancer.setEnabled(enabled && mBoostPercent > 100);
            } catch (Exception e) {
                YandexVotLog.w(TAG, "Failed to toggle LoudnessEnhancer: " + e.getMessage());
            }
        }
    }

    public synchronized boolean isEnabled() {
        return mIsEnabled;
    }

    public synchronized boolean isSupported() {
        return mIsSupported;
    }

    public synchronized int getTargetGainMb() {
        return mTargetGainMb;
    }

    @Override
    public synchronized void onAudioSessionId(int audioSessionId) {
        if (Build.VERSION.SDK_INT < 19 || audioSessionId <= 0) {
            return;
        }

        if (mCurrentSessionId == audioSessionId && mEnhancer != null) {
            return; // Already attached to this session
        }

        mCurrentSessionId = audioSessionId;
        releaseEffectLocked();

        try {
            mEnhancer = new LoudnessEnhancer(audioSessionId);
            mEnhancer.setTargetGain(mTargetGainMb);
            mEnhancer.setEnabled(mIsEnabled && mBoostPercent > 100);
            mIsSupported = true;
            YandexVotLog.i(TAG, "LoudnessEnhancer attached to session=" + audioSessionId + " gainMb=" + mTargetGainMb);
        } catch (Throwable t) {
            YandexVotLog.w(TAG, "LoudnessEnhancer unavailable on session=" + audioSessionId + ": " + t.getMessage());
            mIsSupported = false;
            mEnhancer = null;
        }
    }

    public synchronized void release() {
        mCurrentSessionId = -1;
        releaseEffectLocked();
    }

    private void releaseEffectLocked() {
        if (mEnhancer != null) {
            try {
                mEnhancer.setEnabled(false);
                mEnhancer.release();
            } catch (Exception e) {
                YandexVotLog.w(TAG, "Error releasing LoudnessEnhancer: " + e.getMessage());
            }
            mEnhancer = null;
        }
    }

    private void applyTargetGainLocked() {
        if (mEnhancer != null && mIsSupported) {
            try {
                mEnhancer.setTargetGain(mTargetGainMb);
                mEnhancer.setEnabled(mIsEnabled && mBoostPercent > 100);
            } catch (Exception e) {
                YandexVotLog.w(TAG, "Error applying target gain: " + e.getMessage());
            }
        }
    }

    public static int calculateGainMb(int boostPercent) {
        if (boostPercent <= 100) return 0;
        // 20 * log10(ratio) * 100 = millibels (mB)
        // Ratio = boostPercent / 100.0
        // e.g. 200% -> 20 * log10(2) * 100 = +602 mB (+6 dB)
        // e.g. 300% -> 20 * log10(3) * 100 = +954 mB (+9.5 dB)
        double ratio = boostPercent / 100.0;
        double gainMb = 20.0 * Math.log10(ratio) * 100.0;
        return (int) Math.max(0, Math.min(MAX_GAIN_MB, Math.round(gainMb)));
    }
}

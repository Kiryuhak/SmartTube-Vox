package com.liskovsoft.smartyoutubetv2.common.vox.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotLog;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller orchestrating Adaptive Volume (Smart Ducking), Fixed Auto Volume, Volume Sync,
 * Baseline Tracking, and Mute State Preservation.
 */
public class VoxAdaptiveVolumeController {
    private static final String TAG = "VoxAdaptiveVolume";

    public enum AutoVolumeMode {
        OFF,
        FIXED,
        ADAPTIVE
    }

    public interface Bridge {
        void setOriginalVideoVolume(float volume01);
        float getOriginalVideoVolume();
        boolean isOriginalVideoMuted();
        void setOriginalVideoMuted(boolean muted);
        boolean isTranslationPlaying();
        boolean isMainVideoPlaying();
    }

    public interface TickScheduler {
        void schedule(@NonNull Runnable runnable, long delayMs);
        void cancel(@NonNull Runnable runnable);
    }

    private static class DefaultScheduler implements TickScheduler {
        private Handler mHandler;

        private Handler getHandler() {
            if (mHandler == null) {
                try {
                    Looper looper = Looper.getMainLooper();
                    if (looper != null) {
                        mHandler = new Handler(looper);
                    }
                } catch (Throwable ignored) {
                    // Safe fallback in pure JVM test environments without Android runtime
                }
            }
            return mHandler;
        }

        @Override
        public void schedule(@NonNull Runnable runnable, long delayMs) {
            Handler h = getHandler();
            if (h != null) {
                h.removeCallbacks(runnable);
                h.postDelayed(runnable, delayMs);
            }
        }

        @Override
        public void cancel(@NonNull Runnable runnable) {
            Handler h = getHandler();
            if (h != null) {
                h.removeCallbacks(runnable);
            }
        }
    }

    public static final TickScheduler DEFAULT_SCHEDULER = new DefaultScheduler();

    private final Bridge mBridge;
    private final TickScheduler mScheduler;
    private final VoxSmartDuckingPolicy mPolicy;
    private final VoxVolumeLinkCoordinator mVolumeLinkCoordinator;
    @Nullable private VoxAudioLevelProcessor mLevelProcessor;

    private AutoVolumeMode mMode = AutoVolumeMode.ADAPTIVE;
    private float mFixedTarget01 = 0.15f;
    private boolean mSyncVolumeEnabled = false;

    private boolean mIsActive = false;
    private VoxSmartDuckingPolicy.State mDuckingState;
    @Nullable private Boolean mAutoVolumeMutedOnStart = null;
    private float mInitialVolumeOnStart = 1.0f;
    private VoxSmartDuckingPolicy.DuckingState mLastReportedState = VoxSmartDuckingPolicy.DuckingState.IDLE;
    private float mLastReportedBaseline = -1f;

    private final Runnable mTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsActive) return;
            performTick();
            if (mIsActive) {
                mScheduler.schedule(mTickRunnable, mPolicy.getTickMs());
            }
        }
    };

    public VoxAdaptiveVolumeController(
            @NonNull Bridge bridge,
            @NonNull TickScheduler scheduler,
            @NonNull VoxSmartDuckingPolicy policy,
            @NonNull VoxVolumeLinkCoordinator volumeLinkCoordinator
    ) {
        mBridge = bridge;
        mScheduler = scheduler;
        mPolicy = policy;
        mVolumeLinkCoordinator = volumeLinkCoordinator;
        mDuckingState = policy.initState(1.0f);
    }

    public VoxAdaptiveVolumeController(@NonNull Bridge bridge) {
        this(bridge, DEFAULT_SCHEDULER, new VoxSmartDuckingPolicy(), new VoxVolumeLinkCoordinator(0, 100));
    }

    public synchronized void setLevelProcessor(@Nullable VoxAudioLevelProcessor processor) {
        mLevelProcessor = processor;
    }

    public synchronized void setMode(AutoVolumeMode mode) {
        mMode = mode != null ? mode : AutoVolumeMode.ADAPTIVE;
    }

    public synchronized AutoVolumeMode getMode() {
        return mMode;
    }

    public synchronized void setFixedTarget(float target01) {
        mFixedTarget01 = Math.max(0f, Math.min(1f, target01));
    }

    public synchronized float getFixedTarget() {
        return mFixedTarget01;
    }

    public synchronized void setSyncVolumeEnabled(boolean enabled) {
        mSyncVolumeEnabled = enabled;
    }

    public synchronized boolean isSyncVolumeEnabled() {
        return mSyncVolumeEnabled;
    }

    @NonNull
    public synchronized VoxSmartDuckingPolicy.State getDuckingState() {
        return mDuckingState;
    }

    public synchronized boolean isActive() {
        return mIsActive;
    }

    public synchronized void start(float currentVideoVolume, boolean isMuted) {
        mInitialVolumeOnStart = Math.max(0f, Math.min(1f, currentVideoVolume));
        if (mAutoVolumeMutedOnStart == null) {
            mAutoVolumeMutedOnStart = isMuted || currentVideoVolume <= 0.001f;
        }

        mDuckingState = mPolicy.initState(mInitialVolumeOnStart);
        mLastReportedState = VoxSmartDuckingPolicy.DuckingState.IDLE;
        mLastReportedBaseline = mInitialVolumeOnStart;
        mIsActive = true;

        mScheduler.cancel(mTickRunnable);
        mScheduler.schedule(mTickRunnable, mPolicy.getTickMs());

        YandexVotLog.i(TAG, "Adaptive volume started: baseline=" + mInitialVolumeOnStart
                + " mutedOnStart=" + mAutoVolumeMutedOnStart
                + " mode=" + mMode
                + " syncVolume=" + mSyncVolumeEnabled);
    }

    public synchronized void stop() {
        if (!mIsActive && mAutoVolumeMutedOnStart == null) return;
        mIsActive = false;
        mScheduler.cancel(mTickRunnable);

        // Restore original video volume
        float baselineVol = mDuckingState.getBaseline();
        final float restoreVolume = (baselineVol <= 0.001f && mInitialVolumeOnStart > 0.001f)
                ? mInitialVolumeOnStart
                : baselineVol;

        mVolumeLinkCoordinator.withProgrammaticGuard(() -> {
            mBridge.setOriginalVideoVolume(restoreVolume);
            return null;
        });

        // Restore mute state
        if (mAutoVolumeMutedOnStart != null) {
            boolean wasMuted = mAutoVolumeMutedOnStart;
            mBridge.setOriginalVideoMuted(wasMuted);
            mAutoVolumeMutedOnStart = null;
        }

        mDuckingState = mPolicy.initState(restoreVolume);
        mLastReportedState = VoxSmartDuckingPolicy.DuckingState.IDLE;

        YandexVotLog.i(TAG, "Adaptive volume stopped: restored to " + restoreVolume);
    }

    public synchronized void onVideoChanged() {
        stop();
        mVolumeLinkCoordinator.reset();
    }

    public synchronized void onUserVideoVolumeChanged(float newVolume01) {
        if (mVolumeLinkCoordinator.isProgrammatic()) {
            return;
        }
        float clamped = Math.max(0f, Math.min(1f, newVolume01));
        mDuckingState = mPolicy.updateBaselineOnUserChange(clamped, mDuckingState);

        if (Math.abs(mDuckingState.getBaseline() - mLastReportedBaseline) > mPolicy.getExternalBaselineDelta01()) {
            mLastReportedBaseline = mDuckingState.getBaseline();
            emitStateEvent("VOT_DUCKING_BASELINE_CHANGED", "baseline=" + mLastReportedBaseline);
        }

        if (mSyncVolumeEnabled) {
            int newVideoPercent = Math.round(clamped * 100f);
            VoxVolumeLinkCoordinator.LinkResult linkResult = mVolumeLinkCoordinator.onVideoVolumeChanged(
                    newVideoPercent,
                    Math.round(mDuckingState.getBaseline() * 100f)
            );
            // LinkResult translation update handled by caller / UI slider listener
        }
    }

    public synchronized int onTranslationVolumeChanged(int newTranslationPercent, int currentVideoPercent) {
        if (!mSyncVolumeEnabled || mVolumeLinkCoordinator.isProgrammatic()) {
            return currentVideoPercent;
        }
        VoxVolumeLinkCoordinator.LinkResult linkResult = mVolumeLinkCoordinator.onTranslationVolumeChanged(
                newTranslationPercent,
                currentVideoPercent
            );
        if (linkResult.getNextVideoPercent() != null) {
            int nextVideo = linkResult.getNextVideoPercent();
            float nextVideo01 = nextVideo / 100.0f;
            mVolumeLinkCoordinator.withProgrammaticGuard(() -> {
                mBridge.setOriginalVideoVolume(nextVideo01);
                return null;
            });
            mDuckingState = mPolicy.updateBaselineOnUserChange(nextVideo01, mDuckingState);
            return nextVideo;
        }
        return currentVideoPercent;
    }

    public synchronized void performTick() {
        if (!mIsActive) return;

        if (mMode == AutoVolumeMode.OFF) {
            return;
        }

        boolean translationPlaying = mBridge.isTranslationPlaying();
        boolean hostPlaying = mBridge.isMainVideoPlaying();
        float currentVolume = mBridge.getOriginalVideoVolume();

        // If syncVolume is enabled, reference overrides adaptive ducking to classic fixed mode
        boolean effectiveSmartDucking = (mMode == AutoVolumeMode.ADAPTIVE) && !mSyncVolumeEnabled;

        Float rms = mLevelProcessor != null ? mLevelProcessor.getLatestRms(300L) : null;
        boolean hasRms = rms != null;

        VoxSmartDuckingPolicy.Input input = new VoxSmartDuckingPolicy.Input(
                System.currentTimeMillis(),
                true,
                mMode != AutoVolumeMode.OFF,
                effectiveSmartDucking,
                translationPlaying,
                hostPlaying,
                currentVolume,
                mInitialVolumeOnStart,
                mFixedTarget01,
                rms,
                hasRms
        );

        VoxSmartDuckingPolicy.Decision decision = mPolicy.evaluateStep(input, mDuckingState);

        if (decision instanceof VoxSmartDuckingPolicy.Decision.Apply) {
            VoxSmartDuckingPolicy.Decision.Apply apply = (VoxSmartDuckingPolicy.Decision.Apply) decision;
            mDuckingState = apply.getNewState();
            float targetVol = apply.getTargetVolume();
            mVolumeLinkCoordinator.withProgrammaticGuard(() -> {
                mBridge.setOriginalVideoVolume(targetVol);
                if (targetVol <= 0.001f) {
                    mBridge.setOriginalVideoMuted(true);
                } else if (mAutoVolumeMutedOnStart != null && !mAutoVolumeMutedOnStart && mBridge.isOriginalVideoMuted()) {
                    mBridge.setOriginalVideoMuted(false);
                }
                return null;
            });
            checkAndReportStateTransitions(mDuckingState.getDuckingState());
        } else if (decision instanceof VoxSmartDuckingPolicy.Decision.Noop) {
            VoxSmartDuckingPolicy.Decision.Noop noop = (VoxSmartDuckingPolicy.Decision.Noop) decision;
            mDuckingState = noop.getNewState();
            checkAndReportStateTransitions(mDuckingState.getDuckingState());
        } else if (decision instanceof VoxSmartDuckingPolicy.Decision.Stop) {
            VoxSmartDuckingPolicy.Decision.Stop stop = (VoxSmartDuckingPolicy.Decision.Stop) decision;
            mDuckingState = stop.getNewState();
            float restoreVol = stop.getRestoreVolume();
            mVolumeLinkCoordinator.withProgrammaticGuard(() -> {
                mBridge.setOriginalVideoVolume(restoreVol);
                if (mAutoVolumeMutedOnStart != null) {
                    mBridge.setOriginalVideoMuted(mAutoVolumeMutedOnStart);
                }
                return null;
            });
            checkAndReportStateTransitions(VoxSmartDuckingPolicy.DuckingState.IDLE);
        }
    }

    private void checkAndReportStateTransitions(VoxSmartDuckingPolicy.DuckingState newState) {
        if (newState == mLastReportedState) {
            return;
        }
        VoxSmartDuckingPolicy.DuckingState oldState = mLastReportedState;
        mLastReportedState = newState;

        switch (newState) {
            case ATTACK:
            case DUCKED:
                if (oldState == VoxSmartDuckingPolicy.DuckingState.IDLE || oldState == VoxSmartDuckingPolicy.DuckingState.RELEASE) {
                    emitStateEvent("VOT_DUCKING_STARTED", "state=" + newState);
                }
                break;
            case HOLD:
                emitStateEvent("VOT_DUCKING_HOLD", "holding_baseline");
                break;
            case RELEASE:
            case IDLE:
                if (oldState == VoxSmartDuckingPolicy.DuckingState.DUCKED || oldState == VoxSmartDuckingPolicy.DuckingState.HOLD) {
                    emitStateEvent("VOT_DUCKING_RELEASED", "released_to_baseline");
                }
                break;
        }
    }

    private void emitStateEvent(String eventName, String detail) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("event", eventName);
        ctx.put("detail", detail);
        VoxSafeLogger.d(VoxLogCategory.TRANSLATION, eventName, detail, ctx);
    }
}

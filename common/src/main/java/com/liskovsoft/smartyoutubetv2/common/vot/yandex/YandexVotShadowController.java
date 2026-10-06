/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Developer-only shadow player lifecycle controller for the new Yandex VOT backend.
 *
 * Integrates real SmartTube player events (play, pause, seek, speed, video change, release, destroy)
 * with {@link YandexVotOrchestrator} and {@link YandexVotPlaybackAdapter}.
 *
 * Stays completely disabled (no-op) by default so production VOX flow and UI are never affected.
 */
public class YandexVotShadowController extends BasePlayerController {
    private static final String TAG = "YANDEX_VOT_SHADOW";
    private static final long SYNC_INTERVAL_MS = 1500L;

    private static volatile YandexVotShadowController sInstance;

    private final ScheduledExecutorService mScheduler;
    private final boolean mOwnsScheduler;
    private ScheduledFuture<?> mSyncFuture;

    private boolean mEnabled = false;
    private boolean mAutoStartOnNewVideo = false;
    private YandexVotOrchestrator mOrchestrator;
    private YandexVotPlaybackAdapter mPlaybackAdapter;
    private YandexVotState mLastState;
    private Float mSavedMainVolume = null;
    private boolean mIsAudioDucked = false;
    private String mCurrentVideoId = null;
    private long mGenerationId = 0;

    private final Object mLock = new Object();

    private final YandexVotOrchestrator.Listener mOrchestratorListener = new YandexVotOrchestrator.Listener() {
        @Override
        public void onStateChanged(@NonNull YandexVotState state) {
            synchronized (mLock) {
                mLastState = state;
                YandexVotLog.i(TAG, "onStateChanged status=" + state.getStatus() + " gen=" + state.getGenerationId());
                if (mPlaybackAdapter != null) {
                    mPlaybackAdapter.onStateChanged(state);
                }
                if (state.getStatus() == YandexVotState.Status.READY) {
                    schedulePeriodicSyncLocked();
                }
            }
        }
    };

    public YandexVotShadowController() {
        this(createDefaultScheduler(), true);
    }

    public YandexVotShadowController(@NonNull ScheduledExecutorService scheduler) {
        this(scheduler, false);
    }

    private YandexVotShadowController(@NonNull ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.mScheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.mOwnsScheduler = ownsScheduler;
        sInstance = this;
    }

    private static ScheduledExecutorService createDefaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "YandexVotShadowScheduler");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public static YandexVotShadowController instance() {
        return sInstance;
    }

    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    public void setEnabled(boolean enabled) {
        synchronized (mLock) {
            mEnabled = enabled;
            YandexVotLog.i(TAG, "Shadow controller setEnabled=" + enabled);
            if (!enabled) {
                stopTranslationInternal();
            }
        }
    }

    public boolean isAutoStartOnNewVideo() {
        synchronized (mLock) {
            return mAutoStartOnNewVideo;
        }
    }

    public void setAutoStartOnNewVideo(boolean autoStart) {
        synchronized (mLock) {
            mAutoStartOnNewVideo = autoStart;
        }
    }

    @Nullable
    public YandexVotState getLastState() {
        synchronized (mLock) {
            return mLastState;
        }
    }

    @Nullable
    public YandexVotPlaybackAdapter getPlaybackAdapter() {
        synchronized (mLock) {
            return mPlaybackAdapter;
        }
    }

    @Nullable
    public YandexVotOrchestrator getOrchestrator() {
        synchronized (mLock) {
            return mOrchestrator;
        }
    }

    public void setOrchestrator(@Nullable YandexVotOrchestrator orchestrator) {
        synchronized (mLock) {
            mOrchestrator = orchestrator;
            if (mOrchestrator != null) {
                mOrchestrator.setListener(mOrchestratorListener);
            }
        }
    }

    public void setPlaybackAdapter(@Nullable YandexVotPlaybackAdapter adapter) {
        synchronized (mLock) {
            mPlaybackAdapter = adapter;
        }
    }

    @Override
    public void onInit() {
        synchronized (mLock) {
            YandexVotLog.i(TAG, "onInit");
        }
    }

    @Override
    public void onNewVideo(Video item) {
        synchronized (mLock) {
            String newVideoId = item != null ? item.videoId : null;
            YandexVotLog.i(TAG, "onNewVideo videoId=" + newVideoId);
            mCurrentVideoId = newVideoId;
            mGenerationId++;

            stopTranslationInternal();

            if (mEnabled && mAutoStartOnNewVideo && newVideoId != null) {
                startCurrentTranslationInternal(null);
            }
        }
    }

    @Override
    public void onPlay() {
        synchronized (mLock) {
            if (!mEnabled) return;
            YandexVotLog.i(TAG, "onPlay");
            if (mPlaybackAdapter != null) {
                mPlaybackAdapter.onPlay();
                schedulePeriodicSyncLocked();
            }
        }
    }

    @Override
    public void onPause() {
        synchronized (mLock) {
            if (!mEnabled) return;
            YandexVotLog.i(TAG, "onPause");
            if (mPlaybackAdapter != null) {
                mPlaybackAdapter.onPause();
                cancelPeriodicSyncLocked();
            }
        }
    }

    @Override
    public void onSeekEnd() {
        synchronized (mLock) {
            if (!mEnabled) return;
            if (getPlayer() != null && mPlaybackAdapter != null) {
                long pos = getPlayer().getPositionMs();
                YandexVotLog.i(TAG, "onSeekEnd targetMs=" + pos);
                mPlaybackAdapter.onSeek(pos);
            }
        }
    }

    @Override
    public void onSpeedChanged(float speed) {
        synchronized (mLock) {
            if (!mEnabled) return;
            YandexVotLog.i(TAG, "onSpeedChanged speed=" + speed);
            if (mPlaybackAdapter != null) {
                mPlaybackAdapter.setPlaybackSpeed(speed);
            }
        }
    }

    @Override
    public void onEngineReleased() {
        synchronized (mLock) {
            YandexVotLog.i(TAG, "onEngineReleased");
            stopTranslationInternal();
        }
    }

    @Override
    public void onFinish() {
        synchronized (mLock) {
            YandexVotLog.i(TAG, "onFinish");
            stopTranslationInternal();
        }
    }

    @Override
    public void onViewDestroyed() {
        synchronized (mLock) {
            YandexVotLog.i(TAG, "onViewDestroyed");
            stopTranslationInternal();
        }
    }

    public void startCurrentTranslation(@Nullable String oauthToken) {
        synchronized (mLock) {
            startCurrentTranslationInternal(oauthToken);
        }
    }

    private void startCurrentTranslationInternal(@Nullable String oauthToken) {
        if (VoiceTranslateController.isUserFlowActive()) {
            YandexVotLog.w(TAG, "Cannot start shadow translation: user-flow VOX is active");
            return;
        }

        String videoId = getVideo() != null && getVideo().videoId != null ? getVideo().videoId : mCurrentVideoId;
        if (videoId == null) {
            YandexVotLog.w(TAG, "Cannot start shadow translation: no active videoId");
            return;
        }

        String videoTitle = getVideo() != null ? getVideo().getTitle() : null;
        double durationSec = getPlayer() != null && getPlayer().getDurationMs() > 0
                ? (getPlayer().getDurationMs() / 1000.0) : 212.0;

        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;

        YandexVotLog.i(TAG, "startCurrentTranslation videoId=" + videoId + " duration=" + durationSec);

        ensureComponentsLocked();

        if (mOrchestrator != null) {
            mOrchestrator.cancel();
        }
        if (mPlaybackAdapter != null) {
            mPlaybackAdapter.stop();
        }

        final long gen = ++mGenerationId;

        Context ctx = getContext();
        String token = oauthToken;
        if (token == null && ctx != null) {
            try {
                token = VotData.instance(ctx).getOAuthToken();
            } catch (Throwable ignored) {
            }
        }

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                videoId,
                videoUrl,
                durationSec,
                "en",
                "ru",
                videoTitle,
                false,
                token
        );

        mOrchestrator.startTranslation(params);
    }

    public void stopTranslation() {
        synchronized (mLock) {
            stopTranslationInternal();
        }
    }

    private void stopTranslationInternal() {
        cancelPeriodicSyncLocked();
        if (mOrchestrator != null) {
            mOrchestrator.cancel();
        }
        if (mPlaybackAdapter != null) {
            mPlaybackAdapter.stop();
        }
        restoreMainAudioInternal();
    }

    private void ensureComponentsLocked() {
        Context context = getContext();
        if (mPlaybackAdapter == null && context != null) {
            mPlaybackAdapter = new YandexVotPlaybackAdapter(
                    YandexVotPlaybackAdapter.createDefaultPlayerFactory(context),
                    createDuckingBridge()
            );
        }

        if (mOrchestrator == null) {
            SmartTubeYandexVotAudioSourceProvider sourceProvider = new SmartTubeYandexVotAudioSourceProvider(
                    new SmartTubeYandexVotAudioSourceProvider.FormatInfoProvider() {
                        @Nullable
                        @Override
                        public MediaItemFormatInfo getFormatInfo(@Nullable String vid, @NonNull String vurl) throws Exception {
                            String id = vid != null ? vid : (getVideo() != null ? getVideo().videoId : mCurrentVideoId);
                            if (id == null) return null;
                            ServiceManager service = YouTubeServiceManager.instance();
                            if (service != null && service.getMediaItemService() != null) {
                                return service.getMediaItemService().getFormatInfo(id);
                            }
                            return null;
                        }
                    }
            );

            mOrchestrator = new YandexVotOrchestrator(
                    YandexVotApi.DEFAULT,
                    sourceProvider,
                    YandexVotAudioUploadTransport.DEFAULT
            );
            mOrchestrator.setListener(mOrchestratorListener);
        }
    }

    private YandexVotPlaybackAdapter.AudioDuckingBridge createDuckingBridge() {
        return new YandexVotPlaybackAdapter.AudioDuckingBridge() {
            @Override
            public void duckOriginalAudio() {
                duckMainAudioInternal();
            }

            @Override
            public void restoreOriginalAudio() {
                restoreMainAudioInternal();
            }

            @Override
            public float getTranslationVolume() {
                Context ctx = getContext();
                if (ctx != null) {
                    try {
                        return VotData.instance(ctx).getTranslationVolumeMultiplier();
                    } catch (Throwable ignored) {
                    }
                }
                return 1.0f;
            }

            @Override
            public long getCurrentVideoPositionMs() {
                return getPlayer() != null ? getPlayer().getPositionMs() : 0L;
            }

            @Override
            public boolean isMainVideoPlaying() {
                return getPlayer() != null && getPlayer().isPlaying();
            }
        };
    }

    private void duckMainAudioInternal() {
        if (!mIsAudioDucked && getPlayer() != null) {
            try {
                mSavedMainVolume = getPlayer().getVolume();
                Context ctx = getContext();
                float multiplier = 0.05f;
                if (ctx != null) {
                    try {
                        multiplier = VotData.instance(ctx).getOriginalVolumeMultiplier();
                    } catch (Throwable ignored) {
                    }
                }
                float duckedVol = mSavedMainVolume * multiplier;
                getPlayer().setVolume(duckedVol);
                mIsAudioDucked = true;
                YandexVotLog.i(TAG, "Ducked main volume to " + duckedVol + " (orig=" + mSavedMainVolume + ")");
            } catch (Throwable t) {
                YandexVotLog.w(TAG, "duckMainAudio failed: " + t.getMessage());
            }
        }
    }

    private void restoreMainAudioInternal() {
        if (mIsAudioDucked && getPlayer() != null && mSavedMainVolume != null) {
            try {
                getPlayer().setVolume(mSavedMainVolume);
                mIsAudioDucked = false;
                YandexVotLog.i(TAG, "Restored main volume to " + mSavedMainVolume);
                mSavedMainVolume = null;
            } catch (Throwable t) {
                YandexVotLog.w(TAG, "restoreMainAudio failed: " + t.getMessage());
            }
        }
    }

    private final Runnable mSyncRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                if (mEnabled && mPlaybackAdapter != null && mPlaybackAdapter.isPlaying() && getPlayer() != null) {
                    try {
                        mPlaybackAdapter.checkPeriodicSync(getPlayer().getPositionMs());
                    } catch (Throwable t) {
                        YandexVotLog.w(TAG, "periodicSync error: " + t.getMessage());
                    }
                }
            }
        }
    };

    private void schedulePeriodicSyncLocked() {
        cancelPeriodicSyncLocked();
        if (mEnabled && mPlaybackAdapter != null) {
            mSyncFuture = mScheduler.scheduleWithFixedDelay(
                    mSyncRunnable,
                    SYNC_INTERVAL_MS,
                    SYNC_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void cancelPeriodicSyncLocked() {
        if (mSyncFuture != null) {
            mSyncFuture.cancel(false);
            mSyncFuture = null;
        }
    }

    public void release() {
        synchronized (mLock) {
            stopTranslationInternal();
            if (mOwnsScheduler) {
                mScheduler.shutdownNow();
            }
        }
    }
}

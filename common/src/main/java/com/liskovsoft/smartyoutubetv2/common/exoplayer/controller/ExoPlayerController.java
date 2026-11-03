package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION;
import android.text.TextUtils;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.TrackErrorFixer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.VolumeBooster;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackInfoFormatter2;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.VideoTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.ExoUtils;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger;
import com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPendingSeekCoordinator;
import com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxMediaKind;
import com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPendingSeek;
import com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlayerTracker;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.video.VideoListener;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.R;
import java.util.ArrayList;
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxOfflinePlaybackProbe;
import com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLiveStallRecoveryController;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExoPlayerController implements Player.EventListener, VideoListener {
    private static final String TAG = ExoPlayerController.class.getSimpleName();
    private final Context mContext;
    private final ExoMediaSourceFactory mMediaSourceFactory;
    private final TrackSelectorManager mTrackSelectorManager;
    private final TrackInfoFormatter2 mTrackFormatter;
    private final TrackErrorFixer mTrackErrorFixer;
    private final VoxOfflinePlaybackProbe mOfflineProbe = new VoxOfflinePlaybackProbe();
    private final VoxLiveStallRecoveryController mStallRecoveryController;
    private MediaSource mCurrentMediaSource;
    private boolean mOnSourceChanged;
    private WeakReference<Video> mVideo;
    private final PlayerEventListener mEventListener;
    private SimpleExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private VolumeBooster mVolumeBooster;
    private boolean mIsEnded;
    private Runnable mOnVideoLoaded;
    private long mPrepareStartTimeMs = 0;
    private boolean mHasStartedPlayback = false;
    private int mRebufferCount = 0;
    private boolean mOfflineReadyLogged = false;
    private static final java.util.concurrent.atomic.AtomicInteger sSessionSeq = new java.util.concurrent.atomic.AtomicInteger(0);
    private int mPlaybackSessionId = 0;
    private boolean mLastIsDecoderError = false;
    private Integer mLastHttpStatus = null;
    private String mLastErrorClass = null;
    private com.liskovsoft.smartyoutubetv2.common.vox.playback.RebufferIncident mCurrentRebufferIncident = null;
    private String mCurrentOperationId = null;
    private long mLastDispatchedSeekPosMs = -1L;
    private long mLastDispatchedSeekTimeMs = 0L;

    private synchronized void finishRebufferIncident(String outcome) {
        if (mCurrentRebufferIncident == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long duration = Math.max(0L, now - mCurrentRebufferIncident.getStartTimeMs());
        mCurrentRebufferIncident.setEndTimeMs(now);
        mCurrentRebufferIncident.setRebufferDurationMs(duration);
        mCurrentRebufferIncident.setOutcome(outcome);

        Map<String, String> endCtx = new HashMap<>(mCurrentRebufferIncident.toMap());
        endCtx.put("rebufferDurationMs", String.valueOf(duration));
        endCtx.put("outcome", outcome);

        String msg = "Буферизация завершена (" + outcome + ")";
        if ("RECOVERED".equals(outcome)) {
            VoxSafeLogger.w(VoxLogCategory.PLAYER, VoxLogCode.REBUFFER_ENDED, "Буферизация завершена (восстановлено)", endCtx);
        } else if ("FAILED".equals(outcome)) {
            VoxSafeLogger.w(VoxLogCategory.PLAYER, VoxLogCode.REBUFFER_ENDED, "Буферизация завершена со сбоем", endCtx);
        } else {
            VoxSafeLogger.i(VoxLogCategory.PLAYER, VoxLogCode.REBUFFER_ENDED, msg, endCtx);
        }

        try {
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxUserJourneyRecorder.instance(mContext)
                    .recordPlayback("REBUFFER_ENDED", null, null, getPositionMs(), endCtx,
                            mCurrentRebufferIncident.getRootCause().name(),
                            mCurrentRebufferIncident.getOperationId(), null, outcome, duration);
        } catch (Throwable ignored) {}

        mCurrentRebufferIncident = null;
    }

    public ExoPlayerController(Context context, PlayerEventListener eventListener) {
        PlayerTweaksData playerTweaksData = PlayerTweaksData.instance(context);
        mContext = context.getApplicationContext();
        mMediaSourceFactory = new ExoMediaSourceFactory(context);
        mTrackSelectorManager = new TrackSelectorManager(context);
        mTrackFormatter = new TrackInfoFormatter2();
        mTrackFormatter.enableBitrate(PlayerTweaksData.instance(context).isQualityInfoBitrateEnabled());
        mTrackErrorFixer = new TrackErrorFixer(mTrackSelectorManager);

        mStallRecoveryController = new VoxLiveStallRecoveryController(new VoxLiveStallRecoveryController.LiveRecoveryActionHandler() {
            @Override
            public void onRefreshManifestRequested() {
                if (mPlayer != null) {
                    if (mCurrentMediaSource != null) {
                        mPlayer.prepare(mCurrentMediaSource, false, false);
                    } else {
                        mPlayer.retry();
                    }
                }
            }

            @Override
            public void onReseekRequested(long targetPositionMs) {
                if (mPlayer != null) {
                    mPlayer.seekTo(targetPositionMs);
                }
                mEventListener.onSeekPositionChanged(targetPositionMs);
            }

            @Override
            public void onRecreateSourceRequested() {
                mEventListener.onEngineError(PlayerEventListener.ERROR_TYPE_SOURCE, PlayerEventListener.RENDERER_INDEX_UNKNOWN, new java.io.IOException("Live stall timeout recovery"));
            }

            @Override
            public void onNetworkFallbackRequested(int nextEngine) {
                PlayerTweaksData.instance(mContext).setPlayerDataSource(nextEngine);
                mEventListener.onEngineError(PlayerEventListener.ERROR_TYPE_SOURCE, PlayerEventListener.RENDERER_INDEX_UNKNOWN, new java.io.IOException("Live network fallback to engine " + nextEngine));
            }

            @Override
            public void onReturnToLiveRequested() {
                if (mPlayer != null) {
                    mPlayer.seekToDefaultPosition();
                }
                long defaultPos = mPlayer != null ? mPlayer.getCurrentPosition() : 0L;
                mEventListener.onSeekPositionChanged(defaultPos);
            }

            @Override
            public void onPositionClamped(long clampedPositionMs) {
                mEventListener.onSeekPositionChanged(clampedPositionMs);
            }

            @Override
            public void showMessage(String message) {
                com.liskovsoft.sharedutils.helpers.MessageHelpers.showMessage(mContext, message);
            }

            @Override
            public void hideBufferingSpinner() {
                PlaybackPresenter presenter = PlaybackPresenter.instance(mContext);
                if (presenter != null && presenter.getPlayer() != null) {
                    presenter.getPlayer().showProgressBar(false);
                }
            }

            @Override
            public void onRecoveryExhausted(long targetPositionMs, Runnable onReturnToLive, Runnable onRetry) {
                PlaybackPresenter presenter = PlaybackPresenter.instance(mContext);
                if (presenter != null && presenter.getPlayer() != null) {
                    presenter.getPlayer().showProgressBar(false);
                    presenter.getPlayer().setTitle(mContext.getString(R.string.vox_live_recovery_failed));
                }
                if (mPlayer != null) {
                    mPlayer.setPlayWhenReady(false);
                }
                AppDialogPresenter dialog = AppDialogPresenter.instance(mContext);
                if (dialog != null) {
                    List<OptionItem> options = new ArrayList<>();
                    options.add(UiOptionItem.from(mContext.getString(R.string.vox_live_action_return_to_live), item -> {
                        dialog.closeDialog();
                        if (presenter != null && presenter.getPlayer() != null) {
                            presenter.getPlayer().showProgressBar(true);
                        }
                        if (mPlayer != null) {
                            mPlayer.setPlayWhenReady(true);
                        }
                        onReturnToLive.run();
                    }));
                    options.add(UiOptionItem.from(mContext.getString(R.string.vox_player_recovery_retry), item -> {
                        dialog.closeDialog();
                        if (presenter != null && presenter.getPlayer() != null) {
                            presenter.getPlayer().showProgressBar(true);
                        }
                        if (mPlayer != null) {
                            mPlayer.setPlayWhenReady(true);
                        }
                        onRetry.run();
                    }));
                    options.add(UiOptionItem.from(mContext.getString(R.string.vox_player_recovery_back), item -> {
                        dialog.closeDialog();
                        if (presenter != null && presenter.getPlayer() != null) {
                            presenter.getPlayer().finish();
                        }
                    }));
                    String title = mContext.getString(R.string.vox_live_recovery_failed);
                    dialog.appendStringsCategory(title, options);
                    dialog.showDialog(title);
                }
            }
        });

        mMediaSourceFactory.setTrackErrorFixer(mTrackErrorFixer);
        mEventListener = eventListener;

        applyShield720pFix();
        VideoTrack.sIsAltPresetsEnabled = playerTweaksData.isAltPresetsEnabled();
        com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager voxCompat =
                com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager.instance(mContext);
        if (voxCompat != null && voxCompat.getCodecPolicy() != null &&
                voxCompat.getCodecPolicy().getPreferredVideoCodec() != com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxVideoCodecPreference.AUTO) {
            MediaTrack.updateCodecWeights(voxCompat.getCodecPolicy().getPreferredVideoCodec().getId());
        } else {
            MediaTrack.setAvcOverVp9Preferred(playerTweaksData.isAvcOverVp9Preferred());
        }
    }

    private void applyShield720pFix() {
        PlayerData playerData = PlayerData.instance(mContext);
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_VIDEO)));
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_AUDIO)));
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_SUBTITLE)));
    }

    public void openSabr(MediaItemFormatInfo formatInfo) {
        MediaSource mediaSource = mMediaSourceFactory.fromSabrFormatInfo(formatInfo);
        openMediaSource(mediaSource);
    }

    public void openDash(MediaItemFormatInfo formatInfo) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        openMediaSource(mediaSource);
    }

    public void openDash(InputStream dashManifest) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashManifest(dashManifest);
        openMediaSource(mediaSource);
    }

    public void openDashUrl(String dashManifestUrl) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashManifestUrl(dashManifestUrl);
        openMediaSource(mediaSource);
    }

    public void openHlsUrl(String hlsPlaylistUrl) {
        MediaSource mediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(mediaSource);
    }

    public void openUrlList(List<String> urlList) {
        MediaSource mediaSource = mMediaSourceFactory.fromUrlList(urlList);
        openMediaSource(mediaSource);
    }

    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        MediaSource dashMediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        MediaSource hlsMediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(new MergingMediaSource(dashMediaSource, hlsMediaSource));
    }

    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        MediaSource dashMediaSource = mMediaSourceFactory.fromDashManifest(dashManifest);
        MediaSource hlsMediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(new MergingMediaSource(dashMediaSource, hlsMediaSource));
    }

    private void openMediaSource(MediaSource mediaSource) {
        finishRebufferIncident("SOURCE_REPLACED");
        mCurrentMediaSource = mediaSource;
        resetPlayerState(); // fixes occasional video artifacts and problems with quality switching
        setQualityInfo("");

        mTrackSelectorManager.setMergedSource(mediaSource instanceof MergingMediaSource);
        boolean isLocalTranslated = getVideo() != null && getVideo().isLocal && getVideo().isDownloadedTranslated();
        mTrackSelectorManager.setPreferTranslatedLocalAudio(isLocalTranslated);
        mTrackSelectorManager.invalidate();
        mOnSourceChanged = true;
        mPrepareStartTimeMs = System.currentTimeMillis();
        mHasStartedPlayback = false;
        mOfflineReadyLogged = false;
        mRebufferCount = 0;
        mPlaybackSessionId = sSessionSeq.incrementAndGet();
        mCurrentOperationId = "sess_" + mPlaybackSessionId + "_" + mPrepareStartTimeMs;
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.onUserOpen(mPlaybackSessionId, "EXOPLAYER", mPrepareStartTimeMs);
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.startStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_REQUEST, mPrepareStartTimeMs);
        try {
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxUserJourneyRecorder.instance(mContext)
                    .recordPlayback("PLAYBACK_START", null, null, 0L, null, null, mCurrentOperationId, null, "STARTING", null);
        } catch (Throwable ignored) {}
        boolean isLive = getVideo() != null && getVideo().isLive;
        mStallRecoveryController.onPlaybackStart(isLive);
        String marker = getVideo() != null && getVideo().videoId != null
                ? "live_" + Integer.toHexString(getVideo().videoId.hashCode())
                : "stream_unknown";
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor.INSTANCE.onPlaybackStart(marker, isLive);
        mEventListener.onSourceChanged(getVideo());
        mPlayer.prepare(mediaSource);
        if (com.liskovsoft.smartyoutubetv2.common.vox.shorts.VoxShortsAutoNextController.getINSTANCE().isShort(getVideo())) {
            com.liskovsoft.smartyoutubetv2.common.vox.shorts.VoxShortsAutoNextController.getINSTANCE().onPlayerPrepared(getVideo(), 0);
        }
    }

    public long getBufferedPositionMs() {
        return mPlayer != null ? mPlayer.getBufferedPosition() : 0L;
    }

    public long getPositionMs() {
        if (mPlayer == null) {
            return -1;
        }

        return mPlayer.getCurrentPosition();
    }

    /**
     * NOTE: Pos gathered from content block data may slightly exceed video duration
     * (e.g. 302200 when duration is 302000).
     */
    public void setPositionMs(long positionMs) {
        if (mPlayer == null || positionMs < 0) {
            return;
        }
        if (mCurrentRebufferIncident != null) {
            finishRebufferIncident("ABORTED_BY_SEEK");
        }
        boolean isLive = isLiveOrDynamic();
        if (isLive && getVideo() != null && !getVideo().isLive) {
            getVideo().isLive = true;
        }
        long duration = getDurationMs();
        if (duration <= 0 && getVideo() != null && getVideo().getDurationMs() > 0) {
            duration = getVideo().getDurationMs();
        }
        long windowStartMs = 0L;
        if (isLive) {
            if (mPlayer != null) {
                Timeline timeline = mPlayer.getCurrentTimeline();
                if (timeline != null && !timeline.isEmpty()) {
                    Timeline.Window window = new Timeline.Window();
                    timeline.getWindow(mPlayer.getCurrentWindowIndex(), window);
                    if (window.isDynamic) {
                        if (window.durationUs != C.TIME_UNSET) {
                            duration = C.usToMs(window.durationUs);
                        }
                    }
                }
            }
            positionMs = mStallRecoveryController.onSeekRequested(positionMs, duration, windowStartMs, true);
        }

        long fromPos = getPositionMs();
        VoxMediaKind mediaKind = isLive ? VoxMediaKind.LIVE_DVR : VoxMediaKind.STANDARD_VOD;
        Video video = getVideo();
        String videoId = video != null ? video.videoId : null;
        VoxPendingSeekCoordinator.instance().startSeek(positionMs, fromPos, mediaKind, windowStartMs, duration, videoId);
        mEventListener.onSeekPositionChanged(positionMs);

        if (video != null) {
            video.pendingPosMs = positionMs;
        }

        if (duration <= 0 || positionMs <= duration) {
            long now = System.currentTimeMillis();
            boolean isDuplicateRapidSeek = (mLastDispatchedSeekPosMs == positionMs) && (now - mLastDispatchedSeekTimeMs < 200L);
            if (!isDuplicateRapidSeek) {
                mLastDispatchedSeekPosMs = positionMs;
                mLastDispatchedSeekTimeMs = now;
                mOfflineProbe.onSeekRequested(fromPos, positionMs);
                mPlayer.seekTo(positionMs);
                mOfflineProbe.onSeekCompleted(positionMs);
            }
        }
    }

    public boolean isLiveOrDynamic() {
        if (getVideo() != null && getVideo().isLive) {
            return true;
        }
        if (mPlayer != null) {
            if (mPlayer.isCurrentWindowDynamic()) {
                return true;
            }
            Timeline timeline = mPlayer.getCurrentTimeline();
            if (timeline != null && !timeline.isEmpty()) {
                Timeline.Window window = new Timeline.Window();
                timeline.getWindow(mPlayer.getCurrentWindowIndex(), window);
                if (window.isDynamic) {
                    return true;
                }
            }
        }
        return false;
    }

    public long getDurationMs() {
        if (mPlayer == null) {
            return -1;
        }

        long duration = mPlayer.getDuration();
        return duration != C.TIME_UNSET ? duration : -1;
    }

    public void setPlayWhenReady(boolean play) {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(play);
        }
    }

    public boolean getPlayWhenReady() {
        if (mPlayer == null) {
            return false;
        }

        return mPlayer.getPlayWhenReady();
    }

    public boolean isPlaying() {
        return ExoUtils.isPlaying(mPlayer);
    }

    public boolean isLoading() {
        return ExoUtils.isLoading(mPlayer);
    }

    public boolean isBuffering() {
        return ExoUtils.isBuffering(mPlayer);
    }

    public boolean containsMedia() {
        if (mPlayer == null) {
            return false;
        }

        return mPlayer.getPlaybackState() != Player.STATE_IDLE;
    }

    public void release() {
        mStallRecoveryController.reset();
        mTrackSelectorManager.release();
        mMediaSourceFactory.release();
        releasePlayer();
        mPlayerView = null;
        // Don't destroy it (needed inside the bridge)!
        //mEventListener = null;
    }

    public void setPlayer(SimpleExoPlayer player) {
        if (mPlayer != null && mPlayer != player) {
            releasePlayer();
        }
        mPlayer = player;
        if (player != null) {
            VoxPlayerTracker.onPlayerCreated();
            player.addListener(this);
            player.addVideoListener(this);
        }
    }

    //@Override
    //public void setEventListener(PlayerEventListener eventListener) {
    //    mEventListener = eventListener;
    //}

    public void setPlayerView(PlayerView playerView) {
        mPlayerView = playerView;
    }

    public void setTrackSelector(DefaultTrackSelector trackSelector) {
        mTrackSelectorManager.setTrackSelector(trackSelector);

        if (mContext != null && trackSelector != null && PlayerTweaksData.instance(mContext).isTunneledPlaybackEnabled()) {
            // Enable tunneling if supported by the current media and device configuration.
            if (VERSION.SDK_INT >= 21) {
                trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingAudioSessionId(C.generateAudioSessionIdV21(mContext)));
            }
        }
    }

    public void setVideoTrackEnabled(boolean enabled) {
        if (mTrackSelectorManager != null) {
            mTrackSelectorManager.setVideoTrackEnabled(enabled);
        }
    }

    public void setVideo(Video video) {
        Video old = getVideo();
        if (old != null && video != null && !TextUtils.equals(old.videoId, video.videoId)) {
            VoxPendingSeekCoordinator.instance().cancel("Video changed");
        }
        mVideo = new WeakReference<>(video);
        if (video != null) {
            mOfflineProbe.onBeforeOpen(video, video.mediaUrl);
        }
    }

    public Video getVideo() {
        return mVideo != null ? mVideo.get() : null;
    }

    public VoxOfflinePlaybackProbe getOfflineProbe() {
        return mOfflineProbe;
    }

    public List<FormatItem> getVideoFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getVideoTracks());
    }

    public List<FormatItem> getAudioFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getAudioTracks());
    }

    public List<FormatItem> getSubtitleFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getSubtitleTracks());
    }

    public void selectFormat(FormatItem formatItem) {
        if (formatItem != null && formatItem.getTrack() != null) {
            if (formatItem.getTrack().rendererIndex == TrackSelectorManager.RENDERER_INDEX_AUDIO) {
                mTrackSelectorManager.setPreferTranslatedLocalAudio(false);
            }
            FormatItem selectedFormatItem = getSelectedFormat(formatItem.getTrack().rendererIndex);
            if (!formatItem.equals(selectedFormatItem)) {
                mTrackSelectorManager.selectTrack(formatItem.getTrack());
                mEventListener.onTrackSelected(formatItem);
            }
        }
    }

    public static FormatItem findTranslatedAudioFormat(List<FormatItem> aFormats) {
        if (aFormats == null || aFormats.isEmpty()) {
            return null;
        }
        int totalAudioTracks = aFormats.size();
        FormatItem defaultFlagFallback = null;
        FormatItem secondTrackMpegFallback = null;
        int trackIndex = 0;
        for (FormatItem item : aFormats) {
            if (item == null || item.getTrack() == null) {
                trackIndex++;
                continue;
            }
            String lang = item.getLanguage() != null ? item.getLanguage().toLowerCase() : "";
            if (lang.startsWith("ru") || lang.contains("rus") || lang.contains("рус")) {
                return item;
            }
            if (item.getTrack() != null && item.getTrack().format != null) {
                com.google.android.exoplayer2.Format format = item.getTrack().format;
                String fLang = format.language != null ? format.language.toLowerCase() : "";
                if (fLang.startsWith("ru") || fLang.contains("rus") || fLang.contains("рус")) {
                    return item;
                }
                String label = format.label != null ? format.label.toLowerCase() : "";
                if (label.contains("translation") || label.contains("vox_translated") || label.contains("перевод")) {
                    return item;
                }
                if (totalAudioTracks >= 2) {
                    if (defaultFlagFallback == null && (format.selectionFlags & com.google.android.exoplayer2.C.SELECTION_FLAG_DEFAULT) != 0) {
                        defaultFlagFallback = item;
                    }
                    String mime = format.sampleMimeType;
                    if (secondTrackMpegFallback == null && trackIndex >= 1 && ("audio/mpeg".equals(mime) || "audio/mp3".equals(mime))) {
                        secondTrackMpegFallback = item;
                    }
                }
            }
            trackIndex++;
        }
        if (defaultFlagFallback != null) {
            return defaultFlagFallback;
        }
        return secondTrackMpegFallback;
    }

    public FormatItem getVideoFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_VIDEO);
    }

    public FormatItem getAudioFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_AUDIO);
    }

    public FormatItem getSubtitleFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_SUBTITLE);
    }

    private FormatItem getSelectedFormat(int rendererIndex) {
        return ExoFormatItem.from(mTrackSelectorManager.getSelectedTrack(rendererIndex));
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        Log.d(TAG, "onTracksChanged: start: groups length: " + trackGroups.length);

        if (trackGroups.length == 0) {
            Log.i(TAG, "onTracksChanged: Hmm. Strange. Received empty groups, no selections. Why is this happens only on next/prev videos?");
            return;
        }

        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.finishStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_TRACK_SELECT, "OK");
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.startStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_DECODER_INIT);

        if (getVideo() != null && getVideo().isLocal) {
            List<FormatItem> vFormats = getVideoFormats();
            List<FormatItem> aFormats = getAudioFormats();
            String vMime = (vFormats != null && !vFormats.isEmpty() && vFormats.get(0).getTrack() != null && vFormats.get(0).getTrack().format != null)
                    ? vFormats.get(0).getTrack().format.sampleMimeType : null;
            FormatItem selectedAudio = getAudioFormat();
            boolean expectedTrans = getVideo().isDownloadedTranslated();
            boolean hasTrans = false;
            boolean isTransSelected = false;
            if (expectedTrans) {
                FormatItem translatedFormat = findTranslatedAudioFormat(aFormats);
                if (translatedFormat != null) {
                    hasTrans = true;
                    if (mOnSourceChanged && !translatedFormat.equals(selectedAudio)) {
                        mTrackSelectorManager.selectTrack(translatedFormat.getTrack());
                        selectedAudio = translatedFormat;
                    }
                    isTransSelected = translatedFormat.equals(selectedAudio) || mOnSourceChanged;
                } else {
                    if (mOnSourceChanged) {
                        java.util.Map<String, String> warnPayload = new java.util.LinkedHashMap<>();
                        warnPayload.put("videoId", getVideo().videoId != null ? getVideo().videoId : "unknown");
                        warnPayload.put("audioTracksCount", String.valueOf(aFormats != null ? aFormats.size() : 0));
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.w(
                                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.PLAYER,
                                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.LOCAL_TRANSLATED_AUDIO_MISSING,
                                "Local video marked as translated but no Russian/translated audio track found; falling back to original audio",
                                warnPayload
                        );
                        if (mContext != null && getVideo().videoId != null) {
                            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(mContext)
                                    .selfHealMissingTranslatedAudio(getVideo().videoId);
                        }
                        getVideo().translationState = "COMPLETED_UNTRANSLATED";
                        if (mContext != null) {
                            com.liskovsoft.sharedutils.helpers.MessageHelpers.showMessage(mContext, "Файл перевода не найден, воспроизводится оригинал");
                        }
                    }
                }
            }
            String aMime = (selectedAudio != null && selectedAudio.getTrack() != null && selectedAudio.getTrack().format != null)
                    ? selectedAudio.getTrack().format.sampleMimeType
                    : ((aFormats != null && !aFormats.isEmpty() && aFormats.get(0).getTrack() != null && aFormats.get(0).getTrack().format != null)
                            ? aFormats.get(0).getTrack().format.sampleMimeType : null);
            mOfflineProbe.onTracksDiscovered(
                    vFormats != null ? vFormats.size() : 0,
                    aFormats != null ? aFormats.size() : 0,
                    vMime,
                    aMime,
                    hasTrans,
                    isTransSelected
            );
        }

        notifyOnVideoLoad();

        for (TrackSelection selection : trackSelections.getAll()) {
            if (selection != null) {
                // EXO: 2.12.1
                //Format format = selection.getSelectedFormat();

                // EXO: 2.13.1
                Format format = selection.getFormat(0);

                mEventListener.onTrackChanged(ExoFormatItem.from(format));

                mTrackFormatter.setFormat(format);
            }
        }

        setQualityInfo(mTrackFormatter.getQualityLabel());

        boolean isLiveTracks = (getVideo() != null && getVideo().isLive) || (mPlayer != null && mPlayer.isCurrentWindowDynamic());
        if (isLiveTracks) {
            FormatItem vFormat = getVideoFormat();
            int h = vFormat != null ? vFormat.getHeight() : 0;
            String codec = (vFormat != null && vFormat.getTrack() != null && vFormat.getTrack().format != null && vFormat.getTrack().format.sampleMimeType != null)
                    ? vFormat.getTrack().format.sampleMimeType : "auto";
            long pos = getPositionMs();
            long dur = getDurationMs();
            long buffered = mPlayer != null ? Math.max(0, mPlayer.getBufferedPosition() - pos) : 0;
            long offset = dur > pos ? dur - pos : 0;
            com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor.INSTANCE.onBufferHealthTick(
                    buffered, offset, 0, 0, h, codec);
        }

        // Manage audio focus. E.g. use Spotify when audio is disabled. (NOT NEEDED!!!)
        //MediaTrack audioTrack = mTrackSelectorManager.getAudioTrack();
        //ExoPlayerInitializer.enableAudioFocus(mPlayer, audioTrack != null && !audioTrack.isEmpty());
    }

    private void notifyOnVideoLoad() {
        if (mOnSourceChanged) {
            mOnSourceChanged = false;

            mEventListener.onVideoLoaded(getVideo());

            if (mOnVideoLoaded != null) {
                mOnVideoLoaded.run();
            }

            // Produce thread sync problems
            // Attempt to read from field 'java.util.TreeMap$Node java.util.TreeMap$Node.left' on a null object reference
            //mTrackSelectorManager.fixTracksSelection();
        }
    }

    public String getSourceType() {
        if (getVideo() != null && getVideo().isLocal) {
            String url = getVideo().mediaUrl;
            if (url != null) {
                if (url.startsWith("content://")) return "CONTENT_URI";
                if (url.startsWith("file://") || url.startsWith("/")) return "LOCAL_FILE";
            }
            return "LOCAL_FILE";
        }
        if (getVideo() != null && getVideo().mediaUrl != null) {
            String url = getVideo().mediaUrl;
            if (url.startsWith("content://")) return "CONTENT_URI";
            if (url.startsWith("file://") || url.startsWith("/")) return "LOCAL_FILE";
            if (url.startsWith("http://") || url.startsWith("https://")) return "HTTP";
        }
        return "HTTP";
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        Throwable nested = error.getCause() != null ? error.getCause() : error;
        while (nested.getCause() != null && nested.getCause() != nested) {
            if (nested instanceof com.google.android.exoplayer2.upstream.HttpDataSource.HttpDataSourceException) {
                break;
            }
            nested = nested.getCause();
        }

        // Keep diagnostics useful without leaking query tokens into logcat.
        Log.e(TAG, "onPlayerError: type=%s, renderer=%s, cause=%s",
                error.type, error.rendererIndex, nested.getClass().getSimpleName());

        String sourceType = getSourceType();
        boolean isLocalSource = "LOCAL_FILE".equals(sourceType) || "CONTENT_URI".equals(sourceType);

        Map<String, String> errorCtx = new HashMap<>();
        errorCtx.put("errorType", String.valueOf(error.type));
        errorCtx.put("rendererIndex", String.valueOf(error.rendererIndex));
        errorCtx.put("causeClass", nested.getClass().getSimpleName());
        errorCtx.put("sourceType", sourceType);
        errorCtx.put("isDownloadedItem", String.valueOf(getVideo() != null && getVideo().isLocal));

        if (nested instanceof com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) {
            int httpCode = ((com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) nested).responseCode;
            mLastHttpStatus = httpCode;
            errorCtx.put("responseCode", String.valueOf(httpCode));
        } else {
            mLastHttpStatus = null;
        }
        mLastErrorClass = nested.getClass().getSimpleName();

        finishRebufferIncident("FAILED");

        Throwable cause = error;
        boolean isOom = false;
        while (cause != null) {
            if (cause instanceof OutOfMemoryError || (cause.getMessage() != null && cause.getMessage().contains("OutOfMemoryError"))) {
                isOom = true;
                break;
            }
            cause = cause.getCause();
        }

        String logCode = VoxLogCode.PLAYER_RENDERER_ERROR;
        if (isOom) {
            logCode = VoxLogCode.PLAYER_OUT_OF_MEMORY;
            collectSafeMemoryTelemetry(errorCtx);
        } else if (error.type == ExoPlaybackException.TYPE_RENDERER) {
            logCode = VoxLogCode.PLAYER_DECODER_ERROR;
        } else if (error.type == ExoPlaybackException.TYPE_SOURCE) {
            logCode = com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlayerRecoveryPolicy.classifyPlayerSourceError(nested, isLocalSource);
            String netFailureType = com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlayerRecoveryPolicy.classifyNetworkFailureType(nested);
            errorCtx.put("networkFailureType", netFailureType);
            if (com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlayerRecoveryPolicy.isDnsFailure(nested)) {
                errorCtx.put("dnsResolutionFailed", "true");
            }
            if (com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlayerRecoveryPolicy.isConnectTimeout(nested) ||
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlayerRecoveryPolicy.isReadTimeout(nested)) {
                errorCtx.put("connectionTimeout", "true");
            }
            long bufferAhead = Math.max(0L, mPlayer != null ? mPlayer.getBufferedPosition() - mPlayer.getCurrentPosition() : 0L);
            errorCtx.put("bufferAheadMs", String.valueOf(bufferAhead));
        }
        boolean isDecoderErr = (error.type == ExoPlaybackException.TYPE_RENDERER) || (nested instanceof android.media.MediaCodec.CodecException) || (nested.getClass().getName().contains("MediaCodec"));
        mLastIsDecoderError = isDecoderErr;
        if (isDecoderErr) {
            errorCtx.put("isDecoderError", "true");
            errorCtx.put("rootCause", com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlaybackBufferPolicy.classifyRebuffer(0L, 0L, 0L, 0, false, 0, true).name());
        }
        VoxSafeLogger.e(VoxLogCategory.PLAYER, logCode, "Сбой воспроизведения ExoPlayer", errorCtx, null);

        Throwable curr = error;
        boolean isBehindLiveWindow = false;
        while (curr != null) {
            if (curr instanceof com.google.android.exoplayer2.source.BehindLiveWindowException) {
                isBehindLiveWindow = true;
                break;
            }
            curr = curr.getCause();
        }

        if (isBehindLiveWindow) {
            long windowStartMs = 0L;
            long windowDurationMs = getDurationMs();
            if (mPlayer != null) {
                Timeline timeline = mPlayer.getCurrentTimeline();
                if (timeline != null && !timeline.isEmpty()) {
                    Timeline.Window window = new Timeline.Window();
                    timeline.getWindow(mPlayer.getCurrentWindowIndex(), window);
                    if (window.durationUs != C.TIME_UNSET) {
                        windowDurationMs = C.usToMs(window.durationUs);
                    }
                }
            }
            mStallRecoveryController.handleBehindLiveWindow(mContext, windowStartMs, windowDurationMs, (targetPos) -> {
                if (mPlayer != null) {
                    mPlayer.seekTo(targetPos);
                    if (mCurrentMediaSource != null) {
                        mPlayer.prepare(mCurrentMediaSource, false, false);
                    } else {
                        mPlayer.retry();
                    }
                }
            }, () -> {
                if (mPlayer != null) {
                    mPlayer.seekToDefaultPosition();
                    if (mCurrentMediaSource != null) {
                        mPlayer.prepare(mCurrentMediaSource, false, false);
                    } else {
                        mPlayer.retry();
                    }
                }
            });
            return;
        }

        if (isLiveOrDynamic()) {
            int httpCode = 0;
            if (nested instanceof com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) {
                httpCode = ((com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) nested).responseCode;
            }
            com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor.INSTANCE.onSegmentError(httpCode, nested.getClass().getSimpleName());

            int currentEngine = PlayerTweaksData.instance(mContext).getPlayerDataSource();
            int[] availableEngines = com.liskovsoft.smartyoutubetv2.common.utils.Utils.skipCronet()
                    ? new int[] { PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP }
                    : new int[] { PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP };
            if (mStallRecoveryController.handleNetworkTransportError(nested, currentEngine, availableEngines)) {
                return;
            }
        }

        // NOTE: Player is released at this point. So, there is no sense to restore the playback here.

        mEventListener.onEngineError(error.type, error.rendererIndex, nested);
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onPlayerStateChanged: " + TrackSelectorUtil.stateToString(playbackState));
        }

        boolean isPlayPressed = Player.STATE_READY == playbackState && playWhenReady;
        boolean isPausePressed = Player.STATE_READY == playbackState && !playWhenReady;
        boolean isPlaybackEnded = Player.STATE_ENDED == playbackState && playWhenReady;
        boolean isBuffering = Player.STATE_BUFFERING == playbackState && playWhenReady;

        // Fix chapters (seek and play) after playback ends
        if (isPlaybackEnded && mIsEnded) {
            return;
        }

        boolean isLive = isLiveOrDynamic();

        if (isBuffering) {
            VoxPendingSeek active = VoxPendingSeekCoordinator.instance().getActiveSeek();
            if (active != null) {
                VoxPendingSeekCoordinator.instance().onBuffering(active.getGeneration());
            }
        }

        if (isPlayPressed) {
            mStallRecoveryController.onBufferingEnded();
            if (mPlayer != null) {
                VoxPendingSeek active = VoxPendingSeekCoordinator.instance().getActiveSeek();
                if (active != null) {
                    VoxPendingSeekCoordinator.instance().onPlaybackProgress(mPlayer.getCurrentPosition(), active.getGeneration());
                }
            }
            if (isLive) {
                long pos = getPositionMs();
                long dur = getDurationMs();
                long buffered = mPlayer != null ? Math.max(0, mPlayer.getBufferedPosition() - pos) : 0;
                long offset = dur > pos ? dur - pos : 0;
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor.INSTANCE.onReady(buffered, offset, 0);
            }
            if (!mHasStartedPlayback) {
                mHasStartedPlayback = true;
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.finishStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_BUFFER_FILL, "OK");
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.recordStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_FIRST_FRAME, 0L, "OK");
                if (mPrepareStartTimeMs > 0) {
                    long startupLatency = System.currentTimeMillis() - mPrepareStartTimeMs;
                    long thresholdMs = isLive ? 6000L : 3500L;
                    if (startupLatency > thresholdMs) {
                        Map<String, String> ctx = new HashMap<>();
                        ctx.put("latencyMs", String.valueOf((startupLatency / 100) * 100));
                        ctx.put("startupLatencyMs", String.valueOf(startupLatency));
                        String srcType = getSourceType();
                        ctx.put("sourceType", srcType);
                        boolean isLocalSrc = "LOCAL_FILE".equals(srcType) || "CONTENT_URI".equals(srcType);
                        String codecStr = "unknown";
                        FormatItem vf = getVideoFormat();
                        if (vf != null) {
                            ctx.put("selectedHeight", String.valueOf(vf.getHeight()));
                            MediaTrack tr = vf.getTrack();
                            if (tr != null && tr.format != null && tr.format.codecs != null) {
                                codecStr = tr.format.codecs;
                                ctx.put("selectedCodec", codecStr);
                            }
                        }
                        ctx.put("codec", codecStr);
                        long pos = getPositionMs();
                        long buffered = mPlayer != null ? Math.max(0, mPlayer.getBufferedPosition() - pos) : 0;
                        ctx.put("initialBufferMs", String.valueOf(buffered));
                        ctx.put("isLive", String.valueOf(isLive));
                        String networkConfidence = isLocalSrc ? "0.0" : (buffered < 1000 ? "0.85" : "0.4");
                        ctx.put("networkLimitedConfidence", networkConfidence);
                        String rootCause = com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.classifyStartupRootCause();
                        if ("UNKNOWN".equals(rootCause) && buffered < 1000 && !isLocalSrc) {
                            rootCause = "NETWORK";
                        }
                        ctx.put("rootCause", rootCause);
                        VoxSafeLogger.w(VoxLogCategory.PLAYER, VoxLogCode.PLAYER_STARTUP_SLOW, "Замедленный старт воспроизведения", ctx);
                    }
                }
            }
            if (getVideo() != null && getVideo().isLocal && !mOfflineReadyLogged) {
                mOfflineReadyLogged = true;
                VoxSafeLogger.info(
                        VoxLogCategory.PLAYER,
                        VoxLogCode.OFFLINE_VIDEO_TRACK_READY,
                        "Offline video track ready (local MKV)"
                );
                VoxSafeLogger.info(
                        VoxLogCategory.PLAYER,
                        VoxLogCode.OFFLINE_TIMELINE_READY,
                        "Offline timeline ready (durationMs=" + getDurationMs() + ")"
                );
            }
            mEventListener.onPlay();
            mLastIsDecoderError = false;

            finishRebufferIncident("RECOVERED");
            if (com.liskovsoft.smartyoutubetv2.common.vox.shorts.VoxShortsAutoNextController.getINSTANCE().isShort(getVideo())) {
                com.liskovsoft.smartyoutubetv2.common.vox.shorts.VoxShortsAutoNextController.getINSTANCE().onFirstFrame(getVideo(), 0);
                com.liskovsoft.smartyoutubetv2.common.vox.shorts.VoxShortsAutoNextController.getINSTANCE().onAudioStart(getVideo(), 0);
            }
        } else if (isPausePressed) {
            finishRebufferIncident("STOPPED_BY_USER");
            mStallRecoveryController.onBufferingEnded();
            mEventListener.onPause();
        } else if (isPlaybackEnded) {
            finishRebufferIncident("STOPPED_BY_USER");
            mEventListener.onPlayEnd();
            mIsEnded = true;
        } else if (isBuffering) {
            if (!mHasStartedPlayback) {
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.finishStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_DECODER_INIT, "OK");
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.startStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_BUFFER_FILL);
            }
            if (isLive) {
                long pos = getPositionMs();
                long dur = getDurationMs();
                long buffered = mPlayer != null ? Math.max(0, mPlayer.getBufferedPosition() - pos) : 0;
                long offset = dur > pos ? dur - pos : 0;
                float speed = getSpeed();
                com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor.INSTANCE.onBufferingStarted(
                        buffered, offset, 0, 0, speed > 0 ? speed : 1.0f);
                mStallRecoveryController.onBufferingStarted(true, pos, dur, offset);
            }
            if (mHasStartedPlayback && mCurrentRebufferIncident == null) {
                mRebufferCount++;
                long pos = getPositionMs();
                long buffered = mPlayer != null ? Math.max(0, mPlayer.getBufferedPosition() - pos) : 0;
                long bitrate = 0;
                int height = 0;
                String codec = null;
                FormatItem vf = getVideoFormat();
                if (vf != null) {
                    height = vf.getHeight();
                    MediaTrack tr = vf.getTrack();
                    if (tr != null && tr.format != null) {
                        codec = tr.format.codecs;
                        if (tr.format.bitrate > 0) {
                            bitrate = tr.format.bitrate;
                        }
                    }
                }

                // Query real throughput from ExoMediaSourceFactory.getBandwidthMeter()
                long throughput = 0L;
                try {
                    throughput = com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory.getBandwidthMeter().getBitrateEstimate();
                } catch (Throwable ignored) {}

                // Query real dropped frames from video decoder
                int droppedFrames = 0;
                try {
                    if (mPlayer != null && mPlayer.getVideoDecoderCounters() != null) {
                        mPlayer.getVideoDecoderCounters().ensureUpdated();
                        droppedFrames = mPlayer.getVideoDecoderCounters().droppedBufferCount;
                    }
                } catch (Throwable ignored) {}

                String safeNetType = com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlaybackBufferPolicy.getSafeNetworkType(mContext);
                String engineName = "DefaultHttp";
                try {
                    int currentEngine = PlayerTweaksData.instance(mContext).getPlayerDataSource();
                    engineName = currentEngine == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP ? "OkHttp" : currentEngine == PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET ? "Cronet" : "DefaultHttp";
                } catch (Throwable ignored) {}

                boolean isBehindLive = false;
                if (mLastErrorClass != null && mLastErrorClass.toLowerCase(java.util.Locale.ROOT).contains("behindlive")) {
                    isBehindLive = true;
                }
                boolean isSeekPending = com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPendingSeekCoordinator.instance().hasActivePendingSeek();
                String bandwidthMeterStatus = throughput > 0 ? "SAMPLED" : "UNMEASURED";

                com.liskovsoft.smartyoutubetv2.common.vox.playback.RebufferClassification classification =
                        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPlaybackBufferPolicy.classifyRebufferDetailed(
                                bitrate, buffered, throughput, droppedFrames, height >= 2160, 0, mLastIsDecoderError, mLastHttpStatus, mLastErrorClass, isBehindLive,
                                safeNetType, isSeekPending, bandwidthMeterStatus);

                String opId = mCurrentOperationId != null ? mCurrentOperationId : "sess_" + mPlaybackSessionId;
                String incidentId = "reb_" + System.currentTimeMillis() + "_" + mPlaybackSessionId;

                com.liskovsoft.smartyoutubetv2.common.vox.playback.RebufferIncident incident =
                        new com.liskovsoft.smartyoutubetv2.common.vox.playback.RebufferIncident(
                                incidentId,
                                System.currentTimeMillis(),
                                null,
                                pos,
                                buffered,
                                0L,
                                height,
                                codec,
                                bitrate,
                                engineName,
                                isLive ? "LIVE" : "VOD",
                                isLive,
                                String.valueOf(mPlaybackSessionId),
                                opId,
                                "PLAYBACK_START",
                                safeNetType,
                                mLastIsDecoderError ? "ERROR" : "NORMAL",
                                mLastHttpStatus,
                                mLastErrorClass,
                                null,
                                null,
                                throughput > 0 ? throughput : null,
                                bandwidthMeterStatus,
                                droppedFrames > 0 ? droppedFrames : null,
                                classification.getRootCause(),
                                classification.getEvidenceMissing(),
                                "IN_PROGRESS"
                        );
                mCurrentRebufferIncident = incident;

                if (mRebufferCount <= 3) {
                    Map<String, String> ctx = new HashMap<>(incident.toMap());
                    ctx.put("rebufferCount", String.valueOf(mRebufferCount));
                    if (classification.getDetail() != null) {
                        ctx.put("classificationDetail", classification.getDetail());
                    }

                    VoxSafeLogger.w(VoxLogCategory.PLAYER, VoxLogCode.REBUFFER_STARTED, "Начало буферизации потока", ctx);
                    VoxSafeLogger.w(VoxLogCategory.PLAYER, VoxLogCode.PLAYER_REBUFFER, "Буферизация потока", ctx);

                    try {
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxUserJourneyRecorder.instance(mContext)
                                .recordPlayback("REBUFFER_STARTED", null, null, pos, ctx, classification.getRootCause().name(), opId, null, "BUFFERING", null);
                    } catch (Throwable ignored) {}
                }
            }
            mEventListener.onBuffering();
        }

        if (getPositionMs() < getDurationMs()) {
            mIsEnded = false;
        }
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        Log.e(TAG, "onPositionDiscontinuity");

        // Fix video loop on 480p with legacy codes enabled
        if (reason == Player.DISCONTINUITY_REASON_PERIOD_TRANSITION) {
            mPlayer.stop();
            mEventListener.onPlayEnd();
        }
    }

    @Override
    public void onSeekProcessed() {
        mEventListener.onSeekEnd();
    }

    public float getSpeed() {
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().speed;
        } else {
            return -1;
        }
    }

    public void setSpeed(float speed) {
        if (mPlayer != null && speed > 0) {
            if (PlayerTweaksData.instance(mContext).isAudioTimeStretchingEnabled()) {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, mPlayer.getPlaybackParameters().pitch));
            } else {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, speed));
            }

            mTrackFormatter.setSpeed(speed);
            setQualityInfo(mTrackFormatter.getQualityLabel());
            mEventListener.onSpeedChanged(speed);
            Log.d("VoxSpeedE2E", "EXOPLAYER_SPEED: setSpeed=" + speed + ", actualPlaybackSpeed=" + (mPlayer != null ? mPlayer.getPlaybackParameters().speed : -1f));
        }
    }

    public float getPitch() {
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().pitch;
        } else {
            return -1;
        }
    }

    public void setPitch(float pitch) {
        if (mPlayer != null && pitch > 0) {
            mPlayer.setPlaybackParameters(new PlaybackParameters(mPlayer.getPlaybackParameters().speed, pitch));
        }
    }

    public void setVolume(float volume) {
        if (mPlayer != null && volume >= 0) {
            mPlayer.setVolume(Math.min(volume, 1f));

            //applyVolumeBoost(volume);
        }
    }

    public float getVolume() {
        if (mPlayer != null) {
            return mPlayer.getVolume();
        } else {
            return 1;
        }
    }

    /**
     * Fixes video artifacts when switching to the next video.<br/>
     * Also could help with memory leaks(??)<br/>
     * Without this also you'll have problems with track quality switching(??).
     */
    public void resetPlayerState() {
        finishRebufferIncident("STOPPED_BY_USER");
        mIsEnded = false;
        if (containsMedia()) {
            mPlayer.stop(true);
        }
    }

    public void setOnVideoLoaded(Runnable onVideoLoaded) {
        mOnVideoLoaded = onVideoLoaded;
    }

    private void setQualityInfo(String qualityInfoStr) {
        if (mPlayerView != null && qualityInfoStr != null) {
            mPlayerView.setQualityInfo(qualityInfoStr);
        }
    }

    private void applyVolumeBoost(float volume) {
        if (mPlayer == null) {
            return;
        }

        if (mVolumeBooster != null) {
            mPlayer.removeAudioListener(mVolumeBooster);
            mVolumeBooster = null;
        }

        // 5.1 audio cannot be boosted (format isn't supported error)
        // also, other 2.0 tracks in 5.1 group is already too loud. so cancel them too.
        if (volume > 1f && !contains51Audio() && Build.VERSION.SDK_INT >= 19) {
            mVolumeBooster = new VolumeBooster(true, volume, null);
            mPlayer.addAudioListener(mVolumeBooster);
        }
    }

    private boolean contains51Audio() {
        if (mTrackSelectorManager == null || mTrackSelectorManager.getAudioTracks() == null) {
            return false;
        }

        for (MediaTrack track : mTrackSelectorManager.getAudioTracks()) {
            if (TrackSelectorUtil.is51Audio(track.format)) {
                return true;
            }
        }

        return false;
    }

    private void releasePlayer() {
        if (mPlayer == null) {
            return;
        }

        try {
            mPlayer.removeListener(this);
            mPlayer.removeVideoListener(this);
            mPlayer.stop(true); // Cause input lags due to high cpu load?
            mPlayer.clearVideoSurface();
            mPlayer.release();
        } catch (ArrayIndexOutOfBoundsException e) { // thrown on stop()
            e.printStackTrace();
        } finally {
            finishRebufferIncident("APP_CLOSED");
            mPlayer = null;
            mCurrentMediaSource = null;
            VoxPlayerTracker.onPlayerReleased();
        }
    }

    @Override
    public void onTimelineChanged(Timeline timeline, @Nullable Object manifest, int reason) {
        long duration = getDurationMs();
        mOfflineProbe.onTimelineChanged(duration, duration > 0, false);
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.finishStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_REQUEST, "OK");
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.recordStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_MANIFEST, 0L, "OK");
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.startStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_TRACK_SELECT);
    }

    @Override
    public void onRenderedFirstFrame() {
        mOfflineProbe.onFirstFrameRendered();
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.recordStage(com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.STAGE_FIRST_FRAME, 0L, "OK");
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
    }

    @Override
    public void onSurfaceSizeChanged(int width, int height) {
    }

    private void collectSafeMemoryTelemetry(java.util.Map<String, String> errorCtx) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long maxHeap = runtime.maxMemory();
            long totalHeap = runtime.totalMemory();
            long freeHeap = runtime.freeMemory();
            long usedHeap = totalHeap - freeHeap;
            errorCtx.put("maxHeapMb", String.valueOf(maxHeap / (1024 * 1024)));
            errorCtx.put("totalHeapMb", String.valueOf(totalHeap / (1024 * 1024)));
            errorCtx.put("freeHeapMb", String.valueOf(freeHeap / (1024 * 1024)));
            errorCtx.put("usedHeapMb", String.valueOf(usedHeap / (1024 * 1024)));

            if (mContext != null) {
                android.app.ActivityManager am = (android.app.ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(memInfo);
                    errorCtx.put("availMemMb", String.valueOf(memInfo.availMem / (1024 * 1024)));
                    errorCtx.put("totalMemMb", String.valueOf(memInfo.totalMem / (1024 * 1024)));
                    errorCtx.put("lowMemory", String.valueOf(memInfo.lowMemory));
                    errorCtx.put("thresholdMb", String.valueOf(memInfo.threshold / (1024 * 1024)));
                }
            }

            if (mPlayer != null) {
                Format format = mPlayer.getVideoFormat();
                if (format != null) {
                    errorCtx.put("selectedResolution", format.width + "x" + format.height);
                    if (format.sampleMimeType != null) {
                        errorCtx.put("selectedCodec", format.sampleMimeType + (format.codecs != null ? ("/" + format.codecs) : ""));
                    }
                }
                long bufferedDurationMs = Math.max(0, mPlayer.getBufferedPosition() - mPlayer.getCurrentPosition());
                errorCtx.put("bufferedDurationMs", String.valueOf(bufferedDurationMs));
            }

            errorCtx.put("activePlayers", String.valueOf(VoxPlayerTracker.getActivePlayerInstances()));
            errorCtx.put("recreationCount", String.valueOf(VoxPlayerTracker.getRecreationCount()));

            Video video = getVideo();
            if (video != null) {
                errorCtx.put("isDownloaded", String.valueOf(video.isLocal));
                errorCtx.put("isLive", String.valueOf(video.isLive));
            }
        } catch (Exception e) {
            // Safe telemetry collection must never throw
        }
    }
}

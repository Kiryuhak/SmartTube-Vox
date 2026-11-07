package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.ScreensaverManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
public class VideoStateController extends BasePlayerController {
    private static final String TAG = VideoStateController.class.getSimpleName();
    private static final long MUSIC_VIDEO_MAX_DURATION_MS = 6 * 60 * 1000;
    private static final long NEAR_LIVE_EDGE_MS = 60_000;
    private static final long DEFAULT_LIVE_BUFFER_MS = 60_000; // Minimum issues
    private static final long OFFICIAL_LIVE_BUFFER_MS = 15_000; // Official app buffer
    private static final long LIVE_BUFFER_MS = OFFICIAL_LIVE_BUFFER_MS;
    private static final long SHORT_LIVE_BUFFER_MS = 5_000; // VOX-Patch-4: raised from 0 to 5s to avoid seeking to un-ingested edge
    private static final long BEGIN_THRESHOLD_MS = 10_000;
    private static final long EMBED_THRESHOLD_MS = 30_000;
    private static final int HISTORY_UPDATE_INTERVAL_MINUTES = 3; // Sync history every x minutes
    private boolean mIsPlayEnabled;
    private boolean mIsPlayBlocked;
    private int mTickleCount;
    private boolean mIncognito;
    private final Runnable mUpdateHistory = this::saveState;
    private long mNewVideoTimeMs;
    @Nullable
    private Boolean mRestoreLiveEdge;
    private long mSleepTimerStartMs;
    private boolean mIsApplyingProgrammaticSpeed;
    @Override
    public void onInit() {
        mSleepTimerStartMs = System.currentTimeMillis();
    }

    /**
     * Fired after user clicked on video in browse activity<br/>
     * or video is opened from the intent
     */
    @Override
    public void onNewVideo(Video item) {
        if (item == null) return;
        // Ensure that we aren't running on presenter init stage
        if (getPlayer() != null && getPlayer().containsMedia()) {
            //if (!item.equals(getVideo())) { // a video might be opened twice (when remote connection enabled). Fix for that.

            // NOTE: even for the same videos it's good to save state (switch from embed, video reload etc)
            // Reset auto-save history timer
            mTickleCount = 0;
            // Save state of the previous video.
            // In case video opened from phone and other stuff.
            saveState();
        }

        if (!item.equals(getVideo())) {
            mNewVideoTimeMs = System.currentTimeMillis();
            getPlayerData().setTempVideoFormat(null);
            getPlayerData().setTempAudioFormat(null);
            getPlayerData().setTempSubtitleFormat(null);
        }

        setPlayEnabled(true); // video just added

        enableIncognitoIfNeeded(item);

        // Don't do reset on videoLoaded state because this will influences minimized music videos.
        resetPositionIfNeeded(item);
        resetGlobalSpeedIfNeeded();

        if (getContext() != null) {
            try {
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxUserJourneyRecorder.Companion.instance(getContext())
                        .startNewPlaybackSession();
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxUserJourneyRecorder.Companion.instance(getContext())
                        .recordPlayback("PLAYBACK_OPEN", item != null ? item.getTitle() : null, item != null ? item.videoId : null, 0L);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public boolean onPreviousClicked() {
        // Seek to the start on prev
        if (getPlayer() != null && getPlayer().getPositionMs() > BEGIN_THRESHOLD_MS) {
            saveState(); // in case the user wants to go to previous video
            getPlayer().setPositionMs(100);
            mRestoreLiveEdge = false;
            return true;
        }

        // Pass to others handlers
        return false;
    }

    @Override
    public boolean onNextClicked() {
        // Seek to the actual live position on next
        if (trySeekToLiveEdge()) {
            return true;
        }

        setPlayEnabled(true);

        saveState();

        clearStateOfNextVideo();

        return false;
    }

    @Override
    public void onSuggestionItemClicked(Video item) {
        setPlayEnabled(true); // autoplay video from suggestions

        saveState();
    }

    @Override
    public void onEngineInitialized() {
        // Reset auto-save history timer
        mTickleCount = 0;

        // Show user info instead of black screen.
        //if (getPlayer() != null && !getPlayEnabled()) {
        //    getPlayer().showOverlay(true);
        //}

        mSleepTimerStartMs = System.currentTimeMillis();
    }

    @Override
    public void onEngineReleased() {
        if (getPlayer() == null) {
            return;
        }

        // Save previous state
        if (getPlayer().containsMedia()) {
            setPlayEnabled(getPlayer().getPlayWhenReady());
            saveState();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode) {
        mSleepTimerStartMs = System.currentTimeMillis();

        // Remove error msg if needed
        if (getPlayer() != null && getPlayerData().getSleepTimerHours() > 0) {
            getPlayer().setVideo(getVideo());
        }

        return false;
    }

    @Override
    public void onTickle() {
        if (getPlayer() == null || !getPlayer().isEngineInitialized()) {
            return;
        }

        if (++mTickleCount > HISTORY_UPDATE_INTERVAL_MINUTES && getPlayer().isPlaying()) {
            mTickleCount = 0;
            saveState();
        }

        checkSleepTimer();
    }

    private void checkSleepTimer() {
        if (getPlayer() == null) {
            return;
        }

        float sleepHours = getPlayerData().getSleepTimerHours();
        if (sleepHours > 0 && System.currentTimeMillis() - mSleepTimerStartMs > sleepHours * 60 * 60 * 1_000) {
            getPlayer().setPlayWhenReady(false);
            getPlayer().setTitle(getContext().getString(R.string.player_sleep_timer)
                    + " (" + getContext().getResources().getQuantityString(R.plurals.hours, (int) sleepHours, Helpers.toString(sleepHours)) + ")");
            getPlayer().showOverlay(true);
            Helpers.enableScreensaver(getActivity());
        }
    }

    @Override
    public void onMetadata(MediaItemMetadata metadata) {
        if (metadata != null && metadata.getVideoId() != null && getVideo() != null &&
                !metadata.getVideoId().equals(getVideo().videoId)) {
            return;
        }

        // Channel info should be loaded at this point
        if (getVideo() != null && !getVideo().isRemote) {
            restoreSubtitleFormat();
        }

        // Need to contain channel id
        restoreSpeedIfNeeded();
        restoreLivePositionIfNeeded();

        // NOTE: needed for the restore after oom crash?
        saveState(); // start watching?
    }

    @Override
    public void onSeekPositionChanged(long positionMs) {
        // Need a delay while player internal state changed
        Utils.post(mUpdateHistory);
        mRestoreLiveEdge = false;
        if (getVideo() != null && getVideo().isLive) {
            Video video = getVideo();
            long dur = getPlayer() != null ? getPlayer().getDurationMs() : video.getDurationMs();
            float spd = getPlayer() != null ? getPlayer().getSpeed() : 1.0f;
            State updatedState = new State(video, positionMs, dur, spd);
            getStateService().save(updatedState);
            video.sync(updatedState);
        }
    }

    @Override
    public void onEngineError(int type, int rendererIndex, Throwable error) {
        if (getPlayer() == null) {
            return;
        }

        // Oops. Error happens while playing (network lost etc).
        if (getPlayer().getPositionMs() > 1_000) {
            saveState();
        }
    }

    @Override
    public void onVideoLoaded(Video item) {
        // Actual video that match currently loaded one.
        //mVideo = item;

        // Restore formats again.
        // Maybe this could help with Shield format problem.
        // NOTE: produce multi thread exception:
        // Attempt to read from field 'java.util.TreeMap$TreeMapEntry java.util.TreeMap$TreeMapEntry.left' on a null object reference (TrackSelectorManager.java:181)
        //restoreFormats();

        // In this state video length is not undefined.
        restoreState();
        restoreSpeedIfNeeded();

        if (getContext() != null) {
            try {
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxUserJourneyRecorder.Companion.instance(getContext())
                        .recordPlayback("PLAYBACK_LOADED", item != null ? item.getTitle() : null, item != null ? item.videoId : null, getPlayer() != null ? getPlayer().getPositionMs() : 0L);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onPlay() {
        setPlayEnabled(true);
        showHideScreensaver(false);
        // throttle seeking calls
        Utils.removeCallbacks(mUpdateHistory);

        if (shouldRestoreLiveEdge()) {
            trySeekToLiveEdge();
        }

        if (getContext() != null) {
            try {
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxUserJourneyRecorder.Companion.instance(getContext())
                        .recordPlayback("PLAYBACK_PLAY", getVideo() != null ? getVideo().getTitle() : null, getVideo() != null ? getVideo().videoId : null, getPlayer() != null ? getPlayer().getPositionMs() : 0L);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onPause() {
        setPlayEnabled(false);
        showHideScreensaver(true);
        // throttle seeking calls
        Utils.postDelayed(mUpdateHistory, 10_000);

        if (getContext() != null) {
            try {
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxUserJourneyRecorder.Companion.instance(getContext())
                        .recordPlayback("PLAYBACK_PAUSE", getVideo() != null ? getVideo().getTitle() : null, getVideo() != null ? getVideo().videoId : null, getPlayer() != null ? getPlayer().getPositionMs() : 0L);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onTrackSelected(FormatItem track) {
        //if (!getPlayer().isInPIPMode()) {
        //    if (track.getType() == FormatItem.TYPE_VIDEO) {
        //        if (getPlayerData().getFormat(FormatItem.TYPE_VIDEO).isPreset()) {
        //            mTempVideoFormat = track;
        //        } else {
        //            mTempVideoFormat = null;
        //            getPlayerData().setFormat(track);
        //        }
        //    } else {
        //        getPlayerData().setFormat(track);
        //    }
        //}

        //if (!getPlayer().isInPIPMode()) {
        //    if (track.getType() == FormatItem.TYPE_VIDEO) {
        //        mTempVideoFormat = getPlayerData().getFormat(FormatItem.TYPE_VIDEO).isPreset() ? track : null;
        //    }
        //}
    }

    @Override
    public void onPlayEnd() {
        saveState();

        // Don't enable screensaver here, or you'll brake 'screen off' logic.
        showHideScreensaver(true);
    }

    @Override
    public void onBuffering() {
        // Restore speed on LIVE end or after seek
        restoreSpeedIfNeeded();
        restoreLivePositionIfNeeded();

        // Live stream starts to buffer after the end
        showHideScreensaver(true);

        initRestoreLiveEdge();
    }

    @Override
    public void onSourceChanged(Video item) {
        // At this stage video isn't loaded yet. So format switch isn't take any resources.
        restoreFormats();
    }

    @Override
    public void onSpeedChanged(float speed) {
        if (getVideo() == null) {
            return;
        }

        if (mIsApplyingProgrammaticSpeed) {
            return;
        }

        handleUserSpeedChange(speed);
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId == R.id.action_video_speed) {
            onSpeedClicked(buttonState == PlayerUI.BUTTON_ON);
        }
    }

    @Override
    public void onButtonLongClicked(int buttonId, int buttonState) {
        if (buttonId == R.id.action_video_speed) {
            onSpeedLongClicked(buttonState == PlayerUI.BUTTON_ON);
        }
    }

    private void onSpeedClicked(boolean enabled) {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        fitVideoIntoDialog();

        float targetSpeed;
        if (enabled) {
            targetSpeed = 1.0f;
        } else {
            float preferredSpeed = resolveDesiredSpeed(getVideo());
            if (Helpers.floatEquals(preferredSpeed, 1.0f)) {
                preferredSpeed = getPlayerData().getLastSpeed();
            }
            if (Helpers.floatEquals(preferredSpeed, 1.0f)) {
                preferredSpeed = getPlayerData().getSpeed();
            }
            targetSpeed = !Helpers.floatEquals(preferredSpeed, 1.0f) ? preferredSpeed : 1.25f;
        }

        if (Helpers.floatEquals(targetSpeed, 1.0f) && getPlayerTweaksData().isSpeedButtonOldBehaviorEnabled()) {
            onSpeedLongClicked(enabled);
        } else {
            getPlayer().setSpeed(targetSpeed);
        }
    }

    private void onSpeedLongClicked(boolean enabled) {
        fitVideoIntoDialog();

        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        // suppose live stream if buffering near the end
        // boolean isStream = Math.abs(player.getDuration() - player.getCurrentPosition()) < 10_000;
        settingsPresenter.appendCategory(AppDialogUtil.createSpeedListCategory(getContext(), getPlayer()));

        settingsPresenter.showDialog(getContext().getString(R.string.video_speed), () -> {
            State state = getStateService().getByVideoId(getVideo() != null ? getVideo().videoId : null);
            if (state != null && getPlayerData().isSpeedPerVideoEnabled()) {
                getStateService().save(new State(state.video, state.positionMs, state.durationMs, getPlayerData().getSpeed(getVideo().channelId)));
            }
        });
    }

    @Override
    public void onFinish() {
        mIncognito = false;
    }

    private void clearStateOfNextVideo() {
        if (getVideo() != null && getVideo().nextMediaItem != null) {
            resetPosition(Video.from(getVideo().nextMediaItem));
        }
    }

    /**
     * Reset position of currently opened music and live videos.
     */
    private void resetPositionIfNeeded(Video item) {
        if (getStateService() == null || item == null) {
            return;
        }

        if (item.fromCrashRestorer) {
            item.fromCrashRestorer = false;
            return;
        }

        State state = getStateService().getByVideoId(item.videoId);

        // Reset position of music videos
        boolean isShort = state != null && state.durationMs < MUSIC_VIDEO_MAX_DURATION_MS
                && !getPlayerTweaksData().isRememberPositionOfShortVideosEnabled();
        boolean isVideoEnded = state != null && state.durationMs - state.positionMs < 3_000;
        boolean isLive = item.isLive;

        if (getPlayerTweaksData().isRememberPositionOfLiveVideosEnabled() && item.isFullLive()) {
            isLive = false;
        }

        // Don't reset if doing switch from the embed to the fullscreen one
        boolean sameVideo = item.equals(getVideo());
        if (sameVideo) {
            isShort = false;
        }

        if (isShort || isVideoEnded || isLive) {
            resetPosition(item);
        }
    }

    private void resetGlobalSpeedIfNeeded() {
        // Policy resolver dynamically handles all four modes without destroying user preferences
    }

    private void resetPosition(Video video) {
        if (video == null) {
            return;
        }

        video.markNotViewed();
        State state = getStateService().getByVideoId(video.videoId);

        if (state != null) {
            if (getPlayerData().isSpeedPerVideoEnabled()) {
                getStateService().save(new State(video, 0, state.durationMs, state.speed));
            } else {
                getStateService().removeByVideoId(video.videoId);
            }
        }
    }

    private void enableIncognitoIfNeeded(Video item) {
        if (item == null) {
            return;
        }

        // Enable incognito per session
        // Reset to default when player finished
        if (item.incognito) {
            mIncognito = true;
            item.incognito = false;
        }
    }

    private void restoreVideoFormat() {
        if (getPlayer() == null) {
            return;
        }

        if (getPlayerData().getTempVideoFormat() != null) {
            getPlayer().setFormat(getPlayerData().getTempVideoFormat());
        } else {
            getPlayer().setFormat(getPlayerData().getFormat(FormatItem.TYPE_VIDEO));
        }
    }

    private void restoreAudioFormat() {
        if (getPlayer() == null) {
            return;
        }

        if (getPlayerData().getTempAudioFormat() != null) {
            getPlayer().setFormat(getPlayerData().getTempAudioFormat());
        } else {
            getPlayer().setFormat(getPlayerData().getFormat(FormatItem.TYPE_AUDIO));
        }
    }

    private void restoreSubtitleFormat() {
        if (getPlayer() == null) {
            return;
        }

        if (getPlayerData().getTempSubtitleFormat() != null) {
            getPlayer().setFormat(getPlayerData().getTempSubtitleFormat());
            return;
        }

        FormatItem result = getPlayerData().getFormat(FormatItem.TYPE_SUBTITLE);

        if (getPlayerData().isSubtitlesPerChannelEnabled()) {
            result = getPlayerData().isSubtitlesPerChannelEnabled(getPlayer().getVideo().channelId)
                    ? getPlayerData().getLastSubtitleFormat() : FormatItem.SUBTITLE_NONE;
        }

        getPlayer().setFormat(result);
    }

    private void saveState() {
        // Skip mini player, but don't save for the previews (mute enabled)
        if (isMutedEmbed()) {
            return;
        }

        savePosition();

        if (!isBeginEmbed()) {
            updateHistory();
            syncWithPlaylists();
        }
    }

    private void restoreState() {
        if (getPlayer() == null) {
            return;
        }

        restorePosition();
        restorePendingPosition();
        // Player thinks that subs not enabled if I enable it too early (e.g. on source change event).
        //restoreSubtitleFormat();

        restoreVolume();
        restorePitch();
    }

    //private void persistState() {
    //    // Skip mini player, but don't save for the previews (mute enabled)
    //    if (isMutedEmbed() || isBeginEmbed()) {
    //        return;
    //    }
    //
    //    getStateService().persistState();
    //}

    private void savePosition() {
        Video video = getVideo();

        if (video == null || getPlayer() == null || !getPlayer().containsMedia()) {
            return;
        }

        // Exceptional cases:
        // 1) Track is ended
        // 2) Pause on end enabled
        // 3) Watching live stream in real time
        long durationMs = getPlayer().getDurationMs();
        long positionMs = getPlayer().getPositionMs();
        long remainsMs = durationMs - positionMs;
        boolean isPositionActual = remainsMs > 1_000;
        boolean isLiveBroken = video.isLive && durationMs <= 30_000; // live without a history
        float speedToSave = resolveDesiredSpeed(video);
        if (isPositionActual && !isLiveBroken) { // partially viewed
            State state = new State(video, positionMs, durationMs, speedToSave);
            getStateService().save(state);
            // Sync video. You could safely use it later to restore state.
            video.sync(state);
        } else { // fully viewed
            // Mark video as fully viewed. This could help to restore proper progress marker on the video card later.
            getStateService().save(new State(video, durationMs, durationMs, speedToSave));
            video.markFullyViewed();
        }

        Playlist.instance().sync(video);
    }

    private void restorePosition() {
        Video item = getVideo();

        if (getPlayer() == null || item == null) {
            return;
        }

        // Prioritize explicit user seek target over stale database state
        if (item.pendingPosMs > 0) {
            getPlayer().setPositionMs(item.pendingPosMs);
            if (!mIsPlayBlocked) {
                boolean playEnabled = getPlayEnabled();
                getPlayer().setPlayWhenReady(playEnabled);
                if (!getPlayer().isOverlayShown()) {
                    getPlayer().showOverlay(!playEnabled);
                }
            }
            return;
        }

        State state = getStateService().getByVideoId(item.videoId);

        boolean stateIsOutdated = isStateOutdated(state, item);
        if (stateIsOutdated) { // check that the user logged in
            // Web state is buggy on short videos (e.g. video clips)
            boolean isLongVideo = getPlayer().getDurationMs() > MUSIC_VIDEO_MAX_DURATION_MS;
            if (isLongVideo) {
                state = new State(item, item.getPositionMs());
            }
        }

        // Set actual position for live videos with uncommon length
        if (item.isLive && (state == null || state.durationMs - state.positionMs < Math.max(NEAR_LIVE_EDGE_MS, getLiveThresholdMs()))) {
            // Add buffer. Should I take into account segment offset???
            state = new State(item, getPlayer().getDurationMs() - getLiveBufferMs());
        }

        if (item.isLive && state != null) {
            long durationMs = getPlayer().getDurationMs();
            if (durationMs > 0) {
                long maxSafePos = Math.max(0L, durationMs - getLiveBufferMs());
                if (state.positionMs < 0L || state.positionMs > durationMs) {
                    state = new State(item, maxSafePos);
                }
            }
        }

        // Do I need to check that item isn't live? (state != null && !item.isLive)
        if (state != null) {
            getPlayer().setPositionMs(state.positionMs);
        }

        if (!mIsPlayBlocked) {
            boolean playEnabled = getPlayEnabled();
            getPlayer().setPlayWhenReady(playEnabled);
            if (!getPlayer().isOverlayShown()) {
                getPlayer().showOverlay(!playEnabled);
            }
        }
    }

    private void updateHistory() {
        Video video = getVideo();

        if (video == null || mIncognito || getPlayer() == null || !getPlayer().containsMedia()
                || (video.isRemote && getRemoteControlData().isRemoteHistoryDisabled())
                || getGeneralData().getHistoryState() == GeneralData.HISTORY_DISABLED) {
            return;
        }

        MediaServiceManager.instance().updateHistory(video, Math.max(getPlayer().getPositionMs(), 3_000)); // 0 == fully watched
    }

    /**
     * Restore position from description time code
     */
    private void restorePendingPosition() {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        Video item = getVideo();

        if (item.pendingPosMs > 0) {
            getPlayer().setPositionMs(item.pendingPosMs);
            item.pendingPosMs = 0;
        }
    }

    private void restoreLivePositionIfNeeded() {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        if (com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPendingSeekCoordinator.instance().hasActivePendingSeek()) {
            return;
        }

        Video item = getVideo();
        if (item.pendingPosMs > 0) {
            return;
        }

        if (isLiveEnd()) {
            getPlayer().setPositionMs(getPlayer().getDurationMs() - getLiveBufferMs());
        }
    }

    private void restoreSpeedIfNeeded() {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        Video item = getVideo();
        float effectiveSpeed = calculateEffectiveSpeed(item);
        float currentSpeed = getPlayer().getSpeed();

        if (currentSpeed <= 0 || !Helpers.floatEquals(currentSpeed, effectiveSpeed)) {
            applySpeed(effectiveSpeed);
        } else {
            getPlayer().setButtonState(R.id.action_video_speed, effectiveSpeed != 1.0f ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF);
        }
    }

    private void applySpeed(float speed) {
        if (getPlayer() == null) {
            return;
        }

        logSpeedEvent(VoxLogCode.SPEED_APPLIED, "RESTORE", speed);

        mIsApplyingProgrammaticSpeed = true;
        try {
            getPlayer().setSpeed(speed);
            getPlayer().setButtonState(R.id.action_video_speed, speed != 1.0f ? PlayerUI.BUTTON_ON : PlayerUI.BUTTON_OFF);
        } finally {
            mIsApplyingProgrammaticSpeed = false;
        }
    }

    public float resolveDesiredSpeed(Video video) {
        if (video == null) {
            return 1.0f;
        }

        if (getPlayerData().isSpeedPerVideoEnabled()) {
            State state = getStateService().getByVideoId(video.videoId);
            return (state != null && state.speed > 0) ? state.speed : 1.0f;
        }

        if (getPlayerData().isSpeedPerChannelEnabled()) {
            if (video.channelId != null) {
                return getPlayerData().getSpeed(video.channelId);
            }
            return 1.0f;
        }

        if (getPlayerData().isAllSpeedEnabled()) {
            float speed = getPlayerData().getSpeed(null);
            return speed > 0 ? speed : 1.0f;
        }

        return 1.0f;
    }

    private float calculateEffectiveSpeed(Video video) {
        if (video == null) {
            return 1.0f;
        }
        if (isLiveEnd() || isMusicVideo() || (video.isLive && isNearLiveEdge())) {
            logSpeedEvent(VoxLogCode.SPEED_RESET_REASON, isMusicVideo() ? "MUSIC_VIDEO" : "LIVE_END", 1.0f);
            return 1.0f;
        }
        return resolveDesiredSpeed(video);
    }

    private void handleUserSpeedChange(float speed) {
        Video video = getVideo();
        if (video == null) {
            return;
        }

        logSpeedEvent(VoxLogCode.SPEED_USER_CHANGED, "USER", speed);

        if (getPlayerData().isSpeedPerVideoEnabled()) {
            State state = getStateService().getByVideoId(video.videoId);
            getStateService().save(new State(video, state != null ? state.positionMs : 0, state != null ? state.durationMs : 0, speed));
        } else if (getPlayerData().isSpeedPerChannelEnabled()) {
            getPlayerData().setSpeed(video.channelId, speed);
        } else if (getPlayerData().isAllSpeedEnabled()) {
            getPlayerData().setSpeed(null, speed);
        } else {
            getPlayerData().setLastSpeed(speed);
        }
    }

    private void logSpeedEvent(String code, String trigger, float speed) {
        try {
            Map<String, String> ctx = new HashMap<>();
            String mode = getPlayerData().isAllSpeedEnabled() ? "ALL" :
                    getPlayerData().isSpeedPerChannelEnabled() ? "PER_CHANNEL" :
                    getPlayerData().isSpeedPerVideoEnabled() ? "PER_VIDEO" : "NONE";
            ctx.put("policy", mode);
            ctx.put("trigger", trigger);
            ctx.put("speed", String.valueOf(speed));
            VoxSafeLogger.d(VoxLogCategory.PLAYER, code, "Speed event: " + code, ctx);
            Log.d("VoxSpeedE2E", "SPEED_EVENT: code=" + code + ", trigger=" + trigger + ", speed=" + speed + ", actualExoSpeed=" + (getPlayer() != null ? getPlayer().getSpeed() : -1f) + ", policy=" + mode);
        } catch (Throwable ignored) {}
    }

    public void blockPlay(boolean block) {
        mIsPlayBlocked = block;
    }

    public boolean getPlayEnabled() {
        return mIsPlayEnabled;
    }

    public boolean isPlayEnabled() {
        return mIsPlayEnabled;
    }

    public void setPlayEnabled(boolean isPlayEnabled) {
        Log.d(TAG, "setPlayEnabled %s", isPlayEnabled);
        mIsPlayEnabled = isPlayEnabled;
    }

    private void restoreVolume() {
        if (getVideo() == null || getPlayer() == null) {
            return;
        }

        float newVolume = getPlayerData().getPlayerVolume();

        if (getPlayerTweaksData().isPlayerAutoVolumeEnabled()) {
            //newVolume *= getVideo().volume;
            //newVolume = getVideo().volume;
            if (newVolume < 1f) {
                newVolume *= getVideo().volume;
            } else {
                newVolume = getVideo().volume;
            }
        }

        if (getVideo().isShorts) {
            newVolume /= 2;
        }

        getPlayer().setVolume(newVolume);
    }

    private void restorePitch() {
        if (getPlayer() == null) {
            return;
        }

        getPlayer().setPitch(getPlayerData().getPitch());
    }

    private void restoreFormats() {
        restoreVideoFormat();
        restoreAudioFormat();
        // We don't know yet do we really need a subs.
        // NOTE: Some subs can hang the app.
        restoreSubtitleFormat();
    }

    private void showHideScreensaver(boolean show) {
        ScreensaverManager screensaverManager = getScreensaverManager();

        if (screensaverManager == null) {
            return;
        }

        if (show) {
            screensaverManager.enableChecked();
        } else {
            screensaverManager.disableChecked();
        }
    }

    private boolean isMusicVideo() {
        if (getVideo() == null) {
            return false;
        }

        Video item = getVideo();
        return item.belongsToMusic() || (item.category != null && "Music".equalsIgnoreCase(item.category));
    }

    //private void setPositionMs(long positionMs) {
    //    boolean samePositions = Math.abs(positionMs - getPlayer().getPositionMs()) < BEGIN_THRESHOLD_MS;
    //    if (!samePositions) {
    //        getPlayer().setPositionMs(positionMs);
    //    }
    //}

    private boolean isStateOutdated(State state, Video item) {
        if (state == null) {
            return true;
        }

        // Web live position is broken. Ignore it.
        if (item.isLive || item.getPositionMs() <= 0) {
            return false;
        }

        float posPercents1 = state.positionMs * 100f / state.durationMs;
        float posPercents2 = item.getPositionMs() * 100f / item.getDurationMs();

        return (posPercents2 != 0 && Math.abs(posPercents1 - posPercents2) > 3) && state.timestamp < item.timestamp;
    }

    private void syncWithPlaylists() {
        Video video = getVideo();

        if (video == null) {
            return;
        }

        if (getMediaServiceData().isContentHidden(MediaServiceData.CONTENT_WATCHED_WATCH_LATER) && video.percentWatched > 95) { // remove fully watched
            MediaServiceManager.instance().removeFromWatchLaterPlaylist(video);
        }

        if (getGeneralData().isHideWatchedFromNotificationsEnabled()) { // remove any watched length
            MediaServiceManager.instance().hideNotification(video);
        }
    }

    private boolean isLiveEnd() {
        if (getPlayer() == null || getVideo() == null || !getVideo().isLive) {
            return false;
        }

        if (com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxPendingSeekCoordinator.instance().hasActivePendingSeek()) {
            return false;
        }

        if (getVideo().pendingPosMs > 0) {
            return false;
        }

        long duration = getPlayer().getDurationMs();
        long position = getPlayer().getPositionMs();

        if (duration <= 5_000 || position <= 0) {
            return false;
        }

        return duration - position <= 1_000;
    }

    private long getLiveThresholdMs() {
        return getLiveBufferMs() + 5_000;
    }

    private long getLiveBufferMs() {
        return getPlayerTweaksData().isBufferOnStreamsDisabled() ? SHORT_LIVE_BUFFER_MS : LIVE_BUFFER_MS;
    }

    private boolean isMutedEmbed() {
        return isEmbedPlayer() && getPlayer() != null && Helpers.floatEquals(getPlayer().getVolume(), 0);
    }

    private boolean isBeginEmbed() {
        return isEmbedPlayer() && System.currentTimeMillis() - mNewVideoTimeMs <= EMBED_THRESHOLD_MS &&
                getPlayer() != null && getPlayer().getPositionMs() < getPlayer().getDurationMs();
    }

    private boolean trySeekToLiveEdge() {
        if (getPlayer() != null && isBehindLiveEdge()) {
            getPlayer().setPositionMs(getPlayer().getDurationMs() - getLiveBufferMs());
            mRestoreLiveEdge = getPlayerTweaksData().isLiveEdgeEnabled();
            return true;
        } else {
            return false;
        }
    }

    private boolean shouldRestoreLiveEdge() {
        return mRestoreLiveEdge != null && mRestoreLiveEdge && isBehindLiveEdge();
    }

    private void initRestoreLiveEdge() {
        if (mRestoreLiveEdge == null) {
            mRestoreLiveEdge = getPlayerTweaksData().isLiveEdgeEnabled() && isNearLiveEdge();
        }
    }

    private boolean isBehindLiveEdge() {
        return getVideo() != null && getPlayer() != null && getVideo().isLive && (getPlayer().getDurationMs() - getPlayer().getPositionMs() > getLiveThresholdMs());
    }

    private boolean isNearLiveEdge() {
        return getVideo() != null && getPlayer() != null && getVideo().isLive && (getPlayer().getDurationMs() - getPlayer().getPositionMs() < Math.max(NEAR_LIVE_EDGE_MS, getLiveThresholdMs()));
    }
}

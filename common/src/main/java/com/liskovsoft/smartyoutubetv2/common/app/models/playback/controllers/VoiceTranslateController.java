package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.SystemClock;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.common.vot.TranslationAudioPlayer;
import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioTrackHelper;
import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioTrackHelper.TrackInfo;
import com.liskovsoft.smartyoutubetv2.common.vot.VotClient;
import com.liskovsoft.smartyoutubetv2.common.vot.VotErrorCategory;
import com.liskovsoft.smartyoutubetv2.common.vot.VotProgress;
import com.liskovsoft.smartyoutubetv2.common.vot.VotProgressOverlay;
import com.liskovsoft.smartyoutubetv2.common.vot.VotProgressTimer;

import java.io.IOException;
import java.util.List;
import com.liskovsoft.smartyoutubetv2.common.vot.VotHttpException;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;

/**
 * Yandex voice-over translation (EN→RU) alongside the main player.
 */
public class VoiceTranslateController extends BasePlayerController {
    private static final String TAG = "SmartTubeVOT";
    private static final int ACTION_VOICE_TRANSLATE = R.id.action_voice_translate;

    public static final int BTN_OFF = 0;
    public static final int BTN_PENDING = 1;
    public static final int BTN_ON = 2;
    public static final int BTN_ERROR = 3;

    private static final int STATE_OFF = 0;
    private static final int STATE_PENDING = 1;
    private static final int STATE_ACTIVE = 2;

    private static final long SYNC_INTERVAL_MS = 1500L;
    private static final long SYNC_THRESHOLD_MS = 1800L;
    private static final long SYNC_SEEK_COOLDOWN_MS = 3500L;
    private static final long INITIAL_SYNC_GRACE_PERIOD_MS = 3000L;
    private static final long AUTO_TRANSLATE_RETRY_MS = 1000L;
    private static final int AUTO_TRANSLATE_MAX_RETRIES = 20;
    private static final long MAX_TOTAL_WAIT_MS = 25 * 60 * 1000L;
    private static final long PENDING_HEARTBEAT_TIMEOUT_MS = 90 * 1000L;
    private static final long PROGRESS_TICK_INTERVAL_MS = 1000L;

    public enum TrackSwitchState {
        IDLE,
        WAITING_CONFIRMATION,
        SWITCHING_TO_ORIGINAL,
        STARTING_VOT,
        VOT_ACTIVE,
        RESTORING_PREVIOUS_TRACK
    }

    private TrackSwitchState mTrackSwitchState = TrackSwitchState.IDLE;
    private FormatItem mPendingOriginalFormat;
    private FormatItem mRestorableDubFormat;
    private String mTrackSwitchVideoId;
    private boolean mUserManuallyChangedTrack;

    private VotData mVotData;
    private VotClient mVotClient;
    private TranslationAudioPlayer mTranslationPlayer;
    private Disposable mTranslationDisposable;
    private VotProgressOverlay mProgressOverlay;
    private final VotProgressTimer mProgressTimer = new VotProgressTimer();
    private float mSavedMainVolume = 1f;
    private boolean mIsAudioDucked;
    private FormatItem mSavedAudioFormat;
    private boolean mUserArmed;
    private boolean mArmed;
    private int mState = STATE_OFF;
    private int mPendingEtaSec;
    private boolean mPendingToastShown;
    private String mPendingVideoUrl;
    private String mCurrentVideoId;
    private int mAutoTranslateRetryCount;
    private int mTranslationSessionId;
    private long mLastSyncSeekTimestamp;

    private final Runnable mTrackSwitchTimeoutRunnable = () -> {
        if (mTrackSwitchState == TrackSwitchState.SWITCHING_TO_ORIGINAL) {
            Log.e(TAG, "VOT manual: switch to original track timed out");
            MessageHelpers.showMessage(getContext(), R.string.vot_unable_to_switch_original);
            restorePreviousDubTrack();
            resetTrackSwitch();
            if (mProgressOverlay != null) {
                mProgressOverlay.dismissImmediately();
            }
        }
    };

    private long mRequestStartTimestamp;
    private long mLastBackendPendingTimestamp;

    private final Runnable mSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (mState == STATE_ACTIVE) {
                syncTranslationPositionIfNeeded();
                Utils.postDelayed(mSyncRunnable, SYNC_INTERVAL_MS);
            }
        }
    };

    private final Runnable mAutoTranslateRetryRunnable = new Runnable() {
        @Override
        public void run() {
            tryApplyAutoTranslate(false);
        }
    };

    private final Runnable mResetErrorButtonRunnable = () -> {
        if (mState == STATE_OFF) {
            updateVoiceButton(BTN_OFF);
        }
    };

    private final Runnable mProgressTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (mState != STATE_PENDING) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (mProgressTimer.isHardTimeoutReached(now, MAX_TOTAL_WAIT_MS)) {
                Log.w(TAG, "VOT timeout: exceeded absolute maximum wait (%d ms) for video=%s", MAX_TOTAL_WAIT_MS, mCurrentVideoId);
                onTranslationTimeout();
                return;
            }

            if (mLastBackendPendingTimestamp > 0 && (now - mLastBackendPendingTimestamp > PENDING_HEARTBEAT_TIMEOUT_MS)) {
                Log.w(TAG, "VOT timeout: backend unresponsive for %d ms (video=%s)", now - mLastBackendPendingTimestamp, mCurrentVideoId);
                onTranslationTimeout();
                return;
            }

            long remainingSec = mProgressTimer.getRemainingTimeSec(now);

            if (mUserArmed && progressOverlay() != null) {
                if (remainingSec > 0) {
                    progressOverlay().showWaitingWithEta(getActivity(), VotProgressTimer.formatMmSs(remainingSec));
                } else {
                    long elapsedAfterEtaSec = mProgressTimer.getElapsedAfterEtaSec(now);
                    Log.d(TAG, "VOT ETA expired, polling continues: elapsed=%ds, video=%s", elapsedAfterEtaSec, mCurrentVideoId);
                    progressOverlay().showStillWaiting(getActivity(), VotProgressTimer.formatMmSs(elapsedAfterEtaSec));
                }
            }

            if (getPlayer() != null) {
                getPlayer().updateVoiceTranslatePendingEta((int) remainingSec);
            }

            Utils.postDelayed(mProgressTickRunnable, PROGRESS_TICK_INTERVAL_MS);
        }
    };

    private VotProgressOverlay progressOverlay() {
        if (mProgressOverlay == null && getContext() != null) {
            mProgressOverlay = new VotProgressOverlay(getContext());
        }
        return mProgressOverlay;
    }

    public VoiceTranslateController() {
    }

    private VotData votData() {
        if (mVotData == null) {
            mVotData = VotData.instance(getContext());
        }
        return mVotData;
    }

    private VotClient votClient() {
        if (mVotClient == null) {
            mVotClient = new VotClient(getContext());
        }
        return mVotClient;
    }

    @Override
    public void onNewVideo(Video item) {
        Log.d(TAG, "VOT reset reason: new video (%s)", item != null ? item.videoId : "null");
        mTranslationSessionId++;
        resetTrackSwitch();
        Utils.removeCallbacks(mAutoTranslateRetryRunnable);
        Utils.removeCallbacks(mProgressTickRunnable);
        Utils.removeCallbacks(mSyncRunnable);
        mProgressTimer.clear();
        mAutoTranslateRetryCount = 0;
        cancelTranslationJob();
        releaseTranslationPlayer();
        restoreMainVolume();
        mSavedAudioFormat = null;
        if (mProgressOverlay != null) {
            mProgressOverlay.dismissImmediately();
        }
        mPendingToastShown = false;
        mPendingVideoUrl = null;
        mCurrentVideoId = item != null ? item.videoId : null;

        if (mUserArmed) {
            mArmed = true;
            setState(STATE_PENDING);
        } else {
            mArmed = false;
            setState(STATE_OFF);
        }
    }

    @Override
    public void onVideoLoaded(Video item) {
        tryApplyAutoTranslate(false);
    }

    @Override
    public void onMetadata(MediaItemMetadata metadata) {
        tryApplyAutoTranslate(false);
    }

    @Override
    public void onTrackChanged(FormatItem track) {
        if (track != null && track.getType() == FormatItem.TYPE_AUDIO) {
            if (mTrackSwitchState == TrackSwitchState.WAITING_CONFIRMATION) {
                // User changed track while replacement dialog is open — abort dialog flow.
                Log.i(TAG, "VOT manual: track changed during WAITING_CONFIRMATION, aborting");
                resetTrackSwitch();
                return;
            } else if (mTrackSwitchState == TrackSwitchState.SWITCHING_TO_ORIGINAL) {
                String curVideoId = getPlayer() != null && getPlayer().getVideo() != null ? getPlayer().getVideo().videoId : null;
                if (!Helpers.equals(mTrackSwitchVideoId, curVideoId)) {
                    resetTrackSwitch();
                    return;
                }
                if (VotAudioTrackHelper.isSameFormat(track, mPendingOriginalFormat)
                        || VotAudioTrackHelper.isOriginalTrack(VotAudioTrackHelper.from(track))) {
                    onOriginalTrackActivated(track);
                    return;
                }
                return;
            } else if (mTrackSwitchState == TrackSwitchState.VOT_ACTIVE
                    || mTrackSwitchState == TrackSwitchState.STARTING_VOT) {
                if (mPendingOriginalFormat != null && !VotAudioTrackHelper.isSameFormat(track, mPendingOriginalFormat)) {
                    Log.i(TAG, "VOT manual: restore invalidated by user track change");
                    mUserManuallyChangedTrack = true;
                    mRestorableDubFormat = null;
                    mSavedAudioFormat = null;
                    if (VotAudioTrackHelper.isRussianLang(VotAudioTrackHelper.from(track).langCode)) {
                        disarmQuiet();
                        return;
                    }
                } else {
                    return;
                }
            } else if (mTrackSwitchState == TrackSwitchState.RESTORING_PREVIOUS_TRACK) {
                mTrackSwitchState = TrackSwitchState.IDLE;
                return;
            }
            tryApplyAutoTranslate(true);
        }
    }

    @Override
    public void onPlay() {
        if (mState == STATE_ACTIVE && mTranslationPlayer != null) {
            mTranslationPlayer.resume();
            duckMainAudio();
        }
    }

    @Override
    public void onPause() {
        if (mState == STATE_ACTIVE && mTranslationPlayer != null) {
            mTranslationPlayer.pause();
        }
    }

    @Override
    public void onSeekEnd() {
        if (mState == STATE_ACTIVE && mTranslationPlayer != null && mTranslationPlayer.isReady()) {
            long targetPos = getPlayer().getPositionMs();
            mLastSyncSeekTimestamp = System.currentTimeMillis();
            mTranslationPlayer.seekTo(targetPos);
        }
    }

    @Override
    public void onSpeedChanged(float speed) {
        if (mState == STATE_ACTIVE && mTranslationPlayer != null) {
            mTranslationPlayer.setPlaybackSpeed(speed);
        }
    }

    @Override
    public void onEngineReleased() {
        Log.d(TAG, "VOT reset reason: engine released");
        mTranslationSessionId++;
        Utils.removeCallbacks(mSyncRunnable);
        resetTrackSwitch();
        mSavedAudioFormat = null;
        if (mProgressOverlay != null) {
            mProgressOverlay.destroy();
            mProgressOverlay = null;
        }
        disarm();
    }

    @Override
    public void onViewDestroyed() {
        Log.d(TAG, "VOT reset reason: view destroyed");
        mTranslationSessionId++;
        Utils.removeCallbacks(mAutoTranslateRetryRunnable);
        Utils.removeCallbacks(mProgressTickRunnable);
        Utils.removeCallbacks(mSyncRunnable);
        cancelTranslationJob();
        releaseTranslationPlayer();
        restoreMainVolume();
        mProgressTimer.clear();
        mUserArmed = false;
        mArmed = false;
        mPendingVideoUrl = null;
        setState(STATE_OFF);
        resetTrackSwitch();
        mSavedAudioFormat = null;
        if (mProgressOverlay != null) {
            mProgressOverlay.destroy();
            mProgressOverlay = null;
        }
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId != ACTION_VOICE_TRANSLATE) {
            return;
        }
        if (buttonState == BTN_OFF) {
            armAndStart();
        } else {
            Log.i(TAG, "Trigger: manual stop");
            disarm();
            MessageHelpers.showMessage(getContext(), R.string.vot_disabled);
        }
    }

    @Override
    public void onButtonLongClicked(int buttonId, int buttonState) {
         if (buttonId == ACTION_VOICE_TRANSLATE) {
            AppDialogUtil.showVotMixDialog(getContext(), this::applyCurrentMix);
        }
    }

    private void armAndStart() {
        Log.i(TAG, "Trigger: manual start (Yandex authorized=" + votData().hasOAuthToken() + ")");
        TrackInfo info = resolveAudioInfo();
        Log.i(TAG, "VOT manual: selected audio=" + (info != null ? info.rawLabel : "null"));
        if (info != null && VotAudioTrackHelper.isRussianLang(info.langCode)) {
            List<FormatItem> formats = getAudioFormats();
            if (VotAudioTrackHelper.isRussianDubbedTrack(info, formats)) {
                FormatItem original = VotAudioTrackHelper.findBestOriginalForYandex(formats);
                if (original == null) {
                    MessageHelpers.showMessage(getContext(), R.string.vot_unable_to_determine_original);
                    return;
                }
                TrackInfo origInfo = VotAudioTrackHelper.from(original);
                Log.i(TAG, "VOT manual: original audio=" + (origInfo != null ? origInfo.rawLabel : "null"));
                Log.i(TAG, "VOT manual: russian dubbed track detected");
                showReplaceDubDialog(info.format != null ? info.format : (getPlayer() != null ? getPlayer().getAudioFormat() : null), original);
                return;
            } else {
                MessageHelpers.showMessage(getContext(), R.string.vot_skip_russian);
                return;
            }
        }
        mUserArmed = true;
        mArmed = true;
        if (progressOverlay() != null) {
            progressOverlay().showPreparing(getActivity());
        }
        startYandexTranslation();
    }

    private void showReplaceDubDialog(FormatItem currentDub, FormatItem original) {
        mTrackSwitchState = TrackSwitchState.WAITING_CONFIRMATION;
        String videoId = getPlayer() != null && getPlayer().getVideo() != null ? getPlayer().getVideo().videoId : null;
        AppDialogUtil.showVotReplaceDubDialog(
                getContext(),
                () -> {
                    if (getPlayer() == null || getPlayer().getVideo() == null || !Helpers.equals(videoId, getPlayer().getVideo().videoId)) {
                        resetTrackSwitch();
                        return;
                    }
                    mRestorableDubFormat = currentDub;
                    mPendingOriginalFormat = original;
                    mTrackSwitchVideoId = videoId;
                    mUserManuallyChangedTrack = false;
                    mTrackSwitchState = TrackSwitchState.SWITCHING_TO_ORIGINAL;
                    Log.i(TAG, "VOT manual: switching to original track");
                    if (progressOverlay() != null) {
                        progressOverlay().showPreparing(getActivity());
                    }
                    saveCurrentAudioFormatBeforeSwitch(original);
                    getPlayer().setFormat(original);
                    FormatItem active = getPlayer().getAudioFormat();
                    if (VotAudioTrackHelper.isSameFormat(active, original)) {
                        onOriginalTrackActivated(original);
                    } else {
                        Utils.removeCallbacks(mTrackSwitchTimeoutRunnable);
                        Utils.postDelayed(mTrackSwitchTimeoutRunnable, 5000L);
                    }
                },
                () -> {
                    Log.i(TAG, "VOT manual: replace dubbing cancelled by user");
                    resetTrackSwitch();
                    if (mProgressOverlay != null) {
                        mProgressOverlay.dismissImmediately();
                    }
                }
        );
    }

    private void onOriginalTrackActivated(FormatItem track) {
        Utils.removeCallbacks(mTrackSwitchTimeoutRunnable);
        Log.i(TAG, "VOT manual: original track active");
        mPendingOriginalFormat = track;
        mTrackSwitchState = TrackSwitchState.STARTING_VOT;
        VotAudioTrackHelper.TrackInfo info = VotAudioTrackHelper.from(track);
        Log.i(TAG, "VOT manual: starting Yandex VOT from=" + (info != null && info.langCode != null ? info.langCode : "unknown"));
        mUserArmed = true;
        mArmed = true;
        if (progressOverlay() != null) {
            progressOverlay().showPreparing(getActivity());
        }
        startYandexTranslation();
    }

    private void restorePreviousDubTrack() {
        if (!mUserManuallyChangedTrack && mRestorableDubFormat != null && getPlayer() != null) {
            Log.i(TAG, "VOT manual: restoring previous audio track");
            mTrackSwitchState = TrackSwitchState.RESTORING_PREVIOUS_TRACK;
            getPlayer().setFormat(mRestorableDubFormat);
        }
        mRestorableDubFormat = null;
        mPendingOriginalFormat = null;
        mSavedAudioFormat = null;
    }

    private void resetTrackSwitch() {
        Utils.removeCallbacks(mTrackSwitchTimeoutRunnable);
        mTrackSwitchState = TrackSwitchState.IDLE;
        mPendingOriginalFormat = null;
        mRestorableDubFormat = null;
        mTrackSwitchVideoId = null;
        mUserManuallyChangedTrack = false;
    }

    private void tryApplyAutoTranslate(boolean fromTrackChange) {
        if (getPlayer() == null || getPlayer().getVideo() == null) {
            return;
        }
        String videoId = getPlayer().getVideo().videoId;
        if (videoId != null && !videoId.equals(mCurrentVideoId)) {
            mCurrentVideoId = videoId;
            mAutoTranslateRetryCount = 0;
        }

        boolean autoEnabled = votData().isAutoTranslateEnabled();
        if (!autoEnabled && !mUserArmed) {
            return;
        }

        if (mState == STATE_ACTIVE || mState == STATE_PENDING) {
            return;
        }

        if (mTrackSwitchState != TrackSwitchState.IDLE) {
            return;
        }

        if (votData().isPreferYoutubeAutoDub()) {
            if (tryApplyYoutubeAutoDub(false)) {
                return;
            }
        }

        TrackInfo info = resolveAudioInfo();
        String langCode = info != null ? info.langCode : null;

        if (!VotAudioTrackHelper.isKnownLanguage(langCode)) {
            if ((autoEnabled || mUserArmed) && mAutoTranslateRetryCount < AUTO_TRANSLATE_MAX_RETRIES) {
                mAutoTranslateRetryCount++;
                Utils.removeCallbacks(mAutoTranslateRetryRunnable);
                Utils.postDelayed(mAutoTranslateRetryRunnable, AUTO_TRANSLATE_RETRY_MS);
                return;
            }
            Utils.removeCallbacks(mAutoTranslateRetryRunnable);
            if (autoEnabled) {
                Log.d(TAG, "VOT auto: skip, audio language unknown (video=%s)", videoId);
            }
            return;
        }

        Utils.removeCallbacks(mAutoTranslateRetryRunnable);
        mAutoTranslateRetryCount = 0;

        if (VotAudioTrackHelper.isRussianLang(langCode)) {
            if (mUserArmed) {
                disarmWithMessage(R.string.vot_skip_russian);
                mUserArmed = false;
            } else if (autoEnabled) {
                Log.i(TAG, "VOT auto: skip, current audio language=" + langCode + " (video=" + videoId + ")");
                if (mArmed || mState != STATE_OFF) {
                    disarmQuiet();
                }
            }
            return;
        }

        if (VotAudioTrackHelper.isExplicitNonRussian(info)) {
            if (autoEnabled) {
                Log.i(TAG, "VOT auto: start, current audio language=" + langCode + " (video=" + videoId + ")");
            }
            if (!mArmed || (mState == STATE_OFF && mTranslationDisposable == null)) {
                mArmed = true;
                startYandexTranslation();
            }
            return;
        }

        if (autoEnabled) {
            Log.i(TAG, "VOT auto: skip, audio language=" + langCode + " (video=" + videoId + ")");
        }
    }

    /** @return true if YouTube dub was applied and Yandex should not run */
    private boolean tryApplyYoutubeAutoDub(boolean showToast) {
        if (mUserArmed || mTrackSwitchState != TrackSwitchState.IDLE) {
            return false;
        }
        FormatItem dub = VotAudioTrackHelper.findYoutubeRussianAutoDub(getAudioFormats());
        if (dub == null) {
            return false;
        }
        saveCurrentAudioFormatBeforeSwitch(dub);
        getPlayer().setFormat(dub);
        mArmed = false;
        mUserArmed = false;
        cancelTranslationJob();
        releaseTranslationPlayer();
        restoreMainVolume();
        setState(STATE_OFF);
        if (mProgressOverlay != null) {
            mProgressOverlay.dismissImmediately();
        }
        if (showToast) {
            MessageHelpers.showMessage(getContext(), R.string.vot_using_youtube_dub);
        }
        return true;
    }

    private void startYandexTranslation() {
        if (getPlayer() == null || getPlayer().getVideo() == null) {
            MessageHelpers.showMessage(getContext(), R.string.vot_error_no_video);
            return;
        }

        ensureOriginalAudioForYandex();

        TrackInfo info = resolveAudioInfo();
        if (VotAudioTrackHelper.isRussianOriginal(info)) {
            disarmWithMessage(R.string.vot_skip_russian);
            return;
        }

        String videoUrl = getPlayer().getVideo().videoId != null
                ? "https://www.youtube.com/watch?v=" + getPlayer().getVideo().videoId
                : null;
        if (videoUrl == null) {
            MessageHelpers.showMessage(getContext(), R.string.vot_error_no_video);
            return;
        }

        if (mTranslationDisposable != null && !mTranslationDisposable.isDisposed()
                && videoUrl.equals(mPendingVideoUrl)) {
            return;
        }

        cancelTranslationJob();
        mTranslationSessionId++;
        mPendingToastShown = false;
        mPendingVideoUrl = videoUrl;
        mRequestStartTimestamp = SystemClock.elapsedRealtime();
        mLastBackendPendingTimestamp = mRequestStartTimestamp;
        mProgressTimer.start(mRequestStartTimestamp);
        setState(STATE_PENDING);

        Utils.removeCallbacks(mProgressTickRunnable);
        Utils.postDelayed(mProgressTickRunnable, PROGRESS_TICK_INTERVAL_MS);

        long durationSec = Math.max(1, getPlayer().getDurationMs() / 1000);
        Log.i(TAG, "VOT request started: url=" + videoUrl + ", duration=" + durationSec + "s, userArmed=" + mUserArmed);
        mTranslationDisposable = votClient().observeTranslation(videoUrl, durationSec)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::onVotProgress,
                        this::onVotError
                );
    }

    private void ensureOriginalAudioForYandex() {
        if (getPlayer() == null) {
            return;
        }
        TrackInfo current = resolveAudioInfo();
        if (VotAudioTrackHelper.isOriginalTrack(current) && !VotAudioTrackHelper.isYoutubeAutoDub(current)) {
            return;
        }
        FormatItem original = VotAudioTrackHelper.findBestOriginalForYandex(getAudioFormats());
        if (original == null) {
            return;
        }
        FormatItem active = getPlayer().getAudioFormat();
        if (!VotAudioTrackHelper.isSameFormat(active, original)) {
            saveCurrentAudioFormatBeforeSwitch(original);
            getPlayer().setFormat(original);
        }
    }

    private void saveCurrentAudioFormatBeforeSwitch(FormatItem target) {
        if (getPlayer() == null || target == null || mSavedAudioFormat != null) {
            return;
        }
        FormatItem current = getPlayer().getAudioFormat();
        if (current != null && !VotAudioTrackHelper.isSameFormat(current, target)) {
            mSavedAudioFormat = current;
        }
    }

    private void restoreSavedAudioFormat() {
        if (!mUserManuallyChangedTrack && mRestorableDubFormat != null && getPlayer() != null) {
            Log.d(TAG, "VOT manual: restoring previous audio track");
            mTrackSwitchState = TrackSwitchState.RESTORING_PREVIOUS_TRACK;
            getPlayer().setFormat(mRestorableDubFormat);
            mRestorableDubFormat = null;
            mPendingOriginalFormat = null;
            mSavedAudioFormat = null;
            return;
        }
        if (mSavedAudioFormat != null && getPlayer() != null) {
            getPlayer().setFormat(mSavedAudioFormat);
            mSavedAudioFormat = null;
        }
        mRestorableDubFormat = null;
        mPendingOriginalFormat = null;
    }

    private TrackInfo resolveAudioInfo() {
        if (getPlayer() == null) {
            return VotAudioTrackHelper.from(null);
        }
        return VotAudioTrackHelper.resolveCurrent(getPlayer().getAudioFormat(), getAudioFormats());
    }

    private List<FormatItem> getAudioFormats() {
        return getPlayer() != null ? getPlayer().getAudioFormats() : null;
    }

    private void onVotProgress(VotProgress progress) {
        if (getPlayer() == null || getPlayer().getVideo() == null) {
            return;
        }
        String currentUrl = "https://www.youtube.com/watch?v=" + getPlayer().getVideo().videoId;
        if (mPendingVideoUrl != null && !mPendingVideoUrl.equals(currentUrl)) {
            return;
        }
        if (!mArmed) {
            return;
        }

        mLastBackendPendingTimestamp = SystemClock.elapsedRealtime();

        switch (progress.type) {
            case VotProgress.TYPE_LIVELY_FALLBACK:
                Log.i(TAG, "VOT: lively voice unavailable, falling back to standard voice");
                MessageHelpers.showMessage(getContext(), R.string.vot_lively_fallback_standard);
                break;
            case VotProgress.TYPE_WAITING:
                mPendingEtaSec = progress.remainingTimeSec;
                mProgressTimer.reconcileEta(progress.remainingTimeSec, mLastBackendPendingTimestamp);
                if (progress.remainingTimeSec > 0) {
                    Log.d(TAG, "VOT ETA received: %ds, expected ready at +%ds", progress.remainingTimeSec, progress.remainingTimeSec);
                } else {
                    Log.d(TAG, "VOT pending (status=%d, remainingTime=%d)", progress.status, progress.remainingTimeSec);
                }
                setState(STATE_PENDING);
                mProgressTickRunnable.run();
                break;
            case VotProgress.TYPE_READY:
                Log.d(TAG, "VOT translation ready (audio URL received=%b)", progress.audioUrl != null);
                Utils.removeCallbacks(mProgressTickRunnable);
                if (progress.audioUrl != null) {
                    prepareAndStartTranslationAudio(progress.audioUrl);
                }
                break;
            case VotProgress.TYPE_FAILED:
                Log.e(TAG, "VOT translation failed: %s", progress.message);
                Utils.removeCallbacks(mProgressTickRunnable);
                if (mUserArmed && progressOverlay() != null) {
                    progressOverlay().showError(getActivity());
                }
                handleTranslationError(VotErrorCategory.fromMarker(progress.message));
                break;
        }
    }

    private void onVotError(Throwable e) {
        Log.e(TAG, "VOT error callback: %s", e != null ? e.getMessage() : "unknown");
        Utils.removeCallbacks(mProgressTickRunnable);
        if (mUserArmed && progressOverlay() != null) {
            progressOverlay().showError(getActivity());
        }
        VotErrorCategory category;
        if (e instanceof VotHttpException) {
            int code = ((VotHttpException) e).getStatusCode();
            if (code == 401) {
                votData().markOAuthRejected();
                Log.w(TAG, "VOT: onVotError HTTP 401 — OAuth rejected (authState→REJECTED)");
            }
            category = VotErrorCategory.fromHttpCode(code);
        } else {
            category = VotErrorCategory.NETWORK_ERROR;
        }
        handleTranslationError(category);
    }

    private void prepareAndStartTranslationAudio(String audioUrl) {
        if (getPlayer() == null || getPlayer().getVideo() == null) {
            return;
        }
        final int sessionId = ++mTranslationSessionId;
        final String videoId = getPlayer().getVideo().videoId;

        if (mUserArmed && progressOverlay() != null) {
            progressOverlay().showStarting(getActivity());
        }

        releaseTranslationPlayer();
        mTranslationPlayer = new TranslationAudioPlayer(getContext());

        float speed = getPlayer().getSpeed();
        mTranslationPlayer.prepare(sessionId, audioUrl, speed > 0 ? speed : 1f, new TranslationAudioPlayer.PlaybackCallback() {
            @Override
            public void onPrepared() {
                if (sessionId != mTranslationSessionId || getPlayer() == null || getPlayer().getVideo() == null
                        || !Helpers.equals(videoId, getPlayer().getVideo().videoId)) {
                    Log.w(TAG, "VOT_AUDIO session=%d prepared but session/video changed, ignoring", sessionId);
                    return;
                }

                long targetPositionMs = getPlayer().getPositionMs();
                Log.i(TAG, "VOT_AUDIO session=" + sessionId + " initial_seek_target target_position=" + targetPositionMs);

                if (targetPositionMs <= 200) {
                    onInitialSyncComplete(sessionId, videoId);
                } else {
                    mTranslationPlayer.seekTo(targetPositionMs);
                }
            }

            @Override
            public void onSeekProcessed() {
                if (sessionId != mTranslationSessionId || getPlayer() == null || getPlayer().getVideo() == null
                        || !Helpers.equals(videoId, getPlayer().getVideo().videoId)) {
                    Log.w(TAG, "VOT_AUDIO session=" + sessionId + " seek processed but session/video changed, ignoring");
                    return;
                }

                if (!mTranslationPlayer.isPlaying()) {
                    onInitialSyncComplete(sessionId, videoId);
                }
            }

            @Override
            public void onError(Exception error) {
                if (sessionId == mTranslationSessionId) {
                    Utils.post(() -> onTranslationPlaybackError(error));
                }
            }
        });
    }

    private void onInitialSyncComplete(int sessionId, String videoId) {
        if (sessionId != mTranslationSessionId || getPlayer() == null || getPlayer().getVideo() == null
                || !Helpers.equals(videoId, getPlayer().getVideo().videoId) || mTranslationPlayer == null) {
            return;
        }

        if (mTranslationPlayer.isPlaying()) {
            Log.i(TAG, "VOT_AUDIO session=" + sessionId + " duplicate_play_ignored");
            return;
        }

        duckMainAudio();

        float volume = votData().getTranslationVolumeMultiplier();
        boolean mainPlaying = getPlayer().isPlaying();

        mTranslationPlayer.startPlayback(volume);

        if (!mainPlaying) {
            mTranslationPlayer.pause();
            Log.i(TAG, "VOT_AUDIO session=" + sessionId + " main player paused, waiting for resume");
        }

        setState(STATE_ACTIVE);
        if (mTrackSwitchState == TrackSwitchState.STARTING_VOT) {
            mTrackSwitchState = TrackSwitchState.VOT_ACTIVE;
        }

        if (mUserArmed && progressOverlay() != null) {
            progressOverlay().showReady(getActivity());
        }

        mLastSyncSeekTimestamp = System.currentTimeMillis();
        Utils.removeCallbacks(mSyncRunnable);
        mProgressTimer.clear();
        Utils.postDelayed(mSyncRunnable, INITIAL_SYNC_GRACE_PERIOD_MS);
    }

    private void syncTranslationPositionIfNeeded() {
        if (mTranslationPlayer == null || getPlayer() == null || !mTranslationPlayer.isReady()
                || !mTranslationPlayer.isPlaying() || !getPlayer().isPlaying()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - mLastSyncSeekTimestamp < SYNC_SEEK_COOLDOWN_MS) {
            return;
        }

        long mainPos = getPlayer().getPositionMs();
        long transPos = mTranslationPlayer.getPositionMs();
        long delta = Math.abs(mainPos - transPos);

        Log.i(TAG, "VOT_AUDIO session=" + mTranslationSessionId + " sync video_pos=" + mainPos + " trans_pos=" + transPos + " delta=" + delta + " state=" + stateToString(mState));

        if (delta > SYNC_THRESHOLD_MS) {
            Log.i(TAG, "VOT_AUDIO session=" + mTranslationSessionId + " drift correction seek to " + mainPos + " (delta=" + delta + ")");
            mLastSyncSeekTimestamp = now;
            mTranslationPlayer.seekTo(mainPos);
        }
    }

	private void applyCurrentMix() {
		if (getPlayer() != null && mState == STATE_ACTIVE) {
			getPlayer().setVolume(votData().getOriginalVolumeMultiplier());
		}
		
		if (mTranslationPlayer != null) {
			mTranslationPlayer.setVolume(votData().getTranslationVolumeMultiplier());
        }
    }

    private void duckMainAudio() {
        if (getPlayer() == null || mIsAudioDucked) {
            return;
        }
        mSavedMainVolume = getPlayer().getVolume();
        getPlayer().setVolume(votData().getOriginalVolumeMultiplier());
        mIsAudioDucked = true;
        Log.i(TAG, "VOT main audio ducked");
    }

    private void restoreMainVolume() {
        if (getPlayer() != null && mIsAudioDucked) {
            getPlayer().setVolume(mSavedMainVolume);
            mIsAudioDucked = false;
            Log.i(TAG, "VOT main audio restored");
        }
    }

    private void cancelTranslationJob() {
        Utils.removeCallbacks(mProgressTickRunnable);
        mProgressTimer.clear();
        if (mTranslationDisposable != null && !mTranslationDisposable.isDisposed()) {
            mTranslationDisposable.dispose();
        }
        mTranslationDisposable = null;
    }

    private void releaseTranslationPlayer() {
        Utils.removeCallbacks(mSyncRunnable);
        if (mTranslationPlayer != null) {
            mTranslationPlayer.release();
            mTranslationPlayer = null;
        }
    }

    private void disarm() {
        disarmQuiet();
    }

    private void disarmQuiet() {
        Log.d(TAG, "VOT reset reason: disarmQuiet");
        mTranslationSessionId++;
        mUserArmed = false;
        mArmed = false;
        Utils.removeCallbacks(mAutoTranslateRetryRunnable);
        Utils.removeCallbacks(mProgressTickRunnable);
        Utils.removeCallbacks(mResetErrorButtonRunnable);
        Utils.removeCallbacks(mSyncRunnable);
        cancelTranslationJob();
        releaseTranslationPlayer();
        restoreMainVolume();
        restoreSavedAudioFormat();
        if (mProgressOverlay != null) {
            mProgressOverlay.dismissImmediately();
        }
        setState(STATE_OFF);
        mRequestStartTimestamp = 0;
        mLastBackendPendingTimestamp = 0;
        mPendingEtaSec = 0;
        mPendingToastShown = false;
        mPendingVideoUrl = null;
    }

    private void disarmWithMessage(int msgResId) {
        disarm();
        MessageHelpers.showMessage(getContext(), msgResId);
    }

    private void showBriefErrorButtonState() {
        Utils.removeCallbacks(mResetErrorButtonRunnable);
        updateVoiceButton(BTN_ERROR);
        Utils.postDelayed(mResetErrorButtonRunnable, 3000L);
    }

    private void onTranslationPlaybackError(Exception e) {
        Log.e(TAG, "Translation playback error: %s", e != null ? e.getMessage() : "unknown");
        if (mState == STATE_OFF) {
            return;
        }
        boolean wasUserArmed = mUserArmed;
        disarmQuiet();
        if (wasUserArmed) {
            showBriefErrorButtonState();
            MessageHelpers.showMessage(getContext(), R.string.vot_error_playback);
        }
    }

    private void onTranslationTimeout() {
        Log.w(TAG, "VOT translation timeout (video=%s)", mCurrentVideoId);
        Utils.removeCallbacks(mProgressTickRunnable);
        if (mUserArmed && progressOverlay() != null) {
            progressOverlay().showTimeout(getActivity());
        }
        boolean wasUserArmed = mUserArmed;
        disarmQuiet();
        if (wasUserArmed) {
            showBriefErrorButtonState();
            MessageHelpers.showMessage(getContext(), R.string.vot_error_timeout);
        }
    }

    /**
     * Обрабатывает ошибку перевода по типизированной категории.
     *
     * Правила:
     * - AUTH_REJECTED: токен уже помечен как REJECTED в VotData (в VotClient или onVotError).
     *   Lively выключается. Если активен mUserArmed, показывается сообщение об ошибке авторизации.
     *   Токен НЕ удаляется — пользователь может исправить его или авторизоваться снова.
     * - PROTOCOL_SESSION_REQUIRED: показывается общая ошибка сети (сессия восстанавливается
     *   автоматически в следующем цикле — этот путь достигается только если все ретраи исчерпаны).
     * - TIMEOUT: показывается строка vot_error_timeout.
     * - RATE_LIMITED / SERVER_UNAVAILABLE / NETWORK_ERROR: показывается vot_error_network.
     * - Остальные: vot_error_generic.
     */
    private void handleTranslationError(VotErrorCategory category) {
        Log.e(TAG, "Translation error category: %s", category);
        Utils.removeCallbacks(mProgressTickRunnable);
        boolean wasUserArmed = mUserArmed;

        if (category == VotErrorCategory.AUTH_REJECTED) {
            Log.w(TAG, "Yandex OAuth rejected — Lively Voice disabled, token preserved for user review");
            // Токен помечен как REJECTED уже в VotClient или onVotError.
            // Выключаем Lively (он и так выключится через markOAuthRejected),
            // но НЕ удаляем токен из SharedPreferences.
            votData().setLivelyVoiceEnabled(false);
            votClient().resetSession();
            disarmQuiet();
            if (wasUserArmed) {
                showBriefErrorButtonState();
                MessageHelpers.showMessage(getContext(), R.string.vot_error_auth_required);
            }
            return;
        }

        disarmQuiet();
        if (wasUserArmed) {
            showBriefErrorButtonState();
            switch (category) {
                case TIMEOUT:
                    MessageHelpers.showMessage(getContext(), R.string.vot_error_timeout);
                    break;
                case RATE_LIMITED:
                case SERVER_UNAVAILABLE:
                case NETWORK_ERROR:
                    MessageHelpers.showMessage(getContext(), R.string.vot_error_network);
                    break;
                case PROTOCOL_SESSION_REQUIRED:
                    // Сессия не удалась после всех ретраев — показываем сетевую ошибку
                    MessageHelpers.showMessage(getContext(), R.string.vot_error_network);
                    break;
                default:
                    MessageHelpers.showMessage(getContext(), R.string.vot_error_generic);
                    break;
            }
        }
    }
    private void setState(int state) {
        if (mState != state) {
            Log.d(TAG, "State transition: " + stateToString(mState) + " -> " + stateToString(state));
        }
        mState = state;
        int btnIndex;
        switch (state) {
            case STATE_PENDING:
                btnIndex = BTN_PENDING;
                break;
            case STATE_ACTIVE:
                btnIndex = BTN_ON;
                break;
            default:
                btnIndex = BTN_OFF;
                break;
        }
        updateVoiceButton(btnIndex);
    }

    private static String stateToString(int state) {
        switch (state) {
            case STATE_OFF:
                return "OFF";
            case STATE_PENDING:
                return "PENDING";
            case STATE_ACTIVE:
                return "ACTIVE";
            default:
                return "UNKNOWN";
        }
    }

    private void updateVoiceButton(int index) {
        if (getPlayer() == null) {
            return;
        }
        getPlayer().setButtonState(ACTION_VOICE_TRANSLATE, index);
        if (index == BTN_PENDING) {
            getPlayer().updateVoiceTranslatePendingEta(mPendingEtaSec);
        }
    }
}

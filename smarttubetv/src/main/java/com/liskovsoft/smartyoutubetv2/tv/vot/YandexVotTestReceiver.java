/*
 * Copyright (C) 2026 SmartTube VOX
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.tv.vot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.SmartTubeYandexVotAudioSourceProvider;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApi;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotAudioUploadTransport;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotOrchestrator;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotPlaybackAdapter;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotShadowController;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotState;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

/**
 * Controlled developer-only test entry point for exercising the isolated Yandex VOT backend
 * and translation audio playback on Android emulator without touching the production player UI or live VOX flow.
 *
 * Trigger via ADB:
 * adb shell am broadcast -a com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT --es action start --es videoId dQw4w9WgXcQ
 * adb shell am broadcast -a com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT --es action pause
 * adb shell am broadcast -a com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT --es action resume
 * adb shell am broadcast -a com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT --es action seek --el positionMs 45000
 * adb shell am broadcast -a com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT --es action cancel
 * adb shell am broadcast -a com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT --es action status
 */
public class YandexVotTestReceiver extends BroadcastReceiver {
    public static final String ACTION_TEST_YANDEX_VOT = "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT";
    private static final String TAG = "YANDEX_VOT_TEST";

    private static volatile YandexVotOrchestrator sOrchestrator;
    private static volatile YandexVotPlaybackAdapter sPlaybackAdapter;
    private static volatile YandexVotState sLastState;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_TEST_YANDEX_VOT.equals(intent.getAction())) {
            return;
        }

        String action = intent.getStringExtra("action");
        if (action == null || action.isEmpty() || "start".equalsIgnoreCase(action)) {
            handleStart(context, intent);
        } else if ("cancel".equalsIgnoreCase(action) || "stop".equalsIgnoreCase(action)) {
            handleCancel();
        } else if ("pause".equalsIgnoreCase(action)) {
            handlePause();
        } else if ("resume".equalsIgnoreCase(action)) {
            handleResume();
        } else if ("seek".equalsIgnoreCase(action)) {
            long pos = intent.getLongExtra("positionMs", 0);
            handleSeek(pos);
        } else if ("sync".equalsIgnoreCase(action)) {
            long pos = intent.getLongExtra("positionMs", 0);
            handleSync(pos);
        } else if ("video_switch".equalsIgnoreCase(action)) {
            handleVideoSwitch();
        } else if ("enableShadow".equalsIgnoreCase(action)) {
            handleSetShadowEnabled(true, intent.getBooleanExtra("autoStart", false));
        } else if ("disableShadow".equalsIgnoreCase(action)) {
            handleSetShadowEnabled(false, false);
        } else if ("setShadow".equalsIgnoreCase(action)) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            boolean autoStart = intent.getBooleanExtra("autoStart", false);
            handleSetShadowEnabled(enabled, autoStart);
        } else if ("startShadow".equalsIgnoreCase(action) || "startCurrent".equalsIgnoreCase(action)) {
            handleStartShadow(intent);
        } else if ("stopShadow".equalsIgnoreCase(action) || "cancelShadow".equalsIgnoreCase(action)) {
            handleStopShadow();
        } else if ("enableNewBackend".equalsIgnoreCase(action)) {
            VoiceTranslateController.setNewYandexBackendEnabled(true);
            Log.i(TAG, "New Yandex VOT backend ENABLED for user flow");
        } else if ("disableNewBackend".equalsIgnoreCase(action)) {
            VoiceTranslateController.setNewYandexBackendEnabled(false);
            Log.i(TAG, "New Yandex VOT backend DISABLED for user flow");
        } else if ("setBackendFailure".equalsIgnoreCase(action)) {
            boolean fail = intent.getBooleanExtra("fail", true);
            VoiceTranslateController.setInjectNewBackendFailure(fail);
            Log.i(TAG, "New Yandex VOT backend failure injection set to: " + fail);
        } else if ("backendStatus".equalsIgnoreCase(action)) {
            handleBackendStatus();
        } else if ("status".equalsIgnoreCase(action)) {
            handleStatus();
        } else {
            Log.w(TAG, "Unknown action: " + action);
        }
    }

    private void handleStart(final Context context, Intent intent) {
        String videoId = intent.getStringExtra("videoId");
        String videoUrl = intent.getStringExtra("videoUrl");
        if (videoId == null && videoUrl != null) {
            videoId = extractVideoId(videoUrl);
        }
        if (videoId == null) {
            videoId = "dQw4w9WgXcQ";
        }
        if (videoUrl == null) {
            videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        }

        double duration = intent.getDoubleExtra("duration", 212.0);
        String sourceLang = intent.getStringExtra("sourceLang");
        if (sourceLang == null) sourceLang = "en";
        String targetLang = intent.getStringExtra("targetLang");
        if (targetLang == null) targetLang = "ru";
        String videoTitle = intent.getStringExtra("videoTitle");
        if (videoTitle == null) videoTitle = "Test Video";
        boolean useLively = intent.getBooleanExtra("useLively", false);
        boolean useOAuth = intent.getBooleanExtra("useOAuth", false);
        boolean playAudio = intent.getBooleanExtra("playAudio", true);
        final long initialPositionMs = intent.getLongExtra("positionMs", 0);

        String oauthToken = null;
        if (useOAuth || useLively) {
            oauthToken = intent.getStringExtra("oauthToken");
            if (oauthToken == null || oauthToken.isEmpty()) {
                oauthToken = VotData.instance(context).getOAuthToken();
            }
        }

        Log.i(TAG, "Starting test request: videoId=" + videoId
                + " duration=" + duration
                + " sourceLang=" + sourceLang
                + " targetLang=" + targetLang
                + " useLively=" + useLively
                + " hasOAuth=" + (oauthToken != null && !oauthToken.isEmpty())
                + " playAudio=" + playAudio
                + " initialPositionMs=" + initialPositionMs);

        synchronized (YandexVotTestReceiver.class) {
            if (sOrchestrator != null) {
                sOrchestrator.cancel();
            }
            if (sPlaybackAdapter != null) {
                sPlaybackAdapter.stop();
            }

            SmartTubeYandexVotAudioSourceProvider sourceProvider = new SmartTubeYandexVotAudioSourceProvider(
                    new SmartTubeYandexVotAudioSourceProvider.FormatInfoProvider() {
                        @Override
                        public MediaItemFormatInfo getFormatInfo(@Nullable String vid, @NonNull String vurl) throws Exception {
                            String id = vid != null ? vid : extractVideoId(vurl);
                            Log.i(TAG, "Resolving format info for videoId=" + id);
                            ServiceManager service = YouTubeServiceManager.instance();
                            if (service != null && service.getMediaItemService() != null) {
                                MediaItemFormatInfo info = service.getMediaItemService().getFormatInfo(id);
                                if (info != null) {
                                    Log.i(TAG, "Format info resolved: adaptive_formats="
                                            + (info.getAdaptiveFormats() != null ? info.getAdaptiveFormats().size() : 0));
                                } else {
                                    Log.w(TAG, "Format info resolved to null for videoId=" + id);
                                }
                                return info;
                            }
                            Log.w(TAG, "MediaItemService unavailable");
                            return null;
                        }
                    }
            );

            sOrchestrator = new YandexVotOrchestrator(
                    YandexVotApi.DEFAULT,
                    sourceProvider,
                    YandexVotAudioUploadTransport.DEFAULT
            );

            if (playAudio) {
                YandexVotPlaybackAdapter.AudioDuckingBridge duckingBridge = new YandexVotPlaybackAdapter.AudioDuckingBridge() {
                    private Float savedVolume = null;

                    @Override
                    public void duckOriginalAudio() {
                        try {
                            PlaybackPresenter presenter = PlaybackPresenter.instance(context);
                            if (presenter != null && presenter.getPlayer() != null) {
                                if (savedVolume == null) {
                                    savedVolume = presenter.getPlayer().getVolume();
                                }
                                presenter.getPlayer().setVolume(VotData.instance(context).getOriginalVolumeMultiplier());
                                Log.i(TAG, "Main player original audio ducked");
                            }
                        } catch (Throwable t) {
                            Log.d(TAG, "Main player duck fallback: " + t.getMessage());
                        }
                    }

                    @Override
                    public void restoreOriginalAudio() {
                        try {
                            PlaybackPresenter presenter = PlaybackPresenter.instance(context);
                            if (presenter != null && presenter.getPlayer() != null && savedVolume != null) {
                                presenter.getPlayer().setVolume(savedVolume);
                                savedVolume = null;
                                Log.i(TAG, "Main player original audio restored");
                            }
                        } catch (Throwable t) {
                            Log.d(TAG, "Main player restore fallback: " + t.getMessage());
                        }
                    }

                    @Override
                    public float getTranslationVolume() {
                        return VotData.instance(context).getTranslationVolumeMultiplier();
                    }

                    @Override
                    public long getCurrentVideoPositionMs() {
                        try {
                            PlaybackPresenter presenter = PlaybackPresenter.instance(context);
                            if (presenter != null && presenter.getPlayer() != null && presenter.getPlayer().getVideo() != null) {
                                return presenter.getPlayer().getPositionMs();
                            }
                        } catch (Throwable ignored) {}
                        return initialPositionMs;
                    }

                    @Override
                    public boolean isMainVideoPlaying() {
                        try {
                            PlaybackPresenter presenter = PlaybackPresenter.instance(context);
                            if (presenter != null && presenter.getPlayer() != null && presenter.getPlayer().getVideo() != null) {
                                return presenter.getPlayer().isPlaying();
                            }
                        } catch (Throwable ignored) {}
                        return true;
                    }
                };

                sPlaybackAdapter = new YandexVotPlaybackAdapter(
                        YandexVotPlaybackAdapter.createDefaultPlayerFactory(context),
                        duckingBridge
                );
            } else {
                sPlaybackAdapter = null;
            }

            sOrchestrator.setListener(new YandexVotOrchestrator.Listener() {
                @Override
                public void onStateChanged(@NonNull YandexVotState state) {
                    sLastState = state;
                    logState(state);
                    if (sPlaybackAdapter != null) {
                        sPlaybackAdapter.onStateChanged(state);
                    }
                }
            });

            YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                    videoId,
                    videoUrl,
                    duration,
                    sourceLang,
                    targetLang,
                    videoTitle,
                    useLively,
                    oauthToken
            );

            sOrchestrator.startTranslation(params);
        }
    }

    private void handlePause() {
        synchronized (YandexVotTestReceiver.class) {
            if (sPlaybackAdapter != null) {
                Log.i(TAG, "Pausing test playback");
                sPlaybackAdapter.onPause();
            } else {
                Log.i(TAG, "No active playback adapter to pause");
            }
        }
    }

    private void handleResume() {
        synchronized (YandexVotTestReceiver.class) {
            if (sPlaybackAdapter != null) {
                Log.i(TAG, "Resuming test playback");
                sPlaybackAdapter.onPlay();
            } else {
                Log.i(TAG, "No active playback adapter to resume");
            }
        }
    }

    private void handleSeek(long positionMs) {
        synchronized (YandexVotTestReceiver.class) {
            if (sPlaybackAdapter != null) {
                Log.i(TAG, "Seeking test playback to " + positionMs + "ms");
                sPlaybackAdapter.onSeek(positionMs);
            } else {
                Log.i(TAG, "No active playback adapter to seek");
            }
        }
    }

    private void handleSync(long positionMs) {
        synchronized (YandexVotTestReceiver.class) {
            if (sPlaybackAdapter != null) {
                Log.i(TAG, "Sync check for position " + positionMs + "ms");
                sPlaybackAdapter.checkPeriodicSync(positionMs);
            }
        }
    }

    private void handleVideoSwitch() {
        synchronized (YandexVotTestReceiver.class) {
            if (sOrchestrator != null) {
                sOrchestrator.cancel();
            }
            if (sPlaybackAdapter != null) {
                Log.i(TAG, "Video switch triggered");
                sPlaybackAdapter.onVideoChanged();
            }
        }
    }

    private void handleCancel() {
        synchronized (YandexVotTestReceiver.class) {
            if (sOrchestrator != null) {
                Log.i(TAG, "Cancelling test request");
                sOrchestrator.cancel();
            }
            if (sPlaybackAdapter != null) {
                sPlaybackAdapter.stop();
            }
        }
    }

    private void handleSetShadowEnabled(boolean enabled, boolean autoStart) {
        YandexVotShadowController shadow = YandexVotShadowController.instance();
        if (shadow != null) {
            shadow.setEnabled(enabled);
            shadow.setAutoStartOnNewVideo(autoStart);
            Log.i(TAG, "ShadowController configured: enabled=" + enabled + " autoStart=" + autoStart);
        } else {
            Log.w(TAG, "ShadowController instance is null (player not initialized yet)");
        }
    }

    private void handleStartShadow(Intent intent) {
        YandexVotShadowController shadow = YandexVotShadowController.instance();
        if (shadow != null) {
            String token = intent.getStringExtra("oauthToken");
            Log.i(TAG, "Starting shadow translation via ShadowController (hasCustomToken=" + (token != null) + ")");
            shadow.startCurrentTranslation(token);
        } else {
            Log.w(TAG, "ShadowController instance is null (player not initialized yet)");
        }
    }

    private void handleStopShadow() {
        YandexVotShadowController shadow = YandexVotShadowController.instance();
        if (shadow != null) {
            Log.i(TAG, "Stopping shadow translation via ShadowController");
            shadow.stopTranslation();
        } else {
            Log.w(TAG, "ShadowController instance is null (player not initialized yet)");
        }
    }

    private void handleStatus() {
        synchronized (YandexVotTestReceiver.class) {
            if (sLastState != null) {
                Log.i(TAG, "Current test status: " + sLastState.getStatus());
                logState(sLastState);
            } else {
                Log.i(TAG, "Current test status: IDLE (no standalone test recorded)");
            }
            if (sPlaybackAdapter != null) {
                Log.i(TAG, "Standalone playback adapter: isPrepared=" + sPlaybackAdapter.isPrepared()
                        + " isPlaying=" + sPlaybackAdapter.isPlaying()
                        + " isDucked=" + sPlaybackAdapter.isAudioDucked());
            }

            YandexVotShadowController shadow = YandexVotShadowController.instance();
            if (shadow != null) {
                YandexVotState shadowState = shadow.getLastState();
                YandexVotPlaybackAdapter shadowAdapter = shadow.getPlaybackAdapter();
                Log.i(TAG, "ShadowController status: enabled=" + shadow.isEnabled()
                        + " autoStart=" + shadow.isAutoStartOnNewVideo()
                        + " state=" + (shadowState != null ? shadowState.getStatus() : "IDLE")
                        + " adapterPrepared=" + (shadowAdapter != null && shadowAdapter.isPrepared())
                        + " adapterPlaying=" + (shadowAdapter != null && shadowAdapter.isPlaying())
                        + " adapterDucked=" + (shadowAdapter != null && shadowAdapter.isAudioDucked()));
                if (shadowState != null) {
                    logState(shadowState);
                }
            } else {
                Log.i(TAG, "ShadowController: instance null (player not initialized)");
            }

            handleBackendStatus();
        }
    }

    private void handleBackendStatus() {
        boolean flagEnabled = VoiceTranslateController.isNewYandexBackendEnabled();
        boolean userFlowActive = VoiceTranslateController.isUserFlowActive();
        VoiceTranslateController controller = VoiceTranslateController.instance();
        boolean isNewActive = controller != null && controller.isNewBackendActive();
        boolean fallbackTriggered = controller != null && controller.isFallbackTriggered();
        Log.i(TAG, "BackendStatus: flag_new_backend=" + (flagEnabled ? "ON" : "OFF")
                + " user_flow_active=" + userFlowActive
                + " active_backend=" + (isNewActive ? "NEW" : (userFlowActive ? "OLD" : "NONE"))
                + " fallback_triggered=" + fallbackTriggered);
    }

    private static void logState(@NonNull YandexVotState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(state.getStatus());
        sb.append(" gen=").append(state.getGenerationId());
        if (state.getVideoId() != null) {
            sb.append(" videoId=").append(state.getVideoId());
        }
        if (state.getTranslationId() != null) {
            sb.append(" translation_id=").append(state.getTranslationId());
        }
        if (state.getAudioUrl() != null) {
            sb.append(" audio_url_present=true");
        }
        if (state.getRemainingSeconds() >= 0) {
            sb.append(" remaining_sec=").append(state.getRemainingSeconds());
        }
        if (state.getCurrentPart() >= 0 && state.getTotalParts() > 0) {
            sb.append(" upload_part=").append(state.getCurrentPart() + 1).append("/").append(state.getTotalParts());
        }
        if (state.getErrorMessage() != null) {
            sb.append(" error_msg=").append(state.getErrorMessage());
        }
        if (state.getErrorCategory() != null) {
            sb.append(" error_category=").append(state.getErrorCategory());
        }
        Log.i(TAG, sb.toString());
    }

    @Nullable
    private static String extractVideoId(@NonNull String url) {
        try {
            Uri uri = Uri.parse(url);
            String v = uri.getQueryParameter("v");
            if (v != null && !v.isEmpty()) {
                return v;
            }
            String lastPath = uri.getLastPathSegment();
            if (lastPath != null && !lastPath.isEmpty()) {
                return lastPath;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}

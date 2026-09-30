/*
 * Copyright (C) 2026 SmartTube VOX
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.tv.vot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController;

/**
 * Developer-only internal diagnostics receiver for Yandex VOT backend verification.
 * Not exported in AndroidManifest (android:exported="false").
 *
 * Supported internal actions:
 * - backendStatus
 * - forceOldBackend / disableNewBackend
 * - useDefaultBackend / enableNewBackend
 * - status
 */
public class YandexVotTestReceiver extends BroadcastReceiver {
    public static final String ACTION_TEST_YANDEX_VOT = "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT";
    private static final String TAG = "YANDEX_VOT_TEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_TEST_YANDEX_VOT.equals(intent.getAction())) {
            return;
        }

        String action = intent.getStringExtra("action");
        if (action == null || action.isEmpty() || "backendStatus".equalsIgnoreCase(action)) {
            handleBackendStatus();
        } else if ("enableNewBackend".equalsIgnoreCase(action) || "useDefaultBackend".equalsIgnoreCase(action)) {
            VoiceTranslateController.setNewYandexBackendEnabled(true);
            Log.i(TAG, "New Yandex VOT backend ENABLED for user flow (default candidate)");
        } else if ("disableNewBackend".equalsIgnoreCase(action) || "forceOldBackend".equalsIgnoreCase(action)) {
            VoiceTranslateController.setNewYandexBackendEnabled(false);
            Log.i(TAG, "New Yandex VOT backend DISABLED for user flow (forced OLD override)");
        } else if ("enableDial".equalsIgnoreCase(action)) {
            com.liskovsoft.smartyoutubetv2.common.prefs.RemoteControlData.instance(context).enableExternalLaunch(true);
            com.liskovsoft.smartyoutubetv2.common.vox.external.VoxExternalLaunchManager.instance(context).syncWithSettings();
            Log.i(TAG, "DIAL External Launch ENABLED via test broadcast");
        } else if ("disableDial".equalsIgnoreCase(action)) {
            com.liskovsoft.smartyoutubetv2.common.prefs.RemoteControlData.instance(context).enableExternalLaunch(false);
            com.liskovsoft.smartyoutubetv2.common.vox.external.VoxExternalLaunchManager.instance(context).syncWithSettings();
            Log.i(TAG, "DIAL External Launch DISABLED via test broadcast");
        } else if ("startTranslation".equalsIgnoreCase(action)) {
            VoiceTranslateController controller = VoiceTranslateController.instance();
            if (controller != null) {
                controller.onButtonClicked(com.liskovsoft.smartyoutubetv2.common.R.id.action_voice_translate, VoiceTranslateController.BTN_OFF);
                Log.i(TAG, "Triggered start translation via diagnostic broadcast");
            }
        } else if ("stopTranslation".equalsIgnoreCase(action)) {
            VoiceTranslateController controller = VoiceTranslateController.instance();
            if (controller != null) {
                controller.onButtonClicked(com.liskovsoft.smartyoutubetv2.common.R.id.action_voice_translate, VoiceTranslateController.BTN_ON);
                Log.i(TAG, "Triggered stop translation via diagnostic broadcast");
            }
        } else if ("status".equalsIgnoreCase(action)) {
            handleStatus();
        } else if ("testDownloadCore".equalsIgnoreCase(action)) {
            String videoId = intent.getStringExtra("videoId");
            if (videoId == null || videoId.isEmpty()) {
                videoId = "UF8uR6Z6KLc";
            }
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId == null || downloadId.isEmpty()) {
                downloadId = "test-probe-" + videoId;
            }
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadRequest req =
                    new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadRequest(
                            downloadId,
                            videoId,
                            "Test Download Probe",
                            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxQualityPreference.QUALITY_360P,
                            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxTranslationMode.STANDARD,
                            System.currentTimeMillis()
                    );
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                    com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
            coordinator.startDownload(req, new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadListener() {
                @Override
                public void onStateChanged(com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadProgress progress) {
                    Log.i(TAG, "DOWNLOAD_PROBE: state=" + progress.getState()
                            + " totalBytes=" + progress.getTotalBytesDownloaded());
                }

                @Override
                public void onProgressUpdated(com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadProgress progress) {
                    Log.i(TAG, "DOWNLOAD_PROBE_PROGRESS: state=" + progress.getState()
                            + " videoBytes=" + progress.getVideo().getBytesDownloaded()
                            + " origAudioBytes=" + progress.getOriginalAudio().getBytesDownloaded()
                            + " transAudioBytes=" + progress.getTranslatedAudio().getBytesDownloaded());
                }

                @Override
                public void onError(String id, com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadErrorCode code, String message) {
                    Log.e(TAG, "DOWNLOAD_PROBE_ERROR: id=" + id + " code=" + code + " msg=" + message);
                }
            });
            Log.i(TAG, "DOWNLOAD_PROBE_STARTED: downloadId=" + downloadId + " videoId=" + videoId);
        } else if ("testDownloadStatus".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId == null || downloadId.isEmpty()) {
                downloadId = "test-probe-UF8uR6Z6KLc";
            }
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                    com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadJob job = coordinator.getJob(downloadId);
            if (job != null) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage storage =
                        new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage(context);
                java.io.File vFile = storage.getTrackFile(downloadId, com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadTrack.VIDEO);
                java.io.File oFile = storage.getTrackFile(downloadId, com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadTrack.ORIGINAL_AUDIO);
                java.io.File tFile = storage.getTrackFile(downloadId, com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadTrack.TRANSLATED_AUDIO);
                String transHeader = "none";
                if (tFile.exists() && tFile.length() >= 3) {
                    try {
                        byte[] b = new byte[3];
                        java.io.FileInputStream fis = new java.io.FileInputStream(tFile);
                        fis.read(b);
                        fis.close();
                        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
                            transHeader = "MP3/ID3";
                        } else if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) {
                            transHeader = "MP3/Sync";
                        } else {
                            transHeader = String.format("%02X %02X %02X", b[0], b[1], b[2]);
                        }
                    } catch (Exception ignored) {}
                }
                Log.i(TAG, "DOWNLOAD_PROBE_STATUS: id=" + downloadId
                        + " state=" + job.getState()
                        + " videoSize=" + (vFile.exists() ? vFile.length() : -1)
                        + " origAudioSize=" + (oFile.exists() ? oFile.length() : -1)
                        + " transAudioSize=" + (tFile.exists() ? tFile.length() : -1)
                        + " transHeader=" + transHeader);
            } else {
                Log.w(TAG, "DOWNLOAD_PROBE_STATUS: Job not found for id=" + downloadId);
            }
        } else {
            Log.w(TAG, "Unknown diagnostic action: " + action);
        }
    }

    private void handleStatus() {
        handleBackendStatus();
    }

    private void handleBackendStatus() {
        boolean flagEnabled = VoiceTranslateController.isNewYandexBackendEnabled();
        boolean userFlowActive = VoiceTranslateController.isUserFlowActive();
        VoiceTranslateController controller = VoiceTranslateController.instance();
        boolean isNewActive = controller != null && controller.isNewBackendActive();
        boolean fallbackTriggered = controller != null && controller.isFallbackTriggered();
        Log.i(TAG, "BackendStatus: flag_new_backend=" + (flagEnabled ? "ON" : "OFF")
                + " default_candidate=" + (flagEnabled ? "NEW" : "OLD")
                + " user_flow_active=" + userFlowActive
                + " active_backend=" + (isNewActive ? "NEW" : (userFlowActive ? "OLD" : "NONE"))
                + " fallback_triggered=" + fallbackTriggered);
    }
}

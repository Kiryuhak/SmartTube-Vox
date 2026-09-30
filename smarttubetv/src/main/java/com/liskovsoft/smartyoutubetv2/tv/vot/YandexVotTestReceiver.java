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
        } else if ("testDownloadCancel".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId != null && !downloadId.isEmpty()) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                        com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
                coordinator.cancelDownload(downloadId);
                Log.i(TAG, "DOWNLOAD_PROBE_CANCEL_REQUESTED: id=" + downloadId);
            }
        } else if ("testDownloadPause".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId != null && !downloadId.isEmpty()) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                        com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
                coordinator.pauseDownload(downloadId);
                Log.i(TAG, "DOWNLOAD_PROBE_PAUSE_REQUESTED: id=" + downloadId);
            }
        } else if ("testDownloadResume".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId != null && !downloadId.isEmpty()) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                        com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
                coordinator.resumeDownload(downloadId, new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadListener() {
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
                Log.i(TAG, "DOWNLOAD_PROBE_RESUME_REQUESTED: id=" + downloadId);
            }
        } else if ("testMuxDownload".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId == null || downloadId.isEmpty()) {
                downloadId = "patch4-live-final";
            }
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                    com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
            final String finalDownloadId = downloadId;
            boolean started = coordinator.muxDownload(downloadId, new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadListener() {
                @Override
                public void onStateChanged(com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadProgress progress) {
                    Log.i(TAG, "MUX_PROBE: state=" + progress.getState() + " totalBytes=" + progress.getTotalBytesDownloaded());
                }

                @Override
                public void onProgressUpdated(com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadProgress progress) {
                    Log.i(TAG, "MUX_PROBE_PROGRESS: state=" + progress.getState());
                }

                @Override
                public void onError(String id, com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadErrorCode code, String message) {
                    Log.e(TAG, "MUX_PROBE_ERROR: id=" + id + " code=" + code + " msg=" + message);
                }
            });
            Log.i(TAG, "MUX_PROBE_REQUESTED: id=" + downloadId + " started=" + started);
        } else if ("testMuxStatus".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId == null || downloadId.isEmpty()) {
                downloadId = "patch4-live-final";
            }
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                    com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadJob job = coordinator.getJob(downloadId);
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage storage =
                    new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage(context);
            java.io.File mkvFile = storage.getOutputFile(downloadId);
            java.io.File tmpMkvFile = storage.getTmpOutputFile(downloadId);
            String mkvHeader = "none";
            if (mkvFile.exists() && mkvFile.length() >= 4) {
                try {
                    byte[] b = new byte[4];
                    java.io.FileInputStream fis = new java.io.FileInputStream(mkvFile);
                    fis.read(b);
                    fis.close();
                    mkvHeader = String.format("%02X %02X %02X %02X", b[0], b[1], b[2], b[3]);
                } catch (Exception ignored) {}
            }
            Log.i(TAG, "MUX_PROBE_STATUS: id=" + downloadId
                    + " state=" + (job != null ? job.getState() : "NULL")
                    + " mkvExists=" + mkvFile.exists()
                    + " mkvSize=" + (mkvFile.exists() ? mkvFile.length() : -1)
                    + " tmpMkvExists=" + tmpMkvFile.exists()
                    + " mkvHeader=" + mkvHeader);
        } else if ("testMkvMediaProbe".equalsIgnoreCase(action)) {
            final String downloadId = (intent.getStringExtra("downloadId") != null && !intent.getStringExtra("downloadId").isEmpty())
                    ? intent.getStringExtra("downloadId") : "patch4-live-final";
            new Thread(new Runnable() {
                @Override
                public void run() {
                    com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage storage =
                            new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage(context);
                    java.io.File mkvFile = storage.getOutputFile(downloadId);
                    if (!mkvFile.exists()) {
                        Log.e(TAG, "MKV_MEDIA_PROBE_ERROR: File not found: " + mkvFile.getAbsolutePath());
                        return;
                    }

                    try {
                        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
                        extractor.setDataSource(mkvFile.getAbsolutePath());
                        int numTracks = extractor.getTrackCount();
                        Log.i(TAG, "MKV_MEDIA_PROBE: trackCount=" + numTracks + " fileSize=" + mkvFile.length());

                        for (int i = 0; i < numTracks; i++) {
                            android.media.MediaFormat fmt = extractor.getTrackFormat(i);
                            String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                            long duration = fmt.containsKey(android.media.MediaFormat.KEY_DURATION) ? fmt.getLong(android.media.MediaFormat.KEY_DURATION) : -1L;
                            int width = fmt.containsKey(android.media.MediaFormat.KEY_WIDTH) ? fmt.getInteger(android.media.MediaFormat.KEY_WIDTH) : -1;
                            int height = fmt.containsKey(android.media.MediaFormat.KEY_HEIGHT) ? fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT) : -1;
                            int sampleRate = fmt.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) : -1;
                            int channels = fmt.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT) : -1;

                            Log.i(TAG, "MKV_MEDIA_TRACK: index=" + i
                                    + " mime=" + mime
                                    + " durationUs=" + duration
                                    + " width=" + width
                                    + " height=" + height
                                    + " sampleRate=" + sampleRate
                                    + " channels=" + channels);
                        }
                        extractor.release();

                        // Поэлементная выборка по трекам (single pass probe)
                        android.media.MediaExtractor trkExtractor = new android.media.MediaExtractor();
                        trkExtractor.setDataSource(mkvFile.getAbsolutePath());
                        for (int t = 0; t < numTracks; t++) {
                            trkExtractor.selectTrack(t);
                        }
                        long[] firstPts = new long[]{-1, -1, -1};
                        long[] lastPts = new long[]{-1, -1, -1};
                        int[] sampleCounts = new int[numTracks];
                        int total = 0;
                        while (trkExtractor.getSampleTrackIndex() >= 0 && total < 1500) {
                            int trk = trkExtractor.getSampleTrackIndex();
                            long pts = trkExtractor.getSampleTime();
                            if (trk >= 0 && trk < numTracks) {
                                if (firstPts[trk] < 0) firstPts[trk] = pts;
                                lastPts[trk] = pts;
                                sampleCounts[trk]++;
                            }
                            total++;
                            trkExtractor.advance();
                        }
                        for (int t = 0; t < numTracks; t++) {
                            Log.i(TAG, "MKV_MEDIA_TRACK_SAMPLES: track=" + t + " firstPtsUs=" + firstPts[t] + " lastPtsUs=" + lastPts[t] + " scannedSamples=" + sampleCounts[t]);
                        }
                        trkExtractor.release();

                        // Тест перемотки (Seek test)
                        android.media.MediaExtractor seekExtractor = new android.media.MediaExtractor();
                        seekExtractor.setDataSource(mkvFile.getAbsolutePath());
                        seekExtractor.selectTrack(0);
                        seekExtractor.seekTo(30_000_000L, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        long seek30Pts = seekExtractor.getSampleTime();
                        seekExtractor.seekTo(60_000_000L, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        long seek60Pts = seekExtractor.getSampleTime();
                        seekExtractor.seekTo(10_000_000L, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        long seekBackPts = seekExtractor.getSampleTime();

                        Log.i(TAG, "MKV_SEEK_PROBE: seek30sPtsUs=" + seek30Pts + " seek60sPtsUs=" + seek60Pts + " seekBackPtsUs=" + seekBackPts);
                        seekExtractor.release();
                    } catch (Exception e) {
                        Log.e(TAG, "MKV_MEDIA_PROBE_EXCEPTION: " + e.getMessage(), e);
                    }
                }
            }).start();
        } else if ("testMkvExoPlayerProbe".equalsIgnoreCase(action)) {
            final String downloadId = (intent.getStringExtra("downloadId") != null && !intent.getStringExtra("downloadId").isEmpty())
                    ? intent.getStringExtra("downloadId") : "patch4-live-final";
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage storage =
                            new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage(context);
                    java.io.File mkvFile = storage.getOutputFile(downloadId);
                    if (!mkvFile.exists()) {
                        Log.e(TAG, "EXOPLAYER_PROBE_ERROR: File not found: " + mkvFile.getAbsolutePath());
                        return;
                    }

                    try {
                        com.google.android.exoplayer2.SimpleExoPlayer player =
                                com.google.android.exoplayer2.ExoPlayerFactory.newSimpleInstance(context);
                        com.google.android.exoplayer2.source.MediaSource mediaSource =
                                new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(
                                        new com.google.android.exoplayer2.upstream.DefaultDataSourceFactory(context, "SmartTube"),
                                        new com.google.android.exoplayer2.extractor.DefaultExtractorsFactory()
                                ).createMediaSource(android.net.Uri.fromFile(mkvFile));

                        player.addListener(new com.google.android.exoplayer2.Player.EventListener() {
                            @Override
                            public void onTracksChanged(com.google.android.exoplayer2.source.TrackGroupArray trackGroups, com.google.android.exoplayer2.trackselection.TrackSelectionArray trackSelections) {
                                Log.i(TAG, "EXOPLAYER_PROBE_TRACKS: trackGroupsCount=" + trackGroups.length);
                                for (int g = 0; g < trackGroups.length; g++) {
                                    com.google.android.exoplayer2.source.TrackGroup group = trackGroups.get(g);
                                    for (int t = 0; t < group.length; t++) {
                                        com.google.android.exoplayer2.Format f = group.getFormat(t);
                                        Log.i(TAG, "EXOPLAYER_TRACK: group=" + g + " track=" + t + " mime=" + f.sampleMimeType + " label=" + f.label + " lang=" + f.language);
                                    }
                                }
                                // Тест перемотки в ExoPlayer
                                player.seekTo(30000);
                                Log.i(TAG, "EXOPLAYER_SEEK_30S: target=30000 pos=" + player.getCurrentPosition());
                                player.seekTo(60000);
                                Log.i(TAG, "EXOPLAYER_SEEK_60S: target=60000 pos=" + player.getCurrentPosition());
                                player.seekTo(10000);
                                Log.i(TAG, "EXOPLAYER_SEEK_BACK: target=10000 pos=" + player.getCurrentPosition());
                                player.release();
                            }

                            @Override
                            public void onPlayerError(com.google.android.exoplayer2.ExoPlaybackException error) {
                                Log.e(TAG, "EXOPLAYER_PROBE_ERROR: " + error.getMessage(), error);
                                player.release();
                            }
                        });

                        player.prepare(mediaSource);
                        player.setPlayWhenReady(false);
                    } catch (Exception e) {
                        Log.e(TAG, "EXOPLAYER_PROBE_EXCEPTION: " + e.getMessage(), e);
                    }
                }
            });
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

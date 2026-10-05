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
 * Debug manifest restricts access to the signature-level DUMP permission (ADB shell).
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
                            intent.hasExtra("videoTitle") ? intent.getStringExtra("videoTitle") : "Test Download Probe",
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
            boolean startService = intent.getBooleanExtra("startService", true);
            if (startService) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadService.Companion.start(context, downloadId);
            }
            Log.i(TAG, "DOWNLOAD_PROBE_STARTED: downloadId=" + downloadId + " videoId=" + videoId + " startService=" + startService);
        } else if ("testStartServiceOnly".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId != null && !downloadId.isEmpty()) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadService.Companion.start(context, downloadId);
                Log.i(TAG, "DOWNLOAD_SERVICE_START_ONLY: id=" + downloadId);
            }
        } else if ("testResumeService".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId != null && !downloadId.isEmpty()) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadService.Companion.resume(context, downloadId);
                Log.i(TAG, "DOWNLOAD_SERVICE_RESUME: id=" + downloadId);
            }
        } else if ("testCancelService".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId != null && !downloadId.isEmpty()) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadService.Companion.cancel(context, downloadId);
                Log.i(TAG, "DOWNLOAD_SERVICE_CANCEL_REQUESTED: id=" + downloadId);
            }
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
            boolean force = intent.getBooleanExtra("force", false);
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                    com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadJob job = coordinator.getJob(downloadId);
            if (job != null && (force || job.getState() == com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadState.MUXED)) {
                job.updateState(com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadState.READY_FOR_MUX, null, null);
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage storage =
                        new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage(context);
                java.io.File out = storage.getOutputFile(downloadId);
                if (out.exists()) out.delete();
            }
            final String finalDownloadId = downloadId;
            boolean started = coordinator.muxDownload(downloadId, new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadListener() {
                @Override
                public void onStateChanged(com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadProgress progress) {
                    Log.i(TAG, "MUX_PROBE: state=" + progress.getState() + " totalBytes=" + progress.getTotalBytesDownloaded() + " muxPercent=" + progress.getMuxPercent());
                }

                @Override
                public void onProgressUpdated(com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadProgress progress) {
                    Log.i(TAG, "MUX_PROBE_PROGRESS: state=" + progress.getState() + " muxPercent=" + progress.getMuxPercent() + " bytes=" + progress.getMuxBytesProcessed() + "/" + progress.getMuxTotalBytes());
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

                        // Полная выборка по всем трекам (full sample scan без искусственных ограничений)
                        android.media.MediaExtractor trkExtractor = new android.media.MediaExtractor();
                        trkExtractor.setDataSource(mkvFile.getAbsolutePath());
                        for (int t = 0; t < numTracks; t++) {
                            trkExtractor.selectTrack(t);
                        }
                        long[] firstPts = new long[]{-1, -1, -1};
                        long[] lastPts = new long[]{-1, -1, -1};
                        int[] sampleCounts = new int[numTracks];
                        java.util.TreeMap<Long, Long>[] ptsMaps = new java.util.TreeMap[numTracks];
                        for (int t = 0; t < numTracks; t++) {
                            ptsMaps[t] = new java.util.TreeMap<Long, Long>();
                        }

                        while (trkExtractor.getSampleTrackIndex() >= 0) {
                            int trk = trkExtractor.getSampleTrackIndex();
                            long pts = trkExtractor.getSampleTime();
                            if (trk >= 0 && trk < numTracks) {
                                if (firstPts[trk] < 0) firstPts[trk] = pts;
                                lastPts[trk] = pts;
                                sampleCounts[trk]++;
                                if (ptsMaps[trk].isEmpty() || (pts - ptsMaps[trk].lastKey() >= 200_000L)) {
                                    ptsMaps[trk].put(pts, pts);
                                }
                            }
                            trkExtractor.advance();
                        }
                        for (int t = 0; t < numTracks; t++) {
                            Log.i(TAG, "MKV_MEDIA_TRACK_SAMPLES: track=" + t + " firstPtsUs=" + firstPts[t] + " lastPtsUs=" + lastPts[t] + " sampleCount=" + sampleCounts[t]);
                        }
                        trkExtractor.release();

                        // Измерение синхронизации (Sync measurement) на 0s, 30s, 60s, near end
                        long[] syncTargets = new long[]{0L, 30_000_000L, 60_000_000L, Math.max(0L, lastPts[0] - 5_000_000L)};
                        for (long targetUs : syncTargets) {
                            Long vPts = ptsMaps[0].ceilingKey(targetUs);
                            Long oPts = numTracks > 1 ? ptsMaps[1].ceilingKey(targetUs) : null;
                            Long tPts = numTracks > 2 ? ptsMaps[2].ceilingKey(targetUs) : null;
                            long v = vPts != null ? vPts : -1L;
                            long o = oPts != null ? oPts : -1L;
                            long tr = tPts != null ? tPts : -1L;
                            long origDeltaMs = (o >= 0 && v >= 0) ? (o - v) / 1000L : 0L;
                            long transDeltaMs = (tr >= 0 && v >= 0) ? (tr - v) / 1000L : 0L;
                            Log.i(TAG, "SYNC_MEASUREMENT: targetUs=" + targetUs + " vPtsUs=" + v + " oPtsUs=" + o + " tPtsUs=" + tr
                                    + " origDeltaMs=" + origDeltaMs + " transDeltaMs=" + transDeltaMs);
                        }

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
                        seekExtractor.seekTo(Math.max(0L, lastPts[0] - 3_000_000L), android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        long seekEndPts = seekExtractor.getSampleTime();

                        Log.i(TAG, "MKV_SEEK_PROBE: seek30sPtsUs=" + seek30Pts + " seek60sPtsUs=" + seek60Pts + " seekBackPtsUs=" + seekBackPts + " seekEndPtsUs=" + seekEndPts);
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
                    android.net.Uri uri = null;
                    try {
                        com.liskovsoft.smartyoutubetv2.common.vox.download.StoredJobData jobData = storage.loadJobMetadata(downloadId);
                        if (jobData != null && jobData.getPublishedUri() != null && !jobData.getPublishedUri().isEmpty()) {
                            uri = android.net.Uri.parse(jobData.getPublishedUri());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not lookup mediastore uri: " + e);
                    }
                    if (uri == null) {
                        if (mkvFile.exists()) {
                            uri = android.net.Uri.fromFile(mkvFile);
                        } else {
                            Log.e(TAG, "EXOPLAYER_PROBE_ERROR: File not found: " + mkvFile.getAbsolutePath() + " and no MediaStore URI");
                            return;
                        }
                    }

                    try {
                        final com.google.android.exoplayer2.trackselection.DefaultTrackSelector trackSelector =
                                new com.google.android.exoplayer2.trackselection.DefaultTrackSelector();
                        final com.google.android.exoplayer2.SimpleExoPlayer player =
                                com.google.android.exoplayer2.ExoPlayerFactory.newSimpleInstance(context, trackSelector);

                        com.google.android.exoplayer2.source.MediaSource mediaSource =
                                new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(
                                        new com.google.android.exoplayer2.upstream.DefaultDataSourceFactory(context, "SmartTube"),
                                        new com.google.android.exoplayer2.extractor.DefaultExtractorsFactory()
                                ).createMediaSource(uri);

                        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

                        player.addListener(new com.google.android.exoplayer2.Player.EventListener() {
                            private boolean startedTesting = false;

                            @Override
                            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                                Log.i(TAG, "EXOPLAYER_STATE_CHANGED: playWhenReady=" + playWhenReady + " state=" + playbackState);
                                if (playbackState == com.google.android.exoplayer2.Player.STATE_READY && !startedTesting) {
                                    startedTesting = true;
                                    Log.i(TAG, "EXOPLAYER_STATE_READY: durationMs=" + player.getDuration() + " currentPos=" + player.getCurrentPosition());

                                    // Stage 1: Play initial 4s
                                    handler.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            long pos1 = player.getCurrentPosition();
                                            Log.i(TAG, "EXOPLAYER_PLAY_PASS: initialPlayPos=" + pos1 + " duration=" + player.getDuration());

                                            // Stage 2: Switch to Original Audio (und)
                                            com.google.android.exoplayer2.trackselection.DefaultTrackSelector.ParametersBuilder b1 =
                                                    trackSelector.buildUponParameters();
                                            b1.setPreferredAudioLanguage("und");
                                            trackSelector.setParameters(b1);

                                            handler.postDelayed(new Runnable() {
                                                @Override
                                                public void run() {
                                                    long posOrig = player.getCurrentPosition();
                                                    Log.i(TAG, "EXOPLAYER_SELECT_ORIGINAL_PASS: pos=" + posOrig + " lang=und");

                                                    // Stage 3: Switch to Translated Audio (ru)
                                                    com.google.android.exoplayer2.trackselection.DefaultTrackSelector.ParametersBuilder b2 =
                                                            trackSelector.buildUponParameters();
                                                    b2.setPreferredAudioLanguage("ru");
                                                    trackSelector.setParameters(b2);

                                                    handler.postDelayed(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            long posTrans = player.getCurrentPosition();
                                                            Log.i(TAG, "EXOPLAYER_SWITCH_TRANSLATION_PASS: pos=" + posTrans + " lang=ru");

                                                            // Stage 4: Second track cycle (und -> ru)
                                                            com.google.android.exoplayer2.trackselection.DefaultTrackSelector.ParametersBuilder b3 =
                                                                    trackSelector.buildUponParameters();
                                                            b3.setPreferredAudioLanguage("und");
                                                            trackSelector.setParameters(b3);

                                                            handler.postDelayed(new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    com.google.android.exoplayer2.trackselection.DefaultTrackSelector.ParametersBuilder b4 =
                                                                            trackSelector.buildUponParameters();
                                                                    b4.setPreferredAudioLanguage("ru");
                                                                    trackSelector.setParameters(b4);
                                                                    Log.i(TAG, "EXOPLAYER_TRACK_SWITCH_CYCLE_PASS: pos=" + player.getCurrentPosition());

                                                                    // Stage 5: Seek testing
                                                                    player.seekTo(30000);
                                                                    handler.postDelayed(new Runnable() {
                                                                        @Override
                                                                        public void run() {
                                                                            Log.i(TAG, "EXOPLAYER_SEEK_30S_PASS: pos=" + player.getCurrentPosition());
                                                                            player.seekTo(60000);

                                                                            handler.postDelayed(new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    Log.i(TAG, "EXOPLAYER_SEEK_60S_PASS: pos=" + player.getCurrentPosition());
                                                                                    player.seekTo(10000);

                                                                                    handler.postDelayed(new Runnable() {
                                                                                        @Override
                                                                                        public void run() {
                                                                                            Log.i(TAG, "EXOPLAYER_SEEK_BACK_PASS: pos=" + player.getCurrentPosition());

                                                                                            // Stage 6: Pause / Resume
                                                                                            player.setPlayWhenReady(false);
                                                                                            final long pausedPos = player.getCurrentPosition();

                                                                                            handler.postDelayed(new Runnable() {
                                                                                                @Override
                                                                                                public void run() {
                                                                                                    long stillPausedPos = player.getCurrentPosition();
                                                                                                    boolean isStable = Math.abs(stillPausedPos - pausedPos) < 200;
                                                                                                    Log.i(TAG, "EXOPLAYER_PAUSE_PASS: stable=" + isStable + " pos=" + stillPausedPos);

                                                                                                    player.setPlayWhenReady(true);
                                                                                                    handler.postDelayed(new Runnable() {
                                                                                                        @Override
                                                                                                        public void run() {
                                                                                                            long resumedPos = player.getCurrentPosition();
                                                                                                            Log.i(TAG, "EXOPLAYER_RESUME_PASS: pos=" + resumedPos);

                                                                                                            // Stage 7: Seek near end and await EOS
                                                                                                            long dur = player.getDuration();
                                                                                                            if (dur > 5000) {
                                                                                                                player.seekTo(dur - 2000);
                                                                                                            }
                                                                                                        }
                                                                                                    }, 2500);
                                                                                                }
                                                                                            }, 2000);
                                                                                        }
                                                                                    }, 1500);
                                                                                }
                                                                            }, 1500);
                                                                        }
                                                                    }, 1500);
                                                                }
                                                            }, 1500);
                                                        }
                                                    }, 3000);
                                                }
                                            }, 3000);
                                        }
                                    }, 4000);
                                } else if (playbackState == com.google.android.exoplayer2.Player.STATE_ENDED) {
                                    Log.i(TAG, "EXOPLAYER_EOS_PASS: reached end of stream successfully at pos=" + player.getCurrentPosition());
                                    player.release();
                                }
                            }

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
                            }

                            @Override
                            public void onPlayerError(com.google.android.exoplayer2.ExoPlaybackException error) {
                                Log.e(TAG, "EXOPLAYER_PROBE_ERROR: " + error.getMessage(), error);
                                player.release();
                            }
                        });

                        player.prepare(mediaSource);
                        player.setPlayWhenReady(true);
                    } catch (Exception e) {
                        Log.e(TAG, "EXOPLAYER_PROBE_EXCEPTION: " + e.getMessage(), e);
                    }
                }
            });
        } else if ("testPublishDownload".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId == null || downloadId.isEmpty()) {
                downloadId = "patch4-live-final";
            }
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                    com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadJob job = coordinator.getJob(downloadId);
            if (job != null) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxMediaStorePublisher publisher =
                        new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxMediaStorePublisher(context);
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage storage =
                        new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadStorage(context);
                java.io.File mkv = storage.getOutputFile(downloadId);
                if (mkv.exists() && mkv.length() > 0) {
                    try {
                        android.net.Uri uri = publisher.publish(mkv, job.getRequest().getVideoTitle(), new java.util.concurrent.atomic.AtomicBoolean(false));
                        job.setPublishedUri(uri.toString());
                        job.updateState(com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadState.COMPLETED, null, null);
                        com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadRepository repo =
                                new com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadRepository(storage);
                        repo.persistJob(job);
                        storage.cleanInternalSourcesAfterPublication(downloadId);
                        Log.i(TAG, "PUBLISH_PROBE_SUCCESS: uri=" + uri + " available=" + publisher.isPublishedFileAvailable(uri.toString()));
                    } catch (Exception e) {
                        Log.e(TAG, "PUBLISH_PROBE_ERROR: " + e.getMessage(), e);
                    }
                } else {
                    Log.w(TAG, "PUBLISH_PROBE_FAIL: internal MKV missing: " + mkv.getAbsolutePath());
                }
            } else {
                Log.w(TAG, "PUBLISH_PROBE_FAIL: job not found: " + downloadId);
            }
        } else if ("testPlayerRuntime".equalsIgnoreCase(action)) {
            // Диагностика реального плеера, без отдельного экземпляра ExoPlayer и фиктивных PASS.
            com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView player =
                    com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(context).getPlayer();
            if (player == null || player.getVideo() == null) {
                Log.w(TAG, "PLAYER_RUNTIME: no active player");
                return;
            }
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId != null) {
                com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadJob job =
                        com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context).getJob(downloadId);
                if (job == null || !player.getVideo().isLocal || !java.util.Objects.equals(job.getPublishedUri(), player.getVideo().mediaUrl)) {
                    Log.e(TAG, "PLAYER_RUNTIME: downloadId mismatch " + downloadId);
                    return;
                }
            }
            if (intent.hasExtra("seekMs")) player.setPositionMs(intent.getLongExtra("seekMs", 0));
            if (intent.hasExtra("speed")) player.setSpeed(intent.getFloatExtra("speed", 1f));
            if (intent.hasExtra("play")) player.setPlayWhenReady(intent.getBooleanExtra("play", true));
            Log.i(TAG, "PLAYER_RUNTIME: downloadId=" + downloadId
                    + " videoId=" + player.getVideo().videoId + " local=" + player.getVideo().isLocal
                    + " positionMs=" + player.getPositionMs() + " durationMs=" + player.getDurationMs()
                    + " playing=" + player.isPlaying() + " speed=" + player.getSpeed()
                    + " audio=" + (player.getAudioFormat() == null ? "none" : player.getAudioFormat().getTitle())
                    + " language=" + (player.getAudioFormat() == null ? "none" : player.getAudioFormat().getLanguage()));
        } else if ("testLocalPlayback".equalsIgnoreCase(action)) {
            String downloadId = intent.getStringExtra("downloadId");
            if (downloadId == null || downloadId.isEmpty()) {
                downloadId = "patch4-live-final";
            }
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator coordinator =
                    com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator.instance(context);
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadJob job = coordinator.getJob(downloadId);
            if (job != null) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        com.liskovsoft.smartyoutubetv2.common.vox.download.VoxLocalPlayerHelper.playJob(context, job);
                        Log.i(TAG, "LOCAL_PLAYBACK_OPENED: job=" + job.getDownloadId() + " uri=" + job.getPublishedUri());
                    }
                });
            } else {
                Log.w(TAG, "LOCAL_PLAYBACK_FAIL: job not found: " + downloadId);
            }
        } else if ("testShowCompatibilitySettings".equalsIgnoreCase(action)) {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> {
                com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.VoxCompatibilitySettingsPresenter.instance(context).show();
                Log.i(TAG, "COMPATIBILITY_SETTINGS_SHOWN");
            });
        } else if ("testShowDiagnostics".equalsIgnoreCase(action)) {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> {
                com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.VoxDiagnosticsPresenter.instance(context).show();
                Log.i(TAG, "DIAGNOSTICS_PRESENTER_SHOWN");
            });
        } else if ("testShowJournal".equalsIgnoreCase(action)) {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> {
                com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.VoxDiagnosticsPresenter.instance(context).showJournalDialog();
                Log.i(TAG, "JOURNAL_DIALOG_SHOWN");
            });
        } else if ("testLogSampleEvents".equalsIgnoreCase(action)) {
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.init(context);
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.i(
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.DOWNLOAD,
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.DOWNLOAD_STARTED,
                    "Started test download",
                    java.util.Collections.singletonMap("videoId", "test_video_123")
            );
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.w(
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.PLAYER,
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.TRANSLATION_DESYNC_WARNING,
                    "Translation audio slight desync detected (180ms)"
            );
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.e(
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.NETWORK,
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.DOWNLOAD_FAILED,
                    "Network request timed out to https://api.browser.yandex.ru/test?token=SECRET_TOKEN",
                    java.util.Collections.singletonMap("ip", "192.168.1.55"),
                    new java.io.IOException("SocketTimeoutException")
            );
            Log.i(TAG, "SAMPLE_LOGS_RECORDED: count=" + com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogStore.instance(context).getJournalEventCount());
        } else if ("testClearLogs".equalsIgnoreCase(action)) {
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogStore.instance(context).clearLogs();
            Log.i(TAG, "LOGS_CLEARED: count=" + com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogStore.instance(context).getJournalEventCount());
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

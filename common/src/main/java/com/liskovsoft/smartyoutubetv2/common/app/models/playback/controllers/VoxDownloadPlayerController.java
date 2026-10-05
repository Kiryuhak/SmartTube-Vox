package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadCoordinator;
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadDialogHelper;
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadErrorCode;
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadJob;
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadListener;
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadProgress;
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadProgressFormatter;
import com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadState;

/**
 * Manages the Player HUD Download action button state and interaction.
 * Synchronizes with {@link VoxDownloadCoordinator} to reflect download state (download/in-progress/completed/failed).
 */
public class VoxDownloadPlayerController extends BasePlayerController implements VoxDownloadListener {
    public static final int STATE_DOWNLOAD = 0;
    public static final int STATE_PROGRESS = 1;
    public static final int STATE_COMPLETED = 2;
    public static final int STATE_FAILED = 3;

    private static final int ACTION_DOWNLOAD = R.id.action_download;
    private boolean mIsListenerRegistered = false;

    @Override
    public void onInit() {
        registerCoordinatorListener();
    }

    @Override
    public void onViewResumed() {
        updateDownloadButtonState();
    }

    @Override
    public void onVideoLoaded(Video video) {
        updateDownloadButtonState();
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId == ACTION_DOWNLOAD) {
            if (getContext() != null && getVideo() != null) {
                VoxDownloadDialogHelper.onDownloadActionClicked(getContext(), getVideo());
            }
        }
    }

    @Override
    public void onFinish() {
        unregisterCoordinatorListener();
    }

    @Override
    public void onStateChanged(@NonNull VoxDownloadProgress progress) {
        postUpdateState(progress);
        if (getContext() != null) {
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadOverlay.onProgress(getContext(), progress);
        }
    }

    @Override
    public void onProgressUpdated(@NonNull VoxDownloadProgress progress) {
        postUpdateState(progress);
        if (getContext() != null) {
            com.liskovsoft.smartyoutubetv2.common.vox.download.VoxDownloadOverlay.onProgress(getContext(), progress);
        }
    }

    @Override
    public void onError(@NonNull String downloadId, @NonNull VoxDownloadErrorCode errorCode, @NonNull String message) {
        postUpdateState(null);
    }

    private void registerCoordinatorListener() {
        if (!mIsListenerRegistered && getContext() != null) {
            try {
                VoxDownloadCoordinator.instance(getContext()).addGlobalListener(this);
                mIsListenerRegistered = true;
            } catch (Exception ignored) {
            }
        }
    }

    private void unregisterCoordinatorListener() {
        if (mIsListenerRegistered && getContext() != null) {
            try {
                VoxDownloadCoordinator.instance(getContext()).removeGlobalListener(this);
            } catch (Exception ignored) {
            }
            mIsListenerRegistered = false;
        }
    }

    private void postUpdateState(@Nullable VoxDownloadProgress progress) {
        Utils.post(() -> updateDownloadButtonState(progress));
    }

    public void updateDownloadButtonState() {
        updateDownloadButtonState(null);
    }

    public void updateDownloadButtonState(@Nullable VoxDownloadProgress liveProgress) {
        if (getPlayer() == null || getContext() == null) {
            return;
        }

        Video video = getVideo();
        if (video == null || video.videoId == null || video.videoId.isEmpty()) {
            getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_DOWNLOAD);
            getPlayer().updateDownloadProgress(STATE_DOWNLOAD, "Скачать");
            return;
        }

        try {
            VoxDownloadCoordinator coordinator = VoxDownloadCoordinator.instance(getContext());

            // 1. Active job lookup for current video
            VoxDownloadJob activeJob = coordinator.findActiveJob(video.videoId);
            if (activeJob != null) {
                Integer percent = activeJob.getSnapshot().getOverallPercent();
                String label = VoxDownloadProgressFormatter.formatHudLabel(activeJob.getState(), percent);
                getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_PROGRESS);
                getPlayer().updateDownloadProgress(STATE_PROGRESS, label);
                return;
            }

            // 2. Completed job lookup
            VoxDownloadJob completedJob = coordinator.findCompletedJob(video.videoId);
            if (completedJob != null && coordinator.isPublishedFileAvailable(completedJob)) {
                getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_COMPLETED);
                getPlayer().updateDownloadProgress(STATE_COMPLETED, "Скачано");
                return;
            }

            // 3. Failed job lookup
            VoxDownloadJob failedJob = coordinator.findFailedJob(video.videoId);
            if (failedJob != null) {
                getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_FAILED);
                getPlayer().updateDownloadProgress(STATE_FAILED, "Ошибка загрузки");
                return;
            }

            // 4. Idle state
            getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_DOWNLOAD);
            getPlayer().updateDownloadProgress(STATE_DOWNLOAD, "Скачать");
        } catch (Exception e) {
            getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_DOWNLOAD);
            getPlayer().updateDownloadProgress(STATE_DOWNLOAD, "Скачать");
        }
    }
}

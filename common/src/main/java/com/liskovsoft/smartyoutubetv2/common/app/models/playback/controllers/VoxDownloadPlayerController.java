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

/**
 * Manages the Player HUD Download action button state and interaction.
 * Synchronizes with {@link VoxDownloadCoordinator} to reflect download state (download/in-progress/completed).
 */
public class VoxDownloadPlayerController extends BasePlayerController implements VoxDownloadListener {
    public static final int STATE_DOWNLOAD = 0;
    public static final int STATE_PROGRESS = 1;
    public static final int STATE_COMPLETED = 2;

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
        postUpdateState();
    }

    @Override
    public void onProgressUpdated(@NonNull VoxDownloadProgress progress) {
        // Intentionally lightweight: progress percentage is shown in dialogs/notifications
    }

    @Override
    public void onError(@NonNull String downloadId, @NonNull VoxDownloadErrorCode errorCode, @NonNull String message) {
        postUpdateState();
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

    private void postUpdateState() {
        Utils.post(this::updateDownloadButtonState);
    }

    public void updateDownloadButtonState() {
        if (getPlayer() == null || getContext() == null) {
            return;
        }

        Video video = getVideo();
        if (video == null || video.videoId == null || video.videoId.isEmpty()) {
            getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_DOWNLOAD);
            return;
        }

        try {
            VoxDownloadCoordinator coordinator = VoxDownloadCoordinator.instance(getContext());
            VoxDownloadJob activeJob = coordinator.findActiveJob(video.videoId);
            if (activeJob != null) {
                getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_PROGRESS);
                return;
            }

            VoxDownloadJob completedJob = coordinator.findCompletedJob(video.videoId);
            if (completedJob != null && coordinator.isPublishedFileAvailable(completedJob)) {
                getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_COMPLETED);
                return;
            }

            getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_DOWNLOAD);
        } catch (Exception e) {
            getPlayer().setButtonState(ACTION_DOWNLOAD, STATE_DOWNLOAD);
        }
    }
}

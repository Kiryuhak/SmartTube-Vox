/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Immutable representation of the Yandex VOT request lifecycle state.
 */
public final class YandexVotState {
    public enum Status {
        IDLE,
        REQUESTING,
        WAITING,
        AUDIO_REQUIRED,
        READY,
        CANCELLED,
        ERROR
    }

    private final Status status;
    private final long generationId;
    private final String videoId;
    private final String videoUrl;
    private final String translationId;
    private final String audioUrl;
    private final int remainingSeconds;
    private final String errorMessage;
    private final String errorCategory;
    private final boolean requestedLively;
    private final boolean receivedLively;

    public YandexVotState(
            @NonNull Status status,
            long generationId,
            @Nullable String videoId,
            @Nullable String videoUrl,
            @Nullable String translationId,
            @Nullable String audioUrl,
            int remainingSeconds,
            @Nullable String errorMessage,
            @Nullable String errorCategory,
            boolean requestedLively,
            boolean receivedLively
    ) {
        this.status = status != null ? status : Status.IDLE;
        this.generationId = generationId;
        this.videoId = videoId;
        this.videoUrl = videoUrl;
        this.translationId = translationId;
        this.audioUrl = audioUrl;
        this.remainingSeconds = remainingSeconds;
        this.errorMessage = errorMessage;
        this.errorCategory = errorCategory;
        this.requestedLively = requestedLively;
        this.receivedLively = receivedLively;
    }

    @NonNull
    public static YandexVotState idle() {
        return new YandexVotState(Status.IDLE, 0, null, null, null, null, 0, null, null, false, false);
    }

    @NonNull
    public static YandexVotState requesting(long generationId, @Nullable String videoId, @Nullable String videoUrl, boolean requestedLively) {
        return new YandexVotState(Status.REQUESTING, generationId, videoId, videoUrl, null, null, 0, null, null, requestedLively, false);
    }

    @NonNull
    public static YandexVotState waiting(long generationId, @Nullable String videoId, @Nullable String videoUrl,
                                         @Nullable String translationId, int remainingSeconds, boolean requestedLively) {
        return new YandexVotState(Status.WAITING, generationId, videoId, videoUrl, translationId, null, remainingSeconds, null, null, requestedLively, false);
    }

    @NonNull
    public static YandexVotState audioRequired(long generationId, @Nullable String videoId, @Nullable String videoUrl,
                                               @Nullable String translationId, boolean requestedLively) {
        return new YandexVotState(Status.AUDIO_REQUIRED, generationId, videoId, videoUrl, translationId, null, 0, null, null, requestedLively, false);
    }

    @NonNull
    public static YandexVotState ready(long generationId, @Nullable String videoId, @Nullable String videoUrl,
                                       @Nullable String translationId, @Nullable String audioUrl,
                                       boolean requestedLively, boolean receivedLively) {
        return new YandexVotState(Status.READY, generationId, videoId, videoUrl, translationId, audioUrl, 0, null, null, requestedLively, receivedLively);
    }

    @NonNull
    public static YandexVotState cancelled(long generationId, @Nullable String videoId, @Nullable String videoUrl) {
        return new YandexVotState(Status.CANCELLED, generationId, videoId, videoUrl, null, null, 0, null, null, false, false);
    }

    @NonNull
    public static YandexVotState error(long generationId, @Nullable String videoId, @Nullable String videoUrl,
                                       @Nullable String errorMessage, @Nullable String errorCategory, boolean requestedLively) {
        return new YandexVotState(Status.ERROR, generationId, videoId, videoUrl, null, null, 0, errorMessage, errorCategory, requestedLively, false);
    }

    @NonNull
    public Status getStatus() {
        return status;
    }

    public long getGenerationId() {
        return generationId;
    }

    @Nullable
    public String getVideoId() {
        return videoId;
    }

    @Nullable
    public String getVideoUrl() {
        return videoUrl;
    }

    @Nullable
    public String getTranslationId() {
        return translationId;
    }

    @Nullable
    public String getAudioUrl() {
        return audioUrl;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    @Nullable
    public String getErrorCategory() {
        return errorCategory;
    }

    public boolean isRequestedLively() {
        return requestedLively;
    }

    public boolean isReceivedLively() {
        return receivedLively;
    }

    public boolean isIdle() {
        return status == Status.IDLE;
    }

    public boolean isRequesting() {
        return status == Status.REQUESTING;
    }

    public boolean isWaiting() {
        return status == Status.WAITING;
    }

    public boolean isAudioRequired() {
        return status == Status.AUDIO_REQUIRED;
    }

    public boolean isReady() {
        return status == Status.READY;
    }

    public boolean isCancelled() {
        return status == Status.CANCELLED;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        YandexVotState that = (YandexVotState) o;
        return generationId == that.generationId &&
                remainingSeconds == that.remainingSeconds &&
                requestedLively == that.requestedLively &&
                receivedLively == that.receivedLively &&
                status == that.status &&
                Objects.equals(videoId, that.videoId) &&
                Objects.equals(videoUrl, that.videoUrl) &&
                Objects.equals(translationId, that.translationId) &&
                Objects.equals(audioUrl, that.audioUrl) &&
                Objects.equals(errorMessage, that.errorMessage) &&
                Objects.equals(errorCategory, that.errorCategory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, generationId, videoId, videoUrl, translationId,
                audioUrl, remainingSeconds, errorMessage, errorCategory, requestedLively, receivedLively);
    }

    @Override
    public String toString() {
        return "YandexVotState{" +
                "status=" + status +
                ", generationId=" + generationId +
                ", videoId='" + videoId + '\'' +
                ", videoUrl='" + videoUrl + '\'' +
                ", translationId='" + translationId + '\'' +
                ", audioUrl=" + (audioUrl != null ? "[PROTECTED]" : "null") +
                ", remainingSeconds=" + remainingSeconds +
                ", errorMessage='" + errorMessage + '\'' +
                ", errorCategory='" + errorCategory + '\'' +
                ", requestedLively=" + requestedLively +
                ", receivedLively=" + receivedLively +
                '}';
    }
}

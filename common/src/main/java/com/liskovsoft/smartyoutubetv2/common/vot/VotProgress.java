package com.liskovsoft.smartyoutubetv2.common.vot;

public final class VotProgress {
    public static final int TYPE_WAITING = 0;
    public static final int TYPE_READY = 1;
    public static final int TYPE_FAILED = 2;
    public static final int TYPE_LIVELY_FALLBACK = 3;

    public final int type;
    public final String audioUrl;
    public final int remainingTimeSec;
    public final int status;
    public final String message;
    public final int retryAfterSec;

    private VotProgress(int type, String audioUrl, int remainingTimeSec, int status, String message, int retryAfterSec) {
        this.type = type;
        this.audioUrl = audioUrl;
        this.remainingTimeSec = remainingTimeSec;
        this.status = status;
        this.message = message;
        this.retryAfterSec = retryAfterSec;
    }

    public static VotProgress waiting(int remainingTimeSec, int status) {
        return new VotProgress(TYPE_WAITING, null, remainingTimeSec, status, null, -1);
    }

    public static VotProgress ready(String audioUrl) {
        return new VotProgress(TYPE_READY, audioUrl, 0, VotTranslationResponse.STATUS_FINISHED, null, -1);
    }

    public static VotProgress failed(String message) {
        return new VotProgress(TYPE_FAILED, null, 0, VotTranslationResponse.STATUS_FAILED, message, -1);
    }

    public static VotProgress failed(String message, int retryAfterSec) {
        return new VotProgress(TYPE_FAILED, null, 0, VotTranslationResponse.STATUS_FAILED, message, retryAfterSec);
    }

    public static VotProgress livelyFallback() {
        return new VotProgress(TYPE_LIVELY_FALLBACK, null, 0, 0, null, -1);
    }
}

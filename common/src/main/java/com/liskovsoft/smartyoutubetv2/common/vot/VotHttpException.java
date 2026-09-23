package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;

/**
 * Exception representing an HTTP error during VOT service interaction.
 */
public class VotHttpException extends IOException {
    private final int mStatusCode;
    private final int mRetryAfterSec;

    public VotHttpException(int statusCode, String message) {
        this(statusCode, message, -1);
    }

    public VotHttpException(int statusCode, String message, int retryAfterSec) {
        super("HTTP " + statusCode + ": " + message);
        mStatusCode = statusCode;
        mRetryAfterSec = retryAfterSec;
    }

    public int getStatusCode() {
        return mStatusCode;
    }

    public int getRetryAfterSec() {
        return mRetryAfterSec;
    }

    public boolean isRateLimited() {
        return mStatusCode == 429;
    }

    public boolean isServerUnavailable() {
        return mStatusCode == 502 || mStatusCode == 503 || mStatusCode == 504;
    }
}
package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;

/**
 * Exception representing an HTTP error during VOT service interaction.
 */
public class VotHttpException extends IOException {
    private final int mStatusCode;

    public VotHttpException(int statusCode, String message) {
        super("HTTP " + statusCode + ": " + message);
        mStatusCode = statusCode;
    }

    public int getStatusCode() {
        return mStatusCode;
    }

    public boolean isRateLimited() {
        return mStatusCode == 429;
    }

    public boolean isServerUnavailable() {
        return mStatusCode == 502 || mStatusCode == 503 || mStatusCode == 504;
    }
}
/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;

import java.io.IOException;

/**
 * Fast-failing, bounded load error handling policy for secondary translation audio tracks.
 *
 * Prevents ExoPlayer from hanging for 30+ seconds in STATE_BUFFERING during 403 (expired signed URL)
 * or 404 (CDN delay) errors, enabling instant route fallback (Proxy <-> Direct) and signed URL refresh.
 */
public class VoxTranslationLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
    public static final int MAX_TRANSLATION_RETRIES = 2;

    @Override
    public long getRetryDelayMsFor(int dataType, long loadDurationMs, IOException exception, int errorCount) {
        if (exception instanceof InvalidResponseCodeException) {
            int responseCode = ((InvalidResponseCodeException) exception).responseCode;

            // Non-retryable: 401 Unauthorized or 403 Forbidden.
            // S3 SigV4 signature expired or route blocked: fail fast to let route fallback or token refresh run.
            if (responseCode == 401 || responseCode == 403) {
                return C.TIME_UNSET;
            }

            // CDN propagation delay: 404 Not Found or 410 Gone right after synthesis.
            // Allow at most 1 short retry (500ms).
            if (responseCode == 404 || responseCode == 410) {
                if (errorCount > 1) {
                    return C.TIME_UNSET;
                }
                return 500L;
            }

            // Server-side errors or Rate Limiting: at most 2 retries.
            if ((responseCode >= 500 && responseCode <= 599) || responseCode == 429) {
                if (errorCount > MAX_TRANSLATION_RETRIES) {
                    return C.TIME_UNSET;
                }
                return 1000L;
            }
        }

        // General transport / network errors: bounded to 2 retries
        if (errorCount > MAX_TRANSLATION_RETRIES) {
            return C.TIME_UNSET;
        }

        return Math.min(errorCount * 1000L, 2000L);
    }

    @Override
    public int getMinimumLoadableRetryCount(int dataType) {
        return MAX_TRANSLATION_RETRIES;
    }
}

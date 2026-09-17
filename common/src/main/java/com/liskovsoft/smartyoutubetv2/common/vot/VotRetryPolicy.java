package com.liskovsoft.smartyoutubetv2.common.vot;

/**
 * Exponential backoff retry policy for VOT network resilience.
 * Guards against infinite loops and handles HTTP 429 (rate-limit) and 502/504 (server errors).
 */
public class VotRetryPolicy {
    public static final int DEFAULT_MAX_RETRIES = 5;
    public static final long DEFAULT_INITIAL_BACKOFF_MS = 3000L;
    public static final long DEFAULT_MAX_BACKOFF_MS = 30000L;
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    private final int mMaxRetries;
    private final long mInitialBackoffMs;
    private final long mMaxBackoffMs;
    private final double mMultiplier;

    public VotRetryPolicy() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS, DEFAULT_BACKOFF_MULTIPLIER);
    }

    public VotRetryPolicy(int maxRetries, long initialBackoffMs, long maxBackoffMs, double multiplier) {
        mMaxRetries = Math.max(1, maxRetries);
        mInitialBackoffMs = Math.max(500L, initialBackoffMs);
        mMaxBackoffMs = Math.max(mInitialBackoffMs, maxBackoffMs);
        mMultiplier = multiplier > 1.0 ? multiplier : 2.0;
    }

    public boolean shouldRetry(int statusCode, int attempt) {
        if (attempt >= mMaxRetries) {
            return false;
        }
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    public boolean shouldRetryNetworkError(int attempt) {
        return attempt < mMaxRetries;
    }

    public long getBackoffDelayMs(int statusCode, int attempt) {
        if (attempt <= 0) {
            return mInitialBackoffMs;
        }
        // Rate limit (429) uses a slightly more generous initial delay
        long base = (statusCode == 429) ? Math.max(5000L, mInitialBackoffMs) : mInitialBackoffMs;
        double delay = base * Math.pow(mMultiplier, attempt);
        return (long) Math.min(mMaxBackoffMs, delay);
    }

    public int getMaxRetries() {
        return mMaxRetries;
    }
}
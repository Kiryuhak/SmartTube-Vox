package com.liskovsoft.smartyoutubetv2.common.oauth;

/** Poll timing and bounded transient failures for one Device Code generation. */
final class YandexBrokerPollPolicy {
    private int intervalSeconds;
    private int transientErrors;

    YandexBrokerPollPolicy(int initialIntervalSeconds) {
        intervalSeconds = Math.max(5, Math.min(60, initialIntervalSeconds));
    }

    int nextIntervalSeconds() {
        return intervalSeconds;
    }

    boolean accept(YandexBrokerClient.Result result) {
        switch (result.state) {
            case AUTHORIZATION_PENDING:
                transientErrors = 0;
                break;
            case SLOW_DOWN:
                transientErrors = 0;
                intervalSeconds = Math.min(60, intervalSeconds + 5);
                break;
            case RATE_LIMITED:
                intervalSeconds = Math.max(intervalSeconds, result.retryAfterSeconds);
                break;
            case NETWORK_ERROR:
            case TEMPORARY_SERVER_ERROR:
                transientErrors++;
                break;
            default:
                transientErrors = 0;
        }
        return transientErrors < 3;
    }
}

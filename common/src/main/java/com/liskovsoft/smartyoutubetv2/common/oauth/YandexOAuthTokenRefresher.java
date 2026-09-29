package com.liskovsoft.smartyoutubetv2.common.oauth;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * Coordinates single-flight token refresh across concurrent requests.
 * Prevents multiple simultaneous refresh requests when multiple parallel
 * calls encounter HTTP 401.
 */
public final class YandexOAuthTokenRefresher {
    private static final String TAG = "YandexOAuthTokenRefresher";
    private static final Object REFRESH_LOCK = new Object();
    private static FutureTask<String> sActiveRefreshTask = null;

    private YandexOAuthTokenRefresher() {}

    /**
     * Refresh the access token using the stored refresh token.
     * Uses single-flight coordination so concurrent callers share the same refresh operation.
     *
     * @param context Application context
     * @return New access token if refresh succeeded, null otherwise
     */
    @Nullable
    public static String refreshSync(@NonNull Context context) {
        if (YandexBrokerConfig.mode() != YandexBrokerConfig.Mode.BROKER) {
            Log.d(TAG, "Broker disabled; skipping token refresh");
            return null;
        }

        final YandexOAuthTokenStore store = YandexOAuthTokenStore.instance(context);
        final String refreshToken = store.getRefreshToken();
        if (refreshToken.isEmpty()) {
            Log.d(TAG, "No refresh token available; skipping token refresh");
            return null;
        }

        FutureTask<String> currentTask;
        boolean isCreator = false;

        synchronized (REFRESH_LOCK) {
            if (sActiveRefreshTask != null && !sActiveRefreshTask.isDone()) {
                currentTask = sActiveRefreshTask;
            } else {
                isCreator = true;
                currentTask = new FutureTask<>(new Callable<String>() {
                    @Override
                    public String call() {
                        return performRefresh(store, refreshToken);
                    }
                });
                sActiveRefreshTask = currentTask;
            }
        }

        if (isCreator) {
            currentTask.run();
        }

        try {
            return currentTask.get();
        } catch (Exception e) {
            Log.w(TAG, "Refresh task execution error: %s", e.getClass().getSimpleName());
            return null;
        } finally {
            synchronized (REFRESH_LOCK) {
                if (sActiveRefreshTask == currentTask) {
                    sActiveRefreshTask = null;
                }
            }
        }
    }

    @Nullable
    private static String performRefresh(@NonNull YandexOAuthTokenStore store, @NonNull String refreshToken) {
        String brokerUrl = YandexBrokerConfig.url();
        if (brokerUrl.isEmpty()) {
            return null;
        }

        try {
            YandexBrokerClient client = new YandexBrokerClient(brokerUrl);
            YandexBrokerClient.Result result = client.refresh(refreshToken);

            if (result.state == YandexBrokerClient.State.SUCCESS && result.accessToken != null) {
                Log.i(TAG, "OAuth token refresh successful");
                store.updateTokens(result.accessToken, result.refreshToken, result.expiresInSeconds);
                return result.accessToken;
            }

            if (result.state == YandexBrokerClient.State.INVALID_GRANT
                    || result.state == YandexBrokerClient.State.UNAUTHORIZED_CLIENT
                    || result.state == YandexBrokerClient.State.ACCESS_DENIED
                    || result.state == YandexBrokerClient.State.EXPIRED_TOKEN) {
                Log.w(TAG, "Permanent token refresh failure (%s), clearing credentials", result.state);
                store.clear();
                store.setAuthState(VotData.AuthState.REJECTED);
                return null;
            }

            Log.w(TAG, "Temporary token refresh error (%s), keeping recoverable state", result.state);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Token refresh failed with exception: %s", e.getClass().getSimpleName());
            return null;
        }
    }
}

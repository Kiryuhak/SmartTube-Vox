package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import java.io.File;
import java.lang.reflect.Field;

public final class YouTubePlayerCacheManager {
    private static final String TAG = YouTubePlayerCacheManager.class.getSimpleName();
    private static final String PREF_NAME = "youtube_player_cache_state";
    private static final String KEY_LAST_VERSION = "last_player_cache_version";

    public interface InvalidationListener {
        void onCacheInvalidated();
    }

    private static InvalidationListener sInvalidationListener;

    private YouTubePlayerCacheManager() {
    }

    public static void setInvalidationListener(InvalidationListener listener) {
        sInvalidationListener = listener;
    }

    /**
     * Checks if the app was upgraded or if cache invalidation was never performed.
     * Automatically invalidates stale YouTube player cache on version change.
     */
    public static void onAppUpgradeIfNeeded(Context context) {
        if (context == null) {
            return;
        }

        try {
            String currentVersion = AppInfoHelpers.getAppVersionName(context) + "_" + AppInfoHelpers.getAppVersionCode(context);
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String lastVersion = prefs.getString(KEY_LAST_VERSION, null);

            if (lastVersion == null || !lastVersion.equals(currentVersion)) {
                Log.i(TAG, "App upgrade detected (old: %s, new: %s). Invalidating player cache...", lastVersion, currentVersion);
                invalidatePlayerCache(context);
                prefs.edit().putString(KEY_LAST_VERSION, currentVersion).apply();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error checking app upgrade for player cache: %s", t.getMessage());
        }
    }

    /**
     * Completely purges the cached YouTube player scripts, challenge solvers,
     * and cached MediaServiceData player references.
     * Preserves user accounts, playlists, and settings.
     */
    public static void invalidatePlayerCache(Context context) {
        Log.i(TAG, "Purging YouTube player and challenge-solver caches...");

        // 1. Reset MediaServiceData cached player references
        try {
            MediaServiceData data = MediaServiceData.instance();
            if (data != null) {
                data.setAppInfo(null);
                data.setFailedAppInfo(null);
                data.setPlayerExtractorCache(null);
                data.setPlayerExtractorData(null, null, null);
                data.persistNow();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to clear MediaServiceData player cache: %s", t.getMessage());
        }

        // 2. Clear MediaServiceCache SharedPreferences
        if (context != null) {
            try {
                context.getSharedPreferences("MediaServiceCache", Context.MODE_PRIVATE).edit().clear().apply();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to clear MediaServiceCache prefs: %s", t.getMessage());
            }

            // 3. Clear challenge-solver SharedPreferences
            try {
                context.getSharedPreferences("yt_cache_service2%KEY%challenge-solver", Context.MODE_PRIVATE).edit().clear().apply();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to clear challenge-solver prefs: %s", t.getMessage());
            }

            // 4. Delete persistent challenge-solver scripts directory
            try {
                File challengeSolverDir = new File(context.getFilesDir(), "challenge-solver");
                if (challengeSolverDir.exists()) {
                    FileHelpers.delete(challengeSolverDir);
                    Log.i(TAG, "Deleted challenge-solver directory: %s", challengeSolverDir.getAbsolutePath());
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to delete challenge-solver directory: %s", t.getMessage());
            }
        }

        // 5. Reset AppService sInstance via reflection to force fresh AppServiceIntCached
        try {
            Field instanceField = AppService.class.getDeclaredField("sInstance");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to reset AppService sInstance: %s", t.getMessage());
        }

        // 6. Invalidate YouTubeServiceManager cache (resets PoTokenGate, VideoInfoService, etc.)
        try {
            YouTubeServiceManager.instance().invalidateCache();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to invalidate YouTubeServiceManager: %s", t.getMessage());
        }

        if (sInvalidationListener != null) {
            try {
                sInvalidationListener.onCacheInvalidated();
            } catch (Throwable t) {
                Log.e(TAG, "InvalidationListener error: %s", t.getMessage());
            }
        }

        Log.i(TAG, "YouTube player and challenge-solver caches purged successfully.");
    }
}

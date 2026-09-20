package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class ResetManager {
    private static final String TAG = "ResetManager";
    private static ResetManager sInstance;

    public interface UserDataCleaner {
        boolean clearUserData(Context context);
    }

    public static class DefaultUserDataCleaner implements UserDataCleaner {
        @Override
        public boolean clearUserData(Context context) {
            if (context == null) {
                return false;
            }
            Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            ActivityManager activityManager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                return activityManager.clearApplicationUserData();
            }
            return false;
        }
    }

    private UserDataCleaner mCleaner = new DefaultUserDataCleaner();
    private final AtomicBoolean mIsResetting = new AtomicBoolean(false);

    ResetManager() {
    }

    public static synchronized ResetManager instance() {
        if (sInstance == null) {
            sInstance = new ResetManager();
        }
        return sInstance;
    }

    public void setUserDataCleaner(UserDataCleaner cleaner) {
        mCleaner = cleaner != null ? cleaner : new DefaultUserDataCleaner();
    }

    public UserDataCleaner getUserDataCleaner() {
        return mCleaner;
    }

    public boolean isResetting() {
        return mIsResetting.get();
    }

    public void resetState() {
        mIsResetting.set(false);
    }

    /**
     * Completely wipes application user data (accounts, cache, databases, preferences)
     * via the official Android API {@link ActivityManager#clearApplicationUserData()}.
     *
     * @param context Calling context
     * @return true if the clear request was accepted by the OS, false otherwise
     */
    public boolean resetApplicationData(Context context) {
        if (context == null) {
            safeLogW(TAG, "Context is null, cannot reset application data");
            return false;
        }

        if (!mIsResetting.compareAndSet(false, true)) {
            safeLogW(TAG, "Reset is already in progress, ignoring concurrent call");
            return false;
        }

        safeLogI(TAG, "Executing factory reset via ActivityManager.clearApplicationUserData()");

        boolean result = false;
        try {
            Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            result = mCleaner.clearUserData(appContext);
        } catch (Throwable t) {
            safeLogE(TAG, "Error invoking clearUserData: " + t.getMessage(), t);
            result = false;
        }

        if (!result) {
            safeLogE(TAG, "clearApplicationUserData() returned false or failed", null);
            mIsResetting.set(false);
        }

        return result;
    }

    private static void safeLogI(String tag, String msg) {
        try {
            Log.i(tag, msg);
        } catch (Throwable ignored) {
        }
    }

    private static void safeLogW(String tag, String msg) {
        try {
            Log.w(tag, msg);
        } catch (Throwable ignored) {
        }
    }

    private static void safeLogE(String tag, String msg, Throwable t) {
        try {
            if (t != null) {
                Log.e(tag, msg, t);
            } else {
                Log.e(tag, msg);
            }
        } catch (Throwable ignored) {
        }
    }
}

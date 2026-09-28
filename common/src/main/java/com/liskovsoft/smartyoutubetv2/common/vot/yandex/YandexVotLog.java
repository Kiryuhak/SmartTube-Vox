/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Safe logger for Yandex VOT backend and state machine that delegates to SmartTube logger
 * and remains testable in local JVM unit tests without Android runtime mocks.
 */
public final class YandexVotLog {
    private YandexVotLog() {
    }

    public static void d(String tag, String msg) {
        try {
            Log.d(tag, msg);
        } catch (Throwable ignored) {
            // Android mock fallback in JVM unit tests
        }
    }

    public static void i(String tag, String msg) {
        try {
            Log.i(tag, msg);
        } catch (Throwable ignored) {
            // Android mock fallback in JVM unit tests
        }
    }

    public static void w(String tag, String msg) {
        try {
            Log.w(tag, msg);
        } catch (Throwable ignored) {
            // Android mock fallback in JVM unit tests
        }
    }

    public static void e(String tag, String msg) {
        try {
            Log.e(tag, msg);
        } catch (Throwable ignored) {
            // Android mock fallback in JVM unit tests
        }
    }
}

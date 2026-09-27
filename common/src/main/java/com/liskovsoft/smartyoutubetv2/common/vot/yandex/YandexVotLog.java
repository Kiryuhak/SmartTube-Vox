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
}

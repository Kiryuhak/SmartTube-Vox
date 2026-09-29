package com.liskovsoft.smartyoutubetv2.common.oauth;

import com.liskovsoft.smartyoutubetv2.common.BuildConfig;

/** Device Flow is disabled unless a debug build explicitly supplies a broker URL. */
public final class YandexBrokerConfig {
    public enum Mode { DISABLED, DIRECT_YANDEX, BROKER }

    private YandexBrokerConfig() {}

    public static Mode mode() {
        return BuildConfig.DEBUG && !BuildConfig.YANDEX_OAUTH_BROKER_URL.isEmpty()
                ? Mode.BROKER : Mode.DISABLED;
    }

    public static String url() {
        return BuildConfig.YANDEX_OAUTH_BROKER_URL;
    }

    public static boolean usesLocalMock() {
        return mode() == Mode.BROKER && (url().startsWith("http://127.0.0.1:")
                || url().startsWith("http://localhost:"));
    }
}

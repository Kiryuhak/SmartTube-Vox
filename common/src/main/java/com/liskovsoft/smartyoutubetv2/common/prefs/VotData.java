package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.prefs.SharedPreferencesBase;

public class VotData extends SharedPreferencesBase {
    private static final String PREFS_NAME = "vot_data";
    private static final String OAUTH_TOKEN = "yandex_oauth_token";
    private static final String OAUTH_EXPIRES_AT = "yandex_oauth_expires_at";
    private static final String LIVELY_VOICE = "use_lively_voice";
    private static final String PREF_ONBOARDING_SHOWN = "yandex_onboarding_shown";
    private static final String PREF_PLAYER_HINT_SHOWN = "vot_player_hint_shown";
    private static final String ORIGINAL_VOLUME_PERCENT = "original_volume_percent";
    private static final String TRANSLATION_VOLUME_PERCENT = "translation_volume_percent";
    private static final String AUTO_TRANSLATE = "auto_translate_enabled";
    private static final String PREFER_YOUTUBE_AUTO_DUB = "prefer_youtube_auto_dub";
    private static final String AUTH_STATE = "yandex_auth_state";
    private static final String VOX_PROXY_ENABLED = "vox_proxy_enabled";
    private static final String VOX_PROXY_TYPE = "vox_proxy_type";
    private static final String VOX_PROXY_HOST = "vox_proxy_host";
    private static final String VOX_PROXY_PORT = "vox_proxy_port";
    private static final String VOX_PROXY_USERNAME = "vox_proxy_username";
    private static final String VOX_PROXY_PASSWORD = "vox_proxy_password";
    private static final int DEFAULT_ORIGINAL_VOLUME_PERCENT = 5;
    private static final int DEFAULT_TRANSLATION_VOLUME_PERCENT = 100;

    /**
     * Состояние OAuth-авторизации Яндекс ID.
     *
     * Различает «токен есть» от «токен проверен» — два принципиально разных случая.
     * Переходы:
     *   Установка токена → UNVERIFIED
     *   Успешный перевод (Lively) → CONFIRMED
     *   HTTP 401 бэкенда → REJECTED (токен остаётся в SharedPreferences!)
     *   Явный logout пользователя → сброс токена + ABSENT
     */
    public enum AuthState {
        /** Токен не задан — пользователь не авторизован. */
        ABSENT,
        /**
         * Токен задан, но ещё не проверялся реальным запросом к бэкенду.
         * Устанавливается при setOAuthToken() и сохраняется до первого запроса Lively.
         */
        UNVERIFIED,
        /** Токен успешно использован — бэкенд принял его в текущей сессии. */
        CONFIRMED,
        /**
         * Бэкенд вернул HTTP 401 при запросе с данным токеном.
         * Токен остаётся в SharedPreferences — пользователь может его исправить.
         * Lively Voice выключается до повторного подтверждения.
         */
        REJECTED
    }

    @SuppressLint("StaticFieldLeak")
    private static VotData sInstance;
    private final com.liskovsoft.smartyoutubetv2.common.oauth.YandexOAuthTokenStore mTokenStore;

    private VotData(Context context) {
        super(context.getApplicationContext(), PREFS_NAME);
        mTokenStore = com.liskovsoft.smartyoutubetv2.common.oauth.YandexOAuthTokenStore.instance(context.getApplicationContext());
        mTokenStore.migrateFromLegacyPrefs(this);
    }

    public static VotData instance(Context context) {
        if (sInstance == null && context != null) {
            sInstance = new VotData(context);
        }
        return sInstance;
    }

    @androidx.annotation.VisibleForTesting
    public static synchronized void resetForTesting() {
        sInstance = null;
    }

    public String getOAuthToken() {
        return mTokenStore.getAccessToken();
    }

    public String getRefreshToken() {
        return mTokenStore.getRefreshToken();
    }

    public boolean hasRefreshToken() {
        return mTokenStore.hasRefreshToken();
    }

    public void setOAuthToken(String token) {
        setOAuthToken(token, 0);
    }

    /** The expiry is supplied by the broker; zero means unknown (manual or SDK token). */
    public void setOAuthToken(String token, long expiresInSeconds) {
        setOAuthTokens(token, null, expiresInSeconds);
    }

    public void setOAuthTokens(String accessToken, String refreshToken, long expiresInSeconds) {
        mTokenStore.saveTokens(accessToken, refreshToken, expiresInSeconds);
        if (mTokenStore.hasAccessToken()) {
            setLivelyVoiceEnabled(true);
        } else {
            setLivelyVoiceEnabled(false);
        }
    }

    public void clearOAuthToken() {
        mTokenStore.clear();
        setLivelyVoiceEnabled(false);
    }

    public void logoutYandex() {
        clearOAuthToken();
    }

    /**
     * Помечает OAuth-токен как отклонённый бэкендом (HTTP 401).
     *
     * ВАЖНО: токен НЕ удаляется из защищённого хранилища — пользователь может
     * исправить его или пройти повторную авторизацию через Яндекс ID.
     * Lively Voice выключается до нового подтверждения.
     *
     * @param rejectedToken токен, с которым был выполнен запрос. Если указан,
     *                      проверяется совпадение с текущим токеном, что защищает
     *                      от отклонения нового токена старым откликом.
     */
    public void markOAuthRejected(String rejectedToken) {
        mTokenStore.markRejected(rejectedToken);
        if (mTokenStore.getAuthState() == AuthState.REJECTED) {
            setLivelyVoiceEnabled(false);
        }
    }

    public void markOAuthRejected() {
        markOAuthRejected(null);
    }

    /**
     * Помечает OAuth-токен как подтверждённый (бэкенд принял его).
     * Вызывается при успешном получении Lively-перевода.
     *
     * @param confirmedToken токен, с которым был выполнен успешный запрос.
     *                       Если указан, проверяется совпадение с текущим токеном,
     *                       что защищает от подтверждения старым ответом уже заменённого токена.
     */
    public void markOAuthConfirmed(String confirmedToken) {
        mTokenStore.markConfirmed(confirmedToken);
    }

    public void markOAuthConfirmed() {
        markOAuthConfirmed(null);
    }

    public boolean hasOAuthToken() {
        return mTokenStore.hasAccessToken();
    }

    /** @return текущее состояние авторизации OAuth */
    public AuthState getAuthState() {
        return mTokenStore.getAuthState();
    }

    public boolean isLivelyVoiceEnabled() {
        return hasOAuthToken() && (!isOAuthTokenExpired() || hasRefreshToken()) && getBoolean(LIVELY_VOICE, true);
    }

    public boolean isOAuthTokenExpired() {
        return mTokenStore.isExpired();
    }

    public String getRawLegacyToken() {
        return getString(OAUTH_TOKEN, "");
    }

    public long getRawLegacyExpiresAt() {
        return getLong(OAUTH_EXPIRES_AT, 0);
    }

    public void clearRawLegacyToken() {
        putString(OAUTH_TOKEN, "");
        putLong(OAUTH_EXPIRES_AT, 0);
    }

    public void setLivelyVoiceEnabled(boolean enabled) {
        putBoolean(LIVELY_VOICE, enabled);
    }

    public boolean isOnboardingShown() {
        return getBoolean(PREF_ONBOARDING_SHOWN, false);
    }

    public void setOnboardingShown(boolean shown) {
        putBoolean(PREF_ONBOARDING_SHOWN, shown);
    }

    public boolean isPlayerHintShown() {
        return getBoolean(PREF_PLAYER_HINT_SHOWN, false);
    }

    public void setPlayerHintShown(boolean shown) {
        putBoolean(PREF_PLAYER_HINT_SHOWN, shown);
    }

    /** YouTube/original track level while translation plays (0–100%). */
    public int getOriginalVolumePercent() {
        return clampPercent(getInt(ORIGINAL_VOLUME_PERCENT, DEFAULT_ORIGINAL_VOLUME_PERCENT));
    }

    public void setOriginalVolumePercent(int percent) {
        putInt(ORIGINAL_VOLUME_PERCENT, clampPercent(percent));
    }

    /** Russian voice-over level (0–100%). */
    public int getTranslationVolumePercent() {
        return clampPercent(getInt(TRANSLATION_VOLUME_PERCENT, DEFAULT_TRANSLATION_VOLUME_PERCENT));
    }

    public void setTranslationVolumePercent(int percent) {
        putInt(TRANSLATION_VOLUME_PERCENT, clampPercent(percent));
    }

    public boolean isAutoTranslateEnabled() {
        return getBoolean(AUTO_TRANSLATE, false);
    }

    public void setAutoTranslateEnabled(boolean enabled) {
        putBoolean(AUTO_TRANSLATE, enabled);
    }

    public boolean isPreferYoutubeAutoDub() {
        return getBoolean(PREFER_YOUTUBE_AUTO_DUB, false);
    }

    public void setPreferYoutubeAutoDub(boolean enabled) {
        putBoolean(PREFER_YOUTUBE_AUTO_DUB, enabled);
    }

    public float getOriginalVolumeMultiplier() {
        return getOriginalVolumePercent() / 100f;
    }

    public float getTranslationVolumeMultiplier() {
        return getTranslationVolumePercent() / 100f;
    }

    public boolean isVoxProxyEnabled() {
        return getBoolean(VOX_PROXY_ENABLED, false);
    }

    public void setVoxProxyEnabled(boolean enabled) {
        putBoolean(VOX_PROXY_ENABLED, enabled);
        com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.invalidateCache();
    }

    public com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxProxyConfig.Type getVoxProxyType() {
        String typeStr = getString(VOX_PROXY_TYPE, com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxProxyConfig.Type.HTTP.name());
        try {
            return com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxProxyConfig.Type.valueOf(typeStr);
        } catch (Exception e) {
            return com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxProxyConfig.Type.HTTP;
        }
    }

    public void setVoxProxyType(com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxProxyConfig.Type type) {
        putString(VOX_PROXY_TYPE, type != null ? type.name() : com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxProxyConfig.Type.HTTP.name());
        com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.invalidateCache();
    }

    public String getVoxProxyHost() {
        return getString(VOX_PROXY_HOST, "");
    }

    public void setVoxProxyHost(String host) {
        putString(VOX_PROXY_HOST, host != null ? host.trim() : "");
        com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.invalidateCache();
    }

    public int getVoxProxyPort() {
        return getInt(VOX_PROXY_PORT, 8080);
    }

    public void setVoxProxyPort(int port) {
        putInt(VOX_PROXY_PORT, port);
        com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.invalidateCache();
    }

    public String getVoxProxyUsername() {
        return getString(VOX_PROXY_USERNAME, "");
    }

    public void setVoxProxyUsername(String username) {
        putString(VOX_PROXY_USERNAME, username != null ? username.trim() : "");
        com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.invalidateCache();
    }

    public String getVoxProxyPassword() {
        return getString(VOX_PROXY_PASSWORD, "");
    }

    public void setVoxProxyPassword(String password) {
        putString(VOX_PROXY_PASSWORD, password != null ? password : "");
        com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.invalidateCache();
    }

    public com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxProxyConfig getVoxProxyConfig() {
        return new com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxProxyConfig(
                isVoxProxyEnabled(),
                getVoxProxyType(),
                getVoxProxyHost(),
                getVoxProxyPort(),
                getVoxProxyUsername(),
                getVoxProxyPassword()
        );
    }

    public void setVoxProxyConfig(com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxProxyConfig config) {
        if (config == null || !config.isEnabled()) {
            setVoxProxyEnabled(false);
            if (config != null) {
                setVoxProxyType(config.getType());
                setVoxProxyHost(config.getHost());
                setVoxProxyPort(config.getPort());
                setVoxProxyUsername(config.getUsername());
                setVoxProxyPassword(config.getPassword());
            }
        } else {
            setVoxProxyType(config.getType());
            setVoxProxyHost(config.getHost());
            setVoxProxyPort(config.getPort());
            setVoxProxyUsername(config.getUsername());
            setVoxProxyPassword(config.getPassword());
            setVoxProxyEnabled(config.isEnabled());
        }
        com.liskovsoft.smartyoutubetv2.common.vox.proxy.VoxHttpClientFactory.invalidateCache();
    }

    public static final String VOT_POST_WATCH_ACTION = "vot_post_watch_action";
    public static final int POST_WATCH_DO_NOTHING = 0;
    public static final int POST_WATCH_OFFER_DELETE = 1;

    public boolean isDeleteAfterWatchingEnabled() {
        return getPostWatchAction() == POST_WATCH_OFFER_DELETE;
    }

    public int getPostWatchAction() {
        return getInt(VOT_POST_WATCH_ACTION, POST_WATCH_DO_NOTHING);
    }

    public void setPostWatchAction(int action) {
        putInt(VOT_POST_WATCH_ACTION, action);
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            return "";
        }
        return token.trim().replaceAll("\\s+", "");
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}

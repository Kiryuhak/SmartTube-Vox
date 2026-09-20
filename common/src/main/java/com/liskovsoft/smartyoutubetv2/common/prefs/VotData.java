package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.prefs.SharedPreferencesBase;

public class VotData extends SharedPreferencesBase {
    private static final String PREFS_NAME = "vot_data";
    private static final String OAUTH_TOKEN = "yandex_oauth_token";
    private static final String LIVELY_VOICE = "use_lively_voice";
    private static final String PREF_ONBOARDING_SHOWN = "yandex_onboarding_shown";
    private static final String PREF_PLAYER_HINT_SHOWN = "vot_player_hint_shown";
    private static final String ORIGINAL_VOLUME_PERCENT = "original_volume_percent";
    private static final String TRANSLATION_VOLUME_PERCENT = "translation_volume_percent";
    private static final String AUTO_TRANSLATE = "auto_translate_enabled";
    private static final String PREFER_YOUTUBE_AUTO_DUB = "prefer_youtube_auto_dub";
    private static final String AUTH_STATE = "yandex_auth_state";
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

    private VotData(Context context) {
        super(context.getApplicationContext(), PREFS_NAME);
    }

    public static VotData instance(Context context) {
        if (sInstance == null) {
            sInstance = new VotData(context);
        }
        return sInstance;
    }

    public String getOAuthToken() {
        return getString(OAUTH_TOKEN, "");
    }

    public void setOAuthToken(String token) {
        String normalized = normalizeToken(token);
        putString(OAUTH_TOKEN, normalized);
        if (!TextUtils.isEmpty(normalized)) {
            // Новый токен — сбрасываем в UNVERIFIED: он ещё не проверен бэкендом
            setAuthState(AuthState.UNVERIFIED);
            setLivelyVoiceEnabled(true);
        } else {
            setAuthState(AuthState.ABSENT);
            setLivelyVoiceEnabled(false);
        }
    }

    public void clearOAuthToken() {
        putString(OAUTH_TOKEN, "");
        setAuthState(AuthState.ABSENT);
        setLivelyVoiceEnabled(false);
    }

    public void logoutYandex() {
        clearOAuthToken();
    }

    /**
     * Помечает OAuth-токен как отклонённый бэкендом (HTTP 401).
     *
     * ВАЖНО: токен НЕ удаляется из SharedPreferences — пользователь может
     * исправить его или пройти повторную авторизацию через Яндекс ID.
     * Lively Voice выключается до нового подтверждения.
     *
     * @param rejectedToken токен, с которым был выполнен запрос. Если указан,
     *                      проверяется совпадение с текущим токеном, что защищает
     *                      от отклонения нового токена старым откликом.
     */
    public void markOAuthRejected(String rejectedToken) {
        if (hasOAuthToken() && (rejectedToken == null || Helpers.equals(getOAuthToken(), rejectedToken))) {
            setAuthState(AuthState.REJECTED);
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
        if (hasOAuthToken() && (confirmedToken == null || Helpers.equals(getOAuthToken(), confirmedToken))) {
            setAuthState(AuthState.CONFIRMED);
        }
    }

    public void markOAuthConfirmed() {
        markOAuthConfirmed(null);
    }

    public boolean hasOAuthToken() {
        return !TextUtils.isEmpty(getOAuthToken());
    }

    /** @return текущее состояние авторизации OAuth */
    public AuthState getAuthState() {
        if (!hasOAuthToken()) {
            return AuthState.ABSENT;
        }
        String stored = getString(AUTH_STATE, AuthState.UNVERIFIED.name());
        try {
            return AuthState.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return AuthState.UNVERIFIED;
        }
    }

    private void setAuthState(AuthState state) {
        putString(AUTH_STATE, state.name());
    }

    public boolean isLivelyVoiceEnabled() {
        return hasOAuthToken() && getBoolean(LIVELY_VOICE, true);
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

    public String getTokenPreview() {
        String token = getOAuthToken();
        if (TextUtils.isEmpty(token)) {
            return "";
        }
        if (token.length() <= 12) {
            return token.substring(0, 4) + "…";
        }
        return token.substring(0, 6) + "…" + token.substring(token.length() - 4);
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

package com.liskovsoft.smartyoutubetv2.common.oauth;

/**
 * Результат одного шага polling Яндекс Device Code Flow.
 *
 * Типы результата:
 *   PENDING — пользователь ещё не подтвердил вход, продолжать polling.
 *   SLOW_DOWN — сервер требует увеличить интервал.
 *   ACCESS_DENIED — пользователь явно отказал. Прекратить polling.
 *   EXPIRED — код истёк. Запросить новый.
 *   SUCCESS — авторизация выполнена. Токен содержится в {@link #getAccessToken()}.
 *   NETWORK_ERROR — сетевая ошибка при poll-запросе. Можно повторить.
 */
public final class YandexTokenPollResult {

    public enum Type {
        PENDING,
        SLOW_DOWN,
        ACCESS_DENIED,
        EXPIRED,
        SUCCESS,
        NETWORK_ERROR
    }

    private final Type type;
    /** Токен (только для type == SUCCESS). Никогда не логировать. */
    private final String accessToken;
    /** Сообщение ошибки (для NETWORK_ERROR, ACCESS_DENIED, EXPIRED). */
    private final String errorMessage;

    private YandexTokenPollResult(Type type, String accessToken, String errorMessage) {
        this.type = type;
        this.accessToken = accessToken;
        this.errorMessage = errorMessage;
    }

    public static YandexTokenPollResult pending() {
        return new YandexTokenPollResult(Type.PENDING, null, null);
    }

    public static YandexTokenPollResult slowDown() {
        return new YandexTokenPollResult(Type.SLOW_DOWN, null, null);
    }

    public static YandexTokenPollResult accessDenied() {
        return new YandexTokenPollResult(Type.ACCESS_DENIED, null, "access_denied");
    }

    public static YandexTokenPollResult expired() {
        return new YandexTokenPollResult(Type.EXPIRED, null, "expired_token");
    }

    public static YandexTokenPollResult success(String accessToken) {
        return new YandexTokenPollResult(Type.SUCCESS, accessToken, null);
    }

    public static YandexTokenPollResult networkError(String message) {
        return new YandexTokenPollResult(Type.NETWORK_ERROR, null, message);
    }

    public Type getType() {
        return type;
    }

    /** Возвращает access token. ТОЛЬКО для type == SUCCESS. Никогда не логировать значение. */
    public String getAccessToken() {
        return accessToken;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isTerminal() {
        return type == Type.ACCESS_DENIED || type == Type.EXPIRED || type == Type.SUCCESS;
    }

    @Override
    public String toString() {
        // Намеренно НЕ включаем accessToken: безопасность
        if (type == Type.SUCCESS) {
            return "YandexTokenPollResult{type=SUCCESS, tokenPresent=true}";
        }
        return "YandexTokenPollResult{type=" + type
                + (errorMessage != null ? ", error=" + errorMessage : "") + "}";
    }
}

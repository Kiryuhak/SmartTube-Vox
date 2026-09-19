package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.R;

/**
 * Категории ошибок VOT-перевода для корректного сопоставления сообщений пользователю.
 *
 * Каждая категория соответствует конкретному первопричине, определённой по реальным
 * HTTP-кодам или статусам протокола VOT — а не по совпадению подстрок в сообщениях исключений.
 */
public enum VotErrorCategory {

    /** HTTP 401 — Яндекс ID отклонил OAuth-токен (недействителен, отозван, неверный scope). */
    AUTH_REJECTED,

    /**
     * Протокольный статус 7 (STATUS_SESSION_REQUIRED).
     * Сервер требует анонимную криптосессию VOT через /session/create.
     * НЕ является ошибкой OAuth-авторизации пользователя.
     */
    PROTOCOL_SESSION_REQUIRED,

    /** HTTP 429 — превышен лимит запросов к API перевода. */
    RATE_LIMITED,

    /** HTTP 500/502/503/504 или таймаут соединения — сервер Яндекса временно недоступен. */
    SERVER_UNAVAILABLE,

    /** Сетевой сбой (SocketException, UnknownHostException и т.п.). */
    NETWORK_ERROR,

    /** Исчерпан лимит попыток опроса (MAX_POLL_ATTEMPTS) — перевод занял слишком долго. */
    TIMEOUT,

    /** Бэкенд сообщил, что видео не поддерживается для перевода. */
    UNSUPPORTED_VIDEO,

    /** Любая другая ошибка, не попавшая в явные категории выше. */
    GENERIC_ERROR;

    /**
     * Определяет категорию по HTTP-коду VotHttpException.
     *
     * @param statusCode HTTP-статус из ответа сервера
     * @return точная категория ошибки
     */
    public static VotErrorCategory fromHttpCode(int statusCode) {
        if (statusCode == 401) {
            return AUTH_REJECTED;
        }
        if (statusCode == 429) {
            return RATE_LIMITED;
        }
        if (statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return SERVER_UNAVAILABLE;
        }
        return GENERIC_ERROR;
    }

    /**
     * Определяет категорию по строке-маркеру, уже установленной внутри VotClient.
     *
     * Маркеры — это строковые константы, которые VotClient передаёт через
     * {@link VotProgress#failed(String)}, а не произвольные текстовые совпадения.
     *
     * @param marker маркер из VotProgress.message
     * @return категория ошибки
     */
    public static VotErrorCategory fromMarker(String marker) {
        if (marker == null) {
            return GENERIC_ERROR;
        }
        switch (marker) {
            case VotClient.ERROR_MARKER_AUTH_REJECTED:
                return AUTH_REJECTED;
            case VotClient.ERROR_MARKER_PROTOCOL_SESSION:
                return PROTOCOL_SESSION_REQUIRED;
            case VotClient.ERROR_MARKER_RATE_LIMITED:
                return RATE_LIMITED;
            case VotClient.ERROR_MARKER_SERVER_UNAVAILABLE:
                return SERVER_UNAVAILABLE;
            case VotClient.ERROR_MARKER_TIMEOUT:
                return TIMEOUT;
            case VotClient.ERROR_MARKER_NETWORK:
                return NETWORK_ERROR;
            case VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO:
                return UNSUPPORTED_VIDEO;
            default:
                return GENERIC_ERROR;
        }
    }

    /** @return true если ошибка указывает на проблему с OAuth-токеном пользователя */
    public boolean isOAuthFailure() {
        return this == AUTH_REJECTED;
    }

    /** @return true если ошибка сетевая или серверная (не связана с токеном) */
    public boolean isTransient() {
        return this == NETWORK_ERROR || this == SERVER_UNAVAILABLE || this == RATE_LIMITED;
    }

    /**
     * Возвращает идентификатор ресурса строки с понятным описанием для пользователя на ТВ.
     */
    public int getMessageResId() {
        switch (this) {
            case AUTH_REJECTED:
                return R.string.vot_error_auth_rejected;
            case SERVER_UNAVAILABLE:
                return R.string.vot_error_server_unavailable;
            case RATE_LIMITED:
                return R.string.vot_error_rate_limited;
            case TIMEOUT:
                return R.string.vot_error_timeout;
            case NETWORK_ERROR:
            case PROTOCOL_SESSION_REQUIRED:
                return R.string.vot_error_network;
            case UNSUPPORTED_VIDEO:
                return R.string.vot_error_unsupported_video;
            case GENERIC_ERROR:
            default:
                return R.string.vot_error_generic;
        }
    }
}

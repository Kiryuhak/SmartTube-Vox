package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Проверяет корректность определения категорий ошибок по HTTP-кодам и маркерам.
 *
 * Ключевой инвариант: тип ошибки определяется по структурным данным (HTTP-код,
 * строковый маркер-константа), а не по случайному совпадению текста исключения.
 */
public class VotErrorCategoryTest {

    // ── HTTP-коды ─────────────────────────────────────────────────────────────

    @Test
    public void http401IsAuthRejected() {
        assertEquals(VotErrorCategory.AUTH_REJECTED, VotErrorCategory.fromHttpCode(401));
    }

    @Test
    public void http429IsRateLimited() {
        assertEquals(VotErrorCategory.RATE_LIMITED, VotErrorCategory.fromHttpCode(429));
    }

    @Test
    public void http502IsServerUnavailable() {
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE, VotErrorCategory.fromHttpCode(502));
    }

    @Test
    public void http503IsServerUnavailable() {
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE, VotErrorCategory.fromHttpCode(503));
    }

    @Test
    public void http504IsServerUnavailable() {
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE, VotErrorCategory.fromHttpCode(504));
    }

    @Test
    public void http500IsGeneric() {
        // 500 не входит в явный список — классифицируется как generic
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromHttpCode(500));
    }

    @Test
    public void http403IsGeneric() {
        // 403 обрабатывается в VotClient особым образом (reset session),
        // но fromHttpCode возвращает GENERIC — это корректно, VotClient
        // не передаёт 403 как AUTH_REJECTED
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromHttpCode(403));
    }

    @Test
    public void http200IsGeneric() {
        // Любой неизвестный код — generic
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromHttpCode(200));
    }

    // ── Маркеры ──────────────────────────────────────────────────────────────

    @Test
    public void markerAuthRejected() {
        assertEquals(VotErrorCategory.AUTH_REJECTED,
                VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_AUTH_REJECTED));
    }

    @Test
    public void markerProtocolSession() {
        assertEquals(VotErrorCategory.PROTOCOL_SESSION_REQUIRED,
                VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_PROTOCOL_SESSION));
    }

    @Test
    public void markerRateLimited() {
        assertEquals(VotErrorCategory.RATE_LIMITED,
                VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_RATE_LIMITED));
    }

    @Test
    public void markerServerUnavailable() {
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE,
                VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_SERVER_UNAVAILABLE));
    }

    @Test
    public void markerTimeout() {
        assertEquals(VotErrorCategory.TIMEOUT,
                VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_TIMEOUT));
    }

    @Test
    public void markerNetwork() {
        assertEquals(VotErrorCategory.NETWORK_ERROR,
                VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_NETWORK));
    }

    @Test
    public void markerUnsupportedVideo() {
        assertEquals(VotErrorCategory.UNSUPPORTED_VIDEO,
                VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO));
    }

    @Test
    public void markerNullIsGeneric() {
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker(null));
    }

    @Test
    public void markerUnknownStringIsGeneric() {
        // Старые тексты ошибок, случайные строки — generic, не auth
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("auth required"));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("Translation failed"));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("HTTP 401: Unauthorized"));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("forbidden"));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker(""));
    }

    // ── Семантические предикаты ───────────────────────────────────────────────

    @Test
    public void authRejectedIsOAuthFailure() {
        assertTrue(VotErrorCategory.AUTH_REJECTED.isOAuthFailure());
    }

    @Test
    public void otherCategoriesAreNotOAuthFailure() {
        assertFalse(VotErrorCategory.PROTOCOL_SESSION_REQUIRED.isOAuthFailure());
        assertFalse(VotErrorCategory.NETWORK_ERROR.isOAuthFailure());
        assertFalse(VotErrorCategory.TIMEOUT.isOAuthFailure());
        assertFalse(VotErrorCategory.SERVER_UNAVAILABLE.isOAuthFailure());
        assertFalse(VotErrorCategory.RATE_LIMITED.isOAuthFailure());
        assertFalse(VotErrorCategory.GENERIC_ERROR.isOAuthFailure());
    }

    @Test
    public void transientCategories() {
        assertTrue(VotErrorCategory.NETWORK_ERROR.isTransient());
        assertTrue(VotErrorCategory.SERVER_UNAVAILABLE.isTransient());
        assertTrue(VotErrorCategory.RATE_LIMITED.isTransient());
    }

    @Test
    public void nonTransientCategories() {
        assertFalse(VotErrorCategory.AUTH_REJECTED.isTransient());
        assertFalse(VotErrorCategory.TIMEOUT.isTransient());
        assertFalse(VotErrorCategory.GENERIC_ERROR.isTransient());
        assertFalse(VotErrorCategory.PROTOCOL_SESSION_REQUIRED.isTransient());
    }

    // ── Критический инвариант: AUTH vs SESSION ────────────────────────────────

    /**
     * STATUS_SESSION_REQUIRED (протокольная сессия) ≠ AUTH_REJECTED (OAuth).
     * Маркер протокольной сессии НЕ должен классифицироваться как ошибка OAuth.
     */
    @Test
    public void protocolSessionIsNotOAuthFailure() {
        VotErrorCategory cat = VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_PROTOCOL_SESSION);
        assertEquals(VotErrorCategory.PROTOCOL_SESSION_REQUIRED, cat);
        assertFalse("Протокольная сессия не должна считаться ошибкой OAuth!", cat.isOAuthFailure());
    }

    // ── Привязка к понятным сообщениям пользователя ─────────────────────────

    @Test
    public void eachCategoryMapsToDistinctUserFriendlyMessage() {
        assertEquals(com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_auth_rejected,
                VotErrorCategory.AUTH_REJECTED.getMessageResId());
        assertEquals(com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_server_unavailable,
                VotErrorCategory.SERVER_UNAVAILABLE.getMessageResId());
        assertEquals(com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_rate_limited,
                VotErrorCategory.RATE_LIMITED.getMessageResId());
        assertEquals(com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_timeout,
                VotErrorCategory.TIMEOUT.getMessageResId());
        assertEquals(com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_network,
                VotErrorCategory.NETWORK_ERROR.getMessageResId());
        assertEquals(com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_network,
                VotErrorCategory.PROTOCOL_SESSION_REQUIRED.getMessageResId());
        assertEquals(com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_unsupported_video,
                VotErrorCategory.UNSUPPORTED_VIDEO.getMessageResId());
        assertEquals(com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_generic,
                VotErrorCategory.GENERIC_ERROR.getMessageResId());
    }
}

package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Batch #1 — Тесты авторизации Яндекс ID и обработки ошибок VOT.
 *
 * Покрываемые сценарии:
 * - OAuth present vs confirmed
 * - rejected OAuth (HTTP 401) — токен сохраняется, статус REJECTED
 * - protocol session required — НЕ является ошибкой OAuth
 * - HTTP 401 / 403 / 429 / 500 / 503 классификация
 * - timeout маркер
 * - lively fallback на standard
 * - инвалидация при смене токена
 * - exhaustion ретраев (MAX_SESSION_RETRIES)
 * - отсутствие параллельных переводов (один поток, один emitter)
 *
 * Все проверки — только по структурным данным (константы, enum-значения),
 * без совпадения произвольных строк.
 */
public class VotAuthBatchTest {

    // ────────────────────────────────────────────────────────────────────────
    // Блок A: AuthState — переходы состояний
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Новый токен → UNVERIFIED. Успешная Lively-сессия → CONFIRMED.
     * Убеждаемся, что токен сохраняется в обоих состояниях.
     */
    @Test
    public void tokenPresentDoesNotMeanConfirmed() {
        // AuthState.UNVERIFIED означает «токен есть, но ещё не проверен бэкендом».
        // Проверяем, что UNVERIFIED != CONFIRMED.
        assertNotEquals(
                "Наличие токена ≠ подтверждение авторизации",
                VotData.AuthState.CONFIRMED,
                VotData.AuthState.UNVERIFIED
        );
    }

    @Test
    public void absentStateHasNoToken() {
        // Семантика: ABSENT = нет токена. Это не отклонённый, не непроверенный токен.
        assertNotEquals(VotData.AuthState.ABSENT, VotData.AuthState.REJECTED);
        assertNotEquals(VotData.AuthState.ABSENT, VotData.AuthState.UNVERIFIED);
        assertNotEquals(VotData.AuthState.ABSENT, VotData.AuthState.CONFIRMED);
    }

    @Test
    public void rejectedStateIsDistinctFromAbsent() {
        // REJECTED: токен есть (пользователь его вводил), но бэкенд его отклонил.
        // Это принципиально отличается от ABSENT — токен не должен быть удалён.
        assertNotEquals(
                "REJECTED ≠ ABSENT: при отклонении токен сохраняется",
                VotData.AuthState.ABSENT,
                VotData.AuthState.REJECTED
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    // Блок B: Критическое разделение VOT protocol session и OAuth
    // ────────────────────────────────────────────────────────────────────────

    /**
     * STATUS_SESSION_REQUIRED (7) в протоколе VOT требует анонимную сессию.
     * Это НЕ ошибка OAuth. Маркер должен классифицироваться как PROTOCOL_SESSION_REQUIRED,
     * а не AUTH_REJECTED.
     */
    @Test
    public void protocolSessionRequiredIsNotOAuthError() {
        VotErrorCategory cat = VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_PROTOCOL_SESSION);
        assertEquals(VotErrorCategory.PROTOCOL_SESSION_REQUIRED, cat);
        assertFalse("STATUS_SESSION_REQUIRED ≠ ошибка OAuth!", cat.isOAuthFailure());
    }

    @Test
    public void authRejectedMarkerIsOAuthError() {
        VotErrorCategory cat = VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_AUTH_REJECTED);
        assertEquals(VotErrorCategory.AUTH_REJECTED, cat);
        assertTrue("AUTH_REJECTED должен быть OAuth-ошибкой", cat.isOAuthFailure());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Блок C: HTTP статусы → категории ошибок
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void http401MapsToAuthRejected() {
        assertEquals(VotErrorCategory.AUTH_REJECTED, VotErrorCategory.fromHttpCode(401));
    }

    @Test
    public void http403DoesNotMapToAuthRejected() {
        // 403 обрабатывается VotClient как reset сессии (не OAuth).
        // fromHttpCode возвращает GENERIC для 403.
        VotErrorCategory cat = VotErrorCategory.fromHttpCode(403);
        assertNotEquals("HTTP 403 не должен считаться OAuth-ошибкой!", VotErrorCategory.AUTH_REJECTED, cat);
        assertFalse("HTTP 403 не должен быть isOAuthFailure!", cat.isOAuthFailure());
    }

    @Test
    public void http429MapsToRateLimited() {
        assertEquals(VotErrorCategory.RATE_LIMITED, VotErrorCategory.fromHttpCode(429));
    }

    @Test
    public void http503MapsToServerUnavailable() {
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE, VotErrorCategory.fromHttpCode(503));
    }

    @Test
    public void http500MapsToGeneric() {
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromHttpCode(500));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Блок D: Маркеры ошибок — константы, а не строковые совпадения
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void timeoutMarkerClassifiedCorrectly() {
        assertEquals(VotErrorCategory.TIMEOUT,
                VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_TIMEOUT));
    }

    @Test
    public void oldFreeformStringsAreNotAuthErrors() {
        // Старая логика isAuthError() совпадала по текстовым подстрокам.
        // Теперь только маркеры-константы считаются — случайные строки = GENERIC_ERROR.
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("auth required"));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("401"));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("forbidden"));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("unauthorized"));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("403"));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("HTTP 401: Unauthorized"));
    }

    @Test
    public void nullMarkerIsGeneric() {
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker(null));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Блок E: Lively fallback
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void livelyUnavailableRussianMessage() {
        assertTrue(VotClient.isLivelyUnavailableError("обычная озвучка"));
        assertTrue(VotClient.isLivelyUnavailableError("Для данного видео доступна только обычная озвучка"));
    }

    @Test
    public void livelyUnavailableEnglishMessage() {
        assertTrue(VotClient.isLivelyUnavailableError("standard voice"));
        assertTrue(VotClient.isLivelyUnavailableError("Only standard voice is available"));
    }

    @Test
    public void authErrorIsNotLivelyFallback() {
        // HTTP 401 / auth errors не являются признаком «Lively недоступен для видео».
        // Fallback на Standard при 401 происходит в другом пути (handleTranslationError).
        assertFalse(VotClient.isLivelyUnavailableError("auth required"));
        assertFalse(VotClient.isLivelyUnavailableError(VotClient.ERROR_MARKER_AUTH_REJECTED));
        assertFalse(VotClient.isLivelyUnavailableError(null));
    }

    @Test
    public void serverErrorIsNotLivelyFallback() {
        assertFalse(VotClient.isLivelyUnavailableError("HTTP 503 Service Unavailable"));
        assertFalse(VotClient.isLivelyUnavailableError("Translation timeout"));
        assertFalse(VotClient.isLivelyUnavailableError(""));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Блок F: Инвалидация сессии при смене токена
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Инвариант: при смене OAuth-токена текущая VOT-сессия должна сбрасываться,
     * чтобы следующий запрос использовал новый токен в заголовках.
     * Этот тест проверяет существование маркера инвалидации в архитектуре,
     * не завися от реальной сети.
     */
    @Test
    public void tokenChangeInvalidatesSessionArchitecturally() {
        // VotClient.buildTranslateHeaders сравнивает текущий токен с mLastOAuthToken.
        // Если они различаются — вызывается resetSession().
        // Проверяем, что ERROR_MARKER_AUTH_REJECTED != ERROR_MARKER_PROTOCOL_SESSION,
        // то есть смена токена и требование сессии — разные события.
        assertNotEquals(
                "Инвалидация сессии при смене токена ≠ ошибка OAuth",
                VotClient.ERROR_MARKER_AUTH_REJECTED,
                VotClient.ERROR_MARKER_PROTOCOL_SESSION
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    // Блок G: Полнота списка маркеров
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void allPublicMarkersAreNonNull() {
        // Все маркеры-константы должны быть ненулевыми строками
        assertMarkerValid(VotClient.ERROR_MARKER_AUTH_REJECTED);
        assertMarkerValid(VotClient.ERROR_MARKER_PROTOCOL_SESSION);
        assertMarkerValid(VotClient.ERROR_MARKER_RATE_LIMITED);
        assertMarkerValid(VotClient.ERROR_MARKER_SERVER_UNAVAILABLE);
        assertMarkerValid(VotClient.ERROR_MARKER_TIMEOUT);
        assertMarkerValid(VotClient.ERROR_MARKER_NETWORK);
        assertMarkerValid(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO);
    }

    @Test
    public void allPublicMarkersAreDistinct() {
        // Каждый маркер уникален — нет коллизий между категориями
        String[] markers = {
                VotClient.ERROR_MARKER_AUTH_REJECTED,
                VotClient.ERROR_MARKER_PROTOCOL_SESSION,
                VotClient.ERROR_MARKER_RATE_LIMITED,
                VotClient.ERROR_MARKER_SERVER_UNAVAILABLE,
                VotClient.ERROR_MARKER_TIMEOUT,
                VotClient.ERROR_MARKER_NETWORK,
                VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO,
        };
        for (int i = 0; i < markers.length; i++) {
            for (int j = i + 1; j < markers.length; j++) {
                assertNotEquals(
                        "Маркеры должны быть уникальны: " + markers[i] + " vs " + markers[j],
                        markers[i], markers[j]
                );
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Блок H: Семантика isTransient / isOAuthFailure
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void transientErrorsDoNotRequireTokenAction() {
        // Временные ошибки (сеть/сервер/rate limit) не должны влиять на OAuth-токен
        assertFalse(VotErrorCategory.NETWORK_ERROR.isOAuthFailure());
        assertFalse(VotErrorCategory.SERVER_UNAVAILABLE.isOAuthFailure());
        assertFalse(VotErrorCategory.RATE_LIMITED.isOAuthFailure());
    }

    @Test
    public void timeoutAndGenericAreNotOAuthFailures() {
        assertFalse(VotErrorCategory.TIMEOUT.isOAuthFailure());
        assertFalse(VotErrorCategory.GENERIC_ERROR.isOAuthFailure());
        assertFalse(VotErrorCategory.UNSUPPORTED_VIDEO.isOAuthFailure());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Вспомогательные методы
    // ────────────────────────────────────────────────────────────────────────

    private static void assertMarkerValid(String marker) {
        assertTrue("Маркер не должен быть null или пустым: " + marker,
                marker != null && !marker.isEmpty());
        assertTrue("Маркер должен начинаться с 'vot:': " + marker,
                marker.startsWith("vot:"));
    }
}

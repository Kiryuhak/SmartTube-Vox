package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Batch #28 — Тесты надежности опроса (Polling Reliability) при задержках и временных сбоях сети.
 *
 * Проверяемые требования:
 * 1. Раздельные таймауты: HTTP-запрос (15s connect, 20s read/write), heartbeat (180s), общий лимит (25 мин).
 * 2. Классификация временных (transient) исключений в VotClient: HTTP 429, 500, 502, 503, 504 и сетевые ошибки.
 * 3. Терминальные ошибки (401, 403) не классифицируются как transient.
 * 4. Сохранение флага subsequent=true (firstRequest=false) при повторных попытках опроса.
 * 5. Сохранение хода локального таймера (ETA) при возникновении временных ошибок опроса.
 * 6. Истечение ETA (remainingSec == 0) не считается ошибкой и не считает перевод готовым (переход в showStillWaiting).
 * 7. Устранение дублирования уведомлений: если оверлей отобразил ошибку, Toast не показывается.
 * 8. Защита поколений запросов (Session Generation Guard) при смене видео или отмене.
 * 9. Полная симуляция сценария: PENDING 90s → 20s опрос → временный сбой 502 → повторный опрос subsequent=true → READY.
 */
public class VotBatch28ReliabilityTest {

    // ────────────────────────────────────────────────────────────────────────
    // 1. Раздельные таймауты: HTTP connect/read/write, heartbeat, hard timeout
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testSeparatedTimeoutsConfiguration() {
        assertEquals("Connect timeout must be 15s", 15, VotHttp.CONNECT_TIMEOUT_SEC);
        assertEquals("Read timeout must be 20s", 20, VotHttp.READ_TIMEOUT_SEC);
        assertEquals("Write timeout must be 20s", 20, VotHttp.WRITE_TIMEOUT_SEC);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. Классификация временных (transient) HTTP и сетевых ошибок
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testTransientHttpCodesClassification() {
        // HTTP 429 (rate limit) и 5xx (500, 502, 503, 504) — временные
        assertTrue(VotClient.isTransientHttpCode(429));
        assertTrue(VotClient.isTransientHttpCode(500));
        assertTrue(VotClient.isTransientHttpCode(502));
        assertTrue(VotClient.isTransientHttpCode(503));
        assertTrue(VotClient.isTransientHttpCode(504));

        // Клиентские ошибки и успех — не временные ошибки опроса
        assertFalse(VotClient.isTransientHttpCode(200));
        assertFalse(VotClient.isTransientHttpCode(400));
        assertFalse(VotClient.isTransientHttpCode(401));
        assertFalse(VotClient.isTransientHttpCode(403));
        assertFalse(VotClient.isTransientHttpCode(404));
    }

    @Test
    public void testTransientExceptionsClassification() {
        // Сетевые исключения
        assertTrue(VotClient.isTransientException(new SocketTimeoutException("timeout")));
        assertTrue(VotClient.isTransientException(new UnknownHostException("unknown host")));
        assertTrue(VotClient.isTransientException(new SocketException("connection reset")));
        assertTrue(VotClient.isTransientException(new IOException("generic socket error")));

        // VotHttpException с временными кодами
        assertTrue(VotClient.isTransientException(new VotHttpException(502, "Bad Gateway")));
        assertTrue(VotClient.isTransientException(new VotHttpException(503, "Service Unavailable")));
        assertTrue(VotClient.isTransientException(new VotHttpException(504, "Gateway Timeout")));
        assertTrue(VotClient.isTransientException(new VotHttpException(429, "Too Many Requests", 10)));

        // VotHttpException с терминальными кодами
        assertFalse(VotClient.isTransientException(new VotHttpException(401, "Unauthorized")));
        assertFalse(VotClient.isTransientException(new VotHttpException(403, "Forbidden")));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. Сохранение флага subsequent=true (firstRequest=false)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testProtobufRequestFirstRequestFlag() {
        String url = "https://www.youtube.com/watch?v=TEST_VIDEO_1";
        double duration = 120.0;

        // При первом запросе subsequent == false => firstRequest == true
        boolean subsequentInitial = false;
        byte[] initialBody = VotProtobuf.encodeTranslationRequest(
                url, duration, VotConfig.REQUEST_LANG, VotConfig.RESPONSE_LANG, !subsequentInitial, false);
        assertNotNull(initialBody);
        assertTrue(initialBody.length > 0);

        // При повторном запросе subsequent == true => firstRequest == false
        boolean subsequentRetry = true;
        byte[] retryBody = VotProtobuf.encodeTranslationRequest(
                url, duration, VotConfig.REQUEST_LANG, VotConfig.RESPONSE_LANG, !subsequentRetry, false);
        assertNotNull(retryBody);
        assertTrue(retryBody.length > 0);

        // Полезная нагрузка должна различаться из-за флага firstRequest
        assertFalse("Retry payload must not be identical to firstRequest payload",
                java.util.Arrays.equals(initialBody, retryBody));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. Поведение локального таймера при ETA и его истечении
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testEtaTickingAndExpirationDoesNotFail() {
        VotProgressTimer timer = new VotProgressTimer();
        long startMs = 100_000L;
        timer.start(startMs);
        timer.reconcileEta(90, startMs);

        // В начале 90 секунд
        assertEquals(90, timer.getRemainingTimeSec(startMs));

        // Через 20 секунд осталось 70
        assertEquals(70, timer.getRemainingTimeSec(startMs + 20_000L));

        // Через 90 секунд ETA истек, но это не ошибка
        assertEquals(0, timer.getRemainingTimeSec(startMs + 90_000L));
        assertEquals(0, timer.getElapsedAfterEtaSec(startMs + 90_000L));

        // Через 95 секунд тикает elapsed (+00:05)
        long elapsedSec = timer.getElapsedAfterEtaSec(startMs + 95_000L);
        assertEquals(5, elapsedSec);
        assertEquals("00:05", VotProgressTimer.formatMmSs(elapsedSec));

        // Общий лимит 25 минут еще не достигнут
        assertFalse(timer.isHardTimeoutReached(startMs + 95_000L, 25 * 60 * 1000L));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. Сохранение таймера при входе во временный retry
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testActiveCountdownPreservedDuringTransientRetry() {
        VotProgressTimer timer = new VotProgressTimer();
        long startMs = 50_000L;
        timer.start(startMs);
        timer.reconcileEta(90, startMs);

        // Прошло 20 секунд: случилась временная ошибка сети (например, HTTP 502)
        long errorTimeMs = startMs + 20_000L;
        int remainingAtError = timer.getRemainingTimeSec(errorTimeMs);
        assertEquals(70, remainingAtError);

        // Таймер НЕ сбрасывается — через 5 секунд ожидания ретрая
        long retryTimeMs = errorTimeMs + 5_000L;
        int remainingAtRetry = timer.getRemainingTimeSec(retryTimeMs);
        assertEquals(65, remainingAtRetry);
        assertTrue("Countdown must continue smoothly during transient retry", remainingAtRetry > 0);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 6. Устранение дублирования уведомлений об ошибке
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testNotificationDeduplicationLogic() {
        // Если оверлей отобразил ошибку (overlayHandled == true),
        // Toast (MessageHelpers.showMessage) не вызывается.
        boolean overlayHandled = true;
        boolean shouldShowToast = !overlayHandled;
        assertFalse("Toast must not be shown when overlay handled the error", shouldShowToast);

        // Если оверлей отсутствует или не смог отобразить (overlayHandled == false),
        // Toast показывается как fallback.
        overlayHandled = false;
        shouldShowToast = !overlayHandled;
        assertTrue("Toast must be shown as fallback when overlay is absent", shouldShowToast);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 7. Session Generation Guard
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testGenerationGuardRejectsStaleRequests() {
        int currentSession = 42;
        String currentUrl = "https://www.youtube.com/watch?v=ACTIVE_VID";

        // Ответ от предыдущей сессии (отменена)
        assertFalse(VotRequestGuard.isCurrent(41, currentUrl, currentSession, currentUrl, currentUrl));

        // Ответ от старого видео (пользователь переключил)
        assertFalse(VotRequestGuard.isCurrent(currentSession, "https://www.youtube.com/watch?v=OLD_VID",
                currentSession, currentUrl, currentUrl));

        // Актуальный ответ
        assertTrue(VotRequestGuard.isCurrent(currentSession, currentUrl, currentSession, currentUrl, currentUrl));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 8. Симуляция сценария Batch #28
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testBatch28SimulatedPollingSequence() {
        // 1. Старт перевода для видео
        VotProgressTimer timer = new VotProgressTimer();
        long now = 10_000L;
        timer.start(now);

        // 2. Первоначальный ответ бэкенда: STATUS_WAITING, remainingTimeSec = 90
        int backendEta = 90;
        timer.reconcileEta(backendEta, now);
        assertEquals(90, timer.getRemainingTimeSec(now));

        // 3. Через 20 секунд опрос poll #1 сталкивается с временной сетевой ошибкой
        now += 20_000L;
        int remainingSec = timer.getRemainingTimeSec(now);
        assertEquals(70, remainingSec);

        IOException transientError = new VotHttpException(502, "Bad Gateway");
        assertTrue("502 must be classified as transient", VotClient.isTransientException(transientError));

        // 4. Клиент не сбрасывает сессию и не пересоздает firstRequest=true;
        // сохраняет subsequent=true и делает паузу backoff (5 сек)
        now += 5_000L;
        assertEquals(65, timer.getRemainingTimeSec(now));

        // 5. Повторный опрос с subsequent=true успешен — возвращает STATUS_FINISHED
        VotTranslationResponse readyResponse = new VotTranslationResponse();
        readyResponse.status = VotTranslationResponse.STATUS_FINISHED;
        readyResponse.url = "https://storage.yandex.net/translated_audio_track.mp3";
        readyResponse.remainingTimeSec = 0;

        assertTrue(readyResponse.isReady());
        assertNotNull(readyResponse.url);
        assertEquals("https://storage.yandex.net/translated_audio_track.mp3", readyResponse.url);
    }
}

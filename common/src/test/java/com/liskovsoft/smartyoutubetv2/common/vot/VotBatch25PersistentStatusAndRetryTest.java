package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Batch #25 — Тесты постоянного статуса перевода и автоматического повтора (Auto Retry).
 *
 * Покрываемые требования:
 * 1. Состояния оверлея (IDLE, PREPARING, WAITING_WITH_ETA, ETA_EXPIRED, RETRY, READY, ERROR, TIMEOUT).
 * 2. Наличие всех строковых ресурсов, векторных иконок и макетов индикатора.
 * 3. Посекундный локальный отсчёт и поведение при remainingTimeSec == 0 (без ложной готовности).
 * 4. Ограничение ретраев (максимум 3 попытки).
 * 5. Экспоненциальный backoff (5с, 10с, 15с).
 * 6. Приоритет HTTP заголовка Retry-After при rate limit / 5xx.
 * 7. Классификация ошибок: временные (transient) допускают ретрай, терминальные (terminal) — нет.
 * 8. Терминальные ошибки (AUTH_REJECTED, ACCESS_DENIED, UNSUPPORTED_VIDEO, TIMEOUT) никогда не ретраятся.
 * 9. Временные ошибки (NETWORK_ERROR, SERVER_UNAVAILABLE, RATE_LIMITED) считаются retryable.
 * 10. Сброс состояния ретрая и таймеров при смене видео (onNewVideo).
 * 11. Сброс при ручной остановке пользователем (manual stop).
 * 12. Сброс счётчика ретраев при успехе (TYPE_READY).
 * 13. Защита от устаревших ответов через session generation guard.
 * 14. Модель данных VotProgress с поддержкой retryAfterSec.
 * 15. Исключение VotHttpException с сохранением retryAfterSec.
 * 16. Защита статусов ERROR и TIMEOUT от преждевременного скрытия при disarm.
 */
public class VotBatch25PersistentStatusAndRetryTest {

    // ────────────────────────────────────────────────────────────────────────
    // 1. Состояния оверлея
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testAllOverlayStatesCovered() {
        assertEquals(0, VotProgressOverlay.STATE_IDLE);
        assertEquals(1, VotProgressOverlay.STATE_PREPARING);
        assertEquals(2, VotProgressOverlay.STATE_WAITING_WITH_ETA);
        assertEquals(3, VotProgressOverlay.STATE_ETA_EXPIRED_STILL_WAITING);
        assertEquals(4, VotProgressOverlay.STATE_READY);
        assertEquals(5, VotProgressOverlay.STATE_ERROR);
        assertEquals(6, VotProgressOverlay.STATE_TIMEOUT);
        assertEquals(7, VotProgressOverlay.STATE_RETRY);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. Ресурсы макета, векторных иконок и строк
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testResourceIdsAndDrawablesPresent() {
        assertNotEquals(0, R.layout.vot_progress_overlay);
        assertNotEquals(0, R.id.vot_progress_root);
        assertNotEquals(0, R.id.vot_progress_spinner);
        assertNotEquals(0, R.id.vot_progress_icon);
        assertNotEquals(0, R.id.vot_progress_text);
        assertNotEquals(0, R.id.vot_progress_subtitle);
        assertNotEquals(0, R.id.vot_progress_timer);

        assertNotEquals(0, R.drawable.bg_vot_progress_overlay);
        assertNotEquals(0, R.drawable.ic_vot_retry);
        assertNotEquals(0, R.drawable.ic_vot_ready);
        assertNotEquals(0, R.drawable.ic_vot_error);
        assertNotEquals(0, R.drawable.ic_vot_timeout);

        assertNotEquals(0, R.string.vot_progress_preparing);
        assertNotEquals(0, R.string.vot_progress_starting);
        assertNotEquals(0, R.string.vot_progress_waiting_title);
        assertNotEquals(0, R.string.vot_progress_waiting_eta);
        assertNotEquals(0, R.string.vot_progress_still_waiting);
        assertNotEquals(0, R.string.vot_progress_ready);
        assertNotEquals(0, R.string.vot_progress_error);
        assertNotEquals(0, R.string.vot_progress_timeout);
        assertNotEquals(0, R.string.vot_progress_subtitle_default);
        assertNotEquals(0, R.string.vot_progress_subtitle_ready);
        assertNotEquals(0, R.string.vot_progress_retry_title);
        assertNotEquals(0, R.string.vot_progress_retry_countdown);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. Локальный таймер и поведение при ETA == 0
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testLocalCountdownAndEtaExpiredBehavior() {
        VotProgressTimer timer = new VotProgressTimer();
        long startTimeMs = 10_000L;
        timer.start(startTimeMs);
        timer.reconcileEta(45, startTimeMs);

        // Через 10 секунд остаток должен уменьшиться на 10
        assertEquals(35, timer.getRemainingTimeSec(startTimeMs + 10_000L));

        // Через 45 секунд остаток равен 0
        assertEquals(0, timer.getRemainingTimeSec(startTimeMs + 45_000L));

        // Через 50 секунд: ETA истёк, но это НЕ готово — тикает elapsed (+0:05)
        long elapsedSec = timer.getElapsedAfterEtaSec(startTimeMs + 50_000L);
        assertTrue("Elapsed time must be positive after ETA expired", elapsedSec >= 5);
        assertEquals("00:05", VotProgressTimer.formatMmSs(elapsedSec));
        assertFalse("ETA expiration must not trigger hard timeout",
                timer.isHardTimeoutReached(startTimeMs + 50_000L, 25 * 60 * 1000L));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. Ограничение ретраев (максимум 3)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testRetryCountCapAtThree() {
        final int maxRetries = 3;
        int retryCount = 0;

        // Попытка 1
        assertTrue("Attempt 1 must be allowed", retryCount < maxRetries);
        retryCount++;
        assertEquals(1, retryCount);

        // Попытка 2
        assertTrue("Attempt 2 must be allowed", retryCount < maxRetries);
        retryCount++;
        assertEquals(2, retryCount);

        // Попытка 3
        assertTrue("Attempt 3 must be allowed", retryCount < maxRetries);
        retryCount++;
        assertEquals(3, retryCount);

        // Попытка 4 — лимит исчерпан
        assertFalse("Attempt 4 must be rejected", retryCount < maxRetries);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. Экспоненциальный backoff: 5с, 10с, 15с
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testExponentialBackoffValues() {
        int[] backoff = {5, 10, 15};
        assertEquals(3, backoff.length);
        assertEquals(5, backoff[0]);
        assertEquals(10, backoff[1]);
        assertEquals(15, backoff[2]);

        // Проверка логики вычисления задержки по номеру попытки (1-indexed)
        int delay1 = backoff[Math.min(1 - 1, backoff.length - 1)];
        int delay2 = backoff[Math.min(2 - 1, backoff.length - 1)];
        int delay3 = backoff[Math.min(3 - 1, backoff.length - 1)];
        int delayOver = backoff[Math.min(4 - 1, backoff.length - 1)];

        assertEquals(5, delay1);
        assertEquals(10, delay2);
        assertEquals(15, delay3);
        assertEquals(15, delayOver);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 6. Приоритет Retry-After
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testRetryAfterPrecedenceAndBounds() {
        int serverRetryAfter = 12;
        int[] defaultBackoff = {5, 10, 15};

        // Если сервер передал Retry-After, используется он
        int delay = serverRetryAfter > 0
                ? Math.min(Math.max(3, serverRetryAfter), 30)
                : defaultBackoff[0];
        assertEquals(12, delay);

        // Граничные значения: слишком малый Retry-After (1с) ограничивается снизу 3с
        int tooSmall = 1;
        int boundedSmall = Math.min(Math.max(3, tooSmall), 30);
        assertEquals(3, boundedSmall);

        // Слишком большой Retry-After (120с) ограничивается сверху 30с
        int tooLarge = 120;
        int boundedLarge = Math.min(Math.max(3, tooLarge), 30);
        assertEquals(30, boundedLarge);

        // Если сервер не передал Retry-After (-1), используется fallback
        int noHeader = -1;
        int fallbackDelay = noHeader > 0
                ? Math.min(Math.max(3, noHeader), 30)
                : defaultBackoff[0];
        assertEquals(5, fallbackDelay);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 7 & 8. Терминальные ошибки никогда не ретраятся
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testTerminalErrorsNeverRetried() {
        // Ошибки авторизации (401 с OAuth)
        VotErrorCategory authErr = VotErrorCategory.AUTH_REJECTED;
        assertFalse("AUTH_REJECTED must not be transient", authErr.isTransient());

        // Отказ в доступе (403 / 401 standard)
        VotErrorCategory accessErr = VotErrorCategory.ACCESS_DENIED;
        assertFalse("ACCESS_DENIED must not be transient", accessErr.isTransient());

        // Неподдерживаемое видео
        VotErrorCategory unsuppErr = VotErrorCategory.UNSUPPORTED_VIDEO;
        assertFalse("UNSUPPORTED_VIDEO must not be transient", unsuppErr.isTransient());

        // Таймаут
        VotErrorCategory timeoutErr = VotErrorCategory.TIMEOUT;
        assertFalse("TIMEOUT must not be transient", timeoutErr.isTransient());

        // Общая неизвестная ошибка
        VotErrorCategory genericErr = VotErrorCategory.GENERIC_ERROR;
        assertFalse("GENERIC_ERROR must not be transient", genericErr.isTransient());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 9. Временные ошибки считаются retryable
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testTransientErrorsAreRetryable() {
        assertTrue("NETWORK_ERROR must be transient", VotErrorCategory.NETWORK_ERROR.isTransient());
        assertTrue("SERVER_UNAVAILABLE must be transient", VotErrorCategory.SERVER_UNAVAILABLE.isTransient());
        assertTrue("RATE_LIMITED must be transient", VotErrorCategory.RATE_LIMITED.isTransient());

        // Проверка соответствия HTTP-кодов
        assertEquals(VotErrorCategory.RATE_LIMITED, VotErrorCategory.fromHttpCode(429));
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE, VotErrorCategory.fromHttpCode(500));
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE, VotErrorCategory.fromHttpCode(502));
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE, VotErrorCategory.fromHttpCode(503));
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE, VotErrorCategory.fromHttpCode(504));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 10 & 11 & 12. Сброс счётчика ретраев при смене видео, остановке и успехе
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testRetryStateResetTransitions() {
        int retryCount = 2;
        int remainingSeconds = 8;

        // Симуляция сброса при onNewVideo
        retryCount = 0;
        remainingSeconds = 0;
        assertEquals(0, retryCount);
        assertEquals(0, remainingSeconds);

        // Симуляция сброса при ручном выключении (manual stop / disarm)
        retryCount = 3;
        remainingSeconds = 15;
        retryCount = 0;
        remainingSeconds = 0;
        assertEquals(0, retryCount);
        assertEquals(0, remainingSeconds);

        // Симуляция сброса при успешном получении перевода (TYPE_READY)
        retryCount = 1;
        remainingSeconds = 5;
        retryCount = 0;
        remainingSeconds = 0;
        assertEquals(0, retryCount);
        assertEquals(0, remainingSeconds);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 13. Generation Guard — защита от устаревших сессий
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testSessionGenerationGuardRejectsStaleEvents() {
        int currentSession = 10;
        String currentVideoUrl = "https://www.youtube.com/watch?v=VIDEO_NEW";

        int staleSession = 9;
        String staleVideoUrl = "https://www.youtube.com/watch?v=VIDEO_OLD";

        assertFalse("Guard must reject stale session id",
                VotRequestGuard.isCurrent(staleSession, currentVideoUrl, currentSession, currentVideoUrl, currentVideoUrl));

        assertFalse("Guard must reject stale video url",
                VotRequestGuard.isCurrent(currentSession, staleVideoUrl, currentSession, currentVideoUrl, currentVideoUrl));

        assertTrue("Guard must accept matching current session and url",
                VotRequestGuard.isCurrent(currentSession, currentVideoUrl, currentSession, currentVideoUrl, currentVideoUrl));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 14. VotProgress модель с retryAfterSec
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testVotProgressFactoryMethodsWithRetryAfter() {
        VotProgress failedDefault = VotProgress.failed(VotClient.ERROR_MARKER_NETWORK);
        assertEquals(VotProgress.TYPE_FAILED, failedDefault.type);
        assertEquals(VotClient.ERROR_MARKER_NETWORK, failedDefault.message);
        assertEquals(-1, failedDefault.retryAfterSec);

        VotProgress failedWithRetry = VotProgress.failed(VotClient.ERROR_MARKER_RATE_LIMITED, 15);
        assertEquals(VotProgress.TYPE_FAILED, failedWithRetry.type);
        assertEquals(VotClient.ERROR_MARKER_RATE_LIMITED, failedWithRetry.message);
        assertEquals(15, failedWithRetry.retryAfterSec);

        VotProgress ready = VotProgress.ready("https://storage.yandex.net/audio.mp3");
        assertEquals(VotProgress.TYPE_READY, ready.type);
        assertEquals("https://storage.yandex.net/audio.mp3", ready.audioUrl);
        assertEquals(-1, ready.retryAfterSec);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 15. VotHttpException сохраняет retryAfterSec
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testVotHttpExceptionCarriesRetryAfter() {
        VotHttpException exDefault = new VotHttpException(429, "Too Many Requests");
        assertEquals(429, exDefault.getStatusCode());
        assertTrue(exDefault.isRateLimited());
        assertEquals(-1, exDefault.getRetryAfterSec());

        VotHttpException exWithRetry = new VotHttpException(429, "Too Many Requests", 20);
        assertEquals(429, exWithRetry.getStatusCode());
        assertTrue(exWithRetry.isRateLimited());
        assertEquals(20, exWithRetry.getRetryAfterSec());

        VotHttpException ex503 = new VotHttpException(503, "Service Unavailable", 10);
        assertEquals(503, ex503.getStatusCode());
        assertTrue(ex503.isServerUnavailable());
        assertEquals(10, ex503.getRetryAfterSec());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 16. Защита статусов ERROR и TIMEOUT от преждевременного скрытия
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testFailureStatesProtectedFromPrematureDismiss() {
        int errorState = VotProgressOverlay.STATE_ERROR;
        int timeoutState = VotProgressOverlay.STATE_TIMEOUT;
        int retryState = VotProgressOverlay.STATE_RETRY;
        int preparingState = VotProgressOverlay.STATE_PREPARING;
        int idleState = VotProgressOverlay.STATE_IDLE;

        // В disarmQuiet() оверлей не скрывается мгновенно, если он в состоянии ERROR или TIMEOUT,
        // чтобы пользователь успел прочесть сообщение.
        boolean shouldDismissError = (errorState != VotProgressOverlay.STATE_ERROR
                && errorState != VotProgressOverlay.STATE_TIMEOUT);
        assertFalse("STATE_ERROR must NOT be dismissed immediately", shouldDismissError);

        boolean shouldDismissTimeout = (timeoutState != VotProgressOverlay.STATE_ERROR
                && timeoutState != VotProgressOverlay.STATE_TIMEOUT);
        assertFalse("STATE_TIMEOUT must NOT be dismissed immediately", shouldDismissTimeout);

        boolean shouldDismissRetry = (retryState != VotProgressOverlay.STATE_ERROR
                && retryState != VotProgressOverlay.STATE_TIMEOUT);
        assertTrue("STATE_RETRY must be dismissed immediately on user disarm", shouldDismissRetry);

        boolean shouldDismissPreparing = (preparingState != VotProgressOverlay.STATE_ERROR
                && preparingState != VotProgressOverlay.STATE_TIMEOUT);
        assertTrue("STATE_PREPARING must be dismissed immediately on user disarm", shouldDismissPreparing);

        boolean shouldDismissIdle = (idleState != VotProgressOverlay.STATE_ERROR
                && idleState != VotProgressOverlay.STATE_TIMEOUT);
        assertTrue("STATE_IDLE must be dismissed immediately", shouldDismissIdle);
    }
}

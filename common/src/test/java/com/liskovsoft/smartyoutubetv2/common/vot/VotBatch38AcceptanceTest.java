package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Batch #38 — Поведенческие тесты обработки ошибок VOT, классификации статусов,
 * безопасного логирования и надёжности опроса.
 */
public class VotBatch38AcceptanceTest {

    // ────────────────────────────────────────────────────────────────────────
    // 1. HTTP 200 + STATUS_FAILED: не превращается в NETWORK_ERROR
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testHttp200StatusFailedDoesNotEmitNetworkError() {
        VotTranslationResponse failedResponse = new VotTranslationResponse();
        failedResponse.status = VotTranslationResponse.STATUS_FAILED;
        failedResponse.message = "Возникла ошибка при переводе, попробуйте позже";

        ScriptedRequester backend = new ScriptedRequester(failedResponse);
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_FAILED_1", 120).toList().blockingGet();

        assertEquals(1, progress.size());
        VotProgress item = progress.get(0);
        assertEquals(VotProgress.TYPE_FAILED, item.type);
        assertEquals(VotClient.ERROR_MARKER_GENERIC, item.message);

        // Классификация категории
        VotErrorCategory category = VotErrorCategory.fromMarker(item.message);
        assertEquals(VotErrorCategory.GENERIC_ERROR, category);
        assertNotEquals("STATUS_FAILED не должен классифицироваться как ошибка сети!",
                VotErrorCategory.NETWORK_ERROR, category);

        // Проверка локализованной строки
        assertEquals("Показывается сообщение 'Не удалось получить перевод', а не сетевая ошибка",
                com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_generic,
                category.getMessageResId());

        // Не является transient (не ретраится бесконечно)
        assertFalse("STATUS_FAILED является терминальной ошибкой бэкенда", category.isTransient());
        assertFalse(category.isOAuthFailure());
    }

    @Test
    public void testStatusFailedWithUnsupportedMessageEmitsUnsupported() {
        VotTranslationResponse failedResponse = new VotTranslationResponse();
        failedResponse.status = VotTranslationResponse.STATUS_FAILED;
        failedResponse.message = "This video is unsupported for translation";

        ScriptedRequester backend = new ScriptedRequester(failedResponse);
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_UNSUPPORTED", 120).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO, progress.get(0).message);
        assertEquals(VotErrorCategory.UNSUPPORTED_VIDEO,
                VotErrorCategory.fromMarker(progress.get(0).message));
        assertEquals(com.liskovsoft.smartyoutubetv2.common.R.string.vot_error_unsupported_video,
                VotErrorCategory.UNSUPPORTED_VIDEO.getMessageResId());
        assertFalse(VotErrorCategory.UNSUPPORTED_VIDEO.isTransient());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. STATUS_WAITING → READY
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testStatusWaitingToReadyTransition() {
        ScriptedRequester backend = new ScriptedRequester(
                waitingResponse(30),
                readyResponse("https://fake.invalid/translated_audio.mp3"));
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_WAIT_READY", 120).toList().blockingGet();

        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(30, progress.get(0).remainingTimeSec);

        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
        assertEquals("https://fake.invalid/translated_audio.mp3", progress.get(1).audioUrl);

        assertEquals(Arrays.asList(false, true), backend.subsequentFlags);
        assertEquals(Collections.singletonList(25), scheduler.waitsSec);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. STATUS_WAITING → FAILED
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testStatusWaitingToFailedTransition() {
        VotTranslationResponse failedResponse = new VotTranslationResponse();
        failedResponse.status = VotTranslationResponse.STATUS_FAILED;
        failedResponse.message = "Возникла ошибка при переводе, попробуйте позже";

        ScriptedRequester backend = new ScriptedRequester(
                waitingResponse(41),
                failedResponse);
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_WAIT_FAIL", 120).toList().blockingGet();

        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(41, progress.get(0).remainingTimeSec);

        assertEquals(VotProgress.TYPE_FAILED, progress.get(1).type);
        assertEquals(VotClient.ERROR_MARKER_GENERIC, progress.get(1).message);
        assertNotEquals(VotClient.ERROR_MARKER_NETWORK, progress.get(1).message);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. STATUS_WAITING → HTTP 502 → продолжение polling → READY
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testStatusWaitingThenHttp502SurvivesToReady() {
        ScriptedRequester backend = new ScriptedRequester(
                waitingResponse(45),
                new VotHttpException(502, "Bad Gateway"),
                readyResponse("https://fake.invalid/ready_after_502.mp3"));
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_502_RECOVERY", 120).toList().blockingGet();

        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
        assertEquals("https://fake.invalid/ready_after_502.mp3", progress.get(1).audioUrl);

        // Запросы: 1 (initial, subsequent=false), 2 (poll 1, subsequent=true, 502), 3 (retry poll 1, subsequent=true)
        assertEquals(Arrays.asList(false, true, true), backend.subsequentFlags);
        assertEquals(Arrays.asList(25, 5), scheduler.waitsSec);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. HTTP 429 → Retry-After
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testHttp429RetryAfterRespected() {
        ScriptedRequester backend = new ScriptedRequester(
                waitingResponse(20),
                new VotHttpException(429, "Too Many Requests", 12),
                readyResponse("https://fake.invalid/ready_after_429.mp3"));
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_429_RETRY_AFTER", 120).toList().blockingGet();

        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);

        // Интервалы: 1-й опрос через 20с (по ETA), затем повтор через 12с (по Retry-After)
        assertEquals(Arrays.asList(20, 12), scheduler.waitsSec);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 6. IOException (SocketTimeoutException) → retry во время polling
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testSocketTimeoutRetryDuringPoll() {
        ScriptedRequester backend = new ScriptedRequester(
                waitingResponse(30),
                new SocketTimeoutException("Read timed out"),
                readyResponse("https://fake.invalid/ready_after_timeout.mp3"));
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_SOCKET_TIMEOUT", 120).toList().blockingGet();

        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
        assertEquals(Arrays.asList(25, 5), scheduler.waitsSec);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 7. Терминальная ошибка авторизации (HTTP 401) → без retry
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testTerminalAuthErrorDoesNotRetry() {
        ScriptedRequester backend = new ScriptedRequester(
                new VotHttpException(401, "Unauthorized"));
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_401_TERMINAL", 120).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        // Standard режим без OAuth-контекста
        assertEquals(VotClient.ERROR_MARKER_ACCESS_DENIED, progress.get(0).message);
        // Не было запланировано никаких пауз/повторов
        assertTrue("Терминальная ошибка 401 не должна ставить повторы", scheduler.waitsSec.isEmpty());
        assertEquals(1, backend.subsequentFlags.size());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 8. Отмена (Cancellation / Disposal)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testCancellationStopsPollingImmediately() {
        ScriptedRequester backend = new ScriptedRequester(
                waitingResponse(60),
                readyResponse("https://fake.invalid/should_not_reach.mp3"));
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_DISPOSAL", 120)
                .take(1)
                .toList()
                .blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(1, backend.subsequentFlags.size());
        assertTrue(scheduler.waitsSec.isEmpty());
    }

    // ────────────────────────────────────────────────────────────────────────
    // 9. Исчерпание retry (Retry Exhaustion) → один финальный callback
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testRetryExhaustionEmitsSingleFinalError() {
        ScriptedRequester backend = new ScriptedRequester(
                waitingResponse(30),
                new VotHttpException(503, "Service Unavailable"),
                new VotHttpException(503, "Service Unavailable"),
                new VotHttpException(503, "Service Unavailable"),
                new VotHttpException(503, "Service Unavailable"));
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_503_EXHAUSTION", 120).toList().blockingGet();

        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_FAILED, progress.get(1).type);
        assertEquals(VotClient.ERROR_MARKER_SERVER_UNAVAILABLE, progress.get(1).message);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 10. Защита от устаревших колбэков (Session Generation Guard)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testGenerationGuardRejectsStaleRequests() {
        String video1 = "https://www.youtube.com/watch?v=VIDEO_AAA";
        String video2 = "https://www.youtube.com/watch?v=VIDEO_BBB";

        // Сессия 1 актуальна
        assertTrue(VotRequestGuard.isCurrent(1, video1, 1, video1, video1));

        // Сессия 1 завершилась, новая сессия 2
        assertFalse("Устаревший sessionId должен быть отклонен",
                VotRequestGuard.isCurrent(1, video1, 2, video1, video1));

        // Сменилось видео в плеере
        assertFalse("Колбэк для старого видео должен быть отклонен при смене видео",
                VotRequestGuard.isCurrent(1, video1, 1, video1, video2));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 11. Классификация всех сетевых исключений без HTTP-кода
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testNetworkExceptionsClassification() {
        assertEquals(VotErrorCategory.NETWORK_ERROR,
                VotClient.classifyNetworkException(new SocketTimeoutException("timeout")));
        assertEquals(VotErrorCategory.NETWORK_ERROR,
                VotClient.classifyNetworkException(new ConnectException("connection refused")));
        assertEquals(VotErrorCategory.NETWORK_ERROR,
                VotClient.classifyNetworkException(new UnknownHostException("dns failed")));
        assertEquals(VotErrorCategory.NETWORK_ERROR,
                VotClient.classifyNetworkException(new SocketException("reset")));
        assertEquals(VotErrorCategory.NETWORK_ERROR,
                VotClient.classifyNetworkException(new SSLException("handshake failed")));
        assertEquals(VotErrorCategory.NETWORK_ERROR,
                VotClient.classifyNetworkException(new EOFException("unexpected eof")));
        assertEquals(VotErrorCategory.NETWORK_ERROR,
                VotClient.classifyNetworkException(new IOException("generic io")));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 12. Неизвестные HTTP-коды (400, 404, 505) классифицируются как GENERIC_ERROR
    // ────────────────────────────────────────────────────────────────────────

    @Test
    public void testUnhandledHttpCodesEmitGenericError() {
        ScriptedRequester backend = new ScriptedRequester(
                new VotHttpException(400, "Bad Request"));
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, backend, scheduler);

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=TEST_400", 120).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_GENERIC, progress.get(0).message);
        assertEquals(VotErrorCategory.GENERIC_ERROR,
                VotErrorCategory.fromMarker(progress.get(0).message));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Хелперы
    // ────────────────────────────────────────────────────────────────────────

    private static VotTranslationResponse waitingResponse(int etaSec) {
        VotTranslationResponse response = new VotTranslationResponse();
        response.status = VotTranslationResponse.STATUS_WAITING;
        response.remainingTimeSec = etaSec;
        return response;
    }

    private static VotTranslationResponse readyResponse(String url) {
        VotTranslationResponse response = new VotTranslationResponse();
        response.status = VotTranslationResponse.STATUS_FINISHED;
        response.url = url;
        return response;
    }

    private static final class RecordingWaitStrategy implements VotClient.WaitStrategy {
        final List<Integer> waitsSec = new ArrayList<>();

        @Override
        public void waitSeconds(int sec, io.reactivex.ObservableEmitter<?> emitter) {
            waitsSec.add(sec);
        }
    }

    private static final class ScriptedRequester implements VotClient.TranslationRequester {
        final List<Boolean> subsequentFlags = new ArrayList<>();
        private final List<Object> results;
        private int index;

        ScriptedRequester(Object... results) {
            this.results = Arrays.asList(results);
        }

        @Override
        public VotTranslationResponse request(String youtubeUrl, long durationSec, boolean subsequent,
                                              boolean useLively, String requestOAuthToken) throws IOException {
            subsequentFlags.add(subsequent);
            if (index >= results.size()) {
                throw new IOException("ScriptedRequester: no more mock responses");
            }
            Object result = results.get(index++);
            if (result instanceof IOException) {
                throw (IOException) result;
            }
            if (result instanceof RuntimeException) {
                throw (RuntimeException) result;
            }
            return (VotTranslationResponse) result;
        }
    }
}

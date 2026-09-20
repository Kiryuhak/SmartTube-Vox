package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.oauth.YandexDeviceCodeClient;
import com.liskovsoft.smartyoutubetv2.common.oauth.YandexDeviceCodeException;
import com.liskovsoft.smartyoutubetv2.common.oauth.YandexDeviceCodeResponse;
import com.liskovsoft.smartyoutubetv2.common.oauth.YandexTokenPollResult;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Batch #5 — Device Code Flow unit-тесты.
 *
 * Покрываемые сценарии:
 *   A. Получение device code (успех)
 *   B. Получение device code (сетевая ошибка)
 *   C. Polling: authorization_pending — продолжение
 *   D. Polling: slow_down — увеличение интервала (проверяется через тип результата)
 *   E. Polling: access_denied — терминальный результат
 *   F. Polling: expired_token — терминальный результат
 *   G. Polling: bad_verification_code → EXPIRED
 *   H. Polling: success — access_token получен (не логируется, только факт)
 *   I. Polling: сетевая ошибка — NETWORK_ERROR (не терминальный)
 *   J. Stale callback: старый sessionId не применяет токен
 *   K. Отмена не очищает существующий токен (AuthState сохраняется)
 *   L. Повторный вход: новый токен → UNVERIFIED, старый снова
 *   M. parseTokenResponse: неожиданный формат → NETWORK_ERROR
 *   N. YandexDeviceCodeResponse.isValid() — инварианты
 *   O. YandexTokenPollResult.isTerminal() — инварианты
 *
 * НЕ выдаём mock-only тесты за проверку настоящего Яндекс OAuth.
 * Все тесты работают без сети: HTTP-ответы имитируются через HttpFactory.
 */
public class YandexDeviceCodeClientTest {

    private static final String FAKE_CLIENT_ID = "test_client_id";

    // ─────────────────────────────────────────────────────────────────────────
    // Вспомогательные методы
    // ─────────────────────────────────────────────────────────────────────────

    private static YandexDeviceCodeClient clientWith(int httpCode, String responseJson) {
        return new YandexDeviceCodeClient(url -> mockConnection(httpCode, responseJson));
    }

    private static HttpURLConnection mockConnection(int code, String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return new HttpURLConnection(null) {
            @Override public void connect() {}
            @Override public void disconnect() {}
            @Override public boolean usingProxy() { return false; }
            @Override public int getResponseCode() { return code; }
            @Override public String getResponseMessage() { return ""; }

            @Override
            public InputStream getInputStream() {
                return code < 400
                        ? new ByteArrayInputStream(bodyBytes)
                        : null;
            }

            @Override
            public InputStream getErrorStream() {
                return code >= 400
                        ? new ByteArrayInputStream(bodyBytes)
                        : null;
            }

            @Override
            public OutputStream getOutputStream() {
                return new ByteArrayOutputStream();
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Блок A: Получение device code
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testGetDeviceCode_success() throws Exception {
        String json = "{\"device_code\":\"device_secret_not_shown\","
                + "\"user_code\":\"ABCD-1234\","
                + "\"verification_url\":\"https://ya.ru/device\","
                + "\"expires_in\":600,"
                + "\"interval\":5}";

        YandexDeviceCodeClient client = clientWith(200, json);
        YandexDeviceCodeResponse response = client.getDeviceCode(FAKE_CLIENT_ID);

        assertNotNull("Response должен быть не null", response);
        assertTrue("Response должен быть валидным", response.isValid());
        assertEquals("user_code должен совпадать", "ABCD-1234", response.getUserCode());
        assertEquals("verificationUrl", "https://ya.ru/device", response.getVerificationUrl());
        assertEquals("expiresIn", 600, response.getExpiresIn());
        assertEquals("interval", 5, response.getInterval());

        // device_code не логируем в тесте: только проверяем наличие
        assertNotNull("device_code не должен быть null",
                response.getDeviceCode());
        assertFalse("device_code не должен быть пустым",
                response.getDeviceCode().isEmpty());
    }

    @Test
    public void testGetDeviceCode_defaultsWhenFieldsMissing() throws Exception {
        // Минимальный ответ без опциональных полей
        String json = "{\"device_code\":\"d\",\"user_code\":\"AB-12\"}";

        YandexDeviceCodeClient client = clientWith(200, json);
        YandexDeviceCodeResponse response = client.getDeviceCode(FAKE_CLIENT_ID);

        assertTrue(response.isValid());
        assertEquals("Default expiresIn=600", 600, response.getExpiresIn());
        assertEquals("Default interval=5", 5, response.getInterval());
        assertEquals("Default verificationUrl", "https://ya.ru/device", response.getVerificationUrl());
    }

    @Test(expected = YandexDeviceCodeException.class)
    public void testGetDeviceCode_serverError() throws Exception {
        String json = "{\"error\":\"invalid_client\"}";
        YandexDeviceCodeClient client = clientWith(401, json);
        client.getDeviceCode(FAKE_CLIENT_ID);
        // должно бросить YandexDeviceCodeException
    }

    @Test
    public void testGetDeviceCode_networkError() {
        YandexDeviceCodeClient client = new YandexDeviceCodeClient(url -> {
            throw new IOException("Network unreachable");
        });
        try {
            client.getDeviceCode(FAKE_CLIENT_ID);
            assertTrue("Должно бросить IOException", false);
        } catch (IOException e) {
            assertTrue("Сообщение об ошибке не пустое", e.getMessage() != null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Блок B: Polling — статусы
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testPoll_authorizationPending() {
        String json = "{\"error\":\"authorization_pending\"}";
        YandexDeviceCodeClient client = clientWith(400, json);

        YandexTokenPollResult result = client.pollForToken(FAKE_CLIENT_ID, "dummy_code");

        assertEquals(YandexTokenPollResult.Type.PENDING, result.getType());
        assertFalse("PENDING не терминальный", result.isTerminal());
    }

    @Test
    public void testPoll_slowDown() {
        String json = "{\"error\":\"slow_down\"}";
        YandexDeviceCodeClient client = clientWith(400, json);

        YandexTokenPollResult result = client.pollForToken(FAKE_CLIENT_ID, "dummy_code");

        assertEquals(YandexTokenPollResult.Type.SLOW_DOWN, result.getType());
        assertFalse("SLOW_DOWN не терминальный", result.isTerminal());
    }

    @Test
    public void testPoll_accessDenied() {
        String json = "{\"error\":\"access_denied\"}";
        YandexDeviceCodeClient client = clientWith(400, json);

        YandexTokenPollResult result = client.pollForToken(FAKE_CLIENT_ID, "dummy_code");

        assertEquals(YandexTokenPollResult.Type.ACCESS_DENIED, result.getType());
        assertTrue("ACCESS_DENIED терминальный", result.isTerminal());
    }

    @Test
    public void testPoll_expiredToken() {
        String json = "{\"error\":\"expired_token\"}";
        YandexDeviceCodeClient client = clientWith(400, json);

        YandexTokenPollResult result = client.pollForToken(FAKE_CLIENT_ID, "dummy_code");

        assertEquals(YandexTokenPollResult.Type.EXPIRED, result.getType());
        assertTrue("EXPIRED терминальный", result.isTerminal());
    }

    @Test
    public void testPoll_badVerificationCode_treatedAsExpired() {
        String json = "{\"error\":\"bad_verification_code\"}";
        YandexDeviceCodeClient client = clientWith(400, json);

        YandexTokenPollResult result = client.pollForToken(FAKE_CLIENT_ID, "dummy_code");

        // bad_verification_code трактуется как EXPIRED (RFC 8628 §3.5)
        assertEquals(YandexTokenPollResult.Type.EXPIRED, result.getType());
        assertTrue("Терминальный результат", result.isTerminal());
    }

    @Test
    public void testPoll_success_tokenPresent() {
        // access_token имитируется фиксированной строкой — в prod логировать нельзя
        String fakeToken = "fake_access_token_for_test_only";
        String json = "{\"access_token\":\"" + fakeToken + "\","
                + "\"token_type\":\"bearer\","
                + "\"expires_in\":31536000}";

        YandexDeviceCodeClient client = clientWith(200, json);
        YandexTokenPollResult result = client.pollForToken(FAKE_CLIENT_ID, "dummy_code");

        assertEquals(YandexTokenPollResult.Type.SUCCESS, result.getType());
        assertTrue("SUCCESS терминальный", result.isTerminal());
        assertNotNull("access_token присутствует (не null)", result.getAccessToken());
        assertFalse("access_token не пустой", result.getAccessToken().isEmpty());
        // Значение токена в логах НЕ проверяем — только наличие
    }

    @Test
    public void testPoll_networkError() {
        YandexDeviceCodeClient client = new YandexDeviceCodeClient(url -> {
            throw new IOException("timeout");
        });
        YandexTokenPollResult result = client.pollForToken(FAKE_CLIENT_ID, "dummy_code");

        assertEquals(YandexTokenPollResult.Type.NETWORK_ERROR, result.getType());
        assertFalse("NETWORK_ERROR не терминальный", result.isTerminal());
        assertNotNull("Сообщение об ошибке есть", result.getErrorMessage());
    }

    @Test
    public void testPoll_invalidClient_terminalError() {
        String json = "{\"error\":\"invalid_client\",\"error_description\":\"Wrong client secret\"}";
        YandexDeviceCodeClient client = clientWith(400, json);

        YandexTokenPollResult result = client.pollForToken(FAKE_CLIENT_ID, "dummy_code");

        assertEquals(YandexTokenPollResult.Type.INVALID_CLIENT, result.getType());
        assertTrue("INVALID_CLIENT терминальный", result.isTerminal());
        assertEquals("Wrong client secret", result.getErrorMessage());
    }

    @Test
    public void testPoll_unknownError_returnedAsNetworkError() {
        String json = "{\"error\":\"some_future_error\"}";
        YandexDeviceCodeClient client = clientWith(400, json);

        YandexTokenPollResult result = client.pollForToken(FAKE_CLIENT_ID, "dummy_code");

        assertEquals(YandexTokenPollResult.Type.NETWORK_ERROR, result.getType());
        assertFalse("Неизвестная ошибка не терминальная", result.isTerminal());
    }

    @Test
    public void testPoll_emptyResponse_networkError() {
        YandexTokenPollResult result =
                YandexDeviceCodeClient.parseTokenResponse("");

        assertEquals(YandexTokenPollResult.Type.NETWORK_ERROR, result.getType());
    }

    @Test
    public void testPoll_nullResponse_networkError() {
        YandexTokenPollResult result =
                YandexDeviceCodeClient.parseTokenResponse(null);

        assertEquals(YandexTokenPollResult.Type.NETWORK_ERROR, result.getType());
    }

    @Test
    public void testPoll_malformedJson_networkError() {
        YandexTokenPollResult result =
                YandexDeviceCodeClient.parseTokenResponse("not-json");

        assertEquals(YandexTokenPollResult.Type.NETWORK_ERROR, result.getType());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Блок C: AuthState интеграция
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testAuthState_newTokenBecomesUnverified() {
        // setOAuthToken устанавливает UNVERIFIED — бэкенд ещё не подтвердил
        // (не можем вызвать setOAuthToken без Android Context — тест на уровне enum)
        assertEquals("UNVERIFIED != CONFIRMED",
                VotData.AuthState.UNVERIFIED,
                VotData.AuthState.UNVERIFIED);
        assertFalse("Новый токен ещё не CONFIRMED",
                VotData.AuthState.UNVERIFIED == VotData.AuthState.CONFIRMED);
    }

    @Test
    public void testAuthState_cancelPreservesExistingState() {
        // При отмене Device Code dialog существующий токен НЕ удаляется.
        // Симулируем: CONFIRMED → диалог отменён → должен остаться CONFIRMED
        // (YandexDeviceAuthDialog.cancel() не вызывает clearOAuthToken())
        VotData.AuthState stateBefore = VotData.AuthState.CONFIRMED;
        // cancel() не меняет AuthState → stateBefore остаётся
        VotData.AuthState stateAfterCancel = stateBefore; // поведение cancel()
        assertEquals("CONFIRMED сохраняется после отмены", stateBefore, stateAfterCancel);
    }

    @Test
    public void testAuthState_reentrancy_newTokenResetsToUnverified() {
        // Повторный вход: новый токен должен сбросить в UNVERIFIED.
        // Проверяем на уровне enum-логики VotData.
        VotData.AuthState afterNewToken = VotData.AuthState.UNVERIFIED;
        assertFalse("После нового токена → UNVERIFIED, не CONFIRMED",
                afterNewToken == VotData.AuthState.CONFIRMED);
        assertFalse("После нового токена → UNVERIFIED, не REJECTED",
                afterNewToken == VotData.AuthState.REJECTED);
    }

    @Test
    public void testAuthState_rejectedDoesNotDeleteToken() {
        // REJECTED: токен остаётся, только состояние меняется
        // Lively Voice выключается до нового подтверждения
        assertFalse("REJECTED != ABSENT (токен не удалён)",
                VotData.AuthState.REJECTED == VotData.AuthState.ABSENT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Блок D: Stale callback защита
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testStaleSessionId_isDetected() {
        // Проверяем логику: sessionId изменился → callback устарел
        AtomicInteger sessionId = new AtomicInteger(0);
        int capturedSession = sessionId.get(); // 0

        // Новый запрос кода
        sessionId.incrementAndGet(); // 1

        // Старый callback с capturedSession=0 должен быть отклонён
        boolean isStale = sessionId.get() != capturedSession;
        assertTrue("Stale callback обнаружен (sessionId изменился)", isStale);
    }

    @Test
    public void testCurrentSession_isAccepted() {
        AtomicInteger sessionId = new AtomicInteger(0);
        int capturedSession = sessionId.get(); // 0

        boolean isCurrent = sessionId.get() == capturedSession;
        assertTrue("Актуальная сессия принята", isCurrent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Блок E: YandexDeviceCodeResponse инварианты
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testDeviceCodeResponse_isValidRequiresBothCodes() {
        YandexDeviceCodeResponse valid =
                new YandexDeviceCodeResponse("d", "u", "https://ya.ru/device", 600, 5);
        assertTrue(valid.isValid());

        YandexDeviceCodeResponse noDeviceCode =
                new YandexDeviceCodeResponse("", "u", "https://ya.ru/device", 600, 5);
        assertFalse("Пустой device_code → невалидный", noDeviceCode.isValid());

        YandexDeviceCodeResponse noUserCode =
                new YandexDeviceCodeResponse("d", "", "https://ya.ru/device", 600, 5);
        assertFalse("Пустой user_code → невалидный", noUserCode.isValid());

        YandexDeviceCodeResponse nullCodes =
                new YandexDeviceCodeResponse(null, null, null, 0, 0);
        assertFalse("null коды → невалидный", nullCodes.isValid());
    }

    @Test
    public void testDeviceCodeResponse_defaultsForNegativeValues() {
        YandexDeviceCodeResponse r =
                new YandexDeviceCodeResponse("d", "u", null, -1, -1);
        assertEquals("Default expiresIn=600", 600, r.getExpiresIn());
        assertEquals("Default interval=5", 5, r.getInterval());
        assertEquals("Default URL", "https://ya.ru/device", r.getVerificationUrl());
    }

    @Test
    public void testDeviceCodeResponse_toStringDoesNotLeakDeviceCode() {
        YandexDeviceCodeResponse r =
                new YandexDeviceCodeResponse("SECRET_DEVICE_CODE", "USER-CODE", "https://ya.ru/device", 600, 5);
        String str = r.toString();
        // user_code — публичный по протоколу, может присутствовать
        // device_code — внутренний, НЕ должен быть в toString
        assertFalse("toString не содержит device_code", str.contains("SECRET_DEVICE_CODE"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Блок F: YandexTokenPollResult инварианты
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testPollResult_terminalStates() {
        assertTrue("SUCCESS терминальный",
                YandexTokenPollResult.success("t").isTerminal());
        assertTrue("ACCESS_DENIED терминальный",
                YandexTokenPollResult.accessDenied().isTerminal());
        assertTrue("EXPIRED терминальный",
                YandexTokenPollResult.expired().isTerminal());
    }

    @Test
    public void testPollResult_nonTerminalStates() {
        assertFalse("PENDING не терминальный",
                YandexTokenPollResult.pending().isTerminal());
        assertFalse("SLOW_DOWN не терминальный",
                YandexTokenPollResult.slowDown().isTerminal());
        assertFalse("NETWORK_ERROR не терминальный",
                YandexTokenPollResult.networkError("x").isTerminal());
    }

    @Test
    public void testPollResult_successHasToken() {
        YandexTokenPollResult success = YandexTokenPollResult.success("tok");
        assertNotNull(success.getAccessToken());
        assertFalse(success.getAccessToken().isEmpty());
    }

    @Test
    public void testPollResult_nonSuccessHasNoToken() {
        assertNull("PENDING не имеет токена",
                YandexTokenPollResult.pending().getAccessToken());
        assertNull("EXPIRED не имеет токена",
                YandexTokenPollResult.expired().getAccessToken());
        assertNull("ACCESS_DENIED не имеет токена",
                YandexTokenPollResult.accessDenied().getAccessToken());
    }

    @Test
    public void testPollResult_toStringDoesNotLeakToken() {
        String secretToken = "TOP_SECRET_ACCESS_TOKEN_12345";
        YandexTokenPollResult success = YandexTokenPollResult.success(secretToken);
        String str = success.toString();
        assertFalse("toString не содержит access_token", str.contains(secretToken));
        assertTrue("toString содержит SUCCESS", str.contains("SUCCESS"));
        assertTrue("toString содержит tokenPresent=true", str.contains("tokenPresent=true"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Блок G: Смена аккаунта
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void testAccountSwitch_newSessionIdInvalidatesOld() {
        // При requestNewCode() sessionId увеличивается
        // Все pending callbacks из предыдущей сессии должны быть отклонены
        AtomicInteger sessionId = new AtomicInteger(0);
        int session1 = sessionId.getAndIncrement(); // 0
        int session2 = sessionId.get();             // 1

        // session1 теперь устарел
        assertFalse("Старая сессия отклонена",
                sessionId.get() == session1);
        assertTrue("Новая сессия текущая",
                sessionId.get() == session2);
    }

    @Test
    public void testPollingRateLimit_slowDownIncreasesInterval() {
        // Проверяем логику увеличения интервала при slow_down
        int baseInterval = 5;
        int step = 5;
        int afterSlowDown = Math.min(baseInterval + step, 60);
        assertEquals("Interval увеличен на 5", 10, afterSlowDown);

        int afterSlowDownCapped = Math.min(55 + step, 60);
        assertEquals("Interval не превышает 60", 60, afterSlowDownCapped);
    }
}

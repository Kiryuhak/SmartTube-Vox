package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;

import org.json.JSONObject;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.schedulers.Schedulers;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class VotClient {
    private static final String TAG = VotClient.class.getSimpleName();

    // -------------------------------------------------------------------------
    // Маркеры ошибок: строковые константы, передаваемые через VotProgress.failed().
    // VoiceTranslateController и VotErrorCategory читают только эти константы —
    // никакого совпадения подстрок исключений.
    // -------------------------------------------------------------------------
    public static final String ERROR_MARKER_AUTH_REJECTED       = "vot:auth_rejected";
    public static final String ERROR_MARKER_PROTOCOL_SESSION    = "vot:protocol_session_required";
    public static final String ERROR_MARKER_RATE_LIMITED        = "vot:rate_limited";
    public static final String ERROR_MARKER_SERVER_UNAVAILABLE  = "vot:server_unavailable";
    public static final String ERROR_MARKER_ACCESS_DENIED       = "vot:access_denied";
    public static final String ERROR_MARKER_TIMEOUT             = "vot:timeout";
    public static final String ERROR_MARKER_NETWORK             = "vot:network_error";
    public static final String ERROR_MARKER_UNSUPPORTED_VIDEO   = "vot:unsupported_video";

    interface TranslationRequester {
        VotTranslationResponse request(String youtubeUrl, long durationSec, boolean subsequent,
                                       boolean useLively, String requestOAuthToken) throws IOException;
    }

    interface WaitStrategy {
        void waitSeconds(int sec, @Nullable ObservableEmitter<?> emitter) throws VotException;
    }

    private final VotHttp mHttp;
    @Nullable
    private final VotData mVotData;
    @Nullable
    private final TranslationRequester mTranslationRequester;
    private final WaitStrategy mWaitStrategy;
    private final boolean mLogEnabled;
    @Nullable
    private VotSession mSession;
    private String mLastOAuthToken;

    /** Максимальное число автоматических повторов при STATUS_SESSION_REQUIRED в одном poll-цикле. */
    private static final int MAX_SESSION_RETRIES = 2;

    public synchronized void resetSession() {
        mSession = null;
    }

    public VotClient(Context context) {
        this(VotData.instance(context), null, VotClient::waitRealTime);
    }

    VotClient(@Nullable VotData votData, @Nullable TranslationRequester translationRequester,
              WaitStrategy waitStrategy) {
        mVotData = votData;
        mHttp = new VotHttp();
        mTranslationRequester = translationRequester;
        mWaitStrategy = waitStrategy;
        mLogEnabled = translationRequester == null;
    }

    public String translateToRussian(String youtubeUrl, long durationSec) throws IOException, VotException {
        VotProgress last = observeTranslation(youtubeUrl, durationSec)
                .blockingLast();
        if (last == null) {
            throw new VotException("Translation cancelled");
        }
        if (last.type == VotProgress.TYPE_READY && last.audioUrl != null) {
            return last.audioUrl;
        }
        if (last.type == VotProgress.TYPE_FAILED) {
            throw new VotException(last.message != null ? last.message : "Translation failed");
        }
        throw new VotException("Translation timeout");
    }

    public Observable<VotProgress> observeTranslation(String youtubeUrl, long durationSec) {
        return observeTranslation(youtubeUrl, durationSec, false);
    }

    public Observable<VotProgress> observeTranslation(String youtubeUrl, long durationSec, boolean subsequent) {
        return Observable.<VotProgress>create(emitter -> pollTranslation(emitter, youtubeUrl, durationSec, true, true, subsequent))
                .subscribeOn(Schedulers.io());
    }

    private static final int MAX_POLL_ATTEMPTS = 120;
    private static final int MAX_CONSECUTIVE_TRANSIENT_RETRIES = 3;
    private static final int DEFAULT_POLL_INTERVAL_SEC = 20;
    private static final int MAX_WAIT_INTERVAL_SEC = 45;

    public static boolean isTransientHttpCode(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    public static boolean isTransientException(IOException e) {
        if (e instanceof VotHttpException) {
            return isTransientHttpCode(((VotHttpException) e).getStatusCode());
        }
        return true;
    }

    private int calculateWaitSec(int remainingTimeSec, int attempt) {
        if (remainingTimeSec <= 0) {
            return DEFAULT_POLL_INTERVAL_SEC;
        }
        if (attempt == 0) {
            if (remainingTimeSec <= 20) {
                return Math.max(5, remainingTimeSec);
            }
            if (remainingTimeSec <= 60) {
                return 25;
            }
            return MAX_WAIT_INTERVAL_SEC;
        }
        if (remainingTimeSec <= 15) {
            return Math.max(5, remainingTimeSec);
        }
        return DEFAULT_POLL_INTERVAL_SEC;
    }

    private void pollTranslation(ObservableEmitter<VotProgress> emitter, String youtubeUrl, long durationSec,
                                 boolean allowAudioFallback, boolean allowLivelyFallback) {
        pollTranslation(emitter, youtubeUrl, durationSec, allowAudioFallback, allowLivelyFallback, false);
    }

    private void pollTranslation(ObservableEmitter<VotProgress> emitter, String youtubeUrl, long durationSec,
                                 boolean allowAudioFallback, boolean allowLivelyFallback, boolean subsequent) {
        boolean useLively = allowLivelyFallback && mVotData != null && mVotData.isLivelyVoiceEnabled();
        // Bind the credential to this request cycle. A late response sent with an old token must
        // never confirm or reject a token that the user saved while the request was in flight.
        String requestOAuthToken = useLively ? mVotData.getOAuthToken() : null;
        try {
            logD("VOT request started: duration=%ds, useLively=%b, authState=%s, subsequent=%b",
                    durationSec, useLively, mVotData != null ? mVotData.getAuthState() : "TEST", subsequent);

            VotTranslationResponse response = null;
            int initTransientErrors = 0;
            while (!emitter.isDisposed()) {
                try {
                    response = requestTranslation(
                            youtubeUrl, durationSec, subsequent || (initTransientErrors > 0), useLively, requestOAuthToken);
                    break;
                } catch (IOException e) {
                    if (isTransientException(e) && initTransientErrors < MAX_CONSECUTIVE_TRANSIENT_RETRIES && !emitter.isDisposed()) {
                        initTransientErrors++;
                        int retryAfter = (e instanceof VotHttpException) ? ((VotHttpException) e).getRetryAfterSec() : -1;
                        int waitDelay = retryAfter > 0 ? Math.min(Math.max(3, retryAfter), 30) : 5;
                        logW("Transient error on initial VOT request (retry %d/%d in %ds): %s",
                                initTransientErrors, MAX_CONSECUTIVE_TRANSIENT_RETRIES, waitDelay, e.getMessage());
                        sleep(waitDelay, emitter);
                        continue;
                    }
                    throw e;
                }
            }
            if (response == null || emitter.isDisposed()) {
                return;
            }

            logD("Initial translation response: status=%d, remainingTime=%ds",
                    response.status, response.remainingTimeSec);
            if (!processResponse(emitter, youtubeUrl, durationSec, allowAudioFallback,
                    allowLivelyFallback, useLively, requestOAuthToken, response, 0)) {
                return;
            }

            int waitSec = calculateWaitSec(response.remainingTimeSec, 0);
            int consecutiveTransientErrors = 0;

            for (int i = 0; i < MAX_POLL_ATTEMPTS && !emitter.isDisposed(); i++) {
                logD("VOT poll scheduled: attempt=%d/%d, interval=%ds (reported ETA=%ds)",
                        i + 1, MAX_POLL_ATTEMPTS, waitSec, response.remainingTimeSec);
                sleep(waitSec, emitter);
                if (emitter.isDisposed()) {
                    logD("VOT polling cancelled (emitter disposed)");
                    return;
                }

                try {
                    response = requestTranslation(
                            youtubeUrl, durationSec, true, useLively, requestOAuthToken);
                    consecutiveTransientErrors = 0;
                } catch (IOException e) {
                    if (isTransientException(e)) {
                        consecutiveTransientErrors++;
                        int retryAfter = (e instanceof VotHttpException) ? ((VotHttpException) e).getRetryAfterSec() : -1;
                        logW("Transient error during VOT poll attempt %d (retry %d/%d, retryAfter=%ds): %s",
                                i + 1, consecutiveTransientErrors, MAX_CONSECUTIVE_TRANSIENT_RETRIES, retryAfter, e.getMessage());
                        if (consecutiveTransientErrors <= MAX_CONSECUTIVE_TRANSIENT_RETRIES && !emitter.isDisposed()) {
                            waitSec = retryAfter > 0 ? Math.min(Math.max(3, retryAfter), 30) : 5;
                            continue;
                        }
                    }
                    throw e;
                }

                logD("VOT poll response: attempt=%d, status=%d, remainingTime=%ds",
                        i + 1, response.status, response.remainingTimeSec);

                if (!processResponse(emitter, youtubeUrl, durationSec, allowAudioFallback,
                        allowLivelyFallback, useLively, requestOAuthToken, response, 0)) {
                    return;
                }
                waitSec = calculateWaitSec(response.remainingTimeSec, i + 1);
            }
            if (!emitter.isDisposed()) {
                logW("VOT polling exceeded MAX_POLL_ATTEMPTS (%d), timing out", MAX_POLL_ATTEMPTS);
                emitter.onNext(VotProgress.failed(ERROR_MARKER_TIMEOUT));
                emitter.onComplete();
            }
        } catch (IOException e) {
            logE("VOT IO error");
            if (!emitter.isDisposed()) {
                if (e instanceof VotHttpException) {
                    int code = ((VotHttpException) e).getStatusCode();
                    if (code == 401) {
                        VotErrorCategory category = VotErrorCategory.fromHttpCode(code, useLively);
                        if (category == VotErrorCategory.AUTH_REJECTED) {
                            // Only Lively sends OAuth credentials. Standard must not alter Yandex ID state.
                            updateAuthStateForHttpFailure(
                                    mVotData, code, useLively, requestOAuthToken);
                            logW("VOT: HTTP 401 for Lively — OAuth token rejected (authState→REJECTED)");
                            emitter.onNext(VotProgress.failed(ERROR_MARKER_AUTH_REJECTED));
                        } else {
                            logW("VOT: HTTP 401 for Standard — request denied without OAuth context");
                            emitter.onNext(VotProgress.failed(ERROR_MARKER_ACCESS_DENIED));
                        }
                        emitter.onComplete();
                        return;
                    }
                    int retryAfter = ((VotHttpException) e).getRetryAfterSec();
                    if (code == 429) {
                        logW("VOT: HTTP 429 — rate limited, retryAfter=%ds", retryAfter);
                        emitter.onNext(VotProgress.failed(ERROR_MARKER_RATE_LIMITED, retryAfter));
                        emitter.onComplete();
                        return;
                    }
                    if (code == 500 || code == 502 || code == 503 || code == 504) {
                        logW("VOT: HTTP %d — server unavailable, retryAfter=%ds", code, retryAfter);
                        emitter.onNext(VotProgress.failed(ERROR_MARKER_SERVER_UNAVAILABLE, retryAfter));
                        emitter.onComplete();
                        return;
                    }
                    // HTTP 403 без явного OAuth-контекста — не считаем ошибкой OAuth,
                    // обрабатываем как серверную ошибку или общую
                    if (code == 403) {
                        resetSession();
                        logW("VOT: HTTP 403 — session/access denied, resetting session (not OAuth)");
                        emitter.onNext(VotProgress.failed(ERROR_MARKER_ACCESS_DENIED));
                        emitter.onComplete();
                        return;
                    }
                }
                // Сетевая ошибка (SocketException, UnknownHostException и т.п.)
                VotErrorCategory netCategory = classifyNetworkException(e);
                emitter.onNext(VotProgress.failed(categoryToMarker(netCategory)));
                emitter.onComplete();
            }
        } catch (VotException e) {
            logE("VOT error");
            if (!emitter.isDisposed()) {
                emitter.onNext(VotProgress.failed(ERROR_MARKER_NETWORK));
                emitter.onComplete();
            }
        }
    }

    static void updateAuthStateForHttpFailure(VotData data, int statusCode,
                                              boolean requestUsedOAuth,
                                              String requestOAuthToken) {
        if (data != null && shouldApplyOAuthFailure(statusCode, requestUsedOAuth,
                requestOAuthToken, data.getOAuthToken())) {
            data.markOAuthRejected(requestOAuthToken);
        }
    }

    static boolean shouldApplyOAuthFailure(int statusCode, boolean requestUsedOAuth,
                                           String requestOAuthToken, String currentOAuthToken) {
        return VotErrorCategory.fromHttpCode(statusCode, requestUsedOAuth)
                == VotErrorCategory.AUTH_REJECTED
                && requestOAuthToken != null
                && !requestOAuthToken.isEmpty()
                && Helpers.equals(requestOAuthToken, currentOAuthToken);
    }

    static boolean shouldApplyOAuthSuccess(boolean requestUsedOAuth,
                                           String requestOAuthToken, String currentOAuthToken) {
        return requestUsedOAuth
                && requestOAuthToken != null
                && !requestOAuthToken.isEmpty()
                && Helpers.equals(requestOAuthToken, currentOAuthToken);
    }

    /** @return false если опрос должен прекратиться (готово, ошибка или disposed) */
    private boolean processResponse(ObservableEmitter<VotProgress> emitter, String youtubeUrl, long durationSec,
                                    boolean allowAudioFallback, boolean allowLivelyFallback, boolean useLively,
                                    String requestOAuthToken, VotTranslationResponse response, int sessionRetryCount)
            throws IOException, VotException {
        if (emitter.isDisposed()) {
            return false;
        }

        if (response.status == VotTranslationResponse.STATUS_SESSION_REQUIRED) {
            // Протокол VOT требует анонимную криптосессию (/session/create).
            // Это НЕ ошибка OAuth. Пробуем создать/обновить сессию и повторить запрос.
            if (sessionRetryCount >= MAX_SESSION_RETRIES) {
                logE("VOT: STATUS_SESSION_REQUIRED after %d retries, giving up", MAX_SESSION_RETRIES);
                emitter.onNext(VotProgress.failed(ERROR_MARKER_PROTOCOL_SESSION));
                emitter.onComplete();
                return false;
            }
            logD("VOT: STATUS_SESSION_REQUIRED (retry %d/%d) — creating protocol session",
                    sessionRetryCount + 1, MAX_SESSION_RETRIES);
            resetSession();
            ensureSession();
            VotTranslationResponse retry = requestTranslation(
                    youtubeUrl, durationSec, false, useLively, requestOAuthToken);
            return processResponse(emitter, youtubeUrl, durationSec, allowAudioFallback,
                    allowLivelyFallback, useLively, requestOAuthToken, retry, sessionRetryCount + 1);
        }

        if (response.status == VotTranslationResponse.STATUS_AUDIO_REQUESTED) {
            if (allowAudioFallback) {
                handleAudioRequested(youtubeUrl, durationSec, response.translationId,
                        useLively, requestOAuthToken);
                pollTranslation(emitter, youtubeUrl, durationSec, false, allowLivelyFallback);
                return false;
            } else {
                logW("Audio upload already attempted or disabled, stopping polling");
                emitter.onNext(VotProgress.failed(ERROR_MARKER_UNSUPPORTED_VIDEO));
                emitter.onComplete();
                return false;
            }
        }

        if (response.isReady() && response.url != null && !response.url.isEmpty()) {
            // Успешный ответ — если использовался Lively, подтверждаем токен
            if (shouldApplyOAuthSuccess(useLively, requestOAuthToken,
                    mVotData != null ? mVotData.getOAuthToken() : null)) {
                mVotData.markOAuthConfirmed(requestOAuthToken);
                logD("VOT: Lively translation ready — OAuth marked CONFIRMED");
            }
            emitter.onNext(VotProgress.ready(response.url));
            emitter.onComplete();
            return false;
        }

        if (response.status == VotTranslationResponse.STATUS_FAILED) {
            if (allowLivelyFallback && useLively && isLivelyUnavailableError(response.message)) {
                logD("Lively voice unavailable, retrying with Standard voice");
                emitter.onNext(VotProgress.livelyFallback());
                pollTranslation(emitter, youtubeUrl, durationSec, allowAudioFallback, false);
                return false;
            }
            // Проверяем, не является ли backend message маркером unsupported video
            if (isUnsupportedVideoError(response.message)) {
                emitter.onNext(VotProgress.failed(ERROR_MARKER_UNSUPPORTED_VIDEO));
                emitter.onComplete();
                return false;
            }
            emitter.onNext(VotProgress.failed(ERROR_MARKER_NETWORK));
            emitter.onComplete();
            return false;
        }

        if (response.isWaiting()) {
            emitter.onNext(VotProgress.waiting(response.remainingTimeSec, response.status));
            return true;
        }

        emitter.onNext(VotProgress.failed(ERROR_MARKER_NETWORK));
        emitter.onComplete();
        return false;
    }

    private VotTranslationResponse requestTranslation(String youtubeUrl, double durationSec,
                                                       boolean subsequent, boolean useLively,
                                                       String requestOAuthToken)
            throws IOException {
        if (mTranslationRequester != null) {
            return mTranslationRequester.request(
                    youtubeUrl, (long) durationSec, subsequent, useLively, requestOAuthToken);
        }
        byte[] body = VotProtobuf.encodeTranslationRequest(
                youtubeUrl,
                durationSec,
                VotConfig.REQUEST_LANG,
                VotConfig.RESPONSE_LANG,
                !subsequent,
                useLively
        );

        Map<String, String> headers = buildTranslateHeaders(body, useLively, requestOAuthToken);
        byte[] raw = mHttp.postProtobuf("/video-translation/translate", body, headers);

        if (raw == null || raw.length == 0) {
            throw new IOException("Empty translation response");
        }
        return VotProtobuf.decodeTranslationResponse(raw);
    }

    private Map<String, String> buildTranslateHeaders(byte[] body, boolean useLively,
                                                      String requestOAuthToken) {
        String currentToken = mVotData != null ? mVotData.getOAuthToken() : null;
        if (!Helpers.equals(currentToken, mLastOAuthToken)) {
            mLastOAuthToken = currentToken;
            resetSession();
        }

        Map<String, String> headers;
        if (mSession != null) {
            headers = VotHeaders.sessionTranslate(mSession, body, "/video-translation/translate");
        } else {
            headers = VotHeaders.simpleTranslate(body);
        }
        if (useLively && requestOAuthToken != null && !requestOAuthToken.isEmpty()) {
            headers = VotHeaders.merge(headers, VotHeaders.oauthHeader(requestOAuthToken));
        }
        return headers;
    }

    private void handleAudioRequested(String youtubeUrl, long durationSec,
                                      @Nullable String translationId, boolean useLively,
                                      String requestOAuthToken)
            throws IOException, VotException {
        if (translationId == null || translationId.isEmpty()) {
            VotTranslationResponse r = requestTranslation(
                    youtubeUrl, durationSec, false, useLively, requestOAuthToken);
            translationId = r.translationId;
        }
        if (translationId == null || translationId.isEmpty()) {
            throw new VotException("Missing translationId for audio upload");
        }

        ensureSession();
        requestFailAudio(youtubeUrl);
        uploadEmptyAudio(youtubeUrl, translationId);
    }

    private void requestFailAudio(String youtubeUrl) throws IOException, VotException {
        String json = "{\"video_url\":\"" + youtubeUrl.replace("\"", "\\\"") + "\"}";
        byte[] raw = mHttp.putJson("/video-translation/fail-audio-js", json,
                VotHeaders.simpleTranslate(json.getBytes(StandardCharsets.UTF_8)));
        if (raw == null) {
            throw new VotException("fail-audio-js: empty response");
        }
        try {
            String text = new String(raw, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(text);
            if (obj.optInt("status", 0) != 1) {
                throw new VotException("fail-audio-js failed");
            }
        } catch (VotException e) {
            throw e;
        } catch (Exception e) {
            logE("fail-audio-js parse error");
        }
    }

    private void uploadEmptyAudio(String youtubeUrl, String translationId) throws IOException {
        byte[] body = VotProtobuf.encodeTranslationAudioRequest(
                youtubeUrl, translationId, VotConfig.FAKE_AUDIO_FILE_ID);
        mHttp.putProtobuf("/video-translation/audio", body,
                VotHeaders.sessionTranslate(mSession, body, "/video-translation/audio"));
    }

    private void ensureSession() throws IOException {
        if (mSession != null && System.currentTimeMillis() - mSession.createdAtMs < mSession.expiresSec * 1000L) {
            return;
        }
        String uuid = VotSignature.randomToken();
        byte[] body = VotProtobuf.encodeSessionRequest(uuid, "video-translation");
        byte[] raw = mHttp.postProtobuf("/session/create", body, VotHeaders.simpleTranslate(body));
        if (raw == null) {
            throw new IOException("Empty session response");
        }
        VotSession decoded = VotProtobuf.decodeSessionResponse(raw);
        decoded.uuid = uuid;
        decoded.createdAtMs = System.currentTimeMillis();
        mSession = decoded;
    }

    /**
     * Проверяет, указывает ли сообщение бэкенда на недоступность Lively Voice
     * для данного видео/языка (не на ошибку авторизации или сети).
     */
    public static boolean isLivelyUnavailableError(@Nullable String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("обычная озвучка") || lower.contains("standard voice");
    }

    /**
     * Проверяет, указывает ли сообщение бэкенда на неподдерживаемое видео.
     */
    public static boolean isUnsupportedVideoError(@Nullable String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("unsupported") || lower.contains("not supported")
                || lower.contains("invalid video");
    }

    /**
     * Architecture hook for Lively Voice -> standard voice fallback.
     */
    public static boolean isLivelyVoiceSpecificFailure(@Nullable VotTranslationResponse response) {
        return response != null && isLivelyUnavailableError(response.message);
    }

    /** Классифицирует IOException не-HTTP природы (сетевые сбои). */
    private static VotErrorCategory classifyNetworkException(IOException e) {
        if (e instanceof SocketTimeoutException) {
            return VotErrorCategory.NETWORK_ERROR;
        }
        if (e instanceof UnknownHostException) {
            return VotErrorCategory.NETWORK_ERROR;
        }
        if (e instanceof SocketException) {
            return VotErrorCategory.NETWORK_ERROR;
        }
        return VotErrorCategory.NETWORK_ERROR;
    }

    /** Конвертирует категорию ошибки в строковый маркер для VotProgress. */
    private static String categoryToMarker(VotErrorCategory category) {
        switch (category) {
            case AUTH_REJECTED:         return ERROR_MARKER_AUTH_REJECTED;
            case PROTOCOL_SESSION_REQUIRED: return ERROR_MARKER_PROTOCOL_SESSION;
            case RATE_LIMITED:          return ERROR_MARKER_RATE_LIMITED;
            case SERVER_UNAVAILABLE:    return ERROR_MARKER_SERVER_UNAVAILABLE;
            case ACCESS_DENIED:         return ERROR_MARKER_ACCESS_DENIED;
            case TIMEOUT:               return ERROR_MARKER_TIMEOUT;
            case UNSUPPORTED_VIDEO:     return ERROR_MARKER_UNSUPPORTED_VIDEO;
            default:                    return ERROR_MARKER_NETWORK;
        }
    }

    private void sleep(int sec, @Nullable ObservableEmitter<?> emitter) throws VotException {
        mWaitStrategy.waitSeconds(sec, emitter);
    }

    private static void waitRealTime(int sec, @Nullable ObservableEmitter<?> emitter) throws VotException {
        for (int s = 0; s < sec; s++) {
            if (emitter != null && emitter.isDisposed()) {
                return;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VotException("Interrupted");
            }
        }
    }

    private void sleep(int sec) throws VotException {
        sleep(sec, null);
    }

    private void logD(Object message, Object... args) {
        if (mLogEnabled) {
            Log.d(TAG, message, args);
        }
    }

    private void logW(Object message, Object... args) {
        if (mLogEnabled) {
            Log.w(TAG, message, args);
        }
    }

    private void logE(Object message, Object... args) {
        if (mLogEnabled) {
            Log.e(TAG, message, args);
        }
    }
}

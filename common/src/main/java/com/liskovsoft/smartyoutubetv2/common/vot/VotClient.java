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

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;

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
    public static final String ERROR_MARKER_GENERIC             = "vot:generic_error";

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
    @Nullable
    private VotAudioSourceProvider mAudioSourceProvider;
    private final Object mUploaderLock = new Object();
    private volatile VotAudioUploader mActiveAudioUploader;
    private static final VotAudioUploader CANCELLED_UPLOADER = new VotAudioUploader();
    private static final ThreadLocal<AtomicReference<VotAudioUploader>> sCurrentLocalUploader = new ThreadLocal<>();
    private static final ThreadLocal<VotAudioSource> sCurrentAudioSource = new ThreadLocal<>();
    private static final VotAudioSource EMPTY_SOURCE_MARKER = new VotAudioSource() {
        @Override public void open() {}
        @Override public int read(byte[] buffer, int offset, int length) { return -1; }
        @Override public long getContentLength() { return 0; }
        @Override public void close() {}
    };
    private String mTestOAuthToken;
    private Boolean mTestLivelyEnabled;

    public void setAudioSourceProvider(@Nullable VotAudioSourceProvider provider) {
        mAudioSourceProvider = provider;
    }

    @Nullable
    public VotAudioSourceProvider getAudioSourceProvider() {
        return mAudioSourceProvider;
    }

    void setTestCredentials(@Nullable Boolean livelyEnabled, @Nullable String oauthToken) {
        mTestLivelyEnabled = livelyEnabled;
        mTestOAuthToken = oauthToken;
    }

    public void cancelActiveAudioUpload() {
        VotAudioUploader uploader = mActiveAudioUploader;
        if (uploader != null) {
            uploader.cancel();
        }
    }

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
        this(votData, new VotHttp(), translationRequester, waitStrategy);
    }

    VotClient(@Nullable VotData votData, VotHttp http,
              @Nullable TranslationRequester translationRequester, WaitStrategy waitStrategy) {
        this(votData, http, translationRequester, waitStrategy, translationRequester == null);
    }

    VotClient(@Nullable VotData votData, VotHttp http,
              @Nullable TranslationRequester translationRequester, WaitStrategy waitStrategy,
              boolean logEnabled) {
        mVotData = votData;
        mHttp = http;
        mTranslationRequester = translationRequester;
        mWaitStrategy = waitStrategy;
        mLogEnabled = logEnabled;
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
        return observeTranslation(youtubeUrl, durationSec, false, mAudioSourceProvider);
    }

    public Observable<VotProgress> observeTranslation(String youtubeUrl, long durationSec, boolean subsequent) {
        return observeTranslation(youtubeUrl, durationSec, subsequent, mAudioSourceProvider);
    }

    public Observable<VotProgress> observeTranslation(String youtubeUrl, long durationSec, boolean subsequent,
                                                      @Nullable VotAudioSourceProvider audioSourceProvider) {
        return Observable.<VotProgress>create(emitter -> {
            AtomicReference<VotAudioUploader> localUploaderRef = new AtomicReference<>();
            emitter.setCancellable(() -> {
                VotAudioUploader local = localUploaderRef.getAndSet(CANCELLED_UPLOADER);
                if (local != null && local != CANCELLED_UPLOADER) {
                    local.cancel();
                }
            });
            sCurrentLocalUploader.set(localUploaderRef);
            try {
                pollTranslation(emitter, youtubeUrl, durationSec, true, true, subsequent, audioSourceProvider);
            } finally {
                sCurrentLocalUploader.remove();
            }
        }).subscribeOn(Schedulers.io());
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
        pollTranslation(emitter, youtubeUrl, durationSec, allowAudioFallback, allowLivelyFallback, false, mAudioSourceProvider);
    }

    private void pollTranslation(ObservableEmitter<VotProgress> emitter, String youtubeUrl, long durationSec,
                                 boolean allowAudioFallback, boolean allowLivelyFallback, boolean subsequent) {
        pollTranslation(emitter, youtubeUrl, durationSec, allowAudioFallback, allowLivelyFallback, subsequent, mAudioSourceProvider);
    }

    private void pollTranslation(ObservableEmitter<VotProgress> emitter, String youtubeUrl, long durationSec,
                                 boolean allowAudioFallback, boolean allowLivelyFallback, boolean subsequent,
                                 @Nullable VotAudioSourceProvider audioSourceProvider) {
        boolean useLively = (mTestLivelyEnabled != null)
                ? (allowLivelyFallback && mTestLivelyEnabled)
                : (allowLivelyFallback && mVotData != null && mVotData.isLivelyVoiceEnabled());
        // Bind the credential to this request cycle. A late response sent with an old token must
        // never confirm or reject a token that the user saved while the request was in flight.
        String requestOAuthToken = (mTestOAuthToken != null)
                ? (useLively ? mTestOAuthToken : null)
                : (useLively ? mVotData.getOAuthToken() : null);
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
                                initTransientErrors, MAX_CONSECUTIVE_TRANSIENT_RETRIES, waitDelay, e.getClass().getSimpleName());
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
                    allowLivelyFallback, useLively, requestOAuthToken, response, 0, audioSourceProvider)) {
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
                                i + 1, consecutiveTransientErrors, MAX_CONSECUTIVE_TRANSIENT_RETRIES, retryAfter, e.getClass().getSimpleName());
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
                        allowLivelyFallback, useLively, requestOAuthToken, response, 0, audioSourceProvider)) {
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
            if (e instanceof VotHttpException) {
                VotHttpException he = (VotHttpException) e;
                logE("VOT IO error: HTTP code=%d, retryAfter=%d", he.getStatusCode(), he.getRetryAfterSec());
            } else {
                logE("VOT IO error: transport exception=%s", e.getClass().getSimpleName());
            }
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
                    logW("VOT: HTTP %d — unhandled HTTP status, emitting generic error", code);
                    emitter.onNext(VotProgress.failed(ERROR_MARKER_GENERIC));
                    emitter.onComplete();
                    return;
                }
                // Сетевая ошибка (SocketException, UnknownHostException и т.п.)
                VotErrorCategory netCategory = classifyNetworkException(e);
                emitter.onNext(VotProgress.failed(categoryToMarker(netCategory)));
                emitter.onComplete();
            }
        } catch (VotException e) {
            logE("VOT error: %s", e.getClass().getSimpleName());
            if (!emitter.isDisposed()) {
                emitter.onNext(VotProgress.failed(ERROR_MARKER_GENERIC));
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
        return processResponse(emitter, youtubeUrl, durationSec, allowAudioFallback, allowLivelyFallback,
                useLively, requestOAuthToken, response, sessionRetryCount, mAudioSourceProvider);
    }

    private boolean processResponse(ObservableEmitter<VotProgress> emitter, String youtubeUrl, long durationSec,
                                    boolean allowAudioFallback, boolean allowLivelyFallback, boolean useLively,
                                    String requestOAuthToken, VotTranslationResponse response, int sessionRetryCount,
                                    @Nullable VotAudioSourceProvider audioSourceProvider)
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
                    allowLivelyFallback, useLively, requestOAuthToken, retry, sessionRetryCount + 1, audioSourceProvider);
        }

        if (response.status == VotTranslationResponse.STATUS_AUDIO_REQUESTED) {
            if (allowAudioFallback) {
                if (emitter.isDisposed()) {
                    return false;
                }
                VotAudioSourceProvider provider = audioSourceProvider != null ? audioSourceProvider : mAudioSourceProvider;
                VotAudioSource audioSource = null;
                if (provider != null) {
                    try {
                        audioSource = provider.getAudioSource(youtubeUrl);
                    } catch (Throwable t) {
                        logE("Error resolving audio source for %s: %s", youtubeUrl, sanitizeLogMessage(t));
                    }
                }

                if (emitter.isDisposed()) {
                    return false;
                }

                sCurrentAudioSource.set(audioSource != null ? audioSource : EMPTY_SOURCE_MARKER);
                try {
                    handleAudioRequested(youtubeUrl, durationSec, response.translationId,
                            useLively, requestOAuthToken);
                } catch (IOException | VotException | IllegalArgumentException e) {
                    logE("VOT audio upload failed: %s", sanitizeLogMessage(e));
                    if (!emitter.isDisposed()) {
                        if (e instanceof VotCancellationException) {
                            return false;
                        }
                        Throwable cause = e.getCause();
                        VotHttpException he = (e instanceof VotHttpException) ? (VotHttpException) e
                                : (cause instanceof VotHttpException ? (VotHttpException) cause : null);
                        if (e instanceof VotAudioSourceException || e instanceof IllegalArgumentException) {
                            emitter.onNext(VotProgress.failed(ERROR_MARKER_UNSUPPORTED_VIDEO));
                        } else if (he != null) {
                            emitter.onNext(VotProgress.failed(categoryToMarker(VotErrorCategory.fromHttpCode(he.getStatusCode(), useLively))));
                        } else {
                            emitter.onNext(VotProgress.failed(ERROR_MARKER_GENERIC));
                        }
                        emitter.onComplete();
                    }
                    return false;
                } finally {
                    sCurrentAudioSource.remove();
                }

                // After audio fallback upload completes, Yandex requires a translation request
                // with firstRequest=true (subsequent=false) to queue the synthesis task.
                // allowAudioFallback is set to false to guard against infinite upload loops.
                pollTranslation(emitter, youtubeUrl, durationSec, false, allowLivelyFallback, false, audioSourceProvider);
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
            emitter.onNext(VotProgress.failed(ERROR_MARKER_GENERIC));
            emitter.onComplete();
            return false;
        }

        if (response.isWaiting()) {
            emitter.onNext(VotProgress.waiting(response.remainingTimeSec, response.status));
            return true;
        }

        emitter.onNext(VotProgress.failed(ERROR_MARKER_GENERIC));
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

        // Keep one cryptographic session for the complete server-task lifecycle. In
        // particular, AUDIO_REQUESTED uploads are session-signed and the following
        // request must not switch from legacy headers to a newly created session.
        prepareTranslationSession();
        // Yandex /video-translation/translate REST endpoint requires firstRequest=true (tag 5 = 1)
        // for all requests in the lifecycle: initial translate, retry after audio upload, and
        // all regular polling attempts. Reference implementations (vot.js, voice-over-translation)
        // always send firstRequest=true. Sending firstRequest=false causes Yandex to reject the
        // request with HTTP 400 Bad Request.
        byte[] body = VotProtobuf.encodeTranslationRequest(
                youtubeUrl,
                durationSec,
                VotConfig.REQUEST_LANG,
                VotConfig.RESPONSE_LANG,
                true,
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
        Map<String, String> headers = VotHeaders.sessionTranslate(
                mSession, body, "/video-translation/translate");
        if (useLively && requestOAuthToken != null && !requestOAuthToken.isEmpty()) {
            headers = VotHeaders.merge(headers, VotHeaders.oauthHeader(requestOAuthToken));
        }
        return headers;
    }

    private void prepareTranslationSession() throws IOException {
        String currentToken = mTestOAuthToken != null ? mTestOAuthToken : (mVotData != null ? mVotData.getOAuthToken() : null);
        if (!Helpers.equals(currentToken, mLastOAuthToken)) {
            mLastOAuthToken = currentToken;
            resetSession();
        }
        ensureSession();
    }

    void handleAudioRequested(String youtubeUrl, long durationSec,
                              @Nullable String translationId, boolean useLively,
                              String requestOAuthToken)
            throws IOException, VotException {
        VotAudioSource current = sCurrentAudioSource.get();
        VotAudioSource source;
        if (current == EMPTY_SOURCE_MARKER) {
            source = null;
        } else if (current != null) {
            source = current;
        } else {
            source = mAudioSourceProvider != null ? mAudioSourceProvider.getAudioSource(youtubeUrl) : null;
        }
        handleAudioRequested(youtubeUrl, durationSec, translationId, useLively, requestOAuthToken, source);
    }

    void handleAudioRequested(String youtubeUrl, long durationSec,
                              @Nullable String translationId, boolean useLively,
                              String requestOAuthToken,
                              @Nullable VotAudioSource audioSource)
            throws IOException, VotException {
        if (audioSource == null) {
            throw new VotAudioSourceException("No audio source available for video translation");
        }
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

        String fileId = "smarttube-" + VotSignature.randomToken();
        VotAudioUploader uploader = new VotAudioUploader(mHttp);
        synchronized (mUploaderLock) {
            mActiveAudioUploader = uploader;
        }
        AtomicReference<VotAudioUploader> localRef = sCurrentLocalUploader.get();
        if (localRef != null) {
            if (!localRef.compareAndSet(null, uploader)) {
                uploader.cancel();
                throw new VotCancellationException("Upload cancelled before starting");
            }
        }
        try {
            logI("Starting VOT audio upload: url=%s, translationId=%s, fileId=%s, expectedContentLength=%d",
                    youtubeUrl, translationId, fileId, audioSource.getContentLength());
            VotTranslationAudioResponse resp = uploader.uploadAudio(
                    youtubeUrl, translationId, fileId, mSession, requestOAuthToken, audioSource);
            if (resp == null || resp.status != VotTranslationAudioResponse.STATUS_DONE) {
                throw new VotException("Audio upload incomplete or rejected: status=" + (resp != null ? resp.status : "null"));
            }
            if (resp.remainingChunks != null && !resp.remainingChunks.isEmpty()) {
                throw new VotException("Audio upload incomplete: server waiting for chunks " + resp.remainingChunks);
            }
            logI("VOT audio upload successfully completed: status=%d, totalBytesUploaded=%d, totalChunks=%d",
                    resp.status, uploader.getTotalBytesUploaded(), uploader.getTotalChunksUploaded());
        } finally {
            synchronized (mUploaderLock) {
                if (mActiveAudioUploader == uploader) {
                    mActiveAudioUploader = null;
                }
            }
            if (localRef != null) {
                localRef.compareAndSet(uploader, null);
            }
        }
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

    void ensureSession() throws IOException {
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
    static VotErrorCategory classifyNetworkException(IOException e) {
        if (e instanceof SocketTimeoutException
                || e instanceof ConnectException
                || e instanceof UnknownHostException
                || e instanceof SocketException
                || e instanceof SSLException
                || e instanceof EOFException) {
            return VotErrorCategory.NETWORK_ERROR;
        }
        return VotErrorCategory.NETWORK_ERROR;
    }

    /** Конвертирует категорию ошибки в строковый маркер для VotProgress. */
    static String categoryToMarker(VotErrorCategory category) {
        if (category == null) {
            return ERROR_MARKER_GENERIC;
        }
        switch (category) {
            case AUTH_REJECTED:             return ERROR_MARKER_AUTH_REJECTED;
            case PROTOCOL_SESSION_REQUIRED: return ERROR_MARKER_PROTOCOL_SESSION;
            case RATE_LIMITED:              return ERROR_MARKER_RATE_LIMITED;
            case SERVER_UNAVAILABLE:        return ERROR_MARKER_SERVER_UNAVAILABLE;
            case ACCESS_DENIED:             return ERROR_MARKER_ACCESS_DENIED;
            case TIMEOUT:                   return ERROR_MARKER_TIMEOUT;
            case NETWORK_ERROR:             return ERROR_MARKER_NETWORK;
            case UNSUPPORTED_VIDEO:         return ERROR_MARKER_UNSUPPORTED_VIDEO;
            case GENERIC_ERROR:             return ERROR_MARKER_GENERIC;
            default:                        return ERROR_MARKER_GENERIC;
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

    private void logI(Object message, Object... args) {
        if (mLogEnabled) {
            Log.i(TAG, message, args);
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

    public static String sanitizeLogMessage(@Nullable Throwable t) {
        if (t == null) {
            return "null";
        }
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return sanitizeMessage(msg);
    }

    public static String sanitizeMessage(@Nullable String msg) {
        if (msg == null) {
            return "";
        }
        // Strip out complete URLs to avoid leaking CDN auth tokens/signatures
        String sanitized = msg.replaceAll("https?://[^\\s\"'<>]+", "[REDACTED_URL]");
        // Strip out any key-value token patterns
        sanitized = sanitized.replaceAll("(?i)(sig|signature|token|key|secret|auth|bearer)=[^&\\s\"';]+", "$1=[REDACTED]");
        return sanitized;
    }
}

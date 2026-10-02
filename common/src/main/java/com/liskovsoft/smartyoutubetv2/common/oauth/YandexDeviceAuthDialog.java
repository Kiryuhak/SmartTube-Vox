package com.liskovsoft.smartyoutubetv2.common.oauth;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.utils.VotOAuthTokenValidator;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Диалог авторизации Яндекс ID по Device Code Flow для Android TV.
 *
 * UX:
 *   — Показывает user_code крупным шрифтом
 *   — URL: ya.ru/device
 *   — Таймер обратного отсчёта
 *   — Кнопки: «Обновить код» / «Отмена»
 *   — BACK → отмена polling
 *
 * Безопасность:
 *   — access_token никогда не логируется
 *   — device_code не отображается пользователю
 *   — stale callbacks отклоняются через sessionId
 */
public class YandexDeviceAuthDialog {

    private static final String TAG = "YandexDeviceAuthDialog";

    /** Публичный client_id stvot flavor. Не является секретом. */
    private static final String CLIENT_ID = "a80318baa39742079143855c77b13709";

    private final Activity mActivity;
    private final VotData mVotData;

    private AlertDialog mDialog;
    private Disposable mPollDisposable;
    private Disposable mTimerDisposable;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** Монотонно возрастающий счётчик сессии — защита от stale callbacks. */
    private final YandexPollingSession mSession = new YandexPollingSession();

    // UI views (lazy init — null до show())
    private TextView mCodeView;
    private TextView mUrlView;
    private TextView mInstructionView;
    private TextView mTimerView;
    private TextView mStatusView;
    private Button mRefreshButton;
    private Button mCancelButton;

    private static final class PollEvent {
        final YandexBrokerClient.Result result;
        final boolean canRetry;

        PollEvent(YandexBrokerClient.Result result, boolean canRetry) {
            this.result = result;
            this.canRetry = canRetry;
        }
    }

    private static YandexDeviceAuthDialog sCurrentInstance;
    private final Runnable mOnDismiss;

    private YandexDeviceAuthDialog(Activity activity, Runnable onDismiss) {
        this.mActivity = activity;
        this.mVotData = VotData.instance(activity);
        this.mOnDismiss = onDismiss;
    }

    /**
     * Показать диалог Device Code авторизации.
     *
     * @param activity Activity-контекст (должна быть живой)
     */
    public static void show(Activity activity) {
        show(activity, null);
    }

    public static void show(Activity activity, Runnable onDismiss) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (YandexBrokerConfig.mode() != YandexBrokerConfig.Mode.BROKER) {
            // This client is known to reject direct token exchange without a server-side secret.
            com.liskovsoft.sharedutils.helpers.MessageHelpers.showMessage(activity,
                    R.string.vot_device_auth_broker_unavailable);
            if (onDismiss != null) onDismiss.run();
            return;
        }
        if (sCurrentInstance != null && sCurrentInstance.mDialog != null && sCurrentInstance.mDialog.isShowing()) {
            return;
        }
        sCurrentInstance = new YandexDeviceAuthDialog(activity, onDismiss);
        sCurrentInstance.start();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @MainThread
    private void start() {
        buildAndShowDialog();
        requestNewCode();
    }

    @MainThread
    private void buildAndShowDialog() {
        View view = LayoutInflater.from(mActivity)
                .inflate(R.layout.vot_device_auth_dialog, null);

        mCodeView = view.findViewById(R.id.vot_device_code);
        mUrlView = view.findViewById(R.id.vot_device_url);
        mInstructionView = view.findViewById(R.id.vot_device_instruction);
        mTimerView = view.findViewById(R.id.vot_device_timer);
        mStatusView = view.findViewById(R.id.vot_device_status);
        mRefreshButton = view.findViewById(R.id.vot_device_refresh);
        mCancelButton = view.findViewById(R.id.vot_device_cancel);

        mUrlView.setText(YandexBrokerConfig.usesLocalMock()
                ? R.string.vot_device_auth_mock_url : R.string.vot_device_auth_url);
        mInstructionView.setText(R.string.vot_device_auth_step1);
        mStatusView.setText(R.string.vot_device_auth_pending);
        mCodeView.setText("··········");
        mTimerView.setText("");
        mRefreshButton.setEnabled(false);

        mRefreshButton.setOnClickListener(v -> requestNewCode());
        mCancelButton.setOnClickListener(v -> cancel());

        mDialog = new AlertDialog.Builder(mActivity, R.style.AppDialog)
                .setTitle(R.string.vot_device_auth_title)
                .setView(view)
                .setCancelable(true)
                .setOnDismissListener(d -> {
                    invalidateSessionAndStopPolling();
                    sCurrentInstance = null;
                    if (mOnDismiss != null) {
                        mOnDismiss.run();
                    }
                })
                .create();

        mDialog.show();

        // D-pad: начальный фокус на Cancel
        mCancelButton.requestFocus();
    }

    // -------------------------------------------------------------------------
    // Code request + polling
    // -------------------------------------------------------------------------

    @MainThread
    private void requestNewCode() {
        invalidateSessionAndStopPolling();

        int sessionId = mSession.start();

        mRefreshButton.setEnabled(false);
        mStatusView.setText(R.string.vot_device_auth_pending);
        mCodeView.setText("··········");
        mTimerView.setText("");

        mPollDisposable = Observable
                .<YandexDeviceCodeResponse>create(emitter -> {
                    try {
                        YandexDeviceCodeClient client = YandexBrokerConfig.usesLocalMock()
                                ? new YandexDeviceCodeClient(
                                        url -> (java.net.HttpURLConnection) new java.net.URL(url).openConnection(),
                                        YandexBrokerConfig.url().replaceAll("/+$", "") + "/dev/device/code")
                                : new YandexDeviceCodeClient();
                        YandexDeviceCodeResponse response = client.getDeviceCode(CLIENT_ID);
                        if (!emitter.isDisposed()) {
                            emitter.onNext(response);
                            emitter.onComplete();
                        }
                    } catch (Exception e) {
                        if (!emitter.isDisposed()) {
                            emitter.onError(e);
                        }
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        response -> onDeviceCodeReceived(sessionId, response),
                        error -> onDeviceCodeError(sessionId, error)
                );
    }

    @MainThread
    private void onDeviceCodeReceived(int sessionId, YandexDeviceCodeResponse response) {
        if (!isSessionCurrent(sessionId)) {
            return; // stale response
        }

        Log.d(TAG, "Device code received");

        if (!response.isValid()) {
            onDeviceCodeError(sessionId, new YandexDeviceCodeException(0, "Invalid device code response"));
            return;
        }

        mCodeView.setText(response.getUserCode());
        mRefreshButton.setEnabled(false); // активируется по истечении
        mStatusView.setText(R.string.vot_device_auth_pending);

        startCountdown(sessionId, response.getExpiresIn());
        startPollingLoop(sessionId, response);
    }

    @MainThread
    private void onDeviceCodeError(int sessionId, Throwable error) {
        if (!isSessionCurrent(sessionId)) {
            return;
        }
        Log.e(TAG, "Failed to get device code");
        mStatusView.setText(R.string.vot_device_auth_network_error);
        mCodeView.setText("—");
        mTimerView.setText("");
        mRefreshButton.setEnabled(true);
        mRefreshButton.requestFocus();
    }

    // -------------------------------------------------------------------------
    // Countdown timer
    // -------------------------------------------------------------------------

    private void startCountdown(int sessionId, int expiresIn) {
        if (mTimerDisposable != null) {
            mTimerDisposable.dispose();
        }

        final long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(expiresIn);
        mTimerDisposable = Observable.interval(0, 1, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tick -> {
                    if (!isSessionCurrent(sessionId)) {
                        if (mTimerDisposable != null) mTimerDisposable.dispose();
                        return;
                    }
                    long remaining = Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
                    if (remaining <= 0) {
                        if (mTimerDisposable != null) mTimerDisposable.dispose();
                        onCodeExpired(sessionId);
                    } else {
                        mTimerView.setText(mActivity.getString(
                                R.string.vot_device_auth_timer, formatTime(remaining)));
                    }
                });
    }

    @MainThread
    private void onCodeExpired(int sessionId) {
        if (!isSessionCurrent(sessionId)) return;
        invalidateSessionAndStopPolling();
        mStatusView.setText(R.string.vot_device_auth_expired);
        mCodeView.setText("—");
        mTimerView.setText("");
        mRefreshButton.setEnabled(true);
        mRefreshButton.requestFocus();
    }

    // -------------------------------------------------------------------------
    // Polling loop
    // -------------------------------------------------------------------------

    private void startPollingLoop(int sessionId, YandexDeviceCodeResponse codeResponse) {
        final int pollIntervalSec = codeResponse.getInterval();
        final String deviceCode = codeResponse.getDeviceCode();
        final YandexBrokerPollPolicy policy = new YandexBrokerPollPolicy(pollIntervalSec);
        final YandexBrokerClient brokerClient = new YandexBrokerClient(YandexBrokerConfig.url());
        final long deadline = SystemClock.elapsedRealtime()
                + TimeUnit.SECONDS.toMillis(codeResponse.getExpiresIn());

        if (mPollDisposable != null) {
            mPollDisposable.dispose();
        }

        mPollDisposable = Observable
                .<PollEvent>create(emitter -> {
                    emitter.setCancellable(brokerClient::cancel);
                    while (!emitter.isDisposed() && SystemClock.elapsedRealtime() < deadline) {
                        // Ждём интервал секунда-по-секунде (для быстрой отмены)
                        for (int i = 0; i < policy.nextIntervalSeconds(); i++) {
                            if (emitter.isDisposed()) return;
                            TimeUnit.SECONDS.sleep(1);
                        }
                        if (emitter.isDisposed()) return;
                        if (SystemClock.elapsedRealtime() >= deadline) {
                            emitter.onNext(new PollEvent(YandexBrokerClient.expired(), false));
                            emitter.onComplete();
                            return;
                        }

                        YandexBrokerClient.Result result = brokerClient.poll(deviceCode);
                        boolean canRetry = policy.accept(result);
                        emitter.onNext(new PollEvent(result, canRetry));

                        if (!canRetry) {
                            emitter.onComplete();
                            return;
                        }

                        if (result.isTerminal()) {
                            emitter.onComplete();
                            return;
                        }
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        event -> {
                            if (!isSessionCurrent(sessionId)) return;
                            switch (event.result.state) {
                                case AUTHORIZATION_PENDING:
                                    // продолжаем, ничего не меняем
                                    break;
                                case SLOW_DOWN:
                                    break;
                                case RATE_LIMITED:
                                    break;
                                case NETWORK_ERROR:
                                case TEMPORARY_SERVER_ERROR:
                                    if (!event.canRetry) {
                                        onPollError(sessionId, null);
                                    }
                                    break;
                                case ACCESS_DENIED:
                                    onAccessDenied(sessionId);
                                    break;
                                case EXPIRED_TOKEN:
                                case INVALID_GRANT:
                                    onCodeExpired(sessionId);
                                    break;
                                case INVALID_CLIENT:
                                case UNAUTHORIZED_CLIENT:
                                    onInvalidClient(sessionId, null);
                                    break;
                                case SUCCESS:
                                    onTokenReceived(sessionId, event.result.accessToken,
                                            event.result.refreshToken,
                                            event.result.expiresInSeconds);
                                    break;
                            }
                        },
                        error -> {
                            if (isSessionCurrent(sessionId)) {
                                onPollError(sessionId, error.getMessage());
                            }
                        }
                );
    }

    @MainThread
    private void onTokenReceived(int sessionId, String token, String refreshToken, long expiresInSeconds) {
        if (!isSessionCurrent(sessionId)) {
            return; // stale callback — не применяем токен
        }
        invalidateSessionAndStopPolling();
        // Сохраняем токен: setOAuthTokens устанавливает UNVERIFIED + включает Lively
        if (!VotOAuthTokenValidator.isValid(token)) {
            Log.w(TAG, "Yandex OAuth returned an invalid token");
            mStatusView.setText(R.string.vot_device_auth_network_error);
            mRefreshButton.setEnabled(true);
            mRefreshButton.requestFocus();
            return;
        }
        mVotData.setOAuthTokens(token, refreshToken, expiresInSeconds);
        if (!mVotData.hasOAuthToken()) {
            Log.e(TAG, "Failed to persist OAuth token to secure store");
            mStatusView.setText(R.string.vot_device_auth_network_error);
            mRefreshButton.setEnabled(true);
            mRefreshButton.requestFocus();
            return;
        }
        // token здесь не логируется
        Log.d(TAG, "Yandex OAuth SUCCESS (tokenPresent=true, refreshPresent=%b)", refreshToken != null && !refreshToken.isEmpty());
        mStatusView.setText(R.string.vot_device_auth_success);
        mCodeView.setText("✓");
        mTimerView.setText("");
        mRefreshButton.setEnabled(false);

        // Закрыть диалог через 1.5 секунды (пользователь видит «Вход выполнен!»)
        final int completionGeneration = mSession.start();
        mMainHandler.postDelayed(() -> {
            if (sCurrentInstance == this && mSession.isCurrent(completionGeneration)
                    && mDialog != null && mDialog.isShowing()) {
                mDialog.dismiss();
            }
        }, 1500);
    }

    @MainThread
    private void onAccessDenied(int sessionId) {
        if (!isSessionCurrent(sessionId)) return;
        invalidateSessionAndStopPolling();
        mStatusView.setText(R.string.vot_device_auth_denied);
        mCodeView.setText("✕");
        mTimerView.setText("");
        mRefreshButton.setEnabled(true);
        mRefreshButton.requestFocus();
    }

    @MainThread
    private void onPollError(int sessionId, String message) {
        if (!isSessionCurrent(sessionId)) return;
        invalidateSessionAndStopPolling();
        mStatusView.setText(R.string.vot_device_auth_network_error);
        mRefreshButton.setEnabled(true);
        mRefreshButton.requestFocus();
    }

    @MainThread
    private void onInvalidClient(int sessionId, String message) {
        if (!isSessionCurrent(sessionId)) return;
        invalidateSessionAndStopPolling();
        Log.w(TAG, "OAuth client configuration error (invalid_client)");
        mStatusView.setText(R.string.vot_device_auth_invalid_client);
        mCodeView.setText("✕");
        mTimerView.setText("");
        mRefreshButton.setEnabled(true);
        mRefreshButton.requestFocus();
    }

    // -------------------------------------------------------------------------
    // Control
    // -------------------------------------------------------------------------

    @MainThread
    private void cancel() {
        invalidateSessionAndStopPolling();
        // Существующий токен сохраняется — НЕ вызываем clearOAuthToken()
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
    }

    private void stopPolling() {
        if (mPollDisposable != null) {
            mPollDisposable.dispose();
            mPollDisposable = null;
        }
        if (mTimerDisposable != null) {
            mTimerDisposable.dispose();
            mTimerDisposable = null;
        }
    }

    private void invalidateSessionAndStopPolling() {
        mSession.invalidate();
        stopPolling();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isSessionCurrent(int sessionId) {
        return mSession.isCurrent(sessionId) && isActivityAlive();
    }

    private boolean isActivityAlive() {
        return mActivity != null && !mActivity.isFinishing() && !mActivity.isDestroyed();
    }

    private static String formatTime(long totalSeconds) {
        long min = totalSeconds / 60;
        long sec = totalSeconds % 60;
        return String.format(Locale.ROOT, "%d:%02d", min, sec);
    }
}

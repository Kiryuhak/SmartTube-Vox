package com.liskovsoft.smartyoutubetv2.common.oauth;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** Максимальное число последовательных сетевых ошибок до показа сообщения. */
    private static final int MAX_NETWORK_ERRORS = 3;

    private final Activity mActivity;
    private final VotData mVotData;

    private AlertDialog mDialog;
    private Disposable mPollDisposable;
    private Disposable mTimerDisposable;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** Монотонно возрастающий счётчик сессии — защита от stale callbacks. */
    private final AtomicInteger mSessionId = new AtomicInteger(0);

    // UI views (lazy init — null до show())
    private TextView mCodeView;
    private TextView mUrlView;
    private TextView mInstructionView;
    private TextView mTimerView;
    private TextView mStatusView;
    private Button mRefreshButton;
    private Button mCancelButton;

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

        mUrlView.setText(R.string.vot_device_auth_url);
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
                    stopPolling();
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
        stopPolling();

        int sessionId = mSessionId.incrementAndGet();

        mRefreshButton.setEnabled(false);
        mStatusView.setText(R.string.vot_device_auth_pending);
        mCodeView.setText("··········");
        mTimerView.setText("");

        mPollDisposable = Observable
                .<YandexDeviceCodeResponse>create(emitter -> {
                    try {
                        YandexDeviceCodeClient client = new YandexDeviceCodeClient();
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

        Log.d(TAG, "Device code received: " + response);

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
        Log.e(TAG, "Failed to get device code: " + error.getMessage());
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

        mTimerDisposable = Observable.interval(0, 1, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tick -> {
                    if (!isSessionCurrent(sessionId)) {
                        if (mTimerDisposable != null) mTimerDisposable.dispose();
                        return;
                    }
                    long remaining = expiresIn - tick;
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
        stopPolling();
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
        final int[] networkErrors = {0};
        final int[] currentIntervalSec = {pollIntervalSec};

        if (mPollDisposable != null) {
            mPollDisposable.dispose();
        }

        mPollDisposable = Observable
                .<YandexTokenPollResult>create(emitter -> {
                    while (!emitter.isDisposed()) {
                        // Ждём интервал секунда-по-секунде (для быстрой отмены)
                        for (int i = 0; i < currentIntervalSec[0]; i++) {
                            if (emitter.isDisposed()) return;
                            TimeUnit.SECONDS.sleep(1);
                        }
                        if (emitter.isDisposed()) return;

                        YandexDeviceCodeClient client = new YandexDeviceCodeClient();
                        YandexTokenPollResult result = client.pollForToken(CLIENT_ID, deviceCode);
                        emitter.onNext(result);

                        if (result.isTerminal()) {
                            emitter.onComplete();
                            return;
                        }
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> {
                            if (!isSessionCurrent(sessionId)) return;
                            switch (result.getType()) {
                                case PENDING:
                                    networkErrors[0] = 0;
                                    // продолжаем, ничего не меняем
                                    break;
                                case SLOW_DOWN:
                                    networkErrors[0] = 0;
                                    currentIntervalSec[0] = Math.min(currentIntervalSec[0] + 5, 60);
                                    Log.d(TAG, "slow_down: new interval " + currentIntervalSec[0] + "s");
                                    break;
                                case NETWORK_ERROR:
                                    networkErrors[0]++;
                                    Log.w(TAG, "Poll network error #" + networkErrors[0]
                                            + ": " + result.getErrorMessage());
                                    if (networkErrors[0] >= MAX_NETWORK_ERRORS) {
                                        onPollError(sessionId, result.getErrorMessage());
                                    }
                                    break;
                                case ACCESS_DENIED:
                                    onAccessDenied(sessionId);
                                    break;
                                case EXPIRED:
                                    onCodeExpired(sessionId);
                                    break;
                                case INVALID_CLIENT:
                                    onInvalidClient(sessionId, result.getErrorMessage());
                                    break;
                                case SUCCESS:
                                    onTokenReceived(sessionId, result.getAccessToken());
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
    private void onTokenReceived(int sessionId, String token) {
        if (!isSessionCurrent(sessionId)) {
            return; // stale callback — не применяем токен
        }
        stopPolling();
        // Сохраняем токен: setOAuthToken устанавливает UNVERIFIED + включает Lively
        mVotData.setOAuthToken(token);
        // token здесь не логируется
        Log.d(TAG, "Yandex OAuth SUCCESS (tokenPresent=true, length=" + token.length() + ")");
        mStatusView.setText(R.string.vot_device_auth_success);
        mCodeView.setText("✓");
        mTimerView.setText("");
        mRefreshButton.setEnabled(false);

        // Закрыть диалог через 1.5 секунды (пользователь видит «Вход выполнен!»)
        mMainHandler.postDelayed(() -> {
            if (mDialog != null && mDialog.isShowing()) {
                mDialog.dismiss();
            }
        }, 1500);
    }

    @MainThread
    private void onAccessDenied(int sessionId) {
        if (!isSessionCurrent(sessionId)) return;
        stopPolling();
        mStatusView.setText(R.string.vot_device_auth_denied);
        mCodeView.setText("✕");
        mTimerView.setText("");
        mRefreshButton.setEnabled(true);
        mRefreshButton.requestFocus();
    }

    @MainThread
    private void onPollError(int sessionId, String message) {
        if (!isSessionCurrent(sessionId)) return;
        stopPolling();
        mStatusView.setText(R.string.vot_device_auth_network_error);
        mRefreshButton.setEnabled(true);
        mRefreshButton.requestFocus();
    }

    @MainThread
    private void onInvalidClient(int sessionId, String message) {
        if (!isSessionCurrent(sessionId)) return;
        stopPolling();
        Log.w(TAG, "OAuth client configuration error (invalid_client): " + message);
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
        stopPolling();
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isSessionCurrent(int sessionId) {
        return mSessionId.get() == sessionId && isActivityAlive();
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

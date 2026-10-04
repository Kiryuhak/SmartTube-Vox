package com.liskovsoft.smartyoutubetv2.common.vox.proxy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Диалог настройки изолированного прокси для VOX.
 */
public final class VoxProxyDialog {
    private final Context mContext;
    private final VotData mVotData;
    private final Handler mMainHandler;
    private AlertDialog mDialog;
    private Call mActiveTestCall;

    public interface OnSaveListener {
        void onSave(VoxProxyConfig config);
    }

    private VoxProxyDialog(@NonNull Context context) {
        mContext = context;
        mVotData = VotData.instance(context);
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    public static void show(@NonNull Context context) {
        show(context, null);
    }

    public static void show(@NonNull Context context, OnSaveListener listener) {
        new VoxProxyDialog(context).showInternal(listener);
    }

    private void showInternal(OnSaveListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext, R.style.AppDialog);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        View contentView = inflater.inflate(R.layout.vox_proxy_dialog, null);

        CheckBox enableCheckBox = contentView.findViewById(R.id.vox_proxy_enable_checkbox);
        RadioGroup typeGroup = contentView.findViewById(R.id.vox_proxy_type_group);
        RadioButton typeHttp = contentView.findViewById(R.id.vox_proxy_type_http);
        RadioButton typeSocks = contentView.findViewById(R.id.vox_proxy_type_socks);
        EditText hostEdit = contentView.findViewById(R.id.vox_proxy_host);
        EditText portEdit = contentView.findViewById(R.id.vox_proxy_port);
        EditText userEdit = contentView.findViewById(R.id.vox_proxy_username);
        EditText passEdit = contentView.findViewById(R.id.vox_proxy_password);
        TextView statusMsg = contentView.findViewById(R.id.vox_proxy_status_message);

        KeyHelpers.fixShowKeyboard(hostEdit, portEdit, userEdit, passEdit);

        VoxProxyConfig current = mVotData.getVoxProxyConfig();
        enableCheckBox.setChecked(current.isEnabled());
        if (current.getType() == VoxProxyConfig.Type.SOCKS) {
            typeSocks.setChecked(true);
        } else {
            typeHttp.setChecked(true);
        }
        hostEdit.setText(current.getHost());
        portEdit.setText(current.getPort() > 0 ? String.valueOf(current.getPort()) : "8080");
        userEdit.setText(current.getUsername() != null ? current.getUsername() : "");
        passEdit.setText(current.getPassword() != null ? current.getPassword() : "");

        mDialog = builder
                .setTitle(R.string.vox_proxy_title)
                .setView(contentView)
                .setNeutralButton(R.string.vox_proxy_test_btn, null)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    cancelTest();
                    d.dismiss();
                })
                .create();

        mDialog.show();

        mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean enabled = enableCheckBox.isChecked();
            VoxProxyConfig.Type type = typeSocks.isChecked() ? VoxProxyConfig.Type.SOCKS : VoxProxyConfig.Type.HTTP;
            String host = hostEdit.getText().toString().trim();
            int port = Helpers.parseInt(portEdit.getText().toString().trim(), 0);
            String user = userEdit.getText().toString().trim();
            String pass = passEdit.getText().toString();

            if (enabled) {
                if (!VoxProxyConfig.isValidHost(host)) {
                    statusMsg.setText(R.string.vox_proxy_invalid_host);
                    hostEdit.requestFocus();
                    return;
                }
                if (!VoxProxyConfig.isValidPort(port)) {
                    statusMsg.setText(R.string.vox_proxy_invalid_port);
                    portEdit.requestFocus();
                    return;
                }
            }

            VoxProxyConfig newConfig = new VoxProxyConfig(
                    enabled,
                    type,
                    host,
                    port,
                    !user.isEmpty() ? user : null,
                    !pass.isEmpty() ? pass : null
            );

            mVotData.setVoxProxyConfig(newConfig);
            cancelTest();
            if (listener != null) {
                listener.onSave(newConfig);
            }
            MessageHelpers.showMessage(mContext, enabled ? R.string.vox_proxy_saved : R.string.vox_proxy_disabled);
            mDialog.dismiss();
        });

        mDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            cancelTest();
            VoxProxyConfig.Type type = typeSocks.isChecked() ? VoxProxyConfig.Type.SOCKS : VoxProxyConfig.Type.HTTP;
            String host = hostEdit.getText().toString().trim();
            int port = Helpers.parseInt(portEdit.getText().toString().trim(), 0);
            String user = userEdit.getText().toString().trim();
            String pass = passEdit.getText().toString();

            if (!VoxProxyConfig.isValidHost(host)) {
                statusMsg.setText(R.string.vox_proxy_invalid_host);
                hostEdit.requestFocus();
                return;
            }
            if (!VoxProxyConfig.isValidPort(port)) {
                statusMsg.setText(R.string.vox_proxy_invalid_port);
                portEdit.requestFocus();
                return;
            }

            VoxProxyConfig testConfig = new VoxProxyConfig(
                    true,
                    type,
                    host,
                    port,
                    !user.isEmpty() ? user : null,
                    !pass.isEmpty() ? pass : null
            );

            statusMsg.setText("Проверка подключения...");
            runConnectionTest(testConfig, statusMsg);
        });
    }

    private void cancelTest() {
        Call call = mActiveTestCall;
        if (call != null) {
            call.cancel();
            mActiveTestCall = null;
        }
    }

    private void runConnectionTest(VoxProxyConfig config, TextView statusMsg) {
        Executors.newSingleThreadExecutor().submit(() -> {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .writeTimeout(8, TimeUnit.SECONDS);

            java.net.Proxy javaProxy = config.toJavaProxy();
            if (javaProxy != null) {
                builder.proxy(javaProxy);
                if (config.getUsername() != null && config.getPassword() != null) {
                    final String credential = okhttp3.Credentials.basic(config.getUsername(), config.getPassword());
                    builder.proxyAuthenticator((route, response) -> response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build());
                }
            }

            OkHttpClient client = builder.build();
            Request request = new Request.Builder()
                    .url("https://api.browser.yandex.ru/health")
                    .head()
                    .build();

            Call call = client.newCall(request);
            mActiveTestCall = call;

            try (Response response = call.execute()) {
                mActiveTestCall = null;
                int code = response.code();
                VoxSafeLogger.i(
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.PROXY,
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.PROXY_CONNECTED,
                        "Проверка подключения через прокси успешна",
                        java.util.Collections.singletonMap("type", config.getType().name())
                );
                mMainHandler.post(() -> {
                    if (statusMsg != null) {
                        statusMsg.setText(mContext.getString(R.string.vox_proxy_test_success, code));
                    }
                });
            } catch (IOException e) {
                mActiveTestCall = null;
                VoxSafeLogger.e(
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.PROXY,
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.PROXY_FAILED,
                        "Ошибка проверки подключения через прокси",
                        java.util.Collections.singletonMap("type", config.getType().name()),
                        e
                );
                mMainHandler.post(() -> {
                    if (statusMsg != null) {
                        statusMsg.setText(mContext.getString(R.string.vox_proxy_test_failed, e.getClass().getSimpleName()));
                    }
                });
            }
        });
    }
}

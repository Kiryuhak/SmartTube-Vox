package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxDiagnosticReport;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxDiagnosticsClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Презентер экрана диагностики совместимости VOX.
 * Предоставляет безопасный просмотр, копирование и анонимную отправку отчёта разработчикам.
 */
public class VoxDiagnosticsPresenter extends BasePresenter<Void> {
    private static final String TAG = "VoxDiagnosticsPresenter";
    private static VoxDiagnosticsPresenter sInstance;

    private VoxDiagnosticsPresenter(Context context) {
        super(context);
    }

    public static synchronized VoxDiagnosticsPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new VoxDiagnosticsPresenter(context);
        }
        sInstance.setContext(context);
        return sInstance;
    }

    public void show() {
        Context context = getContext();
        if (context == null) return;

        AppDialogPresenter presenter = AppDialogPresenter.instance(context);
        String title = context.getString(R.string.vox_compatibility_diagnostics);

        List<OptionItem> options = new ArrayList<>();

        // 1. Посмотреть отчёт
        options.add(UiOptionItem.from(context.getString(R.string.vox_diagnostics_view), option -> {
            presenter.closeDialog();
            showReportDialog();
        }));

        // 2. Скопировать отчёт
        options.add(UiOptionItem.from(context.getString(R.string.vox_diagnostics_copy), option -> {
            presenter.closeDialog();
            copyReportToClipboard();
        }));

        // 3. Отправить разработчику
        options.add(UiOptionItem.from(context.getString(R.string.vox_diagnostics_send), option -> {
            presenter.closeDialog();
            showSendConsentDialog();
        }));

        // 4. Отмена
        options.add(UiOptionItem.from(context.getString(R.string.cancel_dialog), option -> {
            presenter.closeDialog();
        }));

        presenter.appendStringsCategory(title, options);
        presenter.showDialog(title);
    }

    private void showReportDialog() {
        Context context = getContext();
        if (!(context instanceof Activity) || !Utils.checkActivity((Activity) context)) {
            Log.w(TAG, "Cannot show report dialog: invalid activity");
            return;
        }

        Activity activity = (Activity) context;
        VoxDiagnosticReport report = VoxDiagnosticReport.create(activity, null);
        String text = report.toFormattedText();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(R.string.vox_compatibility_diagnostics)
                .setMessage(text)
                .setCancelable(true)
                .setPositiveButton(R.string.vox_diagnostics_copy, (dialog, which) -> {
                    copyToClipboard(activity, text);
                    MessageHelpers.showMessage(activity, R.string.vox_diagnostics_copied);
                })
                .setNeutralButton(R.string.vox_diagnostics_send, (dialog, which) -> {
                    showSendConsentDialog();
                })
                .setNegativeButton(android.R.string.ok, (dialog, which) -> dialog.dismiss());

        try {
            builder.create().show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show diagnostics report dialog", e);
        }
    }

    private void copyReportToClipboard() {
        Context context = getContext();
        if (context == null) return;

        VoxDiagnosticReport report = VoxDiagnosticReport.create(context, null);
        String text = report.toFormattedText();
        copyToClipboard(context, text);
        MessageHelpers.showMessage(context, R.string.vox_diagnostics_copied);
    }

    private void showSendConsentDialog() {
        Context context = getContext();
        if (!(context instanceof Activity) || !Utils.checkActivity((Activity) context)) {
            return;
        }

        Activity activity = (Activity) context;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(R.string.vox_diagnostics_send_consent_title)
                .setMessage(R.string.vox_diagnostics_send_consent_desc)
                .setCancelable(true)
                .setPositiveButton(R.string.vox_diagnostics_send_confirm, (dialog, which) -> {
                    dialog.dismiss();
                    performReportSubmission(activity);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss());

        try {
            builder.create().show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show consent dialog", e);
        }
    }

    private void performReportSubmission(Activity activity) {
        VoxDiagnosticReport report = VoxDiagnosticReport.create(activity, null);

        VoxDiagnosticsClient.instance().submitReportAsync(report, new VoxDiagnosticsClient.Callback() {
            @Override
            public void onSuccess(String reportId) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                String msg = activity.getString(R.string.vox_diagnostics_sent_success, reportId);
                MessageHelpers.showMessage(activity, msg);
            }

            @Override
            public void onError(String errorMessage) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                String msg = activity.getString(R.string.vox_diagnostics_send_failed, errorMessage);
                MessageHelpers.showMessage(activity, msg);
            }
        });
    }

    private void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("VOX Diagnostics", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy to clipboard", e);
        }
    }
}

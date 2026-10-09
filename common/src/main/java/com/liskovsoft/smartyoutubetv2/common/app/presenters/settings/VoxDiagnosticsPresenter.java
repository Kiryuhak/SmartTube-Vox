package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogEvent;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogLevel;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogStore;
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannel;
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Презентер раздела «Диагностика и логи» VOX.
 * 
 * Предоставляет безопасный просмотр отчёта, журнал ошибок, копирование,
 * очистку журнала и анонимную отправку разработчикам с обязательным согласием пользователя.
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
        String title = context.getString(R.string.settings_diagnostics_and_logs);

        VoxUpdateChannelManager channelManager = VoxUpdateChannelManager.instance(context);
        boolean isBeta = channelManager.isBetaChannel();
        boolean isAutoDiagActive = channelManager.isDiagnosticsActive();

        String diagModeStatus = isAutoDiagActive
                ? (isBeta ? context.getString(R.string.vox_diagnostics_mode_test) : context.getString(R.string.vox_diagnostics_mode_stable))
                : context.getString(R.string.vox_diagnostics_mode_disabled);

        // 1. Статус автодиагностики и переключатель
        presenter.appendSingleSwitch(UiOptionItem.from(
                context.getString(R.string.vox_diagnostics_auto_toggle),
                diagModeStatus,
                optionItem -> {
                    if (isBeta) {
                        channelManager.setBetaDiagnosticsEnabled(optionItem.isSelected());
                    } else {
                        channelManager.setStableDiagnosticsEnabled(optionItem.isSelected());
                    }
                    presenter.closeDialog();
                    show();
                },
                isAutoDiagActive
        ));

        List<OptionItem> options = new ArrayList<>();

        // 2. Просмотреть отчёт диагностики
        options.add(UiOptionItem.from(context.getString(R.string.vox_diagnostics_view), option -> {
            presenter.closeDialog();
            showReportDialog();
        }));

        // 3. Журнал ошибок
        int eventCount = VoxLogStore.instance(context).getJournalEventCount();
        String journalTitle = eventCount > 0
                ? context.getString(R.string.vox_logs_journal) + " (" + eventCount + ")"
                : context.getString(R.string.vox_logs_journal);

        options.add(UiOptionItem.from(journalTitle, option -> {
            presenter.closeDialog();
            showJournalDialog();
        }));

        // 4. Скопировать журнал
        options.add(UiOptionItem.from(context.getString(R.string.vox_logs_copy), option -> {
            presenter.closeDialog();
            copyJournalToClipboard();
        }));

        // 5. Отправить отчёт разработчику
        options.add(UiOptionItem.from(context.getString(R.string.vox_diagnostics_send), option -> {
            presenter.closeDialog();
            showSendConsentDialog();
        }));

        // 6. Очистить журнал
        options.add(UiOptionItem.from(context.getString(R.string.vox_logs_clear), option -> {
            presenter.closeDialog();
            showClearLogsConfirmDialog();
        }));

        // 7. Отмена / Назад
        options.add(UiOptionItem.from(context.getString(R.string.cancel_dialog), option -> {
            presenter.closeDialog();
        }));

        presenter.appendStringsCategory(title, options);
        presenter.showDialog(title);
    }

    public void showReportDialog() {
        Context context = getContext();
        if (!(context instanceof Activity) || !Utils.checkActivity((Activity) context)) {
            Log.w(TAG, "Cannot show report dialog: invalid activity");
            return;
        }

        Activity activity = (Activity) context;
        VoxDiagnosticReport report = VoxDiagnosticReport.create(activity, null, true);
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

    public void showJournalDialog() {
        Context context = getContext();
        if (context == null) return;

        List<VoxLogEvent> events = VoxLogStore.instance(context).getEvents(100, VoxLogLevel.INFO);
        AppDialogPresenter presenter = AppDialogPresenter.instance(context);
        String title = context.getString(R.string.vox_logs_journal);

        if (events.isEmpty()) {
            List<OptionItem> options = new ArrayList<>();
            options.add(UiOptionItem.from(context.getString(R.string.vox_logs_journal_empty), opt -> {
                presenter.closeDialog();
                show();
            }));
            options.add(UiOptionItem.from(context.getString(android.R.string.cancel), opt -> presenter.closeDialog()));
            presenter.appendStringsCategory(title, options);
            presenter.showDialog(title);
            return;
        }

        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        List<OptionItem> options = new ArrayList<>();

        for (VoxLogEvent ev : events) {
            String timeStr = timeFmt.format(new Date(ev.getTimestamp()));
            String itemTitle = String.format("[%s] %s · %s", timeStr, ev.getCategory().name(), ev.getCode());
            String itemDesc = ev.getMessage();

            options.add(UiOptionItem.from(itemTitle + "\n" + itemDesc, opt -> {
                presenter.closeDialog();
                showEventDetailsDialog(ev);
            }));
        }

        options.add(UiOptionItem.from(context.getString(R.string.vox_logs_clear), opt -> {
            presenter.closeDialog();
            showClearLogsConfirmDialog();
        }));

        options.add(UiOptionItem.from(context.getString(android.R.string.cancel), opt -> {
            presenter.closeDialog();
            show();
        }));

        presenter.appendStringsCategory(title, options);
        presenter.showDialog(title);
    }

    private void showEventDetailsDialog(VoxLogEvent event) {
        Context context = getContext();
        if (!(context instanceof Activity) || !Utils.checkActivity((Activity) context)) {
            return;
        }

        Activity activity = (Activity) context;
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String timeStr = df.format(new Date(event.getTimestamp()));

        StringBuilder sb = new StringBuilder();
        sb.append("Категория: ").append(event.getCategory().name()).append(" (").append(event.getCategory().getDisplayNameRu()).append(")\n");
        sb.append("Код: ").append(event.getCode()).append("\n");
        sb.append("Уровень: ").append(event.getLevel().name()).append("\n");
        sb.append("Время: ").append(timeStr).append("\n\n");
        sb.append("Описание:\n").append(event.getMessage()).append("\n");

        if (event.getContext() != null && !event.getContext().isEmpty()) {
            sb.append("\nКонтекст:\n");
            event.getContext().forEach((k, v) -> sb.append("• ").append(k).append(" = ").append(v).append("\n"));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(event.getCode())
                .setMessage(sb.toString())
                .setCancelable(true)
                .setPositiveButton(R.string.vox_diagnostics_copy, (dialog, which) -> {
                    copyToClipboard(activity, sb.toString());
                    MessageHelpers.showMessage(activity, R.string.vox_logs_copied);
                })
                .setNegativeButton(android.R.string.ok, (dialog, which) -> {
                    dialog.dismiss();
                    showJournalDialog();
                });

        try {
            builder.create().show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show event details dialog", e);
        }
    }

    public void copyJournalToClipboard() {
        Context context = getContext();
        if (context == null) return;

        String journal = VoxLogStore.instance(context).getFormattedJournal();
        copyToClipboard(context, journal);
        MessageHelpers.showMessage(context, R.string.vox_logs_copied);
    }

    public void showClearLogsConfirmDialog() {
        Context context = getContext();
        if (!(context instanceof Activity) || !Utils.checkActivity((Activity) context)) {
            return;
        }

        Activity activity = (Activity) context;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(R.string.vox_logs_clear_confirm_title)
                .setMessage(R.string.vox_logs_clear_confirm_message)
                .setCancelable(true)
                .setPositiveButton(R.string.vox_logs_clear_confirm_btn, (dialog, which) -> {
                    dialog.dismiss();
                    VoxLogStore.instance(activity).clearLogs();
                    MessageHelpers.showMessage(activity, R.string.vox_logs_cleared);
                    show();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    dialog.dismiss();
                    show();
                });

        try {
            builder.create().show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show clear logs dialog", e);
        }
    }

    public void showSendConsentDialog() {
        Context context = getContext();
        if (!(context instanceof Activity) || !Utils.checkActivity((Activity) context)) {
            return;
        }

        Activity activity = (Activity) context;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(R.string.vox_diagnostics_send_consent_title)
                .setMessage(R.string.vox_diagnostics_send_consent_desc_v2)
                .setCancelable(true)
                .setPositiveButton(R.string.vox_diagnostics_send_confirm, (dialog, which) -> {
                    dialog.dismiss();
                    performReportSubmission(activity);
                })
                .setNeutralButton(R.string.vox_diagnostics_view, (dialog, which) -> {
                    dialog.dismiss();
                    showReportDialog();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss());

        try {
            builder.create().show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show consent dialog", e);
        }
    }

    private void performReportSubmission(Activity activity) {
        VoxDiagnosticReport report = VoxDiagnosticReport.create(activity, null, true);

        VoxDiagnosticsClient.instance().submitReportAsync(report, new VoxDiagnosticsClient.Callback() {
            @Override
            public void onSuccess(String reportId) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                showSendSuccessDialog(activity, reportId);
            }

            @Override
            public void onError(String errorMessage) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                showSendFailureDialog(activity, errorMessage);
            }
        });
    }

    private void showSendSuccessDialog(Activity activity, String reportId) {
        String msg = activity.getString(R.string.vox_diagnostics_sent_success_code, reportId);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(R.string.vox_diagnostics_send_consent_title)
                .setMessage(msg)
                .setCancelable(true)
                .setPositiveButton(R.string.vox_diagnostics_btn_copy_code, (dialog, which) -> {
                    copyToClipboard(activity, reportId);
                    MessageHelpers.showMessage(activity, R.string.vox_diagnostics_copied);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss());

        try {
            builder.create().show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show success dialog", e);
        }
    }

    private void showSendFailureDialog(Activity activity, String errorMessage) {
        String msg = activity.getString(R.string.vox_diagnostics_send_failed_retry) + "\n\n" + errorMessage;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(R.string.vox_diagnostics_send_consent_title)
                .setMessage(msg)
                .setCancelable(true)
                .setPositiveButton(R.string.vox_diagnostics_btn_retry, (dialog, which) -> {
                    dialog.dismiss();
                    performReportSubmission(activity);
                })
                .setNeutralButton(R.string.vox_logs_copy, (dialog, which) -> {
                    copyJournalToClipboard();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss());

        try {
            builder.create().show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show failure dialog", e);
        }
    }

    private void copyToClipboard(Context context, String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("SmartTube VOX", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy to clipboard", e);
        }
    }
}

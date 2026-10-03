package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager;

public class VoxDiagnosticsPresenter extends BasePresenter<Void> {
    private static final String TAG = "VoxDiagnosticsPresenter";
    private static VoxDiagnosticsPresenter sInstance;
    private AlertDialog mDialog;

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
        if (!(context instanceof Activity) || !Utils.checkActivity((Activity) context)) {
            Log.w(TAG, "Cannot show diagnostics: invalid activity context");
            return;
        }

        Activity activity = (Activity) context;
        String report = VoxCompatibilityManager.instance(activity).generateSafeDiagnosticReport();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(R.string.vox_compatibility_diagnostics)
                .setMessage(report)
                .setCancelable(true)
                .setPositiveButton(R.string.vox_diagnostics_copy, (dialog, which) -> {
                    copyToClipboard(activity, report);
                    MessageHelpers.showMessage(activity, R.string.vox_diagnostics_copied);
                })
                .setNegativeButton(android.R.string.ok, (dialog, which) -> dialog.dismiss());

        try {
            mDialog = builder.create();
            mDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show diagnostics dialog", e);
        }
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

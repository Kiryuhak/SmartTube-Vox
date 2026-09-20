package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.app.Activity;
import android.content.Context;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.ResetManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class ResetSettingsPresenter extends BasePresenter<Void> {
    private static final String TAG = "ResetSettingsPresenter";
    private static ResetSettingsPresenter sInstance;
    private AlertDialog mDialog;

    ResetSettingsPresenter(Context context) {
        super(context);
    }

    public static synchronized ResetSettingsPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new ResetSettingsPresenter(context);
        }
        sInstance.setContext(context);
        return sInstance;
    }

    public static void unhold() {
        sInstance = null;
    }

    public void show() {
        Context context = getContext();
        if (!(context instanceof Activity) || !Utils.checkActivity((Activity) context)) {
            Log.w(TAG, "Cannot show reset dialog: invalid activity context");
            return;
        }

        if (mDialog != null && mDialog.isShowing()) {
            Log.w(TAG, "Reset dialog is already displayed");
            return;
        }

        Activity activity = (Activity) context;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(R.string.reset_dialog_title)
                .setMessage(R.string.reset_dialog_message)
                .setCancelable(true)
                .setPositiveButton(R.string.reset_dialog_confirm, (dialog, which) -> {
                    dialog.dismiss();
                    boolean success = ResetManager.instance().resetApplicationData(activity);
                    if (!success) {
                        MessageHelpers.showMessage(activity, R.string.reset_dialog_failed);
                    }
                })
                .setNegativeButton(R.string.reset_dialog_cancel, (dialog, which) -> {
                    dialog.dismiss();
                })
                .setOnDismissListener(dialog -> {
                    mDialog = null;
                });

        try {
            mDialog = builder.create();
            mDialog.setOnShowListener(d -> {
                Button negative = mDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (negative != null) {
                    negative.requestFocus();
                }
            });
            mDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show reset dialog: " + e.getMessage(), e);
            mDialog = null;
        }
    }

    public AlertDialog getDialog() {
        return mDialog;
    }
}

package com.liskovsoft.smartyoutubetv2.common.utils;

import android.app.Activity;
import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.VoxCompatibilitySettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.VoxDiagnosticsPresenter;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.TriStateCapability;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VideoCodecCapability;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxDeviceProfile;

public final class VoxCompatibilityOnboardingHelper {
    private static final String TAG = "VoxCompatibilityOnboard";
    private static boolean sIsDialogShowing;
    private static AlertDialog sDialogInstance;

    private VoxCompatibilityOnboardingHelper() {
    }

    public static void checkCompatibilityOnboarding(Activity activity) {
        if (!Utils.checkActivity(activity) || !Utils.isAppInForegroundFixed()) {
            return;
        }

        if (!VotOnboardingHelper.isStvot(activity)) {
            return;
        }

        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(activity);
        if (manager.isScanCompleted()) {
            return;
        }

        if (sIsDialogShowing && sDialogInstance != null && sDialogInstance.isShowing()) {
            return;
        }

        showCompatibilityPrompt(activity);
    }

    private static void showCompatibilityPrompt(Activity activity) {
        sIsDialogShowing = true;
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(activity);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
        builder.setTitle(R.string.vox_compatibility_title)
                .setMessage(R.string.vox_compatibility_message)
                .setCancelable(true)
                .setPositiveButton(R.string.vox_compatibility_btn_auto, (dialog, which) -> {
                    manager.setScanCompleted(true);
                    dialog.dismiss();
                    showScanResult(activity);
                })
                .setNeutralButton(R.string.vox_compatibility_btn_manual, (dialog, which) -> {
                    manager.setScanCompleted(true);
                    dialog.dismiss();
                    VoxCompatibilitySettingsPresenter.instance(activity).show();
                })
                .setNegativeButton(R.string.vox_compatibility_btn_later, (dialog, which) -> {
                    manager.setScanCompleted(true);
                    dialog.dismiss();
                })
                .setOnDismissListener(dialog -> {
                    sIsDialogShowing = false;
                    sDialogInstance = null;
                });

        try {
            AlertDialog dialog = builder.create();
            sDialogInstance = dialog;
            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show compatibility onboarding dialog", e);
            sIsDialogShowing = false;
            sDialogInstance = null;
        }
    }

    private static void showScanResult(Activity activity) {
        try {
            VoxCompatibilityManager manager = VoxCompatibilityManager.instance(activity);
            VoxDeviceProfile profile = manager.getDeviceProfile(true);

            StringBuilder sb = new StringBuilder();
            sb.append("Устройство: ").append(profile.getManufacturer()).append(" ").append(profile.getModel()).append("\n");
            sb.append("Режим: Автоматически\n\n");

            sb.append("Видео:\n");
            appendVideoLine(sb, "AVC", profile.getVideoCodecs().get("avc"));
            appendVideoLine(sb, "VP9", profile.getVideoCodecs().get("vp9"));
            appendVideoLine(sb, "AV1", profile.getVideoCodecs().get("av1"));

            sb.append("\nАудио:\n");
            appendAudioLine(sb, "AAC", profile.getAudioCodecs().get("aac"));
            appendAudioLine(sb, "Opus", profile.getAudioCodecs().get("opus"));
            appendAudioLine(sb, "AC3", profile.getAudioCodecs().get("ac3"));
            appendAudioLine(sb, "EAC3", profile.getAudioCodecs().get("eac3"));

            AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppDialog);
            builder.setTitle(R.string.vox_compatibility_scan_completed_title)
                    .setMessage(sb.toString())
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                    .setNeutralButton(R.string.vox_compatibility_diagnostics, (dialog, which) -> {
                        dialog.dismiss();
                        VoxDiagnosticsPresenter.instance(activity).show();
                    });

            builder.create().show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show scan results dialog", e);
        }
    }

    private static void appendVideoLine(StringBuilder sb, String name, VideoCodecCapability cap) {
        String status = cap != null && cap.getCapability() == TriStateCapability.SUPPORTED ? "Поддерживается" : "Не поддерживается";
        sb.append("• ").append(name).append(" — ").append(status).append("\n");
    }

    private static void appendAudioLine(StringBuilder sb, String name, com.liskovsoft.smartyoutubetv2.common.vox.capability.AudioCodecCapability cap) {
        String status;
        if (cap != null && cap.getDecodeCapability() == TriStateCapability.SUPPORTED) {
            status = "Поддерживается";
        } else if (cap != null && cap.getPassthroughCapability() == TriStateCapability.SUPPORTED) {
            status = "Только passthrough";
        } else {
            status = "Не поддерживается";
        }
        sb.append("• ").append(name).append(" — ").append(status).append("\n");
    }
}

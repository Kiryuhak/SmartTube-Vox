/*
 * Copyright (C) 2026 SmartTube VOX
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.tv.vot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController;

/**
 * Developer-only internal diagnostics receiver for Yandex VOT backend verification.
 * Not exported in AndroidManifest (android:exported="false").
 *
 * Supported internal actions:
 * - backendStatus
 * - forceOldBackend / disableNewBackend
 * - useDefaultBackend / enableNewBackend
 * - status
 */
public class YandexVotTestReceiver extends BroadcastReceiver {
    public static final String ACTION_TEST_YANDEX_VOT = "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT";
    private static final String TAG = "YANDEX_VOT_TEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_TEST_YANDEX_VOT.equals(intent.getAction())) {
            return;
        }

        String action = intent.getStringExtra("action");
        if (action == null || action.isEmpty() || "backendStatus".equalsIgnoreCase(action)) {
            handleBackendStatus();
        } else if ("enableNewBackend".equalsIgnoreCase(action) || "useDefaultBackend".equalsIgnoreCase(action)) {
            VoiceTranslateController.setNewYandexBackendEnabled(true);
            Log.i(TAG, "New Yandex VOT backend ENABLED for user flow (default candidate)");
        } else if ("disableNewBackend".equalsIgnoreCase(action) || "forceOldBackend".equalsIgnoreCase(action)) {
            VoiceTranslateController.setNewYandexBackendEnabled(false);
            Log.i(TAG, "New Yandex VOT backend DISABLED for user flow (forced OLD override)");
        } else if ("startTranslation".equalsIgnoreCase(action)) {
            VoiceTranslateController controller = VoiceTranslateController.instance();
            if (controller != null) {
                controller.onButtonClicked(com.liskovsoft.smartyoutubetv2.common.R.id.action_voice_translate, VoiceTranslateController.BTN_OFF);
                Log.i(TAG, "Triggered start translation via diagnostic broadcast");
            }
        } else if ("stopTranslation".equalsIgnoreCase(action)) {
            VoiceTranslateController controller = VoiceTranslateController.instance();
            if (controller != null) {
                controller.onButtonClicked(com.liskovsoft.smartyoutubetv2.common.R.id.action_voice_translate, VoiceTranslateController.BTN_ON);
                Log.i(TAG, "Triggered stop translation via diagnostic broadcast");
            }
        } else if ("status".equalsIgnoreCase(action)) {
            handleStatus();
        } else {
            Log.w(TAG, "Unknown diagnostic action: " + action);
        }
    }

    private void handleStatus() {
        handleBackendStatus();
    }

    private void handleBackendStatus() {
        boolean flagEnabled = VoiceTranslateController.isNewYandexBackendEnabled();
        boolean userFlowActive = VoiceTranslateController.isUserFlowActive();
        VoiceTranslateController controller = VoiceTranslateController.instance();
        boolean isNewActive = controller != null && controller.isNewBackendActive();
        boolean fallbackTriggered = controller != null && controller.isFallbackTriggered();
        Log.i(TAG, "BackendStatus: flag_new_backend=" + (flagEnabled ? "ON" : "OFF")
                + " default_candidate=" + (flagEnabled ? "NEW" : "OLD")
                + " user_flow_active=" + userFlowActive
                + " active_backend=" + (isNewActive ? "NEW" : (userFlowActive ? "OLD" : "NONE"))
                + " fallback_triggered=" + fallbackTriggered);
    }
}

package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxAudioCodecPreference;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCodecPolicy;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCodecPolicyMode;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityRisk;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityRiskEvaluator;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxDeviceProfile;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxRecommendedSettings;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxVideoCodecPreference;

import java.util.ArrayList;
import java.util.List;

public class VoxCompatibilitySettingsPresenter extends BasePresenter<Void> {
    private static VoxCompatibilitySettingsPresenter sInstance;

    private VoxCompatibilitySettingsPresenter(Context context) {
        super(context);
    }

    public static synchronized VoxCompatibilitySettingsPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new VoxCompatibilitySettingsPresenter(context);
        }
        sInstance.setContext(context);
        return sInstance;
    }

    public void show() {
        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        appendAutoTuneButton(settingsPresenter);
        appendPolicyModeCategory(settingsPresenter);
        appendMaxQualityCategory(settingsPresenter);
        appendVideoCodecCategory(settingsPresenter);
        appendAudioCodecCategory(settingsPresenter);
        appendPassthroughCategory(settingsPresenter);
        appendActionButtons(settingsPresenter);

        settingsPresenter.showDialog(getContext().getString(R.string.settings_device_compatibility));
    }

    private void appendAutoTuneButton(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.vox_compatibility_auto_tune), option -> {
                    showAutoTuneDialog();
                })
        );
    }

    private void showAutoTuneDialog() {
        if (getContext() == null) return;
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());

        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.info(
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.COMPATIBILITY,
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.AUTO_TUNING_SCANNED,
                "Hardware scan triggered for auto tuning"
        );

        VoxRecommendedSettings rec = manager.getRecommendedSettings(true);

        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.info(
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.COMPATIBILITY,
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.AUTO_TUNING_RECOMMENDED,
                "Recommended profile: " + rec.getTier().name() + " (" + rec.getReasonCodes().toString() + ")"
        );

        AppDialogPresenter presenter = AppDialogPresenter.instance(getContext());
        String title = getContext().getString(R.string.vox_compatibility_auto_tune);
        String desc = getContext().getString(R.string.vox_compatibility_auto_tune_summary) + "\n\n" + rec.getSummaryRu();

        List<OptionItem> options = new ArrayList<>();
        options.add(UiOptionItem.from(getContext().getString(R.string.vox_compatibility_auto_tune_apply), opt -> {
            manager.applyRecommendedSettings(rec);
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.info(
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.COMPATIBILITY,
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.AUTO_TUNING_APPLIED,
                    "Auto tuning profile applied: tier=" + rec.getTier().name() + ", maxQuality=" + rec.getMaxQualityHeight() + "p"
            );
            presenter.closeDialog();
            MessageHelpers.showMessage(getContext(), R.string.vox_compatibility_auto_tune_applied);
            show();
        }));

        options.add(UiOptionItem.from(getContext().getString(R.string.vox_compatibility_view_details), opt -> {
            presenter.closeDialog();
            VoxDiagnosticsPresenter.instance(getContext()).show();
        }));

        options.add(UiOptionItem.from(getContext().getString(R.string.cancel_dialog), opt -> {
            presenter.closeDialog();
        }));

        presenter.appendStringsCategory(desc, options);
        presenter.showDialog(title);
    }

    private void appendPolicyModeCategory(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());
        VoxCodecPolicy policy = manager.getCodecPolicy();

        List<OptionItem> options = new ArrayList<>();
        for (VoxCodecPolicyMode mode : VoxCodecPolicyMode.values()) {
            options.add(UiOptionItem.from(
                    mode.getTitleRu(),
                    option -> {
                        VoxCodecPolicy proposed = new VoxCodecPolicy(
                                mode,
                                policy.getMaxQualityHeight(),
                                policy.getPreferredVideoCodec(),
                                policy.getPreferredAudioCodec(),
                                policy.getPassthroughEnabled()
                        );
                        applyPolicyWithRiskCheck(proposed);
                    },
                    mode == policy.getMode()
            ));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.vox_compatibility_mode), options);
    }

    private void appendMaxQualityCategory(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());
        VoxCodecPolicy policy = manager.getCodecPolicy();

        int[] heights = {0, 2160, 1440, 1080, 720, 480};
        String[] titles = {
                getContext().getString(R.string.vox_compatibility_mode_auto),
                "4K (2160p)",
                "2K (1440p)",
                "Full HD (1080p)",
                "HD (720p)",
                "SD (480p)"
        };

        List<OptionItem> options = new ArrayList<>();
        for (int i = 0; i < heights.length; i++) {
            final int h = heights[i];
            options.add(UiOptionItem.from(
                    titles[i],
                    option -> {
                        VoxCodecPolicy proposed = new VoxCodecPolicy(
                                policy.getMode(),
                                h,
                                policy.getPreferredVideoCodec(),
                                policy.getPreferredAudioCodec(),
                                policy.getPassthroughEnabled()
                        );
                        applyPolicyWithRiskCheck(proposed);
                    },
                    policy.getMaxQualityHeight() == h
            ));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.vox_compatibility_max_quality), options);
    }

    private void appendVideoCodecCategory(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());
        VoxCodecPolicy policy = manager.getCodecPolicy();

        List<OptionItem> options = new ArrayList<>();
        for (VoxVideoCodecPreference pref : VoxVideoCodecPreference.values()) {
            options.add(UiOptionItem.from(
                    pref.getDisplayName(),
                    option -> {
                        VoxCodecPolicy proposed = new VoxCodecPolicy(
                                policy.getMode(),
                                policy.getMaxQualityHeight(),
                                pref,
                                policy.getPreferredAudioCodec(),
                                policy.getPassthroughEnabled()
                        );
                        applyPolicyWithRiskCheck(proposed);
                    },
                    policy.getPreferredVideoCodec() == pref
            ));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.vox_compatibility_preferred_video), options);
    }

    private void appendAudioCodecCategory(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());
        VoxCodecPolicy policy = manager.getCodecPolicy();

        List<OptionItem> options = new ArrayList<>();
        for (VoxAudioCodecPreference pref : VoxAudioCodecPreference.values()) {
            options.add(UiOptionItem.from(
                    pref.getDisplayName(),
                    option -> {
                        VoxCodecPolicy proposed = new VoxCodecPolicy(
                                policy.getMode(),
                                policy.getMaxQualityHeight(),
                                policy.getPreferredVideoCodec(),
                                pref,
                                policy.getPassthroughEnabled()
                        );
                        applyPolicyWithRiskCheck(proposed);
                    },
                    policy.getPreferredAudioCodec() == pref
            ));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.vox_compatibility_preferred_audio), options);
    }

    private void appendPassthroughCategory(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());
        VoxCodecPolicy policy = manager.getCodecPolicy();

        List<OptionItem> options = new ArrayList<>();
        options.add(UiOptionItem.from(
                getContext().getString(R.string.vox_compatibility_passthrough),
                option -> {
                    VoxCodecPolicy proposed = new VoxCodecPolicy(
                            policy.getMode(),
                            policy.getMaxQualityHeight(),
                            policy.getPreferredVideoCodec(),
                            policy.getPreferredAudioCodec(),
                            option.isSelected()
                    );
                    applyPolicyWithRiskCheck(proposed);
                },
                policy.getPassthroughEnabled()
        ));

        settingsPresenter.appendCheckedCategory(getContext().getString(R.string.vox_compatibility_passthrough), options);
    }

    private void applyPolicyWithRiskCheck(VoxCodecPolicy proposedPolicy) {
        if (getContext() == null) return;
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());
        VoxDeviceProfile profile = manager.getDeviceProfile(false);
        VoxRecommendedSettings recommended = manager.getRecommendedSettings(false);

        VoxCompatibilityRisk risk = VoxCompatibilityRiskEvaluator.evaluate(proposedPolicy, recommended, profile);
        if (risk != null) {
            com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.warn(
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.COMPATIBILITY,
                    com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.AUTO_TUNING_RISK_WARNING,
                    "Manual override risk warning: " + risk.getMessageRu()
            );

            AppDialogPresenter presenter = AppDialogPresenter.instance(getContext());
            String title = getContext().getString(R.string.vox_compatibility_risk_warning_title);
            String message = getContext().getString(R.string.vox_compatibility_unsupported_warning) + "\n\n" + risk.getMessageRu();

            List<OptionItem> options = new ArrayList<>();
            options.add(UiOptionItem.from(getContext().getString(R.string.vox_compatibility_risk_continue), opt -> {
                com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger.warn(
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory.COMPATIBILITY,
                        com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode.AUTO_TUNING_MANUAL_OVERRIDE,
                        "User manually accepted risky profile"
                );
                manager.setCodecPolicy(proposedPolicy);
                presenter.closeDialog();
                show();
            }));

            options.add(UiOptionItem.from(getContext().getString(R.string.vox_compatibility_risk_revert), opt -> {
                manager.applyRecommendedSettings(recommended);
                presenter.closeDialog();
                show();
            }));

            presenter.appendStringsCategory(message, options);
            presenter.showDialog(title);
        } else {
            manager.setCodecPolicy(proposedPolicy);
        }
    }

    private void appendActionButtons(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());

        settingsPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.settings_diagnostics_and_logs), option -> {
                    VoxDiagnosticsPresenter.instance(getContext()).show();
                })
        );

        settingsPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.vox_compatibility_reset), option -> {
                    manager.resetToDefaults();
                    MessageHelpers.showMessage(getContext(), R.string.vox_compatibility_reset_done);
                    show();
                })
        );
    }
}

package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.TriStateCapability;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxAudioCodecPreference;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCodecPolicy;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCodecPolicyMode;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager;
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxDeviceProfile;
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

        appendPolicyModeCategory(settingsPresenter);
        appendMaxQualityCategory(settingsPresenter);
        appendVideoCodecCategory(settingsPresenter);
        appendAudioCodecCategory(settingsPresenter);
        appendPassthroughCategory(settingsPresenter);
        appendActionButtons(settingsPresenter);

        settingsPresenter.showDialog(getContext().getString(R.string.settings_device_compatibility));
    }

    private void appendPolicyModeCategory(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());
        VoxCodecPolicy policy = manager.getCodecPolicy();

        List<OptionItem> options = new ArrayList<>();
        for (VoxCodecPolicyMode mode : VoxCodecPolicyMode.values()) {
            options.add(UiOptionItem.from(
                    mode.getTitleRu(),
                    option -> {
                        manager.setCodecPolicy(new VoxCodecPolicy(
                                mode,
                                policy.getMaxQualityHeight(),
                                policy.getPreferredVideoCodec(),
                                policy.getPreferredAudioCodec(),
                                policy.getPassthroughEnabled()
                        ));
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
                        manager.setCodecPolicy(new VoxCodecPolicy(
                                policy.getMode(),
                                h,
                                policy.getPreferredVideoCodec(),
                                policy.getPreferredAudioCodec(),
                                policy.getPassthroughEnabled()
                        ));
                    },
                    policy.getMaxQualityHeight() == h
            ));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.vox_compatibility_max_quality), options);
    }

    private void appendVideoCodecCategory(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());
        VoxCodecPolicy policy = manager.getCodecPolicy();
        VoxDeviceProfile profile = manager.getDeviceProfile(false);

        List<OptionItem> options = new ArrayList<>();
        for (VoxVideoCodecPreference pref : VoxVideoCodecPreference.values()) {
            options.add(UiOptionItem.from(
                    pref.getDisplayName(),
                    option -> {
                        if (pref != VoxVideoCodecPreference.AUTO && !profile.isVideoCodecSupported(pref.getId())) {
                            MessageHelpers.showMessage(getContext(), R.string.vox_compatibility_unsupported_warning);
                        }
                        manager.setCodecPolicy(new VoxCodecPolicy(
                                policy.getMode(),
                                policy.getMaxQualityHeight(),
                                pref,
                                policy.getPreferredAudioCodec(),
                                policy.getPassthroughEnabled()
                        ));
                    },
                    policy.getPreferredVideoCodec() == pref
            ));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.vox_compatibility_preferred_video), options);
    }

    private void appendAudioCodecCategory(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());
        VoxCodecPolicy policy = manager.getCodecPolicy();
        VoxDeviceProfile profile = manager.getDeviceProfile(false);

        List<OptionItem> options = new ArrayList<>();
        for (VoxAudioCodecPreference pref : VoxAudioCodecPreference.values()) {
            options.add(UiOptionItem.from(
                    pref.getDisplayName(),
                    option -> {
                        if (pref != VoxAudioCodecPreference.AUTO) {
                            boolean dec = profile.isAudioDecodeSupported(pref.getId());
                            boolean pt = profile.isAudioPassthroughSupported(pref.getId());
                            if (!dec && !pt) {
                                MessageHelpers.showMessage(getContext(), R.string.vox_compatibility_unsupported_warning);
                            }
                        }
                        manager.setCodecPolicy(new VoxCodecPolicy(
                                policy.getMode(),
                                policy.getMaxQualityHeight(),
                                policy.getPreferredVideoCodec(),
                                pref,
                                policy.getPassthroughEnabled()
                        ));
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
                    manager.setCodecPolicy(new VoxCodecPolicy(
                            policy.getMode(),
                            policy.getMaxQualityHeight(),
                            policy.getPreferredVideoCodec(),
                            policy.getPreferredAudioCodec(),
                            option.isSelected()
                    ));
                },
                policy.getPassthroughEnabled()
        ));

        settingsPresenter.appendCheckedCategory(getContext().getString(R.string.vox_compatibility_passthrough), options);
    }

    private void appendActionButtons(AppDialogPresenter settingsPresenter) {
        VoxCompatibilityManager manager = VoxCompatibilityManager.instance(getContext());

        settingsPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.vox_compatibility_rescan), option -> {
                    VoxDeviceProfile fresh = manager.getDeviceProfile(true);
                    manager.setScanCompleted(true);
                    VoxDiagnosticsPresenter.instance(getContext()).show();
                })
        );

        settingsPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.vox_compatibility_diagnostics), option -> {
                    VoxDiagnosticsPresenter.instance(getContext()).show();
                })
        );

        settingsPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.vox_compatibility_reset), option -> {
                    manager.resetToDefaults();
                    MessageHelpers.showMessage(getContext(), R.string.vox_compatibility_reset_done);
                })
        );
    }
}

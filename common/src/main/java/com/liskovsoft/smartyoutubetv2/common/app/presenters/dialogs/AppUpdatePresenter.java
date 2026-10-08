package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import android.annotation.SuppressLint;
import android.content.Context;
import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.appupdatechecker2.AppUpdateCheckerListener;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadingManager;
import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxOtaErrorCode;
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxOtaException;
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxOtaUpdateManager;
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxReleaseAsset;
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxReleaseInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AppUpdatePresenter extends BasePresenter<Void> implements AppUpdateCheckerListener {
    @SuppressLint("StaticFieldLeak")
    private static AppUpdatePresenter sInstance;
    private final AppUpdateChecker mUpdateChecker;
    private final AppDialogPresenter mSettingsPresenter;
    private final String[] mUpdateManifestUrls;
    private boolean mIsForceCheck;

    public AppUpdatePresenter(Context context) {
        super(context);
        mUpdateChecker = new AppUpdateChecker(context, this);
        mSettingsPresenter = AppDialogPresenter.instance(context);
        mUpdateManifestUrls = context.getResources().getStringArray(R.array.update_urls);
    }

    public static AppUpdatePresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new AppUpdatePresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public static void unhold() {
        sInstance = null;
    }

    public void start(boolean forceCheck) {
        mIsForceCheck = forceCheck;

        if (forceCheck) {
            LoadingManager.showLoading(getContext(), true);
        }

        String currentVersion = AppInfoHelpers.getAppVersionName(getContext());
        boolean isBeta = getContext() != null && getContext().getPackageName() != null && getContext().getPackageName().contains("beta");

        VoxOtaUpdateManager.instance(getContext()).checkForUpdates(
                currentVersion,
                mUpdateManifestUrls,
                isBeta,
                new VoxOtaUpdateManager.Callback() {
                    @Override
                    public void onUpdateAvailable(VoxReleaseInfo release, VoxReleaseAsset asset) {
                        if (mIsForceCheck) {
                            LoadingManager.showLoading(getContext(), false);
                            showVoxUpdateDialog(release, asset);
                        } else if (GeneralData.instance(getContext()).isOldUpdateNotificationsEnabled()) {
                            showVoxUpdateDialog(release, asset);
                        } else {
                            pinUpdateSection(release.getVersionName(), release.getChangelog(), asset.getDownloadUrl());
                        }
                    }

                    @Override
                    public void onNoUpdateAvailable(String currentVersion) {
                        if (mIsForceCheck) {
                            LoadingManager.showLoading(getContext(), false);
                            MessageHelpers.showMessage(getContext(), R.string.update_not_found);
                        }
                        onFinish();
                    }

                    @Override
                    public void onDownloadProgress(long bytesRead, long totalBytes, int percent) {
                    }

                    @Override
                    public void onDownloadCompleted(File apkFile) {
                        LoadingManager.showLoading(getContext(), false);
                        VoxOtaUpdateManager.instance(getContext()).installUpdate(apkFile);
                    }

                    @Override
                    public void onError(VoxOtaException error) {
                        if (mIsForceCheck) {
                            LoadingManager.showLoading(getContext(), false);
                            MessageHelpers.showMessage(getContext(), error.getUserMessageRu());
                        }
                        onFinish();
                    }
                }
        );
    }

    @Override
    public void onUpdateFound(String versionName, List<String> changelog, String apkPath) {
        if (mIsForceCheck) {
            LoadingManager.showLoading(getContext(), false);
            showUpdateDialog(versionName, changelog, apkPath);
        } else if (GeneralData.instance(getContext()).isOldUpdateNotificationsEnabled()) {
            showUpdateDialog(versionName, changelog, apkPath);
        } else {
            pinUpdateSection(versionName, changelog, apkPath);
        }
    }

    @Override
    public void onUpdateError(Exception error) {
        if (mIsForceCheck) {
            LoadingManager.showLoading(getContext(), false);

            if (AppUpdateCheckerListener.LATEST_VERSION.equals(error.getMessage())) {
                MessageHelpers.showMessage(getContext(), R.string.update_not_found);
            } else {
                // Никогда не показываем сырой Java exception пользователю
                MessageHelpers.showMessage(getContext(), R.string.update_error);
            }
        }

        onFinish();
    }

    private void showVoxUpdateDialog(VoxReleaseInfo release, VoxReleaseAsset asset) {
        if (getContext() == null || getViewManager().isPlayerInForeground() || !Utils.isAppInForegroundFixed()) {
            return;
        }

        mSettingsPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.install_update), optionItem -> {
                    GeneralData.instance(getContext()).setChangelog(release.getChangelog());
                    LoadingManager.showLoading(getContext(), true);
                    VoxOtaUpdateManager.instance(getContext()).downloadAndVerify(
                            asset,
                            release,
                            new VoxOtaUpdateManager.Callback() {
                                @Override
                                public void onUpdateAvailable(VoxReleaseInfo r, VoxReleaseAsset a) {}

                                @Override
                                public void onNoUpdateAvailable(String v) {}

                                @Override
                                public void onDownloadProgress(long bytesRead, long totalBytes, int percent) {}

                                @Override
                                public void onDownloadCompleted(File apkFile) {
                                    LoadingManager.showLoading(getContext(), false);
                                    VoxOtaUpdateManager.instance(getContext()).installUpdate(apkFile);
                                }

                                @Override
                                public void onError(VoxOtaException error) {
                                    LoadingManager.showLoading(getContext(), false);
                                    MessageHelpers.showMessage(getContext(), error.getUserMessageRu());
                                }
                            }
                    );
                }, false));
        mSettingsPresenter.appendStringsCategory(getContext().getString(R.string.update_changelog), createChangelogOptions(release.getChangelog()));
        mSettingsPresenter.showDialog(String.format("%s %s", getContext().getString(R.string.app_name), release.getVersionName()), AppUpdatePresenter::unhold);
    }

    private void showUpdateDialog(String versionName, List<String> changelog, String apkPath) {
        // Don't show update dialog if the player opened or the app is collapsed
        if (getContext() == null || getViewManager().isPlayerInForeground() || !Utils.isAppInForegroundFixed()) {
            return;
        }

        mSettingsPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.install_update), optionItem -> {
                    GeneralData.instance(getContext()).setChangelog(changelog);
                    mUpdateChecker.installUpdate();
                }, false));
        mSettingsPresenter.appendStringsCategory(getContext().getString(R.string.update_changelog), createChangelogOptions(changelog));
        mSettingsPresenter.showDialog(String.format("%s %s", getContext().getString(R.string.app_name), versionName), AppUpdatePresenter::unhold);
    }

    private void pinUpdateSection(String versionName, List<String> changelog, String apkPath) {
        // Don't show update dialog if the player opened or the app is collapsed
        if (getContext() == null) {
            return;
        }

        BrowsePresenter.instance(getContext()).pinItem(getContext().getString(R.string.update_found), R.drawable.action_info, new ErrorFragmentData() {
            @Override
            public void onAction() {
                GeneralData.instance(getContext()).setChangelog(changelog);
                mUpdateChecker.installUpdate();
            }

            @Override
            public String getMessage() {
                return String.format("%s %s", getContext().getString(R.string.app_name), versionName) + " " +
                        getContext().getString(R.string.update_changelog) + ":\n" +
                        createChangelog(changelog);
            }

            @Override
            public String getActionText() {
                return getContext().getString(R.string.install_update);
            }
        });
    }

    private List<OptionItem> createChangelogOptions(List<String> changelog) {
        List<OptionItem> options = new ArrayList<>();

        for (String change : changelog) {
            options.add(UiOptionItem.from(change));
        }

        return options;
    }

    private String createChangelog(List<String> changelog) {
        StringBuilder builder = new StringBuilder();

        int maxLines = 30;
        int lineNum = 0;

        for (String change : changelog) {
            if (lineNum > maxLines) {
                break;
            }

            builder.append("- ");
            builder.append(change);
            builder.append("\n");

            lineNum++;
        }

        return builder.toString();
    }
}

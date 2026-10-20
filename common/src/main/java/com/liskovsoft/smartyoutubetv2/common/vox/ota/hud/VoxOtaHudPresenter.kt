package com.liskovsoft.smartyoutubetv2.common.vox.ota.hud

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import com.liskovsoft.sharedutils.helpers.AppInfoHelpers
import com.liskovsoft.sharedutils.helpers.MessageHelpers
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
import com.liskovsoft.smartyoutubetv2.common.utils.Utils
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxOtaException
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxOtaProgressEstimator
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxOtaUpdateManager
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxReleaseAsset
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxReleaseInfo
import java.io.File

/**
 * Singleton presenter coordinating the complete OTA HUD experience:
 * - Update Available Offers (Stable / Beta / Dev)
 * - Download Confirmation
 * - Full Progress Modal & Secondary Stages
 * - Bottom-Right Mini-HUD & Restoration
 * - Download Complete & Installation Step-Flow
 */
object VoxOtaHudPresenter {

    private const val TAG = "VoxOtaHudPresenter"
    private const val UI_THROTTLE_MS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeDialog: VoxOtaHudDialog? = null
    private var miniHudOverlay: VoxOtaMiniHudOverlay? = null

    var currentState: VoxOtaHudState = VoxOtaHudState.Idle
        private set

    private var progressEstimator: VoxOtaProgressEstimator? = null
    private var lastProgressUpdateTime = 0L

    @JvmStatic
    fun findActivity(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity && Utils.checkActivity(ctx)) return ctx
            ctx = ctx.baseContext
        }
        if (context != null) {
            try {
                val playbackAct = PlaybackPresenter.instance(context).activity
                if (playbackAct != null && Utils.checkActivity(playbackAct)) {
                    return playbackAct
                }
            } catch (_: Throwable) {}
            try {
                val browseCtx = BrowsePresenter.instance(context).context
                if (browseCtx is Activity && Utils.checkActivity(browseCtx)) {
                    return browseCtx
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun getOrCreateDialog(activity: Activity): VoxOtaHudDialog {
        val existing = activeDialog
        if (existing != null && !activity.isFinishing && !activity.isDestroyed) {
            return existing
        }
        existing?.dismiss()
        val created = VoxOtaHudDialog(activity)
        activeDialog = created
        return created
    }

    private fun getOrCreateMiniHud(context: Context): VoxOtaMiniHudOverlay {
        var mini = miniHudOverlay
        if (mini == null) {
            mini = VoxOtaMiniHudOverlay(context.applicationContext)
            miniHudOverlay = mini
        }
        return mini
    }

    // ==========================================
    // 1. UPDATE AVAILABLE (STABLE / BETA / DEV)
    // ==========================================

    @JvmStatic
    fun showUpdateAvailable(context: Context, release: VoxReleaseInfo, asset: VoxReleaseAsset) {
        val activity = findActivity(context)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "Cannot show update offer: Activity not available")
            return
        }

        val tier = VoxOtaDisplayHelper.detectTier(release.versionName, release)
        val displayVer = VoxOtaDisplayHelper.formatDisplayVersion(release.versionName, tier)
        val currentVerStr = AppInfoHelpers.getAppVersionName(context)
        val currentVerText = VoxOtaDisplayHelper.formatCurrentVersion(currentVerStr)
        val bullets = if (release.changelog.isNotEmpty() && release.changelog.any { it.length > 5 }) {
            release.changelog.take(3).map { if (it.startsWith("•") || it.startsWith("-")) it else "• $it" }
        } else {
            VoxOtaDisplayHelper.getDefaultBullets(tier)
        }

        val offerState = VoxOtaHudState.Offer(
            release = release,
            asset = asset,
            tier = tier,
            displayVersion = displayVer,
            currentVersionText = currentVerText,
            changelogBullets = bullets
        )
        currentState = offerState

        mainHandler.post {
            val dialog = getOrCreateDialog(activity)
            dialog.showOffer(
                state = offerState,
                onUpdate = {
                    showConfirmDownload(activity, release, asset)
                },
                onLater = {
                    dismissAll()
                }
            )
        }
    }

    // ==========================================
    // 2. CONFIRM DOWNLOAD MODAL
    // ==========================================

    @JvmStatic
    fun showConfirmDownload(context: Context, release: VoxReleaseInfo, asset: VoxReleaseAsset) {
        val activity = findActivity(context) ?: return
        val tier = VoxOtaDisplayHelper.detectTier(release.versionName, release)
        val displayVer = VoxOtaDisplayHelper.formatDisplayVersion(release.versionName, tier)
        val sizeText = "Размер файла: ${VoxOtaDisplayHelper.formatFileSize(asset.sizeBytes)}"

        val confirmState = VoxOtaHudState.Confirm(
            release = release,
            asset = asset,
            displayVersion = displayVer,
            fileSizeText = sizeText
        )
        currentState = confirmState

        mainHandler.post {
            val dialog = getOrCreateDialog(activity)
            dialog.showConfirm(
                state = confirmState,
                onDownload = {
                    startDownload(activity, release, asset)
                },
                onCancel = {
                    dismissAll()
                }
            )
        }
    }

    // ==========================================
    // 3. START DOWNLOAD & FULL PROGRESS HUD
    // ==========================================

    @JvmStatic
    fun startDownload(context: Context, release: VoxReleaseInfo, asset: VoxReleaseAsset) {
        val activity = findActivity(context) ?: return
        val tier = VoxOtaDisplayHelper.detectTier(release.versionName, release)
        val displayVer = VoxOtaDisplayHelper.formatDisplayVersion(release.versionName, tier)

        val progressState = VoxOtaHudState.Progress(
            release = release,
            asset = asset,
            displayVersion = displayVer,
            phase = VoxOtaProgressPhase.DOWNLOADING,
            percent = 0,
            bytesRead = 0L,
            totalBytes = if (asset.sizeBytes > 0L) asset.sizeBytes else 368L * 1024 * 1024,
            speedBytesPerSec = 0.0,
            isMinimized = false
        )
        currentState = progressState
        progressEstimator = VoxOtaProgressEstimator(alpha = 0.25f, minAccumulationMs = 1000L)
        lastProgressUpdateTime = 0L

        mainHandler.post {
            val dialog = getOrCreateDialog(activity)
            dialog.showProgressFull(
                state = progressState,
                onMinimize = {
                    minimizeToMiniHud(activity)
                },
                onCancel = {
                    cancelDownload(activity)
                }
            )
        }

        VoxOtaUpdateManager.instance(context).downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(r: VoxReleaseInfo, a: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {}

            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {
                val now = System.currentTimeMillis()
                val effectiveTotal = if (totalBytes > 0L) totalBytes else asset.sizeBytes
                val estimate = progressEstimator?.update(bytesRead, effectiveTotal, now)

                val speed = estimate?.speedBytesPerSec ?: 0.0
                val calculatedPct = estimate?.percent ?: percent

                val updated = progressState.copy(
                    percent = calculatedPct,
                    bytesRead = bytesRead,
                    totalBytes = effectiveTotal,
                    speedBytesPerSec = speed
                )
                currentState = updated

                if (now - lastProgressUpdateTime >= UI_THROTTLE_MS || calculatedPct >= 99) {
                    lastProgressUpdateTime = now
                    mainHandler.post {
                        if (updated.isMinimized) {
                            miniHudOverlay?.updateProgress(updated)
                        } else {
                            activeDialog?.updateProgress(updated)
                        }
                    }
                }
            }

            override fun onDownloadCompleted(apkFile: File) {
                // Secondary Stage: Verifying
                val verifyingState = progressState.copy(
                    phase = VoxOtaProgressPhase.VERIFYING,
                    percent = 75
                )
                currentState = verifyingState
                mainHandler.post {
                    if (verifyingState.isMinimized) {
                        miniHudOverlay?.updateProgress(verifyingState)
                    } else {
                        activeDialog?.updateProgress(verifyingState)
                    }

                    // Secondary Stage: Preparing
                    mainHandler.postDelayed({
                        val preparingState = progressState.copy(
                            phase = VoxOtaProgressPhase.PREPARING,
                            percent = 90
                        )
                        currentState = preparingState
                        if (preparingState.isMinimized) {
                            miniHudOverlay?.updateProgress(preparingState)
                        } else {
                            activeDialog?.updateProgress(preparingState)
                        }

                        // Complete Stage
                        mainHandler.postDelayed({
                            showDownloadComplete(activity, release, asset, apkFile)
                        }, 400L)
                    }, 400L)
                }
            }

            override fun onError(error: VoxOtaException) {
                mainHandler.post {
                    dismissAll()
                    MessageHelpers.showMessage(context, error.userMessageRu)
                }
            }
        })
    }

    // ==========================================
    // 4. MINIMIZE TO BOTTOM-RIGHT MINI-HUD
    // ==========================================

    @JvmStatic
    fun minimizeToMiniHud(context: Context) {
        val activity = findActivity(context) ?: return
        val current = currentState
        if (current !is VoxOtaHudState.Progress) return

        val minimized = current.copy(isMinimized = true)
        currentState = minimized

        mainHandler.post {
            activeDialog?.dismiss()
            val mini = getOrCreateMiniHud(context)
            mini.show(
                activity = activity,
                progress = minimized,
                onExpand = {
                    restoreFromMiniHud(activity)
                },
                onCancel = {
                    cancelDownload(activity)
                }
            )
        }
    }

    // ==========================================
    // 5. RESTORE FROM MINI-HUD
    // ==========================================

    @JvmStatic
    fun restoreFromMiniHud(context: Context) {
        val activity = findActivity(context) ?: return
        val current = currentState
        if (current !is VoxOtaHudState.Progress) return

        val restored = current.copy(isMinimized = false)
        currentState = restored

        mainHandler.post {
            miniHudOverlay?.hide {
                val dialog = getOrCreateDialog(activity)
                dialog.showProgressFull(
                    state = restored,
                    onMinimize = {
                        minimizeToMiniHud(activity)
                    },
                    onCancel = {
                        cancelDownload(activity)
                    }
                )
            }
        }
    }

    // ==========================================
    // 6. CANCEL DOWNLOAD
    // ==========================================

    @JvmStatic
    fun cancelDownload(context: Context) {
        VoxOtaUpdateManager.instance(context).cancelDownload()
        dismissAll()
    }

    // ==========================================
    // 7. DOWNLOAD COMPLETE MODAL
    // ==========================================

    @JvmStatic
    fun showDownloadComplete(context: Context, release: VoxReleaseInfo, asset: VoxReleaseAsset, apkFile: File) {
        val activity = findActivity(context) ?: return
        val tier = VoxOtaDisplayHelper.detectTier(release.versionName, release)
        val displayVer = VoxOtaDisplayHelper.formatDisplayVersion(release.versionName, tier)

        val completeState = VoxOtaHudState.Complete(
            release = release,
            asset = asset,
            displayVersion = displayVer,
            apkFile = apkFile
        )
        currentState = completeState

        mainHandler.post {
            miniHudOverlay?.destroy()
            val dialog = getOrCreateDialog(activity)
            dialog.showComplete(
                state = completeState,
                onInstall = {
                    startInstallationFlow(activity, release, asset, apkFile)
                },
                onLater = {
                    dismissAll()
                }
            )
        }
    }

    // ==========================================
    // 8. INSTALLATION PROCESS MODAL
    // ==========================================

    @JvmStatic
    fun startInstallationFlow(context: Context, release: VoxReleaseInfo, asset: VoxReleaseAsset, apkFile: File) {
        val activity = findActivity(context) ?: return
        val tier = VoxOtaDisplayHelper.detectTier(release.versionName, release)
        val displayVer = VoxOtaDisplayHelper.formatDisplayVersion(release.versionName, tier)

        val step1 = VoxOtaHudState.Installing(
            release = release,
            asset = asset,
            displayVersion = displayVer,
            apkFile = apkFile,
            percent = 35,
            step = VoxOtaInstallStep.PREPARING
        )
        currentState = step1

        mainHandler.post {
            val dialog = getOrCreateDialog(activity)
            dialog.showInstalling(
                state = step1,
                onClose = {
                    dismissAll()
                }
            )

            // Progressive step transitions
            mainHandler.postDelayed({
                val step2 = step1.copy(percent = 65, step = VoxOtaInstallStep.INSTALLING)
                currentState = step2
                dialog.showInstalling(step2) { dismissAll() }

                mainHandler.postDelayed({
                    val step3 = step1.copy(percent = 95, step = VoxOtaInstallStep.FINALIZING)
                    currentState = step3
                    dialog.showInstalling(step3) { dismissAll() }

                    mainHandler.postDelayed({
                        dismissAll()
                        VoxOtaUpdateManager.instance(context).installUpdate(apkFile)
                    }, 400L)
                }, 400L)
            }, 400L)
        }
    }

    // ==========================================
    // 9. TEST DRIVER (FOR EMULATOR VERIFICATION)
    // ==========================================

    @JvmStatic
    fun showTestState(context: Context, stateName: String) {
        val activity = findActivity(context)
        if (activity == null) {
            Log.w(TAG, "Cannot show test state '$stateName': No active Activity found")
            return
        }
        Log.i(TAG, "Showing test state '$stateName' on activity $activity")
        val dummyRelease = VoxReleaseInfo(
            tagName = "v32.56-vox.9.1",
            versionName = "32.56-vox.9.1",
            changelog = listOf("• Улучшения стабильности", "• Исправления ошибок", "• Новые функции"),
            assets = listOf(VoxReleaseAsset("SmartTube_vot_universal.apk", "http://10.0.2.2/univ.apk", 368L * 1024 * 1024)),
            sha256SumsUrl = null,
            isPrerelease = false,
            versionCode = 2446016L
        )
        val dummyAsset = dummyRelease.assets[0]
        val dummyApk = File(context.cacheDir, "test_update.apk")

        when (stateName.lowercase()) {
            "offer_stable" -> {
                showUpdateAvailable(activity, dummyRelease, dummyAsset)
            }
            "offer_beta" -> {
                val betaRel = dummyRelease.copy(
                    tagName = "v32.56-vox.9.2-beta.1",
                    versionName = "32.56-vox.9.2-beta.1",
                    isPrerelease = true,
                    changelog = listOf("• Новые функции (Beta)", "• Улучшения перевода", "• Исправления ошибок")
                )
                showUpdateAvailable(activity, betaRel, dummyAsset)
            }
            "offer_dev" -> {
                val devRel = dummyRelease.copy(
                    tagName = "v32.56-vox.9.3-dev",
                    versionName = "32.56-vox.9.3-dev",
                    isPrerelease = true,
                    changelog = listOf("• Экспериментальные функции", "• Улучшения производительности", "• Исправления ошибок")
                )
                showUpdateAvailable(activity, devRel, dummyAsset)
            }
            "confirm" -> {
                showConfirmDownload(activity, dummyRelease, dummyAsset)
            }
            "progress_full" -> {
                val prog = VoxOtaHudState.Progress(
                    release = dummyRelease,
                    asset = dummyAsset,
                    displayVersion = "SmartTube Vox - 9.1",
                    phase = VoxOtaProgressPhase.DOWNLOADING,
                    percent = 42,
                    bytesRead = 156L * 1024 * 1024,
                    totalBytes = 368L * 1024 * 1024,
                    speedBytesPerSec = 6.8 * 1024 * 1024,
                    isMinimized = false
                )
                currentState = prog
                mainHandler.post {
                    val dialog = getOrCreateDialog(activity)
                    dialog.showProgressFull(prog, { minimizeToMiniHud(activity) }, { dismissAll() })
                }
            }
            "progress_verify" -> {
                val prog = VoxOtaHudState.Progress(
                    release = dummyRelease,
                    asset = dummyAsset,
                    displayVersion = "SmartTube Vox - 9.1",
                    phase = VoxOtaProgressPhase.VERIFYING,
                    percent = 75,
                    bytesRead = 276L * 1024 * 1024,
                    totalBytes = 368L * 1024 * 1024,
                    speedBytesPerSec = 0.0,
                    isMinimized = false
                )
                currentState = prog
                mainHandler.post {
                    val dialog = getOrCreateDialog(activity)
                    dialog.showProgressFull(prog, { minimizeToMiniHud(activity) }, { dismissAll() })
                }
            }
            "progress_prepare" -> {
                val prog = VoxOtaHudState.Progress(
                    release = dummyRelease,
                    asset = dummyAsset,
                    displayVersion = "SmartTube Vox - 9.1",
                    phase = VoxOtaProgressPhase.PREPARING,
                    percent = 90,
                    bytesRead = 331L * 1024 * 1024,
                    totalBytes = 368L * 1024 * 1024,
                    speedBytesPerSec = 0.0,
                    isMinimized = false
                )
                currentState = prog
                mainHandler.post {
                    val dialog = getOrCreateDialog(activity)
                    dialog.showProgressFull(prog, { minimizeToMiniHud(activity) }, { dismissAll() })
                }
            }
            "minihud" -> {
                val prog = VoxOtaHudState.Progress(
                    release = dummyRelease,
                    asset = dummyAsset,
                    displayVersion = "SmartTube Vox - 9.1",
                    phase = VoxOtaProgressPhase.DOWNLOADING,
                    percent = 42,
                    bytesRead = 156L * 1024 * 1024,
                    totalBytes = 368L * 1024 * 1024,
                    speedBytesPerSec = 6.8 * 1024 * 1024,
                    isMinimized = true
                )
                currentState = prog
                mainHandler.post {
                    activeDialog?.dismiss()
                    val mini = getOrCreateMiniHud(context)
                    mini.show(activity, prog, { restoreFromMiniHud(activity) }, { dismissAll() })
                }
            }
            "restored" -> {
                val prog = VoxOtaHudState.Progress(
                    release = dummyRelease,
                    asset = dummyAsset,
                    displayVersion = "SmartTube Vox - 9.1",
                    phase = VoxOtaProgressPhase.DOWNLOADING,
                    percent = 67,
                    bytesRead = 246L * 1024 * 1024,
                    totalBytes = 368L * 1024 * 1024,
                    speedBytesPerSec = 7.3 * 1024 * 1024,
                    isMinimized = false
                )
                currentState = prog
                mainHandler.post {
                    miniHudOverlay?.destroy()
                    val dialog = getOrCreateDialog(activity)
                    dialog.showProgressFull(prog, { minimizeToMiniHud(activity) }, { dismissAll() })
                }
            }
            "complete" -> {
                showDownloadComplete(activity, dummyRelease, dummyAsset, dummyApk)
            }
            "installing" -> {
                val installingState = VoxOtaHudState.Installing(
                    release = dummyRelease,
                    asset = dummyAsset,
                    displayVersion = "SmartTube Vox - 9.1",
                    apkFile = dummyApk,
                    percent = 35,
                    step = VoxOtaInstallStep.PREPARING
                )
                currentState = installingState
                mainHandler.post {
                    val dialog = getOrCreateDialog(activity)
                    dialog.showInstalling(installingState) { dismissAll() }
                }
            }
            else -> {
                Log.w(TAG, "Unknown test state: $stateName")
            }
        }
    }

    @JvmStatic
    fun dismissAll() {
        currentState = VoxOtaHudState.Idle
        progressEstimator = null
        mainHandler.post {
            activeDialog?.dismiss()
            activeDialog = null
            miniHudOverlay?.destroy()
            miniHudOverlay = null
        }
    }
}

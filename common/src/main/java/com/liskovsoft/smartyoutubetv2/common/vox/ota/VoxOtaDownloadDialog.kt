package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Компактный TV-диалог загрузки OTA-обновления SmartTube VOX.
 *
 * Требования:
 * - Фокус по умолчанию на кнопке [ Отмена ];
 * - Реальный progress bar (0-100%) или indeterminate при неизвестном Content-Length;
 * - Счетчик байт (КБ / МБ / ГБ);
 * - Сглаженная скорость (EMA);
 * - Расчёт ETA («Осталось примерно: 15 сек» / «Осталось примерно: 2 мин»);
 * - Троттлинг обновлений UI (не чаще 250-500 мс);
 * - Корректная отмена без крашей и утечек потоков.
 */
object VoxOtaDownloadDialog {

    private const val TAG = "VoxOtaDownloadDialog"
    private const val UI_THROTTLE_MS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun findActivity(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    @JvmStatic
    fun show(
        context: Context,
        release: VoxReleaseInfo,
        asset: VoxReleaseAsset,
        onComplete: (File) -> Unit,
        onError: (VoxOtaException) -> Unit
    ): Dialog? {
        val activity = findActivity(context)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "Cannot show dialog: activity is not available, falling back to silent download")
            VoxOtaUpdateManager.instance(context).downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
                override fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset) {}
                override fun onNoUpdateAvailable(currentVersion: String) {}
                override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {}
                override fun onDownloadCompleted(apkFile: File) { onComplete(apkFile) }
                override fun onError(error: VoxOtaException) { onError(error) }
            })
            return null
        }

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.vox_ota_download_dialog)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)

        val titleView: TextView = dialog.findViewById(R.id.vox_ota_title)
        val subtitleView: TextView = dialog.findViewById(R.id.vox_ota_subtitle)
        val progressBar: ProgressBar = dialog.findViewById(R.id.vox_ota_progressbar)
        val percentText: TextView = dialog.findViewById(R.id.vox_ota_percent_text)
        val bytesText: TextView = dialog.findViewById(R.id.vox_ota_bytes_text)
        val speedText: TextView = dialog.findViewById(R.id.vox_ota_speed_text)
        val etaText: TextView = dialog.findViewById(R.id.vox_ota_eta_text)
        val cancelBtn: Button = dialog.findViewById(R.id.vox_ota_cancel_btn)

        val displayTitle = if (release.versionName.startsWith("SmartTube", ignoreCase = true)) {
            release.versionName
        } else {
            "SmartTube ${release.versionName}"
        }
        titleView.text = displayTitle
        subtitleView.text = "Скачивание обновления"

        if (asset.sizeBytes > 0L) {
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            percentText.visibility = View.VISIBLE
            percentText.text = "0%"
            bytesText.text = "0 Б / ${VoxOtaProgressEstimator.formatBytes(asset.sizeBytes)}"
        } else {
            progressBar.isIndeterminate = true
            percentText.visibility = View.GONE
            bytesText.text = "Скачано: 0 Б"
        }
        speedText.text = "Скорость: 0 КБ/с"
        etaText.text = "Осталось: вычисляется…"

        val isDismissed = AtomicBoolean(false)
        val estimator = VoxOtaProgressEstimator(alpha = 0.25f, minAccumulationMs = 1000L)
        var lastUiUpdateTime = 0L

        fun doCancel() {
            if (isDismissed.compareAndSet(false, true)) {
                VoxOtaUpdateManager.instance(context).cancelDownload()
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    Log.w(TAG, "Error dismissing dialog on cancel: ${e.message}")
                }
            }
        }

        cancelBtn.setOnClickListener { doCancel() }
        dialog.setOnCancelListener { doCancel() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                doCancel()
                true
            } else {
                false
            }
        }

        cancelBtn.requestFocus()

        VoxOtaUpdateManager.instance(context).downloadAndVerify(asset, release, object : VoxOtaUpdateManager.Callback {
            override fun onUpdateAvailable(release: VoxReleaseInfo, asset: VoxReleaseAsset) {}
            override fun onNoUpdateAvailable(currentVersion: String) {}

            override fun onDownloadProgress(bytesRead: Long, totalBytes: Long, percent: Int) {
                if (isDismissed.get()) return
                val now = System.currentTimeMillis()
                val effectiveTotal = if (totalBytes > 0L) totalBytes else if (asset.sizeBytes > 0L) asset.sizeBytes else null
                val progress = estimator.update(bytesRead, effectiveTotal, now)

                if (now - lastUiUpdateTime >= UI_THROTTLE_MS || (percent in 99..100)) {
                    lastUiUpdateTime = now
                    mainHandler.post {
                        if (isDismissed.get()) return@post
                        if (progress.percent != null && progress.percent >= 0) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = progress.percent
                            percentText.visibility = View.VISIBLE
                            percentText.text = "${progress.percent}%"
                        } else {
                            progressBar.isIndeterminate = true
                            percentText.visibility = View.GONE
                        }
                        bytesText.text = VoxOtaProgressEstimator.formatBytesProgress(progress)
                        speedText.text = VoxOtaProgressEstimator.formatSpeed(progress.speedBytesPerSec)
                        etaText.text = VoxOtaProgressEstimator.formatEta(progress.etaSec)
                    }
                }
            }

            override fun onDownloadCompleted(apkFile: File) {
                if (isDismissed.compareAndSet(false, true)) {
                    mainHandler.post {
                        subtitleView.text = "Проверка файла обновления…"
                        progressBar.isIndeterminate = true
                        percentText.visibility = View.GONE
                        speedText.visibility = View.GONE
                        etaText.visibility = View.GONE
                        cancelBtn.isEnabled = false

                        mainHandler.postDelayed({
                            try {
                                dialog.dismiss()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error dismissing dialog on complete: ${e.message}")
                            }
                            onComplete(apkFile)
                        }, 300L)
                    }
                }
            }

            override fun onError(error: VoxOtaException) {
                if (isDismissed.compareAndSet(false, true)) {
                    mainHandler.post {
                        try {
                            dialog.dismiss()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error dismissing dialog on error: ${e.message}")
                        }
                        onError(error)
                    }
                }
            }
        })

        try {
            dialog.show()
            cancelBtn.requestFocus()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show download dialog: ${e.message}", e)
        }

        return dialog
    }
}

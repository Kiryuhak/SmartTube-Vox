package com.liskovsoft.smartyoutubetv2.common.vox.ota.hud

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.R

/**
 * TV Modal Dialog managing the presentation of the OTA HUD modal states.
 */
class VoxOtaHudDialog(private val activity: Activity) {

    private var dialog: Dialog? = null
    private var currentLayoutId: Int = 0

    // Full progress view holders
    private var progressIconView: ImageView? = null
    private var progressTitleView: TextView? = null
    private var progressVersionView: TextView? = null
    private var progressNoteView: TextView? = null
    private var progressBarView: ProgressBar? = null
    private var percentTextView: TextView? = null
    private var metricsContainer: LinearLayout? = null
    private var speedValueView: TextView? = null
    private var sizeValueView: TextView? = null

    val isShowing: Boolean
        get() = dialog?.isShowing == true

    fun dismiss() {
        try {
            dialog?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing dialog: ${e.message}")
        }
        dialog = null
        currentLayoutId = 0
        clearProgressHolders()
    }

    private fun ensureDialog(layoutId: Int): View? {
        if (activity.isFinishing || activity.isDestroyed) return null

        if (dialog == null || !dialog!!.isShowing) {
            dialog = Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window?.setDimAmount(0.70f)
                setCancelable(true)
                setCanceledOnTouchOutside(false)
            }
        }

        if (currentLayoutId != layoutId) {
            clearProgressHolders()
            dialog?.setContentView(layoutId)
            currentLayoutId = layoutId
        }

        try {
            if (!dialog!!.isShowing) {
                dialog!!.show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show dialog: ${e.message}", e)
            return null
        }

        return dialog?.window?.decorView
    }

    fun showOffer(
        state: VoxOtaHudState.Offer,
        onUpdate: () -> Unit,
        onLater: () -> Unit
    ) {
        val root = ensureDialog(R.layout.vox_ota_hud_offer) ?: return

        val titleView = root.findViewById<TextView>(R.id.vox_ota_offer_title)
        val versionView = root.findViewById<TextView>(R.id.vox_ota_offer_version)
        val curVersionView = root.findViewById<TextView>(R.id.vox_ota_offer_current_version)
        val bullet1 = root.findViewById<TextView>(R.id.vox_ota_bullet1)
        val bullet2 = root.findViewById<TextView>(R.id.vox_ota_bullet2)
        val bullet3 = root.findViewById<TextView>(R.id.vox_ota_bullet3)
        val btnUpdate = root.findViewById<Button>(R.id.vox_ota_btn_update)
        val btnLater = root.findViewById<Button>(R.id.vox_ota_btn_later)

        titleView?.text = "Доступно обновление"
        versionView?.text = state.displayVersion
        curVersionView?.text = state.currentVersionText

        val bullets = state.changelogBullets
        bullet1?.text = bullets.getOrNull(0) ?: "• Улучшения стабильности"
        bullet2?.text = bullets.getOrNull(1) ?: "• Исправления ошибок"
        bullet3?.text = bullets.getOrNull(2) ?: "• Новые функции"

        btnUpdate?.setOnClickListener { onUpdate() }
        btnLater?.setOnClickListener { onLater() }

        dialog?.setOnCancelListener { onLater() }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onLater()
                true
            } else {
                false
            }
        }

        btnUpdate?.requestFocus()
    }

    fun showConfirm(
        state: VoxOtaHudState.Confirm,
        onDownload: () -> Unit,
        onCancel: () -> Unit
    ) {
        val root = ensureDialog(R.layout.vox_ota_hud_confirm) ?: return

        val titleView = root.findViewById<TextView>(R.id.vox_ota_confirm_title)
        val versionView = root.findViewById<TextView>(R.id.vox_ota_confirm_version)
        val sizeView = root.findViewById<TextView>(R.id.vox_ota_confirm_size)
        val btnDownload = root.findViewById<Button>(R.id.vox_ota_btn_download)
        val btnCancel = root.findViewById<Button>(R.id.vox_ota_btn_cancel)

        titleView?.text = "Загрузить обновление?"
        versionView?.text = state.displayVersion
        sizeView?.text = state.fileSizeText

        btnDownload?.setOnClickListener { onDownload() }
        btnCancel?.setOnClickListener { onCancel() }

        dialog?.setOnCancelListener { onCancel() }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onCancel()
                true
            } else {
                false
            }
        }

        btnDownload?.requestFocus()
    }

    fun showProgressFull(
        state: VoxOtaHudState.Progress,
        onMinimize: () -> Unit,
        onCancel: () -> Unit
    ) {
        val root = ensureDialog(R.layout.vox_ota_hud_progress_full) ?: return

        progressIconView = root.findViewById(R.id.vox_ota_progress_icon)
        progressTitleView = root.findViewById(R.id.vox_ota_progress_title)
        progressVersionView = root.findViewById(R.id.vox_ota_progress_version)
        progressNoteView = root.findViewById(R.id.vox_ota_progress_note)
        progressBarView = root.findViewById(R.id.vox_ota_progressbar)
        percentTextView = root.findViewById(R.id.vox_ota_percent_text)
        metricsContainer = root.findViewById(R.id.vox_ota_metrics_container)
        speedValueView = root.findViewById(R.id.vox_ota_speed_val)
        sizeValueView = root.findViewById(R.id.vox_ota_size_val)

        val btnMinIcon = root.findViewById<ImageButton>(R.id.vox_ota_btn_minimize_icon)
        val btnCloseIcon = root.findViewById<ImageButton>(R.id.vox_ota_btn_close_icon)
        val btnMinBottom = root.findViewById<Button>(R.id.vox_ota_btn_minimize)
        val btnCancelBottom = root.findViewById<Button>(R.id.vox_ota_btn_cancel_download)

        btnMinIcon?.setOnClickListener { onMinimize() }
        btnMinBottom?.setOnClickListener { onMinimize() }
        btnCloseIcon?.setOnClickListener { onCancel() }
        btnCancelBottom?.setOnClickListener { onCancel() }

        dialog?.setOnCancelListener { onMinimize() }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onMinimize()
                true
            } else {
                false
            }
        }

        updateProgress(state)
        btnMinBottom?.requestFocus()
    }

    fun updateProgress(state: VoxOtaHudState.Progress) {
        if (currentLayoutId != R.layout.vox_ota_hud_progress_full) return

        progressVersionView?.text = state.displayVersion
        val pct = state.percent.coerceIn(0, 100)
        progressBarView?.isIndeterminate = false
        progressBarView?.progress = pct
        percentTextView?.text = "$pct%"

        when (state.phase) {
            VoxOtaProgressPhase.DOWNLOADING -> {
                progressIconView?.setImageResource(R.drawable.ic_vox_ota_arrow_down)
                progressTitleView?.text = "Загрузка обновления"
                progressNoteView?.text = "Пожалуйста, не выключайте устройство"
                metricsContainer?.visibility = View.VISIBLE
                speedValueView?.text = VoxOtaDisplayHelper.formatSpeed(state.speedBytesPerSec)
                sizeValueView?.text = VoxOtaDisplayHelper.formatProgressSize(state.bytesRead, state.totalBytes)
            }
            VoxOtaProgressPhase.VERIFYING -> {
                progressIconView?.setImageResource(R.drawable.ic_vox_ota_gear)
                progressTitleView?.text = "Проверка обновления..."
                progressNoteView?.text = "Проверяем файл..."
                metricsContainer?.visibility = View.GONE
            }
            VoxOtaProgressPhase.PREPARING -> {
                progressIconView?.setImageResource(R.drawable.ic_vox_ota_package)
                progressTitleView?.text = "Подготовка к установке..."
                progressNoteView?.text = "Распаковка файла..."
                metricsContainer?.visibility = View.GONE
            }
        }
    }

    fun showComplete(
        state: VoxOtaHudState.Complete,
        onInstall: () -> Unit,
        onLater: () -> Unit
    ) {
        val root = ensureDialog(R.layout.vox_ota_hud_complete) ?: return

        val titleView = root.findViewById<TextView>(R.id.vox_ota_complete_title)
        val versionView = root.findViewById<TextView>(R.id.vox_ota_complete_version)
        val btnClose = root.findViewById<ImageButton>(R.id.vox_ota_btn_close_complete)
        val btnInstall = root.findViewById<Button>(R.id.vox_ota_btn_install)
        val btnLater = root.findViewById<Button>(R.id.vox_ota_btn_later_complete)

        titleView?.text = "Обновление загружено"
        versionView?.text = state.displayVersion

        btnClose?.setOnClickListener { onLater() }
        btnInstall?.setOnClickListener { onInstall() }
        btnLater?.setOnClickListener { onLater() }

        dialog?.setOnCancelListener { onLater() }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onLater()
                true
            } else {
                false
            }
        }

        btnInstall?.requestFocus()
    }

    fun showInstalling(
        state: VoxOtaHudState.Installing,
        onClose: () -> Unit
    ) {
        val root = ensureDialog(R.layout.vox_ota_hud_installing) ?: return

        val titleView = root.findViewById<TextView>(R.id.vox_ota_installing_title)
        val versionView = root.findViewById<TextView>(R.id.vox_ota_installing_version)
        val progressBar = root.findViewById<ProgressBar>(R.id.vox_ota_install_progressbar)
        val percentText = root.findViewById<TextView>(R.id.vox_ota_install_percent)
        val btnClose = root.findViewById<ImageButton>(R.id.vox_ota_btn_close_installing)

        val dot1 = root.findViewById<ImageView>(R.id.vox_ota_step1_dot)
        val text1 = root.findViewById<TextView>(R.id.vox_ota_step1_text)
        val dot2 = root.findViewById<ImageView>(R.id.vox_ota_step2_dot)
        val text2 = root.findViewById<TextView>(R.id.vox_ota_step2_text)
        val dot3 = root.findViewById<ImageView>(R.id.vox_ota_step3_dot)
        val text3 = root.findViewById<TextView>(R.id.vox_ota_step3_text)

        titleView?.text = "Установка обновления"
        versionView?.text = state.displayVersion

        val pct = state.percent.coerceIn(0, 100)
        progressBar?.progress = pct
        percentText?.text = "$pct%"

        when (state.step) {
            VoxOtaInstallStep.PREPARING -> {
                dot1?.setImageResource(R.drawable.ic_vox_ota_dot_active)
                text1?.setTextColor(Color.WHITE)
                dot2?.setImageResource(R.drawable.ic_vox_ota_dot_pending)
                text2?.setTextColor(Color.parseColor("#CBD5E1"))
                dot3?.setImageResource(R.drawable.ic_vox_ota_dot_pending)
                text3?.setTextColor(Color.parseColor("#8E9DAE"))
            }
            VoxOtaInstallStep.INSTALLING -> {
                dot1?.setImageResource(R.drawable.ic_vox_ota_dot_done)
                text1?.setTextColor(Color.parseColor("#CBD5E1"))
                dot2?.setImageResource(R.drawable.ic_vox_ota_dot_active)
                text2?.setTextColor(Color.WHITE)
                dot3?.setImageResource(R.drawable.ic_vox_ota_dot_pending)
                text3?.setTextColor(Color.parseColor("#8E9DAE"))
            }
            VoxOtaInstallStep.FINALIZING -> {
                dot1?.setImageResource(R.drawable.ic_vox_ota_dot_done)
                text1?.setTextColor(Color.parseColor("#CBD5E1"))
                dot2?.setImageResource(R.drawable.ic_vox_ota_dot_done)
                text2?.setTextColor(Color.parseColor("#CBD5E1"))
                dot3?.setImageResource(R.drawable.ic_vox_ota_dot_active)
                text3?.setTextColor(Color.WHITE)
            }
        }

        btnClose?.setOnClickListener { onClose() }
        dialog?.setOnCancelListener { onClose() }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onClose()
                true
            } else {
                false
            }
        }
    }

    private fun clearProgressHolders() {
        progressIconView = null
        progressTitleView = null
        progressVersionView = null
        progressNoteView = null
        progressBarView = null
        percentTextView = null
        metricsContainer = null
        speedValueView = null
        sizeValueView = null
    }

    companion object {
        private const val TAG = "VoxOtaHudDialog"
    }
}

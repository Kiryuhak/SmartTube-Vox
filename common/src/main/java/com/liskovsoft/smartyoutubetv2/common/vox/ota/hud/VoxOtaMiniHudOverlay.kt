package com.liskovsoft.smartyoutubetv2.common.vox.ota.hud

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.R

/**
 * Floating bottom-right Mini-HUD for background OTA downloads and validation on Android TV.
 */
class VoxOtaMiniHudOverlay(private val context: Context) {

    private var rootView: View? = null
    private var parentView: ViewGroup? = null
    private var isVisible = false

    private var iconView: ImageView? = null
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnExpand: ImageButton? = null
    private var btnClose: ImageButton? = null

    fun isShown(): Boolean = isVisible && rootView != null && rootView?.visibility == View.VISIBLE

    fun show(
        activity: Activity,
        progress: VoxOtaHudState.Progress,
        onExpand: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        if (rootView == null || rootView?.parent != content) {
            destroy()
            try {
                val view = LayoutInflater.from(context).inflate(R.layout.vox_ota_hud_mini, content, false)
                bindViews(view)

                val density = context.resources.displayMetrics.density
                val endMargin = (28 * density).toInt()
                val bottomMargin = (28 * density).toInt()

                val lp = FrameLayout.LayoutParams(
                    (350 * density).toInt(),
                    (72 * density).toInt()
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    setMargins(0, 0, endMargin, bottomMargin)
                }
                view.layoutParams = lp

                content.addView(view)
                view.bringToFront()
                rootView = view
                parentView = content
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inflate Mini-HUD: ${e.message}", e)
                return
            }
        }

        btnExpand?.setOnClickListener { onExpand() }
        btnClose?.setOnClickListener { onCancel() }

        updateProgress(progress)

        rootView?.let { v ->
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(200L).start()
            isVisible = true
        }
    }

    fun updateProgress(progress: VoxOtaHudState.Progress) {
        if (rootView == null) return

        when (progress.phase) {
            VoxOtaProgressPhase.DOWNLOADING -> {
                iconView?.setImageResource(R.drawable.ic_vox_ota_arrow_down)
                titleView?.text = "Загрузка обновления..."
                val sizeStr = VoxOtaDisplayHelper.formatProgressSize(progress.bytesRead, progress.totalBytes)
                val speedStr = VoxOtaDisplayHelper.formatSpeed(progress.speedBytesPerSec)
                statusView?.text = "$sizeStr • $speedStr"
                progressBar?.isIndeterminate = false
                progressBar?.progress = progress.percent.coerceIn(0, 100)
            }
            VoxOtaProgressPhase.VERIFYING -> {
                iconView?.setImageResource(R.drawable.ic_vox_ota_gear)
                titleView?.text = "Проверка обновления..."
                statusView?.text = "Проверяем файл..."
                progressBar?.isIndeterminate = false
                progressBar?.progress = progress.percent.coerceIn(0, 100)
            }
            VoxOtaProgressPhase.PREPARING -> {
                iconView?.setImageResource(R.drawable.ic_vox_ota_package)
                titleView?.text = "Подготовка к установке..."
                statusView?.text = "Распаковка файла..."
                progressBar?.isIndeterminate = false
                progressBar?.progress = progress.percent.coerceIn(0, 100)
            }
        }
    }

    fun hide(onEnd: (() -> Unit)? = null) {
        if (!isVisible || rootView == null) {
            onEnd?.invoke()
            return
        }

        rootView?.animate()?.alpha(0f)?.setDuration(180L)?.withEndAction {
            rootView?.visibility = View.GONE
            isVisible = false
            onEnd?.invoke()
        }?.start()
    }

    fun destroy() {
        isVisible = false
        rootView?.let { v ->
            parentView?.removeView(v)
        }
        rootView = null
        parentView = null
        iconView = null
        titleView = null
        statusView = null
        progressBar = null
        btnExpand = null
        btnClose = null
    }

    private fun bindViews(root: View) {
        iconView = root.findViewById(R.id.vox_ota_mini_icon)
        titleView = root.findViewById(R.id.vox_ota_mini_title)
        statusView = root.findViewById(R.id.vox_ota_mini_status)
        progressBar = root.findViewById(R.id.vox_ota_mini_progressbar)
        btnExpand = root.findViewById(R.id.vox_ota_mini_btn_expand)
        btnClose = root.findViewById(R.id.vox_ota_mini_btn_close)
    }

    companion object {
        private const val TAG = "VoxOtaMiniHudOverlay"
    }
}

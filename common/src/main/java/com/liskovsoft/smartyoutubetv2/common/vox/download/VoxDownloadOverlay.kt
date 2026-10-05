package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.utils.Utils
import java.util.concurrent.atomic.AtomicLong

/**
 * Компактный ненавязчивый оверлей прогресса скачивания для Android TV.
 *
 * Особенности:
 * - Отображается в правом нижнем углу экрана без перекрытия контента и без паузы плеера.
 * - Полностью прозрачен для DPAD-навигации (не перехватывает фокус).
 * - Сглаженный расчет времени загрузки (EMA ETA).
 * - Счетчик очереди (+N в очереди).
 * - Плавные анимации появления и исчезновения с автоматическим скрытием по завершении.
 * - Троттлинг обновлений интерфейса для экономии CPU на ТВ.
 */
class VoxDownloadOverlay(private val context: Context) {

    private var overlayView: View? = null
    private var parentView: ViewGroup? = null
    private var spinner: ProgressBar? = null
    private var icon: ImageView? = null
    private var stageTitle: TextView? = null
    private var queueBadge: TextView? = null
    private var progressText: TextView? = null
    private var etaText: TextView? = null
    private var progressBar: ProgressBar? = null

    private var currentDownloadId: String? = null
    private val speedEstimator = VoxDownloadSpeedEstimator()
    private val lastUiUpdateTime = AtomicLong(0L)
    private var lastRenderedState: VoxDownloadState? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { hide() }

    companion object {
        private const val TAG = "VoxDownloadOverlay"
        private const val THROTTLE_INTERVAL_MS = 300L
        private const val DISMISS_DELAY_COMPLETED_MS = 3500L
        private const val DISMISS_DELAY_FAILED_MS = 5000L
        private const val DISMISS_DELAY_CANCELLED_MS = 2000L

        @Volatile
        private var globalInstance: VoxDownloadOverlay? = null

        @JvmStatic
        fun get(context: Context): VoxDownloadOverlay {
            return globalInstance ?: synchronized(this) {
                globalInstance ?: VoxDownloadOverlay(context.applicationContext).also { globalInstance = it }
            }
        }

        @JvmStatic
        fun show(context: Context, downloadId: String) {
            val activity = findActivity(context)
            val overlay = get(context)
            overlay.showForDownload(activity, downloadId)
        }

        @JvmStatic
        fun onProgress(context: Context, progress: VoxDownloadProgress) {
            val overlay = get(context)
            overlay.updateProgress(findActivity(context), progress)
        }

        @JvmStatic
        fun hideOverlay() {
            globalInstance?.hide()
        }

        @JvmStatic
        fun findActivity(context: Context?): Activity? {
            var ctx = context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }
    }

    fun isShown(): Boolean {
        return overlayView != null && overlayView?.visibility == View.VISIBLE
    }

    fun showForDownload(@Nullable activity: Activity?, downloadId: String) {
        val coordinator = VoxDownloadCoordinator.instance(context)
        val job = coordinator.getJob(downloadId) ?: return
        currentDownloadId = downloadId
        speedEstimator.reset()
        updateProgress(activity, job.getSnapshot(), force = true)
    }

    fun updateProgress(
        @Nullable activity: Activity?,
        progress: VoxDownloadProgress,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val isStateChange = progress.state != lastRenderedState

        // Троттлинг обновлений байт (но не смены состояний)
        if (!force && !isStateChange && (now - lastUiUpdateTime.get() < THROTTLE_INTERVAL_MS)) {
            return
        }
        lastUiUpdateTime.set(now)
        lastRenderedState = progress.state

        mainHandler.post {
            render(activity, progress)
        }
    }

    private fun render(@Nullable activity: Activity?, progress: VoxDownloadProgress) {
        if (!ensureAttached(activity)) {
            return
        }

        mainHandler.removeCallbacks(autoDismissRunnable)

        val stage = getStageTitle(progress.state)
        stageTitle?.text = stage

        // Расчет очереди
        val coordinator = VoxDownloadCoordinator.instance(context)
        val activeJobs = coordinator.getAllJobs().filter { VoxDownloadServicePolicy.isActiveState(it.state) }
        val queuedCount = activeJobs.size - 1
        if (queuedCount > 0) {
            queueBadge?.text = context.getString(R.string.vox_download_overlay_queue_badge, queuedCount)
            queueBadge?.visibility = View.VISIBLE
        } else {
            queueBadge?.visibility = View.GONE
        }

        when (progress.state) {
            VoxDownloadState.COMPLETED -> {
                showIcon(R.drawable.ic_vot_ready, R.color.vox_ready)
                progressText?.text = context.getString(R.string.vox_download_stage_completed)
                etaText?.visibility = View.GONE
                progressBar?.isIndeterminate = false
                progressBar?.progress = 100
                fadeIn()
                mainHandler.postDelayed(autoDismissRunnable, DISMISS_DELAY_COMPLETED_MS)
            }
            VoxDownloadState.FAILED -> {
                showIcon(R.drawable.ic_vot_error, R.color.vox_error)
                val err = progress.errorMessage ?: context.getString(R.string.vox_download_error_unknown)
                progressText?.text = err
                etaText?.visibility = View.GONE
                progressBar?.isIndeterminate = false
                progressBar?.progress = 0
                fadeIn()
                mainHandler.postDelayed(autoDismissRunnable, DISMISS_DELAY_FAILED_MS)
            }
            VoxDownloadState.CANCELLED -> {
                showIcon(R.drawable.ic_vot_error, R.color.vox_text_secondary)
                progressText?.text = context.getString(R.string.vox_download_stage_cancelled)
                etaText?.visibility = View.GONE
                progressBar?.isIndeterminate = false
                progressBar?.progress = 0
                fadeIn()
                mainHandler.postDelayed(autoDismissRunnable, DISMISS_DELAY_CANCELLED_MS)
            }
            VoxDownloadState.PREPARING_TRANSLATION,
            VoxDownloadState.RESOLVING_STREAMS -> {
                showSpinner()
                progressText?.text = context.getString(R.string.loading)
                etaText?.visibility = View.GONE
                progressBar?.isIndeterminate = true
                fadeIn()
            }
            VoxDownloadState.MUXING,
            VoxDownloadState.PUBLISHING -> {
                showSpinner()
                val percent = progress.overallPercent
                if (percent != null && percent > 0) {
                    progressBar?.isIndeterminate = false
                    progressBar?.progress = percent
                    progressText?.text = "$percent%"
                } else {
                    progressBar?.isIndeterminate = true
                    progressText?.text = context.getString(R.string.vox_download_stage_muxing)
                }
                etaText?.visibility = View.GONE
                fadeIn()
            }
            VoxDownloadState.DOWNLOADING_VIDEO,
            VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO,
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO -> {
                showSpinner()
                val downloaded = progress.totalBytesDownloaded
                val total = progress.totalBytesExpected
                val percent = progress.overallPercent

                if (percent != null && percent in 0..100) {
                    progressBar?.isIndeterminate = false
                    progressBar?.progress = percent
                } else {
                    progressBar?.isIndeterminate = true
                }

                val sizeStr = if (total != null && total > 0L) {
                    val percentStr = percent?.let { "$it% · " } ?: ""
                    "$percentStr${VoxDownloadSizeFormatter.formatBytes(downloaded)} / ${VoxDownloadSizeFormatter.formatBytes(total)}"
                } else {
                    val percentStr = percent?.let { "$it% · " } ?: ""
                    "$percentStr${VoxDownloadSizeFormatter.formatBytes(downloaded)}"
                }
                progressText?.text = sizeStr

                // EMA ETA
                val etaSec = speedEstimator.update(downloaded, total)
                if (etaSec != null && etaSec > 0L) {
                    val etaFormatted = if (etaSec >= 60L) {
                        val minutes = (etaSec + 30L) / 60L
                        context.getString(R.string.vox_download_overlay_eta_min, minutes.toInt())
                    } else {
                        context.getString(R.string.vox_download_overlay_eta_sec, etaSec.toInt())
                    }
                    etaText?.text = etaFormatted
                    etaText?.visibility = View.VISIBLE
                } else {
                    etaText?.visibility = View.GONE
                }
                fadeIn()
            }
            else -> {
                showSpinner()
                fadeIn()
            }
        }
    }

    private fun showSpinner() {
        spinner?.visibility = View.VISIBLE
        icon?.visibility = View.GONE
    }

    private fun showIcon(drawableResId: Int, tintColorResId: Int) {
        spinner?.visibility = View.GONE
        icon?.let {
            it.setImageDrawable(ContextCompat.getDrawable(context, drawableResId))
            it.setColorFilter(ContextCompat.getColor(context, tintColorResId))
            it.visibility = View.VISIBLE
        }
    }

    private fun fadeIn() {
        overlayView?.let { view ->
            view.bringToFront()
            view.animate().cancel()
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            view.animate().alpha(1f).setDuration(200L).start()
        }
    }

    fun hide() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        overlayView?.let { view ->
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .setDuration(250L)
                .withEndAction {
                    view.visibility = View.GONE
                }
                .start()
        }
    }

    fun dismissImmediately() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        overlayView?.let { view ->
            view.animate().cancel()
            view.visibility = View.GONE
            view.alpha = 0f
        }
    }

    private fun ensureAttached(@Nullable activity: Activity?): Boolean {
        if (overlayView != null && overlayView?.parent != null) {
            overlayView?.bringToFront()
            return true
        }

        val targetActivity = activity ?: return false
        if (targetActivity.isFinishing) {
            return false
        }

        val targetContainer = findTargetContainer(targetActivity) ?: return false

        try {
            val existing = targetContainer.findViewById<View>(R.id.vox_download_overlay_root)
            if (existing != null) {
                bindViews(existing)
                parentView = targetContainer
                overlayView?.bringToFront()
                return true
            }

            val inflater = LayoutInflater.from(targetActivity)
            val root = inflater.inflate(R.layout.vox_download_overlay, targetContainer, false)
            bindViews(root)

            root.isFocusable = false
            root.isFocusableInTouchMode = false
            root.isClickable = false

            val marginEnd = dpToPx(48f)
            val marginBottom = dpToPx(72f)

            if (targetContainer is FrameLayout) {
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.gravity = Gravity.BOTTOM or Gravity.END
                lp.rightMargin = marginEnd
                lp.bottomMargin = marginBottom
                root.layoutParams = lp
            } else if (targetContainer is LinearLayout) {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.gravity = Gravity.END
                lp.bottomMargin = marginBottom
                root.layoutParams = lp
            } else {
                val lp = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.rightMargin = marginEnd
                lp.bottomMargin = marginBottom
                root.layoutParams = lp
            }

            root.visibility = View.GONE
            root.alpha = 0f

            targetContainer.addView(root)
            root.bringToFront()
            parentView = targetContainer
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate VoxDownloadOverlay: %s", e.message)
            return false
        }
    }

    private fun bindViews(root: View) {
        overlayView = root
        spinner = root.findViewById(R.id.vox_download_spinner)
        icon = root.findViewById(R.id.vox_download_icon)
        stageTitle = root.findViewById(R.id.vox_download_stage_title)
        queueBadge = root.findViewById(R.id.vox_download_queue_badge)
        progressText = root.findViewById(R.id.vox_download_progress_text)
        etaText = root.findViewById(R.id.vox_download_eta_text)
        progressBar = root.findViewById(R.id.vox_download_progressbar)
    }

    private fun findTargetContainer(activity: Activity): ViewGroup? {
        val rootId = activity.resources.getIdentifier("playback_fragment_root", "id", activity.packageName)
        if (rootId != 0) {
            val root = activity.findViewById<View>(rootId)
            if (root is ViewGroup) return root
        }
        val content = activity.findViewById<View>(android.R.id.content)
        if (content is ViewGroup) return content
        val decor = activity.window?.decorView
        if (decor is ViewGroup) return decor
        return null
    }

    private fun getStageTitle(state: VoxDownloadState): String {
        return when (state) {
            VoxDownloadState.PREPARING_TRANSLATION -> context.getString(R.string.vox_download_stage_prep_trans)
            VoxDownloadState.RESOLVING_STREAMS -> context.getString(R.string.vox_download_stage_resolving)
            VoxDownloadState.DOWNLOADING_VIDEO -> context.getString(R.string.vox_download_stage_video)
            VoxDownloadState.DOWNLOADING_ORIGINAL_AUDIO -> context.getString(R.string.vox_download_stage_orig_audio)
            VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO -> context.getString(R.string.vox_download_stage_trans_audio)
            VoxDownloadState.READY_FOR_MUX,
            VoxDownloadState.MUXING -> context.getString(R.string.vox_download_stage_muxing)
            VoxDownloadState.PUBLISHING -> context.getString(R.string.vox_download_stage_publishing)
            VoxDownloadState.COMPLETED -> context.getString(R.string.vox_download_stage_completed)
            VoxDownloadState.FAILED -> context.getString(R.string.vox_download_stage_failed)
            VoxDownloadState.CANCELLED -> context.getString(R.string.vox_download_stage_cancelled)
            VoxDownloadState.PAUSED -> context.getString(R.string.vox_download_state_paused)
            else -> context.getString(R.string.vox_download_notification_title)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    // --- Helpers for unit testing ---
    fun getStageTitleText(): String = stageTitle?.text?.toString().orEmpty()
    fun getProgressText(): String = progressText?.text?.toString().orEmpty()
    fun getEtaText(): String = etaText?.text?.toString().orEmpty()
    fun getQueueBadgeText(): String = queueBadge?.text?.toString().orEmpty()
    fun isProgressBarIndeterminate(): Boolean = progressBar?.isIndeterminate == true
    fun getProgressBarProgress(): Int = progressBar?.progress ?: 0
}

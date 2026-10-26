package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Визуальный виджет скачивания (VOT Download Badge) строго по фиксированному макету (Prompt #028 Golden Reference).
 *
 * 5 этапов отображения:
 * 1. SELECTING: Одно модальное окно (VoxDownloadSelectionDialog).
 * 2. DOWNLOADING_EXPANDED: Развернутая панель 96dp (ширина 320dp, отступы 24dp от правого и нижнего края).
 * 3. DOWNLOADING_COMPACT: Компактный круглый виджет 64x64 dp с кольцом прогресса (#1890FF).
 * 4. REOPENED: Повторное раскрытие по клику без паузы воспроизведения видео.
 * 5. LONG_PRESS_MENU: Контекстное меню по долгому нажатию ("Продолжить" / "Пауза", "Отменить скачивание").
 * 6. COMPLETED: Зеленая галочка #00C853 "Скачано" с автоскрытием через 3.5 секунды.
 */
class VoxDownloadOverlay(private val context: Context) {

    private var overlayRootView: View? = null
    private var parentView: ViewGroup? = null

    // Stage Containers
    private var stageExpandedContainer: LinearLayout? = null
    private var stageCompactContainer: FrameLayout? = null
    private var stageContextMenuContainer: LinearLayout? = null
    private var stageCompletedContainer: LinearLayout? = null

    // Expanded Views
    private var expandedTitle: TextView? = null
    private var expandedPillQuality: TextView? = null
    private var expandedPillAudio: TextView? = null
    private var expandedSpeed: TextView? = null
    private var expandedDownloaded: TextView? = null
    private var expandedTotalSize: TextView? = null
    private var expandedEta: TextView? = null
    private var expandedProgressBar: ProgressBar? = null
    private var expandedPercent: TextView? = null

    // Compact Views
    private var compactRing: VoxCircularProgressView? = null
    private var compactIcon: ImageView? = null
    private var compactPercent: TextView? = null

    // Context Menu Views
    private var menuBtnResumePause: TextView? = null
    private var menuBtnCancel: TextView? = null

    // Completed Views
    private var completedIcon: ImageView? = null
    private var completedDetails: TextView? = null

    private var currentDownloadId: String? = null
    private val speedEstimator = VoxDownloadSpeedEstimator()
    private val lastUiUpdateTime = AtomicLong(0L)
    private var lastRenderedState: VoxDownloadState? = null
    private var boundActivity = WeakReference<Activity>(null)

    val stateMachine = VoxDownloadBadgeStateMachine()

    private val coordinatorListener = object : VoxDownloadListener {
        override fun onStateChanged(progress: VoxDownloadProgress) = updateProgress(null, progress)
        override fun onProgressUpdated(progress: VoxDownloadProgress) = updateProgress(null, progress)
        override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) = Unit
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { hide() }
    private val autoCompactRunnable = Runnable { collapseToCompact() }
    private val contextMenuIdleRunnable = Runnable {
        if (stateMachine.getStage() == VoxBadgeStage.LONG_PRESS_MENU) {
            hideContextMenu()
        }
    }

    private fun scheduleContextMenuIdleTimer() {
        mainHandler.removeCallbacks(contextMenuIdleRunnable)
        mainHandler.postDelayed(contextMenuIdleRunnable, CONTEXT_MENU_IDLE_TIMEOUT_MS)
    }

    fun resetContextMenuIdleTimer() {
        if (stateMachine.getStage() == VoxBadgeStage.LONG_PRESS_MENU) {
            scheduleContextMenuIdleTimer()
        }
    }

    private fun cancelContextMenuIdleTimer() {
        mainHandler.removeCallbacks(contextMenuIdleRunnable)
    }

    companion object {
        private const val TAG = "VoxDownloadOverlay"
        private const val THROTTLE_INTERVAL_MS = 300L
        private const val AUTO_COMPACT_DELAY_MS = 4000L
        private const val DISMISS_DELAY_COMPLETED_MS = 3500L
        private const val DISMISS_DELAY_FAILED_MS = 5000L
        private const val DISMISS_DELAY_CANCELLED_MS = 2000L
        const val CONTEXT_MENU_IDLE_TIMEOUT_MS = 8000L

        // Фиксированные параметры из макета (Golden Reference)
        const val COMPACT_SIZE_DP = 64f
        const val EXPANDED_HEIGHT_DP = 96f
        const val EXPANDED_WIDTH_DP = 320f
        const val MARGIN_BOTTOM_DP = 24f
        const val MARGIN_RIGHT_DP = 24f

        @Volatile
        private var globalInstance: VoxDownloadOverlay? = null

        @JvmStatic
        fun get(context: Context): VoxDownloadOverlay {
            return globalInstance ?: synchronized(this) {
                globalInstance ?: VoxDownloadOverlay(context.applicationContext).also {
                    VoxDownloadCoordinator.instance(context).addGlobalListener(it.coordinatorListener)
                    globalInstance = it
                }
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
        return overlayRootView != null && overlayRootView?.visibility == View.VISIBLE
    }

    fun getStage(): VoxBadgeStage = stateMachine.getStage()
    fun isCompactMode(): Boolean = stateMachine.getStage() == VoxBadgeStage.DOWNLOADING_COMPACT
    fun isExpandedMode(): Boolean = stateMachine.getStage() == VoxBadgeStage.DOWNLOADING_EXPANDED || stateMachine.getStage() == VoxBadgeStage.REOPENED
    fun isContextMenuVisible(): Boolean = stateMachine.getStage() == VoxBadgeStage.LONG_PRESS_MENU
    fun isCompletedVisible(): Boolean = stateMachine.getStage() == VoxBadgeStage.COMPLETED

    fun showForDownload(@Nullable activity: Activity?, downloadId: String) {
        if (activity != null) boundActivity = WeakReference(activity)
        val coordinator = VoxDownloadCoordinator.instance(context)
        val job = coordinator.getJob(downloadId) ?: return
        currentDownloadId = downloadId
        stateMachine.setActiveDownloadId(downloadId)
        speedEstimator.reset()
        stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_EXPANDED)
        scheduleAutoCompact()
        updateProgress(activity, job.getSnapshot(), force = true)
    }

    fun updateProgress(
        @Nullable activity: Activity?,
        progress: VoxDownloadProgress,
        force: Boolean = false
    ) {
        if (activity != null) boundActivity = WeakReference(activity)
        val now = System.currentTimeMillis()
        val isStateChange = progress.state != lastRenderedState

        if (!force && !isStateChange && (now - lastUiUpdateTime.get() < THROTTLE_INTERVAL_MS)) {
            return
        }
        lastUiUpdateTime.set(now)
        lastRenderedState = progress.state

        mainHandler.post {
            render(activity ?: boundActivity.get(), progress)
        }
    }

    private fun render(@Nullable activity: Activity?, event: VoxDownloadProgress) {
        val coordinator = VoxDownloadCoordinator.instance(context)
        val active = coordinator.getProcessingJob()
        val progress = if (active != null && active.downloadId != event.downloadId && !active.state.isTerminal)
            active.getSnapshot() else event
        if (currentDownloadId != progress.downloadId) {
            currentDownloadId = progress.downloadId
            stateMachine.setActiveDownloadId(progress.downloadId)
            speedEstimator.reset()
        }
        if (!ensureAttached(activity)) {
            return
        }

        mainHandler.removeCallbacks(autoDismissRunnable)

        val job = coordinator.getJob(progress.downloadId)
        val titleText = job?.request?.videoTitle ?: "Видео"
        expandedTitle?.text = titleText

        // Пилюли качества и перевода
        val qualityLabel = when (job?.request?.qualityPreference) {
            VoxQualityPreference.QUALITY_2160P -> "2160p / 4K"
            VoxQualityPreference.QUALITY_1440P -> "1440p"
            VoxQualityPreference.QUALITY_1080P -> "1080p"
            VoxQualityPreference.QUALITY_720P -> "720p"
            else -> job?.request?.qualityPreference?.label ?: "1080p"
        }
        expandedPillQuality?.text = qualityLabel

        val audioLabel = when (job?.request?.translationMode) {
            VoxTranslationMode.STANDARD -> "VOT перевод"
            VoxTranslationMode.LIVELY -> "VOT живой"
            else -> "Оригинал"
        }
        expandedPillAudio?.text = audioLabel

        val downloaded = progress.totalBytesDownloaded
        val total = progress.totalBytesExpected
        val percent = progress.overallPercent ?: 0
        val clampedPercent = percent.coerceIn(0, 100)

        // Обновляем метрики прогресса
        compactRing?.setProgress(clampedPercent)
        compactPercent?.text = "$clampedPercent%"
        expandedPercent?.text = "$clampedPercent%"
        expandedProgressBar?.progress = clampedPercent

        val downloadedStr = VoxDownloadSizeFormatter.formatBytes(downloaded)
        expandedDownloaded?.text = "Скачано: $downloadedStr"

        if (total != null && total > 0L) {
            val totalStr = VoxDownloadSizeFormatter.formatBytes(total)
            expandedTotalSize?.text = "Размер: $totalStr"
        } else {
            expandedTotalSize?.text = "Размер: Расчёт…"
        }

        val etaSec = speedEstimator.update(downloaded, total)
        val speedStr = speedEstimator.getFormattedSpeed()
        if (speedStr.isNotEmpty()) {
            expandedSpeed?.text = "Скорость: $speedStr"
        } else {
            expandedSpeed?.text = "Скорость: Расчёт…"
        }

        if (etaSec != null && etaSec > 0L) {
            val etaStr = if (etaSec >= 60L) {
                val min = etaSec / 60L
                val sec = etaSec % 60L
                "~${min} мин ${sec} с"
            } else {
                "~${etaSec} с"
            }
            expandedEta?.text = "Осталось: $etaStr"
        } else {
            expandedEta?.text = "Осталось: ~…"
        }

        // Обновление состояния паузы
        val isPaused = progress.state == VoxDownloadState.PAUSED
        if (isPaused) {
            menuBtnResumePause?.text = "▶  Продолжить"
        } else {
            menuBtnResumePause?.text = "⏸  Пауза"
        }

        when (progress.state) {
            VoxDownloadState.COMPLETED -> {
                stateMachine.transitionTo(VoxBadgeStage.COMPLETED)
                showCompletedStage(downloaded, qualityLabel, audioLabel)
                fadeIn()
                mainHandler.postDelayed(autoDismissRunnable, DISMISS_DELAY_COMPLETED_MS)
            }
            VoxDownloadState.FAILED -> {
                stateMachine.transitionTo(VoxBadgeStage.ERROR)
                val err = VoxDownloadFailureClassifier.getUserMessage(progress.errorCode)
                expandedSpeed?.text = "Ошибка: $err"
                showExpandedStage()
                fadeIn()
                mainHandler.postDelayed(autoDismissRunnable, DISMISS_DELAY_FAILED_MS)
            }
            VoxDownloadState.CANCELLED -> {
                stateMachine.transitionTo(VoxBadgeStage.CANCELLED)
                expandedSpeed?.text = "Скачивание отменено"
                showExpandedStage()
                fadeIn()
                mainHandler.postDelayed(autoDismissRunnable, DISMISS_DELAY_CANCELLED_MS)
            }
            VoxDownloadState.PAUSED -> {
                stateMachine.transitionTo(VoxBadgeStage.PAUSED)
                applyCurrentStageVisibility()
                fadeIn()
            }
            else -> {
                if (stateMachine.getStage() == VoxBadgeStage.HIDDEN) {
                    stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_EXPANDED)
                    scheduleAutoCompact()
                }
                applyCurrentStageVisibility()
                fadeIn()
            }
        }
    }

    private fun applyCurrentStageVisibility() {
        when (stateMachine.getStage()) {
            VoxBadgeStage.DOWNLOADING_EXPANDED,
            VoxBadgeStage.REOPENED,
            VoxBadgeStage.ERROR,
            VoxBadgeStage.CANCELLED -> showExpandedStage()
            VoxBadgeStage.DOWNLOADING_COMPACT -> showCompactStage()
            VoxBadgeStage.LONG_PRESS_MENU -> showContextMenuStage()
            VoxBadgeStage.COMPLETED -> Unit // Handled in showCompletedStage
            VoxBadgeStage.PAUSED -> {
                // Keep either expanded or compact depending on current visibility
                if (stageExpandedContainer?.visibility == View.VISIBLE) {
                    showExpandedStage()
                } else {
                    showCompactStage()
                }
            }
            VoxBadgeStage.HIDDEN,
            VoxBadgeStage.SELECTING -> hideContainers()
        }
    }

    private fun showExpandedStage() {
        stageExpandedContainer?.visibility = View.VISIBLE
        stageCompactContainer?.visibility = View.GONE
        stageContextMenuContainer?.visibility = View.GONE
        stageCompletedContainer?.visibility = View.GONE
    }

    private fun showCompactStage() {
        stageExpandedContainer?.visibility = View.GONE
        stageCompactContainer?.visibility = View.VISIBLE
        stageContextMenuContainer?.visibility = View.GONE
        stageCompletedContainer?.visibility = View.GONE
    }

    private fun showContextMenuStage() {
        stageExpandedContainer?.visibility = View.GONE
        stageCompactContainer?.visibility = View.VISIBLE
        stageContextMenuContainer?.visibility = View.VISIBLE
        stageCompletedContainer?.visibility = View.GONE
        menuBtnResumePause?.requestFocus()
    }

    private fun showCompletedStage(downloadedBytes: Long, quality: String, audio: String) {
        stageExpandedContainer?.visibility = View.GONE
        stageCompactContainer?.visibility = View.GONE
        stageContextMenuContainer?.visibility = View.GONE
        stageCompletedContainer?.visibility = View.VISIBLE

        val sizeStr = VoxDownloadSizeFormatter.formatBytes(downloadedBytes)
        completedDetails?.text = "$sizeStr • $quality • $audio"
    }

    private fun hideContainers() {
        stageExpandedContainer?.visibility = View.GONE
        stageCompactContainer?.visibility = View.GONE
        stageContextMenuContainer?.visibility = View.GONE
        stageCompletedContainer?.visibility = View.GONE
    }

    fun expandToReopened() {
        mainHandler.removeCallbacks(autoCompactRunnable)
        stateMachine.transitionTo(VoxBadgeStage.REOPENED)
        showExpandedStage()
        scheduleAutoCompact()
    }

    fun collapseToCompact() {
        mainHandler.removeCallbacks(autoCompactRunnable)
        stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_COMPACT)
        showCompactStage()
    }

    fun showContextMenu() {
        mainHandler.removeCallbacks(autoCompactRunnable)
        stateMachine.transitionTo(VoxBadgeStage.LONG_PRESS_MENU)
        showContextMenuStage()
        scheduleContextMenuIdleTimer()
    }

    fun hideContextMenu() {
        cancelContextMenuIdleTimer()
        if (stateMachine.getStage() == VoxBadgeStage.LONG_PRESS_MENU) {
            val isPaused = currentDownloadId?.let { id ->
                VoxDownloadCoordinator.instance(context).getJob(id)?.state == VoxDownloadState.PAUSED
            } ?: false
            if (isPaused) {
                stateMachine.transitionTo(VoxBadgeStage.PAUSED)
            } else {
                stateMachine.transitionTo(VoxBadgeStage.DOWNLOADING_COMPACT)
            }
            showCompactStage()
        }
    }

    private fun scheduleAutoCompact() {
        mainHandler.removeCallbacks(autoCompactRunnable)
        mainHandler.postDelayed(autoCompactRunnable, AUTO_COMPACT_DELAY_MS)
    }

    private fun fadeIn() {
        overlayRootView?.let { view ->
            if (!com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.claim(view,
                    com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.StatusOwner.DOWNLOAD)) return
            if (view.visibility == View.VISIBLE && view.alpha == 1f) return
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
        cancelContextMenuIdleTimer()
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.removeCallbacks(autoCompactRunnable)
        stateMachine.transitionTo(VoxBadgeStage.HIDDEN)
        overlayRootView?.let { view ->
            com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.release(
                view,
                com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.StatusOwner.DOWNLOAD
            )
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
        cancelContextMenuIdleTimer()
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.removeCallbacks(autoCompactRunnable)
        stateMachine.transitionTo(VoxBadgeStage.HIDDEN)
        overlayRootView?.let { view ->
            com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.release(
                view,
                com.liskovsoft.smartyoutubetv2.common.vox.ui.VoxTransientStatusCoordinator.StatusOwner.DOWNLOAD
            )
            view.animate().cancel()
            view.visibility = View.GONE
            view.alpha = 0f
        }
    }

    private fun ensureAttached(@Nullable activity: Activity?): Boolean {
        if (activity == null || activity.isFinishing) return false
        if (overlayRootView != null && overlayRootView?.parent != findTargetContainer(activity)) {
            dismissImmediately()
            parentView?.removeView(overlayRootView)
            overlayRootView = null
            parentView = null
        }
        if (overlayRootView != null && overlayRootView?.parent != null) {
            overlayRootView?.bringToFront()
            return true
        }

        val targetActivity = activity ?: return false
        if (targetActivity.isFinishing) return false

        val targetContainer = findTargetContainer(targetActivity) ?: return false

        try {
            val existing = targetContainer.findViewById<View>(R.id.vox_download_badge_root)
            if (existing != null) {
                bindViews(existing)
                parentView = targetContainer
                overlayRootView?.bringToFront()
                return true
            }

            val inflater = LayoutInflater.from(targetActivity)
            val root = inflater.inflate(R.layout.vox_download_badge_overlay, targetContainer, false)
            bindViews(root)

            // Точные отступы по макету: 24dp от правого и нижнего края
            val marginEnd = dpToPx(MARGIN_RIGHT_DP)
            val marginBottom = dpToPx(MARGIN_BOTTOM_DP)

            if (targetContainer is FrameLayout) {
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.gravity = Gravity.BOTTOM or Gravity.END
                lp.rightMargin = marginEnd
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
        overlayRootView = root

        stageExpandedContainer = root.findViewById(R.id.vox_stage_expanded)
        stageCompactContainer = root.findViewById(R.id.vox_stage_compact)
        stageContextMenuContainer = root.findViewById(R.id.vox_stage_context_menu)
        stageCompletedContainer = root.findViewById(R.id.vox_stage_completed)

        expandedTitle = root.findViewById(R.id.vox_expanded_title)
        expandedPillQuality = root.findViewById(R.id.vox_expanded_pill_quality)
        expandedPillAudio = root.findViewById(R.id.vox_expanded_pill_audio)
        expandedSpeed = root.findViewById(R.id.vox_expanded_speed)
        expandedDownloaded = root.findViewById(R.id.vox_expanded_downloaded)
        expandedTotalSize = root.findViewById(R.id.vox_expanded_total_size)
        expandedEta = root.findViewById(R.id.vox_expanded_eta)
        expandedProgressBar = root.findViewById(R.id.vox_expanded_progress_bar)
        expandedPercent = root.findViewById(R.id.vox_expanded_percent)

        compactRing = root.findViewById(R.id.vox_compact_ring)
        compactIcon = root.findViewById(R.id.vox_compact_icon)
        compactPercent = root.findViewById(R.id.vox_compact_percent)

        menuBtnResumePause = root.findViewById(R.id.vox_menu_btn_resume_pause)
        menuBtnCancel = root.findViewById(R.id.vox_menu_btn_cancel)

        completedIcon = root.findViewById(R.id.vox_completed_icon)
        completedDetails = root.findViewById(R.id.vox_completed_details)

        setupListeners()
    }

    private fun setupListeners() {
        // Обычный клик на компактный виджет -> Раскрывает панель БЕЗ паузы плеера (Этап 4)
        stageCompactContainer?.setOnClickListener {
            expandToReopened()
        }

        // Долгое нажатие на компактный виджет -> Открывает контекстное меню (Этап 5)
        stageCompactContainer?.setOnLongClickListener {
            showContextMenu()
            true
        }

        // 8000ms idle timer reset & Back navigation for Context Menu
        val interactionFocusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) resetContextMenuIdleTimer()
        }
        menuBtnResumePause?.onFocusChangeListener = interactionFocusListener
        menuBtnCancel?.onFocusChangeListener = interactionFocusListener

        val keyListener = View.OnKeyListener { _, keyCode, event ->
            resetContextMenuIdleTimer()
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (stateMachine.getStage() == VoxBadgeStage.LONG_PRESS_MENU) {
                    hideContextMenu()
                    return@OnKeyListener true
                }
            }
            false
        }
        menuBtnResumePause?.setOnKeyListener(keyListener)
        menuBtnCancel?.setOnKeyListener(keyListener)
        stageContextMenuContainer?.setOnKeyListener(keyListener)

        stageContextMenuContainer?.setOnTouchListener { _, _ ->
            resetContextMenuIdleTimer()
            false
        }

        // Действия контекстного меню
        menuBtnResumePause?.setOnClickListener {
            resetContextMenuIdleTimer()
            togglePauseResume()
            hideContextMenu()
        }

        menuBtnCancel?.setOnClickListener {
            cancelContextMenuIdleTimer()
            val id = currentDownloadId
            if (id != null) {
                VoxDownloadCoordinator.instance(context).cancelDownload(id)
            }
            hide()
        }
    }

    private fun togglePauseResume() {
        val id = currentDownloadId ?: return
        val coordinator = VoxDownloadCoordinator.instance(context)
        val job = coordinator.getJob(id) ?: return
        if (job.state == VoxDownloadState.PAUSED) {
            coordinator.resumeDownload(id)
        } else {
            coordinator.pauseDownload(id)
        }
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

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    // --- Helpers for legacy unit testing compatibility ---
    fun getStageTitleText(): String = expandedTitle?.text?.toString().orEmpty()
    fun getProgressText(): String = expandedDownloaded?.text?.toString().orEmpty()
    fun getEtaText(): String = expandedEta?.text?.toString().orEmpty()
    fun getQueueBadgeText(): String = ""
    fun isProgressBarIndeterminate(): Boolean = expandedProgressBar?.isIndeterminate == true
    fun getProgressBarProgress(): Int = expandedProgressBar?.progress ?: 0
    fun getContextMenuIdleRunnable(): Runnable = contextMenuIdleRunnable
}

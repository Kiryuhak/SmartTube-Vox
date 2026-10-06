package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.liskovsoft.sharedutils.helpers.MessageHelpers
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil
import com.liskovsoft.smartyoutubetv2.common.utils.VotOnboardingHelper
import java.util.concurrent.atomic.AtomicReference

/**
 * TV-интерфейс скачивания видео с закадровым переводом.
 */
object VoxDownloadDialogHelper {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isSupported(context: Context?): Boolean {
        if (context == null) return false
        return VotOnboardingHelper.isStvot(context)
    }

    @JvmStatic
    fun onDownloadActionClicked(context: Context, video: Video?) {
        if (video == null || video.videoId.isNullOrBlank()) {
            return
        }

        if (video.isLive || video.isUpcoming) {
            MessageHelpers.showMessage(context, R.string.vox_download_live_unsupported)
            return
        }

        val coordinator = VoxDownloadCoordinator.instance(context)

        // 1. Проверяем, есть ли активная загрузка этого видео
        val activeJob = coordinator.findActiveJob(video.videoId)
        if (activeJob != null) {
            MessageHelpers.showMessage(context, R.string.vox_download_already_active)
            VoxDownloadOverlay.show(context, activeJob.downloadId)
            return
        }

        // 2. Проверяем, скачано ли уже это видео
        val completedJob = coordinator.findCompletedJob(video.videoId)
        if (completedJob != null && coordinator.isPublishedFileAvailable(completedJob)) {
            showAlreadyDownloadedDialog(context, video, completedJob)
            return
        }

        // 3. Открываем диалог параметров скачивания
        showDownloadOptionsDialog(context, video)
    }

    private fun showAlreadyDownloadedDialog(context: Context, video: Video, job: VoxDownloadJob) {
        val presenter = AppDialogPresenter.instance(context)
        val coordinator = VoxDownloadCoordinator.instance(context)

        val options = mutableListOf<OptionItem>()

        options.add(UiOptionItem.from(
            context.getString(R.string.vox_download_open_local),
            { _ ->
                presenter.closeDialog()
                VoxLocalPlayerHelper.playJob(context, job)
            }
        ))

        options.add(UiOptionItem.from(
            context.getString(R.string.vox_download_redownload),
            { _ ->
                presenter.closeDialog()
                showDownloadOptionsDialog(context, video)
            }
        ))

        options.add(UiOptionItem.from(
            context.getString(R.string.vox_download_delete),
            { _ ->
                coordinator.deleteDownload(job.downloadId)
                presenter.closeDialog()
                MessageHelpers.showMessage(context, R.string.vox_download_delete)
            }
        ))

        options.add(UiOptionItem.from(
            context.getString(R.string.vox_download_close),
            { _ -> presenter.closeDialog() }
        ))

        val title = context.getString(R.string.vox_download_already_downloaded)
        presenter.appendRadioCategory(title, options)
        presenter.showDialog(title)
    }

    @JvmStatic
    fun showDownloadOptionsDialog(context: Context, video: Video) {
        val presenter = AppDialogPresenter.instance(context)
        val votData = VotData.instance(context)

        var selectedQuality = VoxQualityPreference.QUALITY_720P
        var selectedMode = VoxTranslationMode.STANDARD

        // Категория качества видео
        val qualityOptions = mutableListOf<OptionItem>()
        val qualities = listOf(
            VoxQualityPreference.QUALITY_AUTO,
            VoxQualityPreference.QUALITY_1080P,
            VoxQualityPreference.QUALITY_720P,
            VoxQualityPreference.QUALITY_360P
        )

        for (q in qualities) {
            qualityOptions.add(UiOptionItem.from(
                q.label,
                { opt -> if (opt.isSelected) selectedQuality = q },
                q == selectedQuality
            ))
        }

        // Категория режима перевода
        val transOptions = mutableListOf<OptionItem>()
        transOptions.add(UiOptionItem.from(
            "Без перевода",
            { opt -> if (opt.isSelected) selectedMode = VoxTranslationMode.NONE },
            selectedMode == VoxTranslationMode.NONE
        ))
        transOptions.add(UiOptionItem.from(
            context.getString(R.string.vox_download_translation_standard),
            { opt -> if (opt.isSelected) selectedMode = VoxTranslationMode.STANDARD },
            selectedMode == VoxTranslationMode.STANDARD
        ))

        val hasAuth = votData.hasOAuthToken()
        val livelyTitle = if (hasAuth) {
            context.getString(R.string.vox_download_translation_lively)
        } else {
            "${context.getString(R.string.vox_download_translation_lively)} (${context.getString(R.string.vox_download_auth_required)})"
        }

        transOptions.add(UiOptionItem.from(
            livelyTitle,
            { opt ->
                if (hasAuth) {
                    if (opt.isSelected) selectedMode = VoxTranslationMode.LIVELY
                } else {
                    MessageHelpers.showMessage(context, R.string.vox_download_auth_required)
                }
            },
            selectedMode == VoxTranslationMode.LIVELY
        ))

        val title = context.getString(R.string.vox_download_title)

        presenter.appendRadioCategory(context.getString(R.string.vox_download_quality_category), qualityOptions)
        presenter.appendRadioCategory(context.getString(R.string.vox_download_translation_category), transOptions)
        presenter.appendSingleButton(
            UiOptionItem.from(
                context.getString(R.string.vox_download_start_button),
                { _ ->
                    presenter.closeDialog()
                    startDownloadAndShowProgress(context, video, selectedQuality, selectedMode)
                }
            )
        )
        presenter.showDialog(title)
    }

    private fun startDownloadAndShowProgress(
        context: Context,
        video: Video,
        quality: VoxQualityPreference,
        mode: VoxTranslationMode
    ) {
        val coordinator = VoxDownloadCoordinator.instance(context)
        val storage = VoxDownloadStorage(context.applicationContext)
        val availableBytes = storage.getAvailableBytes()

        if (availableBytes < 300L * 1024L * 1024L) {
            val stats = VoxDownloadStorageStats.collect(context.applicationContext, storage, coordinator.getAllJobs())
            val usedStr = VoxDownloadUiMapper.formatSize(stats.usedBytes)
            val presenter = AppDialogPresenter.instance(context)
            val warningTitle = context.getString(R.string.vox_download_storage_low_warning)
            val desc = context.getString(R.string.vox_download_storage_low_desc, usedStr)
            val options = mutableListOf<OptionItem>()
            options.add(UiOptionItem.from(context.getString(R.string.header_downloaded_videos), { _ ->
                presenter.closeDialog()
                VoxDownloadManager.show(context)
            }))
            options.add(UiOptionItem.from(context.getString(R.string.cancel_dialog), { _ ->
                presenter.closeDialog()
            }))
            presenter.appendStringsCategory(desc, options)
            presenter.showDialog(warningTitle)
            return
        }

        val title = video.title ?: "Video_${video.videoId}"
        val request = VoxDownloadRequest(
            videoId = video.videoId,
            videoTitle = title,
            qualityPreference = quality,
            translationMode = mode
        )

        val downloadId = coordinator.startDownload(request)
        VoxDownloadService.start(context, downloadId)
        VoxDownloadOverlay.show(context, downloadId)
    }

    @JvmStatic
    fun showProgressDialog(context: Context, downloadId: String) {
        val presenter = AppDialogPresenter.instance(context)
        if (presenter.isDialogShown) {
            presenter.closeDialog()
            mainHandler.postDelayed({ openProgressDialog(context, downloadId) }, 250L)
        } else {
            openProgressDialog(context, downloadId)
        }
    }

    private fun openProgressDialog(context: Context, downloadId: String) {
        val presenter = AppDialogPresenter.instance(context)
        val coordinator = VoxDownloadCoordinator.instance(context)
        if (coordinator.getJob(downloadId) == null) return

        val listenerRef = AtomicReference<VoxDownloadListener>()
        var isShowing = true
        var hasRendered = false
        var lastRendered = ""
        lateinit var renderDialogFunc: () -> Unit

        val cleanupListener = {
            isShowing = false
            listenerRef.get()?.let { coordinator.removeListener(downloadId, it) }
        }

        renderDialogFunc = render@{
            if (isShowing) {
                val currentJob = coordinator.getJob(downloadId)
                if (currentJob != null) {
                    val progress = currentJob.getSnapshot()
                val stageTitle = getStageTitle(context, progress)
                val percent = progress.overallPercent

                val displayTitle = if (percent != null && percent in 0..100) {
                    "$stageTitle ($percent%)"
                } else {
                    stageTitle
                }

                val signature = "${progress.state}:$displayTitle"
                if (signature == lastRendered) return@render
                lastRendered = signature

                val options = mutableListOf<OptionItem>()

                if (currentJob.state == VoxDownloadState.COMPLETED) {
                    options.add(UiOptionItem.from(
                        context.getString(R.string.vox_download_open_local),
                        { _ ->
                            cleanupListener()
                            presenter.closeDialog()
                            VoxLocalPlayerHelper.playJob(context, currentJob)
                        }
                    ))
                    options.add(UiOptionItem.from(
                        context.getString(R.string.vox_download_close),
                        { _ ->
                            cleanupListener()
                            presenter.closeDialog()
                        }
                    ))
                } else if (currentJob.state == VoxDownloadState.FAILED || currentJob.state == VoxDownloadState.CANCELLED) {
                    options.add(UiOptionItem.from(
                        context.getString(R.string.vox_download_retry),
                        { _ ->
                            cleanupListener()
                            if (coordinator.retryDownload(currentJob.downloadId)) {
                                VoxDownloadService.start(context, currentJob.downloadId)
                                showProgressDialog(context, currentJob.downloadId)
                            }
                        }
                    ))
                    options.add(UiOptionItem.from(
                        context.getString(R.string.vox_download_close),
                        { _ ->
                            cleanupListener()
                            presenter.closeDialog()
                        }
                    ))
                } else {
                    options.add(UiOptionItem.from(
                        context.getString(R.string.vox_download_cancel_job),
                        { _ ->
                            cleanupListener()
                            AppDialogUtil.showConfirmationDialog(
                                context,
                                context.getString(R.string.vox_download_cancel_confirm),
                                {
                                    cleanupListener()
                                    VoxDownloadService.cancel(context, downloadId)
                                    presenter.closeDialog()
                                }
                            )
                        }
                    ))
                    options.add(UiOptionItem.from(
                        context.getString(R.string.vox_download_close),
                        { _ ->
                            cleanupListener()
                            presenter.closeDialog()
                        }
                    ))
                }

                options.forEach { presenter.appendSingleButton(it) }
                if (hasRendered) {
                    presenter.refreshDialog(displayTitle)
                } else {
                    hasRendered = true
                    presenter.showDialog(displayTitle, Runnable { cleanupListener() })
                }
            }
        }
    }

    val listener = object : VoxDownloadListener {
        override fun onProgressUpdated(progress: VoxDownloadProgress) {
            if (!isShowing) return
            mainHandler.post {
                renderDialogFunc()
            }
        }

        override fun onStateChanged(progress: VoxDownloadProgress) {
            mainHandler.post {
                if (isShowing) {
                    renderDialogFunc()
                }
                if (progress.state == VoxDownloadState.COMPLETED) cleanupListener()
            }
        }

        override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) {
            mainHandler.post {
                if (isShowing) {
                    renderDialogFunc()
                }
            }
        }
    }

    listenerRef.set(listener)
    coordinator.addListener(downloadId, listener)

    renderDialogFunc()
}

    private fun getStageTitle(context: Context, progress: VoxDownloadProgress): String {
        return when (progress.state) {
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
            else -> context.getString(R.string.vox_download_title)
        }
    }

    private fun getErrorMessage(context: Context, code: VoxDownloadErrorCode): String {
        return when (code) {
            VoxDownloadErrorCode.AUTH_REQUIRED -> context.getString(R.string.vox_download_error_auth)
            VoxDownloadErrorCode.INSUFFICIENT_STORAGE -> context.getString(R.string.vox_download_error_storage)
            VoxDownloadErrorCode.STREAM_UNAVAILABLE -> context.getString(R.string.vox_download_error_stream)
            VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE -> context.getString(R.string.vox_download_error_trans)
            VoxDownloadErrorCode.UNSUPPORTED_CODEC -> context.getString(R.string.vox_download_error_codec)
            VoxDownloadErrorCode.MEDIA_PARSE_ERROR -> context.getString(R.string.vox_download_error_parse)
            VoxDownloadErrorCode.NETWORK_ERROR -> context.getString(R.string.vox_download_error_network)
            VoxDownloadErrorCode.CANCELLED -> context.getString(R.string.vox_download_stage_cancelled)
            else -> context.getString(R.string.vox_download_error_unknown)
        }
    }
}

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
import java.util.concurrent.Executors

/** Нативный для SmartTube экран загрузок в диалоге настроек. */
object VoxDownloadManager {
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    @JvmStatic
    fun show(context: Context) {
        if (!VoxDownloadDialogHelper.isSupported(context)) return
        val presenter = AppDialogPresenter.instance(context)
        if (presenter.isDialogShown) {
            presenter.closeDialog()
            main.postDelayed({ Session(context).open() }, 250L)
        } else {
            Session(context).open()
        }
    }

    private class Session(private val context: Context) : VoxDownloadListener {
        private val coordinator = VoxDownloadCoordinator.instance(context)
        private val presenter = AppDialogPresenter.instance(context)
        private var closed = false
        private var queued = false
        private var lastSignature = ""
        private val lastProgressBucket = mutableMapOf<String, Int>()
        private val finish = Runnable { close() }

        fun open() {
            coordinator.addGlobalListener(this)
            presenter.appendSingleButton(UiOptionItem.from(context.getString(R.string.header_downloaded_videos), { _ -> }))
            presenter.showDialog(context.getString(R.string.header_downloaded_videos), finish)
            refresh()
        }

        private fun close() {
            if (closed) return
            closed = true
            coordinator.removeGlobalListener(this)
        }

        private fun refresh() {
            if (closed || queued) return
            queued = true
            background.execute {
                val jobs = VoxDownloadUiMapper.sorted(coordinator.getAllJobs())
                val missing = jobs.filter { it.state == VoxDownloadState.COMPLETED }
                    .associate { it.downloadId to !coordinator.isPublishedFileAvailable(it) }
                val items = jobs.map { VoxDownloadUiMapper.toItem(it, missing[it.downloadId] == true) }
                val stats = VoxDownloadStorageStats.collect(context.applicationContext, VoxDownloadStorage(context.applicationContext), jobs)
                main.post {
                    queued = false
                    if (!closed) render(items, stats)
                }
            }
        }

        private fun render(items: List<VoxDownloadListItem>, stats: VoxDownloadStorageSummary) {
            // Штатный диалог открывает группу во вложенном экране. Не сбрасываем фокус
            // и стек навигации, пока пользователь выбирает конкретную запись.
            if (presenter.view?.canGoBack() == true) return
            val signature = items.joinToString("|") { "${it.downloadId}:${it.state}:${it.percent}:${it.missingFile}" } + ":${stats.usedBytes}"
            if (signature == lastSignature) return
            lastSignature = signature
            val groups = listOf(0 to "Скачиваются", 1 to "Приостановлены", 2 to "Ошибки", 3 to "Готово", 4 to "Отменено")
            if (items.isEmpty()) {
                presenter.appendSingleButton(UiOptionItem.from(context.getString(R.string.vox_download_empty_title), { _ -> }))
                presenter.appendSingleButton(UiOptionItem.from(context.getString(R.string.vox_download_empty_hint), { _ -> }))
            } else {
                for ((group, heading) in groups) {
                    val groupItems = items.filter { VoxDownloadUiMapper.group(it.state) == group }
                    if (groupItems.isEmpty()) continue
                    val options = mutableListOf<OptionItem>()
                    for (item in groupItems) {
                        val detail = when {
                            item.missingFile -> "Файл удалён"
                            item.state == VoxDownloadState.FAILED -> coordinator.getJob(item.downloadId)?.let { VoxDownloadUiMapper.error(it.errorCode) } ?: "Ошибка"
                            item.state == VoxDownloadState.COMPLETED -> listOfNotNull(item.actualQuality, item.translationMode).joinToString(" · ")
                            item.percent != null && group == 0 -> "${item.stage} · ${item.percent}%"
                            else -> item.stage
                        }
                        options.add(UiOptionItem.from("${item.title} — $detail", { _ -> showActions(item) }))
                    }
                    presenter.appendStringsCategory(heading, options)
                }
            }
            presenter.appendSingleButton(UiOptionItem.from(
                "Занято загрузками: ${VoxDownloadUiMapper.formatSize(stats.usedBytes)} · Свободно: ${VoxDownloadUiMapper.formatSize(stats.freeBytes)}",
                { _ -> }))
            presenter.refreshDialog(context.getString(R.string.header_downloaded_videos))
        }

        private fun showActions(item: VoxDownloadListItem) {
            close()
            val job = coordinator.getJob(item.downloadId) ?: run { show(context); return }
            val options = mutableListOf<OptionItem>()
            for (action in VoxDownloadUiMapper.actions(job.state, item.missingFile)) {
                options.add(UiOptionItem.from(action, { _ -> perform(job, item.missingFile, action) }))
            }
            presenter.appendStringsCategory(item.title, options)
            presenter.showDialog(item.title)
        }

        private fun perform(job: VoxDownloadJob, missing: Boolean, action: String) {
            when (action) {
                "Показать прогресс" -> VoxDownloadDialogHelper.showProgressDialog(context, job.downloadId)
                "Открыть" -> {
                    presenter.closeDialog()
                    if (!missing && coordinator.isPublishedFileAvailable(job)) VoxLocalPlayerHelper.playJob(context, job)
                    else MessageHelpers.showMessage(context, "Файл удалён")
                }
                "Продолжить" -> {
                    VoxDownloadService.resume(context, job.downloadId)
                    show(context)
                }
                "Повторить" -> {
                    coordinator.deleteDownload(job.downloadId)
                    val id = coordinator.startDownload(job.request.copy(downloadId = java.util.UUID.randomUUID().toString()))
                    VoxDownloadService.start(context, id)
                    show(context)
                }
                "Скачать заново" -> {
                    val video = Video.from(job.request.videoId)
                    video.title = job.request.videoTitle
                    VoxDownloadDialogHelper.showDownloadOptionsDialog(context, video)
                }
                "Отменить загрузку" -> confirm("Отменить скачивание? Загрузка будет остановлена, временные файлы удалены.") {
                    VoxDownloadService.cancel(context, job.downloadId)
                    show(context)
                }
                "Удалить", "Удалить запись" -> {
                    if (VoxDownloadServicePolicy.isActiveState(job.state) || coordinator.isJobActive(job.downloadId)) {
                        confirm("Сначала отменить загрузку?") { VoxDownloadService.cancel(context, job.downloadId); show(context) }
                    } else {
                        val title = if (job.state == VoxDownloadState.COMPLETED && !missing) "Удалить скачанное видео?" else "Удалить запись о загрузке?"
                        confirm(title) {
                            if (!coordinator.deleteDownload(job.downloadId)) MessageHelpers.showMessage(context, "Не удалось удалить загрузку")
                            show(context)
                        }
                    }
                }
            }
        }

        private fun confirm(title: String, action: () -> Unit) {
            presenter.appendStringsCategory(title, listOf(
                UiOptionItem.from("Назад", { _ -> presenter.goBack() }),
                UiOptionItem.from(if (title.startsWith("Сначала")) "Отменить загрузку" else if (title.startsWith("Удалить")) "Удалить" else "Отменить загрузку", { _ -> action() })
            ))
            presenter.showDialog(title)
        }

        override fun onStateChanged(progress: VoxDownloadProgress) { main.post { refresh() } }
        override fun onProgressUpdated(progress: VoxDownloadProgress) {
            // Не перестраиваем TV-список на каждом сетевом пакете.
            val percent = progress.overallPercent
            if (percent != null) main.post {
                val bucket = percent / 10
                if (lastProgressBucket.put(progress.downloadId, bucket) != bucket) refresh()
            }
        }
        override fun onError(downloadId: String, errorCode: VoxDownloadErrorCode, message: String) { main.post { refresh() } }
    }
}

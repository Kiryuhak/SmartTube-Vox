package com.liskovsoft.smartyoutubetv2.common.vox.ui

import android.view.View
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Единый координатор всплывающих статусов и оверлеев в правом нижнем углу экрана.
 *
 * Предотвращает наложение плашек «Перевод готов», «Упаковка файла», оверлеев скачивания и т.д.
 * Скачивание (DOWNLOAD) имеет строгий приоритет над онлайн-переводом (TRANSLATION).
 * Если в процессе скачивания выполняется подготовка перевода — отдельная плашка перевода
 * подавляется, так как прогресс отображается внутри единого оверлея скачивания.
 */
object VoxTransientStatusCoordinator {

    enum class StatusOwner(val priority: Int) {
        NONE(0),
        TRANSLATION(VoxStatusHost.TRANSLATION),
        DOWNLOAD(VoxStatusHost.DOWNLOAD),
        SYSTEM(3)
    }

    private val isDownloadWorkflowActive = AtomicBoolean(false)
    private var currentActiveOwner: StatusOwner = StatusOwner.NONE

    @Synchronized
    @JvmStatic
    fun notifyDownloadActive(active: Boolean) {
        isDownloadWorkflowActive.set(active)
        if (!active && currentActiveOwner == StatusOwner.DOWNLOAD) {
            currentActiveOwner = StatusOwner.NONE
        }
    }

    @Synchronized
    @JvmStatic
    fun isDownloadActive(): Boolean {
        return isDownloadWorkflowActive.get() || VoxStatusHost.isDownloadActive()
    }

    @Synchronized
    @JvmStatic
    fun shouldSuppressTranslationToast(): Boolean {
        return isDownloadActive()
    }

    @Synchronized
    @JvmStatic
    fun canPresent(owner: StatusOwner): Boolean {
        if (owner == StatusOwner.TRANSLATION && isDownloadActive()) {
            return false
        }
        return true
    }

    @Synchronized
    @JvmStatic
    fun claim(view: View, owner: StatusOwner): Boolean {
        if (!canPresent(owner)) {
            view.animate()?.cancel()
            view.visibility = View.GONE
            view.alpha = 0f
            return false
        }

        val claimed = VoxStatusHost.claim(view, owner.priority)
        if (claimed) {
            currentActiveOwner = owner
            if (owner == StatusOwner.DOWNLOAD) {
                isDownloadWorkflowActive.set(true)
            }
        }
        return claimed
    }

    @Synchronized
    @JvmStatic
    fun release(view: View, owner: StatusOwner) {
        VoxStatusHost.release(view)
        if (currentActiveOwner == owner) {
            currentActiveOwner = StatusOwner.NONE
            if (owner == StatusOwner.DOWNLOAD) {
                isDownloadWorkflowActive.set(false)
            }
        }
    }

    @Synchronized
    @JvmStatic
    fun clear() {
        VoxStatusHost.clear()
        currentActiveOwner = StatusOwner.NONE
        isDownloadWorkflowActive.set(false)
    }

    @Synchronized
    @JvmStatic
    fun getActiveOwner(): StatusOwner {
        return currentActiveOwner
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger

/**
 * Стадии отображения виджета скачивания строго по золотому эталону (Prompt #028 Mockup):
 * 1. SELECTING: Одно стилизованное модальное окно выбора параметров.
 * 2. DOWNLOADING_EXPANDED: Развернутая панель 96dp в правом нижнем углу.
 * 3. DOWNLOADING_COMPACT: Компактный круглый виджет 64x64 dp с кольцом прогресса.
 * 4. REOPENED: Повторное раскрытие панели по клику без паузы видео.
 * 5. LONG_PRESS_MENU: Контекстное меню по долгому нажатию ("Продолжить" / "Отменить").
 * 6. COMPLETED: Зеленая галочка #00C853 "Скачано" с автоскрытием.
 * Дополнительно: PAUSED, ERROR, CANCELLED, HIDDEN.
 */
enum class VoxBadgeStage {
    HIDDEN,
    SELECTING,
    DOWNLOADING_EXPANDED,
    DOWNLOADING_COMPACT,
    REOPENED,
    LONG_PRESS_MENU,
    PAUSED,
    COMPLETED,
    CANCELLED,
    ERROR;

    val isVisible: Boolean
        get() = this != HIDDEN
}

class VoxDownloadBadgeStateMachine {

    interface StateListener {
        fun onStageChanged(oldStage: VoxBadgeStage, newStage: VoxBadgeStage)
    }

    private var currentStage: VoxBadgeStage = VoxBadgeStage.HIDDEN
    private val listeners = mutableListOf<StateListener>()
    private var activeDownloadId: String? = null

    fun getStage(): VoxBadgeStage = currentStage
    fun getActiveDownloadId(): String? = activeDownloadId

    fun setActiveDownloadId(id: String?) {
        this.activeDownloadId = id
    }

    fun addListener(listener: StateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun transitionTo(newStage: VoxBadgeStage): Boolean {
        if (currentStage == newStage) return false

        // Проверяем допустимость переходов согласно конечному автомату
        val allowed = when (newStage) {
            VoxBadgeStage.HIDDEN -> true
            VoxBadgeStage.SELECTING -> currentStage == VoxBadgeStage.HIDDEN || currentStage == VoxBadgeStage.COMPLETED || currentStage == VoxBadgeStage.ERROR || currentStage == VoxBadgeStage.CANCELLED
            VoxBadgeStage.DOWNLOADING_EXPANDED -> currentStage == VoxBadgeStage.SELECTING || currentStage == VoxBadgeStage.HIDDEN || currentStage == VoxBadgeStage.DOWNLOADING_COMPACT || currentStage == VoxBadgeStage.REOPENED || currentStage == VoxBadgeStage.PAUSED
            VoxBadgeStage.DOWNLOADING_COMPACT -> currentStage == VoxBadgeStage.DOWNLOADING_EXPANDED || currentStage == VoxBadgeStage.REOPENED || currentStage == VoxBadgeStage.LONG_PRESS_MENU || currentStage == VoxBadgeStage.PAUSED
            VoxBadgeStage.REOPENED -> currentStage == VoxBadgeStage.DOWNLOADING_COMPACT || currentStage == VoxBadgeStage.LONG_PRESS_MENU || currentStage == VoxBadgeStage.PAUSED
            VoxBadgeStage.LONG_PRESS_MENU -> currentStage == VoxBadgeStage.DOWNLOADING_COMPACT || currentStage == VoxBadgeStage.DOWNLOADING_EXPANDED || currentStage == VoxBadgeStage.PAUSED
            VoxBadgeStage.PAUSED -> currentStage == VoxBadgeStage.DOWNLOADING_EXPANDED || currentStage == VoxBadgeStage.DOWNLOADING_COMPACT || currentStage == VoxBadgeStage.REOPENED || currentStage == VoxBadgeStage.LONG_PRESS_MENU
            VoxBadgeStage.COMPLETED -> currentStage != VoxBadgeStage.HIDDEN
            VoxBadgeStage.CANCELLED -> true
            VoxBadgeStage.ERROR -> true
        }

        if (!allowed) {
            VoxSafeLogger.w(
                VoxLogCategory.UI,
                VoxLogCode.DOWNLOAD_FAILED,
                "Invalid badge transition rejected: $currentStage -> $newStage"
            )
            return false
        }

        val oldStage = currentStage
        currentStage = newStage

        VoxSafeLogger.d(
            VoxLogCategory.UI,
            VoxLogCode.DOWNLOAD_BADGE_STATE,
            "Badge transition: $oldStage -> $newStage (downloadId=$activeDownloadId)"
        )

        for (listener in listeners) {
            try {
                listener.onStageChanged(oldStage, newStage)
            } catch (ignored: Exception) {
            }
        }

        return true
    }

    fun reset() {
        transitionTo(VoxBadgeStage.HIDDEN)
        activeDownloadId = null
    }
}

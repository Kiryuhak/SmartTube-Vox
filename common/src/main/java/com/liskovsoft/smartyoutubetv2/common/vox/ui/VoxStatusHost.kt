package com.liskovsoft.smartyoutubetv2.common.vox.ui

import android.view.View
import java.lang.ref.WeakReference

/**
 * Единый арбитр transient-статусов SmartTube VOX.
 * Гарантирует, что в один момент времени на экране видна максимум ОДНА статусная плашка.
 * Загрузка (DOWNLOAD = 2) имеет строгий приоритет над онлайн-переводом (TRANSLATION = 1).
 */
object VoxStatusHost {
    const val TRANSLATION = 1
    const val DOWNLOAD = 2

    private var activeCard: WeakReference<View>? = null
    private var activePriority: Int = 0

    @Synchronized
    @JvmStatic
    fun claim(card: View, priority: Int): Boolean {
        val currentView = activeCard?.get()
        if (currentView != null && currentView !== card && currentView.visibility == View.VISIBLE) {
            if (activePriority > priority) {
                // Текущая плашка имеет более высокий приоритет (например, активна загрузка):
                // подавляем отображение низкоприоритетной плашки
                card.animate().cancel()
                card.visibility = View.GONE
                card.alpha = 0f
                return false
            }
            // Новая плашка имеет равный или более высокий приоритет:
            // скрываем и отменяем анимации предыдущей плашки
            currentView.animate().cancel()
            currentView.visibility = View.GONE
            currentView.alpha = 0f
        }
        activeCard = WeakReference(card)
        activePriority = priority
        return true
    }

    @Synchronized
    @JvmStatic
    fun isDownloadActive(): Boolean {
        val currentView = activeCard?.get()
        return activePriority >= DOWNLOAD && currentView != null && currentView.visibility == View.VISIBLE
    }

    @Synchronized
    @JvmStatic
    fun release(card: View) {
        if (activeCard?.get() === card) {
            activeCard = null
            activePriority = 0
        }
    }

    @Synchronized
    @JvmStatic
    fun clear() {
        activeCard?.get()?.let {
            it.animate().cancel()
            it.visibility = View.GONE
            it.alpha = 0f
        }
        activeCard = null
        activePriority = 0
    }
}

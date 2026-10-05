package com.liskovsoft.smartyoutubetv2.common.vox.ui

import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Один слот transient-статуса на окно. Загрузка имеет приоритет над онлайн-переводом. */
object VoxStatusHost {
    const val TRANSLATION = 1
    const val DOWNLOAD = 2
    private data class Slot(val view: WeakReference<View>, val priority: Int)
    private val slots = WeakHashMap<View, Slot>()

    @JvmStatic fun claim(card: View, priority: Int): Boolean {
        val window = card.rootView
        val previous = slots[window]
        val old = previous?.view?.get()
        if (old != null && old !== card && old.visibility == View.VISIBLE) {
            if (previous.priority > priority) {
                card.animate().cancel()
                card.visibility = View.GONE
                return false
            }
            old.animate().cancel()
            old.visibility = View.GONE
        }
        slots[window] = Slot(WeakReference(card), priority)
        return true
    }

    @JvmStatic fun release(card: View) {
        val window = card.rootView
        if (slots[window]?.view?.get() === card) slots.remove(window)
    }
}

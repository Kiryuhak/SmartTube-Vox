package com.liskovsoft.smartyoutubetv2.common.vox.ui

/**
 * Политика плавной бегущей строки (marquee) для длинных заголовков бокового меню на TV.
 *
 * Правила:
 * - Текст прокручивается ТОЛЬКО при получении фокуса/выбора и ТОЛЬКО если он не помещается целиком (обрезан).
 * - Старт происходит с плавной задержкой 600мс, чтобы не мерцать при быстрой навигации D-Pad.
 * - При потере фокуса прокрутка мгновенно останавливается, а позиция сбрасывается к началу строки.
 */
object VoxSidebarMarqueePolicy {
    const val MARQUEE_START_DELAY_MS = 600L
    const val MARQUEE_SPEED_FACTOR = 1.0f

    @JvmStatic
    fun shouldStartMarquee(
        isFocusedOrSelected: Boolean,
        isTextClipped: Boolean,
        isAttached: Boolean
    ): Boolean {
        return isFocusedOrSelected && isTextClipped && isAttached
    }
}

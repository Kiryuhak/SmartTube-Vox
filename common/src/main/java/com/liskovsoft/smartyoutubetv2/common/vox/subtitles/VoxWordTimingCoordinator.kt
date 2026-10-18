package com.liskovsoft.smartyoutubetv2.common.vox.subtitles

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * Координатор пословного тайминга и подсветки активного слова в субтитрах (VOT highlightWords).
 */
object VoxWordTimingCoordinator {

    const val DEFAULT_HIGHLIGHT_COLOR = 0xFFFFD54F.toInt() // Мягкий янтарный/жёлтый для ТВ

    data class HighlightInterval(
        val word: String,
        val startChar: Int,
        val endChar: Int
    )

    @JvmStatic
    fun findActiveToken(tokens: List<VoxSubtitleToken>, currentMediaTimeMs: Long): VoxSubtitleToken? {
        if (tokens.isEmpty()) return null
        for (token in tokens) {
            val tokenEnd = if (token.durationMs > 0) token.endMs else token.startMs + 500L
            if (currentMediaTimeMs >= token.startMs && currentMediaTimeMs < tokenEnd) {
                return token
            }
        }
        return null
    }

    @JvmStatic
    fun findTokenSpan(fullText: String, tokenText: String): HighlightInterval? {
        val cleanToken = VoxSmartSubtitleLayout.trimEdgePunctuation(tokenText)
        if (cleanToken.isEmpty()) return null

        val idx = fullText.indexOf(cleanToken, ignoreCase = true)
        if (idx >= 0) {
            return HighlightInterval(cleanToken, idx, idx + cleanToken.length)
        }
        return null
    }

    @JvmStatic
    fun formatWithHighlight(
        item: VoxSubtitleItem,
        currentMediaTimeMs: Long,
        highlightWordsEnabled: Boolean,
        smartLayoutEnabled: Boolean = true,
        highlightColor: Int = DEFAULT_HIGHLIGHT_COLOR
    ): CharSequence {
        val baseText = VoxSmartSubtitleLayout.formatSubtitleText(item.text, smartLayoutEnabled)
        if (!highlightWordsEnabled || item.tokens.isEmpty()) {
            return baseText
        }

        val activeToken = findActiveToken(item.tokens, currentMediaTimeMs) ?: return baseText
        val interval = findTokenSpan(baseText, activeToken.text) ?: return baseText

        return try {
            val spannable = SpannableString(baseText)
            spannable.setSpan(
                ForegroundColorSpan(highlightColor),
                interval.startChar,
                interval.endChar,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable
        } catch (e: Throwable) {
            // Headless JVM test fallback: возвращаем чистый текст без краша
            baseText
        }
    }
}

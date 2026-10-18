package com.liskovsoft.smartyoutubetv2.common.vox.subtitles

import java.util.regex.Pattern

/**
 * Smart Subtitle Layout:
 * Реализация форматирования субтитров из reference userscript 1.11.17 (subtitlesSmartLayout).
 * Включает:
 * - trimEdgePunctuation
 * - пунктуационно-осознанный перенос строк
 * - сбалансированное разбиение на 2 строки для телевизионных экранов
 * - предотвращение висячих коротких слов (orphans)
 */
object VoxSmartSubtitleLayout {

    private val EDGE_PUNCTUATION_OR_SYMBOL = Pattern.compile("^[\\p{P}\\p{S}]$")
    private val CLAUSE_PUNCTUATION = setOf(',', ';', ':', '—', '-', '–')
    private val SENTENCE_PUNCTUATION = setOf('.', '!', '?')

    const val DEFAULT_MAX_LINE_LENGTH = 45 // Оптимальная длина строки для ТВ экрана

    @JvmStatic
    fun isEdgePunctuationOrSymbol(char: Char): Boolean {
        return EDGE_PUNCTUATION_OR_SYMBOL.matcher(char.toString()).matches()
    }

    @JvmStatic
    fun trimEdgePunctuation(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        var startIndex = 0
        var endIndex = trimmed.length
        while (startIndex < endIndex && isEdgePunctuationOrSymbol(trimmed[startIndex])) {
            startIndex++
        }
        while (endIndex > startIndex && isEdgePunctuationOrSymbol(trimmed[endIndex - 1])) {
            endIndex--
        }
        return if (startIndex < endIndex) trimmed.substring(startIndex, endIndex).trim() else ""
    }

    /**
     * Форматирует текст субтитра с применением Smart Layout.
     * @param rawText исходный текст субтитра
     * @param smartLayoutEnabled флаг включения умного макета
     * @param maxLineLength максимальная длина одной строки
     */
    @JvmStatic
    fun formatSubtitleText(
        rawText: String,
        smartLayoutEnabled: Boolean = true,
        maxLineLength: Int = DEFAULT_MAX_LINE_LENGTH
    ): String {
        val clean = rawText.trim().replace(Regex("\\s+"), " ")
        if (!smartLayoutEnabled || clean.length <= maxLineLength) {
            return clean
        }

        // Если текст уже содержит явный перевод строки и строки сбалансированы:
        if (clean.contains("\n")) {
            return clean
        }

        val words = clean.split(" ")
        if (words.size <= 2) {
            return clean
        }

        // Ищем наилучшую точку разделения около середины текста
        val targetSplitIndex = clean.length / 2
        var bestBreakWordIndex = -1
        var bestScore = Int.MAX_VALUE

        var currentLength = 0
        for (i in 0 until words.size - 1) {
            currentLength += words[i].length + (if (i > 0) 1 else 0)
            val distFromCenter = Math.abs(currentLength - targetSplitIndex)
            val lastChar = words[i].lastOrNull() ?: ' '

            // Оценка точки разрыва (чем меньше score, тем лучше)
            var penalty = distFromCenter
            if (SENTENCE_PUNCTUATION.contains(lastChar)) {
                penalty -= 15 // Сильный приоритет концу предложения
            } else if (CLAUSE_PUNCTUATION.contains(lastChar)) {
                penalty -= 10 // Приоритет запятой/тире/двоеточию
            }

            // Штраф за висячее короткое слово на второй строке
            val nextWord = words[i + 1]
            if (nextWord.length <= 3 && i + 1 == words.size - 1) {
                penalty += 20
            }

            if (penalty < bestScore) {
                bestScore = penalty
                bestBreakWordIndex = i
            }
        }

        if (bestBreakWordIndex != -1) {
            val line1 = words.subList(0, bestBreakWordIndex + 1).joinToString(" ")
            val line2 = words.subList(bestBreakWordIndex + 1, words.size).joinToString(" ")
            return "$line1\n$line2"
        }

        return clean
    }
}

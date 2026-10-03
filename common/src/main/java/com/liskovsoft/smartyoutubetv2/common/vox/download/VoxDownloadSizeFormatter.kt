package com.liskovsoft.smartyoutubetv2.common.vox.download

import java.util.Locale

/**
 * Форматирует размеры файлов и прогресс скачивания в человекочитаемый вид
 * согласно правилам русской типографики (запятая как десятичный разделитель, неразрывный пробел перед единицей).
 */
object VoxDownloadSizeFormatter {

    private val RU_LOCALE = Locale("ru")
    private const val KB = 1024.0
    private const val MB = 1024.0 * 1024.0
    private const val GB = 1024.0 * 1024.0 * 1024.0

    /**
     * Форматирует байты в компактную строку размера: "245 МБ", "1,00 ГБ", "824 КБ".
     */
    @JvmStatic
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 Б"
        val d = bytes.toDouble()
        return when {
            d >= GB -> {
                val gb = d / GB
                val formatted = String.format(RU_LOCALE, "%.1f", gb)
                "$formatted ГБ"
            }
            d >= MB -> {
                val mb = d / MB
                if (bytes % (1024 * 1024) == 0L) {
                    "${(bytes / (1024 * 1024))} МБ"
                } else {
                    val formatted = String.format(RU_LOCALE, "%.1f", mb)
                    if (formatted.endsWith(",0")) {
                        "${formatted.substring(0, formatted.length - 2)} МБ"
                    } else {
                        "$formatted МБ"
                    }
                }
            }
            d >= KB -> {
                if (bytes % 1024 == 0L) {
                    "${(bytes / 1024)} КБ"
                } else {
                    val formatted = String.format(RU_LOCALE, "%.0f", d / KB)
                    "$formatted КБ"
                }
            }
            else -> "$bytes Б"
        }
    }

    /**
     * Форматирует прогресс в виде "245 МБ / 1,0 ГБ" или "245 МБ загружено" (если общий размер неизвестен).
     */
    @JvmStatic
    fun formatProgressBytes(downloadedBytes: Long, totalBytes: Long?): String {
        val downloadedStr = formatBytes(downloadedBytes.coerceAtLeast(0L))
        return if (totalBytes != null && totalBytes > 0L) {
            val totalStr = formatBytes(totalBytes)
            "$downloadedStr / $totalStr"
        } else {
            "$downloadedStr загружено"
        }
    }

    /**
     * Форматирует полный статус загрузки для карточки в каталоге:
     * - "Загрузка · 245 МБ / 1,0 ГБ · 24%"
     * - "Загрузка · 245 МБ загружено"
     */
    @JvmStatic
    fun formatActiveProgress(downloadedBytes: Long, totalBytes: Long?, percent: Int?): String {
        val bytesStr = formatProgressBytes(downloadedBytes, totalBytes)
        return if (percent != null && percent in 0..100) {
            "Загрузка · $bytesStr · $percent%"
        } else {
            "Загрузка · $bytesStr"
        }
    }
}

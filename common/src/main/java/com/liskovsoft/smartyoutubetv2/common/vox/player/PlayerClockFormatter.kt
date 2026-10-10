package com.liskovsoft.smartyoutubetv2.common.vox.player

import com.liskovsoft.sharedutils.helpers.DateHelper
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Изолированный форматтер времени окончания воспроизведения (Player Clock Formatter).
 * Гарантирует strict 2-значный формат часов с ведущим нулем (HH:mm, например "00:09", "01:25", "23:59")
 * и безопасный переход через полночь (midnight rollover).
 */
object PlayerClockFormatter {

    @JvmStatic
    @JvmOverloads
    fun formatEndingTime(timeMs: Long, is24Hour: Boolean = is24HourMode()): String {
        val date = Date(timeMs)
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(date)
    }

    @JvmStatic
    fun normalizeTimeString(timeString: String?): String? {
        if (timeString.isNullOrBlank()) return null
        val trimmed = timeString.trim()

        // Проверяем формат "H:mm" (одна цифра в часах, например "0:09" -> "00:09", "9:05" -> "09:05")
        val singleDigitHourRegex = Regex("""^(\d):(\d{2})$""")
        val match = singleDigitHourRegex.matchEntire(trimmed)
        if (match != null) {
            val (hour, minute) = match.destructured
            return "0$hour:$minute"
        }

        return trimmed
    }

    @JvmStatic
    fun is24HourMode(): Boolean {
        return if (GlobalPreferences.sInstance != null) {
            GlobalPreferences.sInstance.is24HourLocaleEnabled
        } else {
            DateHelper.is24HourLocale()
        }
    }
}

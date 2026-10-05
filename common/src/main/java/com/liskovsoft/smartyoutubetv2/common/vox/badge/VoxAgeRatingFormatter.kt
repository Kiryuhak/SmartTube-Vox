package com.liskovsoft.smartyoutubetv2.common.vox.badge

import java.util.Locale
import java.util.regex.Pattern

/**
 * Парсер и форматтер для возрастных ограничений (0+, 6+, 12+, 16+, 18+).
 * Строго опирается на подтверждённые метаданные.
 * При отсутствии или неизвестном рейтинге возвращает null (НИКОГДА не подставляет 0+ по умолчанию).
 */
object VoxAgeRatingFormatter {

    // Шаблоны для точных совпадений возрастных меток в текстах
    private val EXPLICIT_PLUS_PATTERN = Pattern.compile("(?:^|[^0-9])(0|6|12|16|18)(?:\\+|\\s*(?:плюс|plus))(?:[^0-9]|$)", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    private val EXPLICIT_YEARS_PATTERN = Pattern.compile("(?:^|[^0-9])(0|6|12|16|18)\\s*(?:лет|года?|years?|yo)(?:[^a-zA-Zа-яА-Я0-9]|$)", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)

    @JvmStatic
    fun parse(raw: String?): VoxAgeRating? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val upper = trimmed.uppercase(Locale.US)

        // Исключаем ложные срабатывания (просмотры, даты, статистика)
        if (upper.contains("VIEW") || upper.contains("ПРОСМОТР") || upper.contains("AGO") ||
            upper.contains("НАЗАД") || upper.contains("SUB") || upper == "UNKNOWN" || upper == "NONE") {
            return null
        }

        // 1. Быстрое точное совпадение
        when (upper) {
            "0+" -> return VoxAgeRating.AGE_0
            "6+" -> return VoxAgeRating.AGE_6
            "12+" -> return VoxAgeRating.AGE_12
            "16+" -> return VoxAgeRating.AGE_16
            "18+" -> return VoxAgeRating.AGE_18
            "TV-Y", "G", "ALL" -> return VoxAgeRating.AGE_0
            "TV-Y7", "TV-Y7-FV", "TV-PG", "PG" -> return VoxAgeRating.AGE_6
            "TV-14", "PG-13" -> return VoxAgeRating.AGE_12
            "TV-16" -> return VoxAgeRating.AGE_16
            "TV-MA", "NC-17", "R", "ADULT" -> return VoxAgeRating.AGE_18
        }

        // 2. Прямые метки со знаком '+' в строке (например "Movie · 16+ · 2024")
        val plusMatcher = EXPLICIT_PLUS_PATTERN.matcher(upper)
        if (plusMatcher.find()) {
            val age = plusMatcher.group(1)?.toIntOrNull()
            return fromAge(age)
        }

        // 3. Метки типа "18 лет", "16 years"
        val yearsMatcher = EXPLICIT_YEARS_PATTERN.matcher(trimmed)
        if (yearsMatcher.find()) {
            val age = yearsMatcher.group(1)?.toIntOrNull()
            return fromAge(age)
        }

        return null
    }

    @JvmStatic
    fun format(rating: VoxAgeRating?): String? {
        return rating?.label
    }

    @JvmStatic
    fun fromAge(age: Int?): VoxAgeRating? {
        if (age == null) return null
        return when (age) {
            0 -> VoxAgeRating.AGE_0
            in 1..11 -> if (age >= 6) VoxAgeRating.AGE_6 else VoxAgeRating.AGE_0
            in 12..15 -> VoxAgeRating.AGE_12
            in 16..17 -> VoxAgeRating.AGE_16
            in 18..120 -> VoxAgeRating.AGE_18
            else -> null
        }
    }

    @JvmStatic
    fun isAgeString(raw: String?): Boolean {
        return parse(raw) != null
    }
}

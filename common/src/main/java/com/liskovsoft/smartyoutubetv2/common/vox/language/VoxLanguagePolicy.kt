package com.liskovsoft.smartyoutubetv2.common.vox.language

import java.util.Locale

/**
 * Нормализация и правила языков перевода и субтитров.
 * Соответствует reference userscript 1.11.17 (normalizeToRequestLang, baseLang, langMatches, REQUEST_LANG_SET).
 */
object VoxLanguagePolicy {

    const val LANG_AUTO = "auto"
    const val LANG_ORIG = "orig"
    const val LANG_RU = "ru"

    val AVAILABLE_LANGS: List<String> = listOf(
        "auto",
        "ru",
        "en",
        "zh",
        "ko",
        "ar",
        "fr",
        "it",
        "es",
        "de",
        "ja"
    )

    val SUPPORTED_TRANSLATION_SOURCE_LANGS: Set<String> = setOf(
        "ru",
        "en",
        "zh",
        "ko",
        "fr",
        "it",
        "es",
        "de",
        "ja"
    )

    private val REQUEST_LANG_SET: Set<String> = AVAILABLE_LANGS.toSet()

    private val ISO_639_2_TO_1: Map<String, String> = mapOf(
        "rus" to "ru",
        "russian" to "ru",
        "eng" to "en",
        "english" to "en",
        "zho" to "zh",
        "chi" to "zh",
        "chinese" to "zh",
        "kor" to "ko",
        "korean" to "ko",
        "ara" to "ar",
        "arabic" to "ar",
        "fra" to "fr",
        "fre" to "fr",
        "french" to "fr",
        "ita" to "it",
        "italian" to "it",
        "spa" to "es",
        "spanish" to "es",
        "deu" to "de",
        "ger" to "de",
        "german" to "de",
        "jpn" to "ja",
        "japanese" to "ja",
        "ukr" to "uk",
        "ukrainian" to "uk",
        "kaz" to "kk",
        "kazakh" to "kk",
        "tur" to "tr",
        "turkish" to "tr",
        "vie" to "vi",
        "vietnamese" to "vi",
        "hin" to "hi",
        "hindi" to "hi"
    )

    @JvmStatic
    fun normalizeToRequestLang(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim().lowercase(Locale.US)
        if (trimmed == LANG_AUTO || trimmed == "und" || trimmed == "unknown") {
            return LANG_AUTO
        }
        val prefix = trimmed.split(Regex("[-_]"))[0]
        val isoMapped = ISO_639_2_TO_1[prefix] ?: ISO_639_2_TO_1[trimmed] ?: prefix
        return if (REQUEST_LANG_SET.contains(isoMapped)) isoMapped else null
    }

    @JvmStatic
    fun normalizeLang(lang: String?): String {
        return lang?.trim()?.lowercase(Locale.US) ?: ""
    }

    @JvmStatic
    fun baseLang(lang: String?): String {
        val norm = normalizeLang(lang)
        if (norm.isEmpty()) return ""
        val prefix = norm.split(Regex("[-_]"))[0]
        return ISO_639_2_TO_1[prefix] ?: prefix
    }

    @JvmStatic
    fun langMatches(candidate: String?, desired: String?): Boolean {
        if (candidate.isNullOrBlank() || desired.isNullOrBlank()) return false
        val cand = normalizeLang(candidate)
        val want = normalizeLang(desired)
        return cand == want || baseLang(cand) == baseLang(want)
    }

    @JvmStatic
    fun isResolvedLanguage(value: String?): Boolean {
        return !value.isNullOrBlank() && value != LANG_AUTO && value != "und" && value != "unknown"
    }

    @JvmStatic
    fun isSupportedTranslationSource(lang: String?): Boolean {
        val normalized = normalizeToRequestLang(lang) ?: return false
        return SUPPORTED_TRANSLATION_SOURCE_LANGS.contains(normalized)
    }

    /**
     * Проверка политики don't-translate:
     * - Исходный язык совпадает с целевым (не переводить русское видео на русский).
     * - Исходный язык находится в списке исключённых пользователем языков.
     */
    @JvmStatic
    fun shouldSkipAutoTranslate(
        detectedLang: String?,
        targetLang: String?,
        excludedLanguages: Set<String>?
    ): Boolean {
        if (!isResolvedLanguage(detectedLang)) {
            return false
        }
        val effectiveTarget = if (!targetLang.isNullOrBlank()) targetLang else LANG_RU
        if (langMatches(detectedLang, effectiveTarget)) {
            return true
        }
        if (excludedLanguages != null && excludedLanguages.isNotEmpty()) {
            val normDetected = baseLang(detectedLang)
            for (excluded in excludedLanguages) {
                if (langMatches(normDetected, excluded)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Разрешение предпочтительного языка субтитров (согласно resolveSubtitlesLanguage reference userscript).
     * @param preference "orig", "auto", или конкретный код языка
     * @param detectedLanguage определенный язык видео
     * @param responseLanguage язык перевода (например "ru")
     */
    @JvmStatic
    fun resolveSubtitlesLanguage(
        preference: String?,
        detectedLanguage: String?,
        responseLanguage: String?
    ): String? {
        val pref = normalizeLang(preference)
        if (pref == LANG_ORIG) {
            val original = normalizeLang(detectedLanguage)
            return if (isResolvedLanguage(original)) original else null
        }
        if (pref.isNotEmpty() && pref != LANG_AUTO) {
            return pref
        }
        val target = normalizeLang(responseLanguage)
        if (target.isNotEmpty() && target != LANG_AUTO) {
            return target
        }
        val detected = normalizeLang(detectedLanguage)
        return if (isResolvedLanguage(detected)) detected else null
    }
}

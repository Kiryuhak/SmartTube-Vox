package com.liskovsoft.smartyoutubetv2.common.vox.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VoxLanguagePolicy] validating 1:1 behavior with userscript 1.11.17
 * (normalizeToRequestLang, baseLang, langMatches, shouldSkipAutoTranslate, resolveSubtitlesLanguage).
 */
class VoxLanguagePolicyTest {

    @Test
    fun testNormalizeToRequestLang_validAndIsoMapped() {
        assertEquals("ru", VoxLanguagePolicy.normalizeToRequestLang("ru"))
        assertEquals("ru", VoxLanguagePolicy.normalizeToRequestLang("rus"))
        assertEquals("ru", VoxLanguagePolicy.normalizeToRequestLang("russian"))
        assertEquals("ru", VoxLanguagePolicy.normalizeToRequestLang("ru-RU"))

        assertEquals("en", VoxLanguagePolicy.normalizeToRequestLang("en"))
        assertEquals("en", VoxLanguagePolicy.normalizeToRequestLang("eng"))
        assertEquals("en", VoxLanguagePolicy.normalizeToRequestLang("en-US"))
        assertEquals("en", VoxLanguagePolicy.normalizeToRequestLang("en_GB"))

        assertEquals("zh", VoxLanguagePolicy.normalizeToRequestLang("zh"))
        assertEquals("zh", VoxLanguagePolicy.normalizeToRequestLang("zho"))
        assertEquals("zh", VoxLanguagePolicy.normalizeToRequestLang("chi"))
        assertEquals("zh", VoxLanguagePolicy.normalizeToRequestLang("zh-CN"))

        assertEquals("de", VoxLanguagePolicy.normalizeToRequestLang("de-DE"))
        assertEquals("es", VoxLanguagePolicy.normalizeToRequestLang("spa"))
        assertEquals("fr", VoxLanguagePolicy.normalizeToRequestLang("fra"))
        assertEquals("it", VoxLanguagePolicy.normalizeToRequestLang("ita"))
        assertEquals("ja", VoxLanguagePolicy.normalizeToRequestLang("jpn"))
        assertEquals("ko", VoxLanguagePolicy.normalizeToRequestLang("kor"))
    }

    @Test
    fun testNormalizeToRequestLang_autoAndUnd() {
        assertEquals(VoxLanguagePolicy.LANG_AUTO, VoxLanguagePolicy.normalizeToRequestLang("auto"))
        assertEquals(VoxLanguagePolicy.LANG_AUTO, VoxLanguagePolicy.normalizeToRequestLang("und"))
        assertEquals(VoxLanguagePolicy.LANG_AUTO, VoxLanguagePolicy.normalizeToRequestLang("unknown"))
    }

    @Test
    fun testNormalizeToRequestLang_unsupportedOrNull() {
        assertNull(VoxLanguagePolicy.normalizeToRequestLang(null))
        assertNull(VoxLanguagePolicy.normalizeToRequestLang(""))
        assertNull(VoxLanguagePolicy.normalizeToRequestLang("   "))
        // Unsupported source lang not in REQUEST_LANG_SET
        assertNull(VoxLanguagePolicy.normalizeToRequestLang("xyz"))
    }

    @Test
    fun testBaseLang() {
        assertEquals("en", VoxLanguagePolicy.baseLang("en-US"))
        assertEquals("ru", VoxLanguagePolicy.baseLang("ru-RU"))
        assertEquals("ru", VoxLanguagePolicy.baseLang("rus"))
        assertEquals("", VoxLanguagePolicy.baseLang(null))
        assertEquals("", VoxLanguagePolicy.baseLang(""))
    }

    @Test
    fun testLangMatches() {
        assertTrue(VoxLanguagePolicy.langMatches("en-US", "en"))
        assertTrue(VoxLanguagePolicy.langMatches("en", "en-GB"))
        assertTrue(VoxLanguagePolicy.langMatches("ru-RU", "rus"))
        assertTrue(VoxLanguagePolicy.langMatches("ru", "ru"))

        assertFalse(VoxLanguagePolicy.langMatches("en", "ru"))
        assertFalse(VoxLanguagePolicy.langMatches(null, "en"))
        assertFalse(VoxLanguagePolicy.langMatches("en", null))
    }

    @Test
    fun testShouldSkipAutoTranslate_sourceMatchesTarget() {
        // Russian video to Russian translation must be skipped
        assertTrue(VoxLanguagePolicy.shouldSkipAutoTranslate("ru", "ru", emptySet()))
        assertTrue(VoxLanguagePolicy.shouldSkipAutoTranslate("rus", "ru", emptySet()))
        assertTrue(VoxLanguagePolicy.shouldSkipAutoTranslate("ru-RU", "ru", emptySet()))

        // English video to Russian translation should NOT be skipped
        assertFalse(VoxLanguagePolicy.shouldSkipAutoTranslate("en", "ru", emptySet()))
        assertFalse(VoxLanguagePolicy.shouldSkipAutoTranslate("en-US", "ru", emptySet()))
    }

    @Test
    fun testShouldSkipAutoTranslate_userExcludedLanguages() {
        val excluded = setOf("en", "de")
        // English is in excluded list -> skip auto translate
        assertTrue(VoxLanguagePolicy.shouldSkipAutoTranslate("en", "ru", excluded))
        assertTrue(VoxLanguagePolicy.shouldSkipAutoTranslate("en-US", "ru", excluded))
        assertTrue(VoxLanguagePolicy.shouldSkipAutoTranslate("de", "ru", excluded))

        // Spanish is NOT in excluded list -> do not skip
        assertFalse(VoxLanguagePolicy.shouldSkipAutoTranslate("es", "ru", excluded))
        assertFalse(VoxLanguagePolicy.shouldSkipAutoTranslate("fr", "ru", excluded))
    }

    @Test
    fun testShouldSkipAutoTranslate_unresolvedLanguage() {
        // If language is unresolved (auto / und), don't skip; let detection / backend resolve it
        assertFalse(VoxLanguagePolicy.shouldSkipAutoTranslate("auto", "ru", setOf("en")))
        assertFalse(VoxLanguagePolicy.shouldSkipAutoTranslate(null, "ru", setOf("en")))
        assertFalse(VoxLanguagePolicy.shouldSkipAutoTranslate("und", "ru", setOf("en")))
    }

    @Test
    fun testResolveSubtitlesLanguage() {
        // 1. Preference "orig" resolves to detected language
        assertEquals("en", VoxLanguagePolicy.resolveSubtitlesLanguage("orig", "en", "ru"))
        assertEquals("ja", VoxLanguagePolicy.resolveSubtitlesLanguage("orig", "ja", "ru"))
        assertNull(VoxLanguagePolicy.resolveSubtitlesLanguage("orig", "auto", "ru"))

        // 2. Preference specific language overrides default
        assertEquals("fr", VoxLanguagePolicy.resolveSubtitlesLanguage("fr", "en", "ru"))
        assertEquals("es", VoxLanguagePolicy.resolveSubtitlesLanguage("es", "en", "ru"))

        // 3. Preference "auto" or empty falls back to target response lang
        assertEquals("ru", VoxLanguagePolicy.resolveSubtitlesLanguage("auto", "en", "ru"))
        assertEquals("ru", VoxLanguagePolicy.resolveSubtitlesLanguage(null, "en", "ru"))
        assertEquals("ru", VoxLanguagePolicy.resolveSubtitlesLanguage("", "en", "ru"))

        // 4. If target response lang is auto/empty, fall back to detected
        assertEquals("en", VoxLanguagePolicy.resolveSubtitlesLanguage(null, "en", "auto"))
    }
}

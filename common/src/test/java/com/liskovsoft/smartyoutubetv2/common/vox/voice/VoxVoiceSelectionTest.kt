package com.liskovsoft.smartyoutubetv2.common.vox.voice

import org.junit.Assert.*
import org.junit.Test

class VoxVoiceSelectionTest {

    @Test
    fun testVoiceCatalog_DefaultAndAllVoices() {
        val voices = VoxVoiceCatalog.getVoices()
        assertEquals(2, voices.size)

        val standard = VoxVoiceCatalog.getVoiceById(VoxVoiceId.STANDARD)
        assertEquals(VoxVoiceId.STANDARD, standard.id)
        assertFalse(standard.isLively)
        assertFalse(standard.requiresAccount)

        val live = VoxVoiceCatalog.getVoiceById(VoxVoiceId.LIVE)
        assertEquals(VoxVoiceId.LIVE, live.id)
        assertTrue(live.isLively)
        assertTrue(live.requiresAccount)

        val fallback = VoxVoiceCatalog.getVoiceById("unknown_voice")
        assertEquals(VoxVoiceId.STANDARD, fallback.id)

        val defaultVoice = VoxVoiceCatalog.getDefaultVoice()
        assertEquals(VoxVoiceId.STANDARD, defaultVoice.id)
    }

    @Test
    fun testGetRequestLangForTranslation() {
        // Under VOT 1.12.4 parity, auto -> ru remains auto (never forced to en)
        assertEquals("auto", VoxVoiceSelectionPolicy.getRequestLangForTranslation("auto", "ru", useLivelyVoice = true))
        assertEquals("auto", VoxVoiceSelectionPolicy.getRequestLangForTranslation("", "ru", useLivelyVoice = true))
        assertEquals("auto", VoxVoiceSelectionPolicy.getRequestLangForTranslation(null, "ru", useLivelyVoice = true))

        // When lively voice is disabled, auto -> ru remains auto
        assertEquals("auto", VoxVoiceSelectionPolicy.getRequestLangForTranslation("auto", "ru", useLivelyVoice = false))

        // Concrete source language remains unchanged
        assertEquals("de", VoxVoiceSelectionPolicy.getRequestLangForTranslation("de", "ru", useLivelyVoice = true))
        assertEquals("en", VoxVoiceSelectionPolicy.getRequestLangForTranslation("en", "ru", useLivelyVoice = true))
        assertEquals("es", VoxVoiceSelectionPolicy.getRequestLangForTranslation("es", "ru", useLivelyVoice = true))
    }

    @Test
    fun testIsLivelyVoiceAllowed_LanguagePairAndAccount() {
        // Allowed: concrete language (en, fr, es) -> ru with active account
        assertTrue(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("en", "ru", hasActiveAccount = true))
        assertTrue(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("fr", "ru", hasActiveAccount = true))
        assertTrue(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("es", "ru", hasActiveAccount = true))

        // Disallowed: auto source language (per VOT 1.12.4 lines 48517-48520)
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("auto", "ru", hasActiveAccount = true))
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("", "ru", hasActiveAccount = true))
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed(null, "ru", hasActiveAccount = true))

        // Disallowed: no active account
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("en", "ru", hasActiveAccount = false))

        // Disallowed: non-Russian target language
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("en", "de", hasActiveAccount = true))
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("en", "en", hasActiveAccount = true))
    }

    @Test
    fun testIsLivelyVoiceUnavailableError() {
        assertTrue(VoxVoiceSelectionPolicy.isLivelyVoiceUnavailableError("Для этого видео доступна только обычная озвучка"))
        assertTrue(VoxVoiceSelectionPolicy.isLivelyVoiceUnavailableError("Fallback to standard voice"))
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceUnavailableError("Connection timeout"))
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceUnavailableError(null))
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceUnavailableError(""))
    }

    @Test
    fun testCacheKey_StandardVsLivelyIsolation() {
        val standardKey = VoxVoiceSelectionPolicy.buildTranslationCacheKey("video123", "en", "ru", useLivelyVoice = false)
        val liveKey = VoxVoiceSelectionPolicy.buildTranslationCacheKey("video123", "en", "ru", useLivelyVoice = true)

        assertNotEquals(standardKey, liveKey)
        assertTrue(standardKey.contains("_false_"))
        assertTrue(liveKey.contains("_true_"))

        // For auto source, lively is disallowed, so cache key always reflects false for lively
        val autoKey = VoxVoiceSelectionPolicy.buildTranslationCacheKey("video456", "auto", "ru", useLivelyVoice = true)
        assertTrue(autoKey.contains("_auto_ru_false_"))
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationReusePolicyTest {

    @Test
    fun testNormalizeVideoUrl() {
        // Plain 11-char ID
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", VoxTranslationReusePolicy.normalizeVideoUrl("dQw4w9WgXcQ"))

        // Standard watch URL with parameters
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", VoxTranslationReusePolicy.normalizeVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))

        // youtu.be short URL
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", VoxTranslationReusePolicy.normalizeVideoUrl("https://youtu.be/dQw4w9WgXcQ?si=12345"))

        // Blank or null
        assertEquals("", VoxTranslationReusePolicy.normalizeVideoUrl(null))
        assertEquals("", VoxTranslationReusePolicy.normalizeVideoUrl("   "))
    }

    @Test
    fun testBuildCacheKeyConsistency() {
        val key1 = VoxTranslationReusePolicy.buildCacheKey(
            videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s",
            sourceLang = "en",
            targetLang = "ru",
            useLiveVoices = true
        )
        val key2 = VoxTranslationReusePolicy.buildCacheKey(
            videoUrl = "dQw4w9WgXcQ",
            sourceLang = "EN",
            targetLang = "RU",
            useLiveVoices = true
        )
        assertEquals(key1, key2)

        // Different live voice mode results in different key
        val key3 = VoxTranslationReusePolicy.buildCacheKey(
            videoUrl = "dQw4w9WgXcQ",
            sourceLang = "en",
            targetLang = "ru",
            useLiveVoices = false
        )
        assertFalse(key1 == key3)
    }

    @Test
    fun testIsReusable() {
        // Status 1 (FINISHED) and valid audioUrl -> true
        assertTrue(VoxTranslationReusePolicy.isReusable(1, "https://storage.yandex.net/audio.mp3"))

        // Status 1 but empty audioUrl -> false
        assertFalse(VoxTranslationReusePolicy.isReusable(1, ""))
        assertFalse(VoxTranslationReusePolicy.isReusable(1, null))

        // Status 0 (PENDING) -> false
        assertFalse(VoxTranslationReusePolicy.isReusable(0, "https://storage.yandex.net/audio.mp3"))

        // Status 2 (FAILED) -> false
        assertFalse(VoxTranslationReusePolicy.isReusable(2, null))
    }

    @Test
    fun testShouldSkipDuplicate() {
        // Pending request for same video -> skip duplicate
        assertTrue(VoxTranslationReusePolicy.shouldSkipDuplicate("dQw4w9WgXcQ", "dQw4w9WgXcQ", true))

        // Not pending -> do not skip
        assertFalse(VoxTranslationReusePolicy.shouldSkipDuplicate("dQw4w9WgXcQ", "dQw4w9WgXcQ", false))

        // Different videos -> do not skip
        assertFalse(VoxTranslationReusePolicy.shouldSkipDuplicate("videoA", "videoB", true))

        // Null video IDs -> do not skip
        assertFalse(VoxTranslationReusePolicy.shouldSkipDuplicate(null, "videoB", true))
        assertFalse(VoxTranslationReusePolicy.shouldSkipDuplicate("videoA", null, true))
    }
}

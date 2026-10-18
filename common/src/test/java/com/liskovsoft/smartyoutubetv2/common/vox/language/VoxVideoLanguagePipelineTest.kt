package com.liskovsoft.smartyoutubetv2.common.vox.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [VoxVideoLanguagePipeline] and [VoxVideoLanguageCache].
 * Verifies hierarchical resolution: Local Cache -> Server Cache -> Fallback Detection.
 */
class VoxVideoLanguagePipelineTest {

    private lateinit var localCache: VoxVideoLanguageCache

    @Before
    fun setUp() {
        localCache = VoxVideoLanguageCache.createInMemory()
    }

    @Test
    fun testScenarioA_localCacheHit_zeroServerCalls() {
        localCache.putLanguage("video123", "en")

        var serverCalls = 0
        val mockServer = object : VoxVideoLanguagePipeline.ServerLookup {
            override fun lookupServerLangCache(videoUrl: String, title: String?): String? {
                serverCalls++
                return "ru"
            }
        }

        val pipeline = VoxVideoLanguagePipeline(localCache, mockServer)
        val result = pipeline.resolveLanguage("video123", "https://youtu.be/video123")

        assertTrue(result.isResolved)
        assertEquals("en", result.language)
        assertEquals(VoxVideoLanguagePipeline.ResolutionSource.LOCAL_CACHE, result.source)
        assertEquals(0, serverCalls)
    }

    @Test
    fun testScenarioB_serverCacheHit_persistsToLocalCache() {
        var serverCalls = 0
        val mockServer = object : VoxVideoLanguagePipeline.ServerLookup {
            override fun lookupServerLangCache(videoUrl: String, title: String?): String? {
                serverCalls++
                return "de"
            }
        }

        val pipeline = VoxVideoLanguagePipeline(localCache, mockServer)
        val result = pipeline.resolveLanguage("video456", "https://youtu.be/video456")

        assertTrue(result.isResolved)
        assertEquals("de", result.language)
        assertEquals(VoxVideoLanguagePipeline.ResolutionSource.SERVER_CACHE, result.source)
        assertEquals(1, serverCalls)

        // Verify it was stored in local cache so second call doesn't hit server
        assertEquals("de", localCache.getLanguage("video456"))
        val secondResult = pipeline.resolveLanguage("video456", "https://youtu.be/video456")
        assertEquals(VoxVideoLanguagePipeline.ResolutionSource.LOCAL_CACHE, secondResult.source)
        assertEquals(1, serverCalls) // still 1 call
    }

    @Test
    fun testScenarioC_serverError_doesNotPoisonCache() {
        val mockServer = object : VoxVideoLanguagePipeline.ServerLookup {
            override fun lookupServerLangCache(videoUrl: String, title: String?): String? {
                throw IOException("Server 500 error / timeout")
            }
        }

        val pipeline = VoxVideoLanguagePipeline(localCache, mockServer)
        val result = pipeline.resolveLanguage("video789", "https://youtu.be/video789", playerAudioLang = null)

        assertFalse(result.isResolved)
        assertEquals(VoxLanguagePolicy.LANG_AUTO, result.language)
        assertEquals(VoxVideoLanguagePipeline.ResolutionSource.UNKNOWN, result.source)

        // Ensure "unknown" or "auto" was NOT saved to local cache (no negative-cache poisoning)
        assertEquals(null, localCache.getLanguage("video789"))
    }

    @Test
    fun testScenarioD_fallbackAudioTrackDetection() {
        val mockServer = object : VoxVideoLanguagePipeline.ServerLookup {
            override fun lookupServerLangCache(videoUrl: String, title: String?): String? = null
        }

        val pipeline = VoxVideoLanguagePipeline(localCache, mockServer)
        val result = pipeline.resolveLanguage("videoAudio", "https://youtu.be/videoAudio", playerAudioLang = "eng")

        assertTrue(result.isResolved)
        assertEquals("en", result.language)
        assertEquals(VoxVideoLanguagePipeline.ResolutionSource.FALLBACK_DETECTION, result.source)

        // Audio detection should be persisted for next time
        assertEquals("en", localCache.getLanguage("videoAudio"))
    }

    @Test
    fun testScenarioE_fallbackSubtitleDetection() {
        val mockServer = object : VoxVideoLanguagePipeline.ServerLookup {
            override fun lookupServerLangCache(videoUrl: String, title: String?): String? = null
        }

        val pipeline = VoxVideoLanguagePipeline(localCache, mockServer)
        val result = pipeline.resolveLanguage(
            videoId = "videoSubs",
            rawUrl = "https://youtu.be/videoSubs",
            playerAudioLang = null,
            youtubeSubtitleLangs = listOf("fra", "spa")
        )

        assertTrue(result.isResolved)
        assertEquals("fr", result.language)
        assertEquals(VoxVideoLanguagePipeline.ResolutionSource.FALLBACK_DETECTION, result.source)
        assertEquals("fr", localCache.getLanguage("videoSubs"))
    }

    @Test
    fun testCanonicalUrlNormalization() {
        localCache.putLanguage("https://www.youtube.com/watch?v=AbCdEfGh123", "ja")
        // Lookup by short id or short url should resolve the same cache item
        assertEquals("ja", localCache.getLanguage("AbCdEfGh123"))
        assertEquals("ja", localCache.getLanguage("https://youtu.be/AbCdEfGh123"))
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Patch #44 Verification Suite:
 * Cache-First Translation, Existing Translation Lookup, Fast Start,
 * Monotonic Progress, URL Invalidation & Self-Healing.
 */
class VoxPatch44CacheFirstSuiteTest {

    @Before
    fun setUp() {
        VoxTranslationSessionCache.clear()
    }

    // ---------------------------------------------------------------------
    // 1. CACHE-FIRST TIER 1: MEMORY CACHE HIT
    // ---------------------------------------------------------------------
    @Test
    fun testTier1MemoryCacheHitReturnsImmediately() {
        val videoId = "tier1_video"
        val rawUrl = "https://www.youtube.com/watch?v=tier1_video"
        val audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/tier1.mp3"

        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = videoId,
            rawUrl = rawUrl,
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = audioUrl,
            durationSec = 180.0,
            useLively = false
        )

        val result = VoxTranslationSessionCache.lookup(
            videoId = videoId,
            rawUrl = rawUrl,
            sourceLang = "en",
            targetLang = "ru",
            useLively = false
        )

        assertTrue("Expected Hit from memory cache", result is VoxTranslationSessionCache.LookupResult.Hit)
        val hit = result as VoxTranslationSessionCache.LookupResult.Hit
        assertEquals("MEMORY_CACHE", hit.source)
        assertEquals(audioUrl, hit.record.audioUrl)
        assertEquals(videoId, hit.record.videoId)
    }

    // ---------------------------------------------------------------------
    // 2. CACHE-FIRST TIER 2: FALLBACK MATCHING ON EMPTY OR AUTO SOURCE LANG
    // ---------------------------------------------------------------------
    @Test
    fun testTier2FallbackMatchingWhenSourceLangVaries() {
        val videoId = "tier2_video"
        val rawUrl = "https://www.youtube.com/watch?v=tier2_video"
        val audioUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/tier2.mp3"

        // Stored with explicit 'en'
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = videoId,
            rawUrl = rawUrl,
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = audioUrl,
            durationSec = 240.0,
            useLively = false
        )

        // Lookup with empty source language ("")
        val resultEmpty = VoxTranslationSessionCache.lookup(
            videoId = videoId,
            rawUrl = rawUrl,
            sourceLang = "",
            targetLang = "ru",
            useLively = false
        )
        assertTrue("Lookup with empty sourceLang should hit fallback", resultEmpty is VoxTranslationSessionCache.LookupResult.Hit)

        // Lookup with "auto" source language
        val resultAuto = VoxTranslationSessionCache.lookup(
            videoId = videoId,
            rawUrl = rawUrl,
            sourceLang = "auto",
            targetLang = "ru",
            useLively = false
        )
        assertTrue("Lookup with 'auto' sourceLang should hit fallback", resultAuto is VoxTranslationSessionCache.LookupResult.Hit)
    }

    // ---------------------------------------------------------------------
    // 3. CACHE-FIRST TIER 3: SERVER EXISTING TRANSLATION (BACKEND_READY_LOOKUP)
    // ---------------------------------------------------------------------
    @Test
    fun testTier3ServerExistingTranslationTransitionsDirectlyToAttaching() {
        val sm = VoxTranslationStateMachine()
        val t0 = 100_000L

        sm.transitionTo(VoxTranslationStateMachine.State.LOOKUP, t0)
        assertEquals(VoxTranslationStateMachine.State.LOOKUP, sm.currentState)

        // Server returns ready translation on the first request
        sm.markLookupReady(VoxTranslationStateMachine.ReuseSource.BACKEND_READY_LOOKUP, t0 + 250L)
        assertEquals(VoxTranslationStateMachine.State.LOOKUP_READY, sm.currentState)
        assertEquals(VoxTranslationStateMachine.ReuseSource.BACKEND_READY_LOOKUP, sm.reuseSource)

        // Fast Start: direct transition to ATTACHING without intermediate WAITING/PROCESSING
        sm.transitionTo(VoxTranslationStateMachine.State.ATTACHING, t0 + 260L)
        assertEquals(VoxTranslationStateMachine.State.ATTACHING, sm.currentState)

        // Once player prepares, transitions to READY
        sm.transitionTo(VoxTranslationStateMachine.State.READY, t0 + 500L)
        assertEquals(VoxTranslationStateMachine.State.READY, sm.currentState)
        assertTrue("Total startup should be <= 500ms for server existing", sm.totalTranslationStartupMs in 1..600)
    }

    // ---------------------------------------------------------------------
    // 4. CACHE INVALIDATION & EXPIRED URL SELF-HEALING
    // ---------------------------------------------------------------------
    @Test
    fun testExpiredUrlInvalidationRemovesStaleRecordAndAllowsFreshLookup() {
        val videoId = "expired_url_video"
        val rawUrl = "https://www.youtube.com/watch?v=expired_url_video"
        val staleUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/stale_expired.mp3"

        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = videoId,
            rawUrl = rawUrl,
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = staleUrl,
            durationSec = 90.0,
            useLively = false
        )

        // First lookup hits stale record
        val initialLookup = VoxTranslationSessionCache.lookup(videoId, rawUrl, "en", "ru", false)
        assertTrue(initialLookup is VoxTranslationSessionCache.LookupResult.Hit)
        assertEquals(staleUrl, (initialLookup as VoxTranslationSessionCache.LookupResult.Hit).record.audioUrl)

        // Simulated playback failure on stale URL -> triggers invalidation
        VoxTranslationSessionCache.invalidate(videoId, rawUrl)

        // Next lookup is a clean Miss, allowing fresh network fetch from server
        val afterInvalidation = VoxTranslationSessionCache.lookup(videoId, rawUrl, "en", "ru", false)
        assertTrue("Cache lookup must be Miss after invalidation", afterInvalidation is VoxTranslationSessionCache.LookupResult.Miss)

        // Server returns fresh audio URL
        val freshUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/fresh_new_token.mp3"
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = videoId,
            rawUrl = rawUrl,
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = freshUrl,
            durationSec = 90.0,
            useLively = false
        )

        val freshLookup = VoxTranslationSessionCache.lookup(videoId, rawUrl, "en", "ru", false)
        assertTrue(freshLookup is VoxTranslationSessionCache.LookupResult.Hit)
        assertEquals(freshUrl, (freshLookup as VoxTranslationSessionCache.LookupResult.Hit).record.audioUrl)
    }

    // ---------------------------------------------------------------------
    // 5. CACHE CLEAR PURGES ALL ENTRIES
    // ---------------------------------------------------------------------
    @Test
    fun testCacheClearPurgesAllRecords() {
        VoxTranslationSessionCache.storeReadyTranslation("vid1", "url1", "en", "ru", "https://vot.ex/1.mp3", 10.0, false)
        VoxTranslationSessionCache.storeReadyTranslation("vid2", "url2", "en", "ru", "https://vot.ex/2.mp3", 20.0, false)

        assertTrue(VoxTranslationSessionCache.lookup("vid1", "url1", "en", "ru", false) is VoxTranslationSessionCache.LookupResult.Hit)
        assertTrue(VoxTranslationSessionCache.lookup("vid2", "url2", "en", "ru", false) is VoxTranslationSessionCache.LookupResult.Hit)

        VoxTranslationSessionCache.clear()

        assertTrue(VoxTranslationSessionCache.lookup("vid1", "url1", "en", "ru", false) is VoxTranslationSessionCache.LookupResult.Miss)
        assertTrue(VoxTranslationSessionCache.lookup("vid2", "url2", "en", "ru", false) is VoxTranslationSessionCache.LookupResult.Miss)
    }

    // ---------------------------------------------------------------------
    // 6. FAST START DIAGNOSTICS CONTEXT PRESERVES REUSE SOURCE ACCURACY
    // ---------------------------------------------------------------------
    @Test
    fun testDiagnosticsContextClassification() {
        val cacheCtx = VoxTranslationReusePolicy.createSafeDiagnosticsContext(120L, "cache", true, "standard")
        assertEquals("cache", cacheCtx["source"])
        assertEquals("120", cacheCtx["elapsedMs"])

        val existingCtx = VoxTranslationReusePolicy.createSafeDiagnosticsContext(340L, "existing", true, "standard")
        assertEquals("existing", existingCtx["source"])
        assertEquals("340", existingCtx["elapsedMs"])

        val apiCtx = VoxTranslationReusePolicy.createSafeDiagnosticsContext(15000L, "api", false, "live_voice")
        assertEquals("api", apiCtx["source"])
        assertEquals("live_voice", apiCtx["mode"])
        assertEquals("false", apiCtx["sameVideo"])
    }
}

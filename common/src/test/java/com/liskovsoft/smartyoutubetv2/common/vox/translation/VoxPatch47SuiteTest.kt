package com.liskovsoft.smartyoutubetv2.common.vox.translation

import android.content.Context
import android.content.SharedPreferences
import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationStateMachine.State
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VoxPatch47SuiteTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("vox_translation_sessions_v2", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        VoxTranslationSessionCache.init(context)
        VoxTranslationSessionCache.clear()
        VoxTranslationSessionCache.resetStats()
        VoxTranslationSessionCache.resetTtlForTesting()
    }

    @After
    fun tearDown() {
        VoxTranslationSessionCache.clear()
        VoxTranslationSessionCache.resetStats()
        VoxTranslationSessionCache.resetTtlForTesting()
        prefs.edit().clear().commit()
    }

    @Test
    fun testTerminalStateImmutability_lateTimeoutAfterReadyIgnored() {
        val sm = VoxTranslationStateMachine()
        sm.transitionTo(State.LOOKUP, 1000L)
        sm.transitionTo(State.REQUESTING, 1100L)
        sm.transitionTo(State.PROCESSING, 1200L)
        sm.transitionTo(State.ATTACHING, 1300L)
        sm.transitionTo(State.READY, 1400L)

        assertEquals(State.READY, sm.currentState)
        assertTrue(sm.currentState.isTerminal())

        // Late callbacks or timeouts must not override READY
        sm.transitionTo(State.TIMEOUT, 1500L)
        assertEquals(State.READY, sm.currentState)

        sm.transitionTo(State.FAILED, 1600L)
        assertEquals(State.READY, sm.currentState)

        sm.transitionTo(State.CANCELLED, 1700L)
        assertEquals(State.READY, sm.currentState)

        // Only transition to IDLE is permitted to reset the state machine
        sm.transitionTo(State.IDLE, 1800L)
        assertEquals(State.IDLE, sm.currentState)
    }

    @Test
    fun testTerminalStateImmutability_lateReadyAfterCancelledIgnored() {
        val sm = VoxTranslationStateMachine()
        sm.transitionTo(State.LOOKUP, 1000L)
        sm.transitionTo(State.REQUESTING, 1100L)
        sm.transitionTo(State.CANCELLED, 1200L)

        assertEquals(State.CANCELLED, sm.currentState)
        assertTrue(sm.currentState.isTerminal())

        // Late backend response or audio prepare callback must not override CANCELLED
        sm.transitionTo(State.ATTACHING, 1300L)
        assertEquals(State.CANCELLED, sm.currentState)

        sm.transitionTo(State.READY, 1400L)
        assertEquals(State.CANCELLED, sm.currentState)

        sm.transitionTo(State.TIMEOUT, 1500L)
        assertEquals(State.CANCELLED, sm.currentState)
    }

    @Test
    fun testDeterministicMemoryLruTieBreakingWithEqualTimestamps() {
        // Fill memory cache to 50 entries with identical timestamps
        for (i in 0 until 50) {
            val key = String.format("item_%02d", i)
            VoxTranslationSessionCache.storeReadyTranslation(
                videoId = key,
                rawUrl = "https://www.youtube.com/watch?v=$key",
                sourceLang = "en",
                targetLang = "ru",
                audioUrl = "https://vtrans.example.com/$key.mp3",
                durationSec = 100.0,
                useLively = false
            )
        }

        // Add 51st entry
        val key51 = "item_50"
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = key51,
            rawUrl = "https://www.youtube.com/watch?v=$key51",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vtrans.example.com/$key51.mp3",
            durationSec = 100.0,
            useLively = false
        )

        // Clear prefs before lookup so PERSISTED_CACHE cannot hit and re-warm memoryCache
        prefs.edit().clear().commit()

        // Deterministic eviction: item_00 had the earliest index/key and must be evicted from memoryCache
        val evictedRes = VoxTranslationSessionCache.lookup("item_00", null, "en", "ru", false)
        assertTrue("item_00 should be evicted from memoryCache", evictedRes is VoxTranslationSessionCache.LookupResult.Miss)

        // item_01 should still be retained directly in MEMORY_CACHE
        val retainedRes = VoxTranslationSessionCache.lookup("item_01", null, "en", "ru", false)
        assertTrue("item_01 should still be in memoryCache", retainedRes is VoxTranslationSessionCache.LookupResult.Hit)
        assertEquals("MEMORY_CACHE", (retainedRes as VoxTranslationSessionCache.LookupResult.Hit).source)
    }

    @Test
    fun testClockSkewResilience_backwardSkewAcceptedUpTo10Minutes() {
        val now = System.currentTimeMillis()
        val futureCreatedAt = now + 5 * 60_000L // 5 minutes in future (backward clock skew)

        // Write directly to prefs with future timestamp
        val json = JSONObject().apply {
            put("videoId", "clockSkewTolerated")
            put("canonicalUrl", "https://www.youtube.com/watch?v=clockSkewTolerated")
            put("sourceLang", "en")
            put("targetLang", "ru")
            put("audioUrl", "https://vtrans.example.com/clockSkewTolerated.mp3")
            put("durationSec", 60.0)
            put("useLivelyVoice", false)
            put("createdAtMs", futureCreatedAt)
            put("lastAccessedMs", futureCreatedAt)
            put("schemaVersion", 2)
        }
        val key = VoxTranslationReusePolicy.buildCacheKey("https://www.youtube.com/watch?v=clockSkewTolerated", "en", "ru", false)
        prefs.edit().putString("rec_$key", json.toString()).commit()

        val res = VoxTranslationSessionCache.lookup("clockSkewTolerated", "https://www.youtube.com/watch?v=clockSkewTolerated", "en", "ru", false)
        assertTrue("5 min clock skew must be accepted as valid Hit", res is VoxTranslationSessionCache.LookupResult.Hit)
    }

    @Test
    fun testClockSkewResilience_farFutureRejected() {
        val now = System.currentTimeMillis()
        val farFutureCreatedAt = now + 15 * 60_000L // 15 minutes in future (exceeds 10m threshold)

        val json = JSONObject().apply {
            put("videoId", "farFutureRejected")
            put("canonicalUrl", "https://www.youtube.com/watch?v=farFutureRejected")
            put("sourceLang", "en")
            put("targetLang", "ru")
            put("audioUrl", "https://vtrans.example.com/farFutureRejected.mp3")
            put("durationSec", 60.0)
            put("useLivelyVoice", false)
            put("createdAtMs", farFutureCreatedAt)
            put("lastAccessedMs", farFutureCreatedAt)
            put("schemaVersion", 2)
        }
        val key = VoxTranslationReusePolicy.buildCacheKey("https://www.youtube.com/watch?v=farFutureRejected", "en", "ru", false)
        prefs.edit().putString("rec_$key", json.toString()).commit()

        val res = VoxTranslationSessionCache.lookup("farFutureRejected", "https://www.youtube.com/watch?v=farFutureRejected", "en", "ru", false)
        assertTrue("15 min far-future timestamp must be rejected as Miss", res is VoxTranslationSessionCache.LookupResult.Miss)
    }

    @Test
    fun testOffMainThreadPersistenceFlush() {
        VoxTranslationSessionCache.storeReadyTranslation(
            videoId = "offMainThreadTest",
            rawUrl = "https://www.youtube.com/watch?v=offMainThreadTest",
            sourceLang = "en",
            targetLang = "ru",
            audioUrl = "https://vtrans.example.com/offMainThreadTest.mp3",
            durationSec = 45.0,
            useLively = false
        )

        VoxTranslationSessionCache.flushDiskPersistenceForTesting()

        val key = VoxTranslationReusePolicy.buildCacheKey("https://www.youtube.com/watch?v=offMainThreadTest", "en", "ru", false)
        val raw = prefs.getString("rec_$key", null)
        org.junit.Assert.assertNotNull("Record should be persisted after flush", raw)
    }
}

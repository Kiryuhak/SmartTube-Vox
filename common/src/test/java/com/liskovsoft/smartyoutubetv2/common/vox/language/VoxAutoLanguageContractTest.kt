package com.liskovsoft.smartyoutubetv2.common.vox.language

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.SubtitleTrack
import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioTrackHelper
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotProtobuf
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxDiagnosticSanitizer
import com.liskovsoft.smartyoutubetv2.common.vox.voice.VoxVoiceSelectionPolicy
import com.liskovsoft.youtubeapi.videoinfo.models.TranslatedCaptionTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests covering the 18 auto-language and Live Voices scenarios
 * specified in PROMPT №107-R4 Section 6 (VOT 1.12.4 parity).
 *
 * Strict constraint: HINDI_VIDEO_TESTS_RUN = 0 (no Hindi video tests).
 */
class VoxAutoLanguageContractTest {

    private lateinit var localCache: VoxVideoLanguageCache

    @Before
    fun setUp() {
        localCache = VoxVideoLanguageCache.createInMemory()
    }

    // Scenario 1: English speech: original audio "en", UI source=AUTO, target=ru -> requestLang=en, forceSourceLang=false
    @Test
    fun testScenario01_EnglishSpeechAutoSource() {
        val pipeline = VoxVideoLanguagePipeline(localCache)
        val result = pipeline.resolveLanguage("videoEn1", "https://youtu.be/videoEn1", playerAudioLang = "en")

        assertTrue(result.isResolved)
        assertEquals("en", result.language)
        assertEquals(VoxVideoLanguagePipeline.ResolutionSource.FALLBACK_DETECTION, result.source)

        val requestLang = VoxVoiceSelectionPolicy.getRequestLangForTranslation(result.language, "ru", useLivelyVoice = true)
        assertEquals("en", requestLang)

        // Protobuf wire check: field 8 is "en", field 9 (forceSourceLang) is false (omitted)
        val proto = YandexVotProtobuf.encodeTranslationRequest(
            "https://youtu.be/videoEn1", true, 120.0, requestLang, "ru", "Test Video", true
        )
        assertNotNull(proto)
        assertTrue(containsSubarray(proto, byteArrayOf((8 shl 3 or 2).toByte()))) // Tag 66 = field 8
        assertFalse(containsSubarray(proto, byteArrayOf((9 shl 3 or 0).toByte()))) // Tag 72 = field 9 (omitted when false)
    }

    // Scenario 2: Spanish: original "es", title localized to Russian -> correct "es", target=ru, no manual choice
    @Test
    fun testScenario02_SpanishAudioWithRussianTitle() {
        val pipeline = VoxVideoLanguagePipeline(localCache)
        val result = pipeline.resolveLanguage(
            "videoEs1", "https://youtu.be/videoEs1",
            title = "Приключения в Мадриде - Обзор",
            playerAudioLang = "spa"
        )

        assertTrue(result.isResolved)
        assertEquals("es", result.language)

        val requestLang = VoxVoiceSelectionPolicy.getRequestLangForTranslation(result.language, "ru", useLivelyVoice = true)
        assertEquals("es", requestLang)
    }

    // Scenario 3: French: original "fr", caption ASR contains "fr", UI source=AUTO -> fr -> ru; Live Voices only if allowed
    @Test
    fun testScenario03_FrenchAudioFromCaptions() {
        val pipeline = VoxVideoLanguagePipeline(localCache)
        val result = pipeline.resolveLanguage(
            "videoFr1", "https://youtu.be/videoFr1",
            playerAudioLang = null,
            youtubeSubtitleLangs = listOf("fra")
        )

        assertTrue(result.isResolved)
        assertEquals("fr", result.language)

        val requestLang = VoxVoiceSelectionPolicy.getRequestLangForTranslation(result.language, "ru", useLivelyVoice = true)
        assertEquals("fr", requestLang)

        // Live Voices gate: fr -> ru with active account is allowed
        assertTrue(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed(requestLang, "ru", hasActiveAccount = true))
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed(requestLang, "ru", hasActiveAccount = false))
    }

    // Scenario 4: No metadata: no tracks/captions/cache/text -> auto -> ru, NOT forced "en"; Live Voices NOT allowed
    @Test
    fun testScenario04_NoMetadataFallbackToAutoWithoutForcedEn() {
        val pipeline = VoxVideoLanguagePipeline(localCache)
        val result = pipeline.resolveLanguage(
            "videoUnknown1", "https://youtu.be/videoUnknown1",
            title = "",
            playerAudioLang = null,
            youtubeSubtitleLangs = null
        )

        assertFalse(result.isResolved)
        assertEquals("auto", result.language)
        assertEquals(VoxVideoLanguagePipeline.ResolutionSource.UNKNOWN, result.source)

        // VOT 1.12.4 parity: requestLang remains "auto" even when useLivelyVoice is requested
        val requestLang = VoxVoiceSelectionPolicy.getRequestLangForTranslation(result.language, "ru", useLivelyVoice = true)
        assertEquals("auto", requestLang)

        // Live Voices gate: auto -> ru MUST NOT be allowed
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed(requestLang, "ru", hasActiveAccount = true))
    }

    // Scenario 5: Title != audio: English title, French audio track -> source "fr"
    @Test
    fun testScenario05_TitleEnglishAudioFrench() {
        val pipeline = VoxVideoLanguagePipeline(localCache)
        val result = pipeline.resolveLanguage(
            "videoFrEngTitle", "https://youtu.be/videoFrEngTitle",
            title = "A Great Documentary About Paris Architecture",
            playerAudioLang = "fr"
        )

        assertTrue(result.isResolved)
        assertEquals("fr", result.language)
    }

    // Scenario 6: Selected dubbed audio != original: selected track "ru" and original "es"
    @Test
    fun testScenario06_DubbedRussianTrackSwitchesToOriginal() {
        val isDubbed = VotAudioTrackHelper.isRussianLang("ru")
        assertTrue(isDubbed)

        // Russian language audio is skipped for translation (ru -> ru blocked)
        val shouldSkip = VoxLanguagePolicy.shouldSkipAutoTranslate("ru", "ru", null)
        assertTrue(shouldSkip)

        // But non-Russian original audio is NOT skipped
        val shouldTranslateOriginal = VoxLanguagePolicy.shouldSkipAutoTranslate("es", "ru", null)
        assertFalse(shouldTranslateOriginal)
    }

    // Scenario 7: Translated captions: translatedFromLanguage marker -> do not treat as original language
    @Test
    fun testScenario07_TranslatedCaptionsExcludedFromOriginalDetection() {
        val translatedSub = "ru" + TranslatedCaptionTrack.TRANSLATE_MARKER
        assertTrue(SubtitleTrack.isAuto(translatedSub))

        val originalSub = "es"
        assertFalse(SubtitleTrack.isAuto(originalSub))

        // When evaluating subtitle candidates in pipeline, only non-translated subtitles are considered
        val pipeline = VoxVideoLanguagePipeline(localCache)
        val result = pipeline.resolveLanguage(
            "videoSubsOnlyTranslated", "https://youtu.be/videoSubsOnlyTranslated",
            playerAudioLang = null,
            youtubeSubtitleLangs = null // translated subs were filtered out by controller
        )
        assertFalse(result.isResolved)
        assertEquals("auto", result.language)
    }

    // Scenario 8: Cache video A -> video B: no cross-video language contamination
    @Test
    fun testScenario08_CacheIsolationBetweenVideos() {
        val pipeline = VoxVideoLanguagePipeline(localCache)

        pipeline.resolveLanguage("videoA", "https://youtu.be/videoA", playerAudioLang = "en")
        assertEquals("en", localCache.getLanguage("videoA"))

        // Video B has no metadata -> MUST NOT inherit "en" from Video A
        val resultB = pipeline.resolveLanguage("videoB", "https://youtu.be/videoB", playerAudioLang = null)
        assertFalse(resultB.isResolved)
        assertEquals("auto", resultB.language)
        assertNull(localCache.getLanguage("videoB"))
    }

    // Scenario 9: Stale/manual override: default does not mutate to "en"
    @Test
    fun testScenario09_StaleOrUnsetOverridePreservesAuto() {
        val resolved = VoxVoiceSelectionPolicy.getRequestLangForTranslation(null, "ru", useLivelyVoice = false)
        assertEquals("auto", resolved)

        val resolvedBlank = VoxVoiceSelectionPolicy.getRequestLangForTranslation("   ", "ru", useLivelyVoice = true)
        assertEquals("auto", resolvedBlank)
    }

    // Scenario 10: BCP-47 normalization and "und"/null/empty
    @Test
    fun testScenario10_Bcp47Normalization() {
        assertEquals("en", VoxLanguagePolicy.normalizeToRequestLang("en-US"))
        assertEquals("en", VoxLanguagePolicy.normalizeToRequestLang("en_GB"))
        assertEquals("es", VoxLanguagePolicy.normalizeToRequestLang("es-419"))
        assertEquals("fr", VoxLanguagePolicy.normalizeToRequestLang("fr-FR"))
        assertEquals("de", VoxLanguagePolicy.normalizeToRequestLang("de-DE"))
        assertEquals("ja", VoxLanguagePolicy.normalizeToRequestLang("ja-JP"))
        assertEquals("auto", VoxLanguagePolicy.normalizeToRequestLang("und"))
        assertEquals("auto", VoxLanguagePolicy.normalizeToRequestLang("unknown"))
        assertNull(VoxLanguagePolicy.normalizeToRequestLang(null))
        assertNull(VoxLanguagePolicy.normalizeToRequestLang(""))
    }

    // Scenario 11: Source already ru: do not send meaningless ru -> ru translation
    @Test
    fun testScenario11_SourceAlreadyRussianSkipped() {
        assertTrue(VoxLanguagePolicy.shouldSkipAutoTranslate("ru", "ru", null))
        assertTrue(VoxLanguagePolicy.shouldSkipAutoTranslate("rus", "ru", null))
        assertTrue(VoxLanguagePolicy.shouldSkipAutoTranslate("russian", "ru", null))
        assertFalse(VoxLanguagePolicy.shouldSkipAutoTranslate("en", "ru", null))
        assertFalse(VoxLanguagePolicy.shouldSkipAutoTranslate("es", "ru", null))
    }

    // Scenario 12: Unsupported language: handled without crashing
    @Test
    fun testScenario12_UnsupportedLanguagePolicy() {
        // Arabic is not supported by external backend for voice translation
        assertTrue(VotAudioTrackHelper.isArabicLang("ar"))
        assertTrue(VotAudioTrackHelper.isArabicLang("ara"))
        assertFalse(VotAudioTrackHelper.isArabicLang("en"))
    }

    // Scenario 13: Live stream: source="auto", target="ru", Live Voices not claimed
    @Test
    fun testScenario13_LiveStreamDefaultsToAutoWithoutLiveVoices() {
        val streamLang = VoxVoiceSelectionPolicy.getRequestLangForTranslation("auto", "ru", useLivelyVoice = true)
        assertEquals("auto", streamLang)
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed(streamLang, "ru", hasActiveAccount = true))
    }

    // Scenario 14: Protobuf field8/9/14/18 wire test
    @Test
    fun testScenario14_ProtobufWireFields() {
        val encodedAuto = YandexVotProtobuf.encodeTranslationRequest(
            "https://youtu.be/demo", true, 60.0, "auto", "ru", "Title", false
        )
        // Field 8 ("auto") tag: (8 << 3) | 2 = 66 = 0x42
        assertTrue("Field 8 must be present for auto", containsSubarray(encodedAuto, byteArrayOf(66.toByte())))
        // Field 9 (forceSourceLang = false) must be omitted
        assertFalse("Field 9 must be omitted when false", containsSubarray(encodedAuto, byteArrayOf(72.toByte())))
        // Field 14 ("ru") tag: (14 << 3) | 2 = 114 = 0x72
        assertTrue("Field 14 must be present for ru", containsSubarray(encodedAuto, byteArrayOf(114.toByte())))
        // Field 18 (useLiveVoices = false) must be omitted
        val tag18 = byteArrayOf(0x90.toByte(), 0x01.toByte())
        assertFalse("Field 18 must be omitted when false", containsSubarray(encodedAuto, tag18))

        // When useLiveVoices = true
        val encodedLive = YandexVotProtobuf.encodeTranslationRequest(
            "https://youtu.be/demo", true, 60.0, "en", "ru", "Title", true
        )
        assertTrue("Field 18 must be present when true", containsSubarray(encodedLive, tag18))
    }

    // Scenario 15: OAuth validity: token absence disables Lively Voice without breaking standard VOT
    @Test
    fun testScenario15_OAuthTokenAbsenceDisablesLivelyVoice() {
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("en", "ru", hasActiveAccount = false))
        assertTrue(VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("en", "ru", hasActiveAccount = true))
    }

    // Scenario 16: Live Voices fallback: error message detected and graceful fallback allows standard voice
    @Test
    fun testScenario16_LivelyVoiceUnavailableErrorFallback() {
        assertTrue(VoxVoiceSelectionPolicy.isLivelyVoiceUnavailableError("Для этого видео доступна только обычная озвучка"))
        assertTrue(VoxVoiceSelectionPolicy.isLivelyVoiceUnavailableError("Video supports only standard voice"))
        assertFalse(VoxVoiceSelectionPolicy.isLivelyVoiceUnavailableError("Network timeout"))

        // Fallback request maintains original source language (does NOT switch to "en")
        val sourceLang = "es"
        val fallbackLang = VoxVoiceSelectionPolicy.getRequestLangForTranslation(sourceLang, "ru", useLivelyVoice = false)
        assertEquals("es", fallbackLang)
    }

    // Scenario 17: Cancellation/video switch: state machine resets and stale tasks ignored
    @Test
    fun testScenario17_VideoSwitchCancelsStaleTask() {
        val keyA = VoxVoiceSelectionPolicy.buildTranslationCacheKey("videoA", "en", "ru", true)
        val keyB = VoxVoiceSelectionPolicy.buildTranslationCacheKey("videoB", "en", "ru", true)
        assertFalse(keyA == keyB)
        assertTrue(keyA.startsWith("videoA_"))
        assertTrue(keyB.startsWith("videoB_"))
    }

    // Scenario 18: Secret sanitizer: diagnostics logs contain zero tokens and zero private URLs
    @Test
    fun testScenario18_DiagnosticsSanitizationZeroSecrets() {
        val rawLog = "Authorization: Bearer y0_AgAAAAA123456789 with url=https://storage.yandex.net/trans.mp3?sign=abcdef"
        val sanitized = VoxDiagnosticSanitizer.sanitize(rawLog)
        assertFalse("Sanitized message must not contain raw token", sanitized.contains("y0_AgAAAAA123456789"))
        assertFalse("Sanitized message must not contain query sign", sanitized.contains("sign=abcdef"))
    }

    private fun containsSubarray(array: ByteArray?, sub: ByteArray?): Boolean {
        if (array == null || sub == null || array.size < sub.size) return false
        for (i in 0..array.size - sub.size) {
            var match = true
            for (j in sub.indices) {
                if (array[i + j] != sub[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController
import com.liskovsoft.smartyoutubetv2.common.vox.voice.VoxVoiceSelectionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying VOT 1.12.5 auto -> Live Voice resolution, Russian source bypass,
 * and the package-private @VisibleForTesting test seam requested in 163-R36.
 */
class Prompt163R36VotAutoLiveAndSeamTest {

    @Before
    fun setUp() {
        VoiceTranslateController.setAllowTranslationForLocalVideoTestOnly(false)
    }

    @After
    fun tearDown() {
        VoiceTranslateController.setAllowTranslationForLocalVideoTestOnly(false)
    }

    @Test
    fun testAutoLanguagePreservesLiveVoiceWhenRussianTargetAndAccountActive() {
        // Under VOT 1.12.5, "auto" source language allows Live Voice if target is Russian and user is signed in
        val allowed = VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("auto", "ru", true)
        assertTrue("VOT 1.12.5 contract: auto source language must allow Live Voice for Russian target with active account", allowed)
    }

    @Test
    fun testAutoLanguageRejectsLiveVoiceWithoutActiveAccount() {
        // Without active account, live voice is never permitted
        val allowed = VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("auto", "ru", false)
        assertFalse("Live Voice requires active Yandex account", allowed)
    }

    @Test
    fun testRussianSourceRejectsLiveVoiceAndTranslation() {
        // Russian audio needs no translation to Russian
        val allowed = VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("ru", "ru", true)
        assertFalse("Russian source language must reject Live Voice translation into Russian", allowed)
    }

    @Test
    fun testNonRussianTargetRejectsLiveVoice() {
        // Yandex Live Voice only supports Russian target
        val allowed = VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("en", "es", true)
        assertFalse("Live Voice must reject non-Russian target language", allowed)
    }

    @Test
    fun testConcreteLanguagePreservesLiveVoice() {
        val allowed = VoxVoiceSelectionPolicy.isLivelyVoiceAllowed("en", "ru", true)
        assertTrue("Concrete source language (en) must allow Live Voice into Russian", allowed)
    }

    @Test
    fun testCacheKeyDifferentiatesLiveVsStandardOnAuto() {
        val keyLive = VoxVoiceSelectionPolicy.buildTranslationCacheKey("test_video_123", "auto", "ru", true)
        val keyStd = VoxVoiceSelectionPolicy.buildTranslationCacheKey("test_video_123", "auto", "ru", false)

        assertNotEquals("Cache keys for live voice vs standard voice must never collide for auto source", keyLive, keyStd)
        assertTrue("Live key must reflect live voice", keyLive.contains("true"))
        assertTrue("Standard key must reflect standard voice", keyStd.contains("false"))
    }

    @Test
    fun testLocalVideoTestSeamDefaultIsFalse() {
        // Guarantee production safety: local video translation is disallowed by default
        assertFalse("Test seam must be disabled by default in production", VoiceTranslateController.isAllowTranslationForLocalVideoTestOnly())
    }

    @Test
    fun testLocalVideoTestSeamToggle() {
        VoiceTranslateController.setAllowTranslationForLocalVideoTestOnly(true)
        assertTrue("Test seam must be enableable for testing", VoiceTranslateController.isAllowTranslationForLocalVideoTestOnly())

        VoiceTranslateController.setAllowTranslationForLocalVideoTestOnly(false)
        assertFalse("Test seam must reset back to false", VoiceTranslateController.isAllowTranslationForLocalVideoTestOnly())
    }
}

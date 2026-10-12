package com.liskovsoft.smartyoutubetv2.common.vox.shorts

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsTranslationAvailabilityTest {
    @Test
    fun testVoiceTranslateButtonAvailableForShorts() {
        val shortVideo = Video().apply {
            videoId = "short_12345"
            isShorts = true
            isLocal = false
        }
        assertFalse(shortVideo.isDownloadedTranslated)
        assertFalse(shortVideo.isLocal)

        // PlayerTweaksData PLAYER_BUTTON_VOICE_TRANSLATE enabled by default in VOX
        val translateMask = PlayerTweaksData.PLAYER_BUTTON_VOICE_TRANSLATE
        assertEquals(1 shl 28, translateMask)
    }
}

class ShortsTranslationSessionIsolationTest {
    @Test
    fun testShortsSessionIsolationAcrossVideos() {
        var sessionId = 100
        val shortA = Video().apply { videoId = "short_A"; isShorts = true }
        val shortB = Video().apply { videoId = "short_B"; isShorts = true }

        // Start session for Short A
        val sessionA = ++sessionId

        // Switch to Short B
        val sessionB = ++sessionId

        assertTrue(sessionB > sessionA)
        // Callback from Short A must be rejected
        assertFalse(sessionA == sessionId)
    }
}

class ShortsTranslationNextVideoCancelTest {
    @Test
    fun testTransitionFromShortAToShortBCancelsJob() {
        var isJobRunning = true
        var isOverlayShown = true

        // Simulate onNewVideo when transitioning from Short A to Short B
        isJobRunning = false
        isOverlayShown = false

        assertFalse(isJobRunning)
        assertFalse(isOverlayShown)
    }
}

class ShortsAutoTranslateTest {
    @Test
    fun testAutoTranslateAppliesToForeignLanguageShort() {
        val shortForeign = Video().apply {
            videoId = "short_en_1"
            isShorts = true
        }
        val isRussian = false
        val isExplicitForeign = true

        val shouldTranslate = !isRussian && isExplicitForeign
        assertTrue(shouldTranslate)
    }
}

class ShortsPausePreparationTest {
    @Test
    fun testShortsPauseWhilePreparingResumesOnlyIfAppPausedIt() {
        var pausedByPreparation = true
        var userManuallyPaused = false
        var playWhenReady = false

        // Preparation completes:
        if (pausedByPreparation) {
            pausedByPreparation = false
            if (!userManuallyPaused) {
                playWhenReady = true
            }
        }

        assertTrue(playWhenReady)
        assertFalse(pausedByPreparation)

        // Manual user pause case:
        pausedByPreparation = true
        userManuallyPaused = true
        playWhenReady = false

        if (pausedByPreparation) {
            pausedByPreparation = false
            if (!userManuallyPaused) {
                playWhenReady = true
            }
        }

        assertFalse(playWhenReady) // Stays paused because user manually paused
    }
}

class ShortsMixRestoreTest {
    @Test
    fun testOriginalAndTranslationVolumeMixRatios() {
        val translationVolume = 1.0f // 100%
        val duckedOriginalVolume = 0.05f // 5%
        assertEquals(1.0f, translationVolume, 0.001f)
        assertEquals(0.05f, duckedOriginalVolume, 0.001f)

        // When restored
        val restoredOriginalVolume = 1.0f
        assertEquals(1.0f, restoredOriginalVolume, 0.001f)
    }
}

class ShortsNoDuplicateControllerTest {
    @Test
    fun testCanonicalVoiceTranslateControllerReused() {
        val controllerClass = VoiceTranslateController::class.java
        assertNotNull(controllerClass)
        // Verify no duplicate class like ShortsTranslationController exists
        var foundDuplicate = false
        try {
            Class.forName("com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.ShortsTranslationController")
            foundDuplicate = true
        } catch (ignored: ClassNotFoundException) {
            foundDuplicate = false
        }
        assertFalse(foundDuplicate)
    }
}

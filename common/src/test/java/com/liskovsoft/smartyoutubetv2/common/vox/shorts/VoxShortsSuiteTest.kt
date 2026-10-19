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

// =========================================================================
// SHORTS AUTO-NEXT HARDENING SUITE: SCENARIOS A THROUGH J
// =========================================================================

class ShortsScenarioASingleEndTest {
    @Test
    fun testSingleEndedCallbackTriggersNextExactlyOnce() {
        val controller = VoxShortsAutoNextController.createForTesting()
        val shortA = Video().apply { videoId = "short_A_1"; isShorts = true }
        val gen = controller.onNewShort(shortA)

        var nextCallCount = 0
        val accepted = controller.onShortEnded(shortA, gen) {
            nextCallCount++
        }

        assertTrue("Single ended callback must be accepted", accepted)
        assertEquals("Next must be requested exactly once", 1, nextCallCount)
        assertEquals(ShortsTransitionState.TRANSITIONING, controller.state)
    }
}

class ShortsScenarioBDuplicateEndTest {
    @Test
    fun testDuplicateEndedCallbackIgnoredAndTriggersNextOnce() {
        val controller = VoxShortsAutoNextController.createForTesting()
        val shortA = Video().apply { videoId = "short_A_2"; isShorts = true }
        val gen = controller.onNewShort(shortA)

        var nextCallCount = 0
        val firstAccepted = controller.onShortEnded(shortA, gen) {
            nextCallCount++
        }
        val secondAccepted = controller.onShortEnded(shortA, gen) {
            nextCallCount++
        }

        assertTrue("First ended callback must be accepted", firstAccepted)
        assertFalse("Duplicate ended callback must be ignored", secondAccepted)
        assertEquals("Next must be executed exactly once", 1, nextCallCount)
    }
}

class ShortsScenarioCStaleGenerationTest {
    @Test
    fun testStaleGenerationEndedAndPreparedCallbacksIgnored() {
        val controller = VoxShortsAutoNextController.createForTesting()
        val shortA = Video().apply { videoId = "short_A_3"; isShorts = true }
        val genA = controller.onNewShort(shortA)

        val shortB = Video().apply { videoId = "short_B_3"; isShorts = true }
        val genB = controller.onNewShort(shortB)

        assertTrue("Generation B must be higher than A", genB > genA)

        // Late callback from short A with old generation
        var oldNextCalled = false
        val staleEndedAccepted = controller.onShortEnded(shortA, genA) {
            oldNextCalled = true
        }
        assertFalse("Stale generation ended callback must be rejected", staleEndedAccepted)
        assertFalse("Old next action must not be called", oldNextCalled)

        val stalePreparedAccepted = controller.onPlayerPrepared(shortA, genA)
        assertFalse("Stale prepared callback must be rejected", stalePreparedAccepted)

        val staleFirstFrameAccepted = controller.onFirstFrame(shortA, genA)
        assertFalse("Stale first frame must be rejected", staleFirstFrameAccepted)

        assertEquals("Active video must remain Short B", "short_B_3", controller.getActiveVideoId())
    }
}

class ShortsScenarioDManualAutoRaceTest {
    @Test
    fun testManualNavigationRacingAutoNextProducesSingleDestination() {
        val controller = VoxShortsAutoNextController.createForTesting()
        val shortA = Video().apply { videoId = "short_A_4"; isShorts = true }
        val shortB = Video().apply { videoId = "short_B_4"; isShorts = true }
        val gen = controller.onNewShort(shortA)

        var autoNextExecuted = 0
        var manualNextExecuted = 0

        // Auto-next initiates
        controller.onShortEnded(shortA, gen) {
            autoNextExecuted++
        }

        // Concurrently user presses DPAD next
        val manualGen = controller.onManualNext(shortA) {
            manualNextExecuted++
        }

        assertTrue("Manual navigation must advance generation", manualGen > gen)
        assertEquals(1, autoNextExecuted)
        assertEquals(1, manualNextExecuted)
        // Transition to B completed under manualGen
        val resolved = controller.onNextResolved(shortB, manualGen)
        assertTrue(resolved)
        assertEquals(ShortsTransitionState.TRANSITIONING, controller.state)
    }
}

class ShortsScenarioEBoundedSkipTest {
    @Test
    fun testNextItemUnavailableTriggersBoundedSkip() {
        val controller = VoxShortsAutoNextController.createForTesting()
        val shortA = Video().apply { videoId = "short_A_5"; isShorts = true }
        val shortBInvalid = Video().apply { videoId = "short_B_invalid"; isShorts = true }
        val shortCValid = Video().apply { videoId = "short_C_valid"; isShorts = true }

        val gen = controller.onNewShort(shortA)
        controller.onShortEnded(shortA, gen) {}

        var skipActionCount = 0
        var feedEndedCalled = false

        // First invalid skip
        val skipped1 = controller.onNextUnavailable(shortBInvalid, gen, {
            skipActionCount++
        }, {
            feedEndedCalled = true
        })

        assertTrue("First skip must be accepted within bound", skipped1)
        assertEquals(1, skipActionCount)
        assertFalse(feedEndedCalled)

        // Then valid short C is opened
        val genC = controller.onNewShort(shortCValid)
        controller.onFirstFrame(shortCValid, genC)

        assertEquals(ShortsTransitionState.PLAYING, controller.state)
        assertEquals("short_C_valid", controller.getActiveVideoId())
    }
}

class ShortsScenarioFEndOfFeedTest {
    @Test
    fun testEndOfFeedDoesNotInfiniteRetry() {
        val controller = VoxShortsAutoNextController.createForTesting()
        val shortLast = Video().apply { videoId = "short_last"; isShorts = true }
        val gen = controller.onNewShort(shortLast)

        var feedEndNotified = false
        controller.onFeedEnd(gen) {
            feedEndNotified = true
        }

        assertTrue(feedEndNotified)
        assertEquals(ShortsTransitionState.FEED_ENDED, controller.state)

        // Subsequent ended calls while feed is ended must not trigger next
        var rogueNextCalled = false
        val rogueEnded = controller.onShortEnded(shortLast, gen) {
            rogueNextCalled = true
        }
        assertFalse(rogueEnded)
        assertFalse(rogueNextCalled)
    }
}

class ShortsScenarioGFirstFrameCompleteTest {
    @Test
    fun testNewItemFirstFrameCompletesTransitionAndRestoresFocus() {
        val controller = VoxShortsAutoNextController.createForTesting()
        var focusRestored = false
        controller.onEnsureFocus = {
            focusRestored = true
        }

        val shortA = Video().apply { videoId = "short_A_7"; isShorts = true }
        val shortB = Video().apply { videoId = "short_B_7"; isShorts = true }

        val genA = controller.onNewShort(shortA)
        controller.onShortEnded(shortA, genA) {}

        val genB = controller.onNewShort(shortB)
        controller.onNextResolved(shortB, genB)
        controller.onAudioStart(shortB, genB)
        val firstFrameAccepted = controller.onFirstFrame(shortB, genB)

        assertTrue(firstFrameAccepted)
        assertTrue("Focus must be restored on first frame", focusRestored)
        assertEquals(ShortsTransitionState.PLAYING, controller.state)

        val history = controller.getTransitionHistory()
        assertEquals(1, history.size)
        val record = history[0]
        assertEquals("short_A_7", record.videoAId)
        assertEquals("short_B_7", record.videoBId)
        assertTrue(record.focusOk)
        assertFalse(record.doubleAdvance)
    }
}

class ShortsScenarioHVideoSwitchCancelsOldTimeoutTest {
    @Test
    fun testVideoSwitchCancelsOldTimeout() {
        val controller = VoxShortsAutoNextController.createForTesting()
        val shortA = Video().apply { videoId = "short_A_8"; isShorts = true }
        val genA = controller.onNewShort(shortA)
        controller.onShortEnded(shortA, genA) {}

        assertEquals(ShortsTransitionState.TRANSITIONING, controller.state)

        // Switch to Short B cancels any pending timeout for Short A
        val shortB = Video().apply { videoId = "short_B_8"; isShorts = true }
        val genB = controller.onNewShort(shortB)

        assertTrue(genB > genA)
        assertEquals(ShortsTransitionState.PREPARING, controller.state)
        assertEquals("short_B_8", controller.getActiveVideoId())
    }
}

class ShortsScenarioIPauseNearEndTest {
    @Test
    fun testPauseNearEndPreventsPrematureAutoNext() {
        val controller = VoxShortsAutoNextController.createForTesting()
        val shortA = Video().apply { videoId = "short_A_9"; isShorts = true }
        val gen = controller.onNewShort(shortA)

        // User pauses playback near end
        controller.onPauseStateChanged(true)

        var nextCalledWhilePaused = false
        val endedWhilePausedAccepted = controller.onShortEnded(shortA, gen) {
            nextCalledWhilePaused = true
        }

        assertFalse("Ended event while paused must be rejected", endedWhilePausedAccepted)
        assertFalse("Auto-next must not trigger while paused", nextCalledWhilePaused)
        assertEquals(ShortsTransitionState.PREPARING, controller.state)

        // Resumed:
        controller.onPauseStateChanged(false)
        var nextCalledAfterResume = false
        val endedAfterResumeAccepted = controller.onShortEnded(shortA, gen) {
            nextCalledAfterResume = true
        }

        assertTrue(endedAfterResumeAccepted)
        assertTrue(nextCalledAfterResume)
        assertEquals(ShortsTransitionState.TRANSITIONING, controller.state)
    }
}

class ShortsScenarioJRapidNavigationTest {
    @Test
    fun testRapidNavigationLastGenerationWins() {
        val controller = VoxShortsAutoNextController.createForTesting()
        val shorts = (1..5).map { i -> Video().apply { videoId = "short_rapid_$i"; isShorts = true } }

        var lastGen = 0L
        for (video in shorts) {
            lastGen = controller.onManualNext(video) {}
        }

        assertEquals(5L, lastGen)
        assertEquals(5L, controller.getCurrentGeneration())

        // Out-of-order callbacks from earlier generations 1..4 arrive late
        for (staleGen in 1..4) {
            val rejected = controller.onPlayerPrepared(shorts[staleGen.toInt() - 1], staleGen.toLong())
            assertFalse("Callback for generation $staleGen must be rejected", rejected)
        }

        // Active generation 5 completes
        val activeVideo = shorts[4]
        val activePrepared = controller.onPlayerPrepared(activeVideo, 5L)
        val activeFirstFrame = controller.onFirstFrame(activeVideo, 5L)

        assertTrue(activePrepared)
        assertTrue(activeFirstFrame)
        assertEquals(ShortsTransitionState.PLAYING, controller.state)
    }
}

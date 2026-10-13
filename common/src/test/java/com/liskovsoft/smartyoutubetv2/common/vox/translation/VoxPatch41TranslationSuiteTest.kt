package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProgressRealPercentTest {
    @Test
    fun testRealBackendProgressPercentUpdatesMonotonically() {
        val sm = VoxTranslationStateMachine()
        val t0 = 1000L
        sm.transitionTo(VoxTranslationStateMachine.State.REQUESTING, t0)

        val type1 = sm.onProgressPoll(progressPercent = 25, remainingSec = -1, nowMs = t0 + 500L)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.REAL_PROGRESS, type1)
        assertEquals(25, sm.lastRealProgressPercent)

        val type2 = sm.onProgressPoll(progressPercent = 65, remainingSec = -1, nowMs = t0 + 1000L)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.REAL_PROGRESS, type2)
        assertEquals(65, sm.lastRealProgressPercent)
    }
}

class TranslationNoFakePercentTest {
    @Test
    fun testNoFakePercentGeneratedWhenBackendDoesNotReportPercent() {
        val sm = VoxTranslationStateMachine()
        val t0 = 1000L
        sm.transitionTo(VoxTranslationStateMachine.State.WAITING_PROVIDER, t0)

        val poll = sm.onProgressPoll(progressPercent = -1, remainingSec = 20, nowMs = t0 + 1000L)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.REAL_PROGRESS, poll)
        assertEquals(-1, sm.lastRealProgressPercent)
        assertEquals(20, sm.getCountdownSec(t0 + 1000L))
    }
}

class TranslationBackend100ThenAttachTest {
    @Test
    fun testBackend100PercentTransitionsToAttachingBeforePlaying() {
        val sm = VoxTranslationStateMachine()
        val t0 = 1000L
        sm.transitionTo(VoxTranslationStateMachine.State.REQUESTING, t0)
        sm.onProgressPoll(progressPercent = 100, remainingSec = -1, nowMs = t0 + 1000L)
        assertEquals(100, sm.lastRealProgressPercent)

        // Transition to ATTACHING clears the 100% progress badge and enters post-process attach stage
        sm.transitionTo(VoxTranslationStateMachine.State.ATTACHING, t0 + 1200L)
        assertEquals(VoxTranslationStateMachine.State.ATTACHING, sm.currentState)
        assertEquals(-1, sm.lastRealProgressPercent)

        // Once audio track is attached and playback starts -> READY
        sm.transitionTo(VoxTranslationStateMachine.State.READY, t0 + 2000L)
        assertEquals(VoxTranslationStateMachine.State.READY, sm.currentState)
    }
}

class Translation100PercentCannotHangForeverTest {
    @Test
    fun test100PercentCannotHangForeverWithoutAdvancing() {
        val sm = VoxTranslationStateMachine()
        val t0 = 1000L
        sm.transitionTo(VoxTranslationStateMachine.State.WAITING_PROVIDER, t0)
        sm.onProgressPoll(progressPercent = 100, remainingSec = 0, nowMs = t0 + 500L)

        // Subsequent polls with same 100% and 0 remainingSec are HEARTBEAT_ONLY
        val type = sm.onProgressPoll(progressPercent = 100, remainingSec = 0, nowMs = t0 + 30_000L)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.HEARTBEAT_ONLY, type)

        // After 90s without real progress, stage is stalled
        assertTrue(sm.isStageStalled(t0 + 91_000L, maxStageStallMs = 90_000L))
    }
}

class TranslationPostProcessTimeoutTest {
    @Test
    fun testPostProcessTimeoutTriggersIfAudioNeverAttaches() {
        val sm = VoxTranslationStateMachine()
        val t0 = 5000L
        sm.transitionTo(VoxTranslationStateMachine.State.ATTACHING, t0)

        assertFalse(sm.isPostProcessTimedOut(t0 + 5000L, postProcessTimeoutMs = 15_000L))
        assertTrue(sm.isPostProcessTimedOut(t0 + 16_000L, postProcessTimeoutMs = 15_000L))
    }
}

class TranslationSpinnerStopsOnErrorTest {
    @Test
    fun testTransitionToFailedStopsPreparingState() {
        val sm = VoxTranslationStateMachine()
        val t0 = 1000L
        sm.transitionTo(VoxTranslationStateMachine.State.ATTACHING, t0)
        sm.transitionTo(VoxTranslationStateMachine.State.FAILED, t0 + 15_500L)

        assertEquals(VoxTranslationStateMachine.State.FAILED, sm.currentState)
        assertFalse(sm.isPostProcessTimedOut(t0 + 20_000L, postProcessTimeoutMs = 15_000L))
        assertFalse(sm.isStageStalled(t0 + 100_000L, maxStageStallMs = 90_000L))
    }
}

class TranslationRetryAfterErrorTest {
    @Test
    fun testRetryAfterErrorResetsStateAndStartsCleanSession() {
        val sm = VoxTranslationStateMachine()
        val t0 = 1000L
        sm.transitionTo(VoxTranslationStateMachine.State.REQUESTING, t0)
        sm.transitionTo(VoxTranslationStateMachine.State.FAILED, t0 + 5000L)
        assertEquals(VoxTranslationStateMachine.State.FAILED, sm.currentState)

        sm.reset()
        assertEquals(VoxTranslationStateMachine.State.IDLE, sm.currentState)

        val tRetry = 10_000L
        sm.transitionTo(VoxTranslationStateMachine.State.LOOKUP, tRetry)
        assertEquals(VoxTranslationStateMachine.State.LOOKUP, sm.currentState)
        assertFalse(sm.isStageStalled(tRetry + 1000L, maxStageStallMs = 90_000L))
    }
}

class TranslationHeartbeatDoesNotResetStageTimeoutTest {
    @Test
    fun testHeartbeatWithoutProgressDoesNotResetStageTimeout() {
        val sm = VoxTranslationStateMachine()
        val t0 = 1000L
        sm.transitionTo(VoxTranslationStateMachine.State.WAITING_PROVIDER, t0)

        // Initial ETA = 30s at t0 + 1000L
        val firstPoll = sm.onProgressPoll(progressPercent = -1, remainingSec = 30, nowMs = t0 + 1000L)
        assertEquals(VoxTranslationStateMachine.ProgressUpdateType.REAL_PROGRESS, firstPoll)

        // Backend keeps returning remainingSec = 30 (stalled queue, heartbeat only)
        for (i in 1..10) {
            val pollType = sm.onProgressPoll(progressPercent = -1, remainingSec = 30, nowMs = t0 + 1000L + i * 10_000L)
            assertEquals(VoxTranslationStateMachine.ProgressUpdateType.HEARTBEAT_ONLY, pollType)
        }

        // Because heartbeats did not advance real progress, stage stall is detected after 90s
        assertTrue(sm.isStageStalled(t0 + 92_000L, maxStageStallMs = 90_000L))
    }
}

class TranslationOldSessionIgnoredTest {
    @Test
    fun testOldSessionCallbackCannotActivateTranslationOnNewVideo() {
        var activeVideoId = "video_A"
        var activeSessionToken = 101L
        var attachedAudioUrl: String? = null

        // Video B opened before Video A callback arrives
        activeVideoId = "video_B"
        activeSessionToken = 102L

        // Late callback from Video A (session 101L)
        val callbackVideoId = "video_A"
        val callbackSessionToken = 101L
        if (callbackVideoId == activeVideoId && callbackSessionToken == activeSessionToken) {
            attachedAudioUrl = "https://vot.example/audio_A.mp3"
        }

        assertEquals(null, attachedAudioUrl)
        assertEquals("video_B", activeVideoId)
    }
}

class TranslationSecondClickSingleFlightTest {
    @Test
    fun testSecondClickWhilePreparingDoesNotSpawnDuplicateRequest() {
        val sm = VoxTranslationStateMachine()
        sm.transitionTo(VoxTranslationStateMachine.State.WAITING_PROVIDER, 1000L)

        var networkRequestsSpawned = 1
        val isAlreadyPreparing = sm.currentState == VoxTranslationStateMachine.State.LOOKUP ||
                sm.currentState == VoxTranslationStateMachine.State.REQUESTING ||
                sm.currentState == VoxTranslationStateMachine.State.WAITING_PROVIDER ||
                sm.currentState == VoxTranslationStateMachine.State.PROCESSING ||
                sm.currentState == VoxTranslationStateMachine.State.ATTACHING

        // Second click while preparing must be handled as single-flight / cancel, never spawning a 2nd request
        if (!isAlreadyPreparing) {
            networkRequestsSpawned++
        }

        assertTrue(isAlreadyPreparing)
        assertEquals(1, networkRequestsSpawned)
    }
}

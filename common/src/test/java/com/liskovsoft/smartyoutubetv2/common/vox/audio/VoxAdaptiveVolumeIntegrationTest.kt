package com.liskovsoft.smartyoutubetv2.common.vox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration test suite verifying Scenarios A through N for
 * VoxAdaptiveVolumeController, VoxSmartDuckingPolicy, VoxVolumeLinkCoordinator,
 * and VoxAudioLevelProcessor.
 */
class VoxAdaptiveVolumeIntegrationTest {

    private class FakeBridge(
        var videoVolume: Float = 0.85f,
        var muted: Boolean = false,
        var translationPlaying: Boolean = true,
        var mainVideoPlaying: Boolean = true
    ) : VoxAdaptiveVolumeController.Bridge {
        override fun setOriginalVideoVolume(volume01: Float) {
            videoVolume = volume01
        }
        override fun getOriginalVideoVolume(): Float = videoVolume
        override fun isOriginalVideoMuted(): Boolean = muted
        override fun setOriginalVideoMuted(isMuted: Boolean) {
            muted = isMuted
        }
        override fun isTranslationPlaying(): Boolean = translationPlaying
        override fun isMainVideoPlaying(): Boolean = mainVideoPlaying
    }

    private class FakeScheduler : VoxAdaptiveVolumeController.TickScheduler {
        val scheduled = mutableListOf<Runnable>()
        override fun schedule(runnable: Runnable, delayMs: Long) {
            scheduled.add(runnable)
        }
        override fun cancel(runnable: Runnable) {
            scheduled.remove(runnable)
        }
    }

    private class FakeLevelProcessor(var currentRms: Float? = null) : VoxAudioLevelProcessor() {
        override fun getLatestRms(maxAgeMs: Long): Float? = currentRms
    }

    private lateinit var bridge: FakeBridge
    private lateinit var scheduler: FakeScheduler
    private lateinit var policy: VoxSmartDuckingPolicy
    private lateinit var volumeLinkCoordinator: VoxVolumeLinkCoordinator
    private lateinit var levelProcessor: FakeLevelProcessor
    private lateinit var controller: VoxAdaptiveVolumeController

    @Before
    fun setUp() {
        bridge = FakeBridge(videoVolume = 0.85f, muted = false)
        scheduler = FakeScheduler()
        policy = VoxSmartDuckingPolicy()
        volumeLinkCoordinator = VoxVolumeLinkCoordinator(0, 100)
        levelProcessor = FakeLevelProcessor(currentRms = null)

        controller = VoxAdaptiveVolumeController(bridge, scheduler, policy, volumeLinkCoordinator)
        controller.setLevelProcessor(levelProcessor)
        controller.setMode(VoxAdaptiveVolumeController.AutoVolumeMode.ADAPTIVE)
        controller.setFixedTarget(0.15f)
        controller.setSyncVolumeEnabled(false)
    }

    @Test
    fun testScenarioA_initialBaselineCapture() {
        controller.start(0.85f, false)
        assertTrue(controller.isActive)
        assertEquals(0.85f, controller.duckingState.baseline, 0.001f)
        assertFalse(bridge.muted)
    }

    @Test
    fun testScenarioB_silenceDuringPlayback_maintainsBaselineVolume() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.002f // well below speech threshold

        // Perform multiple 50ms ticks
        for (i in 1..10) {
            controller.performTick()
        }

        assertEquals(0.85f, bridge.videoVolume, 0.01f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.IDLE, controller.duckingState.duckingState)
    }

    @Test
    fun testScenarioC_speechBegins_smoothlyDucksToTarget() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f // Speech active

        // First tick initiates attack
        controller.performTick()
        assertTrue(bridge.videoVolume < 0.85f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.ATTACK, controller.duckingState.duckingState)

        // Multiple ticks smoothly transition to ducked target (0.15)
        for (i in 1..25) {
            controller.performTick()
        }

        assertEquals(0.15f, bridge.videoVolume, 0.02f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.DUCKED, controller.duckingState.duckingState)
    }

    @Test
    fun testScenarioD_speechPauseWithinHold_preventsPumping() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }
        assertEquals(0.15f, bridge.videoVolume, 0.02f)

        // Translator pauses for 300ms (< 520ms hold)
        levelProcessor.currentRms = 0.002f
        for (i in 1..6) { // 6 * 50ms = 300ms
            controller.performTick()
            assertTrue("Volume must remain ducked during brief pause", bridge.videoVolume <= 0.17f)
            assertTrue(controller.duckingState.speechGateOpen)
        }
    }

    @Test
    fun testScenarioE_speechResumesDuringHold_staysDuckedWithoutGlitch() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }

        // Brief silence 200ms
        levelProcessor.currentRms = 0.002f
        for (i in 1..4) {
            controller.performTick()
        }

        // Speech resumes
        levelProcessor.currentRms = 0.045f
        for (i in 1..5) {
            controller.performTick()
        }

        assertEquals(0.15f, bridge.videoVolume, 0.02f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.DUCKED, controller.duckingState.duckingState)
    }

    @Test
    fun testScenarioF_silenceExceedsHold_smoothlyReleasesToBaseline() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }
        assertEquals(0.15f, bridge.videoVolume, 0.02f)

        // Extended silence (> 520ms + release time)
        levelProcessor.currentRms = 0.001f
        // 12 ticks for hold expiration (600ms) + 30 ticks for release ramp (~1500ms)
        for (i in 1..45) {
            controller.performTick()
        }

        assertEquals(0.85f, bridge.videoVolume, 0.02f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.IDLE, controller.duckingState.duckingState)
    }

    @Test
    fun testScenarioG_remoteVolumeAdjustment_updatesBaseline() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }
        assertEquals(0.15f, bridge.videoVolume, 0.02f)

        // User changes TV volume using remote while ducked (e.g. from 0.85 to 0.60)
        controller.onUserVideoVolumeChanged(0.60f)
        assertEquals(0.60f, controller.duckingState.baseline, 0.001f)

        // When speech stops and releases, volume should restore to new baseline (0.60), not old 0.85
        levelProcessor.currentRms = 0.001f
        for (i in 1..45) {
            controller.performTick()
        }

        assertEquals(0.60f, bridge.videoVolume, 0.02f)
    }

    @Test
    fun testScenarioH_hostVideoPaused_stopsDucking() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }

        // Host video paused
        bridge.mainVideoPlaying = false
        levelProcessor.currentRms = 0.001f
        for (i in 1..45) {
            controller.performTick()
        }

        // Must unduck
        assertEquals(0.85f, bridge.videoVolume, 0.02f)
    }

    @Test
    fun testScenarioI_translationPaused_releasesToBaseline() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }

        // Translation player paused
        bridge.translationPlaying = false
        levelProcessor.currentRms = 0.001f
        for (i in 1..45) {
            controller.performTick()
        }

        assertEquals(0.85f, bridge.videoVolume, 0.02f)
    }

    @Test
    fun testScenarioJ_stop_restoresBaselineAndMuteState() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }
        assertEquals(0.15f, bridge.videoVolume, 0.02f)

        // Stop translation
        controller.stop()
        assertFalse(controller.isActive)
        assertEquals(0.85f, bridge.videoVolume, 0.001f)
        assertFalse(bridge.muted)
    }

    @Test
    fun testScenarioK_mutedOnStart_preservedAndRestored() {
        // Video was muted before translation
        bridge.muted = true
        bridge.videoVolume = 0.0f
        controller.start(0.0f, true)

        levelProcessor.currentRms = 0.05f
        controller.performTick()

        // Stop translation -> must restore muted state
        controller.stop()
        assertTrue(bridge.muted)
    }

    @Test
    fun testScenarioL_volumeSyncActive_overridesToClassicFixedDucking() {
        controller.setSyncVolumeEnabled(true)
        controller.start(0.85f, false)

        // Even with 0 RMS (silence), syncVolume forces classic fixed ducking
        levelProcessor.currentRms = 0.0f
        for (i in 1..25) {
            controller.performTick()
        }

        // Volume must duck to 0.15 fixed target
        assertEquals(0.15f, bridge.videoVolume, 0.02f)
    }

    @Test
    fun testScenarioM_rmsMissingGracePeriod_fallbackProtection() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }
        assertEquals(0.15f, bridge.videoVolume, 0.02f)

        // Audio tap drops frames (RMS missing / null)
        levelProcessor.currentRms = null

        // Ticks within rmsMissingGraceMs (200ms) keep speech gate open
        for (i in 1..3) { // 3 * 50ms = 150ms < 200ms
            controller.performTick()
            assertTrue(controller.duckingState.speechGateOpen)
        }
    }

    @Test
    fun testScenarioN_videoSwitch_cleansState() {
        controller.start(0.85f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }

        controller.onVideoChanged()
        assertFalse(controller.isActive)
        assertEquals(0.85f, bridge.videoVolume, 0.001f)
    }
}

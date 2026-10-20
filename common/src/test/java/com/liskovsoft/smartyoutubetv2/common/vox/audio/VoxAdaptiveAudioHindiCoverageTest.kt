package com.liskovsoft.smartyoutubetv2.common.vox.audio

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack
import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioTrackHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * Strict unit test suite verifying Phase C requirements:
 * 1. silence -> no ducking
 * 2. translation speech -> duck original (-12dB to -18dB)
 * 3. smooth transition (no pops/clicks)
 * 4. recovery after speech ends (smooth release)
 * 5. multi-segment hindi continuity
 * 6. trailing segment not dropped
 * 7. correct audio track selected
 * 8. coverage metric calculated correctly
 */
class VoxAdaptiveAudioHindiCoverageTest {

    private class TestBridge(
        var videoVolume: Float = 1.0f,
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

    private class TestScheduler : VoxAdaptiveVolumeController.TickScheduler {
        val tasks = mutableListOf<Runnable>()
        override fun schedule(runnable: Runnable, delayMs: Long) {
            tasks.add(runnable)
        }
        override fun cancel(runnable: Runnable) {
            tasks.remove(runnable)
        }
    }

    private class TestLevelProcessor : VoxAudioLevelProcessor() {
        var currentRms: Float? = null
        override fun getLatestRms(maxAgeMs: Long): Float? = currentRms
    }

    private class TestFormatItem(
        private val id: Int,
        private val formatId: String,
        private val language: String?,
        private val isDefaultTrack: Boolean = false,
        private val isSelectedTrack: Boolean = false
    ) : FormatItem {
        override fun getId(): Int = id
        override fun getFormatId(): String = formatId
        override fun getLanguage(): String? = language
        override fun isDefault(): Boolean = isDefaultTrack
        override fun isSelected(): Boolean = isSelectedTrack
        override fun getTitle(): CharSequence = language ?: ""
        override fun isPreset(): Boolean = false
        override fun getFrameRate(): Float = 0f
        override fun getWidth(): Int = 0
        override fun getHeight(): Int = 0
        override fun getType(): Int = FormatItem.TYPE_AUDIO
        override fun getTrack(): MediaTrack? = null
    }

    private lateinit var bridge: TestBridge
    private lateinit var scheduler: TestScheduler
    private lateinit var policy: VoxSmartDuckingPolicy
    private lateinit var levelProcessor: TestLevelProcessor
    private lateinit var controller: VoxAdaptiveVolumeController
    private var simulatedTimeMs: Long = 1000L

    @Before
    fun setUp() {
        simulatedTimeMs = 1000L
        bridge = TestBridge(videoVolume = 1.0f, muted = false)
        scheduler = TestScheduler()
        policy = VoxSmartDuckingPolicy()
        levelProcessor = TestLevelProcessor()

        controller = VoxAdaptiveVolumeController(
            bridge,
            scheduler,
            policy,
            VoxVolumeLinkCoordinator(0, 100)
        )
        controller.setClock {
            val t = simulatedTimeMs
            simulatedTimeMs += 50L
            t
        }
        controller.setLevelProcessor(levelProcessor)
        controller.setMode(VoxAdaptiveVolumeController.AutoVolumeMode.ADAPTIVE)
        controller.setFixedTarget(0.15f)
        controller.setSyncVolumeEnabled(false)
    }

    // 1. silence -> no ducking
    @Test
    fun test1_silenceProducesNoDucking() {
        controller.start(1.0f, false)
        levelProcessor.currentRms = 0.002f // Silence below 0.012 threshold

        for (i in 1..20) {
            controller.performTick()
        }

        assertEquals(1.0f, bridge.videoVolume, 0.01f)
        assertFalse("Speech gate must remain closed on silence", controller.duckingState.speechGateOpen)
        assertFalse("Audio must not duck on silence", controller.duckingState.isDucked)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.IDLE, controller.duckingState.duckingState)
    }

    // 2. translation speech -> duck original (-12dB to -18dB)
    @Test
    fun test2_translationSpeechDucksOriginalWithin12to18dB() {
        controller.start(1.0f, false)
        levelProcessor.currentRms = 0.05f // Active Russian speech

        // Tick until ducked volume settles
        for (i in 1..30) {
            controller.performTick()
        }

        val settledVol = bridge.videoVolume
        assertTrue("Volume must duck below 0.30", settledVol < 0.30f)
        assertTrue("Volume must remain positive", settledVol > 0.05f)

        // Convert gain to dB: dB = 20 * log10(gain / 1.0)
        val attenuationDb = 20.0 * log10(settledVol.toDouble())
        // Target 0.15 gain corresponds to ~ -16.48 dB (within -12dB .. -18dB)
        assertTrue(
            "Attenuation $attenuationDb dB must fall between -18dB and -12dB",
            attenuationDb >= -18.0 && attenuationDb <= -12.0
        )
        assertEquals(VoxSmartDuckingPolicy.DuckingState.DUCKED, controller.duckingState.duckingState)
    }

    // 3. smooth transition (no pops/clicks)
    @Test
    fun test3_smoothTransitionsWithoutPopsOrAbruptJumps() {
        controller.start(1.0f, false)
        levelProcessor.currentRms = 0.06f

        var prevVolume = bridge.videoVolume
        val maxAllowedDownwardDelta = 3.5f * 0.05f + 0.01f // maxDownPerSec * dt + tolerance

        for (i in 1..25) {
            controller.performTick()
            val currentVol = bridge.videoVolume
            val delta = abs(currentVol - prevVolume)
            assertTrue(
                "Downward step delta $delta exceeds safe slew rate $maxAllowedDownwardDelta",
                delta <= maxAllowedDownwardDelta
            )
            prevVolume = currentVol
        }

        // Test smooth upward transition on silence
        levelProcessor.currentRms = 0.001f
        // Pass holdMs (520ms)
        for (i in 1..12) {
            controller.performTick()
        }

        val maxAllowedUpwardDelta = 0.9f * 0.05f + 0.01f // maxUpPerSec * dt + tolerance
        for (i in 1..40) {
            controller.performTick()
            val currentVol = bridge.videoVolume
            val delta = abs(currentVol - prevVolume)
            assertTrue(
                "Upward step delta $delta exceeds safe slew rate $maxAllowedUpwardDelta",
                delta <= maxAllowedUpwardDelta
            )
            prevVolume = currentVol
        }
    }

    // 4. recovery after speech ends (smooth release)
    @Test
    fun test4_recoveryAfterSpeechEndsSmoothRelease() {
        controller.start(1.0f, false)
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) {
            controller.performTick()
        }
        assertEquals(0.15f, bridge.videoVolume, 0.02f)

        // Speech ends
        levelProcessor.currentRms = 0.001f

        // Within hold period (300ms < 520ms), volume stays ducked
        for (i in 1..6) {
            controller.performTick()
            assertTrue("Volume should remain ducked during hold", bridge.videoVolume <= 0.17f)
        }

        // Advance through release phase until full baseline recovery
        for (i in 1..65) {
            controller.performTick()
        }

        assertEquals(1.0f, bridge.videoVolume, 0.02f)
        assertFalse(controller.duckingState.isDucked)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.IDLE, controller.duckingState.duckingState)
    }

    // 5. multi-segment hindi continuity
    @Test
    fun test5_multiSegmentHindiContinuity() {
        controller.start(1.0f, false)

        // Segment 1: Russian translated speech active
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) controller.performTick()
        assertEquals(0.15f, bridge.videoVolume, 0.02f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.DUCKED, controller.duckingState.duckingState)

        // Gap 1: 15 seconds of Hindi silence in translation track (original Hindi audible)
        levelProcessor.currentRms = 0.001f
        for (i in 1..70) controller.performTick()
        assertEquals(1.0f, bridge.videoVolume, 0.02f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.IDLE, controller.duckingState.duckingState)

        // Segment 2: Another Russian translation sentence arrives
        levelProcessor.currentRms = 0.045f
        for (i in 1..25) controller.performTick()
        assertEquals(0.15f, bridge.videoVolume, 0.02f)
        assertEquals(VoxSmartDuckingPolicy.DuckingState.DUCKED, controller.duckingState.duckingState)

        // Gap 2: Another silence interval
        levelProcessor.currentRms = 0.001f
        for (i in 1..70) controller.performTick()
        assertEquals(1.0f, bridge.videoVolume, 0.02f)

        // Verify metrics tracked multiple segments
        val metrics = controller.timelineMetrics
        assertTrue("Gaps must be tracked across multi-segments", metrics.gapCount >= 2)
        assertTrue("Source duration must accumulate", metrics.sourceSpeechDurationSec > 0)
        assertTrue("Translated duration must accumulate", metrics.translatedSpeechDurationSec > 0)
    }

    // 6. trailing segment not dropped
    @Test
    fun test6_trailingSegmentNotDropped() {
        controller.start(1.0f, false)

        // Silence for first 10 seconds
        levelProcessor.currentRms = 0.001f
        for (i in 1..50) controller.performTick()

        // Trailing segment at the end of the video
        levelProcessor.currentRms = 0.05f
        for (i in 1..25) controller.performTick()

        val metrics = controller.timelineMetrics
        assertTrue("Trailing segment must register translated speech", metrics.translatedSpeechDurationMs > 0L)
        assertEquals(0.15f, bridge.videoVolume, 0.02f)
        assertEquals(0L, metrics.trailingSilenceMs)
    }

    // 7. correct audio track selected
    @Test
    fun test7_correctAudioTrackSelected() {
        // Case A: Multilingual track list with Hindi original and English dub
        val trackHindiOriginal = TestFormatItem(1, "hi_orig", "hi (original)")
        val trackEnglishDub = TestFormatItem(2, "en_dub", "en")
        val formatsA = listOf(trackHindiOriginal, trackEnglishDub)

        val bestA = VotAudioTrackHelper.findBestOriginalForYandex(formatsA)
        assertNotNull(bestA)
        // System prioritizes English track for high-coverage Yandex translation
        assertEquals("en", bestA?.language)

        // Case B: Content only has Hindi original and Russian YouTube auto-dub
        val trackHindiOnly = TestFormatItem(10, "hi_orig", "hi (original)")
        val trackRuDub = TestFormatItem(11, "ru_dub", "ru (dubbed-auto)")
        val formatsB = listOf(trackHindiOnly, trackRuDub)

        val bestB = VotAudioTrackHelper.findBestOriginalForYandex(formatsB)
        assertNotNull(bestB)
        assertEquals("hi (original)", bestB?.language)

        // Case C: Track language detection for Hindi
        val hindiTrackInfo = VotAudioTrackHelper.from(trackHindiOnly)
        assertEquals("hi", hindiTrackInfo.langCode)
        assertTrue(VotAudioTrackHelper.isExplicitNonRussian(hindiTrackInfo))
        assertFalse(VotAudioTrackHelper.isRussianLang(hindiTrackInfo.langCode))
        assertTrue(VotAudioTrackHelper.shouldStartYandex(hindiTrackInfo))
    }

    // 8. coverage metric calculated correctly
    @Test
    fun test8_coverageMetricCalculatedCorrectly() {
        val tracker = VoxTranslationTimelineTracker(
            speechThresholdRms = 0.012f,
            silenceHoldMs = 520L,
            periodicLogIntervalMs = 0L
        )

        var time = 1000L
        // Initialize tracker
        tracker.onTick(time, isMainPlaying = true, isTranslationPlaying = true, currentRms = 0.0f, originalGain = 1.0f, rusGain = 1.0f)

        // 1. Simulate 52 seconds of active translated speech (520 ticks * 100ms)
        for (i in 1..520) {
            time += 100L
            tracker.onTick(
                nowMs = time,
                isMainPlaying = true,
                isTranslationPlaying = true,
                currentRms = 0.05f, // active speech
                originalGain = 0.15f,
                rusGain = 1.0f
            )
        }

        // 2. Simulate 3 distinct gaps totaling 188 seconds of silence (total time = 240 seconds)
        // Gap 1: 50 seconds
        for (i in 1..500) {
            time += 100L
            tracker.onTick(
                nowMs = time,
                isMainPlaying = true,
                isTranslationPlaying = true,
                currentRms = 0.001f, // silence
                originalGain = 1.0f,
                rusGain = 1.0f
            )
        }

        // Gap 2: 50 seconds after brief 1s speech burst (10 ticks)
        for (i in 1..10) {
            time += 100L
            tracker.onTick(time, isMainPlaying = true, isTranslationPlaying = true, currentRms = 0.04f, originalGain = 0.15f, rusGain = 1.0f)
        }
        for (i in 1..500) {
            time += 100L
            tracker.onTick(time, isMainPlaying = true, isTranslationPlaying = true, currentRms = 0.001f, originalGain = 1.0f, rusGain = 1.0f)
        }

        // Speech burst 3: 1s speech burst (10 ticks)
        for (i in 1..10) {
            time += 100L
            tracker.onTick(time, isMainPlaying = true, isTranslationPlaying = true, currentRms = 0.04f, originalGain = 0.15f, rusGain = 1.0f)
        }

        // Gap 3 (trailing silence): 20 seconds (200 ticks * 100ms)
        for (i in 1..200) {
            time += 100L
            tracker.onTick(time, isMainPlaying = true, isTranslationPlaying = true, currentRms = 0.001f, originalGain = 1.0f, rusGain = 1.0f)
        }

        val metrics = tracker.currentMetrics()
        assertTrue("Source speech duration must be positive", metrics.sourceSpeechDurationSec > 0)
        assertTrue("Translated speech duration must be positive", metrics.translatedSpeechDurationSec > 0)
        assertTrue("Coverage percent must be clamped between 0 and 100", metrics.translationCoveragePercent in 0.0..100.0)
        assertTrue("Gaps must be detected", metrics.gapCount >= 2)
        assertEquals(20_000L, metrics.trailingSilenceMs)

        // Test math precision with exact values
        val exactTracker = VoxTranslationTimelineTracker()
        var exactTime = 1000L
        exactTracker.onTick(exactTime, true, true, 0f, 1f, 1f)

        // 52 seconds speech (520 ticks * 100ms = 52000 ms)
        for (i in 1..520) {
            exactTime += 100L
            exactTracker.onTick(exactTime, true, true, 0.05f, 0.15f, 1.0f)
        }

        // 188 seconds silence (1880 ticks * 100ms = 188000 ms)
        for (i in 1..1880) {
            exactTime += 100L
            exactTracker.onTick(exactTime, true, true, 0.001f, 1.0f, 1.0f)
        }

        val exactMetrics = exactTracker.currentMetrics()
        assertEquals(240.0, exactMetrics.sourceSpeechDurationSec, 0.01)
        assertEquals(52.0, exactMetrics.translatedSpeechDurationSec, 0.01)
        // 52 / 240 = 21.6666...%
        assertEquals(21.67, exactMetrics.translationCoveragePercent, 0.1)

        // Verify telemetry logging executes cleanly
        exactTracker.logMetrics("TEST_VERIFICATION")
    }
}

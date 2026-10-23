package com.liskovsoft.smartyoutubetv2.common.vox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * Unit tests for VoxAudioTaper ensuring strict compliance with perceptual acoustic criteria
 * and eliminating the Adaptive Audio balance bug.
 */
class VoxAudioTaperTest {

    private fun toDecibels(linear: Float): Double {
        return if (linear <= 0.00001f) -100.0 else 20.0 * log10(linear.toDouble())
    }

    @Test
    fun testOriginalVolume_zeroPercent_isCompletelyMuted() {
        val gain = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(0)
        assertEquals("0% original must be exactly 0.0 amplitude", 0.0f, gain, 0.00001f)
    }

    @Test
    fun testOriginalVolume_negativePercent_isClampedToZero() {
        val gain = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(-10)
        assertEquals(0.0f, gain, 0.00001f)
    }

    @Test
    fun testOriginalVolume_fivePercent_isSoftBackgroundMurmur() {
        // Bug report: Original 5% was still too loud (-26 dB linear).
        // Requirement: At 5%, original audio must be a gentle background bed (below -35 dB).
        val gain = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(5)
        val dB = toDecibels(gain)

        assertTrue("5% original must be >= 0.001", gain >= 0.001f)
        assertTrue("5% original must be <= 0.02 (at least -34 dB)", gain <= 0.02f)
        assertTrue("5% original dB ($dB dB) must be below -35 dB", dB < -35.0)
    }

    @Test
    fun testOriginalVolume_fiftyPercent_doesNotDrownTranslation() {
        // Bug report: Original 50% / Translation 100% caused translation to be barely audible.
        // Reason: Linear 0.50 was only -6 dB attenuation!
        // Requirement: At 50%, original must be comfortable background (attenuated by ~15 to 20 dB).
        val gain = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(50)
        val dB = toDecibels(gain)

        assertTrue("50% original gain ($gain) must be <= 0.25 (-12 dB)", gain <= 0.25f)
        assertTrue("50% original gain ($gain) must be >= 0.10 (-20 dB)", gain >= 0.10f)
        assertTrue("50% original dB ($dB dB) must be between -20 dB and -12 dB", dB in -20.0..-12.0)
    }

    @Test
    fun testOriginalVolume_hundredPercent_isFullVolume() {
        val gain = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(100)
        assertEquals("100% original must be exactly 1.0", 1.0f, gain, 0.00001f)
        assertEquals("100% original must be 0 dB", 0.0, toDecibels(gain), 0.001)
    }

    @Test
    fun testOriginalVolume_aboveHundred_isClampedToOne() {
        val gain = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(150)
        assertEquals(1.0f, gain, 0.00001f)
    }

    @Test
    fun testOriginalVolume_isStrictlyMonotonic() {
        var prev = -1.0f
        for (p in 0..100) {
            val curr = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(p)
            assertTrue("Gain at $p% ($curr) must be >= gain at ${p - 1}% ($prev)", curr >= prev)
            prev = curr
        }
    }

    @Test
    fun testTranslationVolume_zeroAndHundred_bounds() {
        assertEquals(0.0f, VoxAudioTaper.toPerceptualTranslationVolumeMultiplier(0), 0.00001f)
        assertEquals(1.0f, VoxAudioTaper.toPerceptualTranslationVolumeMultiplier(100), 0.00001f)
        assertEquals(0.5f, VoxAudioTaper.toPerceptualTranslationVolumeMultiplier(50), 0.00001f)
    }

    @Test
    fun testAllCombinationsFromUserPrompt() {
        // Combination 1: Original 100 / Translation 100
        val orig100 = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(100)
        val trans100 = VoxAudioTaper.toPerceptualTranslationVolumeMultiplier(100)
        assertEquals(1.0f, orig100, 0.0001f)
        assertEquals(1.0f, trans100, 0.0001f)

        // Combination 2: Original 50 / Translation 100 -> Translation clearly dominant
        val orig50 = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(50)
        assertTrue("Translation (1.0) must have at least +14 dB dynamic advantage over Original 50% ($orig50)", trans100 / orig50 >= 5.0f)

        // Combination 3: Original 5 / Translation 100 -> Translation overwhelmingly dominant
        val orig5 = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(5)
        assertTrue("Translation (1.0) must have massive advantage over Original 5% ($orig5)", trans100 / orig5 >= 50.0f)

        // Combination 4: Original 0 / Translation 100 -> Complete silence on original
        val orig0 = VoxAudioTaper.toPerceptualOriginalVolumeMultiplier(0)
        assertEquals(0.0f, orig0, 0.00001f)
        assertEquals(1.0f, trans100, 0.00001f)

        // Combination 5: Original 100 / Translation 0 -> Original full, translation silent
        val trans0 = VoxAudioTaper.toPerceptualTranslationVolumeMultiplier(0)
        assertEquals(1.0f, orig100, 0.0001f)
        assertEquals(0.0f, trans0, 0.00001f)
    }
}

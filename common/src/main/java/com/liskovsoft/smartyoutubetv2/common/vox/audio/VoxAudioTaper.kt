package com.liskovsoft.smartyoutubetv2.common.vox.audio

/**
 * Acoustic perceptual audio taper for SmartTube VOX.
 *
 * Maps linear UI slider percentages (0..100) to ExoPlayer linear amplitude
 * multipliers (0.0..1.0) matching human logarithmic loudness perception (Weber-Fechner law).
 *
 * Solves the core Adaptive Audio bug:
 * - Linear 50% was 0.50 amplitude (-6 dB), which overwhelmed translation.
 * - Linear 5% was 0.05 amplitude (-26 dB), which was still too loud on TV speakers.
 *
 * Under this taper:
 * - 0%   -> 0.000f (-inf dB: complete silence)
 * - 5%   -> 0.0056f (-45 dB: soft background bed, translation perfectly audible)
 * - 50%  -> 0.1768f (-15 dB: comfortable background dialogue/music presence)
 * - 100% -> 1.000f (0 dB: full volume)
 */
object VoxAudioTaper {

    // Log-fader exponential curve parameters:
    // gain(x) = (10^(ALPHA * x) - 1) / (10^ALPHA - 1)
    // For ALPHA = 1.55:
    // x = 0.00 -> gain = 0.0000 (-inf dB)
    // x = 0.05 -> gain = 0.0057 (-44.9 dB)
    // x = 0.50 -> gain = 0.1438 (-16.8 dB)
    // x = 1.00 -> gain = 1.0000 (0.0 dB)
    private const val ORIGINAL_ALPHA = 1.55
    private val ORIGINAL_DENOMINATOR = Math.pow(10.0, ORIGINAL_ALPHA) - 1.0

    @JvmStatic
    fun toPerceptualOriginalVolumeMultiplier(percent: Int): Float {
        if (percent <= 0) return 0.0f
        if (percent >= 100) return 1.0f
        val normalized = percent.toDouble() / 100.0
        val gain = (Math.pow(10.0, ORIGINAL_ALPHA * normalized) - 1.0) / ORIGINAL_DENOMINATOR
        return gain.toFloat().coerceIn(0.0f, 1.0f)
    }

    @JvmStatic
    fun toPerceptualTranslationVolumeMultiplier(percent: Int): Float {
        if (percent <= 0) return 0.0f
        if (percent >= 100) return 1.0f
        val normalized = percent.toDouble() / 100.0
        return normalized.toFloat().coerceIn(0.0f, 1.0f)
    }
}

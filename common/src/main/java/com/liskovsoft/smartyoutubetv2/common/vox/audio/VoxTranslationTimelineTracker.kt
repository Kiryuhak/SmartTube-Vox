package com.liskovsoft.smartyoutubetv2.common.vox.audio

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, deterministic timeline tracker measuring source speech vs translated speech coverage.
 * Computes sourceSpeechDuration, translatedSpeechDuration, translationCoveragePercent,
 * gapCount, and trailingSilenceMs, and logs telemetry via VoxSafeLogger.
 */
class VoxTranslationTimelineTracker(
    val speechThresholdRms: Float = DEFAULT_SPEECH_THRESHOLD_RMS,
    val silenceHoldMs: Long = DEFAULT_SILENCE_HOLD_MS,
    val periodicLogIntervalMs: Long = DEFAULT_PERIODIC_LOG_INTERVAL_MS
) {
    companion object {
        const val DEFAULT_SPEECH_THRESHOLD_RMS = 0.012f
        const val DEFAULT_SILENCE_HOLD_MS = 520L
        const val DEFAULT_PERIODIC_LOG_INTERVAL_MS = 10_000L
    }

    data class Metrics(
        val sourceSpeechDurationMs: Long = 0L,
        val translatedSpeechDurationMs: Long = 0L,
        val sourceSpeechDurationSec: Double = 0.0,
        val translatedSpeechDurationSec: Double = 0.0,
        val translationCoveragePercent: Double = 0.0,
        val gapCount: Int = 0,
        val trailingSilenceMs: Long = 0L,
        val rusAudioRms: Float = 0.0f,
        val originalGain: Float = 1.0f,
        val rusGain: Float = 1.0f
    )

    private var sourceSpeechDurationMs: Long = 0L
    private var translatedSpeechDurationMs: Long = 0L
    private var gapCount: Int = 0
    private var trailingSilenceMs: Long = 0L

    private var lastTickAt: Long = 0L
    private var lastSpeechAt: Long = 0L
    private var lastPeriodicLogAt: Long = 0L
    private var isCurrentlyInSpeech: Boolean = false

    private var latestRms: Float = 0.0f
    private var latestOriginalGain: Float = 1.0f
    private var latestRusGain: Float = 1.0f

    @Synchronized
    fun reset() {
        sourceSpeechDurationMs = 0L
        translatedSpeechDurationMs = 0L
        gapCount = 0
        trailingSilenceMs = 0L
        lastTickAt = 0L
        lastSpeechAt = 0L
        lastPeriodicLogAt = 0L
        isCurrentlyInSpeech = false
        latestRms = 0.0f
        latestOriginalGain = 1.0f
        latestRusGain = 1.0f
    }

    @Synchronized
    fun onTick(
        nowMs: Long,
        isMainPlaying: Boolean,
        isTranslationPlaying: Boolean,
        currentRms: Float,
        originalGain: Float,
        rusGain: Float
    ): Metrics {
        latestRms = max(0.0f, currentRms)
        latestOriginalGain = max(0.0f, min(1.0f, originalGain))
        latestRusGain = max(0.0f, rusGain)

        if (lastTickAt <= 0L) {
            lastTickAt = nowMs
            lastPeriodicLogAt = nowMs
            return currentMetrics()
        }

        val dtMs = max(0L, min(1000L, nowMs - lastTickAt))
        lastTickAt = nowMs

        if (isMainPlaying) {
            sourceSpeechDurationMs += dtMs
        }

        val speechDetected = isTranslationPlaying && (latestRms >= speechThresholdRms)

        if (speechDetected) {
            translatedSpeechDurationMs += dtMs
            lastSpeechAt = nowMs
            trailingSilenceMs = 0L

            if (!isCurrentlyInSpeech) {
                isCurrentlyInSpeech = true
            }
        } else {
            if (isMainPlaying) {
                trailingSilenceMs += dtMs
            }

            if (isCurrentlyInSpeech) {
                if (nowMs - lastSpeechAt >= silenceHoldMs) {
                    isCurrentlyInSpeech = false
                    gapCount++
                }
            }
        }

        if (periodicLogIntervalMs > 0 && (nowMs - lastPeriodicLogAt >= periodicLogIntervalMs)) {
            lastPeriodicLogAt = nowMs
            logMetrics("PERIODIC")
        }

        return currentMetrics()
    }

    @Synchronized
    fun currentMetrics(): Metrics {
        val srcSec = sourceSpeechDurationMs / 1000.0
        val transSec = translatedSpeechDurationMs / 1000.0
        val coverage = if (sourceSpeechDurationMs > 0L) {
            min(100.0, (translatedSpeechDurationMs.toDouble() / sourceSpeechDurationMs.toDouble()) * 100.0)
        } else {
            0.0
        }

        return Metrics(
            sourceSpeechDurationMs = sourceSpeechDurationMs,
            translatedSpeechDurationMs = translatedSpeechDurationMs,
            sourceSpeechDurationSec = srcSec,
            translatedSpeechDurationSec = transSec,
            translationCoveragePercent = coverage,
            gapCount = gapCount,
            trailingSilenceMs = trailingSilenceMs,
            rusAudioRms = latestRms,
            originalGain = latestOriginalGain,
            rusGain = latestRusGain
        )
    }

    @Synchronized
    fun logMetrics(trigger: String) {
        val m = currentMetrics()
        val context = linkedMapOf<String, String>()
        context["TRIGGER"] = trigger
        context["SOURCE_SPEECH"] = String.format(Locale.US, "%.1fs", m.sourceSpeechDurationSec)
        context["TRANSLATED_SPEECH"] = String.format(Locale.US, "%.1fs", m.translatedSpeechDurationSec)
        context["sourceSpeechDuration"] = String.format(Locale.US, "%.1fs", m.sourceSpeechDurationSec)
        context["translatedSpeechDuration"] = String.format(Locale.US, "%.1fs", m.translatedSpeechDurationSec)
        context["translationCoveragePercent"] = String.format(Locale.US, "%.1f%%", m.translationCoveragePercent)
        context["gapCount"] = m.gapCount.toString()
        context["trailingSilenceMs"] = "${m.trailingSilenceMs}ms"
        context["RUS_AUDIO_RMS"] = String.format(Locale.US, "%.4f", m.rusAudioRms)
        context["ORIGINAL_GAIN"] = String.format(Locale.US, "%.2f", m.originalGain)
        context["RUS_GAIN"] = String.format(Locale.US, "%.2f", m.rusGain)

        VoxSafeLogger.d(
            VoxLogCategory.TRANSLATION,
            VoxLogCode.TRANSLATION_TIMELINE_COVERAGE,
            "Timeline coverage: src=${context["SOURCE_SPEECH"]}, trans=${context["TRANSLATED_SPEECH"]}, coverage=${context["translationCoveragePercent"]}, gaps=${m.gapCount}",
            context
        )
    }
}

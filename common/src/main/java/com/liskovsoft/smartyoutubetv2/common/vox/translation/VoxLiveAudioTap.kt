package com.liskovsoft.smartyoutubetv2.common.vox.translation

import java.nio.ByteBuffer

/**
 * Точка безопасного перехвата аудиопотока из декодера ExoPlayer (Live Audio Tap, Section 53).
 *
 * Перехватывает декодированные PCM-буферы в DelayMediaCodecAudioRenderer с аппаратными PTS.
 * Когда флаг VOX_LIVE_AUDIO_CAPTURE_EXPERIMENTAL отключен (по умолчанию), проверка isEnabled()
 * возвращает false и имеет нулевой оверхед (zero overhead) на аудиопоток.
 */
object VoxLiveAudioTap {

    @Volatile
    private var _enabled: Boolean = VoxLiveFeatureFlags.VOX_LIVE_AUDIO_CAPTURE_EXPERIMENTAL

    private val lock = Any()
    private var _normalizer = VoxLiveAudioPtsNormalizer()
    private var _assembler = VoxLiveAudioFragmentAssembler()
    private var _listener: VoxLiveAudioTapListener? = null

    // Метрики Zero-Telemetry
    @Volatile
    private var totalCapturedBytes: Long = 0L
    @Volatile
    private var totalCapturedFrames: Long = 0L
    @Volatile
    private var lastPtsUs: Long = 0L

    @JvmStatic
    fun isEnabled(): Boolean {
        return _enabled && VoxLiveFeatureFlags.VOX_LIVE_AUDIO_CAPTURE_EXPERIMENTAL
    }

    @JvmStatic
    fun setEnabled(value: Boolean) {
        _enabled = value
    }

    @JvmStatic
    fun setListener(newListener: VoxLiveAudioTapListener?) {
        synchronized(lock) {
            _listener = newListener
        }
    }

    @JvmStatic
    fun getAssembler(): VoxLiveAudioFragmentAssembler = _assembler

    @JvmStatic
    fun getNormalizer(): VoxLiveAudioPtsNormalizer = _normalizer

    /**
     * Вызывается из DelayMediaCodecAudioRenderer.processOutputBuffer при успешном выводе буфера.
     */
    @JvmStatic
    fun onAudioOutputBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        sampleRate: Int,
        channelCount: Int,
        encoding: Int
    ) {
        if (!isEnabled()) return

        val remaining = buffer.remaining()
        if (remaining <= 0) return

        // Быстрое извлечение байт без изменения исходной позиции буфера ExoPlayer
        val bytes = ByteArray(remaining)
        val pos = buffer.position()
        buffer.get(bytes)
        buffer.position(pos)

        val bytesPerSample = 2 // PCM 16-bit
        val channels = channelCount.coerceAtLeast(1)
        val bytesPerFrame = channels * bytesPerSample
        val frames = remaining / bytesPerFrame
        val sRate = sampleRate.coerceAtLeast(8000)
        val durationUs = (frames * 1_000_000L) / sRate

        synchronized(lock) {
            val normResult = _normalizer.normalize(presentationTimeUs)

            if (normResult.isDiscontinuity) {
                _assembler.onDiscontinuity(normResult.generation)
                _listener?.onDiscontinuity(
                    oldGeneration = normResult.oldGeneration,
                    newGeneration = normResult.generation,
                    newBasePtsUs = presentationTimeUs
                )
            }

            val captured = VoxLiveCapturedBuffer(
                data = bytes,
                ptsUs = normResult.normalizedPtsUs,
                durationUs = durationUs,
                sampleRate = sRate,
                channelCount = channels,
                encoding = encoding,
                generation = normResult.generation
            )

            _assembler.pushBuffer(captured)
            _listener?.onCapturedBuffer(captured)

            totalCapturedBytes += remaining
            totalCapturedFrames++
            lastPtsUs = normResult.normalizedPtsUs
        }
    }

    @JvmStatic
    fun reset() {
        synchronized(lock) {
            _normalizer.reset()
            _assembler.clear()
            totalCapturedBytes = 0L
            totalCapturedFrames = 0L
            lastPtsUs = 0L
        }
    }

    @JvmStatic
    fun getDiagnostics(): VoxLiveAudioSyncDiagnostics {
        synchronized(lock) {
            return VoxLiveAudioSyncDiagnostics(
                captureActive = isEnabled(),
                routingMode = VoxAudioRoutingMode.ORIGINAL_ONLY,
                generation = _normalizer.getCurrentGeneration(),
                lastPtsUs = lastPtsUs,
                currentDriftMs = 0L,
                maxDriftMs = 0L,
                underrunCount = 0,
                reanchorCount = 0,
                droppedFrames = _assembler.getDroppedFragmentCount(),
                totalCapturedFragments = _assembler.getAssembledFragmentCount(),
                totalCapturedBytes = totalCapturedBytes,
                fallbackActive = false
            )
        }
    }
}

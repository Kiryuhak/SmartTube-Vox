package com.liskovsoft.smartyoutubetv2.common.vox.translation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Коллбэк для управления громкостью основного плеера и обработки откатов (Fallback).
 */
interface VoxSecondaryAudioCallback {
    fun onPrimaryVolumeAdjustRequested(volume: Float)
    fun onUnderrunFallback()
}

/**
 * Прототип вторичного аудиоплеера на базе Android AudioTrack в режиме потокового вывода (Section 56-57).
 *
 * Обеспечивает:
 * 1. Потоковое воспроизведение декодированного или переведённого PCM аудио в фоне.
 * 2. Три режима маршрутизации: ORIGINAL_ONLY (по умолчанию), SECONDARY_ONLY, MIX_DEBUG.
 * 3. Отслеживание метки времени воспроизведения (Playback Head PTS).
 * 4. Немедленный откат на оригинальный звук при опустошении буфера (Underrun Fallback).
 */
class VoxLiveSecondaryAudioPlayer(
    initialRoutingMode: VoxAudioRoutingMode = VoxAudioRoutingMode.ORIGINAL_ONLY,
    initialCallback: VoxSecondaryAudioCallback? = null
) {
    companion object {
        private const val STREAMING_MODE = 1 // AudioTrack.MODE_STREAMING
    }

    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var _routingMode: VoxAudioRoutingMode = initialRoutingMode
    private var _callback: VoxSecondaryAudioCallback? = initialCallback

    private var sampleRate: Int = 48000
    private var channelConfig: Int = AudioFormat.CHANNEL_OUT_STEREO
    private var audioFormatEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    private var anchorPtsUs: Long = -1L
    private var anchorHeadPosition: Long = 0L
    private var currentGeneration: Long = 1L
    private var writtenBytesTotal: Long = 0L
    private var isPlaying: Boolean = false
    private var fallbackActive: Boolean = false
    private var underrunCount: Int = 0

    fun setCallback(cb: VoxSecondaryAudioCallback?) {
        synchronized(lock) {
            _callback = cb
        }
    }

    fun getRoutingMode(): VoxAudioRoutingMode = synchronized(lock) { _routingMode }

    fun setRoutingMode(mode: VoxAudioRoutingMode) {
        synchronized(lock) {
            _routingMode = mode
            applyRoutingVolumes()
        }
    }

    fun initAudioTrack(sampleRateHz: Int = 48000, channels: Int = 2, encoding: Int = AudioFormat.ENCODING_PCM_16BIT): Boolean {
        synchronized(lock) {
            release()

            sampleRate = sampleRateHz.coerceAtLeast(8000)
            channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            audioFormatEncoding = encoding

            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormatEncoding)
            val bufferSize = (minBufferSize * 4).coerceAtLeast(16384)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()

                    val format = AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormatEncoding)
                        .build()

                    audioTrack = AudioTrack(
                        attributes,
                        format,
                        bufferSize,
                        STREAMING_MODE,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    audioTrack = AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        channelConfig,
                        audioFormatEncoding,
                        bufferSize,
                        STREAMING_MODE
                    )
                }

                applyRoutingVolumes()
                return audioTrack?.state == AudioTrack.STATE_INITIALIZED
            } catch (e: Exception) {
                audioTrack = null
                return false
            }
        }
    }

    private fun applyRoutingVolumes() {
        val track = audioTrack
        when (_routingMode) {
            VoxAudioRoutingMode.ORIGINAL_ONLY -> {
                if (track != null) setTrackVolume(track, 0.0f)
                _callback?.onPrimaryVolumeAdjustRequested(1.0f)
            }
            VoxAudioRoutingMode.SECONDARY_ONLY -> {
                if (track != null) setTrackVolume(track, 1.0f)
                _callback?.onPrimaryVolumeAdjustRequested(0.0f)
            }
            VoxAudioRoutingMode.MIX_DEBUG -> {
                if (track != null) setTrackVolume(track, 1.0f)
                _callback?.onPrimaryVolumeAdjustRequested(0.2f)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setTrackVolume(track: AudioTrack, volume: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            track.setVolume(volume)
        } else {
            track.setStereoVolume(volume, volume)
        }
    }

    fun play() {
        synchronized(lock) {
            val track = audioTrack ?: return
            if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
                isPlaying = true
                fallbackActive = false
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            val track = audioTrack ?: return
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                isPlaying = false
            }
        }
    }

    fun flush() {
        synchronized(lock) {
            val track = audioTrack ?: return
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.flush()
                anchorPtsUs = -1L
                anchorHeadPosition = 0L
                writtenBytesTotal = 0L
            }
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                if (audioTrack != null) {
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.stop()
                    }
                    audioTrack?.release()
                }
            } catch (ignored: Exception) {
            } finally {
                audioTrack = null
                isPlaying = false
                anchorPtsUs = -1L
                writtenBytesTotal = 0L
            }
        }
    }

    /**
     * Запись PCM-данных во вторичный плеер.
     */
    fun writeAudio(data: ByteArray, ptsUs: Long, generation: Long): Int {
        synchronized(lock) {
            if (generation < currentGeneration) {
                // Устаревший фрейм от предыдущего поколения
                return 0
            }

            if (generation > currentGeneration) {
                flush()
                currentGeneration = generation
            }

            val track = audioTrack ?: return 0
            if (track.state != AudioTrack.STATE_INITIALIZED) return 0

            if (anchorPtsUs == -1L) {
                anchorPtsUs = ptsUs
                anchorHeadPosition = getPlaybackHeadPositionSafe(track)
            }

            val written = track.write(data, 0, data.size)
            if (written > 0) {
                writtenBytesTotal += written
                if (!isPlaying && writtenBytesTotal >= data.size) {
                    play()
                }
            }
            return written
        }
    }

    /**
     * Оценка текущей метки времени воспроизведения вторичного аудиопотока.
     */
    fun getEstimatedPlaybackPtsUs(): Long {
        synchronized(lock) {
            val track = audioTrack ?: return anchorPtsUs
            if (anchorPtsUs == -1L || track.state != AudioTrack.STATE_INITIALIZED) {
                return anchorPtsUs
            }

            val currentHead = getPlaybackHeadPositionSafe(track)
            val playedFrames = (currentHead - anchorHeadPosition).coerceAtLeast(0L)
            val playedUs = (playedFrames * 1_000_000L) / sampleRate
            return anchorPtsUs + playedUs
        }
    }

    private fun getPlaybackHeadPositionSafe(track: AudioTrack): Long {
        return try {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (e: Exception) {
            0L
        }
    }

    fun triggerUnderrunFallback() {
        synchronized(lock) {
            if (!fallbackActive) {
                fallbackActive = true
                underrunCount++
                // Немедленно возвращаем 100% громкости основному звуку
                _callback?.onPrimaryVolumeAdjustRequested(1.0f)
                _callback?.onUnderrunFallback()
            }
        }
    }

    fun isFallbackActive(): Boolean = synchronized(lock) { fallbackActive }

    fun getUnderrunCount(): Int = synchronized(lock) { underrunCount }

    fun isPlaying(): Boolean = synchronized(lock) { isPlaying }
}

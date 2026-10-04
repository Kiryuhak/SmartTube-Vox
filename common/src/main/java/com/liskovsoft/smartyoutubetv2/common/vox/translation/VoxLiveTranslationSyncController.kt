package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Синхронизатор аудио- и видеопотоков live-перевода.
 * Отвечает за:
 * - Удержание устойчивого запаса буфера (bufferAheadMs);
 * - Плавную адаптацию целевой задержки (delay policy);
 * - Инвалидацию сегментов при перемотке (Seek);
 * - Ограничение и своевременную очистку устаревших фрагментов (bounded memory cache).
 */
class VoxLiveTranslationSyncController(
    private var mode: VoxLiveDelayMode = VoxLiveDelayMode.AUTO
) {
    companion object {
        private const val MAX_RETENTION_BEHIND_MS = 30_000L // 30 sec behind playback
        private const val MAX_STORED_CHUNKS = 100
    }

    private val chunkMap = ConcurrentSkipListMap<Long, VoxLiveTranslationChunk>()
    private var currentPhase: VoxLivePlaybackPhase = VoxLivePlaybackPhase.IDLE
    private var liveEdgePositionMs: Long = 0L
    private var playbackPositionMs: Long = 0L
    private var currentTargetDelayMs: Long = VoxLiveTranslationDelayPolicy.getBounds(mode).targetDelayMs
    private var consecutiveUnderruns: Int = 0
    private var lastTranslationLatencyMs: Long = 0L
    private var lastNetworkLatencyMs: Long = 0L

    @Synchronized
    fun setMode(newMode: VoxLiveDelayMode) {
        this.mode = newMode
        this.currentTargetDelayMs = VoxLiveTranslationDelayPolicy.getBounds(newMode).targetDelayMs
    }

    @Synchronized
    fun getMode(): VoxLiveDelayMode = mode

    @Synchronized
    fun startPreparation(liveEdgeMs: Long, currentPosMs: Long) {
        this.liveEdgePositionMs = liveEdgeMs
        this.playbackPositionMs = currentPosMs
        this.currentPhase = VoxLivePlaybackPhase.HOLDING_INITIAL_BUFFER
        this.consecutiveUnderruns = 0
        chunkMap.clear()

        VoxSafeLogger.i(
            VoxLogCategory.TRANSLATION,
            VoxLogCode.LIVE_TRANSLATION_START,
            "Старт подготовки live-перевода",
            mapOf(
                "mode" to mode.id,
                "targetDelayMs" to currentTargetDelayMs.toString()
            )
        )
    }

    @Synchronized
    fun onLiveEdgeUpdated(liveEdgeMs: Long) {
        if (liveEdgeMs > this.liveEdgePositionMs) {
            this.liveEdgePositionMs = liveEdgeMs
        }
    }

    @Synchronized
    fun addTranslatedChunk(chunk: VoxLiveTranslationChunk) {
        chunkMap[chunk.sourceStartMs] = chunk
        cleanOldChunks()

        val state = getBufferState()
        if (currentPhase == VoxLivePlaybackPhase.HOLDING_INITIAL_BUFFER && state.hasSufficientBuffer()) {
            currentPhase = VoxLivePlaybackPhase.PLAYING_TRANSLATED
            VoxSafeLogger.i(
                VoxLogCategory.TRANSLATION,
                VoxLogCode.LIVE_TRANSLATION_BUFFER_READY,
                "Буфер live-перевода готов к воспроизведению",
                mapOf(
                    "bufferAheadMs" to state.bufferAheadMs.toString(),
                    "currentDelayMs" to state.currentDelayMs.toString()
                )
            )
        } else if (currentPhase == VoxLivePlaybackPhase.REBUFFERING && state.hasSufficientBuffer()) {
            currentPhase = VoxLivePlaybackPhase.PLAYING_TRANSLATED
            VoxSafeLogger.i(
                VoxLogCategory.TRANSLATION,
                VoxLogCode.LIVE_TRANSLATION_BUFFER_READY,
                "Восстановление воспроизведения после буферизации",
                mapOf("bufferAheadMs" to state.bufferAheadMs.toString())
            )
        }
    }

    @Synchronized
    fun onPlaybackPositionUpdate(playbackPosMs: Long): VoxLivePlaybackPhase {
        this.playbackPositionMs = playbackPosMs
        cleanOldChunks()

        val state = getBufferState()

        if (currentPhase == VoxLivePlaybackPhase.PLAYING_TRANSLATED) {
            if (state.isBufferCritical()) {
                consecutiveUnderruns++
                currentPhase = VoxLivePlaybackPhase.REBUFFERING
                currentTargetDelayMs = VoxLiveTranslationDelayPolicy.calculateNextDelay(
                    currentTargetDelayMs,
                    state.bufferAheadMs,
                    mode,
                    consecutiveUnderruns
                )

                VoxSafeLogger.w(
                    VoxLogCategory.TRANSLATION,
                    VoxLogCode.LIVE_TRANSLATION_REBUFFER,
                    "Критическое истощение буфера live-перевода",
                    mapOf(
                        "bufferAheadMs" to state.bufferAheadMs.toString(),
                        "newTargetDelayMs" to currentTargetDelayMs.toString(),
                        "underruns" to consecutiveUnderruns.toString()
                    )
                )
            } else {
                val previousDelay = currentTargetDelayMs
                currentTargetDelayMs = VoxLiveTranslationDelayPolicy.calculateNextDelay(
                    currentTargetDelayMs,
                    state.bufferAheadMs,
                    mode,
                    0
                )
                if (currentTargetDelayMs > previousDelay) {
                    VoxSafeLogger.d(
                        VoxLogCategory.TRANSLATION,
                        VoxLogCode.LIVE_TRANSLATION_DELAY_INCREASED,
                        "Увеличение задержки live-перевода",
                        mapOf("delayMs" to currentTargetDelayMs.toString())
                    )
                } else if (currentTargetDelayMs < previousDelay) {
                    VoxSafeLogger.d(
                        VoxLogCategory.TRANSLATION,
                        VoxLogCode.LIVE_TRANSLATION_DELAY_DECREASED,
                        "Плавное снижение задержки live-перевода",
                        mapOf("delayMs" to currentTargetDelayMs.toString())
                    )
                }
            }
        }

        return currentPhase
    }

    @Synchronized
    fun handleSeek(newPositionMs: Long) {
        this.playbackPositionMs = newPositionMs
        // Сохраняем только те чанки, которые соответствуют новому положению
        val toRemove = chunkMap.filter { (start, chunk) ->
            chunk.sourceEndMs < newPositionMs || start > newPositionMs + 60_000L
        }.keys
        toRemove.forEach { chunkMap.remove(it) }

        val state = getBufferState()
        if (currentPhase == VoxLivePlaybackPhase.PLAYING_TRANSLATED && !state.hasSufficientBuffer()) {
            currentPhase = VoxLivePlaybackPhase.HOLDING_INITIAL_BUFFER
        }
    }

    @Synchronized
    fun onTranslationLatencySample(latencyMs: Long) {
        this.lastTranslationLatencyMs = latencyMs
    }

    @Synchronized
    fun onNetworkLatencySample(latencyMs: Long) {
        this.lastNetworkLatencyMs = latencyMs
    }

    @Synchronized
    fun setPhase(phase: VoxLivePlaybackPhase) {
        this.currentPhase = phase
    }

    @Synchronized
    fun getBufferState(): VoxLiveTranslationBufferState {
        var readyUntil = playbackPositionMs
        // Находим непрерывный горизонт переведённого аудио начиная от текущей позиции
        for ((start, chunk) in chunkMap) {
            if (chunk.status == VoxLiveChunkStatus.READY) {
                if (start <= readyUntil && chunk.sourceEndMs > readyUntil) {
                    readyUntil = chunk.sourceEndMs
                } else if (start > readyUntil && readyUntil == playbackPositionMs && start <= playbackPositionMs + 2000L) {
                    readyUntil = chunk.sourceEndMs
                }
            }
        }

        return VoxLiveTranslationBufferState(
            phase = currentPhase,
            liveEdgePositionMs = liveEdgePositionMs,
            playbackPositionMs = playbackPositionMs,
            translatedAudioReadyUntilMs = readyUntil,
            translationQueueDepth = chunkMap.count { it.value.status == VoxLiveChunkStatus.PENDING || it.value.status == VoxLiveChunkStatus.TRANSLATING },
            translationLatencyMs = lastTranslationLatencyMs,
            networkLatencyMs = lastNetworkLatencyMs,
            mode = mode,
            targetDelayMs = currentTargetDelayMs,
            consecutiveUnderruns = consecutiveUnderruns
        )
    }

    @Synchronized
    fun getActiveChunkCount(): Int = chunkMap.size

    @Synchronized
    fun reset() {
        chunkMap.clear()
        currentPhase = VoxLivePlaybackPhase.IDLE
        consecutiveUnderruns = 0
        liveEdgePositionMs = 0L
        playbackPositionMs = 0L
    }

    private fun cleanOldChunks() {
        val cutoffMs = (playbackPositionMs - MAX_RETENTION_BEHIND_MS).coerceAtLeast(0L)
        val oldKeys = chunkMap.headMap(cutoffMs).keys.toList()
        for (key in oldKeys) {
            chunkMap.remove(key)
        }

        while (chunkMap.size > MAX_STORED_CHUNKS) {
            val firstKey = chunkMap.firstKey()
            chunkMap.remove(firstKey)
        }
    }
}

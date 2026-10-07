package com.liskovsoft.smartyoutubetv2.common.vox.translation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Решение контроллера адаптивной передачи сегментов (Adaptive Ingestion Decision).
 */
enum class VoxAdaptiveIngestDecision {
    SEND,
    WAIT,
    DROP_STALE,
    RESET,
    FALLBACK
}

/**
 * Информация об открытой сессии шлюза.
 */
data class VoxLiveGatewaySessionInfo(
    val sessionId: String,
    val generation: Long,
    val state: String,
    val maxQueuedSegments: Int = 10,
    val maxQueuedDurationMs: Long = 30_000L
)

/**
 * Результат отправки сегмента на шлюз.
 */
data class VoxLiveSegmentSentResult(
    val sequence: Long,
    val generation: Long,
    val status: String, // QUEUED, DUPLICATE_ACCEPTED, STALE_GENERATION_DROPPED, BACKPRESSURE
    val estimatedReadyMs: Long = 1000L
)

/**
 * Результат перевода сегмента от шлюза.
 */
data class VoxLiveSegmentResult(
    val sequence: Long,
    val generation: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val durationMs: Long,
    val status: String, // READY, FAILED, PROCESSING
    val text: String? = null,
    val audioData: ByteArray? = null,
    val providerLatencyMs: Long = 0L,
    val error: String? = null
)

/**
 * Политика адаптивного буфера живого перевода.
 */
data class VoxLiveBufferPolicy(
    val mode: VoxTranslationMode = VoxTranslationMode.AUTO,
    val minimumTranslatedBufferMs: Long = 4_000L,
    val targetTranslatedBufferMs: Long = 8_000L,
    val maximumTranslatedBufferMs: Long = 20_000L
) {
    companion object {
        val LOW_LATENCY = VoxLiveBufferPolicy(
            mode = VoxTranslationMode.LOW_LATENCY,
            minimumTranslatedBufferMs = 2_000L,
            targetTranslatedBufferMs = 4_000L,
            maximumTranslatedBufferMs = 10_000L
        )

        val BALANCED = VoxLiveBufferPolicy(
            mode = VoxTranslationMode.AUTO,
            minimumTranslatedBufferMs = 4_000L,
            targetTranslatedBufferMs = 8_000L,
            maximumTranslatedBufferMs = 20_000L
        )

        val STABLE = VoxLiveBufferPolicy(
            mode = VoxTranslationMode.STABLE,
            minimumTranslatedBufferMs = 6_000L,
            targetTranslatedBufferMs = 12_000L,
            maximumTranslatedBufferMs = 30_000L
        )
    }

    /**
     * Адаптация целевого буфера на основе текущего RTT шлюза.
     */
    fun adaptWithLatency(gatewayRttMs: Long): VoxLiveBufferPolicy {
        if (gatewayRttMs <= 0L) return this
        val extraBuffer = (gatewayRttMs * 1.5).toLong().coerceIn(0L, 10_000L)
        return copy(
            targetTranslatedBufferMs = (targetTranslatedBufferMs + extraBuffer).coerceAtMost(maximumTranslatedBufferMs)
        )
    }

    /**
     * Порог минимального буфера с учётом скорости воспроизведения (1.0x .. 2.0x).
     */
    fun getEffectiveMinimumBufferMs(speed: Float): Long {
        val factor = max(1.0f, speed)
        return (minimumTranslatedBufferMs * factor).toLong()
    }
}

/**
 * Транспортная абстракция для взаимодействия со шлюзом Live Ingestion Gateway.
 */
interface VoxLiveGatewayTransport {
    fun createSession(options: Map<String, Any>): Result<VoxLiveGatewaySessionInfo>
    fun sendSegment(segment: VoxTranslationSegment, payload: ByteArray?): Result<VoxLiveSegmentSentResult>
    fun pollResult(sequence: Long, generation: Long): Result<VoxLiveSegmentResult?>
    fun closeSession(): Result<Boolean>
}

/**
 * Адаптивный контроллер живых аудиосегментов (Adaptive Live Audio Segment Controller).
 * Оркестрирует жизненный цикл живых сегментов, последовательности, поколений (generation),
 * динамического буфера и безопасного fallback на оригинальный звук при просадках сети.
 */
class VoxLiveAdaptiveSegmentController(
    val queue: VoxTranslationQueue = VoxTranslationQueue(maxCapacity = 30),
    val buffer: VoxTranslatedBuffer = VoxTranslatedBuffer(),
    var bufferPolicy: VoxLiveBufferPolicy = VoxLiveBufferPolicy.BALANCED,
    var transport: VoxLiveGatewayTransport? = null
) {
    private val lock = Any()

    @Volatile
    private var isStarted = false

    @Volatile
    private var currentVideoId: String? = null

    @Volatile
    private var generationId: Long = 1L

    private val sequenceCounter = AtomicLong(0L)

    @Volatile
    private var playbackSpeed: Float = 1.0f

    @Volatile
    private var fallbackActive: Boolean = false

    @Volatile
    private var gatewaySessionState: String = "IDLE"

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var gatewayRttMs: Long = 0L

    @Volatile
    private var lastProviderLatencyMs: Long = 0L

    @Volatile
    private var droppedStaleSegments: Int = 0

    @Volatile
    private var duplicateSegments: Int = 0

    @Volatile
    private var backpressureEvents: Int = 0

    @Volatile
    private var lastErrorCategory: String? = null

    @Volatile
    private var isNetworkAvailable: Boolean = true

    fun isStarted(): Boolean = isStarted
    fun getGenerationId(): Long = generationId
    fun getSequence(): Long = sequenceCounter.get()
    fun isFallbackActive(): Boolean = fallbackActive
    fun getGatewaySessionState(): String = gatewaySessionState
    fun getPlaybackSpeed(): Float = playbackSpeed

    fun start(videoId: String, initialSpeed: Float = 1.0f) {
        synchronized(lock) {
            currentVideoId = videoId
            playbackSpeed = initialSpeed.coerceIn(0.5f, 2.5f)
            buffer.setPlaybackSpeed(playbackSpeed)
            generationId = 1L
            sequenceCounter.set(0L)
            lastErrorCategory = null

            queue.cancelAll()
            buffer.clear()
            updateFallbackStateLocked(0L)

            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_GATEWAY_SESSION_START, "videoId=$videoId, gen=$generationId")

            // Initialize gateway session if transport provided
            transport?.let { tp ->
                val res = tp.createSession(mapOf(
                    "sourceLanguage" to "en",
                    "targetLanguage" to "ru",
                    "audioCodec" to "opus"
                ))
                if (res.isSuccess) {
                    val info = res.getOrThrow()
                    currentSessionId = info.sessionId
                    gatewaySessionState = "ACTIVE"
                    VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_GATEWAY_SESSION_READY, "session=${info.sessionId}")
                } else {
                    gatewaySessionState = "FAILED"
                    lastErrorCategory = "SESSION_CREATE_FAILED"
                    fallbackActive = true
                }
            } ?: run {
                gatewaySessionState = "ACTIVE"
            }

            isStarted = true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!isStarted) return
            isStarted = false
            gatewaySessionState = "STOPPED"
            transport?.closeSession()
            currentSessionId = null
            queue.cancelAll()
            buffer.clear()
            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_GATEWAY_SESSION_STOP, "gen=$generationId")
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        synchronized(lock) {
            this.playbackSpeed = speed.coerceIn(0.5f, 2.5f)
            buffer.setPlaybackSpeed(this.playbackSpeed)
            updateFallbackStateLocked(currentPlaybackPosMs = 0L)
        }
    }

    fun onSeek(newPositionMs: Long) {
        synchronized(lock) {
            generationId++
            queue.cancelAll()
            buffer.clear()
            fallbackActive = false
            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_GATEWAY_SESSION_START, "seek gen=$generationId, pos=$newPositionMs")
        }
    }

    fun onVideoChanged(newVideoId: String) {
        synchronized(lock) {
            generationId++
            currentVideoId = newVideoId
            sequenceCounter.set(0L)
            queue.cancelAll()
            buffer.clear()
            updateFallbackStateLocked(0L)
            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_GATEWAY_SESSION_START, "video change to $newVideoId, gen=$generationId")
        }
    }

    fun onNetworkInterrupted() {
        synchronized(lock) {
            isNetworkAvailable = false
            gatewaySessionState = "DEGRADED"
            lastErrorCategory = "NETWORK_INTERRUPTED"
            fallbackActive = true
            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_FALLBACK, "network loss, fallback to original")
        }
    }

    fun onNetworkRestored() {
        synchronized(lock) {
            isNetworkAvailable = true
            gatewaySessionState = "ACTIVE"
            lastErrorCategory = null
            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_GATEWAY_RECONNECT, "network restored, re-activating gateway")
        }
    }

    /**
     * Оценка адаптивного решения перед передачей сегмента.
     */
    fun evaluateIngestionDecision(
        sourceStartMs: Long,
        sourceEndMs: Long,
        currentGeneration: Long,
        currentPlaybackPosMs: Long = 0L
    ): VoxAdaptiveIngestDecision {
        synchronized(lock) {
            if (!isStarted) return VoxAdaptiveIngestDecision.WAIT
            if (!isNetworkAvailable) return VoxAdaptiveIngestDecision.FALLBACK
            if (currentGeneration < generationId) {
                droppedStaleSegments++
                VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_SEGMENT_STALE, "gen $currentGeneration < current $generationId")
                return VoxAdaptiveIngestDecision.DROP_STALE
            }

            // Проверка очереди
            if (queue.getDepth() >= 15) {
                backpressureEvents++
                VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_BACKPRESSURE, "queue depth ${queue.getDepth()} saturated")
                return VoxAdaptiveIngestDecision.WAIT
            }

            // Проверка горизонта буфера: если запас перевода уже превышает максимум, ждём
            val bufferedAhead = buffer.getBufferedAheadMs(currentPlaybackPosMs)
            if (bufferedAhead >= bufferPolicy.maximumTranslatedBufferMs) {
                return VoxAdaptiveIngestDecision.WAIT
            }

            return VoxAdaptiveIngestDecision.SEND
        }
    }

    /**
     * Захват и отправка живого сегмента на шлюз.
     */
    fun ingestLiveSegment(
        sourceStartMs: Long,
        sourceEndMs: Long,
        payload: ByteArray? = null,
        currentPlaybackPosMs: Long = 0L
    ): VoxAdaptiveIngestDecision {
        synchronized(lock) {
            val decision = evaluateIngestionDecision(sourceStartMs, sourceEndMs, generationId, currentPlaybackPosMs)
            if (decision != VoxAdaptiveIngestDecision.SEND) {
                return decision
            }

            val seq = sequenceCounter.getAndIncrement()
            val segment = VoxTranslationSegment(
                segmentId = "seg_${generationId}_$seq",
                sequence = seq,
                sourceStartMs = sourceStartMs,
                sourceEndMs = sourceEndMs,
                sourceDescriptor = "live_audio_chunk",
                generationId = generationId
            )

            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_SEGMENT_CAPTURED, "seq=$seq, gen=$generationId, span=[$sourceStartMs..$sourceEndMs]")

            val enqueued = queue.enqueue(segment)
            if (!enqueued) {
                duplicateSegments++
                VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_SEGMENT_DUPLICATE, "duplicate seq=$seq")
                return VoxAdaptiveIngestDecision.DROP_STALE
            }

            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_SEGMENT_SENT, "seq=$seq, size=${payload?.size ?: 0}")

            transport?.let { tp ->
                val sendStartTime = System.currentTimeMillis()
                val sendRes = tp.sendSegment(segment, payload)
                val rtt = System.currentTimeMillis() - sendStartTime
                gatewayRttMs = rtt

                if (sendRes.isSuccess) {
                    val sentInfo = sendRes.getOrThrow()
                    when (sentInfo.status) {
                        "QUEUED" -> {
                            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_SEGMENT_ACCEPTED, "seq=$seq, rtt=${rtt}ms")
                        }
                        "DUPLICATE_ACCEPTED" -> {
                            duplicateSegments++
                            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_SEGMENT_DUPLICATE, "seq=$seq duplicate")
                        }
                        "STALE_GENERATION_DROPPED" -> {
                            droppedStaleSegments++
                            queue.remove(seq)
                            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_SEGMENT_STALE, "seq=$seq stale at gateway")
                        }
                        "BACKPRESSURE" -> {
                            backpressureEvents++
                            gatewaySessionState = "DEGRADED"
                            VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_BACKPRESSURE, "gateway backpressure 429")
                        }
                    }
                } else {
                    lastErrorCategory = "SEND_FAILED"
                    updateFallbackStateLocked(currentPlaybackPosMs)
                }
            }

            return VoxAdaptiveIngestDecision.SEND
        }
    }

    /**
     * Приём готового результата от шлюза.
     */
    fun onSegmentResultReceived(result: VoxLiveSegmentResult, currentPlaybackPosMs: Long = 0L) {
        synchronized(lock) {
            // Защита от устаревших результатов предыдущих поколений
            if (result.generation != generationId) {
                droppedStaleSegments++
                VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_SEGMENT_STALE, "result gen ${result.generation} != current $generationId")
                return
            }

            lastProviderLatencyMs = result.providerLatencyMs
            if (gatewayRttMs > 0) {
                val adaptedPolicy = bufferPolicy.adaptWithLatency(gatewayRttMs)
                if (adaptedPolicy.targetTranslatedBufferMs != bufferPolicy.targetTranslatedBufferMs) {
                    bufferPolicy = adaptedPolicy
                    VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_BUFFER_TARGET_CHANGED, "target=${bufferPolicy.targetTranslatedBufferMs}ms")
                }
            }

            if (result.status == "READY") {
                val segment = queue.getSegment(result.sequence)
                if (segment != null && segment.generationId == generationId) {
                    segment.state = VoxSegmentState.READY
                    segment.completedAtMs = System.currentTimeMillis()
                    buffer.addReadySegment(segment)
                    VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_SEGMENT_RESULT, "seq=${result.sequence}, duration=${result.durationMs}ms")
                }
            } else if (result.status == "FAILED") {
                lastErrorCategory = result.error ?: "TRANSLATION_FAILED"
                queue.remove(result.sequence)
            }

            updateFallbackStateLocked(currentPlaybackPosMs)
        }
    }

    /**
     * Проверка и обновление состояния fallback:
     * Если буфер готового звука ниже эффективного минимума -> активируем fallback на оригинальный звук.
     * При этом воспроизведение YouTube LIVE не прерывается и не буферизируется!
     */
    private fun updateFallbackStateLocked(currentPlaybackPosMs: Long) {
        val effectiveMin = bufferPolicy.getEffectiveMinimumBufferMs(playbackSpeed)
        val currentBuffered = buffer.getBufferedAheadMs(currentPlaybackPosMs)

        if (currentBuffered < effectiveMin) {
            if (!fallbackActive) {
                fallbackActive = true
                VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_FALLBACK, "buffer ${currentBuffered}ms < min ${effectiveMin}ms, fallback to original")
            }
        } else {
            if (fallbackActive) {
                fallbackActive = false
                VoxLiveGatewayLogger.log(VoxLiveGatewayEvent.VOX_LIVE_FALLBACK, "buffer recovered to ${currentBuffered}ms, restored translation")
            }
        }
    }

    /**
     * Снимок безопасной диагностики.
     */
    fun getDiagnostics(currentPlaybackPosMs: Long = 0L): VoxLiveGatewayDiagnostics {
        synchronized(lock) {
            return VoxLiveGatewayDiagnostics(
                gatewaySessionState = gatewaySessionState,
                segmentSequence = sequenceCounter.get(),
                generation = generationId,
                queueDepth = queue.getDepth(),
                queuedDurationMs = queue.getQueuedDurationMs(),
                translatedBufferMs = buffer.getBufferedAheadMs(currentPlaybackPosMs),
                providerLatencyMs = lastProviderLatencyMs,
                gatewayRttMs = gatewayRttMs,
                fallbackActive = fallbackActive,
                droppedStaleSegments = droppedStaleSegments,
                duplicateSegments = duplicateSegments,
                lastGatewayErrorCategory = lastErrorCategory
            )
        }
    }
}

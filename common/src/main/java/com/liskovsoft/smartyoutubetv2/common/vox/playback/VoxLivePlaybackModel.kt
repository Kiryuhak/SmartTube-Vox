package com.liskovsoft.smartyoutubetv2.common.vox.playback

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Безопасные события жизненного цикла и диагностики воспроизведения прямого эфира (Zero-Telemetry).
 */
enum class VoxLivePlaybackEvent {
    VOX_LIVE_PLAYBACK_START,
    VOX_LIVE_READY,
    VOX_LIVE_BUFFERING_START,
    VOX_LIVE_BUFFERING_END,
    VOX_LIVE_REBUFFER,
    VOX_LIVE_BUFFER_HEALTH,
    VOX_LIVE_MANIFEST_REFRESH,
    VOX_LIVE_SEGMENT_LOAD,
    VOX_LIVE_SEGMENT_ERROR,
    VOX_LIVE_EDGE_DISTANCE,
    VOX_LIVE_NETWORK_ESTIMATE,
    VOX_LIVE_DECODER_WARNING,
    VOX_LIVE_RECOVERY;
}

/**
 * Типизированные причины буферизации прямого эфира.
 */
enum class VoxLiveRebufferReason(val id: String, val titleRu: String) {
    NETWORK_STARVATION("network_starvation", "Сетевое голодание (пропускная способность ниже битрейта)"),
    MANIFEST_STALE("manifest_stale", "Устаревший или зависший манифест DASH/HLS"),
    SEGMENT_TIMEOUT("segment_timeout", "Таймаут загрузки сегмента с CDN"),
    SEGMENT_404("segment_404", "Сегмент не найден на сервере CDN (HTTP 404 на краю эфира)"),
    SEGMENT_403("segment_403", "Доступ к сегменту отклонён (HTTP 403)"),
    HTTP_429("http_429", "Превышение частоты запросов CDN (HTTP 429)"),
    HTTP_5XX("http_5xx", "Временный серверный сбой узла CDN (HTTP 5xx)"),
    LIVE_EDGE_CATCHUP("live_edge_catchup", "Воспроизведение вплотную подошло к границе прямого эфира"),
    BUFFER_UNDERRUN("buffer_underrun", "Опустошение буфера воспроизведения"),
    PLAYER_STATE_RACE("player_state_race", "Гонка состояний плеера при переинициализации"),
    DECODER_STALL("decoder_stall", "Задержка или зависание декодера"),
    UNKNOWN("unknown", "Не определено");
}

/**
 * Статус стабильности воспроизведения прямого эфира.
 */
enum class VoxLiveStabilityStatus(val id: String, val titleRu: String) {
    LIVE_PLAYBACK_STABLE("stable", "Стабильный прямой эфир"),
    LIVE_PLAYBACK_UNSTABLE("unstable", "Нестабильный поток (периодические повторные буферизации)");
}

/**
 * Политика целевого смещения от края прямого эфира (Target Live Offset Policy).
 */
enum class VoxLiveTargetOffsetPolicy(val id: String, val targetOffsetMs: Long, val titleRu: String) {
    LOW_LATENCY("low_latency", 6_000L, "Минимальная задержка (6 сек)"),
    BALANCED("balanced", 15_000L, "Сбалансированная стабильность (15 сек)"),
    STABLE("stable", 25_000L, "Максимальный буфер стабильности (25 сек)");

    companion object {
        @JvmField
        val DEFAULT = BALANCED

        @JvmStatic
        fun fromId(id: String?): VoxLiveTargetOffsetPolicy {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
        }
    }
}

/**
 * Снимок состояния плеера и сети в момент перехода READY -> BUFFERING.
 */
data class VoxLiveBufferingSnapshot(
    val timestampMs: Long = System.currentTimeMillis(),
    val bufferedDurationMs: Long,
    val liveOffsetMs: Long,
    val bandwidthEstimate: Long,
    val selectedBitrate: Long,
    val networkState: String,
    val lastLoadDurationMs: Long,
    val lastLoadBytes: Long,
    val lastHttpStatusCategory: String,
    val manifestAgeMs: Long,
    val playbackSpeed: Float
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "timestampMs" to timestampMs,
            "bufferedDurationMs" to bufferedDurationMs,
            "liveOffsetMs" to liveOffsetMs,
            "bandwidthEstimate" to bandwidthEstimate,
            "selectedBitrate" to selectedBitrate,
            "networkState" to networkState,
            "lastLoadDurationMs" to lastLoadDurationMs,
            "lastLoadBytes" to lastLoadBytes,
            "lastHttpStatusCategory" to lastHttpStatusCategory,
            "manifestAgeMs" to manifestAgeMs,
            "playbackSpeed" to playbackSpeed
        )
    }
}

/**
 * Запись об инциденте повторной буферизации прямого эфира.
 */
data class VoxLiveRebufferRecord(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val snapshot: VoxLiveBufferingSnapshot?,
    val reason: VoxLiveRebufferReason
)

/**
 * Агрегированные метрики прямого эфира для включения в диагностический отчёт.
 */
data class VoxLivePlaybackMetrics(
    val rebufferCount: Int = 0,
    val totalRebufferMs: Long = 0L,
    val maxRebufferMs: Long = 0L,
    val averageBufferedMs: Long = 0L,
    val averageBandwidthKbps: Long = 0L,
    val selectedHeight: Int = 0,
    val selectedCodec: String = "",
    val liveOffsetMs: Long = 0L,
    val manifestErrorCount: Int = 0,
    val segmentErrorCount: Int = 0,
    val lastErrorCategory: String = "NONE"
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "rebufferCount" to rebufferCount,
            "totalRebufferMs" to totalRebufferMs,
            "maxRebufferMs" to maxRebufferMs,
            "averageBufferedMs" to averageBufferedMs,
            "averageBandwidthKbps" to averageBandwidthKbps,
            "selectedHeight" to selectedHeight,
            "selectedCodec" to selectedCodec,
            "liveOffsetMs" to liveOffsetMs,
            "manifestErrorCount" to manifestErrorCount,
            "segmentErrorCount" to segmentErrorCount,
            "lastErrorCategory" to lastErrorCategory
        )
    }
}

/**
 * Потокобезопасный монитор воспроизведения прямого эфира с нулевой телеметрией.
 * Не сохраняет полные URL, названия роликов, учетные записи или авторизационные заголовки.
 */
object VoxLivePlaybackMonitor {

    private const val CLASSIFIER_WINDOW_MS = 10 * 60 * 1000L // 10 минут
    private const val UNSTABLE_REBUFFER_THRESHOLD_COUNT = 3
    private const val UNSTABLE_TOTAL_REBUFFER_THRESHOLD_MS = 15_000L

    interface Listener {
        fun onEvent(event: VoxLivePlaybackEvent, safeDetails: String)
        fun onBufferingSnapshot(snapshot: VoxLiveBufferingSnapshot)
        fun onRebufferCompleted(record: VoxLiveRebufferRecord)
        fun onStabilityStatusChanged(status: VoxLiveStabilityStatus)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val rebufferHistory = mutableListOf<VoxLiveRebufferRecord>()
    private val bufferSamples = mutableListOf<Long>()
    private val bandwidthSamples = mutableListOf<Long>()

    private var currentStreamMarker: String? = null
    private var isLiveSession: Boolean = false
    private var isPlaying: Boolean = false
    private var isBuffering: Boolean = false
    private var bufferingStartMs: Long = 0L
    private var lastSnapshot: VoxLiveBufferingSnapshot? = null

    // Live state tracking
    private var lastManifestRefreshMs: Long = System.currentTimeMillis()
    private var manifestErrorCount: Int = 0
    private var segmentErrorCount: Int = 0
    private var lastErrorCategory: String = "NONE"
    private var lastHttpStatusCategory: String = "NONE"
    private var lastLoadDurationMs: Long = 0L
    private var lastLoadBytes: Long = 0L
    private var selectedHeight: Int = 0
    private var selectedCodec: String = ""
    private var currentLiveOffsetMs: Long = 0L
    private var currentSpeed: Float = 1.0f
    private var isOnline: Boolean = true

    @Synchronized
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun resetForTesting() {
        listeners.clear()
        rebufferHistory.clear()
        bufferSamples.clear()
        bandwidthSamples.clear()
        currentStreamMarker = null
        isLiveSession = false
        isPlaying = false
        isBuffering = false
        bufferingStartMs = 0L
        lastSnapshot = null
        lastManifestRefreshMs = System.currentTimeMillis()
        manifestErrorCount = 0
        segmentErrorCount = 0
        lastErrorCategory = "NONE"
        lastHttpStatusCategory = "NONE"
        lastLoadDurationMs = 0L
        lastLoadBytes = 0L
        selectedHeight = 0
        selectedCodec = ""
        currentLiveOffsetMs = 0L
        currentSpeed = 1.0f
        isOnline = true
    }

    @Synchronized
    fun onPlaybackStart(streamMarker: String, isLive: Boolean) {
        currentStreamMarker = sanitize(streamMarker)
        isLiveSession = isLive
        isPlaying = false
        isBuffering = false
        bufferingStartMs = 0L
        lastSnapshot = null
        rebufferHistory.clear()
        bufferSamples.clear()
        bandwidthSamples.clear()
        manifestErrorCount = 0
        segmentErrorCount = 0
        lastErrorCategory = "NONE"
        lastHttpStatusCategory = "NONE"

        if (isLive) {
            notifyEvent(VoxLivePlaybackEvent.VOX_LIVE_PLAYBACK_START, "streamMarker=$currentStreamMarker")
        }
    }

    @Synchronized
    fun onReady(bufferedDurationMs: Long, liveOffsetMs: Long, bandwidthEstimate: Long) {
        if (!isLiveSession) return

        currentLiveOffsetMs = liveOffsetMs
        if (bufferedDurationMs > 0) bufferSamples.add(bufferedDurationMs)
        if (bandwidthEstimate > 0) bandwidthSamples.add(bandwidthEstimate)

        if (isBuffering && bufferingStartMs > 0L) {
            val now = System.currentTimeMillis()
            val rebufferDuration = now - bufferingStartMs
            isBuffering = false
            bufferingStartMs = 0L

            val reason = classifyRebufferReason(lastSnapshot)
            val record = VoxLiveRebufferRecord(
                startTimeMs = lastSnapshot?.timestampMs ?: (now - rebufferDuration),
                endTimeMs = now,
                durationMs = rebufferDuration,
                snapshot = lastSnapshot,
                reason = reason
            )
            rebufferHistory.add(record)

            notifyEvent(
                VoxLivePlaybackEvent.VOX_LIVE_BUFFERING_END,
                "rebufferDurationMs=$rebufferDuration, reason=${reason.id}"
            )
            notifyEvent(
                VoxLivePlaybackEvent.VOX_LIVE_REBUFFER,
                "count=${rebufferHistory.size}, lastDurationMs=$rebufferDuration, reason=${reason.id}"
            )

            for (listener in listeners) {
                listener.onRebufferCompleted(record)
            }

            evaluateStability()
        }

        isPlaying = true
        notifyEvent(
            VoxLivePlaybackEvent.VOX_LIVE_READY,
            "bufferedMs=$bufferedDurationMs, liveOffsetMs=$liveOffsetMs, bandwidthKbps=${bandwidthEstimate / 1000}"
        )
    }

    @Synchronized
    fun onBufferingStarted(
        bufferedDurationMs: Long,
        liveOffsetMs: Long,
        bandwidthEstimate: Long,
        selectedBitrate: Long,
        speed: Float
    ) {
        if (!isLiveSession) return

        currentLiveOffsetMs = liveOffsetMs
        currentSpeed = speed
        val manifestAge = System.currentTimeMillis() - lastManifestRefreshMs

        val snapshot = VoxLiveBufferingSnapshot(
            bufferedDurationMs = bufferedDurationMs,
            liveOffsetMs = liveOffsetMs,
            bandwidthEstimate = bandwidthEstimate,
            selectedBitrate = selectedBitrate,
            networkState = if (isOnline) "ONLINE" else "DISCONNECTED",
            lastLoadDurationMs = lastLoadDurationMs,
            lastLoadBytes = lastLoadBytes,
            lastHttpStatusCategory = lastHttpStatusCategory,
            manifestAgeMs = manifestAge,
            playbackSpeed = speed
        )

        lastSnapshot = snapshot
        isBuffering = true
        bufferingStartMs = System.currentTimeMillis()

        notifyEvent(
            VoxLivePlaybackEvent.VOX_LIVE_BUFFERING_START,
            "bufferedMs=$bufferedDurationMs, liveOffsetMs=$liveOffsetMs, statusCat=$lastHttpStatusCategory, network=${snapshot.networkState}"
        )

        for (listener in listeners) {
            listener.onBufferingSnapshot(snapshot)
        }
    }

    @Synchronized
    fun onSegmentLoaded(loadDurationMs: Long, bytesLoaded: Long) {
        lastLoadDurationMs = loadDurationMs
        lastLoadBytes = bytesLoaded
        lastHttpStatusCategory = "2XX_SUCCESS"
        if (isLiveSession) {
            notifyEvent(
                VoxLivePlaybackEvent.VOX_LIVE_SEGMENT_LOAD,
                "durationMs=$loadDurationMs, bytes=$bytesLoaded"
            )
        }
    }

    @Synchronized
    fun onSegmentError(httpStatusCode: Int, errorTypeDesc: String) {
        segmentErrorCount++
        lastHttpStatusCategory = categorizeHttpStatus(httpStatusCode)
        lastErrorCategory = lastHttpStatusCategory
        if (isLiveSession) {
            notifyEvent(
                VoxLivePlaybackEvent.VOX_LIVE_SEGMENT_ERROR,
                "httpStatus=$httpStatusCode, cat=$lastHttpStatusCategory, err=$errorTypeDesc"
            )
        }
    }

    @Synchronized
    fun onManifestRefreshed(isSuccess: Boolean, httpStatusCode: Int = 200) {
        lastManifestRefreshMs = System.currentTimeMillis()
        if (!isSuccess) {
            manifestErrorCount++
            lastErrorCategory = "MANIFEST_ERROR"
            lastHttpStatusCategory = categorizeHttpStatus(httpStatusCode)
        }
        if (isLiveSession) {
            notifyEvent(
                VoxLivePlaybackEvent.VOX_LIVE_MANIFEST_REFRESH,
                "success=$isSuccess, status=$httpStatusCode"
            )
        }
    }

    @Synchronized
    fun onBufferHealthTick(
        bufferedMs: Long,
        liveOffsetMs: Long,
        bandwidth: Long,
        bitrate: Long,
        height: Int,
        codec: String
    ) {
        if (!isLiveSession) return

        currentLiveOffsetMs = liveOffsetMs
        selectedHeight = height
        selectedCodec = codec
        if (bufferedMs > 0) bufferSamples.add(bufferedMs)
        if (bandwidth > 0) bandwidthSamples.add(bandwidth)

        notifyEvent(
            VoxLivePlaybackEvent.VOX_LIVE_BUFFER_HEALTH,
            "bufferedMs=$bufferedMs, offsetMs=$liveOffsetMs, bandwidthKbps=${bandwidth / 1000}, height=${height}p"
        )
    }

    @Synchronized
    fun onNetworkInterruption(isRestored: Boolean) {
        isOnline = isRestored
        if (!isRestored) {
            lastErrorCategory = "NETWORK_INTERRUPTED"
            notifyEvent(VoxLivePlaybackEvent.VOX_LIVE_DECODER_WARNING, "network_drop_detected")
        } else {
            notifyEvent(VoxLivePlaybackEvent.VOX_LIVE_RECOVERY, "network_restored")
        }
    }

    @Synchronized
    fun classifyRebufferReason(snapshot: VoxLiveBufferingSnapshot?): VoxLiveRebufferReason {
        if (!isOnline) {
            return VoxLiveRebufferReason.NETWORK_STARVATION
        }

        if (snapshot == null) {
            return VoxLiveRebufferReason.BUFFER_UNDERRUN
        }

        if (snapshot.lastHttpStatusCategory == "HTTP_429") {
            return VoxLiveRebufferReason.HTTP_429
        }

        if (snapshot.lastHttpStatusCategory == "HTTP_5XX") {
            return VoxLiveRebufferReason.HTTP_5XX
        }

        if (snapshot.lastHttpStatusCategory == "HTTP_404") {
            return VoxLiveRebufferReason.SEGMENT_404
        }

        if (snapshot.lastHttpStatusCategory == "HTTP_403") {
            return VoxLiveRebufferReason.SEGMENT_403
        }

        // Live edge catchup: if playing within 4 seconds of edge and buffer ran out
        if (snapshot.liveOffsetMs in 1..4_000L && snapshot.bufferedDurationMs < 1_000L) {
            return VoxLiveRebufferReason.LIVE_EDGE_CATCHUP
        }

        // Stale manifest: manifest has not updated for > 20s
        if (snapshot.manifestAgeMs > 20_000L && manifestErrorCount > 0) {
            return VoxLiveRebufferReason.MANIFEST_STALE
        }

        // Network starvation: bandwidth significantly lower than required bitrate
        if (snapshot.bandwidthEstimate in 1 until snapshot.selectedBitrate && snapshot.selectedBitrate > 0) {
            return VoxLiveRebufferReason.NETWORK_STARVATION
        }

        if (snapshot.lastLoadDurationMs > 8_000L) {
            return VoxLiveRebufferReason.SEGMENT_TIMEOUT
        }

        return VoxLiveRebufferReason.BUFFER_UNDERRUN
    }

    @Synchronized
    fun getStabilityStatus(): VoxLiveStabilityStatus {
        val now = System.currentTimeMillis()
        val recentRebuffers = rebufferHistory.filter { (now - it.endTimeMs) <= CLASSIFIER_WINDOW_MS }
        val recentCount = recentRebuffers.size
        val recentTotalDuration = recentRebuffers.sumOf { it.durationMs }

        return if (recentCount >= UNSTABLE_REBUFFER_THRESHOLD_COUNT || recentTotalDuration >= UNSTABLE_TOTAL_REBUFFER_THRESHOLD_MS) {
            VoxLiveStabilityStatus.LIVE_PLAYBACK_UNSTABLE
        } else {
            VoxLiveStabilityStatus.LIVE_PLAYBACK_STABLE
        }
    }

    private fun evaluateStability() {
        val status = getStabilityStatus()
        for (listener in listeners) {
            listener.onStabilityStatusChanged(status)
        }
    }

    @Synchronized
    fun getMetricsSummary(): VoxLivePlaybackMetrics {
        val count = rebufferHistory.size
        val totalMs = rebufferHistory.sumOf { it.durationMs }
        val maxMs = rebufferHistory.maxOfOrNull { it.durationMs } ?: 0L
        val avgBuffer = if (bufferSamples.isNotEmpty()) bufferSamples.average().toLong() else 0L
        val avgBwKbps = if (bandwidthSamples.isNotEmpty()) (bandwidthSamples.average() / 1000).toLong() else 0L

        return VoxLivePlaybackMetrics(
            rebufferCount = count,
            totalRebufferMs = totalMs,
            maxRebufferMs = maxMs,
            averageBufferedMs = avgBuffer,
            averageBandwidthKbps = avgBwKbps,
            selectedHeight = selectedHeight,
            selectedCodec = selectedCodec,
            liveOffsetMs = currentLiveOffsetMs,
            manifestErrorCount = manifestErrorCount,
            segmentErrorCount = segmentErrorCount,
            lastErrorCategory = lastErrorCategory
        )
    }

    fun categorizeHttpStatus(code: Int): String {
        return when (code) {
            in 200..299 -> "2XX_SUCCESS"
            401 -> "HTTP_401"
            403 -> "HTTP_403"
            404 -> "HTTP_404"
            408 -> "HTTP_408"
            429 -> "HTTP_429"
            in 500..599 -> "HTTP_5XX"
            else -> "HTTP_$code"
        }
    }

    private fun notifyEvent(event: VoxLivePlaybackEvent, safeDetails: String) {
        val sanitized = sanitize(safeDetails)
        for (listener in listeners) {
            listener.onEvent(event, sanitized)
        }
    }

    fun sanitize(input: String): String {
        return input
            .replace(Regex("https?://[^\\s]+"), "<URL_REDACTED>")
            .replace(Regex("(?i)token=[^\\s&]+"), "token=<REDACTED>")
            .replace(Regex("(?i)bearer\\s+[^\\s]+"), "bearer <REDACTED>")
            .replace(Regex("(?i)cookie=[^\\s;]+"), "cookie=<REDACTED>")
            .replace(Regex("(?i)sig=[^\\s&]+"), "sig=<REDACTED>")
    }
}

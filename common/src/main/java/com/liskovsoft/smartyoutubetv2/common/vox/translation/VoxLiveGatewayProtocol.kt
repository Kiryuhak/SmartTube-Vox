package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.json.JSONObject

/**
 * Безопасные события сетевого шлюза Live Translation Gateway (Section 51).
 * Строго Zero-Telemetry: не содержит аудиоданных, персональных идентификаторов или секретов.
 */
enum class VoxLiveGatewayEvent {
    VOX_LIVE_GATEWAY_SESSION_START,
    VOX_LIVE_GATEWAY_SESSION_READY,
    VOX_LIVE_SEGMENT_CAPTURED,
    VOX_LIVE_SEGMENT_SENT,
    VOX_LIVE_SEGMENT_ACCEPTED,
    VOX_LIVE_SEGMENT_RESULT,
    VOX_LIVE_SEGMENT_DUPLICATE,
    VOX_LIVE_SEGMENT_STALE,
    VOX_LIVE_BACKPRESSURE,
    VOX_LIVE_BUFFER_TARGET_CHANGED,
    VOX_LIVE_FALLBACK,
    VOX_LIVE_GATEWAY_RECONNECT,
    VOX_LIVE_GATEWAY_SESSION_STOP;
}

/**
 * Слушатель событий безопасного логгера шлюза.
 */
interface VoxLiveGatewayEventListener {
    fun onGatewayEvent(event: VoxLiveGatewayEvent, safeDetails: String)
}

/**
 * Безопасный логгер событий сетевого шлюза.
 */
object VoxLiveGatewayLogger {
    private val listeners = mutableListOf<VoxLiveGatewayEventListener>()

    @Synchronized
    fun addListener(listener: VoxLiveGatewayEventListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: VoxLiveGatewayEventListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun clearListeners() {
        listeners.clear()
    }

    @Synchronized
    fun log(event: VoxLiveGatewayEvent, safeDetails: String = "") {
        val sanitized = VoxLiveProtocolLogger.sanitize(safeDetails)
        for (l in listeners) {
            l.onGatewayEvent(event, sanitized)
        }
    }
}

/**
 * Снимок диагностических метрик Controlled Live Gateway (Section 50).
 * Полностью санитизирован, исключает утечку URL, заголовков и медиа-контента.
 */
data class VoxLiveGatewayDiagnostics(
    val gatewaySessionState: String = "IDLE",
    val segmentSequence: Long = 0L,
    val generation: Long = 1L,
    val queueDepth: Int = 0,
    val queuedDurationMs: Long = 0L,
    val translatedBufferMs: Long = 0L,
    val providerLatencyMs: Long = 0L,
    val gatewayRttMs: Long = 0L,
    val fallbackActive: Boolean = false,
    val droppedStaleSegments: Int = 0,
    val duplicateSegments: Int = 0,
    val lastGatewayErrorCategory: String? = null
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "gatewaySessionState" to gatewaySessionState,
            "segmentSequence" to segmentSequence,
            "generation" to generation,
            "queueDepth" to queueDepth,
            "queuedDurationMs" to queuedDurationMs,
            "translatedBufferMs" to translatedBufferMs,
            "providerLatencyMs" to providerLatencyMs,
            "gatewayRttMs" to gatewayRttMs,
            "fallbackActive" to fallbackActive,
            "droppedStaleSegments" to droppedStaleSegments,
            "duplicateSegments" to duplicateSegments
        )
        if (lastGatewayErrorCategory != null) {
            map["lastGatewayErrorCategory"] = lastGatewayErrorCategory
        }
        return map
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("gatewaySessionState", gatewaySessionState)
        json.put("segmentSequence", segmentSequence)
        json.put("generation", generation)
        json.put("queueDepth", queueDepth)
        json.put("queuedDurationMs", queuedDurationMs)
        json.put("translatedBufferMs", translatedBufferMs)
        json.put("providerLatencyMs", providerLatencyMs)
        json.put("gatewayRttMs", gatewayRttMs)
        json.put("fallbackActive", fallbackActive)
        json.put("droppedStaleSegments", droppedStaleSegments)
        json.put("duplicateSegments", duplicateSegments)
        if (lastGatewayErrorCategory != null) {
            json.put("lastGatewayErrorCategory", lastGatewayErrorCategory)
        }
        return json
    }
}

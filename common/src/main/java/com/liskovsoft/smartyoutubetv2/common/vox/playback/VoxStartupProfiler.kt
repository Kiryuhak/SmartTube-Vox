package com.liskovsoft.smartyoutubetv2.common.vox.playback

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Startup Profiler: Measures structured timeline stages for video startup.
 * Zero-PII: No raw URLs, IP addresses, titles, or tokens.
 */
object VoxStartupProfiler {

    data class StageRecord(
        val stage: String,
        val relativeOffsetMs: Long,
        val elapsedMs: Long,
        val engine: String,
        val result: String
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "stage" to stage,
            "relativeOffsetMs" to relativeOffsetMs,
            "elapsedMs" to elapsedMs,
            "engine" to engine,
            "result" to result
        )
    }

    @Volatile private var currentSessionId: Long = 0L
    @Volatile private var sessionOpenTimeMs: Long = 0L
    @Volatile private var currentEngine: String = "AUTO"
    @Volatile private var transportProtocol: String = "UNKNOWN"
    private val stageRecords = CopyOnWriteArrayList<StageRecord>()
    private val stageStartTimes = ConcurrentHashMap<String, Long>()

    @JvmStatic
    @JvmOverloads
    fun onUserOpen(sessionId: Long, engine: String = "AUTO", nowMs: Long = System.currentTimeMillis()) {
        currentSessionId = sessionId
        sessionOpenTimeMs = nowMs
        currentEngine = engine
        transportProtocol = "UNKNOWN"
        stageRecords.clear()
        stageStartTimes.clear()
        recordStage("USER_OPEN", 0L, "OK", nowMs)
    }

    @JvmStatic
    fun updateTransportProtocol(protocol: String?) {
        transportProtocol = when (protocol?.lowercase()?.trim()) {
            "quic", "h3", "http/3" -> "H3"
            "h2", "http/2" -> "H2"
            "http/1.1", "http_1_1" -> "HTTP_1_1"
            null, "" -> "UNKNOWN"
            else -> protocol.uppercase()
        }
    }

    @JvmStatic
    fun getTransportProtocol(): String = transportProtocol

    @JvmStatic
    @JvmOverloads
    fun startStage(stage: String, nowMs: Long = System.currentTimeMillis()) {
        stageStartTimes[stage] = nowMs
    }

    @JvmStatic
    @JvmOverloads
    fun finishStage(stage: String, result: String = "OK", nowMs: Long = System.currentTimeMillis()) {
        val start = stageStartTimes.remove(stage) ?: sessionOpenTimeMs
        val elapsed = if (start > 0L) Math.max(0L, nowMs - start) else 0L
        recordStage(stage, elapsed, result, nowMs)
    }

    @JvmStatic
    @JvmOverloads
    fun recordStage(stage: String, elapsedMs: Long, result: String = "OK", nowMs: Long = System.currentTimeMillis()) {
        val relativeOffset = if (sessionOpenTimeMs > 0L) Math.max(0L, nowMs - sessionOpenTimeMs) else 0L
        stageRecords.add(
            StageRecord(
                stage = stage,
                relativeOffsetMs = relativeOffset,
                elapsedMs = elapsedMs,
                engine = currentEngine,
                result = result
            )
        )
    }

    const val STAGE_REQUEST_START = "REQUEST_START"
    const val STAGE_REQUEST = "REQUEST"
    const val STAGE_MANIFEST_FETCH = "MANIFEST_FETCH"
    const val STAGE_MANIFEST_PARSE = "MANIFEST_PARSE"
    const val STAGE_MANIFEST = "MANIFEST"
    const val STAGE_TRACK_SELECT = "TRACK_SELECT"
    const val STAGE_DECODER_INIT = "DECODER_INIT"
    const val STAGE_BUFFER_FILL = "BUFFER_FILL"
    const val STAGE_FIRST_FRAME = "FIRST_FRAME"

    const val ROOT_CAUSE_NETWORK = "NETWORK"
    const val ROOT_CAUSE_MANIFEST = "MANIFEST"
    const val ROOT_CAUSE_TRACK_SELECTION = "TRACK_SELECTION"
    const val ROOT_CAUSE_DECODER = "DECODER"
    const val ROOT_CAUSE_BUFFER_FILL = "BUFFER_FILL"
    const val ROOT_CAUSE_UNKNOWN = "UNKNOWN"

    @JvmStatic
    fun updateEngine(engine: String) {
        currentEngine = engine
    }

    @JvmStatic
    fun classifyStartupRootCause(): String {
        if (stageRecords.isEmpty()) return ROOT_CAUSE_UNKNOWN
        val maxRecord = stageRecords.maxByOrNull { it.elapsedMs } ?: return ROOT_CAUSE_UNKNOWN
        if (maxRecord.elapsedMs < 300L) return ROOT_CAUSE_UNKNOWN
        return when (maxRecord.stage) {
            STAGE_REQUEST, STAGE_REQUEST_START -> ROOT_CAUSE_NETWORK
            STAGE_MANIFEST, STAGE_MANIFEST_FETCH, STAGE_MANIFEST_PARSE -> ROOT_CAUSE_MANIFEST
            STAGE_TRACK_SELECT -> ROOT_CAUSE_TRACK_SELECTION
            STAGE_DECODER_INIT -> ROOT_CAUSE_DECODER
            STAGE_BUFFER_FILL, "BUFFER_LOAD" -> ROOT_CAUSE_BUFFER_FILL
            else -> ROOT_CAUSE_UNKNOWN
        }
    }

    @JvmStatic
    fun getCurrentProfileSummary(): Map<String, Any> = getProfileSummary()

    @JvmStatic
    fun getProfileSummary(): Map<String, Any> {
        val totalStartupMs = stageRecords.lastOrNull()?.relativeOffsetMs ?: 0L
        val slowestStage = stageRecords.maxByOrNull { it.elapsedMs }?.stage ?: "NONE"
        return mapOf(
            "playbackSessionId" to currentSessionId,
            "engine" to currentEngine,
            "transportProtocol" to transportProtocol,
            "totalStartupMs" to totalStartupMs,
            "slowestStage" to slowestStage,
            "rootCause" to classifyStartupRootCause(),
            "stages" to stageRecords.map { it.toMap() }
        )
    }
}

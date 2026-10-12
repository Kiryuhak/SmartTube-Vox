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

    data class StartupSample(
        val playbackSessionId: Long,
        val totalStartupMs: Long,
        val slowestStage: String,
        val rootCause: String,
        val stages: Map<String, Long> = emptyMap()
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "playbackSessionId" to playbackSessionId,
            "totalStartupMs" to totalStartupMs,
            "slowestStage" to slowestStage,
            "rootCause" to rootCause,
            "stages" to stages
        )
    }

    const val MAX_SAMPLES = 50

    @Volatile private var currentSessionId: Long = 0L
    @Volatile private var sessionOpenTimeMs: Long = 0L
    @Volatile private var currentEngine: String = "AUTO"
    @Volatile private var transportProtocol: String = "UNKNOWN"
    private val stageRecords = CopyOnWriteArrayList<StageRecord>()
    private val stageStartTimes = ConcurrentHashMap<String, Long>()
    private val recentSamples = CopyOnWriteArrayList<StartupSample>()

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
        if (stage == STAGE_FIRST_FRAME) {
            recordCompletedSession()
        }
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
    fun recordCompletedSession() {
        if (stageRecords.isEmpty()) return
        val total = stageRecords.lastOrNull()?.relativeOffsetMs ?: 0L
        val slowest = stageRecords.maxByOrNull { it.elapsedMs }?.stage ?: "NONE"
        val cause = classifyStartupRootCause()
        val stageMap = stageRecords.associate { it.stage to it.elapsedMs }
        val sample = StartupSample(currentSessionId, total, slowest, cause, stageMap)
        recordSample(sample)
    }

    @JvmStatic
    fun recordSample(sample: StartupSample) {
        while (recentSamples.size >= MAX_SAMPLES) {
            recentSamples.removeAt(0)
        }
        recentSamples.add(sample)
    }

    @JvmStatic
    fun clearSamples() {
        recentSamples.clear()
    }

    @JvmStatic
    fun getRecentSamples(): List<StartupSample> = recentSamples.toList()

    @JvmStatic
    fun calculatePercentile(sorted: List<Long>, percentile: Double): Long {
        if (sorted.isEmpty()) return 0L
        if (sorted.size == 1) return sorted[0]
        val index = Math.ceil(percentile * sorted.size).toInt() - 1
        return sorted[index.coerceIn(0, sorted.size - 1)]
    }

    @JvmStatic
    fun getAggregatedStatistics(): Map<String, Any> {
        val samples = recentSamples.toList()
        val count = samples.size
        if (count == 0) {
            return mapOf(
                "sampleCount" to 0,
                "medianTotalMs" to 0L,
                "p90TotalMs" to 0L,
                "minTotalMs" to 0L,
                "maxTotalMs" to 0L,
                "slowestStageOverall" to "NONE",
                "stageMedians" to emptyMap<String, Long>()
            )
        }

        val totalTimes = samples.map { it.totalStartupMs }.sorted()
        val median = calculatePercentile(totalTimes, 0.5)
        val p90 = calculatePercentile(totalTimes, 0.9)
        val min = totalTimes.first()
        val max = totalTimes.last()

        val slowestStageOverall = samples
            .groupBy { it.slowestStage }
            .maxByOrNull { it.value.size }?.key ?: "NONE"

        val allStages = listOf(
            STAGE_REQUEST_START,
            STAGE_MANIFEST_FETCH,
            STAGE_MANIFEST_PARSE,
            STAGE_TRACK_SELECT,
            STAGE_DECODER_INIT,
            STAGE_BUFFER_FILL,
            STAGE_FIRST_FRAME
        )
        val stageMedians = mutableMapOf<String, Long>()
        for (st in allStages) {
            val times = samples.mapNotNull { it.stages[st] }.sorted()
            if (times.isNotEmpty()) {
                stageMedians[st] = calculatePercentile(times, 0.5)
            }
        }

        return mapOf(
            "sampleCount" to count,
            "medianTotalMs" to median,
            "p90TotalMs" to p90,
            "minTotalMs" to min,
            "maxTotalMs" to max,
            "slowestStageOverall" to slowestStageOverall,
            "stageMedians" to stageMedians
        )
    }

    @JvmStatic
    fun hasActiveOrRecordedSession(): Boolean {
        return currentSessionId > 0L || stageRecords.isNotEmpty()
    }

    @JvmStatic
    fun resetForTesting() {
        currentSessionId = 0L
        sessionOpenTimeMs = 0L
        currentEngine = "AUTO"
        transportProtocol = "UNKNOWN"
        stageRecords.clear()
        stageStartTimes.clear()
        recentSamples.clear()
    }

    @JvmStatic
    fun getCurrentProfileSummary(): Map<String, Any>? = getProfileSummary()

    @JvmStatic
    fun getProfileSummary(): Map<String, Any>? {
        if (currentSessionId == 0L && stageRecords.isEmpty()) {
            return null
        }
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

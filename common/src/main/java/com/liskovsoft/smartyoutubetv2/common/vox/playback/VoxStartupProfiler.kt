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
    private val stageRecords = CopyOnWriteArrayList<StageRecord>()
    private val stageStartTimes = ConcurrentHashMap<String, Long>()

    @JvmStatic
    fun onUserOpen(sessionId: Long, engine: String = "AUTO", nowMs: Long = System.currentTimeMillis()) {
        currentSessionId = sessionId
        sessionOpenTimeMs = nowMs
        currentEngine = engine
        stageRecords.clear()
        stageStartTimes.clear()
        recordStage("USER_OPEN", 0L, "OK", nowMs)
    }

    @JvmStatic
    fun startStage(stage: String, nowMs: Long = System.currentTimeMillis()) {
        stageStartTimes[stage] = nowMs
    }

    @JvmStatic
    fun finishStage(stage: String, result: String = "OK", nowMs: Long = System.currentTimeMillis()) {
        val start = stageStartTimes.remove(stage) ?: sessionOpenTimeMs
        val elapsed = if (start > 0L) Math.max(0L, nowMs - start) else 0L
        recordStage(stage, elapsed, result, nowMs)
    }

    @JvmStatic
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

    @JvmStatic
    fun updateEngine(engine: String) {
        currentEngine = engine
    }

    @JvmStatic
    fun getCurrentProfileSummary(): Map<String, Any> = getProfileSummary()

    @JvmStatic
    fun getProfileSummary(): Map<String, Any> {
        val totalStartupMs = stageRecords.lastOrNull()?.relativeOffsetMs ?: 0L
        return mapOf(
            "playbackSessionId" to currentSessionId,
            "engine" to currentEngine,
            "totalStartupMs" to totalStartupMs,
            "stages" to stageRecords.map { it.toMap() }
        )
    }
}

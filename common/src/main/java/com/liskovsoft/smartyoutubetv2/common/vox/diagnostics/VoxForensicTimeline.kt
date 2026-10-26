package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/**
 * VOX Observability 3.0 — Forensic Timeline Builder.
 *
 * Produces a structured diagnostic report grouping events by causal chains (operationId).
 * All content is sanitized per active privacy mode before export.
 *
 * Contract version: 3.0
 */
object VoxForensicTimeline {

    const val CONTRACT_VERSION = "3.0"

    /**
     * Build a forensic report JSON string from the recorder's current state.
     * Groups events by operationId chains for causal analysis.
     * Computes p50/p95 latency gaps between consecutive events.
     */
    fun buildForensicReport(recorder: VoxUserJourneyRecorder): String {
        val events = recorder.getEvents()
        val pinned = recorder.getPinnedBookmarks()
        val mode = recorder.getMode()

        val report = JSONObject()
        val meta = JSONObject().apply {
            put("contractVersion", CONTRACT_VERSION)
            put("schemaVersion", 2)
            put("eventCount", events.size)
            put("pinnedBookmarkCount", pinned.size)
            put("modeName", mode.name)
            put("exportedAt", VoxUserJourneyEvent.currentUtcTimestamp())
            put("contentIncluded", recorder.isIncludeContentDetails() && mode == VoxUserJourneyMode.DETAILED)
        }
        report.put("_meta", meta)

        // Causal chains grouped by operationId
        val chains = JSONObject()
        val ungrouped = JSONArray()
        val grouped = events.groupBy { it.operationId ?: "" }
        for ((opId, opEvents) in grouped) {
            if (opId.isBlank()) {
                opEvents.forEach { ungrouped.put(it.toJson()) }
            } else {
                val chain = JSONArray()
                opEvents.forEach { chain.put(it.toJson()) }
                chains.put(opId, chain)
            }
        }
        report.put("causalChains", chains)
        report.put("ungroupedEvents", ungrouped)

        // Latency statistics on consecutive events
        if (events.size >= 2) {
            val gaps = events.zipWithNext().map { (a, b) -> b.elapsedMs - a.elapsedMs }.filter { it >= 0 }.sorted()
            val p50 = if (gaps.isNotEmpty()) gaps[gaps.size / 2] else 0L
            val p95 = if (gaps.isNotEmpty()) gaps[(gaps.size * 0.95).toInt().coerceAtMost(gaps.size - 1)] else 0L
            val latency = JSONObject().apply {
                put("sampleCount", gaps.size)
                put("p50GapMs", p50)
                put("p95GapMs", p95)
                put("maxGapMs", gaps.lastOrNull() ?: 0L)
            }
            report.put("latencyStats", latency)
        }

        // Pinned bookmarks section
        val pinnedArray = JSONArray()
        pinned.forEach { pinnedArray.put(it.toJson()) }
        report.put("pinnedBookmarks", pinnedArray)

        return report.toString(2)
    }

    /**
     * Generate an anonymized sample timeline for export/sharing.
     * Strips all content details regardless of mode setting.
     */
    fun buildAnonymizedSample(recorder: VoxUserJourneyRecorder, maxEvents: Int = 30): String {
        val events = recorder.getEvents().takeLast(maxEvents)
        val array = JSONArray()
        events.forEach { ev ->
            val sanitized = ev.copy(videoRef = "redacted")
            array.put(sanitized.toJson())
        }
        val wrapper = JSONObject()
        wrapper.put("contractVersion", CONTRACT_VERSION)
        wrapper.put("anonymized", true)
        wrapper.put("events", array)
        return wrapper.toString(2)
    }
}

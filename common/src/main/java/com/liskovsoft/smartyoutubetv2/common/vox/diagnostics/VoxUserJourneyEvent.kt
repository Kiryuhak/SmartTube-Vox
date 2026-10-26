package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Unified Observability 3.0 User Journey Event — Schema v2.
 *
 * Implements strict privacy controls:
 * - Content identifiers (title/videoId) are omitted or hashed ("redacted") unless explicitly permitted.
 * - Monotonic elapsed time is used for duration calculation and UTC wall time for correlation.
 * - Causal parentEventId and operationId link user inputs to downstream playback/VOT/download transitions.
 * - positionBucketMs provides privacy-safe 30-second bucket rounding for position telemetry.
 *
 * Schema migration: fromJson handles both v1 (schemaVersion=1) and v2 (schemaVersion=2).
 * All fields added in v2 default to null when loading v1 events.
 *
 * Contract version: 3.0
 */
data class VoxUserJourneyEvent(
    val schemaVersion: Int = 2,
    val eventId: String,
    val sessionId: String,
    val playbackSessionId: String? = null,
    val sequence: Long,
    val wallTimeUtc: String,
    val elapsedMs: Long,
    val category: String,
    val event: String,
    val cause: String? = null,
    val screen: String? = null,
    val viewRole: String? = null,
    val positionBeforeMs: Long? = null,
    val positionRequestedMs: Long? = null,
    val liveWindowStartMs: Long? = null,
    val liveWindowEndMs: Long? = null,
    val videoRef: String = "redacted",
    val privacyLevel: String = "STANDARD",
    val parentEventId: String? = null,
    val operationId: String? = null,
    val status: String? = null,
    val durationMs: Long? = null,
    val details: Map<String, Any>? = null,
    // --- Schema v2 fields ---
    val appSessionId: String? = null,
    val processSessionId: String? = null,
    val bootId: String? = null,
    val downloadJobRef: String? = null,
    val action: String? = null,
    val outcome: String? = null,
    /** Position rounded to 30-second bucket for privacy-safe telemetry. */
    val positionBucketMs: Long? = null,
    val selectedQuality: String? = null,
    val resolvedQuality: String? = null,
    val translationState: String? = null,
    val networkErrorClass: String? = null,
    val retryAttempt: Int? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("schemaVersion", schemaVersion)
        json.put("eventId", eventId)
        json.put("sessionId", sessionId)
        if (!playbackSessionId.isNullOrBlank()) json.put("playbackSessionId", playbackSessionId)
        json.put("sequence", sequence)
        json.put("wallTimeUtc", wallTimeUtc)
        json.put("elapsedMs", elapsedMs)
        json.put("category", category)
        json.put("event", event)
        if (!cause.isNullOrBlank()) json.put("cause", cause)
        if (!screen.isNullOrBlank()) json.put("screen", screen)
        if (!viewRole.isNullOrBlank()) json.put("viewRole", viewRole)
        if (positionBeforeMs != null) json.put("positionBeforeMs", positionBeforeMs)
        if (positionRequestedMs != null) json.put("positionRequestedMs", positionRequestedMs)
        if (liveWindowStartMs != null) json.put("liveWindowStartMs", liveWindowStartMs)
        if (liveWindowEndMs != null) json.put("liveWindowEndMs", liveWindowEndMs)
        json.put("videoRef", videoRef)
        json.put("privacyLevel", privacyLevel)
        if (!parentEventId.isNullOrBlank()) json.put("parentEventId", parentEventId)
        if (!operationId.isNullOrBlank()) json.put("operationId", operationId)
        if (!status.isNullOrBlank()) json.put("status", status)
        if (durationMs != null) json.put("durationMs", durationMs)
        if (!details.isNullOrEmpty()) {
            val dJson = JSONObject()
            details.forEach { (k, v) ->
                val sanitizedVal = if (v is String) VoxDiagnosticSanitizer.sanitize(v) else v
                dJson.put(k, sanitizedVal)
            }
            json.put("details", dJson)
        }
        // Schema v2 fields serialization
        if (!appSessionId.isNullOrBlank()) json.put("appSessionId", appSessionId)
        if (!processSessionId.isNullOrBlank()) json.put("processSessionId", processSessionId)
        if (!bootId.isNullOrBlank()) json.put("bootId", bootId)
        if (!downloadJobRef.isNullOrBlank()) json.put("downloadJobRef", downloadJobRef)
        if (!action.isNullOrBlank()) json.put("action", action)
        if (!outcome.isNullOrBlank()) json.put("outcome", outcome)
        if (positionBucketMs != null) json.put("positionBucketMs", positionBucketMs)
        if (!selectedQuality.isNullOrBlank()) json.put("selectedQuality", selectedQuality)
        if (!resolvedQuality.isNullOrBlank()) json.put("resolvedQuality", resolvedQuality)
        if (!translationState.isNullOrBlank()) json.put("translationState", translationState)
        if (!networkErrorClass.isNullOrBlank()) json.put("networkErrorClass", networkErrorClass)
        if (retryAttempt != null) json.put("retryAttempt", retryAttempt)
        return json
    }

    fun toSummaryString(): String {
        val time = if (wallTimeUtc.length >= 19) wallTimeUtc.substring(11, 19) else wallTimeUtc
        val op = if (!operationId.isNullOrBlank()) " [op:$operationId]" else ""
        val stat = if (!status.isNullOrBlank()) " -> $status" else ""
        val dur = if (durationMs != null && durationMs > 0) " (${durationMs}ms)" else ""
        val q = if (!selectedQuality.isNullOrBlank()) " [q:$selectedQuality]" else ""
        val det = if (!details.isNullOrEmpty()) " det:$details" else ""
        return "#$sequence [$time] $category.$event$op$stat$dur$q$det"
    }

    companion object {
        private val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        fun currentUtcTimestamp(): String {
            return synchronized(utcFormatter) {
                utcFormatter.format(Date())
            }
        }

        fun fromJson(json: JSONObject): VoxUserJourneyEvent? {
            return try {
                val schemaVer = json.optInt("schemaVersion", 1)
                val eventId = json.optString("eventId", "")
                val sessionId = json.optString("sessionId", "")
                val playbackSessionId = if (json.has("playbackSessionId")) json.optString("playbackSessionId") else null
                val sequence = json.optLong("sequence", 0L)
                val wallTimeUtc = json.optString("wallTimeUtc", "")
                val elapsedMs = json.optLong("elapsedMs", 0L)
                val category = json.optString("category", "SYSTEM")
                val event = json.optString("event", "UNKNOWN")
                val cause = if (json.has("cause")) json.optString("cause") else null
                val screen = if (json.has("screen")) json.optString("screen") else null
                val viewRole = if (json.has("viewRole")) json.optString("viewRole") else null
                val posBefore = if (json.has("positionBeforeMs")) json.optLong("positionBeforeMs") else null
                val posReq = if (json.has("positionRequestedMs")) json.optLong("positionRequestedMs") else null
                val winStart = if (json.has("liveWindowStartMs")) json.optLong("liveWindowStartMs") else null
                val winEnd = if (json.has("liveWindowEndMs")) json.optLong("liveWindowEndMs") else null
                val videoRef = json.optString("videoRef", "redacted")
                val privacyLevel = json.optString("privacyLevel", "STANDARD")
                val parentId = if (json.has("parentEventId")) json.optString("parentEventId") else null
                val opId = if (json.has("operationId")) json.optString("operationId") else null
                val status = if (json.has("status")) json.optString("status") else null
                val durMs = if (json.has("durationMs")) json.optLong("durationMs") else null

                val detailsObj = json.optJSONObject("details")
                val detailsMap = if (detailsObj != null) {
                    val map = mutableMapOf<String, Any>()
                    val keys = detailsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = detailsObj.opt(k)
                        if (v != null) {
                            map[k] = v
                        }
                    }
                    map
                } else null

                // Schema v2 fields — null when loading v1 events (backwards compatible)
                val appSessionId = if (schemaVer >= 2 && json.has("appSessionId")) json.optString("appSessionId") else null
                val processSessionId = if (schemaVer >= 2 && json.has("processSessionId")) json.optString("processSessionId") else null
                val bootId = if (schemaVer >= 2 && json.has("bootId")) json.optString("bootId") else null
                val downloadJobRef = if (schemaVer >= 2 && json.has("downloadJobRef")) json.optString("downloadJobRef") else null
                val action = if (schemaVer >= 2 && json.has("action")) json.optString("action") else null
                val outcome = if (schemaVer >= 2 && json.has("outcome")) json.optString("outcome") else null
                val positionBucketMs = if (schemaVer >= 2 && json.has("positionBucketMs")) json.optLong("positionBucketMs") else null
                val selectedQuality = if (schemaVer >= 2 && json.has("selectedQuality")) json.optString("selectedQuality") else null
                val resolvedQuality = if (schemaVer >= 2 && json.has("resolvedQuality")) json.optString("resolvedQuality") else null
                val translationState = if (schemaVer >= 2 && json.has("translationState")) json.optString("translationState") else null
                val networkErrorClass = if (schemaVer >= 2 && json.has("networkErrorClass")) json.optString("networkErrorClass") else null
                val retryAttempt = if (schemaVer >= 2 && json.has("retryAttempt")) json.optInt("retryAttempt") else null

                VoxUserJourneyEvent(
                    schemaVersion = schemaVer,
                    eventId = eventId,
                    sessionId = sessionId,
                    playbackSessionId = playbackSessionId,
                    sequence = sequence,
                    wallTimeUtc = wallTimeUtc,
                    elapsedMs = elapsedMs,
                    category = category,
                    event = event,
                    cause = cause,
                    screen = screen,
                    viewRole = viewRole,
                    positionBeforeMs = posBefore,
                    positionRequestedMs = posReq,
                    liveWindowStartMs = winStart,
                    liveWindowEndMs = winEnd,
                    videoRef = videoRef,
                    privacyLevel = privacyLevel,
                    parentEventId = parentId,
                    operationId = opId,
                    status = status,
                    durationMs = durMs,
                    details = detailsMap,
                    appSessionId = appSessionId,
                    processSessionId = processSessionId,
                    bootId = bootId,
                    downloadJobRef = downloadJobRef,
                    action = action,
                    outcome = outcome,
                    positionBucketMs = positionBucketMs,
                    selectedQuality = selectedQuality,
                    resolvedQuality = resolvedQuality,
                    translationState = translationState,
                    networkErrorClass = networkErrorClass,
                    retryAttempt = retryAttempt
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

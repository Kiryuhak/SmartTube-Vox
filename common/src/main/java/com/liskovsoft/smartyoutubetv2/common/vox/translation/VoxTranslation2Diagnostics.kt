package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.json.JSONObject

/**
 * Безопасный снимок диагностических метрик подсистемы Translation 2.0.
 * В соответствии с принципами Zero-Telemetry и приватности:
 * - Не содержит персональных данных (PII), пользовательских аккаунтов;
 * - Не содержит media URL, токенов, заголовков авторизации;
 * - Не содержит названий видео.
 */
data class VoxTranslationDiagnosticsSnapshot(
    val mode: String,
    val backendCapability: String,
    val sessionState: String,
    val queueDepth: Int,
    val bufferedMs: Long,
    val averageLatencyMs: Long,
    val lastErrorCategory: String?,
    val fallbackActive: Boolean
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "mode" to mode,
            "backendCapability" to backendCapability,
            "sessionState" to sessionState,
            "queueDepth" to queueDepth,
            "bufferedMs" to bufferedMs,
            "averageLatencyMs" to averageLatencyMs,
            "fallbackActive" to fallbackActive
        )
        if (lastErrorCategory != null) {
            map["lastErrorCategory"] = lastErrorCategory
        }
        return map
    }

    fun toJsonString(): String {
        val errPart = if (lastErrorCategory != null) ",\"lastErrorCategory\":\"$lastErrorCategory\"" else ""
        return "{\"mode\":\"$mode\",\"backendCapability\":\"$backendCapability\",\"sessionState\":\"$sessionState\",\"queueDepth\":$queueDepth,\"bufferedMs\":$bufferedMs,\"averageLatencyMs\":$averageLatencyMs,\"fallbackActive\":$fallbackActive$errPart}"
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("mode", mode)
        json.put("backendCapability", backendCapability)
        json.put("sessionState", sessionState)
        json.put("queueDepth", queueDepth)
        json.put("bufferedMs", bufferedMs)
        json.put("averageLatencyMs", averageLatencyMs)
        json.put("fallbackActive", fallbackActive)
        if (lastErrorCategory != null) {
            json.put("lastErrorCategory", lastErrorCategory)
        }
        return json
    }
}

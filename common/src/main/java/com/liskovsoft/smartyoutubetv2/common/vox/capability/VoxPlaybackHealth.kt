package com.liskovsoft.smartyoutubetv2.common.vox.capability

import org.json.JSONObject

/**
 * Категории стабильности реального воспроизведения (Auto Setup 2.0).
 */
enum class VoxPlaybackHealth(val labelRu: String) {
    EXCELLENT("Отличное"),
    GOOD("Хорошее"),
    DEGRADED("Умеренно сниженное"),
    POOR("Нестабильное"),
    UNKNOWN("Недостаточно данных");

    companion object {
        @JvmStatic
        fun fromName(name: String?): VoxPlaybackHealth {
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * Уровень статистической уверенности в оценке.
 */
enum class VoxPlaybackHealthConfidence(val labelRu: String) {
    LOW("Низкая (мало наблюдений)"),
    MEDIUM("Средняя"),
    HIGH("Высокая (устойчивый профиль)");

    companion object {
        @JvmStatic
        fun fromName(name: String?): VoxPlaybackHealthConfidence {
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LOW
        }
    }
}

/**
 * Локализованный тип выявленной проблемы воспроизведения.
 * Позволяет строго разделять сетевые просадки от перегрузок аппаратного декодера.
 */
enum class VoxPlaybackIssueType(val labelRu: String) {
    NONE("Проблем не обнаружено"),
    NETWORK_LIMITED("Ограничение скорости сети / пропускной способности"),
    DECODER_LIMITED("Аппаратное ограничение видеодекодера (сброс кадров / ошибки MediaCodec)"),
    UNKNOWN("Не определено");

    companion object {
        @JvmStatic
        fun fromName(name: String?): VoxPlaybackIssueType {
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * Коды причин и факторов автонастройки.
 */
object VoxAutoSetupReasonCode {
    const val RUNTIME_4K_STABLE = "RUNTIME_4K_STABLE"
    const val RUNTIME_4K_REBUFFER = "RUNTIME_4K_REBUFFER"
    const val VP9_STABLE = "VP9_STABLE"
    const val AV1_DECODER_ERROR = "AV1_DECODER_ERROR"
    const val NETWORK_LIMITED = "NETWORK_LIMITED"
    const val DECODER_LIMITED = "DECODER_LIMITED"
    const val INSUFFICIENT_SAMPLES = "INSUFFICIENT_SAMPLES"
    const val MANUAL_OVERRIDE = "MANUAL_OVERRIDE"
    const val BALANCED_DEFAULT = "BALANCED_DEFAULT"
}

/**
 * Локальный замер отдельной сессии воспроизведения.
 * ВНИМАНИЕ: Принцип Zero-Telemetry — в структуре принципиально отсутствуют
 * Video ID, Channel Name, Video Title и URL.
 */
data class VoxPlaybackSessionObservation(
    val sessionId: String = "",
    val selectedHeight: Int = 1080,
    val selectedCodec: String = "vp9",
    val isLive: Boolean = false,
    val playbackDurationMs: Long = 0L,
    val startupLatencyMs: Long = 0L,
    val rebufferCount: Int = 0,
    val totalRebufferMs: Long = 0L,
    val droppedFrames: Int = 0,
    val decoderInitFailures: Int = 0,
    val networkBandwidthKbps: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("sessionId", sessionId)
        json.put("selectedHeight", selectedHeight)
        json.put("selectedCodec", selectedCodec)
        json.put("isLive", isLive)
        json.put("playbackDurationMs", playbackDurationMs)
        json.put("startupLatencyMs", startupLatencyMs)
        json.put("rebufferCount", rebufferCount)
        json.put("totalRebufferMs", totalRebufferMs)
        json.put("droppedFrames", droppedFrames)
        json.put("decoderInitFailures", decoderInitFailures)
        json.put("networkBandwidthKbps", networkBandwidthKbps)
        json.put("timestamp", timestamp)
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject?): VoxPlaybackSessionObservation? {
            if (json == null) return null
            return try {
                VoxPlaybackSessionObservation(
                    sessionId = json.optString("sessionId", ""),
                    selectedHeight = json.optInt("selectedHeight", 1080),
                    selectedCodec = json.optString("selectedCodec", "vp9"),
                    isLive = json.optBoolean("isLive", false),
                    playbackDurationMs = json.optLong("playbackDurationMs", 0L),
                    startupLatencyMs = json.optLong("startupLatencyMs", 0L),
                    rebufferCount = json.optInt("rebufferCount", 0),
                    totalRebufferMs = json.optLong("totalRebufferMs", 0L),
                    droppedFrames = json.optInt("droppedFrames", 0),
                    decoderInitFailures = json.optInt("decoderInitFailures", 0),
                    networkBandwidthKbps = json.optLong("networkBandwidthKbps", 0L),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Итоговая рекомендация Auto Setup 2.0.
 */
data class VoxAutoSetupRecommendation(
    val recommendedResolution: Int,
    val recommendedCodec: VoxVideoCodecPreference,
    val confidence: VoxPlaybackHealthConfidence,
    val health: VoxPlaybackHealth,
    val issueType: VoxPlaybackIssueType,
    val reasonCodes: List<String>,
    val userExplanationRu: String,
    val isManualOverrideActive: Boolean = false
)

/**
 * Безопасная сводка статистики для диагностических отчётов.
 */
data class VoxPlaybackHealthSummary(
    val sampleCount: Int = 0,
    val liveSampleCount: Int = 0,
    val vodSampleCount: Int = 0,
    val bestStableHeight: Int = 0,
    val stableVideoCodec: String = "auto",
    val droppedFrameRate: Float = 0f,
    val rebufferRate: Float = 0f,
    val confidence: VoxPlaybackHealthConfidence = VoxPlaybackHealthConfidence.LOW
) {
    fun toDiagnosticString(): String {
        return "Сессий: $sampleCount (VOD: $vodSampleCount, Live: $liveSampleCount), " +
                "Макс. стабильное: ${if (bestStableHeight > 0) "${bestStableHeight}p" else "Н/Д"}, " +
                "Кодек: $stableVideoCodec, " +
                "Буферизаций/мин: ${"%.2f".format(rebufferRate)}, " +
                "Пропуск кадров/сек: ${"%.2f".format(droppedFrameRate)}, " +
                "Уверенность: ${confidence.labelRu}"
    }

    fun toMap(): Map<String, Any> {
        return mapOf(
            "sampleCount" to sampleCount,
            "liveSampleCount" to liveSampleCount,
            "vodSampleCount" to vodSampleCount,
            "bestStableHeight" to bestStableHeight,
            "stableVideoCodec" to stableVideoCodec,
            "droppedFrameRate" to droppedFrameRate,
            "rebufferRate" to rebufferRate,
            "confidence" to confidence.name
        )
    }
}

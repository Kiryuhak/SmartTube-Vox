package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.json.JSONObject

/**
 * Стандартизированные статусы провайдера Live Translation (Section 8).
 */
enum class VoxLiveProviderState(val id: String, val titleRu: String) {
    AVAILABLE("available", "Провайдер готов к работе"),
    UNAVAILABLE("unavailable", "Провайдер недоступен (нет соединения или хост отключен)"),
    UNSUPPORTED("unsupported", "Инкрементальный потоковый перевод не поддерживается"),
    AUTH_REQUIRED("auth_required", "Требуется авторизация или API-ключ"),
    RATE_LIMITED("rate_limited", "Превышена квота или частота запросов (429)"),
    DEGRADED("degraded", "Качество или задержка сервиса деградировали"),
    ERROR("error", "Внутренняя ошибка провайдера");

    companion object {
        fun fromString(value: String?): VoxLiveProviderState {
            if (value == null) return UNAVAILABLE
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) || it.id.equals(value, ignoreCase = true) }
                ?: ERROR
        }
    }
}

/**
 * Возможности провайдера потокового перевода (Section 7).
 */
data class VoxLiveProviderCapabilities(
    val providerId: String,
    val supportsRawPcm: Boolean = true,
    val supportsEncodedAudio: Boolean = false,
    val supportsIncremental: Boolean = true,
    val supportsStreaming: Boolean = false,
    val supportsSession: Boolean = true,
    val supportsCancellation: Boolean = true,
    val supportsSourceLanguageAuto: Boolean = true,
    val supportsVoiceSynthesis: Boolean = true,
    val supportsPartialResults: Boolean = false,
    val targetSampleRate: Int = 16000,
    val targetChannels: Int = 1,
    val description: String = ""
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "providerId" to providerId,
            "supportsRawPcm" to supportsRawPcm,
            "supportsEncodedAudio" to supportsEncodedAudio,
            "supportsIncremental" to supportsIncremental,
            "supportsStreaming" to supportsStreaming,
            "supportsSession" to supportsSession,
            "supportsCancellation" to supportsCancellation,
            "supportsSourceLanguageAuto" to supportsSourceLanguageAuto,
            "supportsVoiceSynthesis" to supportsVoiceSynthesis,
            "supportsPartialResults" to supportsPartialResults,
            "targetSampleRate" to targetSampleRate,
            "targetChannels" to targetChannels
        )
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        for ((key, value) in toMap()) {
            json.put(key, value)
        }
        return json
    }
}

/**
 * Стандартизированный результат обработки аудиофрагмента провайдером (Section 9).
 */
data class VoxLiveProviderResult(
    val sequence: Long,
    val generation: Long,
    val sourceStartPtsUs: Long,
    val sourceEndPtsUs: Long,
    val translatedAudio: ByteArray?,
    val audioFormat: String = "pcm_16le",
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val providerLatencyMs: Long = 0L,
    val providerStatus: VoxLiveProviderState = VoxLiveProviderState.AVAILABLE,
    val detectedLanguage: String? = null,
    val translationText: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoxLiveProviderResult
        if (sequence != other.sequence) return false
        if (generation != other.generation) return false
        if (sourceStartPtsUs != other.sourceStartPtsUs) return false
        if (sourceEndPtsUs != other.sourceEndPtsUs) return false
        if (translatedAudio != null) {
            if (other.translatedAudio == null) return false
            if (!translatedAudio.contentEquals(other.translatedAudio)) return false
        } else if (other.translatedAudio != null) return false
        if (audioFormat != other.audioFormat) return false
        if (sampleRate != other.sampleRate) return false
        if (channels != other.channels) return false
        if (providerLatencyMs != other.providerLatencyMs) return false
        if (providerStatus != other.providerStatus) return false
        if (detectedLanguage != other.detectedLanguage) return false
        if (translationText != other.translationText) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sequence.hashCode()
        result = 31 * result + generation.hashCode()
        result = 31 * result + sourceStartPtsUs.hashCode()
        result = 31 * result + sourceEndPtsUs.hashCode()
        result = 31 * result + (translatedAudio?.contentHashCode() ?: 0)
        result = 31 * result + audioFormat.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + providerLatencyMs.hashCode()
        result = 31 * result + providerStatus.hashCode()
        result = 31 * result + (detectedLanguage?.hashCode() ?: 0)
        result = 31 * result + (translationText?.hashCode() ?: 0)
        return result
    }
}

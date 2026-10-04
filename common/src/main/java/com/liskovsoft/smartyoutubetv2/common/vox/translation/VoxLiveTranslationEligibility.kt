package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Возможности бэкенда перевода по обработке прямых эфиров.
 */
enum class VoxLiveBackendCapability(val id: String, val titleRu: String) {
    VOD_ONLY("vod_only", "Только предварительно записанные VOD-видео"),
    LIVE_CHUNK_SUPPORTED("live_chunk", "Поддержка потоковых аудио-сегментов"),
    LIVE_URL_SUPPORTED("live_url", "Прямая обработка URL трансляции сервером"),
    UNKNOWN("unknown", "Неизвестный тип бэкенда");
}

/**
 * Статус применимости перевода к прямому эфиру.
 */
enum class VoxLiveEligibilityStatus {
    SUPPORTED,
    SUPPORTED_WITH_LIMITS,
    UNSUPPORTED
}

/**
 * Результат проверки применимости live-перевода.
 */
data class VoxLiveTranslationEligibilityResult(
    val status: VoxLiveEligibilityStatus,
    val reasonRu: String,
    val technicalDetails: String
)

/**
 * Проверка условий доступности перевода для прямых эфиров (Live stream).
 */
object VoxLiveTranslationEligibility {

    @JvmStatic
    @JvmOverloads
    fun checkEligibility(
        isLive: Boolean,
        isDvrAvailable: Boolean,
        isSeekable: Boolean,
        audioTrackAvailable: Boolean,
        sourceLanguage: String?,
        backendCapability: VoxLiveBackendCapability = VoxLiveBackendCapability.VOD_ONLY
    ): VoxLiveTranslationEligibilityResult {
        if (!isLive) {
            return VoxLiveTranslationEligibilityResult(
                status = VoxLiveEligibilityStatus.UNSUPPORTED,
                reasonRu = "Видео не является прямым эфиром",
                technicalDetails = "isLive=false"
            )
        }

        if (!audioTrackAvailable) {
            return VoxLiveTranslationEligibilityResult(
                status = VoxLiveEligibilityStatus.UNSUPPORTED,
                reasonRu = "Аудиодорожка трансляции недоступна",
                technicalDetails = "audioTrackAvailable=false"
            )
        }

        val lang = sourceLanguage?.lowercase()?.trim()
        if (lang == "ru" || lang == "rus" || lang == "russian") {
            return VoxLiveTranslationEligibilityResult(
                status = VoxLiveEligibilityStatus.UNSUPPORTED,
                reasonRu = "Исходная дорожка уже на русском языке",
                technicalDetails = "sourceLanguage=$sourceLanguage"
            )
        }

        if (backendCapability == VoxLiveBackendCapability.VOD_ONLY) {
            return VoxLiveTranslationEligibilityResult(
                status = VoxLiveEligibilityStatus.UNSUPPORTED,
                reasonRu = "Перевод прямого эфира недоступен: сервер перевода поддерживает только готовые видео (VOD)",
                technicalDetails = "backendCapability=VOD_ONLY"
            )
        }

        if (backendCapability == VoxLiveBackendCapability.UNKNOWN) {
            return VoxLiveTranslationEligibilityResult(
                status = VoxLiveEligibilityStatus.UNSUPPORTED,
                reasonRu = "Возможности сервера перевода не определены",
                technicalDetails = "backendCapability=UNKNOWN"
            )
        }

        // Live stream with DVR window support
        if (backendCapability == VoxLiveBackendCapability.LIVE_CHUNK_SUPPORTED ||
            backendCapability == VoxLiveBackendCapability.LIVE_URL_SUPPORTED) {
            return if (isDvrAvailable || isSeekable) {
                VoxLiveTranslationEligibilityResult(
                    status = VoxLiveEligibilityStatus.SUPPORTED,
                    reasonRu = "Прямой эфир поддерживается с динамическим буфером",
                    technicalDetails = "backend=$backendCapability, dvr=$isDvrAvailable"
                )
            } else {
                VoxLiveTranslationEligibilityResult(
                    status = VoxLiveEligibilityStatus.SUPPORTED_WITH_LIMITS,
                    reasonRu = "Прямой эфир поддерживается без возможности перемотки (DVR)",
                    technicalDetails = "backend=$backendCapability, dvr=false"
                )
            }
        }

        return VoxLiveTranslationEligibilityResult(
            status = VoxLiveEligibilityStatus.UNSUPPORTED,
            reasonRu = "Прямой эфир не поддерживается текущей конфигурацией",
            technicalDetails = "fallback_unsupported"
        )
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.capability

/**
 * Режим политики выбора кодеков.
 */
enum class VoxCodecPolicyMode(val id: String, val titleRu: String) {
    AUTO("auto", "Автоматически"),
    MAX_QUALITY("max_quality", "Максимальное качество"),
    MAX_COMPATIBILITY("max_compatibility", "Максимальная совместимость"),
    CUSTOM("custom", "Пользовательский");

    companion object {
        @JvmStatic
        fun fromId(id: String?): VoxCodecPolicyMode {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

/**
 * Пользовательские предпочтения по видеокодекам.
 */
enum class VoxVideoCodecPreference(val id: String, val displayName: String, val mimeType: String) {
    AUTO("auto", "Автоматически", ""),
    AVC("avc", "AVC / H.264", "video/avc"),
    VP9("vp9", "VP9", "video/x-vnd.on2.vp9"),
    AV1("av1", "AV1", "video/av01");

    companion object {
        @JvmStatic
        fun fromId(id: String?): VoxVideoCodecPreference {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

/**
 * Пользовательские предпочтения по аудиокодекам.
 */
enum class VoxAudioCodecPreference(val id: String, val displayName: String, val mimeType: String) {
    AUTO("auto", "Автоматически", ""),
    AAC("aac", "AAC", "audio/mp4a-latm"),
    OPUS("opus", "Opus", "audio/opus"),
    AC3("ac3", "AC3", "audio/ac3"),
    EAC3("eac3", "EAC3", "audio/eac3");

    companion object {
        @JvmStatic
        fun fromId(id: String?): VoxAudioCodecPreference {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

/**
 * Результат выбора формата с информацией о fallback и предупреждениях.
 */
data class VoxCodecSelectionResult<T>(
    val selected: T?,
    val isFallback: Boolean = false,
    val warning: String? = null
)

/**
 * Единый движок политики выбора кодеков.
 */
data class VoxCodecPolicy(
    val mode: VoxCodecPolicyMode = VoxCodecPolicyMode.AUTO,
    val maxQualityHeight: Int = 0, // 0 = Auto / Без ограничений
    val preferredVideoCodec: VoxVideoCodecPreference = VoxVideoCodecPreference.AUTO,
    val preferredAudioCodec: VoxAudioCodecPreference = VoxAudioCodecPreference.AUTO,
    val passthroughEnabled: Boolean = true
) {
    /**
     * Выбирает оптимальный видеокодек на основе доступных потоков и профиля устройства.
     *
     * @param availableCodecs список доступных кодеков видеопотока (напр. ["av1", "vp9", "avc"])
     * @param profile профиль возможностей устройства
     */
    fun selectVideoCodec(
        availableCodecs: List<String>,
        profile: VoxDeviceProfile
    ): VoxCodecSelectionResult<String> {
        if (availableCodecs.isEmpty()) {
            return VoxCodecSelectionResult(null)
        }

        val normalizedAvailable = availableCodecs.map { normalizeVideoCodec(it) }

        when (mode) {
            VoxCodecPolicyMode.MAX_COMPATIBILITY -> {
                // Максимальная совместимость: отдавать строгий приоритет AVC / H.264
                if (normalizedAvailable.contains("avc")) {
                    return VoxCodecSelectionResult("avc")
                }
                if (normalizedAvailable.contains("vp9") && profile.isVideoCodecSupported("vp9")) {
                    return VoxCodecSelectionResult("vp9", isFallback = true)
                }
                return VoxCodecSelectionResult(normalizedAvailable.first(), isFallback = true)
            }
            VoxCodecPolicyMode.CUSTOM -> {
                if (preferredVideoCodec != VoxVideoCodecPreference.AUTO) {
                    val target = preferredVideoCodec.id
                    if (normalizedAvailable.contains(target)) {
                        val isSupported = profile.isVideoCodecSupported(target)
                        val warning = if (!isSupported) {
                            "Формат не заявлен как поддерживаемый устройством. Воспроизведение может не работать."
                        } else null
                        return VoxCodecSelectionResult(target, isFallback = !isSupported, warning = warning)
                    }
                }
                // Если выбранный пользователем кодек отсутствует в видео, используем логику AUTO
            }
            VoxCodecPolicyMode.MAX_QUALITY -> {
                // Максимальное качество: наилучший из ДОСТОВЕРНО поддерживаемых устройством
                for (codec in listOf("av1", "vp9", "avc")) {
                    if (normalizedAvailable.contains(codec) && profile.isVideoCodecSupported(codec)) {
                        return VoxCodecSelectionResult(codec)
                    }
                }
                // Безопасный откат к AVC или первому доступному
                val fallback = if (normalizedAvailable.contains("avc")) "avc" else normalizedAvailable.first()
                return VoxCodecSelectionResult(fallback, isFallback = true)
            }
            VoxCodecPolicyMode.AUTO -> {
                // Авто: проверяет поддержку от более качественных к стандартным (AV1 -> VP9 -> AVC)
                if (normalizedAvailable.contains("av1") && profile.isVideoCodecSupported("av1")) {
                    return VoxCodecSelectionResult("av1")
                }
                if (normalizedAvailable.contains("vp9") && profile.isVideoCodecSupported("vp9")) {
                    return VoxCodecSelectionResult("vp9")
                }
                if (normalizedAvailable.contains("avc")) {
                    return VoxCodecSelectionResult("avc")
                }
                return VoxCodecSelectionResult(normalizedAvailable.first(), isFallback = true)
            }
        }

        return VoxCodecSelectionResult(normalizedAvailable.first())
    }

    /**
     * Выбирает оптимальный аудиокодек с учётом декодирования, passthrough и отказоустойчивости.
     *
     * @param availableCodecs список доступных аудиокодеков (напр. ["eac3", "ac3", "opus", "aac"])
     * @param profile профиль возможностей устройства
     */
    fun selectAudioCodec(
        availableCodecs: List<String>,
        profile: VoxDeviceProfile
    ): VoxCodecSelectionResult<String> {
        if (availableCodecs.isEmpty()) {
            return VoxCodecSelectionResult(null)
        }

        val normalizedAvailable = availableCodecs.map { normalizeAudioCodec(it) }

        if (mode == VoxCodecPolicyMode.CUSTOM && preferredAudioCodec != VoxAudioCodecPreference.AUTO) {
            val target = preferredAudioCodec.id
            if (normalizedAvailable.contains(target)) {
                val isDecSupported = profile.isAudioDecodeSupported(target)
                val isPtSupported = profile.isAudioPassthroughSupported(target)
                val isSupported = isDecSupported || (passthroughEnabled && isPtSupported)
                val warning = if (!isSupported) {
                    "Формат не заявлен как поддерживаемый устройством. Воспроизведение может не работать."
                } else if (!isDecSupported && isPtSupported) {
                    "EAC3 — доступен только через совместимый аудиовыход"
                } else null
                return VoxCodecSelectionResult(target, isFallback = !isSupported, warning = warning)
            }
        }

        if (mode == VoxCodecPolicyMode.MAX_COMPATIBILITY) {
            if (normalizedAvailable.contains("aac")) {
                return VoxCodecSelectionResult("aac")
            }
            if (normalizedAvailable.contains("opus") && profile.isAudioDecodeSupported("opus")) {
                return VoxCodecSelectionResult("opus")
            }
        }

        // Стандартная иерархия качества и многоканальности: EAC3 -> AC3 -> Opus -> AAC
        if (normalizedAvailable.contains("eac3")) {
            val dec = profile.isAudioDecodeSupported("eac3")
            val pt = profile.isAudioPassthroughSupported("eac3")
            if (dec || (passthroughEnabled && pt)) {
                val warning = if (!dec && pt) "EAC3 — доступен только через совместимый аудиовыход" else null
                return VoxCodecSelectionResult("eac3", warning = warning)
            }
        }

        if (normalizedAvailable.contains("ac3")) {
            val dec = profile.isAudioDecodeSupported("ac3")
            val pt = profile.isAudioPassthroughSupported("ac3")
            if (dec || (passthroughEnabled && pt)) {
                return VoxCodecSelectionResult("ac3")
            }
        }

        if (normalizedAvailable.contains("opus") && profile.isAudioDecodeSupported("opus")) {
            return VoxCodecSelectionResult("opus")
        }

        if (normalizedAvailable.contains("aac")) {
            return VoxCodecSelectionResult("aac")
        }

        return VoxCodecSelectionResult(normalizedAvailable.first(), isFallback = true)
    }

    private fun normalizeVideoCodec(codec: String): String {
        val lower = codec.lowercase()
        return when {
            lower.contains("av01") || lower.contains("av1") -> "av1"
            lower.contains("vp9") || lower.contains("vp09") -> "vp9"
            lower.contains("avc") || lower.contains("h264") -> "avc"
            lower.contains("hevc") || lower.contains("h265") -> "hevc"
            else -> lower
        }
    }

    private fun normalizeAudioCodec(codec: String): String {
        val lower = codec.lowercase()
        return when {
            lower.contains("eac3") || lower.contains("ec-3") -> "eac3"
            lower.contains("ac3") || lower.contains("ac-3") -> "ac3"
            lower.contains("opus") -> "opus"
            lower.contains("mp4a") || lower.contains("aac") -> "aac"
            else -> lower
        }
    }
}

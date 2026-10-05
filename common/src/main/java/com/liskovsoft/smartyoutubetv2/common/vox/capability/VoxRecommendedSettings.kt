package com.liskovsoft.smartyoutubetv2.common.vox.capability

/**
 * Уровни производительности аппаратной платформы устройства.
 */
enum class VoxPerformanceTier(val displayNameRu: String, val descriptionRu: String) {
    BASIC(
        "Базовый",
        "Устройство с базовой производительностью. Рекомендуется разрешение до 1080p и проверенные кодеки."
    ),
    STANDARD(
        "Стандартный",
        "Современный телевизор / приставка со стандартной поддержкой 4K и аппаратными декодерами."
    ),
    POWERFUL(
        "Высокий",
        "Флагманское устройство с достаточным объёмом памяти, аппаратным 4K60 и поддержкой современных кодеков."
    );
}

/**
 * Рекомендуемые параметры совместимости и производительности для конкретного устройства.
 */
data class VoxRecommendedSettings(
    val tier: VoxPerformanceTier,
    val policyMode: VoxCodecPolicyMode = VoxCodecPolicyMode.AUTO,
    val maxQualityHeight: Int = 2160,
    val preferredVideoCodec: VoxVideoCodecPreference = VoxVideoCodecPreference.AUTO,
    val preferredAudioCodec: VoxAudioCodecPreference = VoxAudioCodecPreference.AUTO,
    val passthroughEnabled: Boolean = true,
    val rationale: List<String> = emptyList(),
    val reasonCodes: List<String> = emptyList()
) {
    fun toCodecPolicy(): VoxCodecPolicy {
        return VoxCodecPolicy(
            mode = policyMode,
            maxQualityHeight = maxQualityHeight,
            preferredVideoCodec = preferredVideoCodec,
            preferredAudioCodec = preferredAudioCodec,
            passthroughEnabled = passthroughEnabled
        )
    }

    fun getSummaryRu(): String {
        val qualityStr = if (maxQualityHeight > 0) "${maxQualityHeight}p" else "Авто"
        val videoStr = preferredVideoCodec.displayName
        val audioStr = preferredAudioCodec.displayName
        val ptStr = if (passthroughEnabled) "Включён" else "Выключен"

        return "Уровень устройства: ${tier.displayNameRu}\n" +
                "Качество: $qualityStr\n" +
                "Видеокодек: $videoStr\n" +
                "Аудиокодек: $audioStr\n" +
                "Режим: ${policyMode.titleRu}\n" +
                "Passthrough: $ptStr"
    }
}

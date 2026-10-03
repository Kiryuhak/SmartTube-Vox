package com.liskovsoft.smartyoutubetv2.common.vox.capability

/**
 * Описание обнаруженного риска при ручной настройке параметров.
 */
data class VoxCompatibilityRisk(
    val titleRu: String,
    val messageRu: String,
    val affectedSetting: String
)

/**
 * Оценщик рисков ручной перенастройки параметров совместимости.
 * Предупреждает пользователя, если выбранные настройки могут привести к тормозам,
 * пропуску кадров или отсутствию звука на данном устройстве.
 */
object VoxCompatibilityRiskEvaluator {

    const val DEFAULT_WARNING_MESSAGE = "Выбранные параметры выше рекомендуемых для этого устройства. " +
            "Это может увеличить нагрузку, вызвать задержки или снизить стабильность воспроизведения."

    @JvmStatic
    fun evaluate(
        proposedPolicy: VoxCodecPolicy,
        recommended: VoxRecommendedSettings,
        profile: VoxDeviceProfile
    ): VoxCompatibilityRisk? {
        // 1. Проверка разрешения: если выбрано разрешение выше рекомендуемого или выше возможностей экрана
        if (proposedPolicy.maxQualityHeight > 0 && proposedPolicy.maxQualityHeight > recommended.maxQualityHeight) {
            return VoxCompatibilityRisk(
                titleRu = "Повышенное разрешение",
                messageRu = "Разрешение ${proposedPolicy.maxQualityHeight}p превышает рекомендуемое для этого экрана/процессора (${recommended.maxQualityHeight}p). Возможны подтормаживания видео.",
                affectedSetting = "maxQuality"
            )
        }

        // 2. Проверка видеокодека: если выбран конкретный кодек, не поддерживаемый устройством
        val videoCodec = proposedPolicy.preferredVideoCodec
        if (videoCodec != VoxVideoCodecPreference.AUTO) {
            val codecId = videoCodec.id
            if (!profile.isVideoCodecSupported(codecId)) {
                return VoxCompatibilityRisk(
                    titleRu = "Неподдерживаемый видеокодек",
                    messageRu = "Видеокодек ${videoCodec.displayName} не поддерживается аппаратно вашим устройством. Воспроизведение может зависать или переключаться на программный декодер.",
                    affectedSetting = "videoCodec"
                )
            }
        }

        // 3. Проверка аудиокодека: если выбран кодек, не поддерживаемый ни декодером, ни passthrough
        val audioCodec = proposedPolicy.preferredAudioCodec
        if (audioCodec != VoxAudioCodecPreference.AUTO) {
            val codecId = audioCodec.id
            val dec = profile.isAudioDecodeSupported(codecId)
            val pt = profile.isAudioPassthroughSupported(codecId)
            if (!dec && !pt) {
                return VoxCompatibilityRisk(
                    titleRu = "Неподдерживаемый аудиокодек",
                    messageRu = "Аудиокодек ${audioCodec.displayName} не поддерживается телевизором или ресивером. Звук может отсутствовать.",
                    affectedSetting = "audioCodec"
                )
            }
        }

        // 4. Проверка Passthrough: если включен при неподдерживаемом выводе
        if (proposedPolicy.passthroughEnabled && profile.audioOutput.passthrough == TriStateCapability.UNSUPPORTED) {
            return VoxCompatibilityRisk(
                titleRu = "Passthrough не поддерживается",
                messageRu = "Сквозной вывод звука (Passthrough) не поддерживается вашей аудиосистемой.",
                affectedSetting = "passthrough"
            )
        }

        return null
    }
}

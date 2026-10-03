package com.liskovsoft.smartyoutubetv2.common.vox.capability

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData

/**
 * Менеджер совместимости устройств и политик кодеков SmartTube VOX.
 */
class VoxCompatibilityManager private constructor(private val context: Context) {

    private val appContext = context.applicationContext
    private val votData = VotData.instance(appContext)
    private var cachedProfile: VoxDeviceProfile? = null

    @Synchronized
    fun getDeviceProfile(forceRescan: Boolean = false): VoxDeviceProfile {
        if (!forceRescan && cachedProfile != null) {
            return cachedProfile!!
        }

        if (!forceRescan) {
            val cachedJson = votData.cachedDeviceProfile
            if (!cachedJson.isNullOrBlank()) {
                val profile = VoxDeviceProfile.fromJson(cachedJson)
                if (profile != null) {
                    cachedProfile = profile
                    return profile
                }
            }
        }

        val provider = AndroidVoxCapabilityProvider(appContext)
        val freshProfile = provider.scanDeviceCapabilities()
        cachedProfile = freshProfile
        votData.cachedDeviceProfile = freshProfile.toJson().toString()
        return freshProfile
    }

    fun getCodecPolicy(): VoxCodecPolicy {
        val mode = VoxCodecPolicyMode.fromId(votData.codecPolicyMode)
        val maxQuality = votData.maxQualityHeight
        val videoCodec = VoxVideoCodecPreference.fromId(votData.preferredVideoCodec)
        val audioCodec = VoxAudioCodecPreference.fromId(votData.preferredAudioCodec)
        val passthrough = votData.isPassthroughEnabled

        return VoxCodecPolicy(
            mode = mode,
            maxQualityHeight = maxQuality,
            preferredVideoCodec = videoCodec,
            preferredAudioCodec = audioCodec,
            passthroughEnabled = passthrough
        )
    }

    fun setCodecPolicy(policy: VoxCodecPolicy) {
        votData.codecPolicyMode = policy.mode.id
        votData.maxQualityHeight = policy.maxQualityHeight
        votData.preferredVideoCodec = policy.preferredVideoCodec.id
        votData.preferredAudioCodec = policy.preferredAudioCodec.id
        votData.isPassthroughEnabled = policy.passthroughEnabled
    }

    fun isScanCompleted(): Boolean {
        return votData.isCompatibilityScanCompleted
    }

    fun setScanCompleted(completed: Boolean) {
        votData.isCompatibilityScanCompleted = completed
    }

    fun resetToDefaults() {
        votData.clearCachedDeviceProfile()
        cachedProfile = null
        votData.codecPolicyMode = VoxCodecPolicyMode.AUTO.id
        votData.maxQualityHeight = 0
        votData.preferredVideoCodec = VoxVideoCodecPreference.AUTO.id
        votData.preferredAudioCodec = VoxAudioCodecPreference.AUTO.id
        votData.isPassthroughEnabled = true
        votData.isCompatibilityScanCompleted = false
    }

    /**
     * Формирует безопасный текстовый отчёт диагностики (без токенов, паролей, cookies).
     */
    fun generateSafeDiagnosticReport(): String {
        val profile = getDeviceProfile(false)
        val policy = getCodecPolicy()

        val sb = StringBuilder()
        sb.append("=== SmartTube VOX — Диагностика совместимости ===\n\n")

        sb.append("Платформа: ").append(profile.platform.displayName).append("\n")
        sb.append("Производитель: ").append(profile.manufacturer).append("\n")
        sb.append("Модель: ").append(profile.model).append("\n")
        sb.append("Система: ").append(profile.osName).append(" ").append(profile.osVersion).append("\n\n")

        sb.append("--- ВИДЕОДЕКОДЕРЫ ---\n")
        val videoCodecs = listOf("avc", "vp9", "av1", "hevc")
        for (c in videoCodecs) {
            val cap = profile.videoCodecs[c]
            val status = cap?.capability?.labelRu ?: TriStateCapability.UNKNOWN.labelRu
            val hw = if (cap?.hardwareAccelerated == true) " [HW]" else ""
            val res = if (cap != null && cap.maxWidth > 0 && cap.maxHeight > 0) " (макс. ${cap.maxWidth}x${cap.maxHeight} @ ${cap.maxFps}fps)" else ""
            sb.append("${c.uppercase()}: $status$hw$res\n")
        }
        sb.append("\n")

        sb.append("--- АУДИОДЕКОДЕРЫ И PASSTHROUGH ---\n")
        val audioCodecs = listOf("aac", "opus", "ac3", "eac3")
        for (c in audioCodecs) {
            val cap = profile.audioCodecs[c]
            val dec = cap?.decodeCapability?.labelRu ?: TriStateCapability.UNKNOWN.labelRu
            val pt = cap?.passthroughCapability?.labelRu ?: TriStateCapability.UNKNOWN.labelRu
            val note = if (cap?.decodeCapability != TriStateCapability.SUPPORTED && cap?.passthroughCapability == TriStateCapability.SUPPORTED) {
                " (Только passthrough)"
            } else ""
            sb.append("${c.uppercase()}: Декод: $dec, Passthrough: $pt$note\n")
        }
        sb.append("\n")

        sb.append("--- ЭКРАН И HDR ---\n")
        sb.append("Разрешение: ${profile.display.maxWidth}x${profile.display.maxHeight} @ ${profile.display.maxFps}Hz\n")
        sb.append("HDR10: ${profile.display.hdr10.labelRu}\n")
        sb.append("HLG: ${profile.display.hlg.labelRu}\n")
        sb.append("HDR10+: ${profile.display.hdr10Plus.labelRu}\n")
        sb.append("Dolby Vision: ${profile.display.dolbyVision.labelRu}\n\n")

        sb.append("--- ТЕКУЩАЯ ПОЛИТИКА VOX ---\n")
        sb.append("Режим: ${policy.mode.titleRu}\n")
        sb.append("Макс. разрешение: ${if (policy.maxQualityHeight > 0) "${policy.maxQualityHeight}p" else "Авто"}\n")
        sb.append("Предпочитаемый видеокодек: ${policy.preferredVideoCodec.displayName}\n")
        sb.append("Предпочитаемый аудиокодек: ${policy.preferredAudioCodec.displayName}\n")
        sb.append("Passthrough: ${if (policy.passthroughEnabled) "Включён" else "Выключен"}\n\n")

        val recVideo = policy.selectVideoCodec(listOf("av1", "vp9", "avc"), profile)
        val recAudio = policy.selectAudioCodec(listOf("eac3", "ac3", "opus", "aac"), profile)
        sb.append("--- РЕКОМЕНДАЦИЯ VOX ДЛЯ УСТРОЙСТВА ---\n")
        sb.append("Видеокодек: ${recVideo.selected?.uppercase() ?: "AUTO"}\n")
        sb.append("Аудиокодек: ${recAudio.selected?.uppercase() ?: "AUTO"}\n")
        sb.append("Резервный аудиокодек: AAC\n")

        return sb.toString()
    }

    companion object {
        private const val TAG = "VoxCompatibilityManager"

        @Volatile
        private var instance: VoxCompatibilityManager? = null

        @JvmStatic
        fun instance(context: Context): VoxCompatibilityManager {
            return instance ?: synchronized(this) {
                instance ?: VoxCompatibilityManager(context).also { instance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        @JvmStatic
        fun resetForTesting() {
            instance = null
        }
    }
}

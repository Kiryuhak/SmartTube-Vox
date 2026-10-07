package com.liskovsoft.smartyoutubetv2.common.vox.capability

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData

enum class VoxApplyStatus {
    APPLIED,
    PARTIAL,
    FAILED
}

data class VoxApplyResult(
    val status: VoxApplyStatus,
    val previousPolicy: VoxCodecPolicy,
    val appliedPolicy: VoxCodecPolicy
)

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

    fun getRecommendedSettings(forceRescan: Boolean = false): VoxRecommendedSettings {
        val profile = getDeviceProfile(forceRescan)
        val tuner = VoxDeviceAutoTuner(appContext)
        return tuner.tune(profile)
    }

    fun applyRecommendedSettings(recommended: VoxRecommendedSettings): VoxApplyResult {
        val previous = getCodecPolicy()
        val target = recommended.toCodecPolicy()
        return try {
            setCodecPolicy(target)
            setScanCompleted(true)
            votData.isManualOverride = false
            votData.lastAppliedRecommendedProfile = recommended.tier.name
            VoxApplyResult(VoxApplyStatus.APPLIED, previous, target)
        } catch (e: Exception) {
            VoxApplyResult(VoxApplyStatus.FAILED, previous, previous)
        }
    }

    fun restoreRecommendedSettings(): VoxApplyResult {
        val recommended = getRecommendedSettings(false)
        return applyRecommendedSettings(recommended)
    }

    fun isManualOverrideActive(): Boolean {
        return getCodecPolicy().mode == VoxCodecPolicyMode.CUSTOM || votData.isManualOverride
    }

    fun markManualOverride(isOverride: Boolean) {
        votData.isManualOverride = isOverride
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

    private val healthTracker: VoxPlaybackHealthTracker by lazy { VoxPlaybackHealthTracker(votData) }

    fun getPlaybackHealthTracker(): VoxPlaybackHealthTracker = healthTracker

    fun recordPlaybackSession(observation: VoxPlaybackSessionObservation) {
        healthTracker.recordSession(observation)
    }

    fun getAutoSetupRecommendation(): VoxAutoSetupRecommendation {
        val profile = getDeviceProfile(false)
        val policy = getCodecPolicy()
        return healthTracker.getRecommendation(profile, policy, isManualOverrideActive())
    }

    fun resetAutoSetupData() {
        healthTracker.reset()
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
        healthTracker.reset()
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
        val recommended = getRecommendedSettings(false)

        val sb = StringBuilder()
        sb.append("=== SmartTube VOX — Диагностика совместимости ===\n\n")

        sb.append("Платформа: ").append(profile.platform.displayName).append("\n")
        sb.append("Производитель: ").append(profile.manufacturer).append("\n")
        sb.append("Модель: ").append(profile.model).append("\n")
        sb.append("Система: ").append(profile.osName).append(" ").append(profile.osVersion).append("\n")
        sb.append("Уровень устройства: ").append(recommended.tier.displayNameRu).append(" (").append(recommended.tier.descriptionRu).append(")\n\n")

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

        sb.append("--- РЕКОМЕНДУЕМЫЕ НАСТРОЙКИ VOX ---\n")
        sb.append("Режим: ${recommended.policyMode.titleRu}\n")
        sb.append("Разрешение: ${if (recommended.maxQualityHeight > 0) "${recommended.maxQualityHeight}p" else "Авто"}\n")
        sb.append("Видеокодек: ${recommended.preferredVideoCodec.displayName}\n")
        sb.append("Аудиокодек: ${recommended.preferredAudioCodec.displayName}\n")
        sb.append("Passthrough: ${if (recommended.passthroughEnabled) "Включён" else "Выключен"}\n\n")

        sb.append("--- АКТИВНЫЕ НАСТРОЙКИ ---\n")
        sb.append("Режим: ${policy.mode.titleRu}\n")
        sb.append("Макс. разрешение: ${if (policy.maxQualityHeight > 0) "${policy.maxQualityHeight}p" else "Авто"}\n")
        sb.append("Предпочитаемый видеокодек: ${policy.preferredVideoCodec.displayName}\n")
        sb.append("Предпочитаемый аудиокодек: ${policy.preferredAudioCodec.displayName}\n")
        sb.append("Passthrough: ${if (policy.passthroughEnabled) "Включён" else "Выключен"}\n")

        val risk = VoxCompatibilityRiskEvaluator.evaluate(policy, recommended, profile)
        if (risk != null) {
            sb.append("\n⚠️ ВНИМАНИЕ: ${risk.messageRu}\n")
        } else {
            sb.append("\n✓ Параметры полностью согласованы с возможностями устройства.\n")
        }

        val summary = healthTracker.getHealthSummary()
        sb.append("\n--- ЗДОРОВЬЕ ВОСПРОИЗВЕДЕНИЯ (AUTO SETUP 2.0) ---\n")
        sb.append(summary.toDiagnosticString()).append("\n")

        val recommendation = getAutoSetupRecommendation()
        if (recommendation.reasonCodes.contains(VoxAutoSetupReasonCode.RUNTIME_4K_REBUFFER)) {
            sb.append("⚠️ Рекомендация: ${recommendation.userExplanationRu}\n")
        }

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

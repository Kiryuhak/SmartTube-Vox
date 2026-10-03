package com.liskovsoft.smartyoutubetv2.common.vox.capability

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Интеллектуальный автотюнер аппаратных возможностей устройства.
 * На основе реальных платформенных сигналов (MediaCodec, RAM, CPU, Display) вычисляет
 * рекомендуемый профиль настроек для максимальной плавности и надежности.
 */
class VoxDeviceAutoTuner(private val context: Context? = null) {

    /**
     * Вычисляет рекомендуемые параметры для профиля устройства.
     */
    fun tune(profile: VoxDeviceProfile): VoxRecommendedSettings {
        val rationale = mutableListOf<String>()

        val isLowRam = checkLowRam()
        val totalRamMb = getApproxTotalRamMb()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val displayHeight = profile.display.maxHeight

        val av1Cap = profile.videoCodecs["av1"]
        val vp9Cap = profile.videoCodecs["vp9"]
        val avcCap = profile.videoCodecs["avc"]

        val hasHw4kAv1 = av1Cap?.hardwareAccelerated == true && av1Cap.maxHeight >= 2160
        val hasHw4kVp9 = vp9Cap?.hardwareAccelerated == true && vp9Cap.maxHeight >= 2160
        val hasHw4kAvc = avcCap?.hardwareAccelerated == true && avcCap.maxHeight >= 2160
        val has4kDecode = hasHw4kAv1 || hasHw4kVp9 || hasHw4kAvc

        val tier: VoxPerformanceTier
        val maxQuality: Int
        val preferredVideo: VoxVideoCodecPreference
        val preferredAudio: VoxAudioCodecPreference
        val passthrough: Boolean

        if (isLowRam || (totalRamMb in 1..1800) || displayHeight < 1080 || !profile.isVideoCodecSupported("vp9")) {
            tier = VoxPerformanceTier.BASIC
            maxQuality = if (displayHeight in 1..1079) displayHeight else 1080
            preferredVideo = if (profile.isVideoCodecSupported("vp9")) {
                VoxVideoCodecPreference.VP9
            } else {
                VoxVideoCodecPreference.AVC
            }
            preferredAudio = VoxAudioCodecPreference.AUTO
            passthrough = profile.audioOutput.passthrough == TriStateCapability.SUPPORTED
            rationale.add("Ограниченный объём памяти или базовый видеодекодер: ограничение разрешения до ${maxQuality}p")
        } else if (has4kDecode && totalRamMb >= 2800 && cpuCores >= 4 && displayHeight >= 2160) {
            tier = VoxPerformanceTier.POWERFUL
            maxQuality = 2160
            preferredVideo = if (hasHw4kAv1) {
                VoxVideoCodecPreference.AV1
            } else if (hasHw4kVp9) {
                VoxVideoCodecPreference.VP9
            } else {
                VoxVideoCodecPreference.AUTO
            }
            preferredAudio = if (profile.isAudioDecodeSupported("eac3") || profile.isAudioPassthroughSupported("eac3")) {
                VoxAudioCodecPreference.EAC3
            } else {
                VoxAudioCodecPreference.AUTO
            }
            passthrough = true
            rationale.add("Флагманский 4K экран и аппаратные декодеры: режим максимального качества 4K")
        } else {
            tier = VoxPerformanceTier.STANDARD
            maxQuality = if (displayHeight >= 2160 && has4kDecode) 2160 else 1080
            preferredVideo = if (vp9Cap?.hardwareAccelerated == true) {
                VoxVideoCodecPreference.VP9
            } else {
                VoxVideoCodecPreference.AUTO
            }
            preferredAudio = if (profile.isAudioDecodeSupported("ac3") || profile.isAudioPassthroughSupported("ac3")) {
                VoxAudioCodecPreference.AC3
            } else {
                VoxAudioCodecPreference.AUTO
            }
            passthrough = true
            rationale.add("Стандартная конфигурация ТВ: автоматический баланс качества и совместимости")
        }

        return VoxRecommendedSettings(
            tier = tier,
            policyMode = VoxCodecPolicyMode.AUTO,
            maxQualityHeight = maxQuality,
            preferredVideoCodec = preferredVideo,
            preferredAudioCodec = preferredAudio,
            passthroughEnabled = passthrough,
            rationale = rationale
        )
    }

    private fun checkLowRam(): Boolean {
        if (context == null) return false
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice ?: false
        } catch (ignored: Exception) {
            false
        }
    }

    private fun getApproxTotalRamMb(): Long {
        if (context == null) return 0L
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem / (1024 * 1024)
        } catch (ignored: Exception) {
            0L
        }
    }
}

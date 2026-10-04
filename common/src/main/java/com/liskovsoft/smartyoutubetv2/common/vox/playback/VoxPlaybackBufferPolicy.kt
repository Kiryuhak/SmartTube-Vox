package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.content.Context
import com.liskovsoft.sharedutils.helpers.DeviceHelpers
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPerformanceTier

/**
 * Семантический профиль буферизации.
 */
enum class VoxBufferProfile(val id: String, val titleRu: String) {
    CONSERVATIVE("conservative", "Экономный (для слабых устройств и прямого эфира)"),
    BALANCED("balanced", "Сбалансированный"),
    HIGH_PERFORMANCE("high_performance", "Максимальная производительность");
}

/**
 * Конфигурация параметров LoadControl для ExoPlayer.
 */
data class VoxBufferConfiguration(
    val profile: VoxBufferProfile,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferMs: Int,
    val targetBufferBytes: Int
)

/**
 * Политика адаптивной буферизации воспроизведения SmartTube VOX.
 *
 * Учитывает:
 * - Аппаратный класс устройства (RAM, ядра CPU, производительность SoC);
 * - Тип контента (Live-стрим, VOD из сети, локальный скачанный MKV-файл);
 * - Разрешение и ресурсоёмкость видеопотока.
 */
object VoxPlaybackBufferPolicy {

    private const val DEFAULT_TARGET_BUFFER_BYTES = 48 * 1024 * 1024 // 48 MB
    private const val MAX_TARGET_BUFFER_BYTES = 128 * 1024 * 1024   // 128 MB
    private const val MIN_TARGET_BUFFER_BYTES = 20 * 1024 * 1024    // 20 MB

    @JvmStatic
    @JvmOverloads
    fun resolve(
        context: Context,
        isLive: Boolean = false,
        isLocal: Boolean = false,
        videoHeight: Int = 0
    ): VoxBufferConfiguration {
        val ram = DeviceHelpers.getDeviceRam(context)
        val tier = try {
            VoxCompatibilityManager.instance(context).getRecommendedSettings().tier
        } catch (e: Exception) {
            VoxPerformanceTier.STANDARD
        }
        return resolve(tier, ram, isLive, isLocal, videoHeight)
    }

    @JvmStatic
    @JvmOverloads
    fun resolve(
        tier: VoxPerformanceTier,
        ramBytes: Long,
        isLive: Boolean = false,
        isLocal: Boolean = false,
        videoHeight: Int = 0
    ): VoxBufferConfiguration {
        val safeRam = if (ramBytes <= 0L) 2L * 1024 * 1024 * 1024 else ramBytes
        val ramMb = safeRam / (1024 * 1024)

        // 1. Прямой эфир (Live stream): минимизация задержки, исключение раздувания буфера
        if (isLive) {
            return VoxBufferConfiguration(
                profile = VoxBufferProfile.CONSERVATIVE,
                minBufferMs = 6_000,
                maxBufferMs = 12_000,
                bufferForPlaybackMs = 1_500,
                bufferForPlaybackAfterRebufferMs = 2_500,
                backBufferMs = 0,
                targetBufferBytes = MIN_TARGET_BUFFER_BYTES
            )
        }

        // 2. Локальное офлайн-воспроизведение: сверхбыстрый старт с диска, скромный буфер
        if (isLocal) {
            val localTargetBytes = (ramBytes / 32).toInt().coerceIn(MIN_TARGET_BUFFER_BYTES, 32 * 1024 * 1024)
            return VoxBufferConfiguration(
                profile = VoxBufferProfile.BALANCED,
                minBufferMs = 8_000,
                maxBufferMs = 15_000,
                bufferForPlaybackMs = 800,
                bufferForPlaybackAfterRebufferMs = 1_500,
                backBufferMs = 10_000,
                targetBufferBytes = localTargetBytes
            )
        }

        // 3. Сетевой VOD: разделение по производительности и объему оперативной памяти
        return when {
            // Флагманские ТВ-боксы (RAM >= 3.5GB, POWERFUL Tier, 4K/2K)
            tier == VoxPerformanceTier.POWERFUL || (ramMb >= 3500 && videoHeight >= 1440) -> {
                val targetBytes = (safeRam / 16).toInt().coerceIn(48 * 1024 * 1024, MAX_TARGET_BUFFER_BYTES)
                VoxBufferConfiguration(
                    profile = VoxBufferProfile.HIGH_PERFORMANCE,
                    minBufferMs = 35_000,
                    maxBufferMs = 60_000,
                    bufferForPlaybackMs = 2_000,
                    bufferForPlaybackAfterRebufferMs = 4_000,
                    backBufferMs = 30_000,
                    targetBufferBytes = targetBytes
                )
            }
            // Слабые / бюджетные ТВ-приставки (RAM < 1.5GB или BASIC Tier)
            tier == VoxPerformanceTier.BASIC || ramMb < 1500 -> {
                val targetBytes = (safeRam / 24).toInt().coerceIn(MIN_TARGET_BUFFER_BYTES, 32 * 1024 * 1024)
                VoxBufferConfiguration(
                    profile = VoxBufferProfile.CONSERVATIVE,
                    minBufferMs = 15_000,
                    maxBufferMs = 25_000,
                    bufferForPlaybackMs = 2_000,
                    bufferForPlaybackAfterRebufferMs = 3_500,
                    backBufferMs = 5_000,
                    targetBufferBytes = targetBytes
                )
            }
            // Стандартный сбалансированный профиль (RAM 1.5 - 3.5GB, STANDARD Tier / 1080p)
            else -> {
                val targetBytes = (safeRam / 20).toInt().coerceIn(MIN_TARGET_BUFFER_BYTES, 64 * 1024 * 1024)
                VoxBufferConfiguration(
                    profile = VoxBufferProfile.BALANCED,
                    minBufferMs = 25_000,
                    maxBufferMs = 40_000,
                    bufferForPlaybackMs = 2_500,
                    bufferForPlaybackAfterRebufferMs = 4_500,
                    backBufferMs = 15_000,
                    targetBufferBytes = targetBytes
                )
            }
        }
    }
}

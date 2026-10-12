package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.content.Context
import com.liskovsoft.sharedutils.helpers.DeviceHelpers
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPerformanceTier
import java.util.ArrayDeque

/**
 * Семантический профиль буферизации.
 */
enum class VoxBufferProfile(val id: String, val titleRu: String) {
    CONSERVATIVE("conservative", "Экономный (для слабых устройств и прямого эфира)"),
    BALANCED("balanced", "Сбалансированный"),
    HIGH_PERFORMANCE("high_performance", "Максимальная производительность");
}

/**
 * Классификация коренной причины буферизации/остановки воспроизведения.
 */
enum class VoxBufferRootCause(val titleRu: String) {
    NETWORK_LIMITED("Ограничение пропускной способности сети"),
    BITRATE_TOO_HIGH("Битрейт потока превышает стабильную скорость соединения"),
    BUFFER_POLICY_TOO_SMALL("Размер буфера недостаточен для битрейта потока"),
    DECODER_LIMITED("Аппаратный декодер устройства не справляется с декодированием"),
    CDN_THROTTLED("Искусственное ограничение скорости со стороны CDN"),
    UNKNOWN("Неопределенная причина буферизации");
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
 * Политика адаптивной буферизации воспроизведения SmartTube VOX (Patch #35 Hardened).
 *
 * Учитывает:
 * - Аппаратный класс устройства (RAM, ядра CPU, производительность SoC);
 * - Тип контента (Live-стрим, VOD из сети, локальный скачанный MKV-файл);
 * - Разрешение и ресурсоёмкость видеопотока (включая 4K VP9 45 Mbps);
 * - Анализ причин буферизации (Network, Bitrate, Buffer Policy, Decoder, CDN Throttling);
 * - Адаптивное масштабирование буфера с гистерезисом.
 */
object VoxPlaybackBufferPolicy {

    private const val DEFAULT_TARGET_BUFFER_BYTES = 48 * 1024 * 1024 // 48 MB
    private const val MAX_TARGET_BUFFER_BYTES = 128 * 1024 * 1024   // 128 MB
    private const val MIN_TARGET_BUFFER_BYTES = 20 * 1024 * 1024    // 20 MB

    const val LIVE_TARGET_OFFSET_MS = 15_000L
    const val LIVE_MIN_BUFFER_MS = 15_000
    const val LIVE_MAX_BUFFER_MS = 30_000
    const val LIVE_BUFFER_FOR_PLAYBACK_MS = 2_000
    const val LIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000
    const val LIVE_TARGET_BUFFER_BYTES = 32 * 1024 * 1024

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

        // 1. Прямой эфир (Live stream): сбалансированная буферизация для исключения периодических заиканий (Patch #4)
        if (isLive) {
            return VoxBufferConfiguration(
                profile = VoxBufferProfile.BALANCED,
                minBufferMs = LIVE_MIN_BUFFER_MS,
                maxBufferMs = LIVE_MAX_BUFFER_MS,
                bufferForPlaybackMs = LIVE_BUFFER_FOR_PLAYBACK_MS,
                bufferForPlaybackAfterRebufferMs = LIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                backBufferMs = 0,
                targetBufferBytes = LIVE_TARGET_BUFFER_BYTES
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

    /**
     * Адаптивный расчет параметров буферизации для 4K и потоков с высоким битрейтом (Patch #35).
     */
    @JvmStatic
    @JvmOverloads
    fun resolveAdaptive(
        context: Context,
        isLive: Boolean = false,
        isLocal: Boolean = false,
        videoHeight: Int = 0,
        bitrate: Long = 0L,
        rebufferCount: Int = 0
    ): VoxBufferConfiguration {
        val ram = DeviceHelpers.getDeviceRam(context)
        val tier = try {
            VoxCompatibilityManager.instance(context).getRecommendedSettings().tier
        } catch (e: Exception) {
            VoxPerformanceTier.STANDARD
        }
        return resolveAdaptive(tier, ram, isLive, isLocal, videoHeight, bitrate, rebufferCount)
    }

    @JvmStatic
    @JvmOverloads
    fun resolveAdaptive(
        tier: VoxPerformanceTier,
        ramBytes: Long,
        isLive: Boolean = false,
        isLocal: Boolean = false,
        videoHeight: Int = 0,
        bitrate: Long = 0L,
        rebufferCount: Int = 0
    ): VoxBufferConfiguration {
        val baseConfig = resolve(tier, ramBytes, isLive, isLocal, videoHeight)
        val is4K = videoHeight >= 2160 || bitrate >= 25_000_000L

        if (isLive || isLocal || !is4K) {
            return baseConfig
        }

        // Для 4K / High Bitrate VOD (например, VP9 45 Mbps):
        // Масштабируем буфер вверх с учетом гистерезиса при ребуферах
        val safeRam = if (ramBytes <= 0L) 2L * 1024 * 1024 * 1024 else ramBytes
        val boost = if (rebufferCount >= 2) 1.5f else 1.0f

        val adaptiveMinBufferMs = (35_000 * boost).toInt().coerceIn(35_000, 60_000)
        val adaptiveMaxBufferMs = (60_000 * boost).toInt().coerceIn(60_000, 100_000)
        val adaptiveBufferForPlaybackMs = if (rebufferCount >= 1) 3_500 else 2_000
        val adaptiveBufferAfterRebufferMs = if (rebufferCount >= 2) 6_000 else 4_500

        // Рассчитываем targetBufferBytes: при 45 Mbps 25 секунд видео = ~140 MB.
        // Ограничиваем потолком RAM устройства (до 128 MB)
        val maxAllowedBytes = if (safeRam >= 3L * 1024 * 1024 * 1024) MAX_TARGET_BUFFER_BYTES else 96 * 1024 * 1024
        val neededBytes = if (bitrate > 0) (bitrate / 8 * 25).toInt() else 64 * 1024 * 1024
        val targetBytes = neededBytes.coerceIn(64 * 1024 * 1024, maxAllowedBytes)

        return VoxBufferConfiguration(
            profile = VoxBufferProfile.HIGH_PERFORMANCE,
            minBufferMs = adaptiveMinBufferMs,
            maxBufferMs = adaptiveMaxBufferMs,
            bufferForPlaybackMs = adaptiveBufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs = adaptiveBufferAfterRebufferMs,
            backBufferMs = 30_000,
            targetBufferBytes = targetBytes
        )
    }

    /**
     * Детерминированный классификатор причин буферизации видеопотока.
     */
    @JvmStatic
    @JvmOverloads
    fun classifyRebuffer(
        bitrate: Long,
        bufferAheadMs: Long,
        measuredBandwidthBps: Long = 0L,
        droppedFrames: Int = 0,
        is4K: Boolean = false,
        targetBufferBytes: Int = 0
    ): VoxBufferRootCause {
        val bitrateMbps = if (bitrate > 0) bitrate / 1_000_000.0 else 0.0
        val measuredMbps = if (measuredBandwidthBps > 0) measuredBandwidthBps / 1_000_000.0 else 0.0

        return when {
            droppedFrames > 60 -> VoxBufferRootCause.DECODER_LIMITED
            // Если заданный лимит буфера в байтах вмещает менее 10-12 секунд видео при данном битрейте
            targetBufferBytes > 0 && bitrate > 0 && (targetBufferBytes.toLong() * 8_000L / bitrate) < 12_000L ->
                VoxBufferRootCause.BUFFER_POLICY_TOO_SMALL
            measuredMbps in 0.001..2.0 ->
                VoxBufferRootCause.CDN_THROTTLED
            measuredMbps > 0 && measuredMbps < (bitrateMbps * 0.8) ->
                VoxBufferRootCause.NETWORK_LIMITED
            bitrateMbps >= 35.0 && measuredMbps > 0 && measuredMbps < bitrateMbps * 1.2 ->
                VoxBufferRootCause.BITRATE_TOO_HIGH
            bufferAheadMs <= 500 && (bitrateMbps >= 25.0 || is4K) ->
                VoxBufferRootCause.BUFFER_POLICY_TOO_SMALL
            bitrateMbps >= 40.0 ->
                VoxBufferRootCause.BITRATE_TOO_HIGH
            // Если измеренная скорость значительно превышает битрейт, сбой не связан с пропускной способностью
            measuredBandwidthBps > 0 && measuredMbps >= (bitrateMbps * 1.2) ->
                VoxBufferRootCause.UNKNOWN
            // Для умеренного битрейта (< 25 Mbps) при отсутствии измерений сети не ставим ложноположительный NETWORK_LIMITED
            measuredBandwidthBps <= 0L && bitrateMbps < 25.0 ->
                VoxBufferRootCause.UNKNOWN
            else -> VoxBufferRootCause.NETWORK_LIMITED
        }
    }
}

/**
 * Трекер адаптивной буферизации: скользящее среднее пропускной способности и гистерезис.
 */
class VoxAdaptiveBufferTracker(
    private val windowSize: Int = 5,
    private val hysteresisThreshold: Double = 0.2
) {
    private val samples = ArrayDeque<Double>()
    private var rebufferCount = 0

    fun addSample(mbps: Double) {
        if (mbps > 0) {
            samples.addLast(mbps)
            if (samples.size > windowSize) samples.removeFirst()
        }
    }

    val movingAverageMbps: Double
        get() = if (samples.isEmpty()) 0.0 else samples.average()

    fun onRebuffer(): Int {
        rebufferCount++
        return rebufferCount
    }

    fun shouldDemoteQuality(targetBitrateMbps: Double): Boolean {
        // Hysteresis: only suggest demoting quality after multiple bad windows
        val avg = movingAverageMbps
        return avg > 0 && rebufferCount >= 3 && avg < targetBitrateMbps * (1.0 - hysteresisThreshold)
    }

    fun reset() {
        samples.clear()
        rebufferCount = 0
    }
}

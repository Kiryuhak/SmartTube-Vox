package com.liskovsoft.smartyoutubetv2.common.vox.playback

import android.content.Context
import com.liskovsoft.sharedutils.helpers.DeviceHelpers
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPerformanceTier
import java.util.ArrayDeque
import java.util.UUID

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
enum class VoxBufferRootCause(val id: String, val titleRu: String) {
    HTTP_4XX_STALE_URL("HTTP_4XX_STALE_URL", "Устаревшая ссылка на сегмент потока (HTTP 4xx)"),
    HTTP_5XX("HTTP_5XX", "Ошибка сервера CDN (HTTP 5xx)"),
    DNS_FAILURE("DNS_FAILURE", "Ошибка разрешения DNS CDN"),
    TLS_FAILURE("TLS_FAILURE", "Ошибка TLS/SSL соединения CDN"),
    TIMEOUT("TIMEOUT", "Таймаут чтения сегмента потока"),
    DECODER_ERROR("DECODER_ERROR", "Сбой аппаратного декодера"),
    SEEK_OUTSIDE_WINDOW("SEEK_OUTSIDE_WINDOW", "Перемотка за пределы доступного буфера"),
    RESOURCE_PRESSURE("RESOURCE_PRESSURE", "Дефицит системных ресурсов или памяти"),
    INSUFFICIENT_BUFFER_OR_THROUGHPUT("INSUFFICIENT_BUFFER_OR_THROUGHPUT", "Пропускная способность сети недостаточна для битрейта"),
    NETWORK_LIMITED("NETWORK_LIMITED", "Ограничение пропускной способности сети"),
    BITRATE_TOO_HIGH("BITRATE_TOO_HIGH", "Битрейт потока превышает стабильную скорость соединения"),
    BUFFER_POLICY_TOO_SMALL("BUFFER_POLICY_TOO_SMALL", "Размер буфера недостаточен для битрейта потока"),
    DECODER_LIMITED("DECODER_LIMITED", "Аппаратный декодер устройства не справляется с декодированием"),
    CDN_THROTTLED("CDN_THROTTLED", "Искусственное ограничение скорости со стороны CDN"),
    UNKNOWN("UNKNOWN", "Неопределенная причина буферизации");

    constructor(titleRu: String) : this(titleRu, titleRu)

    companion object {
        @JvmField
        val INSUFFICIENT_THROUGHPUT = INSUFFICIENT_BUFFER_OR_THROUGHPUT
    }
}

/**
 * Детальная классификация инцидента буферизации с доказательствами и недостающими данными.
 */
data class RebufferClassification(
    val rootCause: VoxBufferRootCause,
    val evidenceMissing: String? = null,
    val detail: String? = null
)

/**
 * Неизменяемая/мутабельная модель инцидента буферизации SmartTube VOX (P0 Forensic Contract).
 *
 * Гарантии приватности:
 * - networkType содержит только "ETHERNET", "WIFI", "CELLULAR", "VPN", "OTHER" (никаких IP, SSID, BSSID);
 * - Никаких videoId, videoTitle, query params или токенов в контексте.
 */
data class RebufferIncident(
    val incidentId: String = UUID.randomUUID().toString().substring(0, 8),
    val startTimeMs: Long = System.currentTimeMillis(),
    var endTimeMs: Long? = null,
    val positionMs: Long = 0L,
    val bufferedDurationMs: Long = 0L,
    var rebufferDurationMs: Long = 0L,
    val selectedHeight: Int = 0,
    val codec: String? = null,
    val bitrate: Long = 0L,
    val networkEngine: String? = null,
    val mediaSourceType: String? = null,
    val isLive: Boolean = false,
    val playbackSessionId: String? = null,
    val operationId: String? = null,
    val networkType: String = "UNKNOWN",
    val decoderState: String? = null,
    val lastHttpStatus: Int? = null,
    val lastErrorClass: String? = null,
    val loadLatencyMs: Long? = null,
    val bytesLoaded: Long? = null,
    val throughputEstimateBps: Long? = null,
    var rootCause: VoxBufferRootCause = VoxBufferRootCause.UNKNOWN,
    var evidenceMissing: String? = null,
    var outcome: String = "IN_PROGRESS"
) {
    fun toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["incidentId"] = incidentId
        map["startTimeMs"] = startTimeMs.toString()
        endTimeMs?.let { map["endTimeMs"] = it.toString() }
        map["positionMs"] = positionMs.toString()
        map["bufferedMs"] = bufferedDurationMs.toString()
        map["bufferAheadMs"] = bufferedDurationMs.toString()
        if (rebufferDurationMs > 0) map["rebufferDurationMs"] = rebufferDurationMs.toString()
        if (selectedHeight > 0) map["selectedHeight"] = selectedHeight.toString()
        codec?.let { map["codec"] = it }
        if (bitrate > 0) map["bitrate"] = bitrate.toString()
        networkEngine?.let { map["networkEngine"] = it }
        mediaSourceType?.let { map["mediaSourceType"] = it }
        map["isLive"] = isLive.toString()
        playbackSessionId?.let { map["playbackSessionId"] = it }
        operationId?.let { map["operationId"] = it }
        map["networkType"] = networkType
        decoderState?.let { map["decoderState"] = it }
        lastHttpStatus?.let { map["lastHttpStatus"] = it.toString() }
        lastErrorClass?.let { map["lastErrorClass"] = it }
        loadLatencyMs?.let { map["loadLatencyMs"] = it.toString() }
        bytesLoaded?.let { map["bytesLoaded"] = it.toString() }
        throughputEstimateBps?.let { if (it > 0) map["throughputEstimateBps"] = it.toString() }
        map["rootCause"] = rootCause.name
        evidenceMissing?.let { map["evidenceMissing"] = it }
        map["outcome"] = outcome
        return map
    }
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
            // Флагманские ТВ-боксы (RAM >= 3.0GB, POWERFUL Tier, UGOOS SK1, 4K/2K)
            tier == VoxPerformanceTier.POWERFUL || ramMb >= 3000 || (ramMb >= 2500 && videoHeight >= 1440) -> {
                val targetBytes = (safeRam / 16).toInt().coerceIn(48 * 1024 * 1024, 96 * 1024 * 1024)
                VoxBufferConfiguration(
                    profile = VoxBufferProfile.HIGH_PERFORMANCE,
                    minBufferMs = 35_000,
                    maxBufferMs = 60_000,
                    bufferForPlaybackMs = 2_000,
                    bufferForPlaybackAfterRebufferMs = 5_000,
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
        // Понижен порог детекции до 15 Mbps для охвата 4K VP9 17.7-45.3 Mbps потоков (VOX-EF0F761F / VOX-0BF9F24D)
        val isHighBitrateOr4K = videoHeight >= 2160 || bitrate >= 15_000_000L

        if (isLive || isLocal || !isHighBitrateOr4K) {
            return baseConfig
        }

        // Для 4K / High Bitrate VOD (например, UGOOS SK1 VP9 45.3 Mbps):
        // Масштабируем буфер вверх с учетом гистерезиса при ребуферах и защитой от OOM
        val safeRam = if (ramBytes <= 0L) 2L * 1024 * 1024 * 1024 else ramBytes
        val boost = if (rebufferCount >= 2) 1.5f else 1.0f

        val adaptiveMinBufferMs = (35_000 * boost).toInt().coerceIn(35_000, 60_000)
        val adaptiveMaxBufferMs = (60_000 * boost).toInt().coerceIn(60_000, 100_000)
        val adaptiveBufferForPlaybackMs = if (rebufferCount >= 1) 3_500 else 2_000
        val adaptiveBufferAfterRebufferMs = if (rebufferCount >= 2) 6_000 else 5_000

        // Рассчитываем targetBufferBytes с жестким потолком 96 МБ во избежание OutOfMemoryError
        val maxAllowedBytes = 96 * 1024 * 1024
        val neededBytes = if (bitrate > 0) (bitrate / 8 * 20).toInt() else 64 * 1024 * 1024
        val targetBytes = neededBytes.coerceIn(48 * 1024 * 1024, maxAllowedBytes)

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
    fun getSafeNetworkType(context: Context?): String {
        if (context == null) return "UNKNOWN"
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "UNKNOWN"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return "NONE"
                val caps = cm.getNetworkCapabilities(network) ?: return "UNKNOWN"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
                    else -> "OTHER"
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                if (info == null || !info.isConnected) return "NONE"
                @Suppress("DEPRECATION")
                when (info.type) {
                    ConnectivityManager.TYPE_ETHERNET -> "ETHERNET"
                    ConnectivityManager.TYPE_WIFI -> "WIFI"
                    ConnectivityManager.TYPE_MOBILE -> "CELLULAR"
                    ConnectivityManager.TYPE_VPN -> "VPN"
                    else -> "OTHER"
                }
            }
        } catch (e: Throwable) {
            "UNKNOWN"
        }
    }

    /**
     * Детальный классификатор причин буферизации с поддержкой явных сетевых, серверных и декодерных доказательств.
     */
    @JvmStatic
    @JvmOverloads
    fun classifyRebufferDetailed(
        bitrate: Long,
        bufferAheadMs: Long,
        measuredBandwidthBps: Long = 0L,
        droppedFrames: Int = 0,
        is4K: Boolean = false,
        targetBufferBytes: Int = 0,
        isDecoderError: Boolean = false,
        lastHttpStatus: Int? = null,
        lastErrorClass: String? = null,
        isBehindLiveWindow: Boolean = false
    ): RebufferClassification {
        // 1. Аппаратная ошибка декодера
        if (isDecoderError) {
            return RebufferClassification(
                VoxBufferRootCause.DECODER_ERROR,
                null,
                "Decoder reported failure: ${lastErrorClass ?: "MediaCodec error"}"
            )
        }

        // 2. HTTP ошибки CDN/сервера
        if (lastHttpStatus != null) {
            if (lastHttpStatus in 400..499) {
                return RebufferClassification(
                    VoxBufferRootCause.HTTP_4XX_STALE_URL,
                    null,
                    "Stream segment returned HTTP $lastHttpStatus"
                )
            }
            if (lastHttpStatus in 500..599) {
                return RebufferClassification(
                    VoxBufferRootCause.HTTP_5XX,
                    null,
                    "CDN server returned HTTP $lastHttpStatus"
                )
            }
        }

        // 3. Сетевые сбои (DNS, TLS, Timeout)
        if (lastErrorClass != null) {
            val err = lastErrorClass.lowercase(java.util.Locale.ROOT)
            when {
                err.contains("dns") || err.contains("unknownhost") ->
                    return RebufferClassification(VoxBufferRootCause.DNS_FAILURE, null, lastErrorClass)
                err.contains("ssl") || err.contains("tls") || err.contains("handshake") || err.contains("cert") ->
                    return RebufferClassification(VoxBufferRootCause.TLS_FAILURE, null, lastErrorClass)
                err.contains("timeout") || err.contains("timedout") || err.contains("sockettimeout") ->
                    return RebufferClassification(VoxBufferRootCause.TIMEOUT, null, lastErrorClass)
            }
        }

        // 4. Позиционирование за пределы окна Live/DVR
        if (isBehindLiveWindow) {
            return RebufferClassification(
                VoxBufferRootCause.SEEK_OUTSIDE_WINDOW,
                null,
                "Behind live window or position discontinuity"
            )
        }

        // 5. Критические пропуски кадров декодера
        if (droppedFrames > 60) {
            return RebufferClassification(
                VoxBufferRootCause.DECODER_LIMITED,
                null,
                "Decoder dropped $droppedFrames frames"
            )
        }

        // 6. Недостаточный размер буфера (< 12 сек при текущем битрейте)
        if (targetBufferBytes > 0 && bitrate > 0 && (targetBufferBytes.toLong() * 8_000L / bitrate) < 12_000L) {
            return RebufferClassification(
                VoxBufferRootCause.BUFFER_POLICY_TOO_SMALL,
                null,
                "Target buffer ${targetBufferBytes / 1024 / 1024}MB capacity < 12s"
            )
        }

        val bitrateMbps = if (bitrate > 0) bitrate / 1_000_000.0 else 0.0
        val measuredMbps = if (measuredBandwidthBps > 0) measuredBandwidthBps / 1_000_000.0 else 0.0

        // 7. Искусственное ограничение скорости CDN (до 2.0 Мбит/с на тяжелом потоке)
        if (measuredMbps in 0.001..2.0 && (bitrateMbps > 3.0 || is4K)) {
            return RebufferClassification(
                VoxBufferRootCause.CDN_THROTTLED,
                null,
                "Throughput severely throttled by CDN ($measuredMbps Mbps)"
            )
        }

        // 8. Анализ измеренной скорости соединения относительно битрейта
        if (measuredBandwidthBps > 0L) {
            if (bitrateMbps > 0) {
                if (measuredMbps < (bitrateMbps * 0.8)) {
                    return RebufferClassification(
                        VoxBufferRootCause.INSUFFICIENT_BUFFER_OR_THROUGHPUT,
                        null,
                        "Throughput ($measuredMbps Mbps) < 80% bitrate ($bitrateMbps Mbps)"
                    )
                }
                if (bitrateMbps >= 35.0 && measuredMbps < (bitrateMbps * 1.2)) {
                    return RebufferClassification(
                        VoxBufferRootCause.BITRATE_TOO_HIGH,
                        null,
                        "Bitrate ($bitrateMbps Mbps) exceeds network headroom ($measuredMbps Mbps)"
                    )
                }
                if (measuredMbps >= (bitrateMbps * 1.2)) {
                    if (droppedFrames in 20..60) {
                        return RebufferClassification(
                            VoxBufferRootCause.DECODER_LIMITED,
                            null,
                            "Throughput healthy ($measuredMbps Mbps) but decoder struggling ($droppedFrames drops)"
                        )
                    }
                    return RebufferClassification(
                        VoxBufferRootCause.UNKNOWN,
                        null,
                        "Throughput and buffer healthy; cause undetermined"
                    )
                }
            }
        }

        // 9. Пропуски кадров без сетевых замеров
        if (droppedFrames in 20..60 && (bitrateMbps >= 35.0 || is4K)) {
            return RebufferClassification(
                VoxBufferRootCause.BITRATE_TOO_HIGH,
                if (measuredBandwidthBps <= 0L) "throughputEstimateBps" else null,
                "Decoder struggling on high bitrate ($droppedFrames dropped frames)"
            )
        }

        // 10. Опустошение буфера при агрессивной буферной политике
        if (bufferAheadMs <= 500 && (bitrateMbps in 25.0..39.9 || (is4K && bitrateMbps < 40.0))) {
            return RebufferClassification(
                VoxBufferRootCause.BUFFER_POLICY_TOO_SMALL,
                if (measuredBandwidthBps <= 0L) "throughputEstimateBps" else null,
                "Buffer starved to ${bufferAheadMs}ms under aggressive policy"
            )
        }

        // 11. Недостаток данных -> UNKNOWN с перечислением отсутствующих доказательств
        val missing = mutableListOf<String>()
        if (measuredBandwidthBps <= 0L) missing.add("throughputEstimateBps")
        if (bitrate <= 0L) missing.add("streamBitrate")
        if (droppedFrames == 0) missing.add("decoderStats")

        return RebufferClassification(
            VoxBufferRootCause.UNKNOWN,
            if (missing.isEmpty()) "Cause undetermined" else "Missing: ${missing.joinToString(", ")}",
            null
        )
    }

    /**
     * Детерминированный классификатор причин буферизации видеопотока (сохраняет обратную совместимость с тестами).
     */
    @JvmStatic
    @JvmOverloads
    fun classifyRebuffer(
        bitrate: Long,
        bufferAheadMs: Long,
        measuredBandwidthBps: Long = 0L,
        droppedFrames: Int = 0,
        is4K: Boolean = false,
        targetBufferBytes: Int = 0,
        isDecoderError: Boolean = false
    ): VoxBufferRootCause {
        val detail = classifyRebufferDetailed(
            bitrate = bitrate,
            bufferAheadMs = bufferAheadMs,
            measuredBandwidthBps = measuredBandwidthBps,
            droppedFrames = droppedFrames,
            is4K = is4K,
            targetBufferBytes = targetBufferBytes,
            isDecoderError = isDecoderError
        )
        return when (detail.rootCause) {
            VoxBufferRootCause.INSUFFICIENT_BUFFER_OR_THROUGHPUT -> VoxBufferRootCause.NETWORK_LIMITED
            VoxBufferRootCause.DECODER_ERROR -> VoxBufferRootCause.DECODER_LIMITED
            else -> detail.rootCause
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

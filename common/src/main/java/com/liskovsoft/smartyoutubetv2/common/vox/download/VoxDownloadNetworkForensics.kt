package com.liskovsoft.smartyoutubetv2.common.vox.download

import java.net.URI
import java.util.Locale

/**
 * Host Family classification for download targets.
 */
enum class VoxHostFamily(val label: String) {
    GOOGLEVIDEO("GOOGLEVIDEO"),
    YOUTUBE("YOUTUBE"),
    YANDEX("YANDEX"),
    OTHER("OTHER");

    companion object {
        fun fromHost(host: String): VoxHostFamily {
            val lower = host.lowercase(Locale.US)
            return when {
                lower.contains("googlevideo.com") -> GOOGLEVIDEO
                lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("ytimg.com") || lower.contains("ggpht.com") -> YOUTUBE
                lower.contains("yandex.") -> YANDEX
                else -> OTHER
            }
        }
    }
}

/**
 * Deterministic Download Bottleneck Classifications.
 */
enum class VoxDownloadBottleneck(val label: String) {
    REMOTE_THROTTLING("REMOTE_THROTTLING"),
    DNS_SLOW("DNS_SLOW"),
    CONNECT_SLOW("CONNECT_SLOW"),
    TLS_SLOW("TLS_SLOW"),
    TTFB_SLOW("TTFB_SLOW"),
    TRANSFER_SLOW("TRANSFER_SLOW"),
    LOCAL_DISK_SLOW("LOCAL_DISK_SLOW"),
    MUX_SLOW("MUX_SLOW"),
    MEDIASTORE_SLOW("MEDIASTORE_SLOW"),
    NETWORK_ENGINE_DEGRADED("NETWORK_ENGINE_DEGRADED"),
    UNKNOWN("UNKNOWN")
}

enum class VoxBottleneckConfidence(val label: String) {
    LOW("LOW"),
    MEDIUM("MEDIUM"),
    HIGH("HIGH")
}

data class VoxBottleneckAnalysis(
    val bottleneck: VoxDownloadBottleneck,
    val confidence: VoxBottleneckConfidence,
    val developerSummary: String
)

/**
 * Safe Host Sanitizer that guarantees NO query strings, signed tokens, signatures, or user PII leak into telemetry.
 */
object VoxHostSanitizer {

    @JvmStatic
    fun sanitizeHost(rawUrlOrHost: String?): String {
        if (rawUrlOrHost.isNullOrBlank()) return "unknown"
        val cleanInput = rawUrlOrHost.trim()
        val rawHost = try {
            if (cleanInput.startsWith("http://") || cleanInput.startsWith("https://")) {
                URI(cleanInput).host ?: extractHostFallback(cleanInput)
            } else {
                cleanInput.split("/")[0].split(":")[0]
            }
        } catch (e: Exception) {
            extractHostFallback(cleanInput)
        }

        if (rawHost.isNullOrBlank()) return "unknown"
        val lower = rawHost.lowercase(Locale.US)

        return when {
            lower.contains("googlevideo.com") -> {
                // Mask dynamic subdomains: e.g. r3---sn-xxxx.googlevideo.com -> *.googlevideo.com
                "*.googlevideo.com"
            }
            lower.contains("youtube.com") -> "*.youtube.com"
            lower.contains("yandex.net") -> "*.yandex.net"
            lower.contains("yandex.ru") -> "*.yandex.ru"
            else -> {
                // General sanitized domain (strip subdomains beyond 2 levels)
                val parts = lower.split(".")
                if (parts.size >= 2) {
                    "*." + parts.takeLast(2).joinToString(".")
                } else {
                    lower
                }
            }
        }
    }

    private fun extractHostFallback(url: String): String {
        val withoutScheme = url.replace(Regex("^https?://"), "")
        val hostPart = withoutScheme.split("/")[0].split("?")[0].split("#")[0]
        return hostPart.split(":")[0]
    }
}

/**
 * Structured, privacy-safe record of download network forensics.
 */
data class VoxDownloadNetworkForensics(
    val resourceType: String,
    val networkEngine: String = "OKHTTP",
    val safeHost: String,
    val hostFamily: VoxHostFamily,
    val routeHint: String = "DIRECT_UNKNOWN",
    val dnsMs: Long = 0L,
    val connectMs: Long = 0L,
    val tlsMs: Long = 0L,
    val ttfbMs: Long = 0L,
    val bytesDownloaded: Long = 0L,
    val elapsedMs: Long = 0L,
    val avgMbps: Double = 0.0,
    val peakMbps: Double = 0.0,
    val stallCount: Int = 0,
    val retryCount: Int = 0,
    val httpStatusClass: String = "2XX_SUCCESS",
    val rangeSupported: Boolean = true,
    val rangeUsed: Boolean = false,
    val parallelConnections: Int = 1,
    val contentLength: Long? = null,
    val serverThrottlingSuspicion: Boolean = false
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "resourceType" to resourceType,
            "networkEngine" to networkEngine,
            "safeHost" to safeHost,
            "hostFamily" to hostFamily.label,
            "routeHint" to routeHint,
            "dnsMs" to dnsMs,
            "connectMs" to connectMs,
            "tlsMs" to tlsMs,
            "ttfbMs" to ttfbMs,
            "bytesDownloaded" to bytesDownloaded,
            "elapsedMs" to elapsedMs,
            "avgMbps" to String.format(Locale.US, "%.2f", avgMbps),
            "peakMbps" to String.format(Locale.US, "%.2f", peakMbps),
            "stallCount" to stallCount,
            "retryCount" to retryCount,
            "httpStatusClass" to httpStatusClass,
            "rangeSupported" to rangeSupported,
            "rangeUsed" to rangeUsed,
            "parallelConnections" to parallelConnections,
            "serverThrottlingSuspicion" to serverThrottlingSuspicion
        )
        if (contentLength != null && contentLength > 0L) {
            map["contentLength"] = contentLength
        }
        return map
    }
}

/**
 * Deterministic Bottleneck Classifier for Download Performance.
 */
object VoxDownloadBottleneckClassifier {

    @JvmStatic
    fun classify(
        forensics: VoxDownloadNetworkForensics?,
        packagingElapsedMs: Long = 0L,
        finalizeElapsedMs: Long = 0L
    ): VoxBottleneckAnalysis {
        if (forensics == null) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.UNKNOWN,
                VoxBottleneckConfidence.LOW,
                "Данные сетевой криминалистики отсутствуют."
            )
        }

        val totalDlMs = Math.max(1L, forensics.elapsedMs)

        // 1. Packaging Slow (Muxing took longer than download or > 25s)
        if (packagingElapsedMs > 25_000L || (packagingElapsedMs > 5_000L && packagingElapsedMs > totalDlMs * 2)) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.MUX_SLOW,
                VoxBottleneckConfidence.HIGH,
                "Узкое место — мультиплексирование контейнера MKV (${packagingElapsedMs} мс)."
            )
        }

        // 2. Mediastore / Finalize slow (> 10s)
        if (finalizeElapsedMs > 10_000L) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.MEDIASTORE_SLOW,
                VoxBottleneckConfidence.HIGH,
                "Узкое место — публикация медиа-файла в файловую систему (${finalizeElapsedMs} мс)."
            )
        }

        // 3. DNS Slow (> 500ms)
        if (forensics.dnsMs > 500L) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.DNS_SLOW,
                VoxBottleneckConfidence.HIGH,
                "Задержка разрешения DNS (${forensics.dnsMs} мс) на хосте ${forensics.safeHost}."
            )
        }

        // 4. Connect Slow (> 800ms)
        if (forensics.connectMs > 800L) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.CONNECT_SLOW,
                VoxBottleneckConfidence.HIGH,
                "Медленное TCP-подключение (${forensics.connectMs} мс) к ${forensics.safeHost}."
            )
        }

        // 5. TLS Slow (> 800ms)
        if (forensics.tlsMs > 800L) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.TLS_SLOW,
                VoxBottleneckConfidence.HIGH,
                "Длительное TLS-рукопожатие (${forensics.tlsMs} мс) с ${forensics.safeHost}."
            )
        }

        // 6. TTFB Slow (> 1500ms)
        if (forensics.ttfbMs > 1500L) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.TTFB_SLOW,
                VoxBottleneckConfidence.MEDIUM,
                "Сервер долго отдавал первый байт (TTFB ${forensics.ttfbMs} мс) на ${forensics.safeHost}."
            )
        }

        // 7. Network Engine Degraded (multiple stalls or reconnects)
        if (forensics.stallCount >= 2 || forensics.retryCount >= 2) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.NETWORK_ENGINE_DEGRADED,
                VoxBottleneckConfidence.HIGH,
                "Зафиксированы сетевые зависания (${forensics.stallCount}) и повторные попытки (${forensics.retryCount})."
            )
        }

        // 8. Remote Throttling suspicion: fast handshake (< 300ms), low throughput (< 2 Mbps) with large file (> 10MB)
        if (forensics.bytesDownloaded > 10_000_000L &&
            forensics.avgMbps in 0.01..2.0 &&
            forensics.ttfbMs in 1..400L &&
            forensics.connectMs in 1..300L
        ) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.REMOTE_THROTTLING,
                VoxBottleneckConfidence.MEDIUM,
                "Низкая скорость наблюдается на media host ${forensics.safeHost}. Нужна проверка DIRECT vs VPN на роутере."
            )
        }

        // 9. Transfer Slow
        if (forensics.avgMbps in 0.01..3.0) {
            return VoxBottleneckAnalysis(
                VoxDownloadBottleneck.TRANSFER_SLOW,
                VoxBottleneckConfidence.MEDIUM,
                "Низкая скорость передачи потока (${String.format(Locale.US, "%.2f", forensics.avgMbps)} Мбит/с) на ${forensics.safeHost}."
            )
        }

        return VoxBottleneckAnalysis(
            VoxDownloadBottleneck.UNKNOWN,
            VoxBottleneckConfidence.LOW,
            "Скорость передачи нормальная (${String.format(Locale.US, "%.2f", forensics.avgMbps)} Мбит/с)."
        )
    }
}

/**
 * Bounded Parallelism Policy: Allows up to 2 parallel segment connections if Range is supported,
 * content length is known, and file is large.
 */
object VoxBoundedParallelismPolicy {

    const val MIN_CONTENT_LENGTH_FOR_PARALLEL = 20_000_000L // 20 MB

    @JvmStatic
    fun getParallelConnectionsCount(
        rangeSupported: Boolean,
        contentLength: Long?
    ): Int {
        if (!rangeSupported || contentLength == null || contentLength < MIN_CONTENT_LENGTH_FOR_PARALLEL) {
            return 1
        }
        return 2 // Safe bounded parallelism
    }
}

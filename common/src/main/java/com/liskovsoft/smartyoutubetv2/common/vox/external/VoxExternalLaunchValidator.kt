package com.liskovsoft.smartyoutubetv2.common.vox.external

import java.net.InetAddress
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * Валидатор безопасности и параметров для внешнего запуска видео (DIAL).
 * 
 * Обеспечивает:
 * 1. Проверку принадлежности IP-клиента к доверенной локальной сети (RFC 1918, Loopback, Link-Local).
 * 2. Строгую валидацию YouTube video ID (11 символов [a-zA-Z0-9_-]).
 * 3. Защиту от Path Traversal, Path Injection и переполнения буферов.
 */
object VoxExternalLaunchValidator {

    private val VIDEO_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{11}$")
    private val PLAYLIST_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{10,64}$")
    private val TIME_STRING_PATTERN = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?$")

    /**
     * Проверяет, является ли IP-адрес адресом локальной сети (RFC 1918, Loopback, Link-Local).
     * Запросы из внешнего интернета (WAN/Public IP) строго отклоняются.
     */
    fun isTrustedLocalAddress(address: InetAddress?): Boolean {
        if (address == null) return false

        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) {
            return true
        }

        val raw = address.address ?: return false

        // IPv4 checks
        if (raw.size == 4) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF

            // 127.0.0.0/8 (Loopback)
            if (b0 == 127) return true

            // 10.0.0.0/8 (RFC 1918)
            if (b0 == 10) return true

            // 172.16.0.0/12 (RFC 1918)
            if (b0 == 172 && (b1 in 16..31)) return true

            // 192.168.0.0/16 (RFC 1918)
            if (b0 == 192 && b1 == 168) return true

            // 169.254.0.0/16 (Link-local)
            if (b0 == 169 && b1 == 254) return true

            return false
        }

        // IPv6 checks (fe80::/10 link-local, fc00::/7 unique local, ::1 loopback, ::ffff:x.x.x.x ipv4-mapped)
        if (raw.size == 16) {
            // Check if IPv4-mapped IPv6 address (e.g. ::ffff:127.0.0.1 or ::ffff:192.168.1.1)
            var isIpv4Mapped = true
            for (i in 0 until 10) {
                if (raw[i] != 0.toByte()) {
                    isIpv4Mapped = false
                    break
                }
            }
            if (isIpv4Mapped && (raw[10].toInt() and 0xFF == 0xFF) && (raw[11].toInt() and 0xFF == 0xFF)) {
                val b0 = raw[12].toInt() and 0xFF
                val b1 = raw[13].toInt() and 0xFF
                if (b0 == 127) return true
                if (b0 == 10) return true
                if (b0 == 172 && (b1 in 16..31)) return true
                if (b0 == 192 && b1 == 168) return true
                if (b0 == 169 && b1 == 254) return true
                return false
            }

            // Check if ::1 (IPv6 loopback)
            if (address.isLoopbackAddress) return true
            var isAllZeroExceptLast = true
            for (i in 0 until 15) {
                if (raw[i] != 0.toByte()) {
                    isAllZeroExceptLast = false
                    break
                }
            }
            if (isAllZeroExceptLast && raw[15] == 1.toByte()) return true

            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF

            // fe80::/10 (Link-local)
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true

            // fc00::/7 (Unique local)
            if ((b0 and 0xFE) == 0xFC) return true

            return false
        }

        return false
    }

    /**
     * Валидация YouTube Video ID.
     */
    fun isValidVideoId(videoId: String?): Boolean {
        if (videoId.isNullOrBlank()) return false
        return VIDEO_ID_PATTERN.matcher(videoId.trim()).matches()
    }

    /**
     * Валидация YouTube Playlist ID.
     */
    fun isValidPlaylistId(playlistId: String?): Boolean {
        if (playlistId.isNullOrBlank()) return false
        return PLAYLIST_ID_PATTERN.matcher(playlistId.trim()).matches()
    }

    /**
     * Парсинг позиции времени в миллисекундах из форматов:
     * - "120" -> 120 000 ms
     * - "120s" -> 120 000 ms
     * - "2m15s" -> 135 000 ms
     * - "1h2m3s" -> 3 723 000 ms
     * - "12345ms" -> 12 345 ms
     */
    fun parseTimeToMs(timeStr: String?): Long {
        if (timeStr.isNullOrBlank()) return -1L
        val clean = timeStr.trim().lowercase()

        // Handle explicit millisecond suffix (e.g. "12345ms")
        if (clean.endsWith("ms")) {
            val numStr = clean.removeSuffix("ms")
            val v = numStr.toLongOrNull() ?: return -1L
            return if (v >= 0) v else -1L
        }

        // Pure integer = seconds (YouTube standard ?t=120, t=0 is valid = start of video)
        val pureDigits = clean.toLongOrNull()
        if (pureDigits != null) {
            if (pureDigits < 0) return -1L
            // Overflow guard: more than 24 hours is unrealistic for a YouTube video
            if (pureDigits > 86400L) return -1L
            return pureDigits * 1000L
        }

        // Compound format: Xh Ym Zs (e.g. 1h2m3s, 2m15s, 45s)
        val matcher = TIME_STRING_PATTERN.matcher(clean)
        if (matcher.matches()) {
            val hours = matcher.group(1)?.toLongOrNull() ?: 0L
            val minutes = matcher.group(2)?.toLongOrNull() ?: 0L
            val seconds = matcher.group(3)?.toLongOrNull() ?: 0L
            val totalSeconds = hours * 3600L + minutes * 60L + seconds
            if (totalSeconds >= 0) {
                return totalSeconds * 1000L
            }
        }

        return -1L
    }


    /**
     * Безопасное URL-декодирование строки.
     */
    fun safeUrlDecode(input: String?): String {
        if (input == null) return ""
        return try {
            URLDecoder.decode(input, "UTF-8")
        } catch (e: Exception) {
            input
        }
    }

    /**
     * Извлечение Video ID из произвольного URL или параметра.
     */
    fun extractVideoId(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()

        // Если это уже чистый 11-значный videoId
        if (isValidVideoId(trimmed)) {
            return trimmed
        }

        // Извлечение из query parameters v=...
        val vParamMatch = Pattern.compile("(?:[?&]v=|v=)([a-zA-Z0-9_-]{11})").matcher(trimmed)
        if (vParamMatch.find()) {
            return vParamMatch.group(1)
        }

        // Извлечение из youtu.be/...
        val youtuMatch = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})").matcher(trimmed)
        if (youtuMatch.find()) {
            return youtuMatch.group(1)
        }

        // Извлечение из /embed/... или /v/...
        val embedMatch = Pattern.compile("/(?:embed|v|shorts)/([a-zA-Z0-9_-]{11})").matcher(trimmed)
        if (embedMatch.find()) {
            return embedMatch.group(1)
        }

        return null
    }
}

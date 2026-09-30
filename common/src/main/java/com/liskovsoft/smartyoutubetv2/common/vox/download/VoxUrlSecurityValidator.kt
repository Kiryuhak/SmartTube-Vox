package com.liskovsoft.smartyoutubetv2.common.vox.download

import java.net.URI
import java.util.Locale

/**
 * Валидатор безопасности URL для предотвращения SSRF и атак через локальную сеть/файловую систему.
 */
object VoxUrlSecurityValidator {

    private val ALLOWED_SCHEMES = setOf("https")

    private val ALLOWED_HOST_SUFFIXES = listOf(
        // YouTube / Google Video CDN
        ".googlevideo.com",
        "googlevideo.com",
        ".youtube.com",
        "youtube.com",
        ".ytimg.com",
        "ytimg.com",
        // Yandex VOT / CDN / Cloud
        ".yandex.net",
        "yandex.net",
        ".yandex.ru",
        "yandex.ru",
        ".yandexcloud.net",
        "yandexcloud.net",
        ".workers.dev",
        "workers.dev"
    )

    /**
     * Проверяет URL на допустимость схемы, хоста и отсутствие приватных IP-адресов.
     *
     * @param url Проверяемый URL
     * @throws VoxDownloadException если URL небезопасен
     */
    @JvmStatic
    fun validateUrl(url: String) {
        if (url.isBlank()) {
            throw VoxDownloadException(VoxDownloadErrorCode.INVALID_URL, "URL cannot be empty")
        }

        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw VoxDownloadException(VoxDownloadErrorCode.INVALID_URL, "Malformed URL: $url", e)
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme !in ALLOWED_SCHEMES) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.INVALID_URL,
                "Forbidden URL scheme '$scheme'. Only HTTPS is allowed."
            )
        }

        val host = uri.host?.lowercase(Locale.US)
        if (host.isNullOrBlank()) {
            throw VoxDownloadException(VoxDownloadErrorCode.INVALID_URL, "URL missing valid host")
        }

        if (isPrivateOrLocalHost(host)) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.INVALID_URL,
                "Forbidden host pointing to local/private network: $host"
            )
        }

        val isAllowedHost = ALLOWED_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(suffix)
        }

        if (!isAllowedHost) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.INVALID_URL,
                "Untrusted host for media/translation: $host"
            )
        }
    }

    /**
     * Проверяет, не является ли хост локальным, приватным или спец-адресом.
     */
    @JvmStatic
    fun isPrivateOrLocalHost(host: String): Boolean {
        val lower = host.lowercase(Locale.US)
        if (lower == "localhost" || lower == "127.0.0.1" || lower == "0.0.0.0" || lower == "::1" || lower == "[::1]") {
            return true
        }

        // Проверка IPv4 диапазонов RFC 1918 / Link-Local / Loopback
        if (lower.matches(Regex("^127\\..*")) ||
            lower.matches(Regex("^10\\..*")) ||
            lower.matches(Regex("^192\\.168\\..*")) ||
            lower.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) ||
            lower.matches(Regex("^169\\.254\\..*")) ||
            lower.matches(Regex("^0\\..*"))
        ) {
            return true
        }

        // IPv6 loopback / unique local / link local
        if (lower.startsWith("fe80:") || lower.startsWith("fc00:") || lower.startsWith("fd00:")) {
            return true
        }

        return false
    }
}

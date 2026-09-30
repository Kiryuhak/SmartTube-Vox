package com.liskovsoft.smartyoutubetv2.common.vox.external

import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Парсер входящих HTTP/DIAL запросов.
 */
data class VoxHttpRequest(
    val method: String,
    val path: String,
    val queryString: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String = ""
)

object VoxDialRequestParser {

    private const val MAX_HEADER_SIZE = 8192
    private const val MAX_BODY_SIZE = 65536 // 64 KB
    private val VALID_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")

    /**
     * Читает и разбирает входящий HTTP-запрос из сокета.
     */
    fun parse(input: InputStream): VoxHttpRequest? {
        val headerBytes = readHeaderBytes(input) ?: return null
        val headerText = String(headerBytes, StandardCharsets.UTF_8)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty() || lines[0].isBlank()) return null

        val requestLineParts = lines[0].split(" ")
        if (requestLineParts.size < 2) return null

        val method = requestLineParts[0].uppercase()
        if (method !in VALID_METHODS) return null

        val rawUri = requestLineParts[1]
        if (!rawUri.startsWith("/")) return null

        val uriParts = rawUri.split("?", limit = 2)
        val path = uriParts[0]
        val queryString = if (uriParts.size > 1) uriParts[1] else ""

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val colonPos = line.indexOf(':')
            if (colonPos > 0) {
                val name = line.substring(0, colonPos).trim().lowercase()
                val value = line.substring(colonPos + 1).trim()
                headers[name] = value
            }
        }

        var body = ""
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength in 1..MAX_BODY_SIZE) {
            val bodyBytes = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val read = input.read(bodyBytes, totalRead, contentLength - totalRead)
                if (read < 0) break
                totalRead += read
            }
            body = String(bodyBytes, 0, totalRead, StandardCharsets.UTF_8)
        }

        return VoxHttpRequest(
            method = method,
            path = path,
            queryString = queryString,
            headers = headers,
            body = body
        )
    }

    /**
     * Извлекает параметры видео (videoId, playlistId, timeMs) из DIAL POST body или queryString.
     * 
     * Поддерживаемые форматы body/query:
     * - `v=dQw4w9WgXcQ`
     * - `v=dQw4w9WgXcQ&t=120`
     * - `pairingCode=...&v=dQw4w9WgXcQ`
     * - `list=PL...&v=...`
     * - Прямой video ID: `dQw4w9WgXcQ`
     * - URL: `https://www.youtube.com/watch?v=dQw4w9WgXcQ`
     */
    fun extractVideoParameters(request: VoxHttpRequest, clientIp: String): VoxExternalVideoRequest? {
        val payload = if (request.body.isNotBlank()) request.body else request.queryString
        if (payload.isBlank()) return null

        val decoded = VoxExternalLaunchValidator.safeUrlDecode(payload)

        var videoId: String? = null
        var playlistId: String? = null
        var timeMs: Long = -1L

        // Попытка парсинга key=value пар
        val pairs = decoded.split("&")
        for (pair in pairs) {
            val kv = pair.split("=", limit = 2)
            if (kv.isEmpty()) continue
            val key = kv[0].trim().lowercase()
            val value = if (kv.size > 1) kv[1].trim() else ""

            when (key) {
                "v", "videoid", "video_id" -> {
                    if (VoxExternalLaunchValidator.isValidVideoId(value)) {
                        videoId = value
                    }
                }
                "list", "playlistid", "playlist_id" -> {
                    if (VoxExternalLaunchValidator.isValidPlaylistId(value)) {
                        playlistId = value
                    }
                }
                "t", "time", "start", "position" -> {
                    val parsed = VoxExternalLaunchValidator.parseTimeToMs(value)
                    if (parsed >= 0) {
                        timeMs = parsed
                    }
                }
            }
        }

        // Если не удалось извлечь по парам ключ-значение, проверяем regex
        if (videoId == null) {
            videoId = VoxExternalLaunchValidator.extractVideoId(decoded)
        }

        if (videoId != null && VoxExternalLaunchValidator.isValidVideoId(videoId)) {
            return VoxExternalVideoRequest(
                videoId = videoId,
                playlistId = playlistId,
                timeMs = timeMs,
                clientIp = clientIp,
                timestampMs = System.currentTimeMillis()
            )
        }

        return null
    }

    private fun readHeaderBytes(input: InputStream): ByteArray? {
        val buffer = ByteArray(MAX_HEADER_SIZE)
        var totalRead = 0

        while (totalRead < MAX_HEADER_SIZE) {
            val b = input.read()
            if (b < 0) {
                if (totalRead == 0) return null
                break
            }
            buffer[totalRead++] = b.toByte()

            // Check for \r\n\r\n
            if (totalRead >= 4 &&
                buffer[totalRead - 4] == '\r'.code.toByte() &&
                buffer[totalRead - 3] == '\n'.code.toByte() &&
                buffer[totalRead - 2] == '\r'.code.toByte() &&
                buffer[totalRead - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }

        val result = ByteArray(totalRead)
        System.arraycopy(buffer, 0, result, 0, totalRead)
        return result
    }
}

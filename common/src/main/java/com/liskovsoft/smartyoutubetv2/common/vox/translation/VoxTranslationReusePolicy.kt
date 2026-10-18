package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Политика быстрого повторного использования готовых переводов и безопасной диагностики старта.
 * Исключает утечку PII (название видео, токен, url query, аккаунт).
 */
object VoxTranslationReusePolicy {

    const val DEFAULT_TARGET_LANG = "ru"
    const val SLOW_STARTUP_THRESHOLD_STANDARD_MS = 5_000L
    const val SLOW_STARTUP_THRESHOLD_LIVE_VOICE_MS = 8_000L

    @JvmStatic
    fun extractVideoId(rawUrlOrId: String?): String? {
        if (rawUrlOrId.isNullOrBlank()) return null
        val trimmed = rawUrlOrId.trim()

        // 1. Plain Video ID (YouTube IDs are alphanumeric, '-' or '_')
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{6,32}$"))) {
            return trimmed
        }

        // 2. youtu.be short link: https://youtu.be/<id>?si=...
        if (trimmed.contains("youtu.be/")) {
            val after = trimmed.substringAfter("youtu.be/").substringBefore("?").substringBefore("&").substringBefore("#")
            val id = after.trimEnd('/').substringBefore("/")
            if (id.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                return id
            }
        }

        // 3. /watch, /embed, /shorts, /live, /v, /e path patterns
        val pathRegex = Regex("/(?:watch|embed|shorts|live|v|e)/([a-zA-Z0-9_-]+)")
        val match = pathRegex.find(trimmed)
        if (match != null) {
            return match.groupValues[1]
        }

        // 4. Query parameter v= or video_id=
        val queryRegex = Regex("[?&](?:v|video_id)=([a-zA-Z0-9_-]+)")
        val queryMatch = queryRegex.find(trimmed)
        if (queryMatch != null) {
            return queryMatch.groupValues[1]
        }

        return null
    }

    @JvmStatic
    fun normalizeVideoUrl(rawUrlOrId: String?): String {
        val videoId = extractVideoId(rawUrlOrId)
        return if (!videoId.isNullOrBlank()) {
            "https://youtu.be/$videoId"
        } else {
            rawUrlOrId?.trim() ?: ""
        }
    }

    fun buildCacheKey(
        videoUrl: String?,
        sourceLang: String?,
        targetLang: String?,
        useLiveVoices: Boolean
    ): String {
        val normalizedUrl = normalizeVideoUrl(videoUrl)
        val sLang = normalizeSourceLang(sourceLang)
        val tLang = if (!targetLang.isNullOrBlank()) targetLang.trim().lowercase() else DEFAULT_TARGET_LANG
        return "$normalizedUrl|$sLang|$tLang|$useLiveVoices"
    }

    fun buildFallbackCacheKey(
        videoUrl: String?,
        targetLang: String?,
        useLiveVoices: Boolean
    ): String {
        return buildCacheKey(videoUrl, "", targetLang, useLiveVoices)
    }

    fun normalizeSourceLang(sourceLang: String?): String {
        if (sourceLang.isNullOrBlank()) return ""
        val trimmed = sourceLang.trim().lowercase()
        if (trimmed == "auto" || trimmed == "und") return ""
        return trimmed.substringBefore("-")
    }

    fun isReusable(status: Int, audioUrl: String?): Boolean {
        // Status 1 = FINISHED
        return status == 1 && !audioUrl.isNullOrBlank()
    }

    fun shouldSkipDuplicate(
        currentVideoId: String?,
        requestedVideoId: String?,
        isPending: Boolean
    ): Boolean {
        if (!isPending) return false
        if (currentVideoId.isNullOrBlank() || requestedVideoId.isNullOrBlank()) return false
        return currentVideoId == requestedVideoId
    }

    fun isStartupSlow(elapsedMs: Long, isLiveVoice: Boolean): Boolean {
        val threshold = if (isLiveVoice) SLOW_STARTUP_THRESHOLD_LIVE_VOICE_MS else SLOW_STARTUP_THRESHOLD_STANDARD_MS
        return elapsedMs > threshold
    }

    fun createSafeDiagnosticsContext(
        elapsedMs: Long,
        source: String,
        sameVideo: Boolean,
        mode: String
    ): Map<String, String> {
        val safeSource = when (source.lowercase()) {
            "cache" -> "cache"
            "existing" -> "existing"
            else -> "api"
        }
        val safeMode = if (mode.contains("live") || mode == "live_voice") "live_voice" else "standard"
        return mapOf(
            "elapsedMs" to Math.max(0L, elapsedMs).toString(),
            "source" to safeSource,
            "sameVideo" to sameVideo.toString(),
            "mode" to safeMode
        )
    }
}

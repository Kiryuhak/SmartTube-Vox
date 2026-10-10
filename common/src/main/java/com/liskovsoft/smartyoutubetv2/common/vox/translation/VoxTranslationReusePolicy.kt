package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Политика быстрого повторного использования готовых переводов и безопасной диагностики старта.
 * Исключает утечку PII (название видео, токен, url query, аккаунт).
 */
object VoxTranslationReusePolicy {

    const val DEFAULT_TARGET_LANG = "ru"
    const val SLOW_STARTUP_THRESHOLD_STANDARD_MS = 5_000L
    const val SLOW_STARTUP_THRESHOLD_LIVE_VOICE_MS = 8_000L

    fun normalizeVideoUrl(rawUrlOrId: String?): String {
        if (rawUrlOrId.isNullOrBlank()) return ""
        val trimmed = rawUrlOrId.trim()
        val videoId = when {
            trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$")) -> trimmed
            trimmed.contains("youtu.be/") -> {
                val after = trimmed.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
                if (after.length == 11) after else trimmed
            }
            trimmed.contains("v=") -> {
                val after = trimmed.substringAfter("v=").substringBefore("&")
                if (after.length == 11) after else trimmed
            }
            else -> trimmed
        }
        return if (videoId.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            "https://www.youtube.com/watch?v=$videoId"
        } else {
            trimmed.substringBefore("?")
        }
    }

    fun buildCacheKey(
        videoUrl: String?,
        sourceLang: String?,
        targetLang: String?,
        useLiveVoices: Boolean
    ): String {
        val normalizedUrl = normalizeVideoUrl(videoUrl)
        val sLang = sourceLang?.trim()?.lowercase() ?: ""
        val tLang = if (!targetLang.isNullOrBlank()) targetLang.trim().lowercase() else DEFAULT_TARGET_LANG
        return "$normalizedUrl|$sLang|$tLang|$useLiveVoices"
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

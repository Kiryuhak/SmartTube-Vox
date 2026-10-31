package com.liskovsoft.smartyoutubetv2.common.vox.voice

import java.util.Locale

/**
 * Voice selection policy matching VOT userscript 1.12.4:
 *
 * References from reference userscript 1.12.4 (lines 48517-48520, 48192-48196, 33501-33567):
 * 1. isLivelyVoiceAllowed(requestLang, responseLang):
 *    if (requestLang === "auto" || responseLang !== "ru") return false;
 *    return true;
 * 2. getRequestLangForTranslation(requestLang, responseLang):
 *    Returns the normalized request language without forcing "en" for "auto".
 * 3. isLivelyVoiceUnavailableError(msg):
 *    msg.toLowerCase().includes("обычная озвучка") || msg.toLowerCase().includes("standard voice")
 * 4. Cache key includes useLivelyVoice flag so standard and lively cached translations never collide.
 */
object VoxVoiceSelectionPolicy {

    /**
     * Resolves request language for API.
     * Preserves "auto" when unresolved; never forces "en" on non-English content.
     */
    @JvmStatic
    @JvmOverloads
    @Suppress("UNUSED_PARAMETER")
    fun getRequestLangForTranslation(
        requestLang: String?,
        responseLang: String?,
        useLivelyVoice: Boolean = true
    ): String {
        return if (requestLang.isNullOrBlank()) "auto" else requestLang.lowercase(Locale.US)
    }

    /**
     * Checks if Lively Voice is supported for the given language pair.
     * Per VOT 1.12.4 (lines 48517-48520), Lively Voice is allowed only when:
     * - User has an active OAuth account session
     * - Target language is Russian ("ru")
     * - Source language is concrete and NOT "auto"
     */
    @JvmStatic
    @JvmOverloads
    fun isLivelyVoiceAllowed(
        requestLang: String?,
        responseLang: String?,
        hasActiveAccount: Boolean = true
    ): Boolean {
        if (!hasActiveAccount) return false
        val resp = if (responseLang.isNullOrBlank()) "ru" else responseLang.lowercase(Locale.US)
        if (resp != "ru") return false

        val req = if (requestLang.isNullOrBlank()) "auto" else requestLang.lowercase(Locale.US)
        if (req == "auto" || req.isBlank()) return false
        return true
    }

    /**
     * Detects when server rejects request because lively voice is unavailable for the video,
     * allowing automatic graceful fallback to standard voice without failing translation.
     */
    @JvmStatic
    fun isLivelyVoiceUnavailableError(errorMessage: String?): Boolean {
        if (errorMessage.isNullOrBlank()) return false
        val lower = errorMessage.lowercase(Locale.US)
        return lower.contains("обычная озвучка") || lower.contains("standard voice")
    }

    /**
     * Builds translation cache key incorporating Lively Voice selection so artifacts never collide.
     */
    @JvmStatic
    @JvmOverloads
    fun buildTranslationCacheKey(
        videoId: String,
        fromLang: String?,
        toLang: String?,
        useLivelyVoice: Boolean,
        extraTag: String = "0"
    ): String {
        val resolvedFrom = getRequestLangForTranslation(fromLang, toLang, useLivelyVoice)
        val resolvedTo = if (toLang.isNullOrBlank()) "ru" else toLang.lowercase(Locale.US)
        val effectiveLively = useLivelyVoice && isLivelyVoiceAllowed(resolvedFrom, resolvedTo, true)
        return "${videoId}_${resolvedFrom}_${resolvedTo}_${effectiveLively}_$extraTag"
    }
}

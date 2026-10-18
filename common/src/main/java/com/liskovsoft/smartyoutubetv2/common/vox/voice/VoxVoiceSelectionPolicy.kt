package com.liskovsoft.smartyoutubetv2.common.vox.voice

import java.util.Locale

/**
 * Voice selection policy matching VOT userscript 1.11.17:
 *
 * References from reference userscript (lines 43628-43633, 44003-44010, 30485-30489):
 * 1. getRequestLangForTranslation(requestLang, responseLang):
 *    if (this.data?.useLivelyVoice && requestLang === "auto" && responseLang === "ru") return "en";
 *    return requestLang;
 * 2. isLivelyVoiceAllowed(requestLang, responseLang):
 *    if (this.getRequestLangForTranslation(requestLang, responseLang) === "auto" || responseLang !== "ru") return false;
 *    return true;
 * 3. isLivelyVoiceUnavailableError(msg):
 *    msg.toLowerCase().includes("обычная озвучка") || msg.toLowerCase().includes("standard voice")
 * 4. Cache key includes useLivelyVoice flag so standard and lively cached translations never collide.
 */
object VoxVoiceSelectionPolicy {

    /**
     * Resolves request language for API.
     * When Lively voice is enabled, auto -> ru defaults to en -> ru.
     */
    @JvmStatic
    @JvmOverloads
    fun getRequestLangForTranslation(
        requestLang: String?,
        responseLang: String?,
        useLivelyVoice: Boolean = true
    ): String {
        val req = if (requestLang.isNullOrBlank()) "auto" else requestLang.lowercase(Locale.US)
        val resp = if (responseLang.isNullOrBlank()) "ru" else responseLang.lowercase(Locale.US)

        if (useLivelyVoice && req == "auto" && resp == "ru") {
            return "en"
        }
        return req
    }

    /**
     * Checks if Lively Voice is supported for the given language pair.
     * Lively Voice is only supported when source language resolves to a concrete language (not auto)
     * and target language is Russian ("ru").
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

        val apiReq = getRequestLangForTranslation(requestLang, resp, useLivelyVoice = true)
        if (apiReq == "auto" || apiReq.isBlank()) return false
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
        return "${videoId}_${resolvedFrom}_${resolvedTo}_${useLivelyVoice}_$extraTag"
    }
}

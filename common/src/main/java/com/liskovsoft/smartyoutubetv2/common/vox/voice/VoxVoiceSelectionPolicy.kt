package com.liskovsoft.smartyoutubetv2.common.vox.voice

import java.util.Locale

/**
 * Voice selection policy matching VOT userscript 1.12.5 / 1.12.6:
 *
 * Upstream Reference:
 * - VOT 1.12.4: YouTube audio-track selection, WebABR fallbacks, cancellation protection.
 * - VOT 1.12.5: "auto" source language preserves "Live Voice" selection (previously forced standard).
 *   Russian source audio is bypassed to prevent redundant/unwanted translation.
 * - VOT 1.12.6: dependency bump (@vot.js libraries 3.2.2) and bilibili improvements (N/A to Android TV YouTube).
 */
object VoxVoiceSelectionPolicy {

    /**
     * Resolves request language for API.
     * Preserves "auto" when unresolved; never forces "en" on non-English content.
     */
    @JvmStatic
    @JvmOverloads
    fun getRequestLangForTranslation(
        requestLang: String?,
        responseLang: String?,
        useLivelyVoice: Boolean = true
    ): String {
        return if (requestLang.isNullOrBlank()) "auto" else requestLang.lowercase(Locale.US)
    }

    /**
     * Checks if Lively Voice is supported for the given language pair.
     * Per VOT 1.12.5:
     * - User has an active OAuth account session
     * - Target language is Russian ("ru")
     * - Source language preserves Live Voice even when "auto"
     * - Source language is NOT Russian ("ru")
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
        if (req.isBlank() || req == "ru") return false
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

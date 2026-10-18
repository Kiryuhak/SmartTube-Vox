package com.liskovsoft.smartyoutubetv2.common.vox.language

import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationReusePolicy

/**
 * Иерархический пайплайн определения языка видео:
 * 1. LOCAL LANGUAGE CACHE (0 обращений к сети)
 * 2. SERVER VideoLangCache (/video-translation/lang-cache)
 * 3. FALLBACK DETECTION (аудиодорожки плеера / субтитры YouTube / метаданные)
 */
class VoxVideoLanguagePipeline @JvmOverloads constructor(
    private val localCache: VoxVideoLanguageCache,
    private val serverClient: ServerLookup = DefaultServerLookup
) {

    interface ServerLookup {
        fun lookupServerLangCache(videoUrl: String, title: String?): String?
    }

    object DefaultServerLookup : ServerLookup {
        override fun lookupServerLangCache(videoUrl: String, title: String?): String? {
            return try {
                val res = YandexVotApiClient.requestVideoLangCache(videoUrl, title ?: "")
                if (res != null && res.url.isNotEmpty()) {
                    // Если сервер вернул url с кодом языка или статус
                    null
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    enum class ResolutionSource {
        LOCAL_CACHE,
        SERVER_CACHE,
        FALLBACK_DETECTION,
        USER_OVERRIDE,
        UNKNOWN
    }

    data class LanguageResult(
        val language: String,
        val source: ResolutionSource,
        val isResolved: Boolean
    )

    fun resolveLanguage(
        videoId: String?,
        rawUrl: String?,
        title: String? = null,
        playerAudioLang: String? = null,
        youtubeSubtitleLangs: List<String>? = null,
        allowServerLookup: Boolean = true
    ): LanguageResult {
        val canonicalUrl = VoxTranslationReusePolicy.normalizeVideoUrl(rawUrl ?: videoId)
        val vid = videoId ?: VoxTranslationReusePolicy.extractVideoId(canonicalUrl) ?: ""

        // 1. LOCAL LANGUAGE CACHE
        val localCached = localCache.getLanguage(vid)
        if (!localCached.isNullOrBlank() && VoxLanguagePolicy.isResolvedLanguage(localCached)) {
            val ctx = mapOf("videoId" to vid, "language" to localCached, "source" to "local_cache")
            VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.VOT_LANGUAGE_LOCAL_CACHE_HIT, "VOT: Local language cache hit: $localCached", ctx)
            return LanguageResult(localCached, ResolutionSource.LOCAL_CACHE, true)
        }

        // 2. SERVER VideoLangCache
        if (allowServerLookup && canonicalUrl.isNotEmpty()) {
            try {
                val serverLang = serverClient.lookupServerLangCache(canonicalUrl, title)
                val normalizedServer = VoxLanguagePolicy.normalizeToRequestLang(serverLang)
                if (!normalizedServer.isNullOrBlank() && VoxLanguagePolicy.isResolvedLanguage(normalizedServer)) {
                    localCache.putLanguage(vid, normalizedServer)
                    val ctx = mapOf("videoId" to vid, "language" to normalizedServer, "source" to "server_cache")
                    VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.VOT_LANGUAGE_SERVER_CACHE_HIT, "VOT: Server language cache hit: $normalizedServer", ctx)
                    return LanguageResult(normalizedServer, ResolutionSource.SERVER_CACHE, true)
                }
            } catch (e: Exception) {
                // Ошибка сети не является промахом кэша (не кэшируем "неизвестно")
                VoxSafeLogger.w(VoxLogCategory.TRANSLATION, "VOT_LANGUAGE_SERVER_ERROR", "Server lang cache request failed: ${e.message}")
            }
        }

        // 3. FALLBACK DETECTION (из дорожек плеера или субтитров)
        val detectedFromAudio = VoxLanguagePolicy.normalizeToRequestLang(playerAudioLang)
        if (!detectedFromAudio.isNullOrBlank() && VoxLanguagePolicy.isResolvedLanguage(detectedFromAudio)) {
            localCache.putLanguage(vid, detectedFromAudio)
            val ctx = mapOf("videoId" to vid, "language" to detectedFromAudio, "source" to "audio_track")
            VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.VOT_LANGUAGE_DETECTED, "VOT: Language detected from audio track: $detectedFromAudio", ctx)
            return LanguageResult(detectedFromAudio, ResolutionSource.FALLBACK_DETECTION, true)
        }

        if (youtubeSubtitleLangs != null && youtubeSubtitleLangs.isNotEmpty()) {
            for (subLang in youtubeSubtitleLangs) {
                val candidate = VoxLanguagePolicy.normalizeToRequestLang(subLang)
                if (!candidate.isNullOrBlank() && VoxLanguagePolicy.isResolvedLanguage(candidate)) {
                    localCache.putLanguage(vid, candidate)
                    val ctx = mapOf("videoId" to vid, "language" to candidate, "source" to "subtitles")
                    VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.VOT_LANGUAGE_DETECTED, "VOT: Language detected from YouTube subtitles: $candidate", ctx)
                    return LanguageResult(candidate, ResolutionSource.FALLBACK_DETECTION, true)
                }
            }
        }

        // 4. Язык не удалось однозначно определить
        val ctx = mapOf("videoId" to vid)
        VoxSafeLogger.d(VoxLogCategory.TRANSLATION, VoxLogCode.VOT_LANGUAGE_CACHE_MISS, "VOT: Language cache miss", ctx)
        return LanguageResult(VoxLanguagePolicy.LANG_AUTO, ResolutionSource.UNKNOWN, false)
    }

    fun rememberResolvedLanguage(videoId: String?, language: String?) {
        val normalized = VoxLanguagePolicy.normalizeToRequestLang(language)
        if (!normalized.isNullOrBlank() && VoxLanguagePolicy.isResolvedLanguage(normalized)) {
            localCache.putLanguage(videoId, normalized)
        }
    }
}

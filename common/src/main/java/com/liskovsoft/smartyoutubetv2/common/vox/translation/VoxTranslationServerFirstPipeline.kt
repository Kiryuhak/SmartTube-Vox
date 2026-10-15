package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioTrackHelper

/**
 * Server-First Translation Pipeline 2.0.
 *
 * Implements strict hierarchical lookup before any job creation:
 * USER REQUEST
 *   -> Source language check (Graceful skip if already Russian/target)
 *   -> L1 Local Memory/Session Cache
 *   -> L2 Server Database Lookup
 *        - READY -> Reuse artifact immediately (no job creation)
 *        - EXPIRED -> Refresh signed URL and reuse
 *        - PROCESSING -> Attach to existing jobId (dedup concurrent requests)
 *        - NOT_FOUND -> Atomic get-or-create new job
 *        - FAILED -> Clear error state and schedule retry
 *   -> Play
 */
class VoxTranslationServerFirstPipeline(
    private val maxRetryAttempts: Int = 3,
    private val baseRetryDelayMs: Long = 2000L
) {

    data class TranslationRequest(
        val videoId: String,
        val rawUrl: String? = null,
        val sourceLang: String = "",
        val targetLang: String = "ru",
        val useLiveVoices: Boolean = false
    ) {
        val cacheKey: String
            get() = VoxTranslationReusePolicy.buildCacheKey(
                rawUrl ?: videoId,
                sourceLang,
                targetLang,
                useLiveVoices
            )
    }

    enum class Stage(val descriptionRu: String) {
        CHECKING_SAME_LANGUAGE("Проверка языка аудио"),
        CHECKING_LOCAL_CACHE("Проверка локального кэша"),
        LOOKING_UP_SERVER("Поиск готового перевода на сервере"),
        READY_FOR_PLAYBACK("Перевод готов"),
        ATTACHED_TO_EXISTING_JOB("Подключение к активному переводу"),
        PROCESSING("Обработка аудиодорожки"),
        REFRESHING_URL("Обновление ссылки на аудио"),
        SKIPPED_SAME_LANGUAGE("Видео уже на языке перевода"),
        RETRYING("Повторная попытка перевода"),
        FAILED("Не удалось перевести видео")
    }

    sealed class PipelineResult {
        data class ImmediatePlayback(
            val audioUrl: String,
            val source: String,
            val durationSec: Double
        ) : PipelineResult()

        data class AttachToJob(
            val jobId: String,
            val status: String,
            val remainingSec: Int,
            val verifiedProgressPercent: Int = -1
        ) : PipelineResult()

        data class InitiatedNewJob(
            val jobId: String,
            val initialStatus: String = "PROCESSING"
        ) : PipelineResult()

        data class SkippedSameLanguage(
            val reason: String
        ) : PipelineResult()

        data class RetryScheduled(
            val attempt: Int,
            val maxAttempts: Int,
            val delayMs: Long
        ) : PipelineResult()

        data class Failed(
            val errorMessage: String,
            val canRetry: Boolean
        ) : PipelineResult()
    }

    sealed class ServerLookupResponse {
        data class Ready(
            val audioUrl: String,
            val durationSec: Double,
            val isExpired: Boolean = false
        ) : ServerLookupResponse()

        data class Processing(
            val jobId: String,
            val remainingSec: Int = 0,
            val verifiedPercent: Int = -1
        ) : ServerLookupResponse()

        object NotFound : ServerLookupResponse()

        data class Failed(
            val error: String,
            val retryable: Boolean = true
        ) : ServerLookupResponse()
    }

    data class ServerCreateResponse(
        val jobId: String,
        val status: String
    )

    data class ServerRefreshResponse(
        val ok: Boolean,
        val refreshedAudioUrl: String? = null
    )

    interface LocalCacheProvider {
        fun lookup(request: TranslationRequest): VoxTranslationSessionCache.LookupResult
        fun store(request: TranslationRequest, audioUrl: String, durationSec: Double)
    }

    interface ServerDatabaseService {
        fun queryExistingTranslation(
            videoId: String,
            sourceLang: String,
            targetLang: String
        ): ServerLookupResponse

        fun createTranslationJob(
            videoId: String,
            sourceLang: String,
            targetLang: String
        ): ServerCreateResponse

        fun refreshSignedUrl(
            videoId: String,
            expiredAudioUrl: String
        ): ServerRefreshResponse
    }

    fun execute(
        request: TranslationRequest,
        localCache: LocalCacheProvider,
        serverDb: ServerDatabaseService,
        retryAttempt: Int = 0
    ): PipelineResult {
        val target = if (request.targetLang.isNotBlank()) request.targetLang.trim().lowercase() else "ru"
        val source = request.sourceLang.trim().lowercase()

        // 1. Guard against unnecessary translation if source already matches target or is Russian
        if (target == "ru" && VotAudioTrackHelper.isRussianLang(source)) {
            return PipelineResult.SkippedSameLanguage("Видео уже содержит аудиодорожку на русском языке")
        }
        if (source.isNotBlank() && VotAudioTrackHelper.isSameLanguage(source, target)) {
            return PipelineResult.SkippedSameLanguage("Язык видео ($source) совпадает с языком перевода ($target)")
        }

        // 2. L1: Local Cache lookup
        val localResult = localCache.lookup(request)
        if (localResult is VoxTranslationSessionCache.LookupResult.Hit) {
            return PipelineResult.ImmediatePlayback(
                audioUrl = localResult.record.audioUrl,
                source = "L1_LOCAL_CACHE",
                durationSec = localResult.record.durationSec
            )
        }

        // 3. L2: Server Database Lookup (Existing Translation Check)
        val serverResponse = serverDb.queryExistingTranslation(request.videoId, request.sourceLang, target)
        return when (serverResponse) {
            is ServerLookupResponse.Ready -> {
                if (serverResponse.isExpired) {
                    val refreshRes = serverDb.refreshSignedUrl(request.videoId, serverResponse.audioUrl)
                    if (refreshRes.ok && !refreshRes.refreshedAudioUrl.isNullOrBlank()) {
                        localCache.store(request, refreshRes.refreshedAudioUrl, serverResponse.durationSec)
                        PipelineResult.ImmediatePlayback(
                            audioUrl = refreshRes.refreshedAudioUrl,
                            source = "L2_SERVER_REFRESHED",
                            durationSec = serverResponse.durationSec
                        )
                    } else {
                        // Failed to refresh expired URL -> initiate fresh job
                        val newJob = serverDb.createTranslationJob(request.videoId, request.sourceLang, target)
                        PipelineResult.InitiatedNewJob(newJob.jobId)
                    }
                } else {
                    localCache.store(request, serverResponse.audioUrl, serverResponse.durationSec)
                    PipelineResult.ImmediatePlayback(
                        audioUrl = serverResponse.audioUrl,
                        source = "L2_SERVER_DB",
                        durationSec = serverResponse.durationSec
                    )
                }
            }

            is ServerLookupResponse.Processing -> {
                // Concurrent request dedup: attach to existing active job
                PipelineResult.AttachToJob(
                    jobId = serverResponse.jobId,
                    status = "PROCESSING",
                    remainingSec = serverResponse.remainingSec,
                    verifiedProgressPercent = serverResponse.verifiedPercent
                )
            }

            is ServerLookupResponse.NotFound -> {
                // Not in DB -> initiate translation
                val newJob = serverDb.createTranslationJob(request.videoId, request.sourceLang, target)
                PipelineResult.InitiatedNewJob(newJob.jobId)
            }

            is ServerLookupResponse.Failed -> {
                if (serverResponse.retryable && retryAttempt < maxRetryAttempts) {
                    val nextAttempt = retryAttempt + 1
                    val delayMs = baseRetryDelayMs * nextAttempt
                    PipelineResult.RetryScheduled(nextAttempt, maxRetryAttempts, delayMs)
                } else {
                    PipelineResult.Failed(serverResponse.error, serverResponse.retryable)
                }
            }
        }
    }
    fun evaluateEligibility(request: TranslationRequest): PipelineResult? {
        val target = if (request.targetLang.isNotBlank()) request.targetLang.trim().lowercase() else "ru"
        val source = request.sourceLang.trim().lowercase()

        if (target == "ru" && VotAudioTrackHelper.isRussianLang(source)) {
            return PipelineResult.SkippedSameLanguage("Видео уже содержит аудиодорожку на русском языке")
        }
        if (source.isNotBlank() && VotAudioTrackHelper.isSameLanguage(source, target)) {
            return PipelineResult.SkippedSameLanguage("Язык видео ($source) совпадает с языком перевода ($target)")
        }
        return null
    }

    companion object {
        @JvmStatic
        val defaultLocalCache: LocalCacheProvider = object : LocalCacheProvider {
            override fun lookup(request: TranslationRequest): VoxTranslationSessionCache.LookupResult {
                return VoxTranslationSessionCache.lookup(
                    request.videoId,
                    request.rawUrl,
                    request.sourceLang,
                    request.targetLang,
                    request.useLiveVoices
                )
            }

            override fun store(request: TranslationRequest, audioUrl: String, durationSec: Double) {
                VoxTranslationSessionCache.storeReadyTranslation(
                    request.videoId,
                    request.rawUrl,
                    request.sourceLang,
                    request.targetLang,
                    audioUrl,
                    durationSec,
                    request.useLiveVoices
                )
            }
        }

        @JvmStatic
        val defaultServerDatabase: ServerDatabaseService = object : ServerDatabaseService {
            override fun queryExistingTranslation(
                videoId: String,
                sourceLang: String,
                targetLang: String
            ): ServerLookupResponse {
                val videoUrl = "https://www.youtube.com/watch?v=$videoId"
                val cached = com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient.getCachedResult(
                    videoUrl, sourceLang, targetLang, false
                )
                if (cached != null && cached.status == com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient.STATUS_FINISHED && !cached.audioUrl.isNullOrBlank()) {
                    return ServerLookupResponse.Ready(cached.audioUrl, 0.0, isExpired = false)
                }
                return ServerLookupResponse.NotFound
            }

            override fun createTranslationJob(
                videoId: String,
                sourceLang: String,
                targetLang: String
            ): ServerCreateResponse {
                val key = VoxTranslationReusePolicy.buildCacheKey("https://www.youtube.com/watch?v=$videoId", sourceLang, targetLang, false)
                VoxTranslationSessionCache.registerPendingJob(videoId, "https://www.youtube.com/watch?v=$videoId", sourceLang, targetLang, false)
                return ServerCreateResponse(key, "PROCESSING")
            }

            override fun refreshSignedUrl(
                videoId: String,
                expiredAudioUrl: String
            ): ServerRefreshResponse {
                return ServerRefreshResponse(ok = false)
            }
        }
    }
}

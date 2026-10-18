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
            val retryable: Boolean = true,
            val isAuthError: Boolean = false,
            val isServerError: Boolean = false,
            val isTimeout: Boolean = false
        ) : ServerLookupResponse()

        companion object {
            @JvmStatic
            fun fromHttpStatus(status: Int, message: String): ServerLookupResponse {
                return when (status) {
                    401, 403 -> Failed("Authentication required ($status): $message", retryable = false, isAuthError = true)
                    404 -> NotFound
                    in 500..599 -> Failed("Server error ($status): $message", retryable = true, isServerError = true)
                    else -> Failed("HTTP error ($status): $message", retryable = true)
                }
            }

            @JvmStatic
            fun timeout(message: String = "Server lookup timed out"): ServerLookupResponse {
                return Failed(message, retryable = true, isTimeout = true)
            }
        }
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
        if (localResult is VoxTranslationSessionCache.LookupResult.PendingJob) {
            return PipelineResult.AttachToJob(
                jobId = localResult.job.key,
                status = "PROCESSING",
                remainingSec = 0
            )
        }

        // 3. L2: Server Database Lookup (Existing Translation Check)
        val serverResponse = serverDb.queryExistingTranslation(request.videoId, request.sourceLang, target)
        return when (serverResponse) {
            is ServerLookupResponse.Ready -> {
                if (serverResponse.isExpired || !VoxTranslationStateMachine.isValidAudioUrl(serverResponse.audioUrl)) {
                    val refreshRes = serverDb.refreshSignedUrl(request.videoId, serverResponse.audioUrl)
                    if (refreshRes.ok && !refreshRes.refreshedAudioUrl.isNullOrBlank()) {
                        localCache.store(request, refreshRes.refreshedAudioUrl, serverResponse.durationSec)
                        PipelineResult.ImmediatePlayback(
                            audioUrl = refreshRes.refreshedAudioUrl,
                            source = "L2_SERVER_REFRESHED",
                            durationSec = serverResponse.durationSec
                        )
                    } else {
                        // Failed to refresh expired/invalid URL -> initiate fresh job
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
                val videoUrl = VoxTranslationReusePolicy.normalizeVideoUrl(videoId)
                val cacheKey = VoxTranslationReusePolicy.buildCacheKey(videoUrl, sourceLang, targetLang, false)

                // 1. In-memory / client cache check
                val cached = com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient.getCachedResult(
                    videoUrl, sourceLang, targetLang, false
                )
                if (cached != null && cached.isReady) {
                    return ServerLookupResponse.Ready(cached.audioUrl, 0.0, isExpired = false)
                }

                // 2. Server cache endpoint lookup (/video-translation/cache) via Single-Flight Coordinator
                val cacheRes = VoxTranslationCoordinator.executeSingleFlight("cache|$cacheKey") {
                    com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient.requestTranslationCache(
                        videoUrl, 0.0, sourceLang, targetLang, false
                    )
                }

                if (cacheRes.isTransportError) {
                    // Invariant: Network/transport error must NEVER be treated as NotFound!
                    return ServerLookupResponse.Failed(
                        error = cacheRes.message ?: "Network error during server cache lookup",
                        retryable = true,
                        isServerError = true
                    )
                }

                if (cacheRes.status == com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient.STATUS_FINISHED) {
                    // Server cache indicates ready! Fetch translation with bypassCache=false to get audioUrl
                    val translateRes = VoxTranslationCoordinator.executeSingleFlight("translate|$cacheKey") {
                        com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient.requestTranslation(
                            videoUrl, 0.0, sourceLang, targetLang, "", false, null, true
                        )
                    }
                    if (translateRes.isTransportError) {
                        return ServerLookupResponse.Failed(
                            error = translateRes.message ?: "Network error fetching cached audio URL",
                            retryable = true,
                            isServerError = true
                        )
                    }
                    if (translateRes.isReady) {
                        return ServerLookupResponse.Ready(translateRes.audioUrl, 0.0, isExpired = false)
                    }
                    if (translateRes.isWaiting) {
                        val jobId = if (!translateRes.translationId.isNullOrBlank()) translateRes.translationId else videoId
                        return ServerLookupResponse.Processing(jobId, translateRes.remainingTime, -1)
                    }
                } else if (cacheRes.isWaiting) {
                    // Translation is already being processed on the server!
                    val jobId = if (!cacheRes.translationId.isNullOrBlank()) cacheRes.translationId else videoId
                    return ServerLookupResponse.Processing(jobId, cacheRes.remainingTime, -1)
                }

                // 3. Translate endpoint query with bypassCache=false before declaring NotFound
                val directRes = VoxTranslationCoordinator.executeSingleFlight("translate|$cacheKey") {
                    com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient.requestTranslation(
                        videoUrl, 0.0, sourceLang, targetLang, "", false, null, true
                    )
                }

                if (directRes.isTransportError) {
                    return ServerLookupResponse.Failed(
                        error = directRes.message ?: "Network error during translation lookup",
                        retryable = true,
                        isServerError = true
                    )
                }
                if (directRes.isReady) {
                    return ServerLookupResponse.Ready(directRes.audioUrl, 0.0, isExpired = false)
                }
                if (directRes.isWaiting) {
                    val jobId = if (!directRes.translationId.isNullOrBlank()) directRes.translationId else videoId
                    return ServerLookupResponse.Processing(jobId, directRes.remainingTime, -1)
                }

                // Confirmed absence: safe to initiate new translation job
                return ServerLookupResponse.NotFound
            }

            override fun createTranslationJob(
                videoId: String,
                sourceLang: String,
                targetLang: String
            ): ServerCreateResponse {
                val videoUrl = VoxTranslationReusePolicy.normalizeVideoUrl(videoId)
                val key = VoxTranslationReusePolicy.buildCacheKey(videoUrl, sourceLang, targetLang, false)
                VoxTranslationSessionCache.registerPendingJob(videoId, videoUrl, sourceLang, targetLang, false)
                return ServerCreateResponse(key, "PROCESSING")
            }

            override fun refreshSignedUrl(
                videoId: String,
                expiredAudioUrl: String
            ): ServerRefreshResponse {
                val videoUrl = VoxTranslationReusePolicy.normalizeVideoUrl(videoId)
                try {
                    val res = com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient.requestTranslation(
                        videoUrl, 0.0, "", "ru", "", false, null, true
                    )
                    if (res != null && res.isReady) {
                        return ServerRefreshResponse(ok = true, refreshedAudioUrl = res.audioUrl)
                    }
                } catch (ignored: Exception) {}
                return ServerRefreshResponse(ok = false)
            }
        }
    }
}

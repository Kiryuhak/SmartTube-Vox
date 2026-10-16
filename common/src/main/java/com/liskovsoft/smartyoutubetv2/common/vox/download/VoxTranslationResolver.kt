package com.liskovsoft.smartyoutubetv2.common.vox.download

import android.content.Context
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApi
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotApiClient
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotTiming
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.SmartTubeYandexVotAudioSourceProvider
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotAudioSourceProvider
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotAudioTransfer
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotAudioUploadTransport
import com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationSessionCache
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager

/**
 * Метаданные разрешенного аудио-перевода (находятся исключительно в оперативной памяти).
 */
data class VoxResolvedTranslation(
    val url: String,
    val format: String = "audio/mp3",
    val remainingTimeSec: Int = 0
)

/**
 * Интерфейс резолвера перевода Яндекс VOT.
 */
interface VoxTranslationResolver {
    @Throws(VoxDownloadException::class)
    fun resolveTranslation(
        videoId: String,
        mode: VoxTranslationMode,
        isCancelled: () -> Boolean = { false },
        onWaitingProgress: ((remainingSeconds: Int) -> Unit)? = null
    ): VoxResolvedTranslation
}

/**
 * Реализация по умолчанию резолвера перевода через YandexVotApi.
 */
class DefaultVoxTranslationResolver(
    private val apiClient: YandexVotApi = YandexVotApi.DEFAULT,
    private val oauthTokenProvider: () -> String? = { null },
    private val isLivelyAuthorized: () -> Boolean = { !oauthTokenProvider().isNullOrBlank() },
    private val audioSourceProvider: YandexVotAudioSourceProvider? = SmartTubeYandexVotAudioSourceProvider { videoId, _ ->
        val service = YouTubeServiceManager.instance()?.mediaItemService
        if (videoId != null && service != null) service.getFormatInfo(videoId) else null
    },
    private val audioUploadTransport: YandexVotAudioUploadTransport = YandexVotAudioUploadTransport.DEFAULT,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) : VoxTranslationResolver {

    companion object {
        private const val MAX_NETWORK_RETRIES = 5
        private const val MAX_STALL_TIMEOUT_MS = 180_000L // 3 minutes without heartbeat
        private const val MAX_TOTAL_WAIT_MS = 20 * 60 * 1000L // 20 minutes max total translation generation
        private const val DEFAULT_TARGET_LANG = "ru"
    }

    @Throws(VoxDownloadException::class)
    override fun resolveTranslation(
        videoId: String,
        mode: VoxTranslationMode,
        isCancelled: () -> Boolean,
        onWaitingProgress: ((remainingSeconds: Int) -> Unit)?
    ): VoxResolvedTranslation {
        val useLiveVoices = mode == VoxTranslationMode.LIVELY
        val oauthToken = oauthTokenProvider()

        if (useLiveVoices && (oauthToken.isNullOrBlank() || !isLivelyAuthorized())) {
            throw VoxDownloadException(
                VoxDownloadErrorCode.AUTH_REQUIRED,
                "Yandex account authorization is required for Lively mode"
            )
        }

        val videoUrl = "https://www.youtube.com/watch?v=$videoId"

        // 0. Cache-First check (Memory & Persisted cache)
        try {
            val lookup = VoxTranslationSessionCache.lookup(
                videoId = videoId,
                rawUrl = videoUrl,
                sourceLang = null,
                targetLang = DEFAULT_TARGET_LANG,
                useLively = useLiveVoices
            )
            if (lookup is VoxTranslationSessionCache.LookupResult.Hit && lookup.record.audioUrl.isNotBlank()) {
                VoxUrlSecurityValidator.validateUrl(lookup.record.audioUrl)
                Log.i("DefaultVoxTranslationResolver", "Translation reused from cache for download: source=${lookup.source} videoId=$videoId")
                return VoxResolvedTranslation(
                    url = lookup.record.audioUrl,
                    format = "audio/mp3",
                    remainingTimeSec = 0
                )
            }
        } catch (e: Exception) {
            Log.w("DefaultVoxTranslationResolver", "Cache lookup for download failed: ${e.message}")
        }

        var isFirstRequest = true
        var audioUploaded = false
        var consecutiveNetworkFailures = 0
        val startTimeMs = System.currentTimeMillis()
        var lastHeartbeatMs = System.currentTimeMillis()

        while (true) {
            if (isCancelled()) {
                throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation cancelled")
            }

            if (System.currentTimeMillis() - startTimeMs > MAX_TOTAL_WAIT_MS) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.TIMEOUT,
                    "Timed out waiting for Yandex VOT translation to become ready (exceeded max duration)"
                )
            }

            if (System.currentTimeMillis() - lastHeartbeatMs > MAX_STALL_TIMEOUT_MS) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.PROCESSING_STALLED,
                    "Translation processing stalled: no progress heartbeat from Yandex VOT within ${MAX_STALL_TIMEOUT_MS / 1000}s"
                )
            }

            val result = try {
                val res = apiClient.requestTranslation(
                    videoUrl,
                    0.0, // duration
                    null, // sourceLang
                    DEFAULT_TARGET_LANG, // targetLang
                    null, // videoTitle
                    useLiveVoices,
                    oauthToken,
                    isFirstRequest
                )
                consecutiveNetworkFailures = 0
                lastHeartbeatMs = System.currentTimeMillis()
                res
            } catch (e: Exception) {
                consecutiveNetworkFailures++
                if (consecutiveNetworkFailures > MAX_NETWORK_RETRIES) {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.NETWORK_ERROR,
                        "Failed to communicate with Yandex VOT API after $consecutiveNetworkFailures attempts: ${e.message}",
                        e
                    )
                }
                Log.w("DefaultVoxTranslationResolver", "Network error polling Yandex VOT (attempt $consecutiveNetworkFailures/$MAX_NETWORK_RETRIES): ${e.message}")
                sleepWithCancellation(3000L, isCancelled)
                continue
            }

            isFirstRequest = false

            if (result == null) {
                consecutiveNetworkFailures++
                if (consecutiveNetworkFailures > MAX_NETWORK_RETRIES) {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE,
                        "Yandex VOT API returned null response repeatedly"
                    )
                }
                sleepWithCancellation(2000L, isCancelled)
                continue
            }

            when (result.status) {
                YandexVotApiClient.STATUS_FINISHED,
                YandexVotApiClient.STATUS_PART_CONTENT -> {
                    val url = result.audioUrl
                    if (!url.isNullOrBlank()) {
                        VoxUrlSecurityValidator.validateUrl(url)
                        try {
                            VoxTranslationSessionCache.storeReadyTranslation(
                                videoId = videoId,
                                rawUrl = videoUrl,
                                sourceLang = null,
                                targetLang = DEFAULT_TARGET_LANG,
                                audioUrl = url,
                                durationSec = 0.0,
                                useLively = useLiveVoices
                            )
                        } catch (_: Exception) {}
                        return VoxResolvedTranslation(
                            url = url,
                            format = "audio/mp3",
                            remainingTimeSec = 0
                        )
                    }
                    // Если URL еще не заполнен, переходим к ожиданию
                    val remaining = if (result.remainingTime > 0) result.remainingTime else 5
                    onWaitingProgress?.invoke(remaining)
                    sleepWithCancellation(3000L, isCancelled)
                }
                YandexVotApiClient.STATUS_AUDIO_REQUESTED -> {
                    val translationId = result.translationId
                    if (!translationId.isNullOrBlank() && audioSourceProvider != null && !audioUploaded) {
                        try {
                            val source = audioSourceProvider.getAudioSource(videoId, videoUrl)
                            if (source != null) {
                                val reader = audioSourceProvider.getStreamReader(source)
                                if (reader != null) {
                                    val transfer = YandexVotAudioTransfer()
                                    transfer.transfer(source, videoUrl, translationId, videoId, reader, audioUploadTransport, null)
                                    audioUploaded = true
                                    Log.i("DefaultVoxTranslationResolver", "Uploaded audio source for $videoId to Yandex VOT")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("DefaultVoxTranslationResolver", "Audio source upload error for $videoId: ${e.message}")
                        }
                    }

                    val remaining = if (result.remainingTime > 0) result.remainingTime else 5
                    onWaitingProgress?.invoke(remaining)

                    val delaySec = YandexVotTiming.pollDelaySeconds(remaining)
                    val sleepMs = (delaySec * 1000L).coerceIn(1000L, 10000L)
                    sleepWithCancellation(sleepMs, isCancelled)
                }
                YandexVotApiClient.STATUS_WAITING,
                YandexVotApiClient.STATUS_LONG_WAITING,
                YandexVotApiClient.STATUS_SESSION_REQUIRED -> {
                    val remaining = if (result.remainingTime > 0) result.remainingTime else 5
                    onWaitingProgress?.invoke(remaining)

                    val delaySec = YandexVotTiming.pollDelaySeconds(remaining)
                    val sleepMs = (delaySec * 1000L).coerceIn(1000L, 10000L)
                    sleepWithCancellation(sleepMs, isCancelled)
                }
                YandexVotApiClient.STATUS_FAILED -> {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE,
                        "Yandex VOT returned FAILED for videoId=$videoId: ${result.message}"
                    )
                }
                else -> {
                    throw VoxDownloadException(
                        VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE,
                        "Unexpected translation status: ${result.status} (${result.message})"
                    )
                }
            }
        }
    }

    private fun sleepWithCancellation(sleepMs: Long, isCancelled: () -> Boolean) {
        val stepMs = 250L
        var waited = 0L
        while (waited < sleepMs) {
            if (isCancelled()) {
                throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation cancelled")
            }
            try {
                sleeper(Math.min(stepMs, sleepMs - waited))
            } catch (e: InterruptedException) {
                throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation interrupted")
            }
            waited += stepMs
        }
    }
}

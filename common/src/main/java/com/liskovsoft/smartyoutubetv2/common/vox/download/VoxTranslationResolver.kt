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
    private val audioUploadTransport: YandexVotAudioUploadTransport = YandexVotAudioUploadTransport.DEFAULT
) : VoxTranslationResolver {

    companion object {
        private const val MAX_POLL_ATTEMPTS = 60
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
        var attempts = 0
        var isFirstRequest = true
        var audioUploaded = false

        while (attempts < MAX_POLL_ATTEMPTS) {
            if (isCancelled()) {
                throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation cancelled")
            }

            val result = try {
                apiClient.requestTranslation(
                    videoUrl,
                    0.0, // duration
                    null, // sourceLang
                    DEFAULT_TARGET_LANG, // targetLang
                    null, // videoTitle
                    useLiveVoices,
                    oauthToken,
                    isFirstRequest
                )
            } catch (e: Exception) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE,
                    "Failed to communicate with Yandex VOT API: ${e.message}",
                    e
                )
            }

            isFirstRequest = false

            if (result == null) {
                throw VoxDownloadException(
                    VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE,
                    "Yandex VOT API returned null response"
                )
            }

            when (result.status) {
                YandexVotApiClient.STATUS_FINISHED,
                YandexVotApiClient.STATUS_PART_CONTENT -> {
                    val url = result.audioUrl
                    if (!url.isNullOrBlank()) {
                        VoxUrlSecurityValidator.validateUrl(url)
                        return VoxResolvedTranslation(
                            url = url,
                            format = "audio/mp3",
                            remainingTimeSec = 0
                        )
                    }
                    // Если URL еще не заполнен, переходим к ожиданию
                    val remaining = if (result.remainingTime > 0) result.remainingTime else 5
                    onWaitingProgress?.invoke(remaining)
                    val sleepMs = 3000L
                    val startWait = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startWait < sleepMs) {
                        if (isCancelled()) {
                            throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation cancelled")
                        }
                        try { Thread.sleep(250) } catch (e: InterruptedException) {
                            throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation interrupted")
                        }
                    }
                    attempts++
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

                    val startWait = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startWait < sleepMs) {
                        if (isCancelled()) {
                            throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation cancelled")
                        }
                        try {
                            Thread.sleep(250)
                        } catch (e: InterruptedException) {
                            throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation interrupted")
                        }
                    }
                    attempts++
                }
                YandexVotApiClient.STATUS_WAITING,
                YandexVotApiClient.STATUS_LONG_WAITING,
                YandexVotApiClient.STATUS_SESSION_REQUIRED -> {
                    val remaining = if (result.remainingTime > 0) result.remainingTime else 5
                    onWaitingProgress?.invoke(remaining)

                    val delaySec = YandexVotTiming.pollDelaySeconds(remaining)
                    val sleepMs = (delaySec * 1000L).coerceIn(1000L, 10000L)

                    val startWait = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startWait < sleepMs) {
                        if (isCancelled()) {
                            throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation cancelled")
                        }
                        try {
                            Thread.sleep(250)
                        } catch (e: InterruptedException) {
                            throw VoxDownloadException(VoxDownloadErrorCode.CANCELLED, "Translation interrupted")
                        }
                    }
                    attempts++
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

        throw VoxDownloadException(
            VoxDownloadErrorCode.TRANSLATION_UNAVAILABLE,
            "Timed out waiting for Yandex VOT translation to become ready"
        )
    }
}

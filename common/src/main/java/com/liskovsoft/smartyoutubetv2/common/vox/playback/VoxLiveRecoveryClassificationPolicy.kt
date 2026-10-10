package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Классификация причин зависаний и сбоев прямого эфира.
 * Строгое правило: UI говорит о DNS только при доказанном DNS-событии.
 */
object VoxLiveRecoveryClassificationPolicy {

    fun isDnsFailure(error: Throwable?): Boolean {
        var curr = error
        while (curr != null) {
            if (curr is UnknownHostException) return true
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("unable to resolve host") || msg.contains("no address associated with hostname")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    fun isNetworkFailure(error: Throwable?): Boolean {
        var curr = error
        while (curr != null) {
            if (curr is UnknownHostException || curr is ConnectException ||
                curr is SocketTimeoutException || curr is SSLException) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    fun classify(
        error: Throwable?,
        httpStatusCode: Int = 0,
        liveOffsetMs: Long = 0L,
        bufferedDurationMs: Long = 0L,
        manifestAgeMs: Long = 0L,
        isBehindLiveWindow: Boolean = false,
        isTargetExpired: Boolean = false
    ): String {
        return when {
            isBehindLiveWindow -> VoxLogCode.BEHIND_LIVE_WINDOW
            isTargetExpired -> VoxLogCode.SEEK_TARGET_EXPIRED
            isDnsFailure(error) -> VoxLogCode.NETWORK_DNS
            error is ConnectException -> VoxLogCode.NETWORK_CONNECT
            error is SSLException -> VoxLogCode.NETWORK_TLS
            error is SocketTimeoutException || httpStatusCode == 408 -> VoxLogCode.NETWORK_TIMEOUT
            httpStatusCode == 404 -> VoxLogCode.SEGMENT_NOT_FOUND
            httpStatusCode in 500..599 || (manifestAgeMs > 30_000L && httpStatusCode > 0) -> VoxLogCode.MANIFEST_STALE
            liveOffsetMs in 1..3_000L && bufferedDurationMs < 1_000L -> VoxLogCode.BUFFER_UNDERRUN
            error != null -> VoxLogCode.SOURCE_STALE
            bufferedDurationMs == 0L && liveOffsetMs > 0L -> VoxLogCode.BUFFER_UNDERRUN
            else -> VoxLogCode.UNKNOWN_LIVE_STALL
        }
    }

    fun resolveUserFacingMessageRes(classification: String): Int {
        return when (classification) {
            VoxLogCode.NETWORK_DNS -> R.string.vox_fixing_dns
            VoxLogCode.NETWORK_CONNECT,
            VoxLogCode.NETWORK_TLS,
            VoxLogCode.NETWORK_TIMEOUT,
            VoxLogCode.NETWORK_ERROR -> R.string.vox_fixing_connection
            VoxLogCode.BEHIND_LIVE_WINDOW,
            VoxLogCode.LIVE_WINDOW_EXPIRED,
            VoxLogCode.SEEK_TARGET_EXPIRED -> R.string.vox_live_return_to_live_prompt
            else -> R.string.vox_fixing_playback
        }
    }
}

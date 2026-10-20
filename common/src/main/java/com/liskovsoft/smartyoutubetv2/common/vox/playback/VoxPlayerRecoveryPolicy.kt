package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.google.android.exoplayer2.upstream.HttpDataSource
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class VoxPlayerRecoveryState {
    IDLE,
    BUFFERING,
    RECOVERING_SOURCE,
    RECOVERING_NETWORK,
    RECOVERED,
    FAILED
}

/**
 * Bounded recovery policy for VOD & generic media playback.
 * Enforces:
 * 1. Meaningful progress definition: bufferedPosition advancement, READY state, or first frame.
 * 2. Non-progress rejection: heartbeats, spinners, unchanged buffer, repeating BUFFERING.
 * 3. Bounded recovery: max 2 source recoveries, max 1 engine switch per session (no engine ping-pong).
 * 4. Stale/forbidden source classification without leaking signed URLs into telemetry.
 */
object VoxPlayerRecoveryPolicy {
    const val MAX_SOURCE_RECOVERIES = 2
    const val MAX_ENGINE_SWITCHES = 1
    const val STALL_WATCHDOG_TIMEOUT_MS = 4_000L
    const val MEANINGFUL_BUFFER_DELTA_MS = 1_000L
    const val POSITION_RESTORE_SAFE_OFFSET_MS = 2_000L

    fun isMeaningfulProgress(
        prevBufferedMs: Long,
        currentBufferedMs: Long,
        isPlaybackReady: Boolean = false
    ): Boolean {
        if (isPlaybackReady) return true
        return (currentBufferedMs - prevBufferedMs) >= MEANINGFUL_BUFFER_DELTA_MS
    }

    fun isStaleOrForbiddenSource(error: Throwable?): Boolean {
        if (error == null) return false
        var curr: Throwable? = error
        while (curr != null) {
            if (curr is HttpDataSource.InvalidResponseCodeException && curr.responseCode == 403) {
                return true
            }
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("response code: 403") || msg.contains("code: 403") || msg.contains("403 forbidden")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    fun isDnsFailure(error: Throwable?): Boolean {
        if (error == null) return false
        var curr: Throwable? = error
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

    fun isNetworkTransportError(error: Throwable?): Boolean {
        if (error == null) return false
        var curr: Throwable? = error
        while (curr != null) {
            if (curr is UnknownHostException || curr is ConnectException ||
                curr is SocketTimeoutException || curr is SSLException ||
                curr is HttpDataSource.HttpDataSourceException) {
                return true
            }
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("unable to connect") || msg.contains("connection refused") ||
                msg.contains("timed out") || msg.contains("timeout") || msg.contains("socket")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.playback

import com.google.android.exoplayer2.upstream.HttpDataSource
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class VoxPlayerRecoveryState {
    IDLE,
    BUFFERING,
    RECOVERING_SOURCE,
    SOURCE_REFRESHED,
    RECOVERING_NETWORK,
    RECOVERING_QUALITY,
    RECOVERY_VERIFYING,
    RECOVERED,
    FAILED,
    RECOVERY_EXHAUSTED
}

/**
 * Bounded recovery policy for VOD & generic media playback.
 * Enforces:
 * 1. Meaningful progress definition: bufferedPosition advancement, READY state, or first frame.
 * 2. Non-progress rejection: heartbeats, spinners, unchanged buffer, repeating BUFFERING.
 * 3. Bounded recovery: max 2 source recoveries, max 1 engine switch per session (no engine ping-pong).
 * 4. Stale/forbidden source classification without leaking signed URLs into telemetry.
 * 5. DNS-aware error classification distinguishing DNS resolution failure, connection timeouts,
 *    read timeouts, connection resets, and HTTP errors without falsely blaming DoH or downgrading quality.
 */
object VoxPlayerRecoveryPolicy {
    const val MAX_SOURCE_RECOVERIES = 2
    const val MAX_ENGINE_SWITCHES = 1
    const val MAX_QUALITY_FALLBACKS = 1
    const val STALL_WATCHDOG_TIMEOUT_MS = 4_000L
    const val MEANINGFUL_BUFFER_DELTA_MS = 1_000L
    const val POSITION_RESTORE_SAFE_OFFSET_MS = 2_000L
    const val VERIFICATION_WINDOW_MS = 4_000L
    const val VERIFICATION_MIN_PROGRESS_MS = 2_000L

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

    @JvmStatic
    fun isStaleOrDeadUrl(error: Throwable?): Boolean {
        if (error == null) return false
        var curr: Throwable? = error
        while (curr != null) {
            if (curr is HttpDataSource.InvalidResponseCodeException) {
                val code = curr.responseCode
                if (code == 403 || code == 404 || code == 410) {
                    return true
                }
            }
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("code: 403") || msg.contains("403 forbidden") ||
                msg.contains("code: 404") || msg.contains("404 not found") ||
                msg.contains("code: 410") || msg.contains("410 gone")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    @JvmStatic
    fun isDnsFailure(error: Throwable?): Boolean {
        if (error == null) return false
        var curr: Throwable? = error
        while (curr != null) {
            if (curr is UnknownHostException) return true
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("unable to resolve host") ||
                msg.contains("no address associated with hostname") ||
                msg.contains("name or service not known") ||
                msg.contains("dns resolution failed") ||
                msg.contains("gai_error")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    @JvmStatic
    fun isDnsFailureReason(reason: String?): Boolean {
        if (reason == null) return false
        val r = reason.uppercase()
        return r.contains("DNS") || r.contains("UNKNOWN_HOST")
    }

    @JvmStatic
    fun isConnectionReset(error: Throwable?): Boolean {
        if (error == null) return false
        var curr: Throwable? = error
        while (curr != null) {
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("connection reset") ||
                msg.contains("econnreset") ||
                msg.contains("software caused connection abort") ||
                msg.contains("broken pipe")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    @JvmStatic
    fun isConnectTimeout(error: Throwable?): Boolean {
        if (error == null) return false
        var curr: Throwable? = error
        while (curr != null) {
            if (curr is ConnectException) return true
            if (curr is HttpDataSource.HttpDataSourceException &&
                curr.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN &&
                (curr.cause is SocketTimeoutException || curr.message?.lowercase()?.contains("timeout") == true)) {
                return true
            }
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("connect timed out") || msg.contains("failed to connect") ||
                msg.contains("connection refused")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    @JvmStatic
    fun isReadTimeout(error: Throwable?): Boolean {
        if (error == null) return false
        var curr: Throwable? = error
        while (curr != null) {
            if (curr is SocketTimeoutException) {
                val msg = curr.message?.lowercase() ?: ""
                if (!msg.contains("connect")) return true
            }
            if (curr is HttpDataSource.HttpDataSourceException &&
                curr.type == HttpDataSource.HttpDataSourceException.TYPE_READ) {
                return true
            }
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("read timed out") || msg.contains("timeout reading")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    @JvmStatic
    fun isNetworkTransportError(error: Throwable?): Boolean {
        if (error == null) return false
        var curr: Throwable? = error
        while (curr != null) {
            if (curr is UnknownHostException || curr is ConnectException ||
                curr is SocketTimeoutException || curr is SocketException ||
                curr is SSLException || curr is HttpDataSource.HttpDataSourceException) {
                return true
            }
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("unable to connect") || msg.contains("connection refused") ||
                msg.contains("timed out") || msg.contains("timeout") || msg.contains("socket") ||
                msg.contains("connection reset") || msg.contains("unable to resolve host")) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    @JvmStatic
    fun classifyPlayerSourceError(error: Throwable?, isLocalSource: Boolean): String {
        if (error == null) return VoxLogCode.PLAYER_SOURCE_ERROR

        if (isDnsFailure(error)) {
            return VoxLogCode.PLAYER_DNS_RESOLUTION_ERROR
        }
        if (isConnectionReset(error)) {
            return VoxLogCode.PLAYER_CONNECTION_RESET
        }
        if (isConnectTimeout(error)) {
            return VoxLogCode.PLAYER_CONNECT_TIMEOUT
        }
        if (isReadTimeout(error)) {
            return VoxLogCode.PLAYER_READ_TIMEOUT
        }
        if (error is HttpDataSource.InvalidResponseCodeException ||
            error is HttpDataSource.HttpDataSourceException ||
            isStaleOrForbiddenSource(error)) {
            return VoxLogCode.PLAYER_HTTP_ERROR
        }
        if (isLocalSource) {
            return VoxLogCode.PLAYER_LOCAL_SOURCE_ERROR
        }
        return VoxLogCode.PLAYER_SOURCE_ERROR
    }

    @JvmStatic
    fun classifyNetworkFailureType(error: Throwable?): String {
        if (error == null) return "UNKNOWN"
        if (isDnsFailure(error)) return "DNS_RESOLUTION_FAILED"
        if (isConnectionReset(error)) return "CONNECTION_RESET"
        if (isConnectTimeout(error)) return "CONNECT_TIMEOUT"
        if (isReadTimeout(error)) return "READ_TIMEOUT"
        if (isStaleOrForbiddenSource(error)) return "HTTP_403_FORBIDDEN"
        if (error is HttpDataSource.InvalidResponseCodeException) return "HTTP_" + error.responseCode
        if (error is HttpDataSource.HttpDataSourceException) return "HTTP_DATA_SOURCE_ERROR"
        if (isNetworkTransportError(error)) return "NETWORK_TRANSPORT_ERROR"
        return "UNKNOWN"
    }
}

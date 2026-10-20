/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pre-playback Audio Validation and Bounded Probe Pipeline.
 *
 * Verifies that the audio URL returned by the provider is reachable and readable BEFORE
 * handing it to ExoPlayer. Prevents ~99% progress stalls caused by:
 * 1. Expired AWS SigV4 / Yandex S3 signed URLs (HTTP 403);
 * 2. CDN propagation delays on newly synthesized audio (HTTP 404/202);
 * 3. ISP / network blocking of direct S3 buckets (switching to proxy worker boundedly).
 */
object VoxAudioValidator {
    private const val TAG = "VoxAudioValidator"
    const val DEFAULT_PROBE_TIMEOUT_MS = 4000
    const val MAX_CDN_RETRIES = 3
    const val CDN_RETRY_INTERVAL_MS = 1000L

    @Volatile
    var isValidationBypassedForTesting: Boolean = false

    enum class ProbeStatus {
        SUCCESS,
        CDN_PROPAGATING,
        EXPIRED_OR_FORBIDDEN,
        ROUTE_UNREACHABLE,
        INVALID_URL
    }

    data class ProbeResult(
        val status: ProbeStatus,
        val httpCode: Int = -1,
        val url: String,
        val errorMessage: String? = null
    )

    data class ValidationResolution(
        val finalAudioUrl: String,
        val wasFallbackRouteUsed: Boolean,
        val cdnRetriesPerformed: Int,
        val probeDurationMs: Long
    )

    interface Callback {
        fun onValidated(resolution: ValidationResolution)
        fun onValidationFailed(category: String, message: String)
    }

    /**
     * Synchronously probes an audio URL with a lightweight byte-range GET (bytes=0-0) or HEAD.
     */
    fun probeAudioUrl(audioUrl: String?, timeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS): ProbeResult {
        if (!VoxTranslationStateMachine.isValidAudioUrl(audioUrl)) {
            return ProbeResult(ProbeStatus.INVALID_URL, url = audioUrl ?: "")
        }
        val safeUrl = audioUrl!!.trim()

        var connection: HttpURLConnection? = null
        return try {
            val urlObj = URL(safeUrl)
            connection = (urlObj.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", "SmartTubeVOT")
                setRequestProperty("Accept", "*/*")
            }

            val responseCode = connection.responseCode
            when (responseCode) {
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_PARTIAL -> {
                    ProbeResult(ProbeStatus.SUCCESS, responseCode, safeUrl)
                }
                HttpURLConnection.HTTP_NOT_FOUND,
                202 -> { // HTTP 202 Accepted or 404 Not Found (synthesis complete, CDN cache propagating)
                    ProbeResult(ProbeStatus.CDN_PROPAGATING, responseCode, safeUrl, "CDN propagation delay ($responseCode)")
                }
                HttpURLConnection.HTTP_FORBIDDEN,
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    ProbeResult(ProbeStatus.EXPIRED_OR_FORBIDDEN, responseCode, safeUrl, "Forbidden or expired ($responseCode)")
                }
                else -> {
                    ProbeResult(ProbeStatus.ROUTE_UNREACHABLE, responseCode, safeUrl, "HTTP $responseCode")
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            ProbeResult(ProbeStatus.ROUTE_UNREACHABLE, -1, safeUrl, msg)
        } finally {
            try {
                connection?.inputStream?.close()
            } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    /**
     * Validates and resolves the optimal audio URL on IO scheduler with bounded fallback and CDN retry.
     */
    fun validateAndResolveAsync(
        initialAudioUrl: String,
        proxyWorkerHost: String? = null,
        callback: Callback
    ) {
        if (isValidationBypassedForTesting) {
            callback.onValidated(ValidationResolution(initialAudioUrl, false, 0, 0L))
            return
        }

        val startMs = System.currentTimeMillis()
        Single.fromCallable {
            executeValidationAndResolution(initialAudioUrl, proxyWorkerHost, startMs)
        }
        .subscribeOn(Schedulers.io())
        .subscribe(
            { resolution -> callback.onValidated(resolution) },
            { error ->
                val category = if (error is ExpiredUrlException) "expired_url" else "probe_failed"
                callback.onValidationFailed(category, error.message ?: "Audio validation failed")
            }
        )
    }

    private class ExpiredUrlException(message: String) : IOException(message)

    @Throws(IOException::class)
    private fun executeValidationAndResolution(
        initialAudioUrl: String,
        proxyWorkerHost: String?,
        startMs: Long
    ): ValidationResolution {
        // Step 1: Pre-check AWS SigV4 / Yandex expiry timestamp if present in URL
        if (isUrlTimestampExpired(initialAudioUrl)) {
            val msg = "Audio URL signature expired before playback"
            VoxSafeLogger.w(
                VoxLogCategory.TRANSLATION,
                VoxLogCode.TRANSLATION_CACHED_URL_EXPIRED,
                msg,
                mapOf("url" to initialAudioUrl)
            )
            throw ExpiredUrlException(msg)
        }

        // Step 2: Probe primary route
        var probe = probeAudioUrl(initialAudioUrl)
        var cdnRetries = 0

        // Handle CDN propagation delay (404/202 right after synthesis)
        while (probe.status == ProbeStatus.CDN_PROPAGATING && cdnRetries < MAX_CDN_RETRIES) {
            cdnRetries++
            Log.d(TAG, "Audio CDN propagation delay on primary route, retry $cdnRetries/$MAX_CDN_RETRIES in ${CDN_RETRY_INTERVAL_MS}ms")
            try {
                Thread.sleep(CDN_RETRY_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            probe = probeAudioUrl(initialAudioUrl)
        }

        if (probe.status == ProbeStatus.SUCCESS) {
            val elapsed = System.currentTimeMillis() - startMs
            return ValidationResolution(initialAudioUrl, false, cdnRetries, elapsed)
        }

        // Step 3: Primary route failed or forbidden -> attempt 1-step route fallback (Direct <-> Proxy)
        val alternateRouteUrl = VoxAudioProxyFallback.getAlternateRouteUrl(initialAudioUrl, proxyWorkerHost)
        if (!alternateRouteUrl.isNullOrBlank() && alternateRouteUrl != initialAudioUrl) {
            Log.w(TAG, "Primary audio route probe failed (${probe.status}), testing alternate route: $alternateRouteUrl")
            var altProbe = probeAudioUrl(alternateRouteUrl)
            var altCdnRetries = 0

            while (altProbe.status == ProbeStatus.CDN_PROPAGATING && altCdnRetries < MAX_CDN_RETRIES) {
                altCdnRetries++
                try {
                    Thread.sleep(CDN_RETRY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                altProbe = probeAudioUrl(alternateRouteUrl)
            }

            if (altProbe.status == ProbeStatus.SUCCESS) {
                val elapsed = System.currentTimeMillis() - startMs
                Log.i(TAG, "Alternate audio route verified successfully: $alternateRouteUrl")
                return ValidationResolution(alternateRouteUrl, true, cdnRetries + altCdnRetries, elapsed)
            }
        }

        // Step 4: Both routes exhausted or failed
        val failureMsg = "Audio route validation failed: primary=${probe.status} (code=${probe.httpCode})"
        Log.e(TAG, failureMsg)
        if (probe.status == ProbeStatus.EXPIRED_OR_FORBIDDEN) {
            throw ExpiredUrlException(failureMsg)
        }
        throw IOException(failureMsg)
    }

    private fun isUrlTimestampExpired(url: String): Boolean {
        return try {
            if (!url.contains("?")) return false
            val query = url.substringAfter("?")
            var amzDateMs: Long? = null
            var amzExpiresSec: Long? = null
            for (param in query.split("&")) {
                val parts = param.split("=")
                if (parts.size == 2) {
                    val key = parts[0].lowercase()
                    if (key == "expires" || key == "expire") {
                        val epochSec = parts[1].toLongOrNull()
                        if (epochSec != null && epochSec in 1_000_000_000L..5_000_000_000L) {
                            return System.currentTimeMillis() + 15_000L >= epochSec * 1000L
                        }
                    } else if (key == "x-amz-date") {
                        amzDateMs = parseAmzDate(parts[1])
                    } else if (key == "x-amz-expires") {
                        amzExpiresSec = parts[1].toLongOrNull()
                    }
                }
            }
            if (amzDateMs != null && amzExpiresSec != null && amzExpiresSec > 0) {
                val expiryMs = amzDateMs + (amzExpiresSec * 1000L)
                return System.currentTimeMillis() + 15_000L >= expiryMs
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun getUrlValidityRemainingSec(url: String?): Long {
        if (url == null || !url.contains("?")) return -1L
        return try {
            val query = url.substringAfter("?")
            var amzDateMs: Long? = null
            var amzExpiresSec: Long? = null
            for (param in query.split("&")) {
                val parts = param.split("=")
                if (parts.size == 2) {
                    val key = parts[0].lowercase()
                    if (key == "expires" || key == "expire") {
                        val epochSec = parts[1].toLongOrNull()
                        if (epochSec != null && epochSec in 1_000_000_000L..5_000_000_000L) {
                            val remaining = (epochSec * 1000L - System.currentTimeMillis()) / 1000L
                            return Math.max(0L, remaining)
                        }
                    } else if (key == "x-amz-date") {
                        amzDateMs = parseAmzDate(parts[1])
                    } else if (key == "x-amz-expires") {
                        amzExpiresSec = parts[1].toLongOrNull()
                    }
                }
            }
            if (amzDateMs != null && amzExpiresSec != null && amzExpiresSec > 0) {
                val expiryMs = amzDateMs + (amzExpiresSec * 1000L)
                val remaining = (expiryMs - System.currentTimeMillis()) / 1000L
                return Math.max(0L, remaining)
            }
            -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun parseAmzDate(dateStr: String): Long? {
        return try {
            val format = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(dateStr)?.time
        } catch (_: Exception) {
            null
        }
    }
}

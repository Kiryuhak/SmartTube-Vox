/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vox.translation

/**
 * Proxy and direct fallback URL rewriter matching VOT userscript 1.11.17 reference.
 *
 * Reference:
 * - AUDIO_SOURCE_PREFIX = "https://vtrans.s3-private.mds.yandex.net/tts/prod/"
 * - AUDIO_PROXY_PATH_PREFIX = "/video-translation/audio-proxy/"
 * - DEFAULT_PROXY_WORKER_HOST = "vot-worker.eu.cc"
 * - 1-step bounded fallback (PRIMARY -> FALLBACK -> EXHAUSTED) prevents infinite switching loops.
 */
object VoxAudioProxyFallback {
    const val AUDIO_SOURCE_PREFIX = "https://vtrans.s3-private.mds.yandex.net/tts/prod/"
    const val AUDIO_PROXY_PATH_PREFIX = "/video-translation/audio-proxy/"
    const val DEFAULT_PROXY_WORKER_HOST = "vot-worker.eu.cc"

    fun resolveProxyWorkerHost(host: String?): String {
        return if (!host.isNullOrBlank()) host.trim() else DEFAULT_PROXY_WORKER_HOST
    }

    /**
     * Checks if given URL is a proxified Yandex translation audio URL.
     */
    fun isProxified(audioUrl: String?): Boolean {
        if (audioUrl.isNullOrBlank()) return false
        return audioUrl.contains(AUDIO_PROXY_PATH_PREFIX)
    }

    /**
     * Checks if given URL is a direct Yandex S3 translation audio URL.
     */
    fun isDirectS3(audioUrl: String?): Boolean {
        if (audioUrl.isNullOrBlank()) return false
        return audioUrl.startsWith(AUDIO_SOURCE_PREFIX)
    }

    /**
     * Rewrites direct Yandex S3 audio URL to proxified worker URL.
     */
    fun proxifyAudio(audioUrl: String?, proxyWorkerHost: String? = null): String {
        if (audioUrl.isNullOrBlank() || !audioUrl.startsWith(AUDIO_SOURCE_PREFIX)) {
            return audioUrl ?: ""
        }
        val host = resolveProxyWorkerHost(proxyWorkerHost)
        return audioUrl.replace(AUDIO_SOURCE_PREFIX, "https://$host$AUDIO_PROXY_PATH_PREFIX")
    }

    /**
     * Rewrites proxified worker URL back to direct Yandex S3 audio URL.
     */
    fun unproxifyAudio(audioUrl: String?): String {
        if (audioUrl.isNullOrBlank()) return ""
        val proxyIndex = audioUrl.indexOf(AUDIO_PROXY_PATH_PREFIX)
        if (proxyIndex < 0) return audioUrl
        val subPath = audioUrl.substring(proxyIndex + AUDIO_PROXY_PATH_PREFIX.length)
        return "$AUDIO_SOURCE_PREFIX$subPath"
    }

    /**
     * Returns alternate route URL for bounded 1-step fallback.
     * If currently proxified -> returns direct S3 URL.
     * If currently direct S3 -> returns proxified URL.
     * Otherwise returns null.
     */
    fun getAlternateRouteUrl(audioUrl: String?, proxyWorkerHost: String? = null): String? {
        if (audioUrl.isNullOrBlank()) return null
        if (isProxified(audioUrl)) {
            val direct = unproxifyAudio(audioUrl)
            return if (direct != audioUrl) direct else null
        }
        if (isDirectS3(audioUrl)) {
            val proxied = proxifyAudio(audioUrl, proxyWorkerHost)
            return if (proxied != audioUrl) proxied else null
        }
        return null
    }

    /**
     * Bounded 1-step route fallback state machine.
     * Guarantees at most ONE route fallback attempt before reporting terminal failure.
     */
    class BoundedRouteTracker(
        val initialUrl: String,
        val proxyWorkerHost: String? = null
    ) {
        enum class Stage {
            PRIMARY,
            FALLBACK,
            EXHAUSTED
        }

        var stage: Stage = Stage.PRIMARY
            private set
        var currentUrl: String = initialUrl
            private set
        var fallbackAttemptCount: Int = 0
            private set

        fun canAttemptFallback(): Boolean {
            if (stage != Stage.PRIMARY) return false
            val alternate = getAlternateRouteUrl(currentUrl, proxyWorkerHost)
            return !alternate.isNullOrBlank() && alternate != currentUrl
        }

        fun trySwitchToFallback(): String? {
            if (!canAttemptFallback()) {
                stage = Stage.EXHAUSTED
                return null
            }
            val alternate = getAlternateRouteUrl(currentUrl, proxyWorkerHost) ?: return null
            stage = Stage.FALLBACK
            fallbackAttemptCount++
            currentUrl = alternate
            return alternate
        }

        fun markExhausted() {
            stage = Stage.EXHAUSTED
        }

        fun reset(newUrl: String) {
            stage = Stage.PRIMARY
            currentUrl = newUrl
            fallbackAttemptCount = 0
        }
    }
}

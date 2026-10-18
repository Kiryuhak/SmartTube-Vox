/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxAudioProxyFallbackTest {

    private val directS3Url = "https://vtrans.s3-private.mds.yandex.net/tts/prod/audio_12345.mp3"
    private val proxiedUrl = "https://vot-worker.eu.cc/video-translation/audio-proxy/audio_12345.mp3"
    private val customWorkerUrl = "https://custom-proxy.example.com/video-translation/audio-proxy/audio_12345.mp3"

    @Test
    fun test01_isProxified_identifiesCorrectly() {
        assertTrue(VoxAudioProxyFallback.isProxified(proxiedUrl))
        assertTrue(VoxAudioProxyFallback.isProxified(customWorkerUrl))
        assertFalse(VoxAudioProxyFallback.isProxified(directS3Url))
        assertFalse(VoxAudioProxyFallback.isProxified(null))
        assertFalse(VoxAudioProxyFallback.isProxified(""))
    }

    @Test
    fun test02_isDirectS3_identifiesCorrectly() {
        assertTrue(VoxAudioProxyFallback.isDirectS3(directS3Url))
        assertFalse(VoxAudioProxyFallback.isDirectS3(proxiedUrl))
        assertFalse(VoxAudioProxyFallback.isDirectS3(null))
        assertFalse(VoxAudioProxyFallback.isDirectS3(""))
    }

    @Test
    fun test03_proxifyAudio_rewritesDirectToProxy() {
        val rewritten = VoxAudioProxyFallback.proxifyAudio(directS3Url)
        assertEquals(proxiedUrl, rewritten)

        val customRewritten = VoxAudioProxyFallback.proxifyAudio(directS3Url, "custom-proxy.example.com")
        assertEquals(customWorkerUrl, customRewritten)
    }

    @Test
    fun test04_unproxifyAudio_rewritesProxyToDirect() {
        val direct = VoxAudioProxyFallback.unproxifyAudio(proxiedUrl)
        assertEquals(directS3Url, direct)

        val customDirect = VoxAudioProxyFallback.unproxifyAudio(customWorkerUrl)
        assertEquals(directS3Url, customDirect)
    }

    @Test
    fun test05_getAlternateRouteUrl_switchesRoute() {
        // Direct -> Proxied
        val altFromDirect = VoxAudioProxyFallback.getAlternateRouteUrl(directS3Url)
        assertEquals(proxiedUrl, altFromDirect)

        // Proxied -> Direct
        val altFromProxy = VoxAudioProxyFallback.getAlternateRouteUrl(proxiedUrl)
        assertEquals(directS3Url, altFromProxy)

        // Unknown host -> null
        val altUnknown = VoxAudioProxyFallback.getAlternateRouteUrl("https://example.com/audio.mp3")
        assertNull(altUnknown)
    }

    @Test
    fun test06_boundedRouteTracker_preventsInfiniteLoop() {
        val tracker = VoxAudioProxyFallback.BoundedRouteTracker(proxiedUrl)
        assertEquals(VoxAudioProxyFallback.BoundedRouteTracker.Stage.PRIMARY, tracker.stage)
        assertTrue(tracker.canAttemptFallback())

        // 1st failure -> switches to fallback
        val fallbackUrl = tracker.trySwitchToFallback()
        assertNotNull(fallbackUrl)
        assertEquals(directS3Url, fallbackUrl)
        assertEquals(VoxAudioProxyFallback.BoundedRouteTracker.Stage.FALLBACK, tracker.stage)
        assertEquals(1, tracker.fallbackAttemptCount)

        // Cannot attempt another fallback -> bounded to 1 step
        assertFalse(tracker.canAttemptFallback())
        val exhaustedUrl = tracker.trySwitchToFallback()
        assertNull(exhaustedUrl)
        assertEquals(VoxAudioProxyFallback.BoundedRouteTracker.Stage.EXHAUSTED, tracker.stage)
    }

    @Test
    fun test07_boundedRouteTracker_resetOnNewSession() {
        val tracker = VoxAudioProxyFallback.BoundedRouteTracker(proxiedUrl)
        tracker.trySwitchToFallback()
        tracker.trySwitchToFallback()
        assertEquals(VoxAudioProxyFallback.BoundedRouteTracker.Stage.EXHAUSTED, tracker.stage)

        val newUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/new.mp3"
        tracker.reset(newUrl)
        assertEquals(VoxAudioProxyFallback.BoundedRouteTracker.Stage.PRIMARY, tracker.stage)
        assertEquals(newUrl, tracker.currentUrl)
        assertEquals(0, tracker.fallbackAttemptCount)
        assertTrue(tracker.canAttemptFallback())
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxDownloadNetworkForensicsTest {

    @Test
    fun testHostSanitizerStripsSensitiveDataAndExtractsCleanHost() {
        val sensitiveUrl = "https://rr3---sn-4g5ednsl.googlevideo.com/videoplayback?expire=1712345678&ei=abc&ip=1.2.3.4&id=xyz&itag=22&source=youtube&requiressl=yes&ratebypass=yes&mime=video/mp4&gir=yes&clen=12345678&dur=120.5&lmt=1234567890&signature=secret_token_12345"
        val cleanHost = VoxHostSanitizer.sanitizeHost(sensitiveUrl)
        assertEquals("*.googlevideo.com", cleanHost)
        assertFalse(cleanHost.contains("secret_token"))
        assertFalse(cleanHost.contains("videoplayback"))
        assertFalse(cleanHost.contains("1.2.3.4"))

        val portUrl = "https://vtrans.yandex.net:8443/download/test.mp3?token=secret"
        val cleanPortHost = VoxHostSanitizer.sanitizeHost(portUrl)
        assertEquals("*.yandex.net", cleanPortHost)

        val invalidUrl = "not-a-valid-url"
        assertEquals("not-a-valid-url", VoxHostSanitizer.sanitizeHost(invalidUrl))
    }

    @Test
    fun testHostFamilyClassification() {
        assertEquals(
            VoxHostFamily.GOOGLEVIDEO,
            VoxHostFamily.fromHost("rr1---sn-abc.googlevideo.com")
        )
        assertEquals(
            VoxHostFamily.YOUTUBE,
            VoxHostFamily.fromHost("i.ytimg.com")
        )
        assertEquals(
            VoxHostFamily.YANDEX,
            VoxHostFamily.fromHost("vtrans.yandex.net")
        )
        assertEquals(
            VoxHostFamily.OTHER,
            VoxHostFamily.fromHost("cdn.custom-proxy.org")
        )
    }

    @Test
    fun testBottleneckClassification() {
        // Fast connection, fast disk -> UNKNOWN (normal speed)
        val normalForensics = VoxDownloadNetworkForensics(
            resourceType = "VIDEO",
            safeHost = "*.googlevideo.com",
            hostFamily = VoxHostFamily.GOOGLEVIDEO,
            dnsMs = 15L,
            connectMs = 30L,
            tlsMs = 25L,
            ttfbMs = 150L,
            avgMbps = 45.0,
            elapsedMs = 2000L,
            stallCount = 0
        )
        val normal = VoxDownloadBottleneckClassifier.classify(
            forensics = normalForensics,
            packagingElapsedMs = 300L,
            finalizeElapsedMs = 100L
        )
        assertEquals(VoxDownloadBottleneck.UNKNOWN, normal.bottleneck)

        // Slow TTFB (> 1500ms) -> TTFB_SLOW
        val ttfbSlowForensics = VoxDownloadNetworkForensics(
            resourceType = "VIDEO",
            safeHost = "*.googlevideo.com",
            hostFamily = VoxHostFamily.GOOGLEVIDEO,
            dnsMs = 20L,
            connectMs = 40L,
            tlsMs = 30L,
            ttfbMs = 4200L,
            avgMbps = 30.0,
            elapsedMs = 4500L,
            stallCount = 0
        )
        val ttfbSlow = VoxDownloadBottleneckClassifier.classify(ttfbSlowForensics)
        assertEquals(VoxDownloadBottleneck.TTFB_SLOW, ttfbSlow.bottleneck)
        assertEquals(VoxBottleneckConfidence.MEDIUM, ttfbSlow.confidence)

        // High stalls with throttled throughput on googlevideo -> NETWORK_ENGINE_DEGRADED or REMOTE_THROTTLING
        val stallsForensics = VoxDownloadNetworkForensics(
            resourceType = "VIDEO",
            safeHost = "*.googlevideo.com",
            hostFamily = VoxHostFamily.GOOGLEVIDEO,
            dnsMs = 15L,
            connectMs = 30L,
            tlsMs = 25L,
            ttfbMs = 200L,
            avgMbps = 0.8,
            elapsedMs = 15000L,
            stallCount = 3
        )
        val stallsResult = VoxDownloadBottleneckClassifier.classify(stallsForensics)
        assertEquals(VoxDownloadBottleneck.NETWORK_ENGINE_DEGRADED, stallsResult.bottleneck)

        // Slow muxing stage (> 25s)
        val muxSlow = VoxDownloadBottleneckClassifier.classify(
            forensics = normalForensics,
            packagingElapsedMs = 35000L,
            finalizeElapsedMs = 100L
        )
        assertEquals(VoxDownloadBottleneck.MUX_SLOW, muxSlow.bottleneck)
    }

    @Test
    fun testBoundedParallelismPolicy() {
        assertEquals(
            2,
            VoxBoundedParallelismPolicy.getParallelConnectionsCount(
                rangeSupported = true,
                contentLength = 50_000_000L
            )
        )
        assertEquals(
            1,
            VoxBoundedParallelismPolicy.getParallelConnectionsCount(
                rangeSupported = true,
                contentLength = 2_000_000L
            )
        )
        assertEquals(
            1,
            VoxBoundedParallelismPolicy.getParallelConnectionsCount(
                rangeSupported = false,
                contentLength = 50_000_000L
            )
        )
    }
}

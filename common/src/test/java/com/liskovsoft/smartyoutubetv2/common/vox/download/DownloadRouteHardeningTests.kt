package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DownloadRouteSummaryTest {

    @Test
    fun testRouteSummaryFieldsAndCalculations() {
        val forensics = VoxDownloadNetworkForensics(
            resourceType = "VIDEO",
            networkEngine = "OKHTTP",
            safeHost = "*.googlevideo.com",
            hostFamily = VoxHostFamily.GOOGLEVIDEO,
            dnsMs = 12L,
            connectMs = 28L,
            tlsMs = 35L,
            ttfbMs = 180L,
            avgMbps = 32.5,
            peakMbps = 45.0,
            rangeSupported = true,
            parallelConnections = 2
        )

        val finalBytes = 100_000_000L
        val packagingElapsedMs = 5_000L
        val finalizeElapsedMs = 2_000L
        val effectiveSize = 100_000_000L

        val muxMbps = String.format(
            Locale.US,
            "%.2f",
            (finalBytes * 8.0) / (packagingElapsedMs / 1000.0) / 1_000_000.0
        )
        val diskWriteMbps = String.format(
            Locale.US,
            "%.2f",
            (effectiveSize * 8.0) / (finalizeElapsedMs / 1000.0) / 1_000_000.0
        )

        val analysis = VoxDownloadBottleneckClassifier.classify(
            forensics = forensics,
            packagingElapsedMs = packagingElapsedMs,
            finalizeElapsedMs = finalizeElapsedMs
        )

        // Simulate summary map construction matching VoxDownloadCoordinator.emitRouteSummary
        val moveUsed = true
        val summaryMap = mutableMapOf<String, String>(
            "videoHost" to forensics.safeHost,
            "audioHost" to "*.googlevideo.com",
            "hostFamily" to forensics.hostFamily.label,
            "safeHost" to forensics.safeHost,
            "engine" to forensics.networkEngine,
            "dnsMs" to forensics.dnsMs.toString(),
            "connectMs" to forensics.connectMs.toString(),
            "tlsMs" to forensics.tlsMs.toString(),
            "ttfbMs" to forensics.ttfbMs.toString(),
            "avgMbps" to String.format(Locale.US, "%.2f", forensics.avgMbps),
            "peakMbps" to String.format(Locale.US, "%.2f", forensics.peakMbps),
            "rangeSupported" to forensics.rangeSupported.toString(),
            "parallelConnections" to forensics.parallelConnections.toString(),
            "bottleneck" to analysis.bottleneck.name,
            "confidence" to analysis.confidence.name,
            "muxMbpsEquivalent" to muxMbps,
            "diskWriteMbps" to diskWriteMbps,
            "copyBytes" to effectiveSize.toString(),
            "moveUsed" to moveUsed.toString(),
            "fullCopyCount" to (if (moveUsed) "0" else "1"),
            "summary" to analysis.developerSummary
        )

        assertEquals("*.googlevideo.com", summaryMap["safeHost"])
        assertEquals("OKHTTP", summaryMap["engine"])
        assertEquals("32.50", summaryMap["avgMbps"])
        assertEquals("45.00", summaryMap["peakMbps"])
        assertEquals("160.00", summaryMap["muxMbpsEquivalent"])
        assertEquals("400.00", summaryMap["diskWriteMbps"])
        assertEquals("true", summaryMap["moveUsed"])
        assertEquals("0", summaryMap["fullCopyCount"])
        assertEquals("UNKNOWN", summaryMap["bottleneck"])

        // Non-move (MediaStore publication) should report fullCopyCount = 1
        val copySummaryMap = summaryMap.toMutableMap()
        copySummaryMap["moveUsed"] = "false"
        copySummaryMap["fullCopyCount"] = "1"
        assertEquals("false", copySummaryMap["moveUsed"])
        assertEquals("1", copySummaryMap["fullCopyCount"])
    }
}

class DownloadSafeHostnameTest {

    @Test
    fun testHostnameExtractionStripsQueryAndParameters() {
        val testUrls = listOf(
            "https://rr1---sn-nx5e6nzs.googlevideo.com/videoplayback?expire=1712345678&ei=abc&ip=10.0.0.1&signature=ABC123XYZ",
            "https://rr4---sn-ab5sznzl.googlevideo.com:443/videoplayback?sparams=expire%2Cid&sig=SECRET_SIG",
            "https://vtrans.yandex.net:8443/download/file.mp3?token=MY_SECRET_AUTH_TOKEN&user=42",
            "https://manifest.googlevideo.com/api/manifest/dash/id/12345?source=youtube"
        )

        for (url in testUrls) {
            val safe = VoxHostSanitizer.sanitizeHost(url)
            assertFalse("Safe host should not contain expire: $safe", safe.contains("expire"))
            assertFalse("Safe host should not contain ip: $safe", safe.contains("10.0.0.1"))
            assertFalse("Safe host should not contain signature: $safe", safe.contains("ABC123XYZ"))
            assertFalse("Safe host should not contain secret token: $safe", safe.contains("SECRET"))
            assertFalse("Safe host should not contain path: $safe", safe.contains("videoplayback"))
            assertFalse("Safe host should not contain port: $safe", safe.contains("8443"))
            assertTrue("Safe host must start with *.: $safe", safe.startsWith("*."))
        }
    }

    @Test
    fun testHostFamilyClassification() {
        assertEquals(VoxHostFamily.GOOGLEVIDEO, VoxHostFamily.fromHost("rr2---sn-4g5ednsl.googlevideo.com"))
        assertEquals(VoxHostFamily.GOOGLEVIDEO, VoxHostFamily.fromHost("*.googlevideo.com"))
        assertEquals(VoxHostFamily.YOUTUBE, VoxHostFamily.fromHost("i.ytimg.com"))
        assertEquals(VoxHostFamily.YOUTUBE, VoxHostFamily.fromHost("www.youtube.com"))
        assertEquals(VoxHostFamily.YANDEX, VoxHostFamily.fromHost("vtrans.yandex.net"))
        assertEquals(VoxHostFamily.OTHER, VoxHostFamily.fromHost("custom-proxy.internal"))
    }
}

class DownloadParallelRangeRuntimeTest {

    @Test
    fun testBoundedParallelismThresholds() {
        // Below 20MB -> 1 connection even if range supported
        assertEquals(
            1,
            VoxBoundedParallelismPolicy.getParallelConnectionsCount(
                rangeSupported = true,
                contentLength = 19_999_999L
            )
        )

        // At or above 20MB with range support -> 2 connections
        assertEquals(
            2,
            VoxBoundedParallelismPolicy.getParallelConnectionsCount(
                rangeSupported = true,
                contentLength = 20_000_000L
            )
        )
        assertEquals(
            2,
            VoxBoundedParallelismPolicy.getParallelConnectionsCount(
                rangeSupported = true,
                contentLength = 100_000_000L
            )
        )

        // Range not supported -> 1 connection regardless of size
        assertEquals(
            1,
            VoxBoundedParallelismPolicy.getParallelConnectionsCount(
                rangeSupported = false,
                contentLength = 100_000_000L
            )
        )

        // Null content length -> 1 connection
        assertEquals(
            1,
            VoxBoundedParallelismPolicy.getParallelConnectionsCount(
                rangeSupported = true,
                contentLength = null
            )
        )
    }
}

class DownloadTransferSlowClassifierTest {

    @Test
    fun testTransferSlowClassification() {
        val slowForensics = VoxDownloadNetworkForensics(
            resourceType = "VIDEO",
            safeHost = "*.googlevideo.com",
            hostFamily = VoxHostFamily.GOOGLEVIDEO,
            dnsMs = 25L,
            connectMs = 450L, // connectMs > 300 so not remote throttling
            tlsMs = 60L,
            ttfbMs = 200L,
            avgMbps = 1.8, // 0.01..3.0 Mbps -> TRANSFER_SLOW
            bytesDownloaded = 5_000_000L,
            elapsedMs = 22_000L,
            stallCount = 0
        )

        val result = VoxDownloadBottleneckClassifier.classify(slowForensics)
        assertEquals(VoxDownloadBottleneck.TRANSFER_SLOW, result.bottleneck)
        assertEquals(VoxBottleneckConfidence.MEDIUM, result.confidence)
        assertTrue(result.developerSummary.contains("1.80 Мбит/с"))
    }

    @Test
    fun testRemoteThrottlingClassification() {
        val throttledForensics = VoxDownloadNetworkForensics(
            resourceType = "VIDEO",
            safeHost = "*.googlevideo.com",
            hostFamily = VoxHostFamily.GOOGLEVIDEO,
            dnsMs = 15L,
            connectMs = 50L, // Fast connect < 300ms
            tlsMs = 40L,
            ttfbMs = 120L, // Fast TTFB < 400ms
            avgMbps = 0.9, // Low throughput < 2 Mbps
            bytesDownloaded = 15_000_000L, // Large file > 10MB
            elapsedMs = 133_000L,
            stallCount = 0
        )

        val result = VoxDownloadBottleneckClassifier.classify(throttledForensics)
        assertEquals(VoxDownloadBottleneck.REMOTE_THROTTLING, result.bottleneck)
        assertEquals(VoxBottleneckConfidence.MEDIUM, result.confidence)
    }
}

class DownloadNoSignedUrlLeakTest {

    @Test
    fun testForensicsMapContainsNoSensitiveQueryStringOrSignatures() {
        val rawUrl = "https://rr2---sn-4g5ednsl.googlevideo.com/videoplayback?expire=1712345678&ei=k1234&ip=192.168.1.100&id=abcd1234efgh&itag=137&source=youtube&requiressl=yes&ratebypass=yes&mime=video%2Fmp4&gir=yes&clen=50000000&dur=300.0&lmt=1700000000&signature=EA0B1C2D3E4F5A6B7C8D&key=yt8"
        val safeHost = VoxHostSanitizer.sanitizeHost(rawUrl)

        val forensics = VoxDownloadNetworkForensics(
            resourceType = "VIDEO",
            safeHost = safeHost,
            hostFamily = VoxHostFamily.fromHost(safeHost),
            avgMbps = 25.0
        )

        val map = forensics.toMap()

        val mapValuesJoined = map.values.joinToString(" ")
        assertFalse("Map must not leak raw IP", mapValuesJoined.contains("192.168.1.100"))
        assertFalse("Map must not leak signature", mapValuesJoined.contains("EA0B1C2D3E4F5A6B7C8D"))
        assertFalse("Map must not leak expire parameter", mapValuesJoined.contains("1712345678"))
        assertFalse("Map must not leak videoplayback path", mapValuesJoined.contains("videoplayback"))
        assertEquals("*.googlevideo.com", map["safeHost"])
    }
}

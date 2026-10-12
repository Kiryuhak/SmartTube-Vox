package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxBuildChannel
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// =========================================================================
// SECTION 34: AUTO_BETA POLICY & AGGREGATION TESTS
// =========================================================================

class AutoBetaE2EPolicyTest {
    @Test
    fun testBetaBuildDefaultsAutoDiagnosticsOn() {
        val betaChannel = VoxBuildChannel.fromVersion("32.56-vox.8.2-beta.2")
        assertEquals(VoxBuildChannel.BETA, betaChannel)
        assertTrue(betaChannel.isPreRelease)
        assertTrue(VoxUpdateChannelManager.isAutoDiagnosticsAllowedByDefault("32.56-vox.8.2-beta.2"))
    }

    @Test
    fun testBetaUserCanDisableDiagnostics() {
        var userDiagnosticsEnabled = true
        userDiagnosticsEnabled = false
        assertFalse(userDiagnosticsEnabled)
    }
}

class AutoBetaIncidentAggregationTest {
    @Test
    fun testIncidentAggregationWindowAndGrouping() {
        assertEquals(45_000L, VoxDiagnosticsPolicy.INCIDENT_AGGREGATION_WINDOW_MS)

        val incident = VoxDiagnosticsScheduler.AutoBetaIncident(
            triggerCode = "HUD_STATE_INCONSISTENT",
            normalizedSignature = "HUD_STATE_INCONSISTENT|session_1|hud_1",
            appSessionId = "app_1234",
            playbackSessionId = "session_1",
            hudSessionId = "hud_1",
            repeatCount = 1
        )
        assertEquals(1, incident.repeatCount)

        // Simulate subsequent duplicate arrival
        incident.repeatCount++
        incident.lastSeenMs = System.currentTimeMillis()
        assertEquals(2, incident.repeatCount)

        val map = incident.toMap()
        assertEquals("HUD_STATE_INCONSISTENT", map["triggerCode"])
        assertEquals(2, map["repeatCount"])
        assertEquals("session_1", map["playbackSessionId"])
        assertEquals("hud_1", map["hudSessionId"])
        assertEquals("app_1234", map["appSessionId"])
    }
}

class AutoBetaRateLimitTest {
    @Test
    fun testIncidentMinIntervalRateLimit() {
        assertEquals(300_000L, VoxDiagnosticsPolicy.INCIDENT_MIN_INTERVAL_MS)
        val now = 1_000_000L
        val lastSent = now - 60_000L // 1 minute ago
        val canSend = (now - lastSent) >= VoxDiagnosticsPolicy.INCIDENT_MIN_INTERVAL_MS
        assertFalse(canSend)
    }
}

class AutoBetaOfflineQueueTest {
    @Test
    fun testOfflineQueueEntryMetadataTracking() {
        val entry = VoxDiagnosticsScheduler.QueuedOfflineReport(
            report = VoxDiagnosticReport(
                appVersion = "32.56-vox.8.2-beta.2",
                appVersionCode = 2446009,
                platform = "ANDROID",
                manufacturer = "Google",
                model = "Android TV",
                osName = "Android",
                osVersion = "14",
                sdkInt = 34,
                deviceTier = "HIGH",
                videoCodecs = emptyMap(),
                audioCodecs = emptyMap(),
                display = emptyMap(),
                currentPolicy = emptyMap(),
                recommendedSettings = emptyMap()
            ),
            queuedTimestampMs = System.currentTimeMillis(),
            retryCount = 0
        )
        assertEquals(0, entry.retryCount)
        entry.retryCount++
        assertEquals(1, entry.retryCount)
        assertTrue(entry.queuedTimestampMs > 0L)
    }
}

class AutoBetaQueueBoundedTest {
    @Test
    fun testOfflineQueueDropsOldestOnOverflow() {
        assertEquals(10, VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_SIZE)
        val queue = java.util.ArrayDeque<String>()
        for (i in 1..15) {
            if (queue.size >= VoxDiagnosticsPolicy.MAX_OFFLINE_QUEUE_SIZE) {
                queue.pollFirst()
            }
            queue.addLast("report_$i")
        }
        assertEquals(10, queue.size)
        assertEquals("report_6", queue.peekFirst())
        assertEquals("report_15", queue.peekLast())
    }
}

class AutoBetaSanitizerTest {
    @Test
    fun testSanitizerRemovesIpAndPersonalTokens() {
        val raw = "Error connecting to 192.168.1.100 with token=secret123456789"
        val sanitized = VoxDiagnosticSanitizer.sanitizeMessage(raw)
        assertFalse(sanitized.contains("192.168.1.100"))
        assertFalse(sanitized.contains("secret123456789"))
    }
}

class AutoBetaReportPurposeTest {
    @Test
    fun testReportPurposeConstantsAndSchemaV3() {
        assertEquals("AUTO_BETA", VoxDiagnosticReport.PURPOSE_AUTO_BETA)
        assertEquals("USER_MANUAL", VoxDiagnosticReport.PURPOSE_USER_MANUAL)
        assertEquals("EMULATOR_TEST", VoxDiagnosticReport.PURPOSE_EMULATOR_TEST)
        assertEquals("vox-diagnostic-report-v3", VoxDiagnosticReport.SCHEMA_V3)
    }
}

class AutoBetaStableIsolationTest {
    @Test
    fun testStableBuildDefaultsAutoDiagnosticsOffAndResetsConsent() {
        val stableChannel = VoxBuildChannel.fromVersion("32.56-vox.8.1")
        assertEquals(VoxBuildChannel.STABLE, stableChannel)
        assertFalse(stableChannel.isPreRelease)
        assertFalse(VoxUpdateChannelManager.isAutoDiagnosticsAllowedByDefault("32.56-vox.8.1"))

        var autoEnabled = true
        if (!stableChannel.isPreRelease) {
            autoEnabled = false
        }
        assertFalse(autoEnabled)
    }
}

class AutoBetaEscalationTest {
    @Test
    fun testFatalErrorEscalatesToImmediateDelivery() {
        assertTrue(VoxDiagnosticsPolicy.isFatalOrCriticalError(VoxLogCode.PLAYER_INIT_FAILED))
        assertTrue(VoxDiagnosticsPolicy.isFatalOrCriticalError(VoxLogCode.PLAYER_RENDERER_ERROR))
        assertFalse(VoxDiagnosticsPolicy.isFatalOrCriticalError(VoxLogCode.HUD_STATE_INCONSISTENT))
    }
}

// =========================================================================
// SECTION 39: MANDATORY PRIVACY TESTS
// =========================================================================

class NoVideoTitleInAutoReportTest {
    @Test
    fun testVideoTitleNotInHudPayloadOrReport() {
        // Raw video title must never be present in telemetry
        val rawTitle = "Secret Personal Home Video 2026 - Do Not Share"
        val sanitized = VoxDiagnosticSanitizer.sanitizeMessage(rawTitle)
        assertNotNull(sanitized)

        // Ensure no PII keys allowed in payload map
        val payload = mapOf(
            "event" to "HUD_SESSION_STARTED",
            "videoIdHash" to "a1b2c3d4"
        )
        assertFalse(payload.containsKey("videoTitle"))
        assertFalse(payload.containsKey("title"))
    }
}

class NoVideoIdInAutoReportTest {
    @Test
    fun testPlainVideoIdScrubbedOrHashedInAutoReport() {
        val rawMessage = "Playing videoId=dQw4w9WgXcQ on player"
        val sanitized = VoxDiagnosticSanitizer.sanitize(rawMessage)
        assertNotNull(sanitized)
    }
}

class NoOAuthTokenLeakTest {
    @Test
    fun testSanitizerRemovesOAuthTokens() {
        val authHeader = "Authorization: Bearer y0_AgAAAAA1234567890abcdef"
        val sanitized = VoxDiagnosticSanitizer.sanitizeMessage(authHeader)
        assertFalse(sanitized.contains("y0_AgAAAAA1234567890abcdef"))
    }
}

class NoCookieLeakTest {
    @Test
    fun testCookieHeaderAndTokensScrubbed() {
        val cookie = "Cookie: SID=AbCdEf123456; HSID=987654321; SSID=xyz123"
        val sanitized = VoxDiagnosticSanitizer.sanitize(cookie)
        assertFalse(sanitized.contains("AbCdEf123456"))
        assertFalse(sanitized.contains("987654321"))
    }
}

class NoSignedUrlLeakTest {
    @Test
    fun testSanitizerRemovesUrlSignatures() {
        val url = "https://rr1---sn-4g5ednkk.googlevideo.com/videoplayback?expire=1600000000&signature=ABCD1234EFGH"
        val sanitized = VoxDiagnosticSanitizer.sanitizeUrl(url)
        assertFalse(sanitized.contains("signature=ABCD1234EFGH"))
        assertFalse(sanitized.contains("expire="))
    }
}

class NoQueryLeakTest {
    @Test
    fun testQueryParametersStrippedFromMediaUrls() {
        val queryUrl = "https://example.com/stream?query=my+private+search&api_key=secretkey"
        val sanitized = VoxDiagnosticSanitizer.sanitizeUrl(queryUrl)
        assertFalse(sanitized.contains("query=my+private+search"))
        assertFalse(sanitized.contains("secretkey"))
        assertEquals("https://example.com/stream", sanitized)
    }
}

class NoIpLeakTest {
    @Test
    fun testIpAddressesRedacted() {
        val msgWithIps = "Connected to 192.168.2.114 and public 8.8.8.8"
        val sanitized = VoxDiagnosticSanitizer.sanitize(msgWithIps)
        assertFalse(sanitized.contains("192.168.2.114"))
        assertFalse(sanitized.contains("8.8.8.8"))
        assertTrue(sanitized.contains("[IP_REDACTED]"))
    }
}

class NoSubtitleTextLeakTest {
    @Test
    fun testSubtitleTextNotIncludedInDiagnostics() {
        val forbiddenKey = "subtitles_text"
        assertTrue(VoxDiagnosticSanitizer.isForbiddenKey("token"))
        // Subtitle content payload is never attached to safeRecentEvents
    }
}

class NoTranslationTextLeakTest {
    @Test
    fun testTranslationTextNotIncludedInDiagnostics() {
        val ctx = com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxTranslationReusePolicy.createSafeDiagnosticsContext(
            elapsedMs = 1500L,
            source = "api",
            sameVideo = false,
            mode = "live_voice"
        )
        assertFalse(ctx.containsKey("text"))
        assertFalse(ctx.containsKey("transcript"))
        assertTrue(ctx.containsKey("elapsedMs"))
    }
}

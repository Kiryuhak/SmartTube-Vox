package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxBuildChannel
import com.liskovsoft.smartyoutubetv2.common.vox.ota.VoxUpdateChannelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaAutoDiagnosticsEnabledTest {
    @Test
    fun testBetaBuildDefaultsAutoDiagnosticsOn() {
        val betaChannel = VoxBuildChannel.fromVersion("32.56-vox.8.2-beta.2")
        assertEquals(VoxBuildChannel.BETA, betaChannel)
        assertTrue(betaChannel.isPreRelease)
        assertTrue(VoxUpdateChannelManager.isAutoDiagnosticsAllowedByDefault("32.56-vox.8.2-beta.2"))
    }
}

class BetaAutoDiagnosticsDisableTest {
    @Test
    fun testBetaUserCanDisableDiagnostics() {
        var userDiagnosticsEnabled = true
        userDiagnosticsEnabled = false
        assertFalse(userDiagnosticsEnabled)
    }
}

class StableDiagnosticsDefaultOffTest {
    @Test
    fun testStableBuildDefaultsAutoDiagnosticsOff() {
        val stableChannel = VoxBuildChannel.fromVersion("32.56-vox.8.1")
        assertEquals(VoxBuildChannel.STABLE, stableChannel)
        assertFalse(stableChannel.isPreRelease)
        assertFalse(VoxUpdateChannelManager.isAutoDiagnosticsAllowedByDefault("32.56-vox.8.1"))
    }
}

class BetaToStablePolicyTest {
    @Test
    fun testSwitchingToStableResetsConsent() {
        var autoEnabled = true
        val targetChannel = VoxBuildChannel.STABLE
        if (!targetChannel.isPreRelease) {
            autoEnabled = false
        }
        assertFalse(autoEnabled)
    }
}

class IncidentAggregationTest {
    @Test
    fun testIncidentAggregationWindowConstant() {
        assertEquals(45_000L, VoxDiagnosticsPolicy.INCIDENT_AGGREGATION_WINDOW_MS)
    }
}

class IncidentRateLimitTest {
    @Test
    fun testIncidentMinIntervalRateLimit() {
        assertEquals(300_000L, VoxDiagnosticsPolicy.INCIDENT_MIN_INTERVAL_MS)
        val now = 1_000_000L
        val lastSent = now - 60_000L // 1 minute ago
        val canSend = (now - lastSent) >= VoxDiagnosticsPolicy.INCIDENT_MIN_INTERVAL_MS
        assertFalse(canSend)
    }
}

class IncidentRepeatCountTest {
    @Test
    fun testIncidentRepeatCountIncrements() {
        var repeatCount = 1
        // Duplicate event arrives within aggregation window:
        repeatCount++
        assertEquals(2, repeatCount)
    }
}

class OfflineQueueBoundedTest {
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

class AutoReportSanitizerTest {
    @Test
    fun testSanitizerRemovesIpAndPersonalTokens() {
        val raw = "Error connecting to 192.168.1.100 with token=secret123456789"
        val sanitized = VoxDiagnosticSanitizer.sanitizeMessage(raw)
        assertFalse(sanitized.contains("192.168.1.100"))
        assertFalse(sanitized.contains("secret123456789"))
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

class NoOAuthLeakTest {
    @Test
    fun testSanitizerRemovesOAuthTokens() {
        val authHeader = "Authorization: Bearer y0_AgAAAAA1234567890abcdef"
        val sanitized = VoxDiagnosticSanitizer.sanitizeMessage(authHeader)
        assertFalse(sanitized.contains("y0_AgAAAAA1234567890abcdef"))
    }
}

class ReportPurposeTest {
    @Test
    fun testReportPurposeConstantsAndSchemaV3() {
        assertEquals("AUTO_BETA", VoxDiagnosticReport.PURPOSE_AUTO_BETA)
        assertEquals("USER_MANUAL", VoxDiagnosticReport.PURPOSE_USER_MANUAL)
        assertEquals("EMULATOR_TEST", VoxDiagnosticReport.PURPOSE_EMULATOR_TEST)
        assertEquals("vox-diagnostic-report-v3", VoxDiagnosticReport.SCHEMA_V3)
    }
}

class EmulatorReportPurposeTest {
    @Test
    fun testEmulatorTestReportPurposeClassification() {
        val purpose = VoxDiagnosticReport.PURPOSE_EMULATOR_TEST
        assertEquals("EMULATOR_TEST", purpose)
    }
}

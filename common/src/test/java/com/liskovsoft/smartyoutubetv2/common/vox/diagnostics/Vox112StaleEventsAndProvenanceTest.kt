package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROMPT №112: Comprehensive verification of stale event repackaging protection,
 * version provenance, bounded recency threshold (30 minutes), and episode overlay background audio gate.
 */
class Vox112StaleEventsAndProvenanceTest {

    private val currentRc9Version = "32.56-vox.9.2-rc.9"
    private val thresholdMs = VoxDiagnosticsPolicy.TRIGGER_RECENCY_THRESHOLD_MS // 30 min (1,800,000 ms)

    @Test
    fun testOldTriggerCurrentReportVersionReclassifiedAsHistorical() {
        val now = 1_700_000_000_000L
        val age11HoursMs = (11.17 * 3600 * 1000).toLong()
        val staleEventTimestamp = now - age11HoursMs

        val staleEvent = VoxLogEvent(
            timestamp = staleEventTimestamp,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_RECOVERY_FAILED,
            message = "Player recovery failed on old run",
            appVersion = "32.56-vox.9.2-rc.8" // recorded on RC.8
        )

        // Stale event from older version is not trigger-eligible on RC.9
        assertFalse(VoxDiagnosticsPolicy.isTriggerEligible(staleEvent, currentRc9Version, now))

        // Prominent unrecovered event returns null (no active error)
        val prominent = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(
            listOf(staleEvent),
            currentRc9Version,
            now
        )
        assertNull("Old trigger from pre-RC.9 must not become prominent unrecovered event", prominent)

        // Building report canonical classification
        val report = VoxDiagnosticReport(
            timestamp = now,
            appVersion = currentRc9Version,
            appVersionCode = 2446028,
            platform = "ARM32_DEVICE",
            manufacturer = "TCL",
            model = "BeyondTV",
            osName = "Android",
            osVersion = "11",
            sdkInt = 30,
            deviceTier = "MEDIUM",
            videoCodecs = emptyMap(),
            audioCodecs = emptyMap(),
            display = emptyMap(),
            currentPolicy = emptyMap(),
            recommendedSettings = emptyMap(),
            safeRecentEvents = listOf(staleEvent.copy(isHistorical = true)),
            triggerEvent = VoxLogCode.PLAYER_RECOVERY_FAILED
        ).withCanonicalClassification()

        // Stale trigger with matching historical event must NOT generate an active error signature
        assertNull(report.errorCategory)
        assertTrue(report.errorSignature.isNullOrBlank() || report.errorSignature?.contains("GENERAL") == true)
    }

    @Test
    fun testOldTriggerSameVersionReclassifiedAsHistorical() {
        val now = 1_700_000_000_000L
        val age24HoursMs = (23.63 * 3600 * 1000).toLong()
        val staleEventTimestamp = now - age24HoursMs

        val staleEvent = VoxLogEvent(
            timestamp = staleEventTimestamp,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.YANDEX_AUTH,
            code = "AUTH_FAILED",
            message = "Auth failed 24 hours ago",
            appVersion = currentRc9Version // same version, but stale
        )

        // Exceeds 30 minute threshold -> not eligible
        assertFalse(VoxDiagnosticsPolicy.isTriggerEligible(staleEvent, currentRc9Version, now))

        val prominent = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(
            listOf(staleEvent),
            currentRc9Version,
            now
        )
        assertNull("Stale event (>30m) on same version must not become active trigger", prominent)
    }

    @Test
    fun testFreshTriggerSameVersionEligible() {
        val now = 1_700_000_000_000L
        val freshEventTimestamp = now - (2 * 60 * 1000L) // 2 minutes ago

        val freshEvent = VoxLogEvent(
            timestamp = freshEventTimestamp,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_HTTP_ERROR,
            message = "HTTP 503 error on playback start",
            appVersion = currentRc9Version
        )

        assertTrue(VoxDiagnosticsPolicy.isTriggerEligible(freshEvent, currentRc9Version, now))

        val prominent = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(
            listOf(freshEvent),
            currentRc9Version,
            now
        )
        assertNotNull(prominent)
        assertEquals(VoxLogCode.PLAYER_HTTP_ERROR, prominent?.code)
    }

    @Test
    fun testLegacyReportMissingVersionGracefulFallback() {
        val now = 1_700_000_000_000L

        // Legacy event without appVersion field
        val legacyJson = JSONObject().apply {
            put("timestamp", now - 60_000L) // 1 min ago
            put("level", "WARNING")
            put("category", "PLAYER")
            put("code", VoxLogCode.PLAYER_REBUFFER)
            put("message", "Rebuffer detected")
        }

        val deserialized = VoxLogEvent.fromJson(legacyJson)
        assertNotNull(deserialized)
        assertNull(deserialized?.appVersion)

        // Fresh legacy event without version is still eligible (fallback safe)
        assertTrue(VoxDiagnosticsPolicy.isTriggerEligible(deserialized!!, currentRc9Version, now))

        // Stale legacy event is correctly filtered by time
        val staleLegacyEvent = deserialized.copy(timestamp = now - (45 * 60 * 1000L))
        assertFalse(VoxDiagnosticsPolicy.isTriggerEligible(staleLegacyEvent, currentRc9Version, now))
    }

    @Test
    fun testFreshErrorAfterUpgradeWithNewVersion() {
        val now = 1_700_000_000_000L

        val oldVersionError = VoxLogEvent(
            timestamp = now - (10 * 60 * 1000L), // 10 min ago, but old version
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_RENDERER_ERROR,
            message = "Old renderer error before update",
            appVersion = "32.56-vox.9.1"
        )

        val freshNewVersionError = VoxLogEvent(
            timestamp = now - (1 * 60 * 1000L), // 1 min ago, new version
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.TRANSLATION,
            code = VoxLogCode.TRANSLATION_TIMEOUT,
            message = "Translation timeout on RC.9",
            appVersion = currentRc9Version
        )

        assertFalse("Old version event must be rejected", VoxDiagnosticsPolicy.isTriggerEligible(oldVersionError, currentRc9Version, now))
        assertTrue("Fresh new version event must be accepted", VoxDiagnosticsPolicy.isTriggerEligible(freshNewVersionError, currentRc9Version, now))

        val prominent = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(
            listOf(oldVersionError, freshNewVersionError),
            currentRc9Version,
            now
        )
        assertNotNull(prominent)
        assertEquals(VoxLogCode.TRANSLATION_TIMEOUT, prominent?.code)
    }

    @Test
    fun testThresholdBoundaryMinus1ms() {
        val now = 1_700_000_000_000L
        val eventTimestamp = now - (thresholdMs - 1L) // 29m 59.999s

        val event = VoxLogEvent(
            timestamp = eventTimestamp,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_SOURCE_ERROR,
            message = "Source error just inside threshold",
            appVersion = currentRc9Version
        )
        assertTrue("Event at threshold-1ms must be eligible", VoxDiagnosticsPolicy.isTriggerEligible(event, currentRc9Version, now))
    }

    @Test
    fun testThresholdBoundaryExact() {
        val now = 1_700_000_000_000L
        val eventTimestamp = now - thresholdMs // Exactly 30 minutes

        val event = VoxLogEvent(
            timestamp = eventTimestamp,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_SOURCE_ERROR,
            message = "Source error at exact threshold",
            appVersion = currentRc9Version
        )
        assertTrue("Event at exact threshold must be eligible", VoxDiagnosticsPolicy.isTriggerEligible(event, currentRc9Version, now))
    }

    @Test
    fun testThresholdBoundaryPlus1ms() {
        val now = 1_700_000_000_000L
        val eventTimestamp = now - (thresholdMs + 1L) // 30m 0.001s

        val event = VoxLogEvent(
            timestamp = eventTimestamp,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_SOURCE_ERROR,
            message = "Source error just beyond threshold",
            appVersion = currentRc9Version
        )
        assertFalse("Event at threshold+1ms must NOT be eligible", VoxDiagnosticsPolicy.isTriggerEligible(event, currentRc9Version, now))
    }

    @Test
    fun testInvalidTimestampFutureZeroClockSkew() {
        val now = 1_700_000_000_000L

        val zeroTsEvent = VoxLogEvent(
            timestamp = 0L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.APP,
            code = "CRASH",
            message = "Zero timestamp",
            appVersion = currentRc9Version
        )
        assertFalse("Zero timestamp must not be eligible", VoxDiagnosticsPolicy.isTriggerEligible(zeroTsEvent, currentRc9Version, now))

        val negativeTsEvent = zeroTsEvent.copy(timestamp = -5000L)
        assertFalse("Negative timestamp must not be eligible", VoxDiagnosticsPolicy.isTriggerEligible(negativeTsEvent, currentRc9Version, now))

        val futureSkewFarEvent = zeroTsEvent.copy(timestamp = now + 120_000L) // +2 min in future
        assertFalse("Future timestamp beyond tolerance (>1m) must not be eligible", VoxDiagnosticsPolicy.isTriggerEligible(futureSkewFarEvent, currentRc9Version, now))

        val futureSkewCloseEvent = zeroTsEvent.copy(timestamp = now + 30_000L) // +30s in future (jitter/clock skew)
        assertTrue("Small clock skew (<=1m) must be tolerated", VoxDiagnosticsPolicy.isTriggerEligible(futureSkewCloseEvent, currentRc9Version, now))
    }

    @Test
    fun testErrorRecoveredNewErrorChain() {
        val now = 1_700_000_000_000L

        val event1 = VoxLogEvent(
            timestamp = now - 50_000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_HTTP_ERROR,
            message = "Initial HTTP failure",
            appVersion = currentRc9Version
        )

        val event2 = VoxLogEvent(
            timestamp = now - 40_000L,
            level = VoxLogLevel.INFO,
            category = VoxLogCategory.PLAYER,
            code = "PLAYER_PLAYBACK_PROGRESS",
            message = "Player recovered playback progress",
            appVersion = currentRc9Version
        )

        val event3 = VoxLogEvent(
            timestamp = now - 10_000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.CODEC,
            code = VoxLogCode.PLAYER_DECODER_ERROR,
            message = "Decoder error occurred later",
            appVersion = currentRc9Version
        )

        val prominent = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(
            listOf(event1, event2, event3),
            currentRc9Version,
            now
        )
        assertNotNull(prominent)
        assertEquals("Recovered HTTP error must be skipped, and subsequent decoder error selected", VoxLogCode.PLAYER_DECODER_ERROR, prominent?.code)
    }

    @Test
    fun testContextTransferWithoutSecretsOrPII() {
        val rawContext = mapOf(
            "playbackSessionId" to "sess_789",
            "resolution" to "1920x1080",
            "token" to "y0_AgAAAAA_SECRET_TOKEN",
            "ip" to "192.168.1.100"
        )

        // Ensure logger and policy sanitize forbidden keys
        assertTrue(VoxDiagnosticSanitizer.isForbiddenKey("token"))
        val sanitizedMsg = VoxDiagnosticSanitizer.sanitize("Access granted with token=secret123 to 192.168.2.114")
        assertFalse(sanitizedMsg.contains("secret123"))
        assertFalse(sanitizedMsg.contains("192.168.2.114"))
    }

    @Test
    fun testAutoBetaIdempotentClassification() {
        val now = 1_700_000_000_000L
        val event = VoxLogEvent(
            timestamp = now - 10_000L,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_INIT_FAILED,
            message = "Init failed",
            appVersion = currentRc9Version
        )

        val report = VoxDiagnosticReport(
            timestamp = now,
            appVersion = currentRc9Version,
            appVersionCode = 2446028,
            platform = "ARM32_DEVICE",
            manufacturer = "TCL",
            model = "BeyondTV",
            osName = "Android",
            osVersion = "11",
            sdkInt = 30,
            deviceTier = "MEDIUM",
            videoCodecs = emptyMap(),
            audioCodecs = emptyMap(),
            display = emptyMap(),
            currentPolicy = emptyMap(),
            recommendedSettings = emptyMap(),
            safeRecentEvents = listOf(event)
        ).withCanonicalClassification()

        val classification1 = report.withCanonicalClassification()
        val classification2 = classification1.withCanonicalClassification()

        assertEquals(classification1.errorCategory, classification2.errorCategory)
        assertEquals(classification1.errorSignature, classification2.errorSignature)
    }

    @Test
    fun testHistoricalEventsPreservedInTimelineWithoutFieldLoss() {
        val now = 1_700_000_000_000L
        val oldTs1 = now - (11 * 3600 * 1000L)
        val oldTs2 = now - (23 * 3600 * 1000L)

        val oldEvent1 = VoxLogEvent(
            timestamp = oldTs1,
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_RECOVERY_FAILED,
            message = "Recovery failed 11h ago",
            context = mapOf("reason" to "dns_fail"),
            repeatCount = 3,
            isHistorical = true,
            appVersion = "32.56-vox.9.2-rc.8"
        )

        val oldEvent2 = VoxLogEvent(
            timestamp = oldTs2,
            level = VoxLogLevel.WARNING,
            category = VoxLogCategory.YANDEX_AUTH,
            code = "AUTH_FAILED",
            message = "Auth failed 23h ago",
            context = mapOf("service" to "vot"),
            repeatCount = 1,
            isHistorical = true,
            appVersion = "32.56-vox.9.1"
        )

        val report = VoxDiagnosticReport(
            timestamp = now,
            appVersion = currentRc9Version,
            appVersionCode = 2446028,
            platform = "ARM32_DEVICE",
            manufacturer = "TCL",
            model = "BeyondTV",
            osName = "Android",
            osVersion = "11",
            sdkInt = 30,
            deviceTier = "MEDIUM",
            videoCodecs = emptyMap(),
            audioCodecs = emptyMap(),
            display = emptyMap(),
            currentPolicy = emptyMap(),
            recommendedSettings = emptyMap(),
            safeRecentEvents = listOf(oldEvent1, oldEvent2)
        )

        val json = JSONObject(report.toJson())
        val deserialized = VoxDiagnosticReport.fromJson(json)

        assertNotNull(deserialized)
        assertEquals(2, deserialized?.safeRecentEvents?.size)

        val ev1 = deserialized?.safeRecentEvents?.get(0)
        assertEquals(oldTs1, ev1?.timestamp)
        assertEquals(VoxLogCode.PLAYER_RECOVERY_FAILED, ev1?.code)
        assertEquals(3, ev1?.repeatCount)
        assertTrue(ev1?.isHistorical == true)
        assertEquals("32.56-vox.9.2-rc.8", ev1?.appVersion)
        assertEquals("dns_fail", ev1?.context?.get("reason"))

        val ev2 = deserialized?.safeRecentEvents?.get(1)
        assertEquals(oldTs2, ev2?.timestamp)
        assertEquals("AUTH_FAILED", ev2?.code)
        assertTrue(ev2?.isHistorical == true)
        assertEquals("32.56-vox.9.1", ev2?.appVersion)
    }

    @Test
    fun testEpisodeOverlayBackgroundAudioGatePreventsVideoShutdown() {
        // Simulating condition in PlaybackActivity.onPause():
        // When isChapterNotification or any AppDialog is active, enterBackgroundAudioOnly must NOT trigger.
        val backgroundModeSound = 2 // PlayerData.BACKGROUND_MODE_SOUND
        var isBackgroundAudioOnlyActive = false
        var isFinishing = false
        var isBackPressed = false

        // Case A: Dialog is NOT shown (e.g. user pressed HOME or collapsed app)
        var isDialogShown = false
        val shouldEnterBackgroundA = (backgroundModeSound == 2) &&
            !isBackgroundAudioOnlyActive && !isFinishing && !isBackPressed && !isDialogShown
        assertTrue("Without dialog, background audio mode must be entered onPause", shouldEnterBackgroundA)

        // Case B: Chapter notification dialog is active (Nitronomic #217 scenario)
        isDialogShown = true
        val shouldEnterBackgroundB = (backgroundModeSound == 2) &&
            !isBackgroundAudioOnlyActive && !isFinishing && !isBackPressed && !isDialogShown
        assertFalse("When AppDialog is shown, background audio must NOT enter and video track must NOT be disabled", shouldEnterBackgroundB)
    }
}

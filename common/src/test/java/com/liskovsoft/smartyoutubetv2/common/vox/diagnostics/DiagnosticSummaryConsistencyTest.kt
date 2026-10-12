package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxCompatibilityManager
import com.liskovsoft.smartyoutubetv2.common.vox.capability.VoxPlaybackSessionObservation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticSummaryConsistencyTest {

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        VoxLogStore.instance(context).clearLogs()
        VoxCompatibilityManager.instance(context).getPlaybackHealthTracker().reset()
    }

    @Test
    fun reportSchemaIsV3() {
        val context = RuntimeEnvironment.getApplication()
        val report = VoxDiagnosticReport.create(context, "test-rep-1", includeEvents = true)
        assertEquals(VoxDiagnosticReport.SCHEMA_V3, report.schema)
        assertEquals("test-rep-1", report.reportId)
    }

    @Test
    fun playbackStatsMatchesRecordedSessionsConsistently() {
        val context = RuntimeEnvironment.getApplication()
        val manager = VoxCompatibilityManager.instance(context)

        // Record a VOD session with 2 rebuffers
        val session = VoxPlaybackSessionObservation(
            sessionId = "s1",
            selectedHeight = 2160,
            selectedCodec = "vp9",
            isLive = false,
            playbackDurationMs = 120_000L, // 2 minutes
            rebufferCount = 2,
            totalRebufferMs = 15_000L
        )
        manager.recordPlaybackSession(session)

        val report = VoxDiagnosticReport.create(context, "test-rep-2", includeEvents = true)
        assertNotNull(report.playbackStats)
        val stats = report.playbackStats!!
        assertEquals(1, stats["sampleCount"])
        assertEquals(1, stats["vodSampleCount"])
        assertEquals(0, stats["liveSampleCount"])
        val rebufferRate = stats["rebufferRate"] as Float
        assertTrue(rebufferRate > 0f)
    }

    @Test
    fun recentEventsReflectRecentErrorsConsistently() {
        val context = RuntimeEnvironment.getApplication()
        val store = VoxLogStore.instance(context)

        store.addEvent(
            VoxLogEvent(
                timestamp = System.currentTimeMillis(),
                level = VoxLogLevel.WARNING,
                category = VoxLogCategory.PLAYER,
                code = VoxLogCode.PLAYER_REBUFFER,
                message = "Stall duration: 12000ms"
            )
        )

        val report = VoxDiagnosticReport.create(context, "test-rep-3", includeEvents = true)
        val rebufferEvent = report.safeRecentEvents.firstOrNull { it.code == VoxLogCode.PLAYER_REBUFFER }
        assertNotNull(rebufferEvent)
    }

    @Test
    fun nullWhenNoSessionsRecorded() {
        val context = RuntimeEnvironment.getApplication()
        VoxCompatibilityManager.instance(context).getPlaybackHealthTracker().reset()
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLivePlaybackMonitor.resetForTesting()
        com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxStartupProfiler.resetForTesting()

        val report = VoxDiagnosticReport.create(context, "test-rep-empty", includeEvents = false)
        assertNull("playbackStats must be null when 0 observations recorded", report.playbackStats)
        assertNull("livePlayback must be null when no live session active or recent", report.livePlayback)
        assertNull("startupProfile must be null when no startup session recorded", report.startupProfile)
    }

    @Test
    fun eventRecoveryDetection() {
        val now = System.currentTimeMillis()
        val otaRateLimit = VoxLogEvent(
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.OTA,
            code = VoxLogCode.OTA_RATE_LIMIT,
            message = "Rate limit 429",
            timestamp = now - 10_000L
        )
        val otaNoUpdate = VoxLogEvent(
            level = VoxLogLevel.INFO,
            category = VoxLogCategory.OTA,
            code = VoxLogCode.OTA_NO_UPDATE,
            message = "No update",
            timestamp = now - 5_000L
        )

        val journalWithRecovery = listOf(otaRateLimit, otaNoUpdate)
        assertTrue("OTA_RATE_LIMIT followed by OTA_NO_UPDATE must be marked recovered",
            VoxDiagnosticsPolicy.isEventRecovered(otaRateLimit, journalWithRecovery))

        val journalWithoutRecovery = listOf(otaRateLimit)
        assertFalse("Standalone OTA_RATE_LIMIT must NOT be marked recovered",
            VoxDiagnosticsPolicy.isEventRecovered(otaRateLimit, journalWithoutRecovery))

        val prominentWhenRecovered = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(journalWithRecovery)
        assertNull("When only error is recovered, findProminentUnrecoveredEvent must return null", prominentWhenRecovered)
        assertFalse("hasValuableDiagnostics must return false when all errors are recovered",
            VoxDiagnosticsPolicy.hasValuableDiagnostics(journalWithRecovery))

        val unrecoveredPlayerError = VoxLogEvent(
            level = VoxLogLevel.ERROR,
            category = VoxLogCategory.PLAYER,
            code = VoxLogCode.PLAYER_HTTP_ERROR,
            message = "HTTP 403 Forbidden",
            timestamp = now - 2_000L
        )
        val mixedJournal = listOf(otaRateLimit, otaNoUpdate, unrecoveredPlayerError)
        val prominentMixed = VoxDiagnosticsPolicy.findProminentUnrecoveredEvent(mixedJournal)
        assertNotNull(prominentMixed)
        assertEquals(VoxLogCode.PLAYER_HTTP_ERROR, prominentMixed?.code)
        assertTrue(VoxDiagnosticsPolicy.hasValuableDiagnostics(mixedJournal))
    }
}

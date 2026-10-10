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
    fun reportSchemaIsV2() {
        val context = RuntimeEnvironment.getApplication()
        val report = VoxDiagnosticReport.create(context, "test-rep-1", includeEvents = true)
        assertEquals(VoxDiagnosticReport.SCHEMA_V2, report.schema)
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
}

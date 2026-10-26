package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoxForensicTimelineTest {

    private lateinit var context: Context
    private lateinit var recorder: VoxUserJourneyRecorder

    @Before
    fun setUp() {
        VoxUserJourneyRecorder.resetForTests()
        context = RuntimeEnvironment.getApplication()
        recorder = VoxUserJourneyRecorder.instance(context)
        recorder.purgeAll()
        recorder.setMode(VoxUserJourneyMode.DETAILED)
        recorder.setIncludeContentDetails(true)
    }

    @Test
    fun testForensicReportGroupsByOperationId() {
        val op1 = "op-dvr-seek-001"
        val op2 = "op-vot-resync-002"

        recorder.recordPlayback(
            event = "SEEK_REQUESTED",
            videoTitle = "Test Video",
            videoId = "vid123",
            positionMs = 1000L,
            operationId = op1
        )
        recorder.recordPlayback(
            event = "SEEK_APPLIED",
            videoTitle = "Test Video",
            videoId = "vid123",
            positionMs = 5000L,
            operationId = op1
        )
        recorder.recordVot(
            event = "VOT_RESYNC",
            stage = "RESYNCING",
            driftMs = 250L,
            operationId = op2
        )
        recorder.recordKeyAction("DPAD_CENTER", "user_click") // ungrouped (no operationId)

        val reportJson = VoxForensicTimeline.buildForensicReport(recorder)
        assertNotNull(reportJson)

        val report = JSONObject(reportJson)
        val chains = report.getJSONObject("causalChains")
        assertTrue(chains.has(op1))
        assertTrue(chains.has(op2))

        val op1Array = chains.getJSONArray(op1)
        assertEquals(2, op1Array.length())
        assertEquals("SEEK_REQUESTED", op1Array.getJSONObject(0).getString("event"))
        assertEquals("SEEK_APPLIED", op1Array.getJSONObject(1).getString("event"))

        val op2Array = chains.getJSONArray(op2)
        assertEquals(1, op2Array.length())
        assertEquals("VOT_RESYNC", op2Array.getJSONObject(0).getString("event"))

        val ungrouped = report.getJSONArray("ungroupedEvents")
        assertEquals(1, ungrouped.length())
        assertEquals("KEY_ACTION", ungrouped.getJSONObject(0).getString("event"))
    }

    @Test
    fun testAnonymizedSampleHasNoVideoRef() {
        recorder.recordPlayback(
            event = "PLAYBACK_START",
            videoTitle = "Super Secret Video Title",
            videoId = "secretVid999",
            positionMs = 0L
        )

        val sampleJson = VoxForensicTimeline.buildAnonymizedSample(recorder, 10)
        val sample = JSONObject(sampleJson)

        assertEquals("3.0", sample.getString("contractVersion"))
        assertTrue(sample.getBoolean("anonymized"))

        val events = sample.getJSONArray("events")
        assertEquals(1, events.length())
        val ev = events.getJSONObject(0)
        assertEquals("redacted", ev.getString("videoRef"))
        assertFalse(sampleJson.contains("Super Secret Video Title"))
        assertFalse(sampleJson.contains("secretVid999"))
    }

    @Test
    fun testLatencyStatsComputed() {
        recorder.recordKeyAction("DPAD_DOWN", "nav1")
        recorder.recordKeyAction("DPAD_UP", "nav2")
        recorder.recordKeyAction("DPAD_CENTER", "select")

        val reportJson = VoxForensicTimeline.buildForensicReport(recorder)
        val report = JSONObject(reportJson)

        assertTrue(report.has("latencyStats"))
        val latency = report.getJSONObject("latencyStats")
        assertTrue(latency.has("sampleCount"))
        assertTrue(latency.has("p50GapMs"))
        assertTrue(latency.has("p95GapMs"))
        assertTrue(latency.has("maxGapMs"))
        assertTrue(latency.getInt("sampleCount") >= 2)
    }

    @Test
    fun testMetaContainsExpectedFields() {
        recorder.recordKeyAction("DPAD_CENTER", "test")

        val reportJson = VoxForensicTimeline.buildForensicReport(recorder)
        val report = JSONObject(reportJson)

        assertTrue(report.has("_meta"))
        val meta = report.getJSONObject("_meta")
        assertEquals("3.0", meta.getString("contractVersion"))
        assertEquals(2, meta.getInt("schemaVersion"))
        assertEquals(1, meta.getInt("eventCount"))
        assertEquals("DETAILED", meta.getString("modeName"))
        assertTrue(meta.has("exportedAt"))
        assertTrue(meta.getBoolean("contentIncluded"))
    }

    @Test
    fun testForensicReportWithPinnedBookmarks() {
        // Record an error event to trigger pinning
        recorder.recordError(
            category = "DECODER",
            errorClass = "MediaCodecException",
            message = "Hardware decoder failed, fallback to software"
        )
        // Record some regular events
        recorder.recordKeyAction("DPAD_UP", "nav")

        val reportJson = VoxForensicTimeline.buildForensicReport(recorder)
        val report = JSONObject(reportJson)

        assertTrue(report.has("pinnedBookmarks"))
        val pinned = report.getJSONArray("pinnedBookmarks")
        assertTrue(pinned.length() >= 1)
        val firstPinned = pinned.getJSONObject(0)
        assertEquals("DECODER", firstPinned.getString("category"))
        assertEquals("ERROR", firstPinned.getString("event"))
    }
}

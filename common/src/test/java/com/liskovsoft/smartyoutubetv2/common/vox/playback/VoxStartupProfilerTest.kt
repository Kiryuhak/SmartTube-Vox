package com.liskovsoft.smartyoutubetv2.common.vox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxStartupProfilerTest {

    @Test
    fun testStartupProfilerStageRecording() {
        val t0 = 10_000L
        VoxStartupProfiler.onUserOpen(sessionId = 123456789L, engine = "CRONET", nowMs = t0)

        // Stage 1: Player preparation
        VoxStartupProfiler.startStage("PLAYER_PREPARE", t0 + 50L)
        VoxStartupProfiler.finishStage("PLAYER_PREPARE", "OK", t0 + 200L)

        // Stage 2: Buffer load
        VoxStartupProfiler.startStage("BUFFER_LOAD", t0 + 210L)
        VoxStartupProfiler.finishStage("BUFFER_LOAD", "OK", t0 + 450L)

        // Stage 3: First frame rendered
        VoxStartupProfiler.recordStage("FIRST_FRAME", 0L, "OK", t0 + 460L)

        val summary = VoxStartupProfiler.getProfileSummary()
        assertEquals(123456789L, summary["playbackSessionId"])
        assertEquals("CRONET", summary["engine"])

        val stages = summary["stages"] as? List<Map<String, Any>>
        assertNotNull(stages)
        assertEquals(4, stages!!.size)

        assertEquals("USER_OPEN", stages[0]["stage"])
        assertEquals(0L, stages[0]["relativeOffsetMs"])

        assertEquals("PLAYER_PREPARE", stages[1]["stage"])
        assertEquals(150L, stages[1]["elapsedMs"])
        assertEquals(200L, stages[1]["relativeOffsetMs"])

        assertEquals("BUFFER_LOAD", stages[2]["stage"])
        assertEquals(240L, stages[2]["elapsedMs"])
        assertEquals(450L, stages[2]["relativeOffsetMs"])

        assertEquals("FIRST_FRAME", stages[3]["stage"])
        assertEquals(460L, stages[3]["relativeOffsetMs"])

        assertEquals(460L, summary["totalStartupMs"])
    }

    @Test
    fun testZeroPiiGuarantee() {
        val summary = VoxStartupProfiler.getProfileSummary()
        val text = summary.toString()
        assertFalse(text.contains("http://"))
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("Bearer"))
        assertFalse(text.contains(".googlevideo.com"))
    }

    @Test
    fun testRootCauseClassification() {
        val t0 = 20_000L
        VoxStartupProfiler.onUserOpen(sessionId = 999L, engine = "EXOPLAYER", nowMs = t0)
        VoxStartupProfiler.startStage(VoxStartupProfiler.STAGE_REQUEST, t0)
        VoxStartupProfiler.finishStage(VoxStartupProfiler.STAGE_REQUEST, "OK", t0 + 1500L) // 1500ms
        VoxStartupProfiler.startStage(VoxStartupProfiler.STAGE_BUFFER_FILL, t0 + 1500L)
        VoxStartupProfiler.finishStage(VoxStartupProfiler.STAGE_BUFFER_FILL, "OK", t0 + 1900L) // 400ms

        val rootCause = VoxStartupProfiler.classifyStartupRootCause()
        assertEquals(VoxStartupProfiler.ROOT_CAUSE_NETWORK, rootCause)

        val summary = VoxStartupProfiler.getProfileSummary()
        assertEquals(VoxStartupProfiler.ROOT_CAUSE_NETWORK, summary["rootCause"])
    }
}

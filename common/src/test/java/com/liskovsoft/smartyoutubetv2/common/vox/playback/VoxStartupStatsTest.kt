package com.liskovsoft.smartyoutubetv2.common.vox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StartupRepeatedSampleTest {
    @Before
    fun setup() {
        VoxStartupProfiler.clearSamples()
    }

    @Test
    fun testCollectsRepeatedSamplesAndRespectsCapacity() {
        for (i in 1..60) {
            val sample = VoxStartupProfiler.StartupSample(
                playbackSessionId = i.toLong(),
                totalStartupMs = (500 + i * 10).toLong(),
                slowestStage = VoxStartupProfiler.STAGE_BUFFER_FILL,
                rootCause = VoxStartupProfiler.ROOT_CAUSE_BUFFER_FILL,
                stages = mapOf(
                    VoxStartupProfiler.STAGE_REQUEST_START to 50L,
                    VoxStartupProfiler.STAGE_BUFFER_FILL to (300 + i * 5).toLong(),
                    VoxStartupProfiler.STAGE_FIRST_FRAME to 50L
                )
            )
            VoxStartupProfiler.recordSample(sample)
        }

        val samples = VoxStartupProfiler.getRecentSamples()
        assertEquals(VoxStartupProfiler.MAX_SAMPLES, samples.size)
        // Oldest (1..10) should have rolled out
        assertEquals(11L, samples.first().playbackSessionId)
        assertEquals(60L, samples.last().playbackSessionId)
    }
}

class StartupMedianCalculationTest {
    @Before
    fun setup() {
        VoxStartupProfiler.clearSamples()
    }

    @Test
    fun testMedianCalculatesCorrectlyForOddAndEven() {
        val latencies = listOf(200L, 300L, 400L, 500L, 600L)
        latencies.forEachIndexed { idx, ms ->
            VoxStartupProfiler.recordSample(
                VoxStartupProfiler.StartupSample(
                    playbackSessionId = idx.toLong(),
                    totalStartupMs = ms,
                    slowestStage = VoxStartupProfiler.STAGE_MANIFEST_FETCH,
                    rootCause = VoxStartupProfiler.ROOT_CAUSE_MANIFEST
                )
            )
        }

        val stats = VoxStartupProfiler.getAggregatedStatistics()
        assertEquals(5, stats["sampleCount"])
        assertEquals(400L, stats["medianTotalMs"])
        assertEquals(200L, stats["minTotalMs"])
        assertEquals(600L, stats["maxTotalMs"])
    }
}

class StartupP90Test {
    @Before
    fun setup() {
        VoxStartupProfiler.clearSamples()
    }

    @Test
    fun testP90IdentifiesTailLatency() {
        // 10 samples from 100ms to 1000ms
        for (i in 1..10) {
            VoxStartupProfiler.recordSample(
                VoxStartupProfiler.StartupSample(
                    playbackSessionId = i.toLong(),
                    totalStartupMs = (i * 100).toLong(),
                    slowestStage = VoxStartupProfiler.STAGE_DECODER_INIT,
                    rootCause = VoxStartupProfiler.ROOT_CAUSE_DECODER
                )
            )
        }

        val stats = VoxStartupProfiler.getAggregatedStatistics()
        assertEquals(10, stats["sampleCount"])
        assertEquals(500L, stats["medianTotalMs"])
        assertEquals(900L, stats["p90TotalMs"])
    }
}

class StartupSlowestStageTest {
    @Before
    fun setup() {
        VoxStartupProfiler.clearSamples()
    }

    @Test
    fun testAggregatedSlowestStageReflectsMostFrequentBottleNeck() {
        // 7 buffer fill, 3 decoder init
        for (i in 1..7) {
            VoxStartupProfiler.recordSample(
                VoxStartupProfiler.StartupSample(
                    playbackSessionId = i.toLong(),
                    totalStartupMs = 800L,
                    slowestStage = VoxStartupProfiler.STAGE_BUFFER_FILL,
                    rootCause = VoxStartupProfiler.ROOT_CAUSE_BUFFER_FILL,
                    stages = mapOf(VoxStartupProfiler.STAGE_BUFFER_FILL to 500L)
                )
            )
        }
        for (i in 8..10) {
            VoxStartupProfiler.recordSample(
                VoxStartupProfiler.StartupSample(
                    playbackSessionId = i.toLong(),
                    totalStartupMs = 700L,
                    slowestStage = VoxStartupProfiler.STAGE_DECODER_INIT,
                    rootCause = VoxStartupProfiler.ROOT_CAUSE_DECODER,
                    stages = mapOf(VoxStartupProfiler.STAGE_DECODER_INIT to 400L)
                )
            )
        }

        val stats = VoxStartupProfiler.getAggregatedStatistics()
        assertEquals(VoxStartupProfiler.STAGE_BUFFER_FILL, stats["slowestStageOverall"])
        @Suppress("UNCHECKED_CAST")
        val stageMedians = stats["stageMedians"] as Map<String, Long>
        assertEquals(500L, stageMedians[VoxStartupProfiler.STAGE_BUFFER_FILL])
        assertEquals(400L, stageMedians[VoxStartupProfiler.STAGE_DECODER_INIT])
    }
}

class StartupNoFalseRootCauseTest {
    @Before
    fun setup() {
        VoxStartupProfiler.clearSamples()
    }

    @Test
    fun testFastStartupDoesNotProduceFalseRootCause() {
        VoxStartupProfiler.onUserOpen(1001L, "AUTO", 1000L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_REQUEST, 40L, "OK", 1040L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_MANIFEST, 50L, "OK", 1090L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_TRACK_SELECT, 10L, "OK", 1100L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_DECODER_INIT, 60L, "OK", 1160L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_BUFFER_FILL, 80L, "OK", 1240L)
        VoxStartupProfiler.recordStage(VoxStartupProfiler.STAGE_FIRST_FRAME, 10L, "OK", 1250L)

        // Elapsed < 300ms for each stage -> root cause must be UNKNOWN
        val rootCause = VoxStartupProfiler.classifyStartupRootCause()
        assertEquals(VoxStartupProfiler.ROOT_CAUSE_UNKNOWN, rootCause)
    }
}

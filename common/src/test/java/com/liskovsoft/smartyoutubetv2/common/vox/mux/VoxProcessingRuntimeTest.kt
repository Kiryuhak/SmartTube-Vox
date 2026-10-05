package com.liskovsoft.smartyoutubetv2.common.vox.mux

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoxProcessingRuntimeTest {
    private fun source(count: Int = 20_000) = object : VoxSampleSource {
        var read = 0
        var closed = false
        override val trackInfo = VoxMuxTrackInfo(1, 1, VoxMuxTrackType.VIDEO, VoxMuxCodec.AVC,
            "video/avc", "Видео", "und", true, width = 640, height = 360)
        override val sourceSizeBytes = count * 512L
        override fun readNextSample(): VoxMuxSample? = if (read >= count) null else
            VoxMuxSample(1, read++ * 33_333L, 33_333L, read % 60 == 1, ByteArray(512))
        override fun close() { closed = true }
    }

    @Test fun samplesDoNotFloodListenersAndFinalizationPrecedesCompletion() {
        val dir = Files.createTempDirectory("vox-runtime").toFile()
        try {
            val source = source()
            var callbacks = 0
            var finalizing = false
            val output = java.io.File(dir, "out.mkv")
            val result = VoxMatroskaMuxer().mux(listOf(source), output,
                progressListener = { p ->
                    callbacks++
                    assertTrue(p.bytesProcessed <= p.totalInputBytes)
                    if (p.percent == 100) { assertTrue(finalizing); assertTrue(output.length() > 0) }
                }, onFinalizing = { finalizing = true })
            assertEquals(20_000L, result.videoSamplesCount)
            assertTrue(source.closed)
            assertTrue("Callbacks must be time-bounded, not per sample", callbacks < 100)
            assertFalse(java.io.File(dir, "out.mkv.tmp").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun movingProgressHasNoAbsoluteTimeoutAndIdenticalProgressDoesNotResetWatchdog() {
        var now = 0L
        val watchdog = VoxProcessingWatchdog(100) { now }
        try {
            repeat(30) { now += 90; watchdog.progress(it + 1L, it + 1L); assertFalse(watchdog.check()) }
            now += 90
            watchdog.progress(30, 30)
            now += 11
            assertTrue(watchdog.check())
        } finally { watchdog.close() }
    }

    @Test fun stalledInputIsClosedAndNoFinalFileIsPublished() {
        val dir = Files.createTempDirectory("vox-stall").toFile()
        val released = CountDownLatch(1)
        val delegate = source(1)
        val blocked = object : VoxSampleSource by delegate {
            override fun readNextSample(): VoxMuxSample? { released.await(3, TimeUnit.SECONDS); return null }
            override fun close() { released.countDown() }
        }
        try {
            val output = java.io.File(dir, "out.mkv")
            try { VoxMatroskaMuxer(50).mux(listOf(blocked), output); fail("Must fail stalled input") }
            catch (expected: VoxProcessingStalledException) { assertFalse(output.exists()) }
            assertEquals(0L, released.count)
        } finally { dir.deleteRecursively() }
    }
}

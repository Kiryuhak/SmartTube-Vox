package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxDownloadOverlayStateTest {

    @Test
    fun testDownloadProgressFormattingKnownTotal() {
        val downloaded = 370L * 1024L * 1024L
        val total = 1024L * 1024L * 1024L

        val downloadedFormatted = VoxDownloadSizeFormatter.formatBytes(downloaded)
        val totalFormatted = VoxDownloadSizeFormatter.formatBytes(total)

        assertNotNull(downloadedFormatted)
        assertNotNull(totalFormatted)
        assertTrue(downloadedFormatted.contains("МБ"))
        assertTrue(totalFormatted.contains("ГБ"))
    }

    @Test
    fun testSpeedEstimatorAndEtaCalculation() {
        val estimator = VoxDownloadSpeedEstimator()
        estimator.reset()

        val baseTime = 1000000L
        estimator.update(0L, 100_000_000L, nowMs = baseTime)

        // 5 seconds later, downloaded 20 MB -> speed 4 MB/s -> remaining 80 MB -> ETA 20 sec
        val etaSec = estimator.update(20_000_000L, 100_000_000L, nowMs = baseTime + 5000L)
        assertNotNull(etaSec)
        assertTrue(etaSec!! > 0L)
    }

    @Test
    fun testHudLabelFormattingAcrossStates() {
        val labelDownloading = VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.DOWNLOADING_VIDEO, 42)
        assertEquals("Загрузка 42%", labelDownloading)

        val labelCompleted = VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.COMPLETED, 100)
        assertEquals("Скачано", labelCompleted)

        val labelFailed = VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.FAILED, 0)
        assertEquals("Ошибка загрузки", labelFailed)

        val labelIdle = VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.IDLE, null)
        assertEquals("Скачать", labelIdle)
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.ota

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OtaProgressTest {

    private lateinit var estimator: VoxOtaProgressEstimator

    @Before
    fun setUp() {
        estimator = VoxOtaProgressEstimator(alpha = 0.5f, minAccumulationMs = 1000L)
    }

    @Test
    fun formatsKnownTotalProgressCorrectly() {
        val t0 = 10000L
        val totalBytes = 32_400_000L // ~32.4 MB
        val downloadedBytes = 12_800_000L // ~12.8 MB (approx 39%)

        estimator.update(0L, totalBytes, t0)
        val progress = estimator.update(downloadedBytes, totalBytes, t0 + 1000L)

        assertEquals(VoxOtaState.DOWNLOADING, progress.state)
        assertEquals(39, progress.percent)
        assertEquals(totalBytes, progress.totalBytes)
        assertEquals(downloadedBytes, progress.bytesDownloaded)

        val title = VoxOtaProgressEstimator.formatTitle(progress)
        assertTrue(title.contains("39%"))

        val bytesStr = VoxOtaProgressEstimator.formatBytesProgress(progress)
        assertTrue(bytesStr.contains("/"))
        assertTrue(bytesStr.contains("МБ"))

        val speedEtaStr = VoxOtaProgressEstimator.formatSpeedAndEta(progress)
        assertTrue(speedEtaStr.contains("МБ/с") || speedEtaStr.contains("КБ/с"))
        assertTrue(speedEtaStr.contains("Осталось"))
    }

    @Test
    fun formatsUnknownTotalWithoutFakePercent() {
        val t0 = 10000L
        val downloadedBytes = 12_800_000L

        estimator.update(0L, null, t0)
        val progress = estimator.update(downloadedBytes, null, t0 + 1000L)

        assertNull(progress.percent)
        assertNull(progress.totalBytes)
        assertNull(progress.etaSec)

        val title = VoxOtaProgressEstimator.formatTitle(progress)
        assertFalse(title.contains("%"))
        assertTrue(title.contains("Скачивание обновления"))

        val bytesStr = VoxOtaProgressEstimator.formatBytesProgress(progress)
        assertFalse(bytesStr.contains("/"))
        assertTrue(bytesStr.contains("МБ"))
    }

    @Test
    fun coversAllRequiredOtaStates() {
        for (state in VoxOtaState.values()) {
            val progress = VoxOtaProgress(state = state)
            val title = VoxOtaProgressEstimator.formatTitle(progress)
            assertTrue(title.isNotEmpty())
        }
    }
}

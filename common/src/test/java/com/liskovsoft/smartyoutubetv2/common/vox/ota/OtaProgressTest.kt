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
    fun formatsScalingAndSpeedAndEtaStrictly() {
        // KB
        assertEquals("500 КБ", VoxOtaProgressEstimator.formatBytes(500 * 1024L))
        // MB
        val mbStr = VoxOtaProgressEstimator.formatBytes(63 * 1024 * 1024L)
        assertTrue(mbStr.contains("63") && mbStr.contains("МБ"))
        // GB
        val gbStr = VoxOtaProgressEstimator.formatBytes((1.5 * 1024 * 1024 * 1024).toLong())
        assertTrue(gbStr.contains("1,5") || gbStr.contains("1.5"))
        assertTrue(gbStr.contains("ГБ"))

        // Unknown total
        val unknownTotalStr = VoxOtaProgressEstimator.formatBytesProgress(63 * 1024 * 1024L, null)
        assertTrue(unknownTotalStr.startsWith("Скачано:"))
        assertTrue(unknownTotalStr.contains("63") && unknownTotalStr.contains("МБ"))

        // Speed format
        val speedMb = VoxOtaProgressEstimator.formatSpeed(5.8 * 1024.0 * 1024.0)
        assertTrue(speedMb.startsWith("Скорость:"))
        assertTrue(speedMb.contains("5,8") || speedMb.contains("5.8"))
        assertTrue(speedMb.contains("МБ/с"))

        val speedKb = VoxOtaProgressEstimator.formatSpeed(350.0 * 1024.0)
        assertEquals("Скорость: 350 КБ/с", speedKb)

        val speedZero = VoxOtaProgressEstimator.formatSpeed(0.0)
        assertEquals("Скорость: 0 КБ/с", speedZero)

        // ETA format
        assertEquals("Осталось: вычисляется…", VoxOtaProgressEstimator.formatEta(null))
        assertEquals("Осталось: вычисляется…", VoxOtaProgressEstimator.formatEta(0L))
        assertEquals("Осталось примерно: 15 сек", VoxOtaProgressEstimator.formatEta(15L))
        assertEquals("Осталось примерно: 2 мин", VoxOtaProgressEstimator.formatEta(120L))
    }

    @Test
    fun testUniversalAssetNotFoundErrorCode() {
        val err = VoxOtaErrorCode.UNIVERSAL_ASSET_NOT_FOUND
        assertEquals("Не найден универсальный пакет обновления.", err.userMessageRu)
        assertEquals(err, VoxOtaErrorCode.OTA_UNIVERSAL_ASSET_NOT_FOUND)
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

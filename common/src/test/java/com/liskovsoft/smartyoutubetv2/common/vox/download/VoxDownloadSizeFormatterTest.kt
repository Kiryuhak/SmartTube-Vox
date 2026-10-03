package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxDownloadSizeFormatterTest {

    @Test
    fun testFormatBytesZeroAndSmall() {
        assertEquals("0 Б", VoxDownloadSizeFormatter.formatBytes(0L))
        assertEquals("500 Б", VoxDownloadSizeFormatter.formatBytes(500L))
        assertEquals("1 КБ", VoxDownloadSizeFormatter.formatBytes(1024L))
        assertEquals("150 КБ", VoxDownloadSizeFormatter.formatBytes(150 * 1024L))
    }

    @Test
    fun testFormatBytesMegabytesAndGigabytes() {
        assertEquals("12,5 МБ", VoxDownloadSizeFormatter.formatBytes((12.5 * 1024 * 1024).toLong()))
        assertEquals("245 МБ", VoxDownloadSizeFormatter.formatBytes(245 * 1024 * 1024L))
        assertEquals("824 МБ", VoxDownloadSizeFormatter.formatBytes(824 * 1024 * 1024L))
        assertEquals("1,0 ГБ", VoxDownloadSizeFormatter.formatBytes(1024 * 1024 * 1024L))
        assertEquals("2,5 ГБ", VoxDownloadSizeFormatter.formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("15,2 ГБ", VoxDownloadSizeFormatter.formatBytes((15.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testFormatProgressBytes() {
        val downloaded = 245 * 1024 * 1024L
        val total = 1024 * 1024 * 1024L
        assertEquals("245 МБ / 1,0 ГБ", VoxDownloadSizeFormatter.formatProgressBytes(downloaded, total))

        val equalBytes = 824 * 1024 * 1024L
        assertEquals("824 МБ / 824 МБ", VoxDownloadSizeFormatter.formatProgressBytes(equalBytes, equalBytes))
    }

    @Test
    fun testFormatProgressBytesUnknownTotal() {
        val downloaded = 245 * 1024 * 1024L
        assertEquals("245 МБ загружено", VoxDownloadSizeFormatter.formatProgressBytes(downloaded, null))
        assertEquals("245 МБ загружено", VoxDownloadSizeFormatter.formatProgressBytes(downloaded, 0L))
        assertEquals("245 МБ загружено", VoxDownloadSizeFormatter.formatProgressBytes(downloaded, -1L))
    }

    @Test
    fun testFormatActiveProgress() {
        val downloaded = 245 * 1024 * 1024L
        val total = 1024 * 1024 * 1024L
        assertEquals(
            "Загрузка · 245 МБ / 1,0 ГБ · 24%",
            VoxDownloadSizeFormatter.formatActiveProgress(downloaded, total, 24)
        )
        assertEquals(
            "Загрузка · 245 МБ загружено",
            VoxDownloadSizeFormatter.formatActiveProgress(downloaded, null, null)
        )
    }
}

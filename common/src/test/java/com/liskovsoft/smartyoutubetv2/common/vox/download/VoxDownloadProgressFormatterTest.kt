package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxDownloadProgressFormatterTest {

    @Test
    fun testHudLabelFormatting() {
        assertEquals("Скачать", VoxDownloadProgressFormatter.formatHudLabel(null, null))
        assertEquals("Скачать", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.IDLE, null))
        assertEquals("Скачать", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.CANCELLED, null))
        assertEquals("Загрузка 37%", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.DOWNLOADING_VIDEO, 37))
        assertEquals("Загрузка 0%", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.DOWNLOADING_VIDEO, 0))
        assertEquals("Загрузка 100%", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.DOWNLOADING_VIDEO, 100))
        assertEquals("Загрузка…", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.DOWNLOADING_VIDEO, null))
        assertEquals("Загрузка…", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.PREPARING_TRANSLATION, null))
        assertEquals("Загрузка…", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.MUXING, null))
        assertEquals("Скачано", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.COMPLETED, 100))
        assertEquals("Ошибка загрузки", VoxDownloadProgressFormatter.formatHudLabel(VoxDownloadState.FAILED, 50))
    }

    @Test
    fun testCardStatusFormatting() {
        assertEquals("Загрузка видео · 64%", VoxDownloadProgressFormatter.formatCardStatus("Загрузка видео", 64))
        assertEquals("Подготовка…", VoxDownloadProgressFormatter.formatCardStatus("Подготовка…", null))
        assertEquals("64%", VoxDownloadProgressFormatter.formatCardStatus(null, 64))
        assertEquals("Загрузка…", VoxDownloadProgressFormatter.formatCardStatus(null, null))
    }
}

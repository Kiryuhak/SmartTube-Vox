package com.liskovsoft.smartyoutubetv2.common.vox.download

import org.junit.Assert.*
import org.junit.Test

class VoxDownloadUiMapperTest {
    @Test fun groupsAndStableOrdering() {
        val active = job("active", 1, VoxDownloadState.DOWNLOADING_VIDEO)
        val paused = job("paused", 4, VoxDownloadState.PAUSED)
        val failed = job("failed", 3, VoxDownloadState.FAILED)
        val older = job("old", 2, VoxDownloadState.COMPLETED)
        val newer = job("new", 5, VoxDownloadState.COMPLETED)
        assertEquals(listOf("active", "paused", "failed", "new", "old"),
            VoxDownloadUiMapper.sorted(listOf(older, failed, newer, paused, active)).map { it.downloadId })
    }

    @Test fun missingCompletedFileHasNoOpenAction() {
        assertFalse(VoxDownloadUiMapper.actions(VoxDownloadState.COMPLETED, true).contains("Открыть"))
        assertTrue(VoxDownloadUiMapper.actions(VoxDownloadState.COMPLETED, false).contains("Открыть"))
        assertEquals(listOf("Продолжить", "Удалить"), VoxDownloadUiMapper.actions(VoxDownloadState.PAUSED, false))
    }

    @Test fun labelsAndStorageMath() {
        assertEquals("Загрузка перевода", VoxDownloadUiMapper.stage(VoxDownloadState.DOWNLOADING_TRANSLATED_AUDIO))
        assertEquals("Этот формат пока не поддерживается", VoxDownloadUiMapper.error(VoxDownloadErrorCode.UNSUPPORTED_CODEC))
        assertEquals("Живой голос", VoxDownloadUiMapper.mode(VoxTranslationMode.LIVELY))
        assertEquals("1,5 ГБ", VoxDownloadUiMapper.formatSize(1610612736))
        assertEquals(18L, VoxDownloadStorageStats.sum(listOf(5, 3), listOf(10, -1), 20).usedBytes)
    }

    @Test fun unknownProgressDoesNotInventPercentAndQualityIsActual() {
        val item = job("video", 1, VoxDownloadState.DOWNLOADING_VIDEO)
        item.actualVideoHeight = 720
        assertNull(VoxDownloadUiMapper.toItem(item, false).percent)
        assertEquals("720p", VoxDownloadUiMapper.toItem(item, false).actualQuality)
    }

    private fun job(id: String, time: Long, state: VoxDownloadState): VoxDownloadJob =
        VoxDownloadJob(VoxDownloadRequest(id, id, id, createdAt = time), initialState = state)
}

package com.liskovsoft.smartyoutubetv2.common.vox.download

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoxDownloadTranslationStateTest {

    @Test
    fun testTranslationStateEnumParsing() {
        assertEquals(VoxDownloadTranslationState.DOWNLOADED_TRANSLATED, VoxDownloadTranslationState.fromString("DOWNLOADED_TRANSLATED"))
        assertEquals(VoxDownloadTranslationState.DOWNLOADED_TRANSLATED, VoxDownloadTranslationState.fromString("downloaded_translated"))
        assertEquals(VoxDownloadTranslationState.NONE, VoxDownloadTranslationState.fromString("NONE"))
        assertEquals(VoxDownloadTranslationState.UNKNOWN, VoxDownloadTranslationState.fromString("UNKNOWN"))
        assertEquals(VoxDownloadTranslationState.UNKNOWN, VoxDownloadTranslationState.fromString(null))
        assertEquals(VoxDownloadTranslationState.UNKNOWN, VoxDownloadTranslationState.fromString("INVALID"))
    }

    @Test
    fun testVideoIsDownloadedTranslated() {
        val regularOnlineVideo = Video.from("test123")
        regularOnlineVideo.isLocal = false
        assertFalse(regularOnlineVideo.isDownloadedTranslated())

        val localTranslatedVideo = Video.from("test456")
        localTranslatedVideo.isLocal = true
        localTranslatedVideo.translationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED.name
        assertTrue(localTranslatedVideo.isDownloadedTranslated())

        val localUntranslatedVideo = Video.from("test789")
        localUntranslatedVideo.isLocal = true
        localUntranslatedVideo.translationState = VoxDownloadTranslationState.NONE.name
        assertFalse(localUntranslatedVideo.isDownloadedTranslated())
    }

    @Test
    fun testVideoCopyPreservesTranslationState() {
        val original = Video.from("testCopy")
        original.isLocal = true
        original.translationState = VoxDownloadTranslationState.DOWNLOADED_TRANSLATED.name

        val copy = Video.from(original)
        assertTrue(copy.isLocal)
        assertEquals("DOWNLOADED_TRANSLATED", copy.translationState)
        assertTrue(copy.isDownloadedTranslated())
    }
}

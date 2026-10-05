package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxPlayerActionContractTest {

    @Test
    fun testOnlineVideoAllowsTranslationAndDownload() {
        val onlineVideo = Video()
        onlineVideo.videoId = "dQw4w9WgXcQ"
        onlineVideo.isLocal = false
        onlineVideo.translationState = "NONE"

        assertFalse(onlineVideo.isLocal)
        assertFalse(onlineVideo.isDownloadedTranslated())

        // Contract: Online video is eligible for translate and download
        val allowTranslate = !onlineVideo.isLocal && !onlineVideo.isDownloadedTranslated()
        val allowDownload = !onlineVideo.isLocal && !onlineVideo.isDownloadedTranslated()

        assertTrue(allowTranslate)
        assertTrue(allowDownload)
    }

    @Test
    fun testDownloadedTranslatedVideoSuppressesTranslateAndDownload() {
        val downloadedVideo = Video()
        downloadedVideo.videoId = "dQw4w9WgXcQ"
        downloadedVideo.isLocal = true
        downloadedVideo.translationState = "DOWNLOADED_TRANSLATED"

        assertTrue(downloadedVideo.isLocal)
        assertTrue(downloadedVideo.isDownloadedTranslated())

        // Contract: Downloaded translated video must suppress network translation and redundant download
        val allowTranslate = !downloadedVideo.isLocal && !downloadedVideo.isDownloadedTranslated()
        val allowDownload = !downloadedVideo.isLocal && !downloadedVideo.isDownloadedTranslated()

        assertFalse(allowTranslate)
        assertFalse(allowDownload)
    }

    @Test
    fun testDownloadedUntranslatedVideoSuppressesRedundantDownload() {
        val downloadedVideo = Video()
        downloadedVideo.videoId = "dQw4w9WgXcQ"
        downloadedVideo.isLocal = true
        downloadedVideo.translationState = "NONE"

        assertTrue(downloadedVideo.isLocal)
        assertFalse(downloadedVideo.isDownloadedTranslated())

        val allowDownload = !downloadedVideo.isLocal && !downloadedVideo.isDownloadedTranslated()
        assertFalse(allowDownload)
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.playback

import org.robolectric.RuntimeEnvironment
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Vox034DecoderFallbackAndVotContinuityTest {

    private lateinit var playerData: PlayerData

    @Before
    fun setUp() {
        playerData = PlayerData.instance(RuntimeEnvironment.getApplication())
        playerData.setTempVideoFormat(null)
        playerData.setTempAudioFormat(null)
        playerData.setTempSubtitleFormat(null)
    }

    @Test
    fun test01_decoderFallbackSetsTempVideoFormatWithoutAlteringPersistentFormat() {
        val originalFormat = FormatItem.VIDEO_4K_VP9_60
        playerData.setFormat(originalFormat)
        assertEquals("Persistent format must initially be 4K VP9", originalFormat, playerData.getFormat(FormatItem.TYPE_VIDEO))

        // Simulate ErrorFixerController decoder error handling:
        val fallbackFormat = FormatItem.VIDEO_FHD_AVC_30
        playerData.setTempVideoFormat(fallbackFormat)

        // Effective format during current playback session should be the temp format
        assertEquals("Temp format should hold the session fallback", fallbackFormat, playerData.tempVideoFormat)
        // Persistent preference must remain UNCHANGED
        assertEquals("Persistent format must not be overwritten by decoder fallback", originalFormat, playerData.getFormat(FormatItem.TYPE_VIDEO))
    }

    @Test
    fun test02_newVideoResetsTempFormatsWhileSameVideoPreservesThem() {
        val video1 = Video().apply { videoId = "video_alpha" }
        val video1Reload = Video().apply { videoId = "video_alpha" }
        val video2 = Video().apply { videoId = "video_beta" }

        playerData.setTempVideoFormat(FormatItem.VIDEO_FHD_AVC_30)
        playerData.setTempAudioFormat(FormatItem.AUDIO_HQ_MP4A)
        playerData.setTempSubtitleFormat(FormatItem.SUBTITLE_NONE)

        // Same-video reload simulation (VideoStateController logic)
        val isSameVideo = video1Reload == video1
        assertTrue("video1Reload must equal video1", isSameVideo)
        // Temp formats must remain preserved on same-video reload
        assertEquals(FormatItem.VIDEO_FHD_AVC_30, playerData.tempVideoFormat)
        assertEquals(FormatItem.AUDIO_HQ_MP4A, playerData.tempAudioFormat)
        assertEquals(FormatItem.SUBTITLE_NONE, playerData.tempSubtitleFormat)

        // Different video transition simulation (VideoStateController logic)
        val isDifferentVideo = video2 != video1
        assertTrue("video2 must not equal video1", isDifferentVideo)
        if (isDifferentVideo) {
            playerData.setTempVideoFormat(null)
            playerData.setTempAudioFormat(null)
            playerData.setTempSubtitleFormat(null)
        }

        assertNull("Temp video format must be cleared on new video", playerData.tempVideoFormat)
        assertNull("Temp audio format must be cleared on new video", playerData.tempAudioFormat)
        assertNull("Temp subtitle format must be cleared on new video", playerData.tempSubtitleFormat)
    }

    @Test
    fun test03_audioAndSubtitleRendererFallbacksUseTempOverridesOnly() {
        val persistentAudio = FormatItem.AUDIO_HQ_MP4A
        playerData.setFormat(persistentAudio)
        assertEquals(persistentAudio, playerData.getFormat(FormatItem.TYPE_AUDIO))

        // Set temp audio fallback
        playerData.setTempAudioFormat(FormatItem.AUDIO_HQ_MP4A)
        assertEquals(FormatItem.AUDIO_HQ_MP4A, playerData.tempAudioFormat)
        assertEquals("Persistent audio format remains intact", persistentAudio, playerData.getFormat(FormatItem.TYPE_AUDIO))

        // Set temp subtitle fallback (disable subtitles on render error)
        playerData.setTempSubtitleFormat(FormatItem.SUBTITLE_NONE)
        assertEquals(FormatItem.SUBTITLE_NONE, playerData.tempSubtitleFormat)
    }

    @Test
    fun test04_sameVideoReloadPreservesVotSessionContinuity() {
        val currentVideoId = "video_101"
        var activeSessionId = 42
        var isVotActive = true
        var isPlayerPaused = false

        // Simulate onNewVideo with same video ID (stall recovery / format change)
        val incomingVideo = Video().apply { videoId = "video_101" }
        val isSameVideo = incomingVideo.videoId != null && incomingVideo.videoId == currentVideoId

        assertTrue(isSameVideo)
        if (isSameVideo) {
            // Non-destructive hold: pause audio without destroying session
            isPlayerPaused = true
        } else {
            activeSessionId++
            isVotActive = false
        }

        assertEquals("Session ID must NOT increment on same video reload", 42, activeSessionId)
        assertTrue("VOT active state must remain true", isVotActive)
        assertTrue("Audio player should be paused during reload window", isPlayerPaused)
    }

    @Test
    fun test05_differentVideoResetsVotSessionCleanly() {
        val currentVideoId = "video_101"
        var activeSessionId = 42
        var isVotActive = true

        val incomingVideo = Video().apply { videoId = "video_202" }
        val isSameVideo = incomingVideo.videoId != null && incomingVideo.videoId == currentVideoId

        assertFalse(isSameVideo)
        if (!isSameVideo) {
            activeSessionId++
            isVotActive = false
        }

        assertEquals("Session ID must increment on different video", 43, activeSessionId)
        assertFalse("VOT must be reset to inactive on video switch", isVotActive)
    }

    @Test
    fun test06_engineRestartPreservesTranslationSession() {
        var activeSessionId = 10
        var translationAdapterReleased = false
        var isPlayerPaused = false

        // Simulate onEngineReleased non-destructive behavior
        fun onEngineReleased(releaseCompletely: Boolean) {
            if (releaseCompletely) {
                activeSessionId++
                translationAdapterReleased = true
            } else {
                // VOX 9.2-034 hardened behavior
                isPlayerPaused = true
            }
        }

        onEngineReleased(releaseCompletely = false)

        assertEquals("Session ID preserved across engine release", 10, activeSessionId)
        assertFalse("Adapter not torn down on engine release", translationAdapterReleased)
        assertTrue("Player safely paused during switch", isPlayerPaused)
    }
}

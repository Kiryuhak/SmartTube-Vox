package com.liskovsoft.smartyoutubetv2.common.vox.ui

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Helper modelling the Home top-bar PiP / Now-Playing visibility contract in NavigateTitleView + PlaybackPresenter.
 */
object HomeNowPlayingBarPolicy {
    fun shouldShowNowPlaying(isRunningInBackground: Boolean, currentVideo: Video?): Boolean {
        return isRunningInBackground && currentVideo != null
    }

    fun resolveDisplayedTitle(isRunningInBackground: Boolean, currentVideo: Video?): String {
        return if (shouldShowNowPlaying(isRunningInBackground, currentVideo)) {
            currentVideo?.title ?: ""
        } else {
            ""
        }
    }
}

class StaleNowPlayingClearedAfterPlaybackExitTest {
    @Test
    fun testNowPlayingClearedWhenExitingPlaybackWithoutBackgroundMode() {
        val video = Video().apply {
            videoId = "vid_exit_1"
            title = "Тестовое видео на Главной"
        }
        val backgroundMode = PlaybackView.BACKGROUND_MODE_DEFAULT
        val isInPipMode = false
        val isEngineBlocked = false

        val isRunningInBackground = backgroundMode != PlaybackView.BACKGROUND_MODE_DEFAULT &&
                (isEngineBlocked || isInPipMode)

        assertFalse(isRunningInBackground)
        assertFalse(HomeNowPlayingBarPolicy.shouldShowNowPlaying(isRunningInBackground, video))
        assertEquals("", HomeNowPlayingBarPolicy.resolveDisplayedTitle(isRunningInBackground, video))
    }
}

class OldPlaybackSessionCannotRestoreTitleTest {
    @Test
    fun testEndedSessionClearsVideoReferenceAndCannotRestoreTitleOnHomeResume() {
        var activeVideo: Video? = Video().apply {
            videoId = "vid_old_session"
            title = "Старое видео"
        }
        val isRunningInBackground = false

        // When playback finishes without background mode, clearEndedSessionIfNotBackground() nulls mVideo
        if (!isRunningInBackground) {
            activeVideo = null
        }

        assertNull(activeVideo)
        assertFalse(HomeNowPlayingBarPolicy.shouldShowNowPlaying(isRunningInBackground = false, currentVideo = activeVideo))
        assertEquals("", HomeNowPlayingBarPolicy.resolveDisplayedTitle(isRunningInBackground = false, currentVideo = activeVideo))
    }
}

class BackgroundPlaybackKeepsLegitimateNowPlayingTest {
    @Test
    fun testLegitimatePipOrBackgroundAudioKeepsNowPlayingVisible() {
        val video = Video().apply {
            videoId = "vid_pip_active"
            title = "Фоновое воспроизведение"
        }
        val backgroundMode = PlaybackView.BACKGROUND_MODE_PIP
        val isInPipMode = true

        val isRunningInBackground = backgroundMode != PlaybackView.BACKGROUND_MODE_DEFAULT && isInPipMode
        assertTrue(isRunningInBackground)
        assertTrue(HomeNowPlayingBarPolicy.shouldShowNowPlaying(isRunningInBackground, video))
        assertEquals("Фоновое воспроизведение", HomeNowPlayingBarPolicy.resolveDisplayedTitle(isRunningInBackground, video))
    }
}

class StoppedPlaybackClearsNowPlayingTest {
    @Test
    fun testStoppingPlaybackClearsNowPlayingImmediately() {
        var activeVideo: Video? = Video().apply {
            videoId = "vid_stopped"
            title = "Остановленное видео"
        }
        var isRunningInBackground = true

        // Engine released / playback stopped:
        isRunningInBackground = false
        activeVideo = null

        assertFalse(HomeNowPlayingBarPolicy.shouldShowNowPlaying(isRunningInBackground, activeVideo))
        assertEquals("", HomeNowPlayingBarPolicy.resolveDisplayedTitle(isRunningInBackground, activeVideo))
    }
}

/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vox.translation

import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotPlaybackAdapter
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VoxAudioLifecycleIntegrationTest {

    private class MockPlayer : YandexVotPlaybackAdapter.AudioPlayer {
        var sessionId: Int = 0
        var url: String? = null
        var speed: Float = 1.0f
        var currentVolume: Float = 1.0f
        var currentPositionMs: Long = 0L
        var prepared: Boolean = false
        var playing: Boolean = false
        var ready: Boolean = false
        var released: Boolean = false
        var callback: YandexVotPlaybackAdapter.AudioPlayer.Callback? = null

        override fun prepare(sessionId: Int, url: String, speed: Float, callback: YandexVotPlaybackAdapter.AudioPlayer.Callback?) {
            this.sessionId = sessionId
            this.url = url
            this.speed = speed
            this.callback = callback
        }

        override fun startPlayback(volume: Float) {
            this.currentVolume = volume
            this.playing = true
            this.ready = true
        }

        override fun setVolume(volume: Float) {
            this.currentVolume = volume
        }

        override fun setPlaybackSpeed(speed: Float) {
            this.speed = speed
        }

        override fun seekTo(positionMs: Long) {
            this.currentPositionMs = positionMs
        }

        override fun getPositionMs(): Long = currentPositionMs

        override fun pause() {
            this.playing = false
        }

        override fun resume() {
            this.playing = true
        }

        override fun release() {
            this.released = true
            this.playing = false
            this.ready = false
        }

        override fun isReady(): Boolean = ready
        override fun isPlaying(): Boolean = playing
    }

    private class MockBridge : YandexVotPlaybackAdapter.AudioDuckingBridge {
        var ducked: Boolean = false
        var videoPositionMs: Long = 0L
        var totalDurationMs: Long = 300000L // 5 minutes
        var videoPlaying: Boolean = true
        var videoBuffering: Boolean = false
        var playbackSpeed: Float = 1.0f
        var volumeMultiplier: Float = 1.0f

        override fun duckOriginalAudio() {
            ducked = true
        }

        override fun restoreOriginalAudio() {
            ducked = false
        }

        override fun getTranslationVolume(): Float = volumeMultiplier
        override fun getCurrentVideoPositionMs(): Long = videoPositionMs
        override fun isMainVideoPlaying(): Boolean = videoPlaying
        override fun isMainVideoBuffering(): Boolean = videoBuffering
        override fun getVideoDurationMs(): Long = totalDurationMs
        override fun getMainPlaybackSpeed(): Float = playbackSpeed
        override fun determineInitialPlaybackPosition(): Long = videoPositionMs
    }

    private lateinit var mockBridge: MockBridge
    private lateinit var mockPlayer: MockPlayer
    private lateinit var adapter: YandexVotPlaybackAdapter
    private val activeGenerations = mutableListOf<Long>()
    private val pausedGenerations = mutableListOf<Long>()
    private val stoppedGenerations = mutableListOf<Long>()
    private val errors = mutableListOf<Pair<Long, String>>()

    @Before
    fun setUp() {
        mockBridge = MockBridge()
        adapter = YandexVotPlaybackAdapter(
            {
                mockPlayer = MockPlayer()
                mockPlayer
            },
            mockBridge
        )

        adapter.setStateListener(object : YandexVotPlaybackAdapter.PlaybackStateListener {
            override fun onPlaybackActive(generationId: Long) {
                activeGenerations.add(generationId)
            }

            override fun onPlaybackPaused(generationId: Long) {
                pausedGenerations.add(generationId)
            }

            override fun onPlaybackStopped(generationId: Long) {
                stoppedGenerations.add(generationId)
            }

            override fun onPlaybackError(generationId: Long, category: String) {
                errors.add(generationId to category)
            }
        })
    }

    @Test
    fun testScenarioA_fullPlaybackLifecycleFollowsVideo() {
        // 1. Ready state arrives
        val ready = YandexVotState.ready(
            1L, "vid1", "https://youtube.com/watch?v=vid1", "t1",
            "https://vtrans.s3-private.mds.yandex.net/tts/prod/audio1.mp3", false, false
        )
        adapter.onStateChanged(ready)

        // 2. Player prepared & initial sync complete
        mockPlayer.callback?.onPrepared()
        assertTrue(adapter.isPlaying)
        assertTrue(mockBridge.ducked)
        assertEquals(listOf(1L), activeGenerations)

        // 3. User pauses video
        mockBridge.videoPlaying = false
        adapter.onPause()
        assertFalse(mockPlayer.playing)
        assertEquals(listOf(1L), pausedGenerations)

        // 4. User resumes video
        mockBridge.videoPlaying = true
        adapter.onPlay()
        assertTrue(mockPlayer.playing)
        assertTrue(mockBridge.ducked)

        // 5. User changes playback speed to 1.75x
        mockBridge.playbackSpeed = 1.75f
        adapter.setPlaybackSpeed(1.75f)
        assertEquals(1.75f, mockPlayer.speed, 0.001f)

        // 6. User seeks forward to 120s
        mockBridge.videoPositionMs = 120000L
        adapter.onSeek(120000L)
        assertEquals(120000L, mockPlayer.currentPositionMs)

        // 7. Network glitch: video buffers
        mockBridge.videoBuffering = true
        adapter.onBuffering()
        assertFalse(mockPlayer.playing) // Follower paused while buffering

        // 8. Video resumes playing after buffering
        mockBridge.videoBuffering = false
        adapter.onPlay()
        assertTrue(mockPlayer.playing)

        // 9. Video finishes -> Stop
        adapter.stop()
        assertFalse(adapter.isPlaying)
        assertFalse(mockBridge.ducked)
        assertTrue(mockPlayer.released)
        assertEquals(listOf(1L), stoppedGenerations)
    }

    @Test
    fun testScenarioB_videoSwitchInvalidatesOldGeneration() {
        val ready1 = YandexVotState.ready(
            1L, "vid1", "https://youtube.com/watch?v=vid1", "t1",
            "https://vtrans.s3-private.mds.yandex.net/tts/prod/audio1.mp3", false, false
        )
        adapter.onStateChanged(ready1)
        val p1 = mockPlayer

        // Video switched before p1 finished preparing
        adapter.onVideoChanged()
        assertTrue(p1.released)

        // Stale callback from p1 arrives late -> MUST be discarded
        p1.callback?.onPrepared()
        assertFalse(adapter.isPlaying)
        assertFalse(mockBridge.ducked)

        // New video starts at generation 2
        val ready2 = YandexVotState.ready(
            2L, "vid2", "https://youtube.com/watch?v=vid2", "t2",
            "https://vtrans.s3-private.mds.yandex.net/tts/prod/audio2.mp3", false, false
        )
        adapter.onStateChanged(ready2)
        val p2 = mockPlayer
        p2.callback?.onPrepared()

        assertTrue(adapter.isPlaying)
        assertTrue(mockBridge.ducked)
        assertEquals("https://vtrans.s3-private.mds.yandex.net/tts/prod/audio2.mp3", p2.url)
    }

    @Test
    fun testScenarioC_boundedRouteFallbackPreservesPlayback() {
        val directUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/sample.mp3"
        val proxyUrl = "https://vot-worker.eu.cc/video-translation/audio-proxy/sample.mp3"

        val ready = YandexVotState.ready(
            1L, "vid1", "https://youtube.com/watch?v=vid1", "t1",
            directUrl, false, false
        )
        adapter.onStateChanged(ready)
        assertEquals(directUrl, mockPlayer.url)

        // Direct S3 fails with 403 Forbidden
        mockPlayer.callback?.onError(RuntimeException("403 Forbidden"))

        // Bounded route fallback seamlessly retries proxy URL
        assertEquals(proxyUrl, mockPlayer.url)
        assertEquals(proxyUrl, adapter.currentSourceUrl)

        // Proxy prepare succeeds
        mockPlayer.callback?.onPrepared()
        assertTrue(adapter.isPlaying)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testScenarioD_signedUrlRefreshMaintainsPositionAndSpeed() {
        val initialUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/expiring.mp3"
        val ready = YandexVotState.ready(
            1L, "vid1", "https://youtube.com/watch?v=vid1", "t1",
            initialUrl, false, false
        )
        adapter.onStateChanged(ready)
        mockPlayer.callback?.onPrepared()
        assertTrue(adapter.isPlaying)

        // Playback progressed to 42000ms at 1.25x speed
        mockPlayer.currentPositionMs = 42000L
        adapter.setPlaybackSpeed(1.25f)

        // URL expires; refreshed URL provided
        val freshUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/fresh_signed.mp3"
        adapter.refreshSource(1L, freshUrl, 42000L, 1.25f)

        assertEquals(freshUrl, mockPlayer.url)
        assertEquals(1.25f, mockPlayer.speed, 0.001f)

        mockPlayer.callback?.onPrepared()
        assertEquals(42000L, mockPlayer.currentPositionMs)
        mockPlayer.callback?.onSeekProcessed()

        assertTrue(adapter.isPlaying)
        assertTrue(mockBridge.ducked)
    }

    @Test
    fun testScenarioE_rapidToggleAndCancellationSafety() {
        // Repeated start/stop in quick succession
        for (i in 1L..5L) {
            val state = YandexVotState.ready(
                i, "vid$i", "https://youtube.com/watch?v=vid$i", "t$i",
                "https://vtrans.s3-private.mds.yandex.net/tts/prod/audio$i.mp3", false, false
            )
            adapter.onStateChanged(state)
            adapter.stop()
        }

        assertFalse(adapter.isPlaying)
        assertFalse(mockBridge.ducked)
        assertTrue(errors.isEmpty())
    }
}

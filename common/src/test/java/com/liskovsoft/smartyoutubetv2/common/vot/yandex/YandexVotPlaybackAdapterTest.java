/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class YandexVotPlaybackAdapterTest {

    private static class FakeAudioPlayer implements YandexVotPlaybackAdapter.AudioPlayer {
        boolean prepared = false;
        boolean playing = false;
        boolean ready = false;
        boolean released = false;
        long positionMs = 0;
        float volume = 1.0f;
        float speed = 1.0f;
        int sessionId = 0;
        String url;
        Callback callback;

        @Override
        public void prepare(int sessionId, @NonNull String url, float speed, @Nullable Callback callback) {
            this.sessionId = sessionId;
            this.url = url;
            this.speed = speed;
            this.callback = callback;
        }

        @Override
        public void startPlayback(float volume) {
            this.volume = volume;
            this.playing = true;
            this.ready = true;
        }

        @Override
        public void setVolume(float volume) {
            this.volume = volume;
        }

        @Override
        public void setPlaybackSpeed(float speed) {
            this.speed = speed;
        }

        @Override
        public void seekTo(long positionMs) {
            this.positionMs = positionMs;
        }

        @Override
        public long getPositionMs() {
            return positionMs;
        }

        @Override
        public void pause() {
            this.playing = false;
        }

        @Override
        public void resume() {
            this.playing = true;
        }

        @Override
        public void release() {
            this.released = true;
            this.playing = false;
            this.ready = false;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }
    }

    private static class FakeDuckingBridge implements YandexVotPlaybackAdapter.AudioDuckingBridge {
        boolean ducked = false;
        int duckCount = 0;
        int restoreCount = 0;
        long currentVideoPositionMs = 0;
        boolean mainVideoPlaying = true;
        float translationVolume = 1.0f;

        @Override
        public void duckOriginalAudio() {
            ducked = true;
            duckCount++;
        }

        @Override
        public void restoreOriginalAudio() {
            ducked = false;
            restoreCount++;
        }

        @Override
        public float getTranslationVolume() {
            return translationVolume;
        }

        @Override
        public long getCurrentVideoPositionMs() {
            return currentVideoPositionMs;
        }

        @Override
        public boolean isMainVideoPlaying() {
            return mainVideoPlaying;
        }
    }

    private FakeAudioPlayer fakePlayer;
    private FakeDuckingBridge fakeDucking;
    private YandexVotPlaybackAdapter adapter;

    @Before
    public void setUp() {
        fakeDucking = new FakeDuckingBridge();
        adapter = new YandexVotPlaybackAdapter(
                () -> {
                    fakePlayer = new FakeAudioPlayer();
                    return fakePlayer;
                },
                fakeDucking
        );
    }

    @Test
    public void test01_ReadyStartsPlaybackAdapter() {
        final AtomicBoolean activeNotified = new AtomicBoolean(false);
        adapter.setStateListener(new YandexVotPlaybackAdapter.PlaybackStateListener() {
            @Override
            public void onPlaybackActive(long generationId) {
                activeNotified.set(true);
            }

            @Override
            public void onPlaybackPaused(long generationId) {}

            @Override
            public void onPlaybackStopped(long generationId) {}

            @Override
            public void onPlaybackError(long generationId, @NonNull String category) {}
        });

        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/trans.mp3", false, false
        );

        adapter.onStateChanged(readyState);
        assertNotNull(fakePlayer.url);
        assertEquals("https://vtrans.yandex.net/audio/trans.mp3", fakePlayer.url);

        // Simulate player prepared
        fakePlayer.callback.onPrepared();

        assertTrue(adapter.isPlaying());
        assertTrue(fakeDucking.ducked);
        assertTrue(activeNotified.get());
    }

    @Test
    public void test02_ReadyWithoutAudioUrlReportsError() {
        final AtomicReference<String> errorCategory = new AtomicReference<>();
        adapter.setStateListener(new YandexVotPlaybackAdapter.PlaybackStateListener() {
            @Override
            public void onPlaybackActive(long generationId) {}

            @Override
            public void onPlaybackPaused(long generationId) {}

            @Override
            public void onPlaybackStopped(long generationId) {}

            @Override
            public void onPlaybackError(long generationId, @NonNull String category) {
                errorCategory.set(category);
            }
        });

        YandexVotState readyWithoutUrl = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                null, false, false
        );

        adapter.onStateChanged(readyWithoutUrl);
        assertEquals("missing_audio_url", errorCategory.get());
        assertFalse(adapter.isPlaying());
        assertFalse(fakeDucking.ducked);
    }

    @Test
    public void test03_StaleReadyDoesNotStartAudio() {
        adapter.startPlayback(5L, "https://vtrans.yandex.net/audio/5.mp3", 1.0f);
        FakeAudioPlayer p5 = fakePlayer;

        // Old READY from gen 4 arrives -> must be ignored because 4 < 5
        YandexVotState oldReady = YandexVotState.ready(
                4L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/4.mp3", false, false
        );
        adapter.onStateChanged(oldReady);
        assertEquals(5L, adapter.getCurrentGeneration());

        // When new generation 6 starts:
        adapter.startPlayback(6L, "https://vtrans.yandex.net/audio/6.mp3", 1.0f);
        // Stale callback from p5 (generation 5)
        p5.callback.onPrepared();
        // Since current generation is 6, p5's callback is rejected
        assertFalse(adapter.isPlaying());
        assertFalse(fakeDucking.ducked);
    }

    @Test
    public void test04_CancelStopsAudio() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        assertTrue(fakePlayer.isPlaying());

        YandexVotState cancelState = YandexVotState.cancelled(1L, "v123", "https://youtube.com/watch?v=v123");
        adapter.onStateChanged(cancelState);

        assertTrue(fakePlayer.released);
        assertFalse(adapter.isPlaying());
    }

    @Test
    public void test05_OriginalAudioRestoredAfterCancel() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        assertTrue(fakeDucking.ducked);

        adapter.stop();
        assertFalse(fakeDucking.ducked);
        assertEquals(1, fakeDucking.restoreCount);
    }

    @Test
    public void test06_PauseForwarded() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        assertTrue(fakePlayer.isPlaying());

        adapter.onPause();
        assertFalse(fakePlayer.isPlaying());
    }

    @Test
    public void test07_ResumeForwarded() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        adapter.onPause();
        assertFalse(fakePlayer.isPlaying());

        adapter.onPlay();
        assertTrue(fakePlayer.isPlaying());
        assertTrue(fakeDucking.ducked);
    }

    @Test
    public void test08_SeekForwarded() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();

        adapter.onSeek(45000L);
        assertEquals(45000L, fakePlayer.positionMs);
    }

    @Test
    public void test09_VideoSwitchStopsOldAudio() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        assertTrue(adapter.isPlaying());

        adapter.onVideoChanged();
        assertFalse(adapter.isPlaying());
        assertTrue(fakePlayer.released);
        assertFalse(fakeDucking.ducked);
    }

    @Test
    public void test10_StalePlayerCallbackIgnored() {
        adapter.startPlayback(1L, "https://vtrans.yandex.net/audio/1.mp3", 1.0f);
        FakeAudioPlayer p1 = fakePlayer;

        // User switches video / stops
        adapter.stop();

        // Stale onPrepared callback arrives
        p1.callback.onPrepared();
        assertFalse(adapter.isPlaying());
        assertFalse(fakeDucking.ducked);
    }

    @Test
    public void test11_PlaybackErrorRestoresMainAudio() {
        final AtomicReference<String> errorCategory = new AtomicReference<>();
        adapter.setStateListener(new YandexVotPlaybackAdapter.PlaybackStateListener() {
            @Override
            public void onPlaybackActive(long generationId) {}

            @Override
            public void onPlaybackPaused(long generationId) {}

            @Override
            public void onPlaybackStopped(long generationId) {}

            @Override
            public void onPlaybackError(long generationId, @NonNull String category) {
                errorCategory.set(category);
            }
        });

        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        assertTrue(fakeDucking.ducked);

        fakePlayer.callback.onError(new RuntimeException("ExoPlayer decoder error"));
        assertEquals("playback_error", errorCategory.get());
        assertFalse(fakeDucking.ducked);
        assertFalse(adapter.isPlaying());
    }

    @Test
    public void test12_StopAndReleaseAreIdempotent() {
        adapter.stop();
        adapter.stop();
        adapter.release();
        adapter.release();
        assertFalse(adapter.isPlaying());
        assertFalse(fakeDucking.ducked);
    }

    @Test
    public void test13_InitialSyncUsesDeterminedPlaybackPosition() {
        fakeDucking.currentVideoPositionMs = 45000L;
        final AtomicLong customTarget = new AtomicLong(5000L);

        YandexVotPlaybackAdapter customAdapter = new YandexVotPlaybackAdapter(
                () -> {
                    fakePlayer = new FakeAudioPlayer();
                    return fakePlayer;
                },
                new YandexVotPlaybackAdapter.AudioDuckingBridge() {
                    @Override
                    public void duckOriginalAudio() {
                        fakeDucking.duckOriginalAudio();
                    }

                    @Override
                    public void restoreOriginalAudio() {
                        fakeDucking.restoreOriginalAudio();
                    }

                    @Override
                    public float getTranslationVolume() {
                        return 1.0f;
                    }

                    @Override
                    public long getCurrentVideoPositionMs() {
                        return 45000L;
                    }

                    @Override
                    public boolean isMainVideoPlaying() {
                        return true;
                    }

                    @Override
                    public long determineInitialPlaybackPosition() {
                        return customTarget.get();
                    }
                }
        );

        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        customAdapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();

        // Must seek to 5000L (the determined position), not 45000L
        assertEquals(5000L, fakePlayer.positionMs);
        fakePlayer.callback.onSeekProcessed();
        assertTrue(customAdapter.isPlaying());
    }

    @Test
    public void test14_BufferingPausesFollowerAudio() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        assertTrue(fakePlayer.isPlaying());
        assertFalse(adapter.isMainBuffering());

        // Main video starts buffering -> follower audio holds
        adapter.onBuffering();
        assertTrue(adapter.isMainBuffering());
        assertFalse(fakePlayer.isPlaying());

        // Main video resumes playing -> follower audio unpauses
        adapter.onPlay();
        assertFalse(adapter.isMainBuffering());
        assertTrue(fakePlayer.isPlaying());
    }

    @Test
    public void test15_PlaybackSpeedSynchronizedOnPrepareAndLive() {
        adapter.setPlaybackSpeed(1.5f);
        assertEquals(1.5f, adapter.getCurrentPlaybackSpeed(), 0.001f);

        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        assertEquals(1.5f, fakePlayer.speed, 0.001f);

        // Change speed dynamically during playback
        fakePlayer.callback.onPrepared();
        adapter.setPlaybackSpeed(2.0f);
        assertEquals(2.0f, fakePlayer.speed, 0.001f);
    }

    @Test
    public void test16_SeekForwardAndBackwardClamped() {
        final long DURATION_MS = 60000L;
        YandexVotPlaybackAdapter clampedAdapter = new YandexVotPlaybackAdapter(
                () -> {
                    fakePlayer = new FakeAudioPlayer();
                    return fakePlayer;
                },
                new YandexVotPlaybackAdapter.AudioDuckingBridge() {
                    @Override public void duckOriginalAudio() {}
                    @Override public void restoreOriginalAudio() {}
                    @Override public float getTranslationVolume() { return 1f; }
                    @Override public long getCurrentVideoPositionMs() { return 0L; }
                    @Override public boolean isMainVideoPlaying() { return true; }
                    @Override public long getVideoDurationMs() { return DURATION_MS; }
                }
        );

        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        clampedAdapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();

        // Normal seek forward
        clampedAdapter.onSeek(30000L);
        assertEquals(30000L, fakePlayer.positionMs);

        // Seek backward
        clampedAdapter.onSeek(10000L);
        assertEquals(10000L, fakePlayer.positionMs);

        // Seek past end of video -> clamped to duration
        clampedAdapter.onSeek(75000L);
        assertEquals(DURATION_MS, fakePlayer.positionMs);

        // Seek before start -> clamped to 0
        clampedAdapter.onSeek(-5000L);
        assertEquals(0L, fakePlayer.positionMs);
    }

    @Test
    public void test17_PeriodicSyncToleratesSmallDrift() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        fakePlayer.positionMs = 20000L;

        // Small drift: 100ms <= 150ms tolerance -> no seek
        adapter.checkPeriodicSync(20100L);
        assertEquals(20000L, fakePlayer.positionMs);
    }

    @Test
    public void test18_PeriodicSyncCorrectsLargeDriftImmediately() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        fakePlayer.positionMs = 20000L;

        // Large drift: 5000ms > 1500ms hard threshold -> hard seek to main position
        adapter.checkPeriodicSync(25000L);
        assertEquals(25000L, fakePlayer.positionMs);
    }

    @Test
    public void test19_PeriodicSyncHoldsAudioWhenMasterBuffering() {
        final AtomicBoolean bufferingFlag = new AtomicBoolean(false);
        YandexVotPlaybackAdapter bufferAdapter = new YandexVotPlaybackAdapter(
                () -> {
                    fakePlayer = new FakeAudioPlayer();
                    return fakePlayer;
                },
                new YandexVotPlaybackAdapter.AudioDuckingBridge() {
                    @Override public void duckOriginalAudio() {}
                    @Override public void restoreOriginalAudio() {}
                    @Override public float getTranslationVolume() { return 1f; }
                    @Override public long getCurrentVideoPositionMs() { return 10000L; }
                    @Override public boolean isMainVideoPlaying() { return true; }
                    @Override public boolean isMainVideoBuffering() { return bufferingFlag.get(); }
                }
        );

        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/1.mp3", false, false
        );
        bufferAdapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        assertTrue(fakePlayer.isPlaying());

        // Master enters buffering
        bufferingFlag.set(true);
        bufferAdapter.checkPeriodicSync(10000L);
        assertFalse(fakePlayer.isPlaying());
    }

    @Test
    public void test20_BoundedRouteFallbackDirectToProxyOnFailure() {
        final String directUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/test.mp3";
        final String proxiedUrl = "https://vot-worker.eu.cc/video-translation/audio-proxy/test.mp3";

        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                directUrl, false, false
        );
        adapter.onStateChanged(readyState);
        assertEquals(directUrl, fakePlayer.url);

        // First attempt fails -> triggers bounded 1-step route fallback
        fakePlayer.callback.onError(new RuntimeException("Direct S3 403 Forbidden"));
        assertEquals(proxiedUrl, fakePlayer.url);
        assertEquals(proxiedUrl, adapter.getCurrentSourceUrl());

        // Second attempt with fallback succeeds
        fakePlayer.callback.onPrepared();
        assertTrue(adapter.isPlaying());
    }

    @Test
    public void test21_BoundedRouteFallbackExhaustedReportsError() {
        final AtomicReference<String> errorReported = new AtomicReference<>();
        adapter.setStateListener(new YandexVotPlaybackAdapter.PlaybackStateListener() {
            @Override public void onPlaybackActive(long generationId) {}
            @Override public void onPlaybackPaused(long generationId) {}
            @Override public void onPlaybackStopped(long generationId) {}
            @Override public void onPlaybackError(long generationId, @NonNull String category) {
                errorReported.set(category);
            }
        });

        final String directUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/test.mp3";
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                directUrl, false, false
        );
        adapter.onStateChanged(readyState);

        // 1st failure -> switches to fallback
        fakePlayer.callback.onError(new RuntimeException("Direct S3 failure"));
        assertNull(errorReported.get());

        // 2nd failure on fallback -> route fallback exhausted -> reports terminal error
        fakePlayer.callback.onError(new RuntimeException("Proxy failure"));
        assertEquals("playback_error", errorReported.get());
        assertFalse(adapter.isPlaying());
    }

    @Test
    public void test22_SourceRefreshUpdatesAudioWithoutGenerationLeak() {
        YandexVotState readyState = YandexVotState.ready(
                1L, "v123", "https://youtube.com/watch?v=v123", "t456",
                "https://vtrans.yandex.net/audio/old.mp3", false, false
        );
        adapter.onStateChanged(readyState);
        fakePlayer.callback.onPrepared();
        assertTrue(adapter.isPlaying());

        // Audio URL expired; refresh signed URL and resume at 25000ms with speed 1.5x
        final String refreshedUrl = "https://vtrans.s3-private.mds.yandex.net/tts/prod/fresh.mp3";
        adapter.refreshSource(1L, refreshedUrl, 25000L, 1.5f);
        assertEquals(refreshedUrl, fakePlayer.url);
        assertEquals(1.5f, fakePlayer.speed, 0.001f);

        fakePlayer.callback.onPrepared();
        assertEquals(25000L, fakePlayer.positionMs);
        fakePlayer.callback.onSeekProcessed();
        assertTrue(adapter.isPlaying());
    }
}

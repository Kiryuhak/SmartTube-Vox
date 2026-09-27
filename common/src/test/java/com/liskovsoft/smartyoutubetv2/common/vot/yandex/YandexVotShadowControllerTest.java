/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class YandexVotShadowControllerTest {

    private static class FakeApi implements YandexVotApi {
        @Nullable
        @Override
        public YandexVotApiClient.TranslationResult requestTranslation(
                @NonNull String videoUrl,
                double duration,
                @Nullable String sourceLang,
                @NonNull String targetLang,
                @Nullable String videoTitle,
                boolean useLiveVoices,
                @Nullable String oauthToken,
                boolean firstRequest
        ) {
            return new YandexVotApiClient.TranslationResult(
                    1, // SUCCESS
                    "https://fake.url/audio.mp3",
                    0,
                    "fake-trans-id",
                    "Success"
            );
        }
    }

    private static class FakeAudioSourceProvider implements YandexVotAudioSourceProvider {
        @Nullable
        @Override
        public YandexVotAudioSource getAudioSource(@Nullable String videoId, @NonNull String videoUrl) {
            return null;
        }

        @Nullable
        @Override
        public YandexVotAudioStreamReader getStreamReader(@NonNull YandexVotAudioSource source) {
            return null;
        }
    }

    private static class FakeAudioUploadTransport implements YandexVotAudioUploadTransport {
        @Override
        public UploadOutcome uploadPart(
                @NonNull String videoUrl,
                @NonNull String translationId,
                @NonNull String fileId,
                int totalParts,
                int version,
                int chunkId,
                @NonNull byte[] audioData
        ) {
            return UploadOutcome.SUCCESS;
        }
    }

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

    private YandexVotShadowController controller;
    private YandexVotOrchestrator orchestrator;
    private YandexVotPlaybackAdapter adapter;
    private FakeAudioPlayer fakePlayer;
    private FakeDuckingBridge fakeDucking;

    @Before
    public void setUp() {
        controller = new YandexVotShadowController();
        orchestrator = new YandexVotOrchestrator(
                new FakeApi(),
                new FakeAudioSourceProvider(),
                new FakeAudioUploadTransport()
        );
        fakePlayer = new FakeAudioPlayer();
        fakeDucking = new FakeDuckingBridge();
        adapter = new YandexVotPlaybackAdapter(
                () -> fakePlayer,
                fakeDucking
        );
        controller.setOrchestrator(orchestrator);
        controller.setPlaybackAdapter(adapter);
    }

    private void waitForState(YandexVotState.Status expectedStatus, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            YandexVotState s = controller.getLastState();
            if (s != null && s.getStatus() == expectedStatus) {
                return;
            }
            Thread.sleep(10);
        }
    }

    @Test
    public void testDefaultDisabled() {
        assertFalse("Shadow controller must be disabled by default", controller.isEnabled());
        assertFalse("AutoStart must be false by default", controller.isAutoStartOnNewVideo());

        // Event callbacks when disabled should not throw or alter adapter state
        controller.onPlay();
        assertFalse(adapter.isPlaying());

        controller.onPause();
        assertFalse(adapter.isPlaying());

        controller.onSeekEnd();
        controller.onSpeedChanged(1.25f);
        controller.onEngineReleased();
        controller.onFinish();
        controller.onViewDestroyed();
    }

    @Test
    public void testSetEnabledAndDisabled() {
        controller.setEnabled(true);
        assertTrue(controller.isEnabled());

        controller.setEnabled(false);
        assertFalse(controller.isEnabled());
    }

    @Test
    public void testOnNewVideoWhenDisabled() {
        controller.setEnabled(false);
        controller.setAutoStartOnNewVideo(true);

        Video video = Video.from("dQw4w9WgXcQ");
        controller.onNewVideo(video);

        assertNull(controller.getLastState());
    }

    @Test
    public void testOnNewVideoWhenEnabledWithAutoStart() throws Exception {
        controller.setEnabled(true);
        controller.setAutoStartOnNewVideo(true);

        Video video = Video.from("dQw4w9WgXcQ");
        controller.onNewVideo(video);

        waitForState(YandexVotState.Status.READY, 2000);

        YandexVotState state = controller.getLastState();
        assertNotNull("Translation should start automatically on new video", state);
        assertEquals(YandexVotState.Status.READY, state.getStatus());
    }

    @Test
    public void testOnNewVideoCancelsPreviousSession() throws Exception {
        controller.setEnabled(true);
        controller.setAutoStartOnNewVideo(false);

        Video video1 = Video.from("video1");
        controller.onNewVideo(video1);
        controller.startCurrentTranslation("token1");

        waitForState(YandexVotState.Status.READY, 2000);
        assertNotNull(controller.getLastState());

        Video video2 = Video.from("video2");
        controller.onNewVideo(video2);

        // After new video with autoStart=false, previous session is stopped
        assertFalse(adapter.isPlaying());
    }

    @Test
    public void testOnPlayWhenEnabled() {
        controller.setEnabled(true);
        adapter.startPlayback(1, "https://fake.url/audio.mp3", 1.0f);
        fakePlayer.callback.onPrepared();

        assertTrue(adapter.isPlaying());
        controller.onPause();
        assertFalse(adapter.isPlaying());

        controller.onPlay();
        assertTrue(adapter.isPlaying());
    }

    @Test
    public void testOnPauseWhenEnabled() {
        controller.setEnabled(true);
        adapter.startPlayback(1, "https://fake.url/audio.mp3", 1.0f);
        fakePlayer.callback.onPrepared();

        assertTrue(adapter.isPlaying());
        controller.onPause();
        assertFalse(adapter.isPlaying());
    }

    @Test
    public void testOnSpeedChangedWhenEnabled() {
        controller.setEnabled(true);
        controller.onSpeedChanged(1.5f);
        // Should execute cleanly without error
    }

    @Test
    public void testOnEngineReleasedCleansUp() {
        controller.setEnabled(true);
        adapter.startPlayback(1, "https://fake.url/audio.mp3", 1.0f);
        fakePlayer.callback.onPrepared();

        assertTrue(adapter.isPlaying());
        controller.onEngineReleased();

        assertFalse(adapter.isPlaying());
        assertTrue(fakePlayer.released);
    }

    @Test
    public void testOnFinishCleansUp() {
        controller.setEnabled(true);
        adapter.startPlayback(1, "https://fake.url/audio.mp3", 1.0f);
        fakePlayer.callback.onPrepared();

        assertTrue(adapter.isPlaying());
        controller.onFinish();

        assertFalse(adapter.isPlaying());
        assertTrue(fakePlayer.released);
    }

    @Test
    public void testOnViewDestroyedCleansUp() {
        controller.setEnabled(true);
        adapter.startPlayback(1, "https://fake.url/audio.mp3", 1.0f);
        fakePlayer.callback.onPrepared();

        assertTrue(adapter.isPlaying());
        controller.onViewDestroyed();

        assertFalse(adapter.isPlaying());
        assertTrue(fakePlayer.released);
    }

    @Test
    public void testIdempotentMultipleStops() {
        controller.setEnabled(true);
        controller.stopTranslation();
        controller.stopTranslation();
        controller.stopTranslation();
        // Should not throw
    }

    @Test
    public void testStatePropagationToLastStateAndAdapter() throws Exception {
        controller.setEnabled(true);

        Video video = Video.from("dQw4w9WgXcQ");
        controller.onNewVideo(video);
        controller.startCurrentTranslation("token");

        waitForState(YandexVotState.Status.READY, 2000);

        YandexVotState state = controller.getLastState();
        assertNotNull(state);
        assertEquals(YandexVotState.Status.READY, state.getStatus());

        if (fakePlayer.callback != null) {
            fakePlayer.callback.onPrepared();
        }
        assertTrue(fakeDucking.ducked);
    }
}

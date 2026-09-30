/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.vot.TranslationAudioPlayer;

import java.util.Objects;

/**
 * Isolated adapter connecting YandexVotState.READY to translated audio playback
 * using SmartTube's TranslationAudioPlayer and original audio ducking.
 */
public class YandexVotPlaybackAdapter implements YandexVotOrchestrator.Listener {
    private static final String TAG = "YANDEX_VOT_PLAYBACK";

    public static final long SYNC_THRESHOLD_MS = 1800L;
    public static final long SYNC_SEEK_COOLDOWN_MS = 3500L;
    public static final long INITIAL_SYNC_TOLERANCE_MS = 200L;

    public interface AudioPlayer {
        interface Callback {
            void onPrepared();
            void onSeekProcessed();
            void onError(@NonNull Exception error);
        }

        void prepare(int sessionId, @NonNull String url, float speed, @Nullable Callback callback);
        void startPlayback(float volume);
        void setVolume(float volume);
        void setPlaybackSpeed(float speed);
        void seekTo(long positionMs);
        long getPositionMs();
        void pause();
        void resume();
        void release();
        boolean isReady();
        boolean isPlaying();
    }

    public interface AudioDuckingBridge {
        void duckOriginalAudio();
        void restoreOriginalAudio();
        float getTranslationVolume();
        long getCurrentVideoPositionMs();
        boolean isMainVideoPlaying();
        default long determineInitialPlaybackPosition() {
            return getCurrentVideoPositionMs();
        }
    }

    public interface PlaybackStateListener {
        void onPlaybackActive(long generationId);
        void onPlaybackPaused(long generationId);
        void onPlaybackStopped(long generationId);
        void onPlaybackError(long generationId, @NonNull String category);
    }

    public interface AudioPlayerFactory {
        @NonNull
        AudioPlayer createPlayer();
    }

    private final AudioPlayerFactory playerFactory;
    private final AudioDuckingBridge duckingBridge;
    @Nullable private PlaybackStateListener stateListener;

    private final Object lock = new Object();
    private long currentGeneration = 0;
    @Nullable private AudioPlayer activePlayer;
    private boolean isPrepared = false;
    private boolean isPlaying = false;
    private boolean isDucked = false;
    private long lastSyncSeekTimestamp = 0;

    public YandexVotPlaybackAdapter(
            @NonNull AudioPlayerFactory playerFactory,
            @NonNull AudioDuckingBridge duckingBridge
    ) {
        this.playerFactory = Objects.requireNonNull(playerFactory, "playerFactory cannot be null");
        this.duckingBridge = Objects.requireNonNull(duckingBridge, "duckingBridge cannot be null");
    }

    public static AudioPlayerFactory createDefaultPlayerFactory(@NonNull final Context context) {
        final Context appContext = context.getApplicationContext();
        return new AudioPlayerFactory() {
            @NonNull
            @Override
            public AudioPlayer createPlayer() {
                final TranslationAudioPlayer player = new TranslationAudioPlayer(appContext);
                return new AudioPlayer() {
                    @Override
                    public void prepare(int sessionId, @NonNull String url, float speed, @Nullable final Callback callback) {
                        player.prepare(sessionId, url, speed, callback != null ? new TranslationAudioPlayer.PlaybackCallback() {
                            @Override
                            public void onPrepared() {
                                callback.onPrepared();
                            }

                            @Override
                            public void onSeekProcessed() {
                                callback.onSeekProcessed();
                            }

                            @Override
                            public void onError(Exception error) {
                                callback.onError(error != null ? error : new RuntimeException("Playback error"));
                            }
                        } : null);
                    }

                    @Override
                    public void startPlayback(float volume) {
                        player.startPlayback(volume);
                    }

                    @Override
                    public void setVolume(float volume) {
                        player.setVolume(volume);
                    }

                    @Override
                    public void setPlaybackSpeed(float speed) {
                        player.setPlaybackSpeed(speed);
                    }

                    @Override
                    public void seekTo(long positionMs) {
                        player.seekTo(positionMs);
                    }

                    @Override
                    public long getPositionMs() {
                        return player.getPositionMs();
                    }

                    @Override
                    public void pause() {
                        player.pause();
                    }

                    @Override
                    public void resume() {
                        player.resume();
                    }

                    @Override
                    public void release() {
                        player.release();
                    }

                    @Override
                    public boolean isReady() {
                        return player.isReady();
                    }

                    @Override
                    public boolean isPlaying() {
                        return player.isPlaying();
                    }
                };
            }
        };
    }

    public void setStateListener(@Nullable PlaybackStateListener listener) {
        synchronized (lock) {
            this.stateListener = listener;
        }
    }

    @Override
    public void onStateChanged(@NonNull YandexVotState state) {
        synchronized (lock) {
            if (state.getGenerationId() < currentGeneration && currentGeneration != 0) {
                YandexVotLog.w(TAG, "Dropping stale state: stateGen=" + state.getGenerationId() + " currentGen=" + currentGeneration);
                return;
            }
            if (state.getStatus() == YandexVotState.Status.READY) {
                handleReadyStateLocked(state);
            } else if (state.getStatus() == YandexVotState.Status.CANCELLED) {
                handleStopLocked(state.getGenerationId(), "cancel");
            } else if (state.getStatus() == YandexVotState.Status.ERROR) {
                handleStopLocked(state.getGenerationId(), "error");
            } else if (state.getStatus() == YandexVotState.Status.IDLE) {
                handleStopLocked(state.getGenerationId(), "idle");
            }
        }
    }

    public void startPlayback(long generationId, @Nullable String audioUrl, float speed) {
        synchronized (lock) {
            if (generationId < currentGeneration && currentGeneration != 0) {
                YandexVotLog.w(TAG, "Dropping stale startPlayback: gen=" + generationId + " currentGen=" + currentGeneration);
                return;
            }
            currentGeneration = generationId;
            if (audioUrl == null || audioUrl.trim().isEmpty()) {
                YandexVotLog.e(TAG, "generation=" + generationId + " error=missing_audio_url");
                handleStopLocked(generationId, "missing_audio_url");
                notifyErrorLocked(generationId, "missing_audio_url");
                return;
            }

            stopAndReleasePlayerLocked();

            final AudioPlayer player = playerFactory.createPlayer();
            activePlayer = player;
            isPrepared = false;
            isPlaying = false;

            YandexVotLog.i(TAG, "generation=" + generationId + " prepare_start");

            final int sessionId = (int) (generationId & 0x7FFFFFFF);
            player.prepare(sessionId, audioUrl, speed > 0 ? speed : 1.0f, new AudioPlayer.Callback() {
                @Override
                public void onPrepared() {
                    synchronized (lock) {
                        if (generationId != currentGeneration || activePlayer != player) {
                            YandexVotLog.w(TAG, "generation=" + generationId + " prepared but generation changed (current=" + currentGeneration + ")");
                            return;
                        }
                        isPrepared = true;
                        YandexVotLog.i(TAG, "generation=" + generationId + " prepared");

                        long targetPosMs = duckingBridge.determineInitialPlaybackPosition();
                        if (targetPosMs <= INITIAL_SYNC_TOLERANCE_MS) {
                            onInitialSyncCompleteLocked(generationId, player);
                        } else {
                            YandexVotLog.i(TAG, "generation=" + generationId + " initial_seek target_ms=" + targetPosMs);
                            player.seekTo(targetPosMs);
                        }
                    }
                }

                @Override
                public void onSeekProcessed() {
                    synchronized (lock) {
                        if (generationId != currentGeneration || activePlayer != player) {
                            return;
                        }
                        if (!isPlaying) {
                            onInitialSyncCompleteLocked(generationId, player);
                        }
                    }
                }

                @Override
                public void onError(@NonNull Exception error) {
                    synchronized (lock) {
                        if (generationId != currentGeneration || activePlayer != player) {
                            return;
                        }
                        YandexVotLog.e(TAG, "generation=" + generationId + " error=playback_error");
                        handleStopLocked(generationId, "playback_error");
                        notifyErrorLocked(generationId, "playback_error");
                    }
                }
            });
        }
    }

    private void handleReadyStateLocked(@NonNull YandexVotState state) {
        long gen = state.getGenerationId();
        currentGeneration = gen;
        String audioUrl = state.getAudioUrl();
        startPlayback(gen, audioUrl, 1.0f);
    }

    private void onInitialSyncCompleteLocked(long generationId, @NonNull AudioPlayer player) {
        if (generationId != currentGeneration || activePlayer != player) {
            return;
        }

        if (isPlaying) {
            return;
        }

        duckOriginalAudioLocked();

        float volume = duckingBridge.getTranslationVolume();
        boolean mainPlaying = duckingBridge.isMainVideoPlaying();

        player.startPlayback(volume);
        isPlaying = true;
        YandexVotLog.i(TAG, "generation=" + generationId + " audible_start");

        if (!mainPlaying) {
            player.pause();
            YandexVotLog.i(TAG, "generation=" + generationId + " pause");
            notifyPausedLocked(generationId);
        } else {
            notifyActiveLocked(generationId);
        }

        lastSyncSeekTimestamp = System.currentTimeMillis();
    }

    public void onPlay() {
        synchronized (lock) {
            if (activePlayer != null && isPlaying) {
                activePlayer.resume();
                duckOriginalAudioLocked();
                YandexVotLog.i(TAG, "generation=" + currentGeneration + " resume");
                notifyActiveLocked(currentGeneration);
            }
        }
    }

    public void onPause() {
        synchronized (lock) {
            if (activePlayer != null) {
                activePlayer.pause();
                YandexVotLog.i(TAG, "generation=" + currentGeneration + " pause");
                notifyPausedLocked(currentGeneration);
            }
        }
    }

    public void onSeek(long targetPositionMs) {
        synchronized (lock) {
            if (activePlayer != null) {
                lastSyncSeekTimestamp = System.currentTimeMillis();
                activePlayer.seekTo(targetPositionMs);
                YandexVotLog.i(TAG, "generation=" + currentGeneration + " seek target_ms=" + targetPositionMs);
            }
        }
    }

    public void setPlaybackSpeed(float speed) {
        synchronized (lock) {
            if (activePlayer != null) {
                activePlayer.setPlaybackSpeed(speed);
            }
        }
    }

    public void setVolume(float volume) {
        synchronized (lock) {
            if (activePlayer != null) {
                activePlayer.setVolume(volume);
            }
        }
    }

    public void checkPeriodicSync(long mainPosMs) {
        synchronized (lock) {
            if (activePlayer == null || !isPlaying || !activePlayer.isReady()) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastSyncSeekTimestamp < SYNC_SEEK_COOLDOWN_MS) {
                return;
            }

            long transPosMs = activePlayer.getPositionMs();
            long delta = Math.abs(mainPosMs - transPosMs);

            YandexVotLog.i(TAG, "generation=" + currentGeneration + " sync delta_ms=" + delta);

            if (delta > SYNC_THRESHOLD_MS) {
                YandexVotLog.i(TAG, "generation=" + currentGeneration + " drift_seek target_ms=" + mainPosMs);
                lastSyncSeekTimestamp = now;
                activePlayer.seekTo(mainPosMs);
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            handleStopLocked(currentGeneration, "stop");
        }
    }

    public void release() {
        synchronized (lock) {
            handleStopLocked(currentGeneration, "release");
            stateListener = null;
        }
    }

    public void onVideoChanged() {
        synchronized (lock) {
            YandexVotLog.i(TAG, "generation=" + currentGeneration + " video_switch");
            currentGeneration++;
            handleStopLocked(currentGeneration, "video_switch");
        }
    }

    private void handleStopLocked(long generationId, @NonNull String reason) {
        YandexVotLog.i(TAG, "generation=" + generationId + " " + reason);
        stopAndReleasePlayerLocked();
        restoreOriginalAudioLocked();
        notifyStoppedLocked(generationId);
    }

    private void stopAndReleasePlayerLocked() {
        if (activePlayer != null) {
            activePlayer.release();
            activePlayer = null;
        }
        isPrepared = false;
        isPlaying = false;
    }

    private void duckOriginalAudioLocked() {
        if (!isDucked) {
            duckingBridge.duckOriginalAudio();
            isDucked = true;
        }
    }

    private void restoreOriginalAudioLocked() {
        if (isDucked) {
            duckingBridge.restoreOriginalAudio();
            isDucked = false;
        }
    }

    private void notifyActiveLocked(long gen) {
        if (stateListener != null) {
            stateListener.onPlaybackActive(gen);
        }
    }

    private void notifyPausedLocked(long gen) {
        if (stateListener != null) {
            stateListener.onPlaybackPaused(gen);
        }
    }

    private void notifyStoppedLocked(long gen) {
        if (stateListener != null) {
            stateListener.onPlaybackStopped(gen);
        }
    }

    private void notifyErrorLocked(long gen, @NonNull String category) {
        if (stateListener != null) {
            stateListener.onPlaybackError(gen, category);
        }
    }

    public boolean isPlaying() {
        synchronized (lock) {
            return isPlaying && activePlayer != null && activePlayer.isPlaying();
        }
    }

    public boolean isPrepared() {
        synchronized (lock) {
            return isPrepared;
        }
    }

    public boolean isAudioDucked() {
        synchronized (lock) {
            return isDucked;
        }
    }

    public long getCurrentGeneration() {
        synchronized (lock) {
            return currentGeneration;
        }
    }
}

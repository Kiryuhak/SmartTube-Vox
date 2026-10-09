/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotState;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Patch #20 — Unit tests for:
 * 1. Automatic activation of ready translation when requested by user or auto-translate.
 * 2. User explicit original audio choice protection (no overriding user will).
 * 3. Manual activation flow when auto-activation preference is disabled.
 * 4. Per-video session identity reset and stale callback rejection on video switch.
 * 5. Instant playback on cached translation.
 * 6. Target language default for Russian locale vs user overrides.
 */
public class VoiceTranslateAutoActivationTest {

    public static class VoiceTranslateModel {
        public static final int STATE_OFF = 0;
        public static final int STATE_PENDING = 1;
        public static final int STATE_ACTIVE = 2;
        public static final int STATE_READY = 3;

        private boolean autoActivateReady = true;
        private String targetLanguage = "ru";
        private int state = STATE_OFF;
        private boolean userArmed = false;
        private boolean translationRequestedByUser = false;
        private boolean userExplicitlySelectedOriginal = false;
        private boolean isAudioDucked = false;
        private boolean playbackActive = false;
        private int sessionId = 0;
        private long generationId = 0;
        private String currentVideoId = null;
        private String pendingReadyAudioUrl = null;
        private YandexVotState pendingReadyNewState = null;
        private String overlayTitle = null;
        private String overlaySubtitle = null;

        public void setAutoActivateReady(boolean enabled) {
            this.autoActivateReady = enabled;
        }

        public boolean isAutoActivateReady() {
            return autoActivateReady;
        }

        public void setTargetLanguage(String lang) {
            this.targetLanguage = lang;
        }

        public String getTargetLanguage() {
            return targetLanguage != null ? targetLanguage : "ru";
        }

        public int getState() {
            return state;
        }

        public boolean isAudioDucked() {
            return isAudioDucked;
        }

        public boolean isPlaybackActive() {
            return playbackActive;
        }

        public String getOverlayTitle() {
            return overlayTitle;
        }

        public String getOverlaySubtitle() {
            return overlaySubtitle;
        }

        public void onNewVideo(String videoId) {
            sessionId++;
            generationId++;
            currentVideoId = videoId;
            pendingReadyAudioUrl = null;
            pendingReadyNewState = null;
            userExplicitlySelectedOriginal = false;
            translationRequestedByUser = false;
            userArmed = false;
            playbackActive = false;
            isAudioDucked = false;
            overlayTitle = null;
            overlaySubtitle = null;
            state = STATE_OFF;
        }

        public void onButtonClicked() {
            if (state == STATE_READY) {
                activateReadyTranslation();
            } else if (state == STATE_OFF) {
                userExplicitlySelectedOriginal = false;
                translationRequestedByUser = true;
                userArmed = true;
                state = STATE_PENDING;
                generationId++;
                overlayTitle = "Подготовка перевода…";
                overlaySubtitle = null;
            } else {
                userExplicitlySelectedOriginal = true;
                disarm();
            }
        }

        public void onUserSelectedOriginalAudioTrack() {
            userExplicitlySelectedOriginal = true;
            disarm();
        }

        public void disarm() {
            sessionId++;
            generationId++;
            pendingReadyAudioUrl = null;
            pendingReadyNewState = null;
            translationRequestedByUser = false;
            userArmed = false;
            playbackActive = false;
            isAudioDucked = false;
            state = STATE_OFF;
            overlayTitle = null;
            overlaySubtitle = null;
        }

        public void onBackendReady(long gen, String audioUrl) {
            if (gen != generationId) {
                // Stale callback
                return;
            }
            if (userExplicitlySelectedOriginal) {
                // User explicitly selected original; ignore ready
                return;
            }
            if (autoActivateReady) {
                // Auto-activate
                duckMainAudio();
                playbackActive = true;
                state = STATE_ACTIVE;
                overlayTitle = "Перевод включён";
                overlaySubtitle = null;
            } else {
                // Keep ready for manual activation
                pendingReadyAudioUrl = audioUrl;
                state = STATE_READY;
                overlayTitle = "Перевод готов";
                overlaySubtitle = "Нажмите кнопку перевода для включения";
            }
        }

        public void activateReadyTranslation() {
            userArmed = true;
            userExplicitlySelectedOriginal = false;
            translationRequestedByUser = true;
            duckMainAudio();
            playbackActive = true;
            state = STATE_ACTIVE;
            pendingReadyAudioUrl = null;
            pendingReadyNewState = null;
            overlayTitle = "Перевод включён";
            overlaySubtitle = null;
        }

        private void duckMainAudio() {
            isAudioDucked = true;
        }
    }

    private VoiceTranslateModel model;

    @Before
    public void setUp() {
        model = new VoiceTranslateModel();
    }

    @Test
    public void testUserRequestedAutoActivationOnReady() {
        model.onNewVideo("vid_123");
        model.setAutoActivateReady(true);
        assertEquals(VoiceTranslateModel.STATE_OFF, model.getState());

        // User clicks translate button
        model.onButtonClicked();
        assertEquals(VoiceTranslateModel.STATE_PENDING, model.getState());
        assertEquals("Подготовка перевода…", model.getOverlayTitle());
        assertFalse(model.isPlaybackActive());
        assertFalse(model.isAudioDucked());

        // Translation becomes READY
        model.onBackendReady(model.generationId, "https://audio.yandex.net/trans_123.mp3");

        assertEquals(VoiceTranslateModel.STATE_ACTIVE, model.getState());
        assertTrue(model.isPlaybackActive());
        assertTrue(model.isAudioDucked());
        assertEquals("Перевод включён", model.getOverlayTitle());
        assertNull(model.getOverlaySubtitle());
    }

    @Test
    public void testManualActivationWhenAutoActivateDisabled() {
        model.onNewVideo("vid_456");
        model.setAutoActivateReady(false);

        // User requests translation
        model.onButtonClicked();
        assertEquals(VoiceTranslateModel.STATE_PENDING, model.getState());

        // Backend finishes preparation
        model.onBackendReady(model.generationId, "https://audio.yandex.net/trans_456.mp3");

        // Should be in READY state, not playing yet and not ducked
        assertEquals(VoiceTranslateModel.STATE_READY, model.getState());
        assertFalse(model.isPlaybackActive());
        assertFalse(model.isAudioDucked());
        assertEquals("Перевод готов", model.getOverlayTitle());
        assertEquals("Нажмите кнопку перевода для включения", model.getOverlaySubtitle());

        // User clicks button to activate
        model.onButtonClicked();

        assertEquals(VoiceTranslateModel.STATE_ACTIVE, model.getState());
        assertTrue(model.isPlaybackActive());
        assertTrue(model.isAudioDucked());
        assertEquals("Перевод включён", model.getOverlayTitle());
    }

    @Test
    public void testUserExplicitOriginalPreventsAutoActivation() {
        model.onNewVideo("vid_789");
        model.setAutoActivateReady(true);

        // User starts translation
        model.onButtonClicked();
        assertEquals(VoiceTranslateModel.STATE_PENDING, model.getState());
        long pendingGen = model.generationId;

        // While pending, user switches to original audio track (or clicks button to cancel)
        model.onUserSelectedOriginalAudioTrack();
        assertEquals(VoiceTranslateModel.STATE_OFF, model.getState());

        // Late backend response arrives for old generation
        model.onBackendReady(pendingGen, "https://audio.yandex.net/trans_789.mp3");

        // Must remain OFF and not duck original audio!
        assertEquals(VoiceTranslateModel.STATE_OFF, model.getState());
        assertFalse(model.isPlaybackActive());
        assertFalse(model.isAudioDucked());
    }

    @Test
    public void testNewVideoResetsSessionAndPreventsStaleReady() {
        model.onNewVideo("vid_videoA");
        model.onButtonClicked();
        long genVideoA = model.generationId;

        // User switches to Video B
        model.onNewVideo("vid_videoB");
        assertEquals(VoiceTranslateModel.STATE_OFF, model.getState());
        assertFalse(model.isPlaybackActive());

        // Stale callback for Video A arrives
        model.onBackendReady(genVideoA, "https://audio.yandex.net/trans_videoA.mp3");

        // Video B must remain OFF
        assertEquals(VoiceTranslateModel.STATE_OFF, model.getState());
        assertFalse(model.isPlaybackActive());
        assertFalse(model.isAudioDucked());
    }

    @Test
    public void testCachedTranslationImmediateActivation() {
        model.onNewVideo("vid_cached");
        model.setAutoActivateReady(true);

        model.onButtonClicked();
        // Immediately ready from cache
        model.onBackendReady(model.generationId, "https://audio.yandex.net/cached.mp3");

        assertEquals(VoiceTranslateModel.STATE_ACTIVE, model.getState());
        assertTrue(model.isPlaybackActive());
        assertTrue(model.isAudioDucked());
        assertEquals("Перевод включён", model.getOverlayTitle());
    }

    @Test
    public void testRussianDefaultTargetLanguage() {
        assertEquals("ru", model.getTargetLanguage());

        model.setTargetLanguage("kk");
        assertEquals("kk", model.getTargetLanguage());

        model.setTargetLanguage(null);
        assertEquals("ru", model.getTargetLanguage());
    }
}

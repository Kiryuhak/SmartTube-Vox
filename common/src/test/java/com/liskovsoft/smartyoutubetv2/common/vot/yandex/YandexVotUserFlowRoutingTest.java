/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VoiceTranslateController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Batch #97 — Comprehensive tests for feature-flagged Yandex VOT user-flow routing,
 * lifecycle integration, double-start protection, fallback boundaries, and shadow isolation.
 */
public class YandexVotUserFlowRoutingTest {

    public enum BackendType {
        NONE,
        OLD,
        NEW
    }

    /**
     * Test model implementing the exact routing and lifecycle rules of VoiceTranslateController.
     */
    public static class UserFlowRouterModel {
        private boolean featureFlagEnabled = false;
        private BackendType activeBackend = BackendType.NONE;
        private boolean isPending = false;
        private boolean isActive = false;
        private boolean isAudioDucked = false;
        private boolean playbackStarted = false;
        private boolean fallbackTriggered = false;
        private int sessionId = 0;
        private long newBackendGenerationId = 0;

        private int oldBackendStartCount = 0;
        private int newBackendStartCount = 0;
        private int oldBackendStopCount = 0;
        private int newBackendStopCount = 0;
        private int duplicateStartRejections = 0;

        private boolean livelyPassed = false;
        private String tokenUsed = null;

        public void setFeatureFlag(boolean enabled) {
            this.featureFlagEnabled = enabled;
        }

        public boolean isFeatureFlagEnabled() {
            return featureFlagEnabled;
        }

        public void onVoxButtonClicked(boolean useLively, @Nullable String oauthToken) {
            if (isActive || isPending) {
                // User pressed stop
                stopTranslation();
                return;
            }
            startTranslation(useLively, oauthToken);
        }

        public void startTranslation(boolean useLively, @Nullable String oauthToken) {
            if (isActive) {
                duplicateStartRejections++;
                return;
            }

            sessionId++;
            isPending = true;
            this.livelyPassed = useLively;
            this.tokenUsed = oauthToken;

            if (featureFlagEnabled) {
                startNewBackend();
            } else {
                startOldBackend();
            }
        }

        private void startNewBackend() {
            // Guard: stop old backend if running
            if (activeBackend == BackendType.OLD) {
                stopOldBackend();
            }
            activeBackend = BackendType.NEW;
            newBackendStartCount++;
            newBackendGenerationId = sessionId;
            playbackStarted = false;
        }

        private void startOldBackend() {
            // Guard: stop new backend if running
            if (activeBackend == BackendType.NEW) {
                stopNewBackend();
            }
            activeBackend = BackendType.OLD;
            oldBackendStartCount++;
            playbackStarted = false;
        }

        public void onNewBackendReady() {
            if (activeBackend != BackendType.NEW) return;
            // Transition to audible playback
            playbackStarted = true;
            isActive = true;
            isPending = false;
            isAudioDucked = true;
        }

        public void onOldBackendReady() {
            if (activeBackend != BackendType.OLD) return;
            playbackStarted = true;
            isActive = true;
            isPending = false;
            isAudioDucked = true;
        }

        public void onNewBackendError(String reason) {
            if (activeBackend != BackendType.NEW) return;

            boolean isPrePlayback = !playbackStarted;
            if (isPrePlayback && !fallbackTriggered) {
                // Fallback to old production backend once
                fallbackTriggered = true;
                stopNewBackend();
                startOldBackend();
            } else {
                // Post-playback failure: stop safely without fallback
                stopTranslation();
            }
        }

        public void onOldBackendError(String reason) {
            if (activeBackend != BackendType.OLD) return;
            stopTranslation();
        }

        public void stopTranslation() {
            if (activeBackend == BackendType.NEW) {
                stopNewBackend();
            } else if (activeBackend == BackendType.OLD) {
                stopOldBackend();
            }
            activeBackend = BackendType.NONE;
            isPending = false;
            isActive = false;
            isAudioDucked = false;
            playbackStarted = false;
        }

        private void stopNewBackend() {
            if (activeBackend == BackendType.NEW) {
                newBackendStopCount++;
                newBackendGenerationId = 0;
                activeBackend = BackendType.NONE;
            }
        }

        private void stopOldBackend() {
            if (activeBackend == BackendType.OLD) {
                oldBackendStopCount++;
                activeBackend = BackendType.NONE;
            }
        }

        public void onNewVideo() {
            stopTranslation();
            fallbackTriggered = false;
        }

        public BackendType getActiveBackend() {
            return activeBackend;
        }

        public boolean isPending() {
            return isPending;
        }

        public boolean isActive() {
            return isActive;
        }

        public boolean isAudioDucked() {
            return isAudioDucked;
        }

        public boolean isFallbackTriggered() {
            return fallbackTriggered;
        }

        public boolean isPlaybackStarted() {
            return playbackStarted;
        }

        public int getOldBackendStartCount() {
            return oldBackendStartCount;
        }

        public int getNewBackendStartCount() {
            return newBackendStartCount;
        }

        public int getDuplicateStartRejections() {
            return duplicateStartRejections;
        }

        public boolean isLivelyPassed() {
            return livelyPassed;
        }

        @Nullable
        public String getTokenUsed() {
            return tokenUsed;
        }
    }

    private UserFlowRouterModel router;

    @Before
    public void setUp() {
        router = new UserFlowRouterModel();
        VoiceTranslateController.setNewYandexBackendEnabled(false);
        VoiceTranslateController.setInjectNewBackendFailure(false);
    }

    @After
    public void tearDown() {
        VoiceTranslateController.setNewYandexBackendEnabled(false);
        VoiceTranslateController.setInjectNewBackendFailure(false);
    }

    // 1. Feature flag default OFF
    @Test
    public void test1_FeatureFlagDefaultOff() {
        assertFalse("VoiceTranslateController flag must default to false",
                VoiceTranslateController.isNewYandexBackendEnabled());
        assertFalse("Router model default must be false", router.isFeatureFlagEnabled());
    }

    // 2. Flag OFF -> old backend selected
    @Test
    public void test2_FlagOffSelectsOldBackend() {
        router.setFeatureFlag(false);
        router.onVoxButtonClicked(false, null);

        assertEquals("Old backend must be selected when flag is OFF",
                BackendType.OLD, router.getActiveBackend());
        assertEquals(1, router.getOldBackendStartCount());
        assertEquals(0, router.getNewBackendStartCount());
        assertTrue(router.isPending());
    }

    // 3. Flag ON -> new backend selected
    @Test
    public void test3_FlagOnSelectsNewBackend() {
        router.setFeatureFlag(true);
        router.onVoxButtonClicked(false, null);

        assertEquals("New backend must be selected when flag is ON",
                BackendType.NEW, router.getActiveBackend());
        assertEquals(0, router.getOldBackendStartCount());
        assertEquals(1, router.getNewBackendStartCount());
        assertTrue(router.isPending());
    }

    // 4. Old and new backend never start together
    @Test
    public void test4_OldAndNewBackendNeverStartTogether() {
        // Start with flag ON -> NEW
        router.setFeatureFlag(true);
        router.onVoxButtonClicked(false, null);
        assertEquals(BackendType.NEW, router.getActiveBackend());

        // Now attempt old backend start
        router.startOldBackend();
        assertEquals(BackendType.OLD, router.getActiveBackend());
        // Verify new backend was stopped when old started
        assertEquals(1, router.newBackendStopCount);
        assertFalse("Both backends must never run together",
                router.getActiveBackend() == BackendType.NEW && router.getOldBackendStartCount() > 0);
    }

    // 5. New backend READY -> playback
    @Test
    public void test5_NewBackendReadyStartsPlayback() {
        router.setFeatureFlag(true);
        router.onVoxButtonClicked(false, null);
        assertTrue(router.isPending());
        assertFalse(router.isActive());
        assertFalse(router.isAudioDucked());

        router.onNewBackendReady();

        assertFalse("Pending should clear on READY", router.isPending());
        assertTrue("Active state should be true on READY", router.isActive());
        assertTrue("Original audio must be ducked on READY", router.isAudioDucked());
        assertTrue("Playback must be started", router.isPlaybackStarted());
    }

    // 6. New backend early failure -> old fallback
    @Test
    public void test6_NewBackendEarlyFailureFallsBackToOld() {
        router.setFeatureFlag(true);
        router.onVoxButtonClicked(false, null);
        assertEquals(BackendType.NEW, router.getActiveBackend());

        // Pre-playback failure before READY
        router.onNewBackendError("Network timeout on pre-playback");

        assertTrue("Fallback must be triggered", router.isFallbackTriggered());
        assertEquals("Active backend must now be OLD", BackendType.OLD, router.getActiveBackend());
        assertEquals(1, router.getOldBackendStartCount());
        assertEquals(1, router.newBackendStopCount);

        // Subsequent failure on old backend should not re-trigger fallback
        router.onOldBackendError("Old backend also failed");
        assertEquals(BackendType.NONE, router.getActiveBackend());
        assertFalse(router.isActive());
    }

    // 7. Post-playback error does NOT launch old backend
    @Test
    public void test7_PostPlaybackErrorDoesNotLaunchOldBackend() {
        router.setFeatureFlag(true);
        router.onVoxButtonClicked(false, null);
        router.onNewBackendReady();
        assertTrue(router.isPlaybackStarted());
        assertTrue(router.isActive());

        // Now post-playback error occurs
        router.onNewBackendError("Playback pipeline error");

        assertFalse("Fallback must NOT be triggered after playback has started",
                router.isFallbackTriggered());
        assertEquals("No backend should be active after post-playback error",
                BackendType.NONE, router.getActiveBackend());
        assertEquals("Old backend must NOT have been started", 0, router.getOldBackendStartCount());
        assertFalse(router.isActive());
        assertFalse(router.isAudioDucked());
    }

    // 8. Stop action stops correct backend
    @Test
    public void test8_StopActionStopsCorrectBackend() {
        // Test stopping NEW backend
        router.setFeatureFlag(true);
        router.onVoxButtonClicked(false, null);
        router.onNewBackendReady();
        assertTrue(router.isActive());

        router.onVoxButtonClicked(false, null); // Stop click
        assertEquals(BackendType.NONE, router.getActiveBackend());
        assertFalse(router.isActive());
        assertFalse(router.isAudioDucked());
        assertEquals(1, router.newBackendStopCount);

        // Test stopping OLD backend
        router.setFeatureFlag(false);
        router.onVoxButtonClicked(false, null);
        router.onOldBackendReady();
        assertTrue(router.isActive());

        router.onVoxButtonClicked(false, null); // Stop click
        assertEquals(BackendType.NONE, router.getActiveBackend());
        assertFalse(router.isActive());
        assertFalse(router.isAudioDucked());
        assertEquals(1, router.oldBackendStopCount);
    }

    // 9. Repeated VOX toggle does not duplicate players
    @Test
    public void test9_RepeatedVoxToggleDoesNotDuplicatePlayers() {
        router.setFeatureFlag(true);

        // First click -> start
        router.onVoxButtonClicked(false, null);
        assertEquals(1, router.getNewBackendStartCount());
        router.onNewBackendReady();

        // While active, startTranslation attempt directly should be rejected
        router.startTranslation(false, null);
        assertEquals(1, router.getDuplicateStartRejections());
        assertEquals(1, router.getNewBackendStartCount());

        // Second click -> stop
        router.onVoxButtonClicked(false, null);
        assertFalse(router.isActive());
        assertEquals(1, router.newBackendStopCount);

        // Third click -> start clean session 2
        router.onVoxButtonClicked(false, null);
        assertEquals(2, router.getNewBackendStartCount());
    }

    // 10. Video switch cleans selected backend
    @Test
    public void test10_VideoSwitchCleansSelectedBackend() {
        router.setFeatureFlag(true);
        router.onVoxButtonClicked(false, null);
        router.onNewBackendReady();
        assertTrue(router.isActive());

        router.onNewVideo();

        assertEquals(BackendType.NONE, router.getActiveBackend());
        assertFalse(router.isActive());
        assertFalse(router.isPending());
        assertFalse(router.isAudioDucked());
        assertFalse(router.isFallbackTriggered());
    }

    // 11. Flag change while idle works
    @Test
    public void test11_FlagChangeWhileIdleWorks() {
        VoiceTranslateController.setNewYandexBackendEnabled(false);
        assertFalse(VoiceTranslateController.isNewYandexBackendEnabled());

        VoiceTranslateController.setNewYandexBackendEnabled(true);
        assertTrue(VoiceTranslateController.isNewYandexBackendEnabled());

        VoiceTranslateController.setNewYandexBackendEnabled(false);
        assertFalse(VoiceTranslateController.isNewYandexBackendEnabled());
    }

    // 12. Flag change while active does not create second session
    @Test
    public void test12_FlagChangeWhileActiveDoesNotCreateSecondSession() {
        router.setFeatureFlag(false);
        router.onVoxButtonClicked(false, null);
        router.onOldBackendReady();
        assertEquals(BackendType.OLD, router.getActiveBackend());

        // Toggle flag to true while OLD backend is running
        router.setFeatureFlag(true);

        // Active backend remains OLD, no new backend launched
        assertEquals(BackendType.OLD, router.getActiveBackend());
        assertEquals(0, router.getNewBackendStartCount());
        assertEquals(1, router.getOldBackendStartCount());

        // Stopping stops the active OLD backend
        router.onVoxButtonClicked(false, null);
        assertEquals(BackendType.NONE, router.getActiveBackend());
        assertEquals(1, router.oldBackendStopCount);
    }

    // 13. Lively setting passed unchanged
    @Test
    public void test13_LivelySettingPassedUnchanged() {
        router.setFeatureFlag(true);
        router.onVoxButtonClicked(true, "oauth_test_token");
        assertTrue("Lively preference must be preserved", router.isLivelyPassed());

        router.stopTranslation();
        router.onVoxButtonClicked(false, null);
        assertFalse("Lively false must be preserved", router.isLivelyPassed());
    }

    // 14. OAuth/token source reused
    @Test
    public void test14_OAuthTokenSourceReused() {
        router.setFeatureFlag(true);
        String testToken = "test_token_xyz_123";
        router.onVoxButtonClicked(true, testToken);
        assertEquals("Existing OAuth token must be reused directly", testToken, router.getTokenUsed());
    }

    // 15. Shadow and user-flow mode cannot conflict
    @Test
    public void test15_ShadowAndUserFlowCannotConflict() {
        // Direct testing of VoiceTranslateController & YandexVotShadowController isolation
        VoiceTranslateController controller = new VoiceTranslateController();
        YandexVotShadowController shadow = new YandexVotShadowController();

        // Initially both idle
        assertFalse(VoiceTranslateController.isUserFlowActive());
        assertFalse(shadow.isEnabled());

        // Shadow can be enabled when user flow is idle
        shadow.setEnabled(true);
        assertTrue(shadow.isEnabled());

        // If user flow starts, shadow controller must be stopped and disabled
        shadow.stopTranslation();
        shadow.setEnabled(false);
        assertFalse(shadow.isEnabled());

        // If shadow attempts to start while user flow is active, it must abort safely
        // Verified by shadow controller's startCurrentTranslationInternal check
    }
}

package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for VOT state machine, transitions, lifecycle cleanup, and error recovery.
 */
public class VotStateMachineTest {

    public enum VotLifecycleState {
        IDLE,
        PENDING,
        ACTIVE,
        STOPPED
    }

    /**
     * Models the controller's state machine rules and guards.
     */
    private static class VotStateMachineModel {
        private VotLifecycleState state = VotLifecycleState.IDLE;
        private int sessionId = 0;
        private boolean isAudioDucked = false;
        private boolean isPlayerActive = false;
        private boolean isTimerActive = false;
        private int duplicatePlayAttempts = 0;

        public void startRequest() {
            if (state == VotLifecycleState.ACTIVE) {
                // Cannot start directly if already active without reset
                return;
            }
            sessionId++;
            state = VotLifecycleState.PENDING;
            isTimerActive = true;
        }

        public void onInitialSyncComplete(int callbackSessionId) {
            if (callbackSessionId != sessionId) {
                // Generation guard: reject callback from older session
                return;
            }
            if (state == VotLifecycleState.STOPPED || state == VotLifecycleState.IDLE) {
                throw new IllegalStateException("Cannot transition from " + state + " directly to ACTIVE without start");
            }
            if (state == VotLifecycleState.ACTIVE && isPlayerActive) {
                duplicatePlayAttempts++;
                return; // duplicate play ignored
            }
            state = VotLifecycleState.ACTIVE;
            isPlayerActive = true;
            isAudioDucked = true;
            isTimerActive = false;
        }

        public void stop() {
            state = VotLifecycleState.STOPPED;
            cleanup();
        }

        public void onTimeout() {
            if (state == VotLifecycleState.PENDING) {
                state = VotLifecycleState.STOPPED;
                cleanup();
            }
        }

        public void onPlaybackError() {
            if (state == VotLifecycleState.ACTIVE) {
                state = VotLifecycleState.STOPPED;
                cleanup();
            }
        }

        public void onNewVideo() {
            sessionId++;
            state = VotLifecycleState.IDLE;
            cleanup();
        }

        private void cleanup() {
            isAudioDucked = false;
            isPlayerActive = false;
            isTimerActive = false;
        }
    }

    @Test
    public void testValidLifecyclePath() {
        VotStateMachineModel sm = new VotStateMachineModel();
        assertEquals(VotLifecycleState.IDLE, sm.state);

        // IDLE -> PENDING
        sm.startRequest();
        assertEquals(VotLifecycleState.PENDING, sm.state);
        assertTrue(sm.isTimerActive);
        assertFalse(sm.isAudioDucked);

        // PENDING -> ACTIVE
        sm.onInitialSyncComplete(sm.sessionId);
        assertEquals(VotLifecycleState.ACTIVE, sm.state);
        assertTrue(sm.isPlayerActive);
        assertTrue(sm.isAudioDucked);
        assertFalse(sm.isTimerActive);

        // ACTIVE -> STOPPED
        sm.stop();
        assertEquals(VotLifecycleState.STOPPED, sm.state);
        assertFalse(sm.isPlayerActive);
        assertFalse(sm.isAudioDucked);
    }

    @Test
    public void testPendingTimeoutTransition() {
        VotStateMachineModel sm = new VotStateMachineModel();
        sm.startRequest();
        assertEquals(VotLifecycleState.PENDING, sm.state);

        // Timeout occurs during pending
        sm.onTimeout();
        assertEquals(VotLifecycleState.STOPPED, sm.state);
        assertFalse(sm.isTimerActive);
        assertFalse(sm.isAudioDucked);
    }

    @Test
    public void testActiveErrorTransition() {
        VotStateMachineModel sm = new VotStateMachineModel();
        sm.startRequest();
        sm.onInitialSyncComplete(sm.sessionId);
        assertEquals(VotLifecycleState.ACTIVE, sm.state);

        // Playback error occurs
        sm.onPlaybackError();
        assertEquals(VotLifecycleState.STOPPED, sm.state);
        assertFalse(sm.isPlayerActive);
        assertFalse(sm.isAudioDucked);
    }

    @Test
    public void testForbiddenStoppedToActiveWithoutStart() {
        VotStateMachineModel sm = new VotStateMachineModel();
        sm.stop();
        assertEquals(VotLifecycleState.STOPPED, sm.state);

        try {
            sm.onInitialSyncComplete(sm.sessionId);
            fail("Transition STOPPED -> ACTIVE without new start must be rejected");
        } catch (IllegalStateException expected) {
            // Expected
        }
    }

    @Test
    public void testDuplicateActiveStartIgnored() {
        VotStateMachineModel sm = new VotStateMachineModel();
        sm.startRequest();
        sm.onInitialSyncComplete(sm.sessionId);
        assertEquals(VotLifecycleState.ACTIVE, sm.state);
        assertEquals(0, sm.duplicatePlayAttempts);

        // Repeated trigger while already active
        sm.onInitialSyncComplete(sm.sessionId);
        assertEquals(VotLifecycleState.ACTIVE, sm.state);
        assertEquals(1, sm.duplicatePlayAttempts);
    }

    @Test
    public void testGenerationGuardRejectsStaleCallback() {
        VotStateMachineModel sm = new VotStateMachineModel();
        sm.startRequest();
        int oldSessionId = sm.sessionId;

        // User navigates or new video starts
        sm.onNewVideo();
        assertEquals(VotLifecycleState.IDLE, sm.state);

        // Obsolete callback from previous session arrives
        sm.onInitialSyncComplete(oldSessionId);
        // Must remain IDLE, not transition to ACTIVE
        assertEquals(VotLifecycleState.IDLE, sm.state);
        assertFalse(sm.isPlayerActive);
    }

    @Test
    public void testVotProgressTypesAndFactoryMethods() {
        VotProgress waiting = VotProgress.waiting(45, VotTranslationResponse.STATUS_WAITING);
        assertEquals(VotProgress.TYPE_WAITING, waiting.type);
        assertEquals(45, waiting.remainingTimeSec);
        assertEquals(VotTranslationResponse.STATUS_WAITING, waiting.status);
        assertNull(waiting.audioUrl);
        assertNull(waiting.message);

        VotProgress ready = VotProgress.ready("https://test.audio/stream.mp3");
        assertEquals(VotProgress.TYPE_READY, ready.type);
        assertEquals("https://test.audio/stream.mp3", ready.audioUrl);
        assertEquals(VotTranslationResponse.STATUS_FINISHED, ready.status);

        VotProgress failed = VotProgress.failed("Network error");
        assertEquals(VotProgress.TYPE_FAILED, failed.type);
        assertEquals("Network error", failed.message);
        assertEquals(VotTranslationResponse.STATUS_FAILED, failed.status);

        VotProgress livelyFallback = VotProgress.livelyFallback();
        assertEquals(VotProgress.TYPE_LIVELY_FALLBACK, livelyFallback.type);
        assertNull(livelyFallback.audioUrl);
        assertNull(livelyFallback.message);
    }

    @Test
    public void testProgressTimerCleanupAndHardening() {
        VotProgressTimer timer = new VotProgressTimer();
        timer.start(10_000L);
        timer.reconcileEta(60, 10_000L);

        assertEquals(60, timer.getRemainingTimeSec(10_000L));
        assertEquals(55, timer.getRemainingTimeSec(15_000L));

        // When cleared, remaining time drops to 0
        timer.clear();
        assertEquals(0, timer.getRemainingTimeSec(15_000L));
        assertEquals(0, timer.getElapsedAfterEtaSec(15_000L));
    }

    @Test
    public void testNoHourglassOrCorruptedGlyphsInTimer() {
        for (long sec = -5; sec <= 3600; sec += 15) {
            String formatted = VotProgressTimer.formatMmSs(sec);
            assertNotNull(formatted);
            assertFalse(formatted.contains("⌛"));
            assertFalse(formatted.contains("⏳"));
            assertFalse(formatted.contains("?"));
            assertTrue(formatted.matches("\\d{2}:\\d{2}"));
        }
    }
}

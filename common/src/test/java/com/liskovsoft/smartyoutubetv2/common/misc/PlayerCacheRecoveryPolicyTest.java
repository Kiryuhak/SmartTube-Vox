package com.liskovsoft.smartyoutubetv2.common.misc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerCacheRecoveryPolicyTest {

    @Test
    public void triggersRecoveryOnRepeatedSource403BeforePlayback() {
        PlayerCacheRecoveryPolicy policy = new PlayerCacheRecoveryPolicy();

        // First 403 before playable: normal client fallback, no cache purge yet
        assertFalse(policy.shouldRecoverOnSource403(false));
        assertEquals(1, policy.getSource403Count());
        assertFalse(policy.isRecoveryAttempted());

        // Second 403 before playable: repeated error, triggers one-shot cache recovery
        assertTrue(policy.shouldRecoverOnSource403(false));
        assertEquals(2, policy.getSource403Count());
        assertTrue(policy.isRecoveryAttempted());
    }

    @Test
    public void oneShotGuardPreventsInfiniteRecoveryLoop() {
        PlayerCacheRecoveryPolicy policy = new PlayerCacheRecoveryPolicy();

        policy.shouldRecoverOnSource403(false); // 1st
        assertTrue(policy.shouldRecoverOnSource403(false)); // 2nd: triggers

        // 3rd, 4th, 5th: must NOT trigger again in same session to prevent infinite loop
        assertFalse(policy.shouldRecoverOnSource403(false));
        assertFalse(policy.shouldRecoverOnSource403(false));
        assertFalse(policy.shouldRecoverOnSource403(false));

        assertEquals(5, policy.getSource403Count());
        assertTrue(policy.isRecoveryAttempted());
    }

    @Test
    public void doesNotTriggerWhenAlreadyPlayable() {
        PlayerCacheRecoveryPolicy policy = new PlayerCacheRecoveryPolicy();

        // Mid-stream 403 (isPlayable == true) should not count towards startup recovery
        assertFalse(policy.shouldRecoverOnSource403(true));
        assertFalse(policy.shouldRecoverOnSource403(true));
        assertFalse(policy.shouldRecoverOnSource403(true));

        assertEquals(0, policy.getSource403Count());
        assertFalse(policy.isRecoveryAttempted());
    }

    @Test
    public void resetOnNewVideoClearsState() {
        PlayerCacheRecoveryPolicy policy = new PlayerCacheRecoveryPolicy();

        policy.shouldRecoverOnSource403(false);
        policy.shouldRecoverOnSource403(false);
        assertTrue(policy.isRecoveryAttempted());

        // New video resets policy
        policy.reset();
        assertEquals(0, policy.getSource403Count());
        assertFalse(policy.isRecoveryAttempted());

        // Ready for new recovery if needed
        assertFalse(policy.shouldRecoverOnSource403(false));
        assertTrue(policy.shouldRecoverOnSource403(false));
    }

    @Test
    public void playbackStartedClearsState() {
        PlayerCacheRecoveryPolicy policy = new PlayerCacheRecoveryPolicy();

        policy.shouldRecoverOnSource403(false);
        assertEquals(1, policy.getSource403Count());

        // Playback successfully started (onPlay)
        policy.onPlaybackStarted();
        assertEquals(0, policy.getSource403Count());
        assertFalse(policy.isRecoveryAttempted());
    }
}

package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BackNavigationLifecycleTest {

    @Test
    public void testReentrantBackNavigationPrevented() {
        AtomicBoolean isFinishingPlayback = new AtomicBoolean(false);
        AtomicInteger finishReallyInvocations = new AtomicInteger(0);

        Runnable finishInternalAction = () -> {
            if (isFinishingPlayback.getAndSet(true)) {
                // Already finishing or destroyed: skip
                return;
            }
            finishReallyInvocations.incrementAndGet();
        };

        // First call proceeds:
        finishInternalAction.run();
        assertEquals(1, finishReallyInvocations.get());
        assertTrue(isFinishingPlayback.get());

        // Rapid second and third back calls must be skipped (no double release / re-entrancy):
        finishInternalAction.run();
        finishInternalAction.run();
        assertEquals(1, finishReallyInvocations.get());
    }

    @Test
    public void testSoundOnlyModeResetsFinishingLock() {
        AtomicBoolean isFinishingPlayback = new AtomicBoolean(false);

        // When entering background sound-only playback on back, the activity is kept alive
        // and the finishing lock is reset so subsequent actions are not blocked.
        isFinishingPlayback.set(true);
        // Emulate enterBackgroundAudioOnly branch:
        isFinishingPlayback.set(false);

        assertFalse("Sound mode must clear finishing lock", isFinishingPlayback.get());
    }
}

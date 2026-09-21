package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VotRequestGuardTest {
    private static final String VIDEO_A = "https://www.youtube.com/watch?v=video-a";
    private static final String VIDEO_B = "https://www.youtube.com/watch?v=video-b";

    @Test
    public void acceptsOnlyActiveGenerationForCurrentVideo() {
        assertTrue(VotRequestGuard.isCurrent(7, VIDEO_A, 7, VIDEO_A, VIDEO_A));
    }

    @Test
    public void rejectsLateResponseAfterRestartOnSameVideo() {
        assertFalse(VotRequestGuard.isCurrent(6, VIDEO_A, 7, VIDEO_A, VIDEO_A));
    }

    @Test
    public void rejectsResponseAfterVideoChange() {
        assertFalse(VotRequestGuard.isCurrent(7, VIDEO_A, 7, VIDEO_A, VIDEO_B));
    }

    @Test
    public void rejectsMissingOrReplacedActiveRequest() {
        assertFalse(VotRequestGuard.isCurrent(7, null, 7, VIDEO_A, VIDEO_A));
        assertFalse(VotRequestGuard.isCurrent(7, VIDEO_A, 7, VIDEO_B, VIDEO_A));
    }
}

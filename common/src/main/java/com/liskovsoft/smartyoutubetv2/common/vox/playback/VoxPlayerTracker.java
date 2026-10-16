package com.liskovsoft.smartyoutubetv2.common.vox.playback;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks active ExoPlayer instances and recreations to enforce the single-player invariant
 * and provide safe telemetry during OutOfMemory diagnostics.
 */
public final class VoxPlayerTracker {
    private static final AtomicInteger sActivePlayerInstances = new AtomicInteger(0);
    private static final AtomicInteger sRecreationCount = new AtomicInteger(0);

    private VoxPlayerTracker() {}

    public static void onPlayerCreated() {
        sActivePlayerInstances.incrementAndGet();
    }

    public static void onPlayerReleased() {
        sActivePlayerInstances.updateAndGet(current -> Math.max(0, current - 1));
    }

    public static void onPlayerRecreated() {
        sRecreationCount.incrementAndGet();
    }

    public static int getActivePlayerInstances() {
        return sActivePlayerInstances.get();
    }

    public static int getRecreationCount() {
        return sRecreationCount.get();
    }

    public static boolean isSingleInstanceInvariantSatisfied() {
        return sActivePlayerInstances.get() <= 1;
    }

    public static void reset() {
        sActivePlayerInstances.set(0);
        sRecreationCount.set(0);
    }
}

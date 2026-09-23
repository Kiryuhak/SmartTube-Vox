package com.liskovsoft.smartyoutubetv2.common.oauth;

import java.util.concurrent.atomic.AtomicInteger;

/** Tracks Device Code attempts and rejects callbacks after refresh, cancel, or BACK. */
final class YandexPollingSession {
    private final AtomicInteger mGeneration = new AtomicInteger();

    int start() {
        return mGeneration.incrementAndGet();
    }

    void invalidate() {
        mGeneration.incrementAndGet();
    }

    boolean isCurrent(int generation) {
        return mGeneration.get() == generation;
    }
}

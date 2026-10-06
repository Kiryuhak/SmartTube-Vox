package com.liskovsoft.smartyoutubetv2.common.vox.badge;

/** Tracks the identity of a card while Leanback recycles its holder. */
public final class VoxQualityBindingGuard {
    private String videoId;
    private long generation;

    public long bind(String id) {
        videoId = id;
        return ++generation;
    }

    public void unbind() {
        videoId = null;
        generation++;
    }

    public boolean accepts(String id, long token) {
        return videoId != null && videoId.equals(id) && generation == token;
    }
}

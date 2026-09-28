package com.liskovsoft.smartyoutubetv2.common.vot;

import java.util.ArrayList;
import java.util.List;

public class VotTranslationAudioResponse {
    public static final int STATUS_UNKNOWN = 0;
    public static final int STATUS_WAITING_CHUNKS = 1;
    public static final int STATUS_DONE = 2;

    public int status;
    public final List<String> remainingChunks = new ArrayList<>();

    public boolean isDone() {
        return status == STATUS_DONE;
    }

    public boolean isWaitingChunks() {
        return status == STATUS_WAITING_CHUNKS;
    }
}

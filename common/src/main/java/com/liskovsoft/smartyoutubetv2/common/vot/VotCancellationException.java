package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;

/**
 * Thrown when an audio upload operation is cancelled before or during execution.
 */
public class VotCancellationException extends IOException {
    public VotCancellationException(String message) {
        super(message);
    }
}

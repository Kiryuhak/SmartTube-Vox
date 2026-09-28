package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;

/**
 * Thrown when an error occurs reading or preparing the audio source stream.
 * Distinguishes source I/O errors from transport/network errors and server rejections.
 */
public class VotAudioSourceException extends IOException {
    public VotAudioSourceException(String message) {
        super(message);
    }

    public VotAudioSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}

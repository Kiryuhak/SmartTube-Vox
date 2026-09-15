package com.google.android.exoplayer2.source.sabr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public class SabrLogUtilTest {
    @Test
    public void chunkLoadErrorMessageDoesNotExposeSignedMediaUrl() {
        String signedUrl = "https://rr1---sn-primary.googlevideo.com/videoplayback?"
                + "sig=secret-signature&pot=secret-token";

        String message = SabrLogUtil.buildChunkLoadErrorMessage(
                "rr1---sn-primary.googlevideo.com",
                new IOException("Unable to connect to " + signedUrl));

        assertTrue(message.contains("host=rr1---sn-primary.googlevideo.com"));
        assertFalse(message.contains("videoplayback"));
        assertFalse(message.contains("secret-signature"));
        assertFalse(message.contains("secret-token"));
    }
}

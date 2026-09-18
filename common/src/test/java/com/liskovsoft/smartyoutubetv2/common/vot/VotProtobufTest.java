package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VotProtobufTest {
    @Test
    public void truncatedLengthDelimitedFieldDoesNotCrashDecoder() {
        byte[] truncatedResponse = new byte[]{0x0A, 0x72};

        VotTranslationResponse response = VotProtobuf.decodeTranslationResponse(truncatedResponse);

        assertEquals(VotTranslationResponse.STATUS_FAILED, response.status);
    }
}
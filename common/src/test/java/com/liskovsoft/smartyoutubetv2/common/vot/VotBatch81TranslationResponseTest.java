package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.*;

public class VotBatch81TranslationResponseTest {
    @Test
    public void failedWithoutMessageOrRetryKeepsFieldsAbsent() {
        VotWireWriter wire = new VotWireWriter();
        wire.writeInt32IncludeZero(4, VotTranslationResponse.STATUS_FAILED);
        VotTranslationResponse response = VotProtobuf.decodeTranslationResponse(wire.toByteArray());
        assertEquals(VotTranslationResponse.STATUS_FAILED, response.status);
        assertNull(response.message);
        assertNull(response.allowToTranslateVideo);
        assertNull(response.shouldRetry);
        assertNull(response.unknown3);
    }

    @Test
    public void failedWithRetryFlagKeepsMessageAbsent() {
        VotWireWriter wire = new VotWireWriter();
        wire.writeInt32IncludeZero(4, VotTranslationResponse.STATUS_FAILED);
        wire.writeInt32(12, 1);
        VotTranslationResponse response = VotProtobuf.decodeTranslationResponse(wire.toByteArray());
        assertNull(response.message);
        assertEquals(Integer.valueOf(1), response.shouldRetry);
    }

    @Test
    public void failedWithMessageAndAllKnownMetadataPreservesWireTypes() {
        VotWireWriter wire = new VotWireWriter();
        wire.writeInt32IncludeZero(4, VotTranslationResponse.STATUS_FAILED);
        wire.writeString(9, "fixture-message");
        wire.writeInt32IncludeZero(11, 0);
        wire.writeInt32IncludeZero(12, 0);
        wire.writeInt32(13, 7);
        VotTranslationResponse response = VotProtobuf.decodeTranslationResponse(wire.toByteArray());
        assertEquals("fixture-message", response.message);
        assertEquals(Boolean.FALSE, response.allowToTranslateVideo);
        assertEquals(Integer.valueOf(0), response.shouldRetry);
        assertEquals(Integer.valueOf(7), response.unknown3);
    }

    @Test
    public void lengthDelimitedMetadataIsNotReadAsVarint() {
        VotWireWriter wire = new VotWireWriter();
        wire.writeString(11, "not-a-bool");
        wire.writeString(12, "not-an-int");
        wire.writeString(13, "not-an-int");
        VotTranslationResponse response = VotProtobuf.decodeTranslationResponse(wire.toByteArray());
        assertNull(response.allowToTranslateVideo);
        assertNull(response.shouldRetry);
        assertNull(response.unknown3);
    }
}

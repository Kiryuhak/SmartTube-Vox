package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class VotBatch85LivelyResponseTest {
    @Test
    public void absentFieldIsDistinctFromExplicitFalse() {
        VotWireWriter wire = new VotWireWriter();
        wire.writeInt32(4, VotTranslationResponse.STATUS_FINISHED);
        assertNull(VotProtobuf.decodeTranslationResponse(wire.toByteArray()).isLivelyVoice);
    }

    @Test
    public void explicitFalseIsPreservedOnFailedResponse() {
        VotWireWriter wire = new VotWireWriter();
        wire.writeInt32IncludeZero(4, VotTranslationResponse.STATUS_FAILED);
        wire.writeInt32IncludeZero(10, 0);
        VotTranslationResponse response = VotProtobuf.decodeTranslationResponse(wire.toByteArray());
        assertEquals(VotTranslationResponse.STATUS_FAILED, response.status);
        assertEquals(Boolean.FALSE, response.isLivelyVoice);
        assertFalse(VotProtobuf.unknownFieldMetadata(wire.toByteArray(), false).toString().contains("field=10"));
    }

    @Test
    public void explicitTrueIsPreservedOnFinishedResponse() {
        VotWireWriter wire = new VotWireWriter();
        wire.writeInt32(4, VotTranslationResponse.STATUS_FINISHED);
        wire.writeInt32(10, 1);
        wire.writeInt32(11, 1);
        wire.writeInt32IncludeZero(12, 0);
        wire.writeInt32(13, 1);
        VotTranslationResponse response = VotProtobuf.decodeTranslationResponse(wire.toByteArray());
        assertEquals(VotTranslationResponse.STATUS_FINISHED, response.status);
        assertEquals(Boolean.TRUE, response.isLivelyVoice);
        assertEquals(Boolean.TRUE, response.allowToTranslateVideo);
        assertEquals(Integer.valueOf(0), response.shouldRetry);
        assertEquals(Integer.valueOf(1), response.unknown3);
        assertFalse(VotProtobuf.unknownFieldMetadata(wire.toByteArray(), false).toString().contains("field=10"));
    }

    @Test
    public void lengthDelimitedFieldTenIsNotTreatedAsBoolean() {
        VotWireWriter wire = new VotWireWriter();
        wire.writeString(10, "wrong-wire-type");
        assertNull(VotProtobuf.decodeTranslationResponse(wire.toByteArray()).isLivelyVoice);
    }
}

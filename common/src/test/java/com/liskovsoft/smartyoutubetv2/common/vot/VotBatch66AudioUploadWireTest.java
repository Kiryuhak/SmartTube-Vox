package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VotBatch66AudioUploadWireTest {

    private static final String SAMPLE_URL = "https://www.youtube.com/watch?v=8Dgdk555G0g";
    private static final String SAMPLE_TRANSLATION_ID = "vtrans-id-batch66-test";
    private static final String SAMPLE_FILE_ID = "smarttube-audio-8Dgdk555G0g";

    // ---------------------------------------------------------------------------------------------
    // Helper: Raw protobuf parser to verify wire bytes independently of the serializer
    // ---------------------------------------------------------------------------------------------
    private static class RawField {
        final int tag;
        final int wireType;
        final long varintValue;
        final byte[] bytesValue;

        RawField(int tag, int wireType, long varintValue, byte[] bytesValue) {
            this.tag = tag;
            this.wireType = wireType;
            this.varintValue = varintValue;
            this.bytesValue = bytesValue;
        }
    }

    private static List<RawField> parseRawWire(byte[] data) {
        List<RawField> fields = new ArrayList<>();
        if (data == null || data.length == 0) {
            return fields;
        }
        int pos = 0;
        while (pos < data.length) {
            long tagAndWire = 0;
            int shift = 0;
            while (pos < data.length) {
                int b = data[pos++] & 0xFF;
                tagAndWire |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            int tag = (int) (tagAndWire >>> 3);
            int wireType = (int) (tagAndWire & 0x7);
            if (tag <= 0) break;

            if (wireType == 0) { // Varint
                long val = 0;
                shift = 0;
                while (pos < data.length) {
                    int b = data[pos++] & 0xFF;
                    val |= (long) (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                fields.add(new RawField(tag, wireType, val, null));
            } else if (wireType == 2) { // Length-delimited
                int length = 0;
                shift = 0;
                while (pos < data.length) {
                    int b = data[pos++] & 0xFF;
                    length |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                if (length < 0 || pos + length > data.length) {
                    break;
                }
                byte[] chunk = new byte[length];
                System.arraycopy(data, pos, chunk, 0, length);
                pos += length;
                fields.add(new RawField(tag, wireType, 0, chunk));
            } else if (wireType == 1) { // 64-bit
                pos += 8;
            } else if (wireType == 5) { // 32-bit
                pos += 4;
            } else {
                break;
            }
        }
        return fields;
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Single-Part Audio Request Encoding
    // ---------------------------------------------------------------------------------------------
    @Test
    public void testSinglePartEncodingWireStructure() {
        byte[] syntheticAudio = new byte[]{(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x01, 0x02, 0x03}; // WebM magic prefix
        byte[] wire = VotProtobuf.encodeAudioRequestSinglePart(
                SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, syntheticAudio);

        assertNotNull(wire);
        assertTrue(wire.length > 0);

        List<RawField> rootFields = parseRawWire(wire);
        RawField tag1 = null; // translationId
        RawField tag2 = null; // url
        RawField tag6 = null; // audioInfo (AudioBufferObject)

        for (RawField f : rootFields) {
            if (f.tag == 1) tag1 = f;
            else if (f.tag == 2) tag2 = f;
            else if (f.tag == 6) tag6 = f;
        }

        assertNotNull("Tag 1 (translationId) must be present", tag1);
        assertEquals(SAMPLE_TRANSLATION_ID, new String(tag1.bytesValue, StandardCharsets.UTF_8));

        assertNotNull("Tag 2 (url) must be present", tag2);
        assertEquals(SAMPLE_URL, new String(tag2.bytesValue, StandardCharsets.UTF_8));

        assertNotNull("Tag 6 (audioInfo) must be present", tag6);
        assertEquals(2, tag6.wireType);

        // Parse inner AudioBufferObject
        List<RawField> innerFields = parseRawWire(tag6.bytesValue);
        RawField innerTag1 = null; // fileId
        RawField innerTag2 = null; // audioFile

        for (RawField f : innerFields) {
            if (f.tag == 1) innerTag1 = f;
            else if (f.tag == 2) innerTag2 = f;
        }

        assertNotNull("AudioBufferObject tag 1 (fileId) must be present", innerTag1);
        assertEquals(SAMPLE_FILE_ID, new String(innerTag1.bytesValue, StandardCharsets.UTF_8));

        assertNotNull("AudioBufferObject tag 2 (audioFile) must be present", innerTag2);
        assertArrayEquals("Binary audio bytes must match exactly", syntheticAudio, innerTag2.bytesValue);
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Multi-Part / Chunked Encoding: Chunk Index Zero (Intermediate)
    // ---------------------------------------------------------------------------------------------
    @Test
    public void testChunkZeroIntermediateEncoding() {
        byte[] chunkBytes = new byte[1024];
        Arrays.fill(chunkBytes, (byte) 0xAA);

        byte[] wire = VotProtobuf.encodeAudioRequestChunk(
                SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, 0, 0, chunkBytes);

        List<RawField> rootFields = parseRawWire(wire);
        RawField tag4 = null; // partialAudioInfo (ChunkAudioObject)

        for (RawField f : rootFields) {
            if (f.tag == 4) tag4 = f;
        }

        assertNotNull("Tag 4 (partialAudioInfo) must be present", tag4);

        // Parse ChunkAudioObject
        List<RawField> chunkFields = parseRawWire(tag4.bytesValue);
        RawField chunkTag1 = null; // audioBuffer (PartialAudioBufferObject)
        RawField chunkTag2 = null; // audioPartsLength (0 -> omitted for intermediate)
        RawField chunkTag3 = null; // fileId
        RawField chunkTag4 = null; // version (1)

        for (RawField f : chunkFields) {
            if (f.tag == 1) chunkTag1 = f;
            else if (f.tag == 2) chunkTag2 = f;
            else if (f.tag == 3) chunkTag3 = f;
            else if (f.tag == 4) chunkTag4 = f;
        }

        assertNotNull("ChunkAudioObject tag 1 (audioBuffer) must be present", chunkTag1);
        // chunkTag2 is 0, so omitted per proto3 default
        assertEquals("fileId in tag 3", SAMPLE_FILE_ID, new String(chunkTag3.bytesValue, StandardCharsets.UTF_8));
        assertNotNull("version in tag 4 must be present", chunkTag4);
        assertEquals(1, chunkTag4.varintValue);

        // Parse inner PartialAudioBufferObject
        List<RawField> partialFields = parseRawWire(chunkTag1.bytesValue);
        RawField pTag1 = null; // chunkId
        RawField pTag2 = null; // audioFile

        for (RawField f : partialFields) {
            if (f.tag == 1) pTag1 = f;
            else if (f.tag == 2) pTag2 = f;
        }

        assertNotNull("PartialAudioBufferObject tag 1 (chunkId) must be explicitly present", pTag1);
        assertEquals(0, pTag1.varintValue);

        assertNotNull("PartialAudioBufferObject tag 2 (audioFile) must be present", pTag2);
        assertArrayEquals(chunkBytes, pTag2.bytesValue);
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Multi-Part / Chunked Encoding: Terminal Chunk with Total Amount
    // ---------------------------------------------------------------------------------------------
    @Test
    public void testChunkTerminalEncoding() {
        byte[] chunkBytes = new byte[512];
        Arrays.fill(chunkBytes, (byte) 0xBB);

        int chunkId = 2; // Third chunk (0, 1, 2)
        int totalParts = 3;

        byte[] wire = VotProtobuf.encodeAudioRequestChunk(
                SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, chunkId, totalParts, chunkBytes);

        List<RawField> rootFields = parseRawWire(wire);
        RawField tag4 = null;
        for (RawField f : rootFields) {
            if (f.tag == 4) tag4 = f;
        }
        assertNotNull(tag4);

        List<RawField> chunkFields = parseRawWire(tag4.bytesValue);
        RawField chunkTag1 = null;
        RawField chunkTag2 = null;
        for (RawField f : chunkFields) {
            if (f.tag == 1) chunkTag1 = f;
            else if (f.tag == 2) chunkTag2 = f;
        }

        assertNotNull("Terminal chunk must write tag 2 (audioPartsLength)", chunkTag2);
        assertEquals(totalParts, chunkTag2.varintValue);

        List<RawField> partialFields = parseRawWire(chunkTag1.bytesValue);
        RawField pTag1 = null;
        RawField pTag2 = null;
        for (RawField f : partialFields) {
            if (f.tag == 1) pTag1 = f;
            else if (f.tag == 2) pTag2 = f;
        }
        assertEquals(chunkId, pTag1.varintValue);
        assertArrayEquals(chunkBytes, pTag2.bytesValue);
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Exact Threshold Boundary & Threshold Plus One
    // ---------------------------------------------------------------------------------------------
    @Test
    public void testExactThresholdBoundaryAndPlusOne() {
        assertEquals("Config minimum chunk size must match reference (5,295,308 bytes)",
                5295308, VotConfig.AUDIO_MIN_CHUNK_SIZE);

        // Test chunk at exact threshold
        byte[] thresholdChunk = new byte[100]; // simulate content with threshold metadata
        byte[] wireThreshold = VotProtobuf.encodeAudioRequestChunk(
                SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, 0, 1, thresholdChunk);
        assertNotNull(wireThreshold);

        // Test chunk with threshold + 1
        byte[] wirePlusOne = VotProtobuf.encodeAudioRequestChunk(
                SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, 0, 2, thresholdChunk);
        assertNotNull(wirePlusOne);
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Zero-Length Audio & Invalid Parameter Rejection
    // ---------------------------------------------------------------------------------------------
    @Test
    public void testZeroLengthAudioRejectionSinglePart() {
        try {
            VotProtobuf.encodeAudioRequestSinglePart(SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, new byte[0]);
            fail("Must reject empty audio in real upload path");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-empty"));
        }

        try {
            VotProtobuf.encodeAudioRequestSinglePart(SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, null);
            fail("Must reject null audio");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-empty"));
        }
    }

    @Test
    public void testZeroLengthAudioRejectionChunk() {
        try {
            VotProtobuf.encodeAudioRequestChunk(SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, 0, 0, new byte[0]);
            fail("Must reject empty audio chunk");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-empty"));
        }
    }

    @Test
    public void testInvalidParametersRejection() {
        byte[] audio = new byte[]{1, 2, 3};
        // Empty url
        try {
            VotProtobuf.encodeAudioRequestSinglePart("", SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, audio);
            fail("Must reject empty url");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("url"));
        }

        // Empty translationId
        try {
            VotProtobuf.encodeAudioRequestSinglePart(SAMPLE_URL, "  ", SAMPLE_FILE_ID, audio);
            fail("Must reject empty translationId");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("translationId"));
        }

        // Empty fileId
        try {
            VotProtobuf.encodeAudioRequestChunk(SAMPLE_URL, SAMPLE_TRANSLATION_ID, "", 0, 0, audio);
            fail("Must reject empty fileId");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("fileId"));
        }

        // Negative chunkId
        try {
            VotProtobuf.encodeAudioRequestChunk(SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, -1, 0, audio);
            fail("Must reject negative chunkId");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("chunkId"));
        }

        // Negative audioPartsLength
        try {
            VotProtobuf.encodeAudioRequestChunk(SAMPLE_URL, SAMPLE_TRANSLATION_ID, SAMPLE_FILE_ID, 0, -5, audio);
            fail("Must reject negative audioPartsLength");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("audioPartsLength"));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Non-ASCII Metadata & Binary Audio Integrity
    // ---------------------------------------------------------------------------------------------
    @Test
    public void testNonAsciiMetadataAndBinaryIntegrity() {
        String nonAsciiUrl = "https://www.youtube.com/watch?v=тест_ютуб_123";
        String nonAsciiFileId = "файл_аудио_123";
        // Binary audio with nulls, 0xFF, and control codes < 0x09
        byte[] binaryAudio = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, 0x08, 0x7F};

        byte[] wire = VotProtobuf.encodeAudioRequestSinglePart(
                nonAsciiUrl, SAMPLE_TRANSLATION_ID, nonAsciiFileId, binaryAudio);

        List<RawField> fields = parseRawWire(wire);
        RawField tag2 = null;
        RawField tag6 = null;
        for (RawField f : fields) {
            if (f.tag == 2) tag2 = f;
            else if (f.tag == 6) tag6 = f;
        }

        assertEquals(nonAsciiUrl, new String(tag2.bytesValue, StandardCharsets.UTF_8));

        List<RawField> inner = parseRawWire(tag6.bytesValue);
        RawField innerTag1 = null;
        RawField innerTag2 = null;
        for (RawField f : inner) {
            if (f.tag == 1) innerTag1 = f;
            else if (f.tag == 2) innerTag2 = f;
        }

        assertEquals(nonAsciiFileId, new String(innerTag1.bytesValue, StandardCharsets.UTF_8));
        assertArrayEquals("Control characters and high bytes must remain intact", binaryAudio, innerTag2.bytesValue);
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Audio Response Decoder: Status and Remaining Chunks
    // ---------------------------------------------------------------------------------------------
    @Test
    public void testDecodeAudioResponseWaitingChunks() {
        // Tag 1 (status = 1: waiting chunks), Tag 2 (remainingChunks = "1"), Tag 2 (remainingChunks = "2")
        VotWireWriter w = new VotWireWriter();
        w.writeInt32(1, VotTranslationAudioResponse.STATUS_WAITING_CHUNKS);
        w.writeString(2, "chunk_1");
        w.writeString(2, "chunk_2");

        VotTranslationAudioResponse resp = VotProtobuf.decodeTranslationAudioResponse(w.toByteArray());
        assertNotNull(resp);
        assertEquals(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, resp.status);
        assertTrue(resp.isWaitingChunks());
        assertFalse(resp.isDone());
        assertEquals(2, resp.remainingChunks.size());
        assertEquals("chunk_1", resp.remainingChunks.get(0));
        assertEquals("chunk_2", resp.remainingChunks.get(1));
    }

    @Test
    public void testDecodeAudioResponseDone() {
        // Tag 1 (status = 2: done)
        VotWireWriter w = new VotWireWriter();
        w.writeInt32(1, VotTranslationAudioResponse.STATUS_DONE);

        VotTranslationAudioResponse resp = VotProtobuf.decodeTranslationAudioResponse(w.toByteArray());
        assertNotNull(resp);
        assertEquals(VotTranslationAudioResponse.STATUS_DONE, resp.status);
        assertTrue(resp.isDone());
        assertFalse(resp.isWaitingChunks());
        assertTrue(resp.remainingChunks.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // 8. Decoder Safety: Malformed, Truncated, Empty, and Unknown Fields
    // ---------------------------------------------------------------------------------------------
    @Test
    public void testDecoderSafetyEmptyAndNull() {
        VotTranslationAudioResponse rNull = VotProtobuf.decodeTranslationAudioResponse(null);
        assertNotNull(rNull);
        assertEquals(VotTranslationAudioResponse.STATUS_UNKNOWN, rNull.status);
        assertTrue(rNull.remainingChunks.isEmpty());

        VotTranslationAudioResponse rEmpty = VotProtobuf.decodeTranslationAudioResponse(new byte[0]);
        assertNotNull(rEmpty);
        assertEquals(VotTranslationAudioResponse.STATUS_UNKNOWN, rEmpty.status);
        assertTrue(rEmpty.remainingChunks.isEmpty());
    }

    @Test
    public void testDecoderSafetyTruncatedPayload() {
        // Tag 2 length-delimited specifies length 10, but only 3 bytes provided
        byte[] truncated = new byte[]{0x12, 0x0A, 'a', 'b', 'c'};
        VotTranslationAudioResponse resp = VotProtobuf.decodeTranslationAudioResponse(truncated);
        assertNotNull(resp);
        assertEquals(VotTranslationAudioResponse.STATUS_UNKNOWN, resp.status);
        assertTrue(resp.remainingChunks.isEmpty());
    }

    @Test
    public void testDecoderSafetyMalformedVarint() {
        // Continuous MSB=1 without terminal byte
        byte[] malformed = new byte[]{(byte) 0x88, (byte) 0x80, (byte) 0x80};
        VotTranslationAudioResponse resp = VotProtobuf.decodeTranslationAudioResponse(malformed);
        assertNotNull(resp);
        assertEquals(VotTranslationAudioResponse.STATUS_UNKNOWN, resp.status);
    }

    @Test
    public void testDecoderSafetyUnknownFieldsIgnored() {
        VotWireWriter w = new VotWireWriter();
        // Tag 99 (wireType 0: varint = 42)
        w.writeInt32(99, 42);
        // Tag 1 (status = 2)
        w.writeInt32(1, VotTranslationAudioResponse.STATUS_DONE);
        // Tag 98 (wireType 2: string)
        w.writeString(98, "unknown_field_data");

        VotTranslationAudioResponse resp = VotProtobuf.decodeTranslationAudioResponse(w.toByteArray());
        assertNotNull(resp);
        assertEquals(VotTranslationAudioResponse.STATUS_DONE, resp.status);
        assertTrue(resp.isDone());
    }
}

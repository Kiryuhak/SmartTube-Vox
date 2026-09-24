package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SmartTube VOX — Batch #67
 * Comprehensive synthetic unit tests for VotAudioUploader, VotAudioSource, progressive streaming,
 * chunking boundaries, terminal metadata, response validation, session continuity, retries, and cancellation.
 */
public class VotBatch67AudioUploaderTest {
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=TEST_AUDIO_67";
    private static final String TRANSLATION_ID = "tr-test-67";
    private static final String FILE_ID = "smarttube-android-test67";
    private static final String OAUTH_TOKEN = "test-oauth-token-67";

    private static VotSession createSession() {
        VotSession s = new VotSession();
        s.secretKey = "test-secret-key-67";
        s.uuid = "test-session-uuid-67";
        s.expiresSec = 3600;
        s.createdAtMs = System.currentTimeMillis();
        return s;
    }

    private static byte[] makeResponseBytes(int status, List<String> remainingChunks) {
        VotWireWriter w = new VotWireWriter();
        w.writeInt32(1, status);
        if (remainingChunks != null) {
            for (String rc : remainingChunks) {
                w.writeString(2, rc);
            }
        }
        return w.toByteArray();
    }

    // =============================================================================================
    // 1. Single-Part Upload: Small Valid Audio Stream
    // =============================================================================================
    @Test
    public void testSinglePartUpload_SmallValidAudio() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        VotAudioUploader uploader = new VotAudioUploader(http, 1024);
        byte[] audioBytes = "Hello Yandex Voice Translation Audio Payload".getBytes(StandardCharsets.UTF_8);
        VotAudioSource source = VotAudioSource.fromBytes(audioBytes);

        VotTranslationAudioResponse resp = uploader.uploadAudio(
                VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), OAUTH_TOKEN, source);

        assertNotNull(resp);
        assertTrue(resp.isDone());
        assertEquals(1, http.requests.size());

        MockUploadHttp.Record req = http.requests.get(0);
        assertEquals("/video-translation/audio", req.path);
        assertEquals("OAuth " + OAUTH_TOKEN, req.headers.get("Authorization"));
        assertEquals("test-secret-key-67", req.headers.get("Sec-Vtrans-Sk"));
        assertNotNull(req.headers.get("Sec-Vtrans-Token"));
        assertNotNull(req.headers.get("Vtrans-Signature"));

        // Single-part must use outer Tag 6 (audioInfo), NOT Tag 4 (partialAudioInfo)
        ParsedWire wire = ParsedWire.parse(req.body);
        assertEquals(TRANSLATION_ID, wire.getString(1));
        assertEquals(VIDEO_URL, wire.getString(2));
        assertTrue("Outer Tag 6 (audioInfo) must be present", wire.hasField(6));
        assertFalse("Outer Tag 4 (partialAudioInfo) must NOT be present", wire.hasField(4));

        ParsedWire inner = ParsedWire.parse(wire.getBytes(6));
        assertEquals(FILE_ID, inner.getString(1));
        assertArrayEquals(audioBytes, inner.getBytes(2));
    }

    // =============================================================================================
    // 2. Multi-Part Upload: Multiple Chunks, Ordering, and Terminal Metadata
    // =============================================================================================
    @Test
    public void testMultiPartUpload_ThreeChunks_CorrectMetadataAndOrdering() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        // Chunk 0 -> STATUS_WAITING_CHUNKS
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, Collections.singletonList("1")));
        // Chunk 1 -> STATUS_WAITING_CHUNKS
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, Collections.singletonList("2")));
        // Chunk 2 (terminal) -> STATUS_DONE
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        int chunkSize = 100;
        VotAudioUploader uploader = new VotAudioUploader(http, chunkSize);

        // Total 250 bytes: Chunk 0 = 100, Chunk 1 = 100, Chunk 2 = 50
        byte[] audioBytes = new byte[250];
        for (int i = 0; i < audioBytes.length; i++) {
            audioBytes[i] = (byte) (i & 0xFF);
        }
        VotAudioSource source = VotAudioSource.fromBytes(audioBytes);

        VotTranslationAudioResponse resp = uploader.uploadAudio(
                VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);

        assertNotNull(resp);
        assertTrue(resp.isDone());
        assertEquals(3, http.requests.size());

        // Check Chunk 0
        ParsedWire c0 = ParsedWire.parse(http.requests.get(0).body);
        ParsedWire p0 = ParsedWire.parse(c0.getBytes(4));
        assertEquals(0, p0.getInt(2)); // audioPartsLength = 0 for intermediate
        assertEquals(FILE_ID, p0.getString(3));
        assertEquals(1, p0.getInt(4)); // version = 1
        ParsedWire b0 = ParsedWire.parse(p0.getBytes(1));
        assertEquals(0, b0.getInt(1)); // chunkId = 0
        assertEquals(100, b0.getBytes(2).length);
        assertArrayEquals(Arrays.copyOfRange(audioBytes, 0, 100), b0.getBytes(2));

        // Check Chunk 1
        ParsedWire c1 = ParsedWire.parse(http.requests.get(1).body);
        ParsedWire p1 = ParsedWire.parse(c1.getBytes(4));
        assertEquals(0, p1.getInt(2)); // audioPartsLength = 0 for intermediate
        assertEquals(FILE_ID, p1.getString(3));
        ParsedWire b1 = ParsedWire.parse(p1.getBytes(1));
        assertEquals(1, b1.getInt(1)); // chunkId = 1
        assertEquals(100, b1.getBytes(2).length);
        assertArrayEquals(Arrays.copyOfRange(audioBytes, 100, 200), b1.getBytes(2));

        // Check Chunk 2 (terminal)
        ParsedWire c2 = ParsedWire.parse(http.requests.get(2).body);
        ParsedWire p2 = ParsedWire.parse(c2.getBytes(4));
        assertEquals(3, p2.getInt(2)); // audioPartsLength = 3 (totalChunks) for terminal
        assertEquals(FILE_ID, p2.getString(3));
        ParsedWire b2 = ParsedWire.parse(p2.getBytes(1));
        assertEquals(2, b2.getInt(1)); // chunkId = 2
        assertEquals(50, b2.getBytes(2).length);
        assertArrayEquals(Arrays.copyOfRange(audioBytes, 200, 250), b2.getBytes(2));
    }

    // =============================================================================================
    // 3. Exact Threshold Boundary
    // =============================================================================================
    @Test
    public void testExactThresholdBoundary_UploadedAsSinglePart() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        int chunkSize = 256;
        VotAudioUploader uploader = new VotAudioUploader(http, chunkSize);

        byte[] audioBytes = new byte[chunkSize];
        Arrays.fill(audioBytes, (byte) 0xAA);
        VotAudioSource source = VotAudioSource.fromBytes(audioBytes);

        VotTranslationAudioResponse resp = uploader.uploadAudio(
                VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);

        assertTrue(resp.isDone());
        assertEquals(1, http.requests.size());

        ParsedWire wire = ParsedWire.parse(http.requests.get(0).body);
        assertTrue("Exact threshold must be uploaded as single-part (Tag 6)", wire.hasField(6));
        assertFalse(wire.hasField(4));
        ParsedWire inner = ParsedWire.parse(wire.getBytes(6));
        assertEquals(chunkSize, inner.getBytes(2).length);
    }

    // =============================================================================================
    // 4. Threshold Plus One Byte: Transitions to Two Chunks
    // =============================================================================================
    @Test
    public void testThresholdPlusOneByte_TransitionsToTwoChunks() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, null));
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        int chunkSize = 256;
        VotAudioUploader uploader = new VotAudioUploader(http, chunkSize);

        byte[] audioBytes = new byte[chunkSize + 1];
        Arrays.fill(audioBytes, (byte) 0x55);
        VotAudioSource source = VotAudioSource.fromBytes(audioBytes);

        VotTranslationAudioResponse resp = uploader.uploadAudio(
                VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);

        assertTrue(resp.isDone());
        assertEquals(2, http.requests.size());

        // Chunk 0: intermediate, 256 bytes, audioPartsLength = 0
        ParsedWire c0 = ParsedWire.parse(http.requests.get(0).body);
        ParsedWire p0 = ParsedWire.parse(c0.getBytes(4));
        assertEquals(0, p0.getInt(2));
        ParsedWire b0 = ParsedWire.parse(p0.getBytes(1));
        assertEquals(0, b0.getInt(1));
        assertEquals(256, b0.getBytes(2).length);

        // Chunk 1: terminal, 1 byte, audioPartsLength = 2
        ParsedWire c1 = ParsedWire.parse(http.requests.get(1).body);
        ParsedWire p1 = ParsedWire.parse(c1.getBytes(4));
        assertEquals(2, p1.getInt(2));
        ParsedWire b1 = ParsedWire.parse(p1.getBytes(1));
        assertEquals(1, b1.getInt(1));
        assertEquals(1, b1.getBytes(2).length);
    }

    // =============================================================================================
    // 5. Unknown Content Length (-1): Streaming Source Without Data Loss
    // =============================================================================================
    @Test
    public void testUnknownContentLength_ProgressiveStreamingWithoutDataLoss() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, null));
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        int chunkSize = 50;
        VotAudioUploader uploader = new VotAudioUploader(http, chunkSize);

        byte[] audioBytes = new byte[80];
        for (int i = 0; i < audioBytes.length; i++) {
            audioBytes[i] = (byte) i;
        }

        // Unknown content length (-1)
        VotAudioSource source = VotAudioSource.fromInputStream(new ByteArrayInputStream(audioBytes), -1);

        VotTranslationAudioResponse resp = uploader.uploadAudio(
                VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);

        assertTrue(resp.isDone());
        assertEquals(2, http.requests.size());

        // Verify Chunk 0 has 50 bytes and Chunk 1 has 30 bytes
        ParsedWire c0 = ParsedWire.parse(http.requests.get(0).body);
        ParsedWire b0 = ParsedWire.parse(ParsedWire.parse(c0.getBytes(4)).getBytes(1));
        assertEquals(50, b0.getBytes(2).length);

        ParsedWire c1 = ParsedWire.parse(http.requests.get(1).body);
        ParsedWire b1 = ParsedWire.parse(ParsedWire.parse(c1.getBytes(4)).getBytes(1));
        assertEquals(30, b1.getBytes(2).length);

        // Verify total recombined bytes match original
        byte[] recombined = new byte[80];
        System.arraycopy(b0.getBytes(2), 0, recombined, 0, 50);
        System.arraycopy(b1.getBytes(2), 0, recombined, 50, 30);
        assertArrayEquals(audioBytes, recombined);
    }

    // =============================================================================================
    // 6. Empty Audio Source: Rejection
    // =============================================================================================
    @Test
    public void testEmptyAudioSource_RejectedWithIllegalArgumentException() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        VotAudioUploader uploader = new VotAudioUploader(http, 1024);
        VotAudioSource emptySource = VotAudioSource.fromBytes(new byte[0]);

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, emptySource);
            fail("Empty audio source must throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
        assertEquals(0, http.requests.size());
    }

    // =============================================================================================
    // 7. Early EOF / Truncated Source
    // =============================================================================================
    @Test
    public void testEarlyEOF_DeclaredLengthMismatch_ThrowsVotAudioSourceException() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        VotAudioUploader uploader = new VotAudioUploader(http, 1024);

        // Declared 500 bytes, but byte array only has 100 bytes
        VotAudioSource truncated = VotAudioSource.fromBytes(new byte[100], 500);

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, truncated);
            fail("Premature EOF must throw VotAudioSourceException");
        } catch (VotAudioSourceException expected) {
            assertTrue(expected.getMessage().contains("Premature EOF"));
        }
    }

    // =============================================================================================
    // 8. Interrupted Stream During Read
    // =============================================================================================
    @Test
    public void testInterruptedStreamDuringRead_ThrowsVotAudioSourceException() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        VotAudioUploader uploader = new VotAudioUploader(http, 100);

        final AtomicBoolean closed = new AtomicBoolean(false);
        VotAudioSource failingSource = new VotAudioSource() {
            private int mCount = 0;

            @Override
            public void open() {
            }

            @Override
            public int read(byte[] buffer, int off, int len) throws IOException {
                mCount++;
                if (mCount > 1) {
                    throw new IOException("Connection dropped while reading YouTube audio stream");
                }
                Arrays.fill(buffer, off, off + len, (byte) 1);
                return len;
            }

            @Override
            public long getContentLength() {
                return 500;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, failingSource);
            fail("Source I/O error must throw VotAudioSourceException");
        } catch (VotAudioSourceException expected) {
            assertTrue(expected.getMessage().contains("Failed reading from audio source"));
        }
        assertTrue("Source must be closed after failure", closed.get());
    }

    // =============================================================================================
    // 9. Cancellation During Read
    // =============================================================================================
    @Test
    public void testCancellationDuringRead_AbortsAndClosesSource() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        final VotAudioUploader uploader = new VotAudioUploader(http, 100);

        final AtomicBoolean sourceClosed = new AtomicBoolean(false);
        VotAudioSource cancellableSource = new VotAudioSource() {
            @Override
            public void open() {
            }

            @Override
            public int read(byte[] buffer, int off, int len) throws IOException {
                uploader.cancel();
                throw new IOException("Read interrupted by cancel");
            }

            @Override
            public long getContentLength() {
                return 1000;
            }

            @Override
            public void close() {
                sourceClosed.set(true);
            }
        };

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, cancellableSource);
            fail("Cancellation must throw VotCancellationException");
        } catch (VotCancellationException expected) {
            assertTrue(uploader.isCancelled());
        }
        assertTrue("Source must be closed on cancellation", sourceClosed.get());
        assertEquals(0, http.requests.size());
    }

    // =============================================================================================
    // 10. Cancellation During Upload: In-Flight Request Aborted
    // =============================================================================================
    @Test
    public void testCancellationDuringUpload_AbortsInFlightRequest() throws Exception {
        final MockUploadHttp http = new MockUploadHttp();
        final VotAudioUploader uploader = new VotAudioUploader(http, 100);

        final AtomicBoolean callCancelled = new AtomicBoolean(false);
        http.enqueueCustomHandler(new MockUploadHttp.Handler() {
            @Override
            public byte[] handle(MockUploadHttp.Record req) throws IOException {
                uploader.cancel();
                if (req.callHolder != null) {
                    callCancelled.set(true);
                }
                throw new IOException("Canceled");
            }
        });

        VotAudioSource source = VotAudioSource.fromBytes(new byte[50]);

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);
            fail("Cancellation during upload must throw VotCancellationException");
        } catch (VotCancellationException expected) {
            assertTrue(uploader.isCancelled());
        }
        assertTrue(callCancelled.get());
    }

    // =============================================================================================
    // 11. No Success After Cancellation
    // =============================================================================================
    @Test
    public void testNoSuccessDeliveredAfterCancellation() {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        VotAudioUploader uploader = new VotAudioUploader(http, 100);
        uploader.cancel();

        VotAudioSource source = VotAudioSource.fromBytes(new byte[50]);
        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);
            fail("Must not deliver success after cancellation");
        } catch (Exception expected) {
            assertTrue(expected instanceof VotCancellationException);
        }
    }

    // =============================================================================================
    // 12. HTTP 400 Bad Request: Non-Transient, No Retry
    // =============================================================================================
    @Test
    public void testHttp400_NonTransient_FailsImmediatelyWithoutRetry() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueException(new VotHttpException(400, "Bad Request"));

        VotAudioUploader uploader = new VotAudioUploader(http, 100);
        VotAudioSource source = VotAudioSource.fromBytes(new byte[50]);

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);
            fail("HTTP 400 must throw VotHttpException");
        } catch (VotHttpException e) {
            assertEquals(400, e.getStatusCode());
        }
        assertEquals("HTTP 400 must NOT be retried", 1, http.requests.size());
    }

    // =============================================================================================
    // 13. Transient HTTP Error: Retried and Succeeds
    // =============================================================================================
    @Test
    public void testTransientError_RetriesAndRecovers() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        // First attempt fails with 502 Bad Gateway
        http.enqueueException(new VotHttpException(502, "Bad Gateway"));
        // Second attempt succeeds
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        VotAudioUploader uploader = new VotAudioUploader(http, 100);
        VotAudioSource source = VotAudioSource.fromBytes(new byte[50]);

        VotTranslationAudioResponse resp = uploader.uploadAudio(
                VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);

        assertTrue(resp.isDone());
        assertEquals("Must retry after transient 502", 2, http.requests.size());
    }

    // =============================================================================================
    // 14. Max Retries Exceeded: No Infinite Loop
    // =============================================================================================
    @Test
    public void testTransientError_ExceedsMaxRetries_Fails() {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueException(new SocketTimeoutException("timeout"));
        http.enqueueException(new SocketTimeoutException("timeout"));
        http.enqueueException(new SocketTimeoutException("timeout"));

        VotAudioUploader uploader = new VotAudioUploader(http, 100);
        VotAudioSource source = VotAudioSource.fromBytes(new byte[50]);

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);
            fail("Exceeding max retries must throw IOException");
        } catch (Exception e) {
            assertTrue(e instanceof IOException);
            assertTrue(e.getMessage().contains("after 3 attempts"));
        }
        assertEquals(3, http.requests.size());
    }

    // =============================================================================================
    // 15. Premature STATUS_DONE on Intermediate Chunk: Protocol Violation
    // =============================================================================================
    @Test
    public void testPrematureStatusDone_OnIntermediateChunk_ThrowsVotException() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        // Chunk 0 returns STATUS_DONE prematurely while chunk 1 is still pending
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        int chunkSize = 50;
        VotAudioUploader uploader = new VotAudioUploader(http, chunkSize);
        VotAudioSource source = VotAudioSource.fromBytes(new byte[100]); // 2 chunks

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);
            fail("Premature STATUS_DONE must throw VotException");
        } catch (VotException expected) {
            assertTrue(expected.getMessage().contains("Premature STATUS_DONE"));
        }
        assertEquals(1, http.requests.size());
    }

    // =============================================================================================
    // 16. Server Still Requests Remaining Chunks After Terminal Chunk
    // =============================================================================================
    @Test
    public void testTerminalChunk_ServerRequestsRemainingChunks_ThrowsVotException() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        // Server still in STATUS_WAITING_CHUNKS with missing chunks
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS,
                Arrays.asList("chunk-missing-1", "chunk-missing-2")));

        VotAudioUploader uploader = new VotAudioUploader(http, 100);
        VotAudioSource source = VotAudioSource.fromBytes(new byte[50]);

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);
            fail("Incomplete upload must throw VotException");
        } catch (VotException expected) {
            assertTrue(expected.getMessage().contains("server still waiting for chunks"));
        }
    }

    // =============================================================================================
    // 17. Malformed / Empty Protobuf Response: Rejected
    // =============================================================================================
    @Test
    public void testMalformedResponse_ThrowsVotException() {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(new byte[0]); // empty response

        VotAudioUploader uploader = new VotAudioUploader(http, 100);
        VotAudioSource source = VotAudioSource.fromBytes(new byte[50]);

        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);
            fail("Empty response must throw VotException");
        } catch (Exception expected) {
            assertTrue(expected instanceof VotException);
        }
    }

    // =============================================================================================
    // 18. Session Continuity: Same Session Identity Across All Chunks
    // =============================================================================================
    @Test
    public void testSessionContinuity_SameKeyAndUuidAcrossAllChunks() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, null));
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, null));
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        VotAudioUploader uploader = new VotAudioUploader(http, 50);
        VotSession session = createSession();
        VotAudioSource source = VotAudioSource.fromBytes(new byte[120]); // 3 chunks

        uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, session, OAUTH_TOKEN, source);

        assertEquals(3, http.requests.size());
        for (int i = 0; i < 3; i++) {
            MockUploadHttp.Record r = http.requests.get(i);
            assertEquals("Sec-Vtrans-Sk must match session secretKey across all chunks",
                    session.secretKey, r.headers.get("Sec-Vtrans-Sk"));
            assertTrue("Sec-Vtrans-Token must contain session UUID",
                    r.headers.get("Sec-Vtrans-Token").contains(session.uuid));
            assertEquals("OAuth header must remain constant",
                    "OAuth " + OAUTH_TOKEN, r.headers.get("Authorization"));
        }
    }

    // =============================================================================================
    // Mock HTTP Infrastructure for Unit Testing
    // =============================================================================================
    static class MockUploadHttp extends VotHttp {
        interface Handler {
            byte[] handle(Record req) throws IOException;
        }

        static class Record {
            final String path;
            final byte[] body;
            final Map<String, String> headers;
            final VotHttp.CallHolder callHolder;

            Record(String path, byte[] body, Map<String, String> headers, VotHttp.CallHolder callHolder) {
                this.path = path;
                this.body = body != null ? body.clone() : null;
                this.headers = headers != null ? new HashMap<>(headers) : Collections.emptyMap();
                this.callHolder = callHolder;
            }
        }

        final List<Record> requests = new ArrayList<>();
        private final List<Object> responses = new ArrayList<>();

        void enqueueResponse(byte[] body) {
            responses.add(body);
        }

        void enqueueException(IOException e) {
            responses.add(e);
        }

        void enqueueCustomHandler(Handler h) {
            responses.add(h);
        }

        @Override
        public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers, VotHttp.CallHolder callHolder) throws IOException {
            Record rec = new Record(path, body, headers, callHolder);
            requests.add(rec);

            if (responses.isEmpty()) {
                throw new IOException("MockUploadHttp: no response enqueued");
            }
            Object next = responses.remove(0);
            if (next instanceof IOException) {
                throw (IOException) next;
            }
            if (next instanceof Handler) {
                return ((Handler) next).handle(rec);
            }
            return (byte[]) next;
        }
    }

    // =============================================================================================
    // Lightweight Wire Parser for Testing Protobuf Assertions
    // =============================================================================================
    static class ParsedWire {
        static class Field {
            final int tag;
            final int wireType;
            final long varint;
            final byte[] bytes;

            Field(int tag, int wireType, long varint, byte[] bytes) {
                this.tag = tag;
                this.wireType = wireType;
                this.varint = varint;
                this.bytes = bytes;
            }
        }

        private final List<Field> fields;

        private ParsedWire(List<Field> fields) {
            this.fields = fields;
        }

        static ParsedWire parse(byte[] data) {
            List<Field> list = new ArrayList<>();
            if (data == null) return new ParsedWire(list);
            int pos = 0;
            while (pos < data.length) {
                int[] tagInfo = readVarint(data, pos);
                if (tagInfo[0] <= 0) break;
                pos = tagInfo[1];
                int tag = tagInfo[0] >>> 3;
                int wireType = tagInfo[0] & 7;

                if (wireType == 0) { // varint
                    int[] val = readVarint(data, pos);
                    pos = val[1];
                    list.add(new Field(tag, wireType, val[0], null));
                } else if (wireType == 2) { // length-delimited
                    int[] len = readVarint(data, pos);
                    pos = len[1];
                    int length = len[0];
                    byte[] b = new byte[length];
                    System.arraycopy(data, pos, b, 0, length);
                    pos += length;
                    list.add(new Field(tag, wireType, 0, b));
                } else if (wireType == 5) { // 32-bit
                    pos += 4;
                } else if (wireType == 1) { // 64-bit
                    pos += 8;
                } else {
                    break;
                }
            }
            return new ParsedWire(list);
        }

        boolean hasField(int tag) {
            for (Field f : fields) {
                if (f.tag == tag) return true;
            }
            return false;
        }

        int getInt(int tag) {
            for (Field f : fields) {
                if (f.tag == tag) return (int) f.varint;
            }
            return 0;
        }

        byte[] getBytes(int tag) {
            for (Field f : fields) {
                if (f.tag == tag) return f.bytes;
            }
            return null;
        }

        String getString(int tag) {
            byte[] b = getBytes(tag);
            return b != null ? new String(b, StandardCharsets.UTF_8) : null;
        }

        private static int[] readVarint(byte[] data, int pos) {
            int result = 0;
            int shift = 0;
            while (pos < data.length) {
                int b = data[pos++] & 0xFF;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return new int[]{result, pos};
                }
                shift += 7;
                if (shift > 35) return new int[]{-1, pos};
            }
            return new int[]{-1, pos};
        }
    }
}

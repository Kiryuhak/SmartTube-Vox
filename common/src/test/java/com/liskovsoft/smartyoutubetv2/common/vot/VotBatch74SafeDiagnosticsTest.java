package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SmartTube VOX — Batch #74
 * Tests for safe upload diagnostics, metric counters, byte tracking,
 * and chunk accounting in VotAudioUploader and VotYouTubeAudioSource.
 */
public class VotBatch74SafeDiagnosticsTest {
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=TEST_AUDIO_74";
    private static final String TRANSLATION_ID = "tr-test-74";
    private static final String FILE_ID = "smarttube-android-test74";
    private static final String OAUTH_TOKEN = "test-oauth-token-74";

    private static VotSession createSession() {
        VotSession s = new VotSession();
        s.secretKey = "test-secret-key-74";
        s.uuid = "test-session-uuid-74";
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
    // 1. Single-Part Upload: Diagnostic Counters Verification
    // =============================================================================================
    @Test
    public void testSinglePartUpload_TracksCountersAccurately() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        VotAudioUploader uploader = new VotAudioUploader(http, 1024);
        byte[] audioBytes = "Hello SmartTube VOX Diagnostic Tracking".getBytes(StandardCharsets.UTF_8);
        VotAudioSource source = VotAudioSource.fromBytes(audioBytes);

        assertEquals(0, uploader.getTotalBytesRead());
        assertEquals(0, uploader.getTotalBytesUploaded());
        assertEquals(0, uploader.getTotalChunksUploaded());
        assertEquals(-1, uploader.getFinalStatus());
        assertEquals(-1, uploader.getRemainingChunksCount());

        VotTranslationAudioResponse resp = uploader.uploadAudio(
                VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), OAUTH_TOKEN, source);

        assertNotNull(resp);
        assertTrue(resp.isDone());
        assertEquals(audioBytes.length, uploader.getTotalBytesRead());
        assertEquals(audioBytes.length, uploader.getTotalBytesUploaded());
        assertEquals(1, uploader.getTotalChunksUploaded());
        assertEquals(VotTranslationAudioResponse.STATUS_DONE, uploader.getFinalStatus());
        assertEquals(0, uploader.getRemainingChunksCount());
    }

    // =============================================================================================
    // 2. Multi-Part Upload: Diagnostic Counters Across Multiple Chunks
    // =============================================================================================
    @Test
    public void testMultiPartUpload_TracksCountersAcrossChunks() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        // Chunk 0 (intermediate)
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, Arrays.asList("1", "2")));
        // Chunk 1 (intermediate)
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, Collections.singletonList("2")));
        // Chunk 2 (terminal)
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        int chunkSize = 100;
        int totalAudioLength = 250;
        byte[] fullAudio = new byte[totalAudioLength];
        for (int i = 0; i < totalAudioLength; i++) {
            fullAudio[i] = (byte) (i & 0xFF);
        }

        VotAudioUploader uploader = new VotAudioUploader(http, chunkSize);
        VotAudioSource source = VotAudioSource.fromBytes(fullAudio);

        VotTranslationAudioResponse resp = uploader.uploadAudio(
                VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, source);

        assertNotNull(resp);
        assertTrue(resp.isDone());
        assertEquals(3, http.requests.size());
        assertEquals(totalAudioLength, uploader.getTotalBytesRead());
        assertEquals(totalAudioLength, uploader.getTotalBytesUploaded());
        assertEquals(3, uploader.getTotalChunksUploaded());
        assertEquals(VotTranslationAudioResponse.STATUS_DONE, uploader.getFinalStatus());
        assertEquals(0, uploader.getRemainingChunksCount());
    }

    // =============================================================================================
    // 3. Counter Reset on Successive Uploads
    // =============================================================================================
    @Test
    public void testCountersResetOnSuccessiveUploads() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        VotAudioUploader uploader = new VotAudioUploader(http, 512);

        // Upload #1: 40 bytes
        byte[] bytes1 = new byte[40];
        uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, VotAudioSource.fromBytes(bytes1));
        assertEquals(40, uploader.getTotalBytesRead());
        assertEquals(40, uploader.getTotalBytesUploaded());
        assertEquals(1, uploader.getTotalChunksUploaded());

        // Upload #2: 95 bytes
        byte[] bytes2 = new byte[95];
        uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, VotAudioSource.fromBytes(bytes2));
        assertEquals(95, uploader.getTotalBytesRead());
        assertEquals(95, uploader.getTotalBytesUploaded());
        assertEquals(1, uploader.getTotalChunksUploaded());
    }

    // =============================================================================================
    // 4. Incomplete Multi-Part Final Response Captured in Counters
    // =============================================================================================
    @Test
    public void testFinalResponseIncomplete_ReflectedInCounters() throws Exception {
        MockUploadHttp http = new MockUploadHttp();
        // Chunk 0 (intermediate)
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, Collections.singletonList("1")));
        // Chunk 1 (terminal, but server reports chunk 0 still missing!)
        http.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, Collections.singletonList("0")));

        int chunkSize = 50;
        byte[] audio = new byte[80];

        VotAudioUploader uploader = new VotAudioUploader(http, chunkSize);
        try {
            uploader.uploadAudio(VIDEO_URL, TRANSLATION_ID, FILE_ID, createSession(), null, VotAudioSource.fromBytes(audio));
            fail("Expected VotException due to remainingChunks");
        } catch (VotException e) {
            assertTrue(e.getMessage().contains("still waiting for chunks"));
        }

        assertEquals(80, uploader.getTotalBytesRead());
        assertEquals(80, uploader.getTotalBytesUploaded());
        assertEquals(2, uploader.getTotalChunksUploaded());
        assertEquals(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, uploader.getFinalStatus());
        assertEquals(1, uploader.getRemainingChunksCount());
    }

    // =============================================================================================
    // Mock HTTP Infrastructure
    // =============================================================================================
    static class MockUploadHttp extends VotHttp {
        static class Record {
            final String path;
            final byte[] body;
            final Map<String, String> headers;

            Record(String path, byte[] body, Map<String, String> headers) {
                this.path = path;
                this.body = body != null ? body.clone() : null;
                this.headers = headers != null ? new HashMap<>(headers) : Collections.emptyMap();
            }
        }

        final List<Record> requests = new ArrayList<>();
        private final List<byte[]> responses = new ArrayList<>();

        void enqueueResponse(byte[] body) {
            responses.add(body);
        }

        @Override
        public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers, VotHttp.CallHolder callHolder) throws IOException {
            requests.add(new Record(path, body, headers));
            if (responses.isEmpty()) {
                throw new IOException("MockUploadHttp: no response enqueued");
            }
            return responses.remove(0);
        }
    }
}

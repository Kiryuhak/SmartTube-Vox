package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SmartTube VOX — Batch #73
 * Comprehensive offline regression & synthetic unit tests for YouTube audio source EOF fix.
 *
 * Verifies that:
 * 1. An underlying stream that delivers exact bytes (e.g. 1,231,355 bytes) and resets on further reads
 *    does NOT fail with SocketException / Connection reset.
 * 2. VotYouTubeAudioSource returns -1 (EOF) immediately once mBytesRead reaches mActualContentLength.
 * 3. read(buffer, off, len) bounds len to remaining expected bytes.
 * 4. Premature stream termination throws controlled VotAudioSourceException.
 * 5. Unknown content length stream handles normal EOF without throwing premature EOF exception.
 * 6. HTTP Content-Length takes precedence over format metadata clen.
 * 7. Declared format clen is used when HTTP Content-Length is absent.
 * 8. End-to-end VotAudioUploader succeeds with single-part upload without hanging or resetting.
 * 9. End-to-end VotAudioUploader succeeds with multi-part upload where tail chunk triggers EOF.
 * 10. Cancellation during reading properly throws VotCancellationException.
 */
public class VotBatch73AudioSourceEofTest {

    /**
     * InputStream that delivers exactly totalBytes of data, then throws SocketException("Connection reset")
     * if read() is invoked again instead of returning EOF cleanly (reproducing Google Video CDN behavior).
     */
    private static class ResetOnExtraReadInputStream extends InputStream {
        private final byte[] mData;
        private int mPos = 0;
        private final AtomicInteger mReadAfterEofCount = new AtomicInteger(0);

        public ResetOnExtraReadInputStream(int totalBytes) {
            mData = new byte[totalBytes];
            for (int i = 0; i < totalBytes; i++) {
                mData[i] = (byte) (i % 251);
            }
        }

        @Override
        public int read() throws IOException {
            if (mPos >= mData.length) {
                mReadAfterEofCount.incrementAndGet();
                throw new SocketException("Connection reset");
            }
            return mData[mPos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (mPos >= mData.length) {
                mReadAfterEofCount.incrementAndGet();
                throw new SocketException("Connection reset");
            }
            int available = mData.length - mPos;
            int toRead = Math.min(len, available);
            System.arraycopy(mData, mPos, b, off, toRead);
            mPos += toRead;
            return toRead;
        }

        public int getReadAfterEofCount() {
            return mReadAfterEofCount.get();
        }
    }

    private static OkHttpClient createMockClientWithStream(final int statusCode, final String contentType,
                                                           final InputStream stream, final long contentLength) {
        return new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request request = chain.request();
                        ResponseBody responseBody = new ResponseBody() {
                            @Override
                            public MediaType contentType() {
                                return MediaType.parse(contentType);
                            }

                            @Override
                            public long contentLength() {
                                return contentLength;
                            }

                            @Override
                            public BufferedSource source() {
                                Source source = Okio.source(stream);
                                return Okio.buffer(source);
                            }
                        };

                        return new Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(statusCode)
                                .message("OK")
                                .body(responseBody)
                                .build();
                    }
                })
                .build();
    }

    private static class MockUploadHttp extends VotHttp {
        private final List<byte[]> mEnqueuedResponses = new ArrayList<>();
        private final List<byte[]> mCapturedBodies = new ArrayList<>();

        public MockUploadHttp() {
            super(new OkHttpClient());
        }

        public void enqueueResponse(byte[] responseBody) {
            mEnqueuedResponses.add(responseBody);
        }

        public List<byte[]> getCapturedBodies() {
            return mCapturedBodies;
        }

        @Override
        public byte[] putProtobuf(String path, byte[] body, java.util.Map<String, String> headers, CallHolder callHolder) throws IOException {
            mCapturedBodies.add(body);
            if (mEnqueuedResponses.isEmpty()) {
                throw new IOException("No mock response enqueued for PUT " + path);
            }
            return mEnqueuedResponses.remove(0);
        }
    }

    private static VotSession createSession() {
        VotSession s = new VotSession();
        s.secretKey = "test-secret-key-73";
        s.uuid = "test-session-uuid-73";
        s.expiresSec = 3600;
        s.createdAtMs = System.currentTimeMillis();
        return s;
    }

    private static byte[] makeDoneResponse() {
        VotWireWriter w = new VotWireWriter();
        w.writeInt32(1, VotTranslationAudioResponse.STATUS_DONE);
        return w.toByteArray();
    }

    private static byte[] makeWaitingChunksResponse(List<String> chunks) {
        VotWireWriter w = new VotWireWriter();
        w.writeInt32(1, VotTranslationAudioResponse.STATUS_WAITING_CHUNKS);
        for (String c : chunks) {
            w.writeString(2, c);
        }
        return w.toByteArray();
    }

    // =============================================================================================
    // Test 1: Reproduction Gate - Exact 1,231,355 bytes with stream reset after completion
    // =============================================================================================

    @Test
    public void testReproduction_StreamResetAfterAllBytesRead() throws Exception {
        final int targetBytes = 1231355; // Exact size from Batch #72 video dQw4w9WgXcQ
        ResetOnExtraReadInputStream stream = new ResetOnExtraReadInputStream(targetBytes);
        OkHttpClient client = createMockClientWithStream(200, "audio/webm", stream, targetBytes);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_72_repro", targetBytes, client);
        source.open();

        MockUploadHttp http = new MockUploadHttp();
        http.enqueueResponse(makeDoneResponse());

        VotAudioUploader uploader = new VotAudioUploader(http, 5295308); // 5.29MB chunk size
        VotTranslationAudioResponse response = uploader.uploadAudio(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "tr-repro-73",
                "file-repro-73",
                createSession(),
                "dummy-oauth",
                source
        );

        assertNotNull(response);
        assertEquals(VotTranslationAudioResponse.STATUS_DONE, response.status);
        assertEquals(targetBytes, source.getBytesRead());
        assertEquals(0, stream.getReadAfterEofCount());
    }

    // =============================================================================================
    // Test 2: Direct Read - Returns -1 immediately when content length reached
    // =============================================================================================

    @Test
    public void testDirectRead_ReturnsMinusOneImmediatelyWhenContentLengthReached() throws Exception {
        final int targetBytes = 50000;
        ResetOnExtraReadInputStream stream = new ResetOnExtraReadInputStream(targetBytes);
        OkHttpClient client = createMockClientWithStream(200, "audio/webm", stream, targetBytes);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_exact", targetBytes, client);
        source.open();

        byte[] buf = new byte[8192];
        int totalRead = 0;
        int r;
        while (totalRead < targetBytes && (r = source.read(buf, 0, Math.min(buf.length, targetBytes - totalRead))) != -1) {
            totalRead += r;
        }
        assertEquals(targetBytes, totalRead);
        assertEquals(targetBytes, source.getBytesRead());

        // Subsequent read MUST return -1 without touching underlying stream
        int eof = source.read(buf, 0, buf.length);
        assertEquals(-1, eof);
        assertEquals(0, stream.getReadAfterEofCount());

        source.close();
    }

    // =============================================================================================
    // Test 3: Direct Read - Bytes to read is bounded to remaining content length
    // =============================================================================================

    @Test
    public void testDirectRead_BytesToReadBoundedToRemaining() throws Exception {
        final int targetBytes = 1500;
        ResetOnExtraReadInputStream stream = new ResetOnExtraReadInputStream(targetBytes);
        OkHttpClient client = createMockClientWithStream(200, "audio/webm", stream, targetBytes);
        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_bounded", targetBytes, client);
        source.open();

        // Buffer much larger than remaining content length
        byte[] largeBuf = new byte[100000];
        int totalRead = 0;
        int r;
        while ((r = source.read(largeBuf, 0, largeBuf.length)) != -1) {
            totalRead += r;
        }
        assertEquals(targetBytes, totalRead);
        assertEquals(targetBytes, source.getBytesRead());
        assertEquals(0, stream.getReadAfterEofCount());

        source.close();
    }

    // =============================================================================================
    // Test 4: Premature EOF - Throws controlled VotAudioSourceException
    // =============================================================================================

    @Test
    public void testPrematureEof_UnderlyingStreamEndsEarly_ThrowsControlledVotAudioSourceException() throws Exception {
        final int expectedBytes = 100000;
        final int actualBytesDelivered = 40000;
        byte[] shortData = new byte[actualBytesDelivered];

        ByteArrayInputStream bais = new ByteArrayInputStream(shortData);
        OkHttpClient client = createMockClientWithStream(200, "audio/webm", bais, expectedBytes);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_premature", expectedBytes, client);
        source.open();

        byte[] buf = new byte[8192];
        int totalRead = 0;
        try {
            int r;
            while ((r = source.read(buf, 0, buf.length)) != -1) {
                totalRead += r;
            }
            fail("Expected VotAudioSourceException on premature EOF");
        } catch (VotAudioSourceException e) {
            assertEquals(actualBytesDelivered, totalRead);
            assertTrue("Exception should mention Premature EOF: " + e.getMessage(),
                    e.getMessage().contains("Premature EOF"));
            assertTrue(e.getMessage().contains("expected 100000"));
            assertTrue(e.getMessage().contains("read 40000"));
        }

        source.close();
    }

    // =============================================================================================
    // Test 5: Unknown Content-Length (-1) - Handles normal EOF without error
    // =============================================================================================

    @Test
    public void testUnknownContentLength_NormalEofReturnsMinusOne() throws Exception {
        byte[] payload = "Dynamic chunked payload without Content-Length header".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(payload);
        OkHttpClient client = createMockClientWithStream(200, "audio/webm", bais, -1);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_unknown_len", -1, client);
        source.open();
        assertEquals(-1, source.getContentLength());

        byte[] buf = new byte[1024];
        int r = source.read(buf, 0, buf.length);
        assertEquals(payload.length, r);

        int eof = source.read(buf, 0, buf.length);
        assertEquals(-1, eof);

        source.close();
    }

    // =============================================================================================
    // Test 6: Content-Length Precedence - HTTP header overrides format metadata clen
    // =============================================================================================

    @Test
    public void testContentLengthPrecedence_HttpHeaderOverridesDeclaredFormatLength() throws Exception {
        byte[] data = new byte[500];
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        // HTTP header declares 500, but format metadata declared 1000
        OkHttpClient client = createMockClientWithStream(200, "audio/webm", bais, 500);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_precedence", 1000, client);
        assertEquals(1000, source.getContentLength()); // Before open

        source.open();
        assertEquals(500, source.getContentLength()); // After open, HTTP Content-Length wins

        source.close();
    }

    // =============================================================================================
    // Test 7: Content-Length Fallback - Declared clen used when HTTP header is absent
    // =============================================================================================

    @Test
    public void testContentLengthFallback_DeclaredFormatLengthUsedWhenHttpHeaderAbsent() throws Exception {
        byte[] data = new byte[750];
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        // HTTP header has -1, format metadata declared 750
        OkHttpClient client = createMockClientWithStream(200, "audio/webm", bais, -1);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_fallback", 750, client);
        source.open();
        assertEquals(750, source.getContentLength());

        source.close();
    }

    // =============================================================================================
    // Test 8: End-to-End Single-Part Upload with exact 1,231,355 bytes
    // =============================================================================================

    @Test
    public void testUploaderIntegration_Exact1231355BytesSinglePartSuccess() throws Exception {
        final int targetBytes = 1231355;
        ResetOnExtraReadInputStream stream = new ResetOnExtraReadInputStream(targetBytes);
        OkHttpClient ytClient = createMockClientWithStream(200, "audio/webm", stream, targetBytes);

        VotYouTubeAudioSource ytSource = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_full_1231355", targetBytes, ytClient);

        MockUploadHttp yandexHttp = new MockUploadHttp();
        yandexHttp.enqueueResponse(makeDoneResponse());

        VotAudioUploader uploader = new VotAudioUploader(yandexHttp, 5295308);
        VotTranslationAudioResponse resp = uploader.uploadAudio(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "tr-single-73",
                "file-single-73",
                createSession(),
                "token-73",
                ytSource
        );

        assertNotNull(resp);
        assertEquals(VotTranslationAudioResponse.STATUS_DONE, resp.status);
        assertEquals(1, yandexHttp.getCapturedBodies().size());
        assertEquals(0, stream.getReadAfterEofCount());
    }

    // =============================================================================================
    // Test 9: End-to-End Multi-Part Upload with Tail Chunk EOF
    // =============================================================================================

    @Test
    public void testUploaderIntegration_MultiPartWithTailChunkEof() throws Exception {
        // Total bytes: 6,000,000. Chunk size: 5,295,308.
        // Chunk 0: 5,295,308 bytes.
        // Chunk 1: 704,692 bytes. Tail chunk ends at 6,000,000.
        final int totalBytes = 6000000;
        final int chunkSize = 5295308;
        ResetOnExtraReadInputStream stream = new ResetOnExtraReadInputStream(totalBytes);
        OkHttpClient ytClient = createMockClientWithStream(200, "audio/webm", stream, totalBytes);

        VotYouTubeAudioSource ytSource = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_multipart", totalBytes, ytClient);

        MockUploadHttp yandexHttp = new MockUploadHttp();
        // Intermediate chunk response
        yandexHttp.enqueueResponse(makeWaitingChunksResponse(Collections.singletonList("1")));
        // Final chunk response
        yandexHttp.enqueueResponse(makeDoneResponse());

        VotAudioUploader uploader = new VotAudioUploader(yandexHttp, chunkSize);
        VotTranslationAudioResponse resp = uploader.uploadAudio(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ_multi",
                "tr-multi-73",
                "file-multi-73",
                createSession(),
                "token-multi-73",
                ytSource
        );

        assertNotNull(resp);
        assertEquals(VotTranslationAudioResponse.STATUS_DONE, resp.status);
        assertEquals(2, yandexHttp.getCapturedBodies().size());
        assertEquals(0, stream.getReadAfterEofCount());
    }

    // =============================================================================================
    // Test 10: Cancellation during read properly throws VotCancellationException
    // =============================================================================================

    @Test
    public void testCancellation_DuringRead_ThrowsCancellationException() throws Exception {
        byte[] data = new byte[1000];
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        OkHttpClient client = createMockClientWithStream(200, "audio/webm", bais, 1000);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_cancel", 1000, client);
        source.open();
        source.close();

        byte[] buf = new byte[100];
        try {
            source.read(buf, 0, 100);
            fail("Expected VotCancellationException after close");
        } catch (VotCancellationException expected) {
            // Expected
        }
    }
}

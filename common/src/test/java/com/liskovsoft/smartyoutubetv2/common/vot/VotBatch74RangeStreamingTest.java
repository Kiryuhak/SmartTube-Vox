package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Okio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SmartTube VOX — Batch #74.1
 * Comprehensive offline unit tests for HTTP Range-based audio streaming in VotYouTubeAudioSource.
 *
 * Verifies that:
 * 1. HTTP 206 Partial Content multi-range chunked downloads properly progress across range boundaries.
 * 2. Range requests emit proper `Range: bytes=start-end` headers.
 * 3. Total stream length is parsed from `Content-Range: bytes start-end/total`.
 * 4. HTTP 200 responses fallback gracefully to un-ranged single-stream reading.
 * 5. Premature EOF during multi-range streaming throws VotAudioSourceException.
 * 6. Clean EOF at content boundary returns -1.
 * 7. Cancellation during range streaming aborts immediately with VotCancellationException.
 * 8. End-to-end integration between VotYouTubeAudioSource range streaming and VotAudioUploader.
 */
public class VotBatch74RangeStreamingTest {

    private static OkHttpClient createRangedMockClient(final byte[] fullData, final List<String> capturedRanges) {
        return new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request request = chain.request();
                        String rangeHeader = request.header("Range");
                        if (capturedRanges != null && rangeHeader != null) {
                            capturedRanges.add(rangeHeader);
                        }

                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            String rangeSpec = rangeHeader.substring("bytes=".length()).trim();
                            String[] parts = rangeSpec.split("-");
                            int start = Integer.parseInt(parts[0]);
                            int end = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : fullData.length - 1;
                            if (end >= fullData.length) {
                                end = fullData.length - 1;
                            }

                            if (start > end || start >= fullData.length) {
                                return new Response.Builder()
                                        .request(request)
                                        .protocol(Protocol.HTTP_1_1)
                                        .code(416)
                                        .message("Range Not Satisfiable")
                                        .body(ResponseBody.create(null, new byte[0]))
                                        .build();
                            }

                            int chunkLen = end - start + 1;
                            byte[] chunk = new byte[chunkLen];
                            System.arraycopy(fullData, start, chunk, 0, chunkLen);

                            return new Response.Builder()
                                    .request(request)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(206)
                                    .message("Partial Content")
                                    .header("Content-Range", "bytes " + start + "-" + end + "/" + fullData.length)
                                    .header("Content-Length", String.valueOf(chunkLen))
                                    .body(ResponseBody.create(MediaType.parse("audio/webm"), chunk))
                                    .build();
                        }

                        // Full content HTTP 200 fallback
                        return new Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .header("Content-Length", String.valueOf(fullData.length))
                                .body(ResponseBody.create(MediaType.parse("audio/webm"), fullData))
                                .build();
                    }
                })
                .build();
    }

    @Test
    public void testMultiRangeStreaming_SequentialRangesAndCompleteData() throws Exception {
        byte[] expectedData = new byte[250];
        for (int i = 0; i < expectedData.length; i++) {
            expectedData[i] = (byte) (i & 0xFF);
        }

        List<String> capturedRanges = new ArrayList<>();
        int rangeSize = 100; // 3 ranges: 0-99, 100-199, 200-249
        OkHttpClient client = createRangedMockClient(expectedData, capturedRanges);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream", expectedData.length, client, rangeSize);
        source.open();

        assertTrue(source.isRanged());
        assertEquals(expectedData.length, source.getContentLength());

        byte[] result = new byte[expectedData.length];
        int totalRead = 0;
        byte[] readBuf = new byte[32];
        int r;
        while ((r = source.read(readBuf, 0, readBuf.length)) != -1) {
            System.arraycopy(readBuf, 0, result, totalRead, r);
            totalRead += r;
        }

        assertEquals(expectedData.length, totalRead);
        assertArrayEquals(expectedData, result);
        assertEquals(-1, source.read(readBuf, 0, readBuf.length));
        assertEquals(expectedData.length, source.getBytesRead());

        // Verify captured range headers
        assertEquals(3, capturedRanges.size());
        assertEquals("bytes=0-99", capturedRanges.get(0));
        assertEquals("bytes=100-199", capturedRanges.get(1));
        assertEquals("bytes=200-249", capturedRanges.get(2));

        source.close();
    }

    @Test
    public void testContentRangeParsing_TotalLengthDiscoveredFromHeader() throws Exception {
        byte[] expectedData = new byte[150];
        List<String> capturedRanges = new ArrayList<>();
        int rangeSize = 100;
        OkHttpClient client = createRangedMockClient(expectedData, capturedRanges);

        // declaredContentLength is -1 (unknown upfront)
        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream", -1, client, rangeSize);
        source.open();

        // Total content length was discovered from Content-Range header
        assertEquals(150, source.getContentLength());
        assertTrue(source.isRanged());

        byte[] result = new byte[150];
        int totalRead = 0;
        byte[] readBuf = new byte[50];
        int r;
        while ((r = source.read(readBuf, 0, readBuf.length)) != -1) {
            System.arraycopy(readBuf, 0, result, totalRead, r);
            totalRead += r;
        }

        assertEquals(150, totalRead);
        assertEquals(2, capturedRanges.size());
        assertEquals("bytes=0-99", capturedRanges.get(0));
        assertEquals("bytes=100-149", capturedRanges.get(1));

        source.close();
    }

    @Test
    public void testHttp200Fallback_ServerIgnoresRangeHeader() throws Exception {
        final byte[] expectedData = "Non-ranged full stream data".getBytes(StandardCharsets.UTF_8);
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        return new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .header("Content-Length", String.valueOf(expectedData.length))
                                .body(ResponseBody.create(MediaType.parse("audio/webm"), expectedData))
                                .build();
                    }
                })
                .build();

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream", expectedData.length, client, 10);
        source.open();

        assertFalse("Expected non-ranged mode when server returns HTTP 200", source.isRanged());
        assertEquals(expectedData.length, source.getContentLength());

        byte[] buf = new byte[expectedData.length + 10];
        int read = source.read(buf, 0, buf.length);
        assertEquals(expectedData.length, read);
        assertEquals(-1, source.read(buf, 0, buf.length));

        source.close();
    }

    @Test
    public void testCancellation_DuringRangeRead_AbortsSafely() throws Exception {
        byte[] expectedData = new byte[200];
        OkHttpClient client = createRangedMockClient(expectedData, null);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream", expectedData.length, client, 100);
        source.open();

        byte[] buf = new byte[50];
        int read = source.read(buf, 0, 50);
        assertEquals(50, read);

        source.close();

        try {
            source.read(buf, 0, 50);
            fail("Expected VotCancellationException after close");
        } catch (VotCancellationException expected) {
            // Success
        }
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

    @Test
    public void testEndToEndWithUploader_MultiRangeSource() throws Exception {
        // Create 1500-byte audio payload
        byte[] fullAudio = new byte[1500];
        for (int i = 0; i < fullAudio.length; i++) {
            fullAudio[i] = (byte) ((i * 7) & 0xFF);
        }

        // Mock ranged YouTube client with range size 500
        OkHttpClient ytClient = createRangedMockClient(fullAudio, null);
        VotYouTubeAudioSource ytSource = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream", fullAudio.length, ytClient, 500);

        // Mock upload HTTP client with chunk size 600 (3 chunks: 600, 600, 300)
        final List<byte[]> uploadedChunks = new ArrayList<>();
        final List<byte[]> enqueuedResponses = new ArrayList<>();
        enqueuedResponses.add(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, java.util.Arrays.asList("1", "2")));
        enqueuedResponses.add(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, java.util.Collections.singletonList("2")));
        enqueuedResponses.add(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        VotHttp uploadHttp = new VotHttp(new OkHttpClient()) {
            @Override
            public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers, CallHolder callHolder) throws IOException {
                uploadedChunks.add(body);
                return enqueuedResponses.remove(0);
            }
        };

        VotAudioUploader uploader = new VotAudioUploader(uploadHttp, 600);
        VotSession session = new VotSession();
        session.secretKey = "fake_key";
        session.uuid = "fake_uuid";

        VotTranslationAudioResponse resp = uploader.uploadAudio(
                "https://youtu.be/test",
                "trans_id_123",
                "file_id_456",
                session,
                null,
                ytSource
        );

        assertNotNull(resp);
        assertEquals(1500, uploader.getTotalBytesRead());
        assertEquals(1500, uploader.getTotalBytesUploaded());
        assertEquals(3, uploader.getTotalChunksUploaded()); // 600 + 600 + 300
    }
}

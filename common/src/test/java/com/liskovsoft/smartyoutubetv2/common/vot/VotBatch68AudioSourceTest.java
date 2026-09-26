package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.NonNull;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SmartTube VOX — Batch #68
 * Comprehensive synthetic unit tests for:
 * - VotMediaFormatSelector (audio extraction, codec sorting, bitrate preference, exclusion of Russian/dubbed tracks)
 * - VotYouTubeAudioSource (HTTP streaming, error classification, HTML rejection, network isolation, lifecycle, cancellation)
 * - VotAudioUploader integration with VotYouTubeAudioSource
 */
public class VotBatch68AudioSourceTest {

    // =============================================================================================
    // Helper: TestMediaFormat mock
    // =============================================================================================
    private static class TestMediaFormat implements MediaFormat {
        private String mUrl;
        private String mMimeType;
        private String mBitrate;
        private String mClen;
        private String mLanguage;
        private String mAudioTrackId;
        private boolean mIsOtf;

        public TestMediaFormat(String url, String mimeType, String bitrate, String clen, String lang, String trackId, boolean isOtf) {
            mUrl = url;
            mMimeType = mimeType;
            mBitrate = bitrate;
            mClen = clen;
            mLanguage = lang;
            mAudioTrackId = trackId;
            mIsOtf = isOtf;
        }

        @Override public int getFormatType() { return FORMAT_TYPE_DASH; }
        @Override public String getUrl() { return mUrl; }
        @Override public String getMimeType() { return mMimeType; }
        @Override public String getITag() { return "251"; }
        @Override public boolean isDrc() { return false; }
        @Override public String getClen() { return mClen; }
        @Override public String getBitrate() { return mBitrate; }
        @Override public String getProjectionType() { return null; }
        @Override public String getXtags() { return null; }
        @Override public String getAudioTrackId() { return mAudioTrackId; }
        @Override public int getWidth() { return 0; }
        @Override public int getHeight() { return 0; }
        @Override public String getIndex() { return null; }
        @Override public String getInit() { return null; }
        @Override public String getFps() { return null; }
        @Override public String getLmt() { return null; }
        @Override public String getQualityLabel() { return null; }
        @Override public String getFormat() { return null; }
        @Override public boolean isOtf() { return mIsOtf; }
        @Override public String getOtfInitUrl() { return null; }
        @Override public String getOtfTemplateUrl() { return null; }
        @Override public String getLanguage() { return mLanguage; }
        @Override public int getTargetDurationSec() { return 0; }
        @Override public int getMaxDvrDurationSec() { return 0; }
        @Override public int getApproxDurationMs() { return 0; }
        @Override public String getQuality() { return null; }
        @Override public String getSignature() { return null; }
        @Override public String getAudioSamplingRate() { return "48000"; }
        @Override public String getSourceUrl() { return null; }
        @Override public List<String> getSegmentUrlList() { return null; }
        @Override public List<String> getGlobalSegmentList() { return null; }
        @Override public int compareTo(MediaFormat o) { return 0; }
    }

    private static OkHttpClient createMockHttpClient(final int statusCode, final String contentType, final byte[] bodyBytes, final AtomicReference<Request> capturedRequest) {
        return new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request request = chain.request();
                        if (capturedRequest != null) {
                            capturedRequest.set(request);
                        }

                        ResponseBody body = null;
                        if (bodyBytes != null) {
                            MediaType mediaType = contentType != null ? MediaType.parse(contentType) : null;
                            body = ResponseBody.create(mediaType, bodyBytes);
                        }

                        return new Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(statusCode)
                                .message(statusCode == 200 ? "OK" : "Error " + statusCode)
                                .body(body)
                                .build();
                    }
                })
                .build();
    }

    // =============================================================================================
    // Group 1: VotMediaFormatSelector Tests
    // =============================================================================================

    @Test
    public void testSelectBestAudioFormat_NullOrEmptyList() {
        assertNull(VotMediaFormatSelector.selectBestAudioFormat(null));
        assertNull(VotMediaFormatSelector.selectBestAudioFormat(Collections.<MediaFormat>emptyList()));
    }

    @Test
    public void testSelectBestAudioFormat_NonAudioExcluded() {
        List<MediaFormat> list = Arrays.<MediaFormat>asList(
                new TestMediaFormat("https://rr.googlevideo.com/videoplayback?1", "video/webm", "1500000", "10000000", "en", null, false),
                new TestMediaFormat("https://rr.googlevideo.com/videoplayback?2", "video/mp4", "2500000", "20000000", "en", null, false),
                new TestMediaFormat("https://rr.googlevideo.com/videoplayback?3", "text/vtt", "1000", "5000", "en", null, false)
        );
        assertNull(VotMediaFormatSelector.selectBestAudioFormat(list));
    }

    @Test
    public void testSelectBestAudioFormat_InvalidUrlExcluded() {
        List<MediaFormat> list = Arrays.<MediaFormat>asList(
                new TestMediaFormat(null, "audio/webm", "50000", "100000", "en", null, false),
                new TestMediaFormat("   ", "audio/webm", "50000", "100000", "en", null, false),
                new TestMediaFormat("javascript:void(0)", "audio/webm", "50000", "100000", "en", null, false)
        );
        assertNull(VotMediaFormatSelector.selectBestAudioFormat(list));
    }

    @Test
    public void testSelectBestAudioFormat_LowestBitratePreferred() {
        TestMediaFormat low = new TestMediaFormat("https://rr.googlevideo.com/audio_low", "audio/webm", "50000", "500000", "en", null, false);
        TestMediaFormat mid = new TestMediaFormat("https://rr.googlevideo.com/audio_mid", "audio/webm", "70000", "700000", "en", null, false);
        TestMediaFormat high = new TestMediaFormat("https://rr.googlevideo.com/audio_high", "audio/webm", "160000", "1600000", "en", null, false);

        List<MediaFormat> list = Arrays.<MediaFormat>asList(high, low, mid);
        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(list);

        assertNotNull(selected);
        assertEquals(low.getUrl(), selected.getUrl());
        assertEquals("50000", selected.getBitrate());
    }

    @Test
    public void testSelectBestAudioFormat_RussianTracksExcludedWhenOriginalPresent() {
        TestMediaFormat origEng = new TestMediaFormat("https://rr.googlevideo.com/audio_orig_en", "audio/webm", "128000", "1000000", "en", "en.orig", false);
        TestMediaFormat ruDub = new TestMediaFormat("https://rr.googlevideo.com/audio_ru_dub", "audio/webm", "48000", "400000", "ru", "ru.dubbed", false);
        TestMediaFormat ruLang = new TestMediaFormat("https://rr.googlevideo.com/audio_ru", "audio/webm", "48000", "400000", "rus", null, false);

        List<MediaFormat> list = Arrays.<MediaFormat>asList(ruDub, ruLang, origEng);
        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(list);

        assertNotNull(selected);
        assertEquals(origEng.getUrl(), selected.getUrl());
        assertEquals("en.orig", selected.getAudioTrackId());
    }

    @Test
    public void testSelectBestAudioFormat_DubbedTracksExcludedWhenOriginalPresent() {
        TestMediaFormat orig = new TestMediaFormat("https://rr.googlevideo.com/audio_orig", "audio/webm", "128000", "1000000", "en", "en.main", false);
        TestMediaFormat dubbed = new TestMediaFormat("https://rr.googlevideo.com/audio_dubbed", "audio/webm", "48000", "400000", "en", "en.dubbed", false);

        List<MediaFormat> list = Arrays.<MediaFormat>asList(dubbed, orig);
        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(list);

        assertNotNull(selected);
        assertEquals(orig.getUrl(), selected.getUrl());
    }

    @Test
    public void testSelectBestAudioFormat_AutoDubbedTracksExcluded() {
        TestMediaFormat orig = new TestMediaFormat("https://rr.googlevideo.com/audio_orig", "audio/webm", "128000", "1000000", "en", "en", false);
        TestMediaFormat autoDub = new TestMediaFormat("https://rr.googlevideo.com/audio_autodub", "audio/webm", "48000", "400000", "en", "en.dubbed-auto", false);

        List<MediaFormat> list = Arrays.<MediaFormat>asList(autoDub, orig);
        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(list);

        assertNotNull(selected);
        assertEquals(orig.getUrl(), selected.getUrl());
    }

    @Test
    public void testSelectBestAudioFormat_WebmPreferredOverMp4AtEqualBitrate() {
        TestMediaFormat mp4 = new TestMediaFormat("https://rr.googlevideo.com/audio_mp4", "audio/mp4", "128000", "1000000", "en", null, false);
        TestMediaFormat webm = new TestMediaFormat("https://rr.googlevideo.com/audio_webm", "audio/webm", "128000", "1000000", "en", null, false);

        List<MediaFormat> list = Arrays.<MediaFormat>asList(mp4, webm);
        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(list);

        assertNotNull(selected);
        assertEquals(webm.getUrl(), selected.getUrl());
    }

    @Test
    public void testSelectBestAudioFormat_Mp4SelectedIfNoWebm() {
        TestMediaFormat mp4 = new TestMediaFormat("https://rr.googlevideo.com/audio_mp4", "audio/mp4", "128000", "1000000", "en", null, false);

        List<MediaFormat> list = Collections.<MediaFormat>singletonList(mp4);
        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(list);

        assertNotNull(selected);
        assertEquals(mp4.getUrl(), selected.getUrl());
    }

    @Test
    public void testSelectBestAudioFormat_OtfWithoutUrlExcluded() {
        TestMediaFormat otfNoUrl = new TestMediaFormat("", "audio/webm", "50000", "100000", "en", null, true);
        TestMediaFormat regular = new TestMediaFormat("https://rr.googlevideo.com/audio_regular", "audio/webm", "70000", "200000", "en", null, false);

        List<MediaFormat> list = Arrays.<MediaFormat>asList(otfNoUrl, regular);
        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(list);

        assertNotNull(selected);
        assertEquals(regular.getUrl(), selected.getUrl());
    }

    @Test
    public void testSelectBestAudioFormat_FallbackWhenOnlyRussianTracksExist() {
        // Native Russian video (e.g. no English tracks at all)
        TestMediaFormat ruOriginal = new TestMediaFormat("https://rr.googlevideo.com/audio_ru_orig", "audio/webm", "70000", "500000", "ru", "ru.orig", false);
        TestMediaFormat ruAutoDub = new TestMediaFormat("https://rr.googlevideo.com/audio_ru_autodub", "audio/webm", "48000", "400000", "ru", "ru.dubbed-auto", false);

        List<MediaFormat> list = Arrays.<MediaFormat>asList(ruAutoDub, ruOriginal);
        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(list);

        assertNotNull(selected);
        assertEquals(ruOriginal.getUrl(), selected.getUrl());
    }

    // =============================================================================================
    // Group 2: VotYouTubeAudioSource Construction & Metadata Tests
    // =============================================================================================

    @Test
    public void testFromMediaFormat_ExtractsUrlAndContentLength() {
        TestMediaFormat fmt = new TestMediaFormat("https://rr.googlevideo.com/stream1", "audio/webm", "50000", "1234567", "en", null, false);
        VotYouTubeAudioSource src = VotYouTubeAudioSource.fromMediaFormat(fmt);

        assertEquals("https://rr.googlevideo.com/stream1", src.getMediaUrl());
        assertEquals(1234567L, src.getContentLength());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_RejectsNullUrl() {
        new VotYouTubeAudioSource(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_RejectsEmptyUrl() {
        new VotYouTubeAudioSource("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromMediaFormat_RejectsNullFormat() {
        VotYouTubeAudioSource.fromMediaFormat(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromMediaFormat_RejectsFormatWithNullUrl() {
        TestMediaFormat fmt = new TestMediaFormat(null, "audio/webm", "50000", "1000", "en", null, false);
        VotYouTubeAudioSource.fromMediaFormat(fmt);
    }

    // =============================================================================================
    // Group 3: VotYouTubeAudioSource Streaming & HTTP Tests
    // =============================================================================================

    @Test
    public void testOpenAndRead_SuccessfulAudioStream() throws Exception {
        byte[] expectedData = "Dummy YouTube Opus Audio Payload Data Stream for VOT Acceptance".getBytes(StandardCharsets.UTF_8);
        OkHttpClient client = createMockHttpClient(200, "audio/webm", expectedData, null);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_opus", expectedData.length, client);
        assertEquals(expectedData.length, source.getContentLength());

        source.open();
        assertEquals(expectedData.length, source.getContentLength());

        byte[] readBuf = new byte[expectedData.length + 10];
        int totalRead = 0;
        int r;
        while ((r = source.read(readBuf, totalRead, readBuf.length - totalRead)) != -1) {
            totalRead += r;
        }

        assertEquals(expectedData.length, totalRead);
        assertEquals(expectedData.length, source.getBytesRead());

        byte[] result = new byte[totalRead];
        System.arraycopy(readBuf, 0, result, 0, totalRead);
        assertArrayEquals(expectedData, result);

        source.close();
    }

    @Test
    public void testOpen_Error403_ClassifiedAsExpiredUrl() {
        OkHttpClient client = createMockHttpClient(403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8), null);
        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/expired_stream", -1, client);

        try {
            source.open();
            fail("Expected VotAudioSourceException for 403");
        } catch (VotAudioSourceException e) {
            assertTrue("Expected mention of HTTP 403 or expired URL: " + e.getMessage(),
                    e.getMessage().contains("403") && e.getMessage().contains("expired"));
        } catch (IOException e) {
            fail("Expected VotAudioSourceException but got: " + e.getClass().getName());
        }
    }

    @Test
    public void testOpen_Error404_ClassifiedAsNotFound() {
        OkHttpClient client = createMockHttpClient(404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8), null);
        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/missing_stream", -1, client);

        try {
            source.open();
            fail("Expected VotAudioSourceException for 404");
        } catch (VotAudioSourceException e) {
            assertTrue("Expected mention of HTTP 404: " + e.getMessage(), e.getMessage().contains("404"));
        } catch (IOException e) {
            fail("Expected VotAudioSourceException but got: " + e.getClass().getName());
        }
    }

    @Test
    public void testOpen_Error500_ClassifiedAsServerError() {
        OkHttpClient client = createMockHttpClient(500, "text/plain", "Server Error".getBytes(StandardCharsets.UTF_8), null);
        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/server_error", -1, client);

        try {
            source.open();
            fail("Expected VotAudioSourceException for 500");
        } catch (VotAudioSourceException e) {
            assertTrue("Expected mention of HTTP 500: " + e.getMessage(), e.getMessage().contains("500"));
        } catch (IOException e) {
            fail("Expected VotAudioSourceException but got: " + e.getClass().getName());
        }
    }

    @Test
    public void testOpen_HtmlErrorResponse_Rejected() {
        String htmlPage = "<!DOCTYPE html><html><body><h1>Captcha Verification Required</h1></body></html>";
        OkHttpClient client = createMockHttpClient(200, "text/html; charset=utf-8", htmlPage.getBytes(StandardCharsets.UTF_8), null);
        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/captcha", -1, client);

        try {
            source.open();
            fail("Expected VotAudioSourceException for HTML response");
        } catch (VotAudioSourceException e) {
            assertTrue("Expected rejection of HTML: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains("html"));
        } catch (IOException e) {
            fail("Expected VotAudioSourceException but got: " + e.getClass().getName());
        }
    }

    @Test
    public void testRead_BeforeOpenThrowsException() {
        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/test", -1, null);
        byte[] buf = new byte[16];
        try {
            source.read(buf, 0, 16);
            fail("Expected exception when reading before open");
        } catch (VotAudioSourceException e) {
            assertTrue(e.getMessage().contains("not opened"));
        } catch (IOException e) {
            fail("Unexpected exception: " + e);
        }
    }

    @Test
    public void testClose_CancelsActiveStreamAndIdempotent() throws Exception {
        byte[] data = "Short Audio".getBytes(StandardCharsets.UTF_8);
        OkHttpClient client = createMockHttpClient(200, "audio/webm", data, null);
        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream", data.length, client);

        source.open();
        source.close();
        // Idempotent close
        source.close();

        byte[] buf = new byte[16];
        try {
            source.read(buf, 0, 16);
            fail("Expected VotCancellationException after close");
        } catch (VotCancellationException expected) {
            // Success
        }
    }

    @Test
    public void testNetworkIsolation_YouTubeRequestHasNoYandexHeaders() throws Exception {
        byte[] data = "Audio Payload".getBytes(StandardCharsets.UTF_8);
        final AtomicReference<Request> capturedRequest = new AtomicReference<>();
        OkHttpClient client = createMockHttpClient(200, "audio/webm", data, capturedRequest);

        VotYouTubeAudioSource source = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_isolation", data.length, client);
        source.open();

        Request req = capturedRequest.get();
        assertNotNull(req);

        // Verify standard media headers exist
        assertEquals("keep-alive", req.header("Connection"));
        assertEquals("*/*", req.header("Accept"));
        assertEquals(VotConfig.USER_AGENT, req.header("User-Agent"));

        // Strictly verify NO Yandex session headers or auth tokens are leaked to YouTube
        assertNull(req.header("Vtrans-Signature"));
        assertNull(req.header("Sec-Vtrans-Token"));
        assertNull(req.header("Authorization"));
        assertNull(req.header("X-Yandex-Auth"));

        source.close();
    }

    // =============================================================================================
    // Group 4: VotAudioUploader + VotYouTubeAudioSource End-to-End Integration
    // =============================================================================================

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
        s.secretKey = "test-secret-key-68";
        s.uuid = "test-session-uuid-68";
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

    @Test
    public void testUploaderWithYouTubeAudioSource_SinglePart() throws Exception {
        byte[] audioBytes = "Simulated YouTube WebM Audio Payload Bytes".getBytes(StandardCharsets.UTF_8);
        OkHttpClient ytClient = createMockHttpClient(200, "audio/webm", audioBytes, null);
        VotYouTubeAudioSource ytSource = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_single", audioBytes.length, ytClient);

        MockUploadHttp yandexHttp = new MockUploadHttp();
        yandexHttp.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        VotAudioUploader uploader = new VotAudioUploader(yandexHttp, 1024);
        VotTranslationAudioResponse resp = uploader.uploadAudio(
                "https://www.youtube.com/watch?v=TEST_68",
                "tr-test-68",
                "smarttube-android-test68",
                createSession(),
                "test-oauth-token",
                ytSource
        );

        assertNotNull(resp);
        assertEquals(VotTranslationAudioResponse.STATUS_DONE, resp.status);
        assertEquals(1, yandexHttp.getCapturedBodies().size());
    }

    @Test
    public void testUploaderWithYouTubeAudioSource_MultiPart() throws Exception {
        // Create 2500 bytes of audio data with chunk size 1000 -> 3 chunks (1000, 1000, 500)
        byte[] audioBytes = new byte[2500];
        Arrays.fill(audioBytes, (byte) 0x7E);

        OkHttpClient ytClient = createMockHttpClient(200, "audio/webm", audioBytes, null);
        VotYouTubeAudioSource ytSource = new VotYouTubeAudioSource("https://rr.googlevideo.com/stream_multi", audioBytes.length, ytClient);

        MockUploadHttp yandexHttp = new MockUploadHttp();
        yandexHttp.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, Arrays.asList("1", "2")));
        yandexHttp.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, Collections.singletonList("2")));
        yandexHttp.enqueueResponse(makeResponseBytes(VotTranslationAudioResponse.STATUS_DONE, null));

        VotAudioUploader uploader = new VotAudioUploader(yandexHttp, 1000);
        VotTranslationAudioResponse resp = uploader.uploadAudio(
                "https://www.youtube.com/watch?v=TEST_68_MULTI",
                "tr-test-68-multi",
                "smarttube-android-test68-multi",
                createSession(),
                "test-oauth-token",
                ytSource
        );

        assertNotNull(resp);
        assertEquals(VotTranslationAudioResponse.STATUS_DONE, resp.status);
        assertEquals(3, yandexHttp.getCapturedBodies().size());
    }
}

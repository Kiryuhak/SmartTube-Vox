/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.Observable;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SmartTubeYandexVotAudioSourceBridgeTest {

    private static class FakeMediaFormat implements MediaFormat {
        private final String url;
        private final String mimeType;
        private final String itag;
        private final String bitrate;
        private final String clen;
        private final int formatType;
        private final boolean otf;
        private final String language;
        private final String audioTrackId;

        public FakeMediaFormat(String url, String mimeType, String itag, String bitrate, String clen, int formatType, boolean otf, String language, String audioTrackId) {
            this.url = url;
            this.mimeType = mimeType;
            this.itag = itag;
            this.bitrate = bitrate;
            this.clen = clen;
            this.formatType = formatType;
            this.otf = otf;
            this.language = language;
            this.audioTrackId = audioTrackId;
        }

        @Override public int getFormatType() { return formatType; }
        @Override public String getUrl() { return url; }
        @Override public String getMimeType() { return mimeType; }
        @Override public String getITag() { return itag; }
        @Override public boolean isDrc() { return false; }
        @Override public String getClen() { return clen; }
        @Override public String getBitrate() { return bitrate; }
        @Override public String getProjectionType() { return null; }
        @Override public String getXtags() { return null; }
        @Override public String getAudioTrackId() { return audioTrackId; }
        @Override public int getWidth() { return 0; }
        @Override public int getHeight() { return 0; }
        @Override public String getIndex() { return null; }
        @Override public String getInit() { return null; }
        @Override public String getFps() { return null; }
        @Override public String getLmt() { return null; }
        @Override public String getQualityLabel() { return null; }
        @Override public String getFormat() { return null; }
        @Override public boolean isOtf() { return otf; }
        @Override public String getOtfInitUrl() { return null; }
        @Override public String getOtfTemplateUrl() { return null; }
        @Override public String getLanguage() { return language; }
        @Override public int getTargetDurationSec() { return 0; }
        @Override public int getMaxDvrDurationSec() { return 0; }
        @Override public int getApproxDurationMs() { return 0; }
        @Override public String getQuality() { return null; }
        @Override public String getSignature() { return null; }
        @Override public String getAudioSamplingRate() { return null; }
        @Override public String getSourceUrl() { return null; }
        @Override public List<String> getSegmentUrlList() { return null; }
        @Override public List<String> getGlobalSegmentList() { return null; }
        @Override public int compareTo(MediaFormat o) { return 0; }
    }

    private static class FakeMediaItemFormatInfo implements MediaItemFormatInfo {
        private final String videoId;
        private final List<MediaFormat> adaptiveFormats;

        public FakeMediaItemFormatInfo(String videoId, List<MediaFormat> adaptiveFormats) {
            this.videoId = videoId;
            this.adaptiveFormats = adaptiveFormats;
        }

        @Override public List<MediaFormat> getAdaptiveFormats() { return adaptiveFormats; }
        @Override public List<MediaFormat> getUrlFormats() { return Collections.emptyList(); }
        @Override public List<MediaSubtitle> getSubtitles() { return Collections.emptyList(); }
        @Override public String getHlsManifestUrl() { return null; }
        @Override public String getDashManifestUrl() { return null; }
        @Override public String getLengthSeconds() { return "300"; }
        @Override public String getTitle() { return "Test Video"; }
        @Override public String getAuthor() { return "Author"; }
        @Override public String getViewCount() { return "1000"; }
        @Override public String getDescription() { return "Description"; }
        @Override public String getVideoId() { return videoId; }
        @Override public String getChannelId() { return "channel_1"; }
        @Override public boolean isLive() { return false; }
        @Override public boolean isLiveContent() { return false; }
        @Override public boolean containsMedia() { return true; }
        @Override public boolean containsSabrFormats() { return false; }
        @Override public boolean containsDashFormats() { return true; }
        @Override public boolean containsHlsUrl() { return false; }
        @Override public boolean containsDashUrl() { return false; }
        @Override public boolean containsUrlFormats() { return false; }
        @Override public boolean hasExtendedHlsFormats() { return false; }
        @Override public float getVolumeLevel() { return 1.0f; }
        @Override public InputStream createMpdStream() { return null; }
        @Override public Observable<InputStream> createMpdStreamObservable() { return null; }
        @Override public List<String> createUrlList() { return Collections.emptyList(); }
        @Override public MediaItemStoryboard createStoryboard() { return null; }
        @Override public boolean isUnplayable() { return false; }
        @Override public boolean isUnknownError() { return false; }
        @Override public String getPlayabilityReason() { return null; }
        @Override public boolean isStreamSeekable() { return true; }
        @Override public String getStartTimestamp() { return null; }
        @Override public String getUploadDate() { return null; }
        @Override public long getStartTimeMs() { return 0; }
        @Override public int getStartSegmentNum() { return 0; }
        @Override public int getSegmentDurationUs() { return 0; }
        @Override public String getPaidContentText() { return null; }
        @Override public String getVideoPlaybackUstreamerConfig() { return null; }
        @Override public String getServerAbrStreamingUrl() { return null; }
        @Override public String getPoToken() { return null; }
        @Override public String getVisitorCookie() { return null; }
        @Override public ClientInfo getClientInfo() { return null; }

        @Override public boolean isSynced() { return true; }
        @Override public boolean isAuth() { return false; }
        @Override public String getEventId() { return null; }
        @Override public String getVisitorMonitoringData() { return null; }
        @Override public String getOfParam() { return null; }
        @Override public String getClickTrackingParams() { return null; }
        @Override public void setClickTrackingParams(String clickTrackingParams) {}
        @Override public boolean isCacheActual() { return true; }
        @Override public void sync(MediaItemFormatInfo formatInfo) {}
    }

    // =========================================================================
    // Bridge & Mapping Tests (1 - 10, 17, 18)
    // =========================================================================

    @Test
    public void test1_MapsValidSmartTubeAudioFormatToYandexVotAudioSource() {
        FakeMediaFormat format = new FakeMediaFormat(
                "https://googlevideo.com/audio", "audio/webm; codecs=\"opus\"", "251", "160000", "5000000",
                MediaFormat.FORMAT_TYPE_DASH, false, "en", "main"
        );
        FakeMediaItemFormatInfo info = new FakeMediaItemFormatInfo("vid_test1", Collections.singletonList(format));

        YandexVotAudioSource source = YandexVotAudioSourceSelector.fromMediaItemFormatInfo(info);
        assertNotNull(source);
        assertEquals("vid_test1", source.getVideoId());
        assertEquals("audio/webm", source.getMimeType());
        assertEquals("opus", source.getCodec());
        assertEquals(251, source.getItag());
        assertEquals(160000, source.getBitrate());
        assertEquals(5000000L, source.getContentLength());
        assertTrue(source.isRangeSupported());
    }

    @Test
    public void test2_MapsVideoIdCorrectly() {
        FakeMediaFormat format = new FakeMediaFormat(
                "https://googlevideo.com/audio", "audio/webm", "249", "50000", "2000000",
                MediaFormat.FORMAT_TYPE_DASH, false, "en", "main"
        );
        FakeMediaItemFormatInfo info = new FakeMediaItemFormatInfo("dQw4w9WgXcQ", Collections.singletonList(format));

        YandexVotAudioSource source = YandexVotAudioSourceSelector.fromMediaItemFormatInfo(info);
        assertNotNull(source);
        assertEquals("dQw4w9WgXcQ", source.getVideoId());
    }

    @Test
    public void test3_PreservesMimeCodecItagBitrateContentLength() {
        FakeMediaFormat format = new FakeMediaFormat(
                "https://googlevideo.com/audio_aac", "audio/mp4; codecs=\"mp4a.40.2\"", "140", "128000", "8195051",
                MediaFormat.FORMAT_TYPE_DASH, false, "en", "main"
        );
        FakeMediaItemFormatInfo info = new FakeMediaItemFormatInfo("vid_aac", Collections.singletonList(format));

        YandexVotAudioSource source = YandexVotAudioSourceSelector.fromMediaItemFormatInfo(info);
        assertNotNull(source);
        assertEquals("audio/mp4", source.getMimeType());
        assertEquals("mp4a", source.getCodec());
        assertEquals(140, source.getItag());
        assertEquals(128000, source.getBitrate());
        assertEquals(8195051L, source.getContentLength());
    }

    @Test
    public void test4_PrefersOpusLowerBitrateCandidate() {
        FakeMediaFormat aac128 = new FakeMediaFormat(
                "https://googlevideo.com/aac128", "audio/mp4", "140", "128000", "5000000",
                MediaFormat.FORMAT_TYPE_DASH, false, "en", "main"
        );
        FakeMediaFormat opus160 = new FakeMediaFormat(
                "https://googlevideo.com/opus160", "audio/webm; codecs=\"opus\"", "251", "160000", "6000000",
                MediaFormat.FORMAT_TYPE_DASH, false, "en", "main"
        );
        FakeMediaFormat opus50 = new FakeMediaFormat(
                "https://googlevideo.com/opus50", "audio/webm; codecs=\"opus\"", "249", "50000", "2000000",
                MediaFormat.FORMAT_TYPE_DASH, false, "en", "main"
        );

        FakeMediaItemFormatInfo info = new FakeMediaItemFormatInfo("vid_multi", Arrays.asList(aac128, opus160, opus50));

        YandexVotAudioSource source = YandexVotAudioSourceSelector.fromMediaItemFormatInfo(info);
        assertNotNull(source);
        // Opus 50k should be selected
        assertEquals(249, source.getItag());
        assertEquals(50000, source.getBitrate());
        assertTrue(source.isOpus());
    }

    @Test
    public void test5_RejectsVideoOnlyFormat() {
        FakeMediaFormat video = new FakeMediaFormat(
                "https://googlevideo.com/video", "video/mp4", "137", "5000000", "100000000",
                MediaFormat.FORMAT_TYPE_DASH, false, null, null
        );
        FakeMediaItemFormatInfo info = new FakeMediaItemFormatInfo("vid_v", Collections.singletonList(video));

        YandexVotAudioSource source = YandexVotAudioSourceSelector.fromMediaItemFormatInfo(info);
        assertNull(source);
    }

    @Test
    public void test6_RejectsSabrCandidate() {
        FakeMediaFormat sabr = new FakeMediaFormat(
                "https://googlevideo.com/sabr", "audio/webm", "251", "160000", "5000000",
                MediaFormat.FORMAT_TYPE_SABR, false, "en", "main"
        );
        FakeMediaItemFormatInfo info = new FakeMediaItemFormatInfo("vid_sabr", Collections.singletonList(sabr));

        YandexVotAudioSource source = YandexVotAudioSourceSelector.fromMediaItemFormatInfo(info);
        assertNull(source);
    }

    @Test
    public void test7_RejectsMissingUrl() {
        FakeMediaFormat emptyUrl = new FakeMediaFormat(
                "", "audio/webm", "251", "160000", "5000000",
                MediaFormat.FORMAT_TYPE_DASH, false, "en", "main"
        );
        FakeMediaFormat nullUrl = new FakeMediaFormat(
                null, "audio/webm", "251", "160000", "5000000",
                MediaFormat.FORMAT_TYPE_DASH, false, "en", "main"
        );
        FakeMediaItemFormatInfo info = new FakeMediaItemFormatInfo("vid_empty", Arrays.asList(emptyUrl, nullUrl));

        YandexVotAudioSource source = YandexVotAudioSourceSelector.fromMediaItemFormatInfo(info);
        assertNull(source);
    }

    @Test
    public void test8_RejectsMissingOrInvalidLengthWhereRequired() {
        YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();
        YandexVotAudioSource zeroLenSource = new YandexVotAudioSource(
                "vid", "https://example.com/audio", "audio/webm", "opus", 50000, 0, 249, true, null
        );
        YandexVotAudioResult result = transfer.transfer(
                zeroLenSource, "https://url", "trans", "file", (start, length) -> new byte[length], null, null
        );
        assertEquals(YandexVotAudioResult.SOURCE_UNAVAILABLE, result);
    }

    @Test
    public void test9_RejectsNonRangeSource() {
        FakeMediaFormat otf = new FakeMediaFormat(
                "https://googlevideo.com/otf", "audio/webm", "251", "160000", "5000000",
                MediaFormat.FORMAT_TYPE_DASH, true, "en", "main"
        );
        FakeMediaItemFormatInfo info = new FakeMediaItemFormatInfo("vid_otf", Collections.singletonList(otf));

        YandexVotAudioSource source = YandexVotAudioSourceSelector.fromMediaItemFormatInfo(info);
        assertNull(source);
    }

    @Test
    public void test10_StripsAndProtectsSensitiveUrlInLogsAndToString() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer potoken_secret");
        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid_sec", "https://googlevideo.com/videoplayback?expire=99999&signature=privatesig",
                "audio/webm", "opus", 50000, 5000000, 249, true, headers
        );

        String str = source.toString();
        assertTrue(str.contains("[PROTECTED]"));
        assertFalse(str.contains("potoken_secret"));
        assertFalse(str.contains("privatesig"));
        assertFalse(str.contains("googlevideo.com"));
    }

    @Test
    public void test17_HeadersCopiedSafely() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Custom-Header", "value123");
        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid", "https://example.com/audio", "audio/webm", "opus", 50000, 1000, 249, true, headers
        );

        assertEquals("value123", source.getHeaders().get("X-Custom-Header"));
    }

    @Test
    public void test18_SelectorAndProviderReturnSourceUnavailableIfNoCandidateWorks() throws Exception {
        SmartTubeYandexVotAudioSourceProvider provider = new SmartTubeYandexVotAudioSourceProvider(
                (videoId, videoUrl) -> new FakeMediaItemFormatInfo(videoId, Collections.emptyList())
        );

        YandexVotAudioSource source = provider.getAudioSource("vid_none", "https://youtube.com/watch?v=vid_none");
        assertNull(source);
    }

    // =========================================================================
    // Range Reader Tests (11, 12, 13, 14, 15, 16)
    // =========================================================================

    @Test
    public void test11_ReaderSendsCorrectRangeHeader() throws Exception {
        final AtomicReference<String> rangeHeaderSeen = new AtomicReference<>();

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request req = chain.request();
                    rangeHeaderSeen.set(req.header("Range"));
                    byte[] bodyBytes = new byte[100];
                    return new Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .header("Content-Range", "bytes 0-99/1000")
                            .body(ResponseBody.create(MediaType.parse("audio/webm"), bodyBytes))
                            .build();
                })
                .build();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid1", "https://example.com/stream", "audio/webm", "opus", 50000, 1000, 249, true, null
        );

        SmartTubeYandexVotAudioStreamReader reader = new SmartTubeYandexVotAudioStreamReader(source, client);
        byte[] bytes = reader.readRange(0, 100);

        assertNotNull(bytes);
        assertEquals(100, bytes.length);
        assertEquals("bytes=0-99", rangeHeaderSeen.get());
    }

    @Test
    public void test12_ReaderValidates206RangeResponse() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    byte[] bodyBytes = new byte[50];
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .header("Content-Range", "bytes 50-99/1000")
                            .body(ResponseBody.create(MediaType.parse("audio/webm"), bodyBytes))
                            .build();
                })
                .build();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid1", "https://example.com/stream", "audio/webm", "opus", 50000, 1000, 249, true, null
        );

        SmartTubeYandexVotAudioStreamReader reader = new SmartTubeYandexVotAudioStreamReader(source, client);
        byte[] bytes = reader.readRange(50, 50);

        assertNotNull(bytes);
        assertEquals(50, bytes.length);
    }

    @Test
    public void test13_ReaderRejectsShortRead() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    byte[] truncatedBody = new byte[30]; // Only 30 bytes instead of requested 50
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .body(ResponseBody.create(MediaType.parse("audio/webm"), truncatedBody))
                            .build();
                })
                .build();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid1", "https://example.com/stream", "audio/webm", "opus", 50000, 1000, 249, true, null
        );

        SmartTubeYandexVotAudioStreamReader reader = new SmartTubeYandexVotAudioStreamReader(source, client);
        try {
            reader.readRange(0, 50);
            fail("Expected IOException for short read");
        } catch (Exception e) {
            assertTrue(e instanceof IOException);
            assertTrue(e.getMessage().contains("Short read"));
        }
    }

    @Test
    public void test14_ReaderClosesResourceAfterSuccess() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean(false);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Buffer buffer = new Buffer();
                    buffer.write(new byte[50]);
                    BufferedSource source = Okio.buffer(new ForwardingSource(buffer) {
                        @Override
                        public void close() throws IOException {
                            super.close();
                            closed.set(true);
                        }
                    });

                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .body(ResponseBody.create(MediaType.parse("audio/webm"), 50, source))
                            .build();
                })
                .build();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid1", "https://example.com/stream", "audio/webm", "opus", 50000, 1000, 249, true, null
        );

        SmartTubeYandexVotAudioStreamReader reader = new SmartTubeYandexVotAudioStreamReader(source, client);
        reader.readRange(0, 50);

        assertTrue("Response stream must be closed after success", closed.get());
    }

    @Test
    public void test15_ReaderClosesResourceAfterFailure() {
        final AtomicBoolean closed = new AtomicBoolean(false);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Buffer buffer = new Buffer();
                    buffer.write(new byte[20]); // Truncated
                    BufferedSource source = Okio.buffer(new ForwardingSource(buffer) {
                        @Override
                        public void close() throws IOException {
                            super.close();
                            closed.set(true);
                        }
                    });

                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .body(ResponseBody.create(MediaType.parse("audio/webm"), 20, source))
                            .build();
                })
                .build();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid1", "https://example.com/stream", "audio/webm", "opus", 50000, 1000, 249, true, null
        );

        SmartTubeYandexVotAudioStreamReader reader = new SmartTubeYandexVotAudioStreamReader(source, client);
        try {
            reader.readRange(0, 50);
            fail("Expected exception on short read");
        } catch (Exception expected) {
            assertTrue(closed.get());
        }
    }

    @Test
    public void test16_CancellationStopsReading() {
        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid1", "https://example.com/stream", "audio/webm", "opus", 50000, 1000, 249, true, null
        );

        SmartTubeYandexVotAudioStreamReader reader = new SmartTubeYandexVotAudioStreamReader(source);
        reader.cancel();
        assertTrue(reader.isCancelled());

        try {
            reader.readRange(0, 100);
            fail("Expected IOException on cancelled reader");
        } catch (Exception e) {
            assertTrue(e instanceof IOException);
            assertTrue(e.getMessage().contains("cancelled"));
        }
    }
}

package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.ObservableEmitter;
import io.reactivex.disposables.Disposable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SmartTube VOX — Batch #69
 * Comprehensive offline integration tests for real YouTube audio upload
 * into active Yandex VOT translation lifecycle.
 */
public class VotBatch69RealAudioIntegrationTest {
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=TEST_BATCH_69";
    private static final String TRANSLATION_ID = "tr-batch-69-real";
    private static final String OAUTH_TOKEN = "synthetic-oauth-token-69";

    // =============================================================================================
    // 1. Initial FINISHED: No audio source opened
    // =============================================================================================
    @Test
    public void testInitialFinished_NoAudioSourceOpened() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/already_translated.mp3"));

        TrackingAudioSource source = new TrackingAudioSource("AUDIO_BYTES".getBytes(StandardCharsets.UTF_8));
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_READY, progress.get(0).type);
        assertEquals("https://example.invalid/already_translated.mp3", progress.get(0).audioUrl);

        // Verify source was NEVER opened and 0 audio requests occurred
        assertFalse("Source must NOT be opened when translation is already finished", source.opened.get());
        assertTrue("No audio upload requests must occur", http.audioBodies.isEmpty());
        assertEquals(1, http.translateBodies.size());
    }

    // =============================================================================================
    // 2. Initial WAITING: No unnecessary upload
    // =============================================================================================
    @Test
    public void testInitialWaiting_NoUnnecessaryUpload() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 40, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/done_waiting.mp3"));

        TrackingAudioSource source = new TrackingAudioSource("AUDIO_BYTES".getBytes(StandardCharsets.UTF_8));
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(40, progress.get(0).remainingTimeSec);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);

        assertFalse("Source must NOT be opened during standard waiting polling", source.opened.get());
        assertTrue("No audio upload requests must occur during waiting flow", http.audioBodies.isEmpty());
        assertEquals(2, http.translateBodies.size());
    }

    // =============================================================================================
    // 3. AUDIO_REQUESTED: Real source opened and closed
    // =============================================================================================
    @Test
    public void testAudioRequested_RealSourceOpenedAndClosed() {
        MockHttp http = new MockHttp();
        // 1. Initial translate -> STATUS_AUDIO_REQUESTED
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 90, TRANSLATION_ID, null));
        // 2. Post-upload translate -> STATUS_WAITING
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 90, TRANSLATION_ID, null));
        // 3. Regular poll -> STATUS_FINISHED
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/translated_lively.mp3"));

        TrackingAudioSource source = new TrackingAudioSource("REAL_AUDIO_CONTENT".getBytes(StandardCharsets.UTF_8));
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertTrue("Real audio source must be opened", source.opened.get());
        assertTrue("Real audio source must be closed cleanly", source.closed.get());
        assertEquals("Exactly 1 audio upload request must occur", 1, http.audioBodies.size());
        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
    }

    // =============================================================================================
    // 4. AUDIO_REQUESTED: Non-empty audio uploaded & wire verification
    // =============================================================================================
    @Test
    public void testAudioRequested_NonEmptyAudioUploadedAndWireVerified() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/done.mp3"));

        byte[] originalMediaBytes = "NON_EMPTY_RAW_AUDIO_STREAM_BATCH_69".getBytes(StandardCharsets.UTF_8);
        TrackingAudioSource source = new TrackingAudioSource(originalMediaBytes);

        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, http.audioBodies.size());
        byte[] uploadWire = http.audioBodies.get(0);
        assertTrue("Audio payload must not be empty", uploadWire.length > originalMediaBytes.length);

        ParsedWire outer = ParsedWire.parse(uploadWire);
        assertEquals(TRANSLATION_ID, outer.getString(1));
        assertEquals(VIDEO_URL, outer.getString(2));
        assertTrue("Outer Tag 6 (audioInfo) must be present for single-part audio", outer.hasField(6));
        assertFalse("Outer Tag 4 (partialAudioInfo) must not be present for single-part audio", outer.hasField(4));

        ParsedWire inner = ParsedWire.parse(outer.getBytes(6));
        assertTrue("File ID should start with smarttube-", inner.getString(1).startsWith("smarttube-"));
        assertArrayEquals("Uploaded audio bytes must match actual source bytes exactly",
                originalMediaBytes, inner.getBytes(2));
    }

    // =============================================================================================
    // 5. Audio upload: Same cryptographic session and OAuth context preserved
    // =============================================================================================
    @Test
    public void testAudioUpload_SameSessionAndOAuthPreserved() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 60, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/done.mp3"));

        TrackingAudioSource source = new TrackingAudioSource(new byte[]{1, 2, 3, 4, 5});
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setTestCredentials(true, OAUTH_TOKEN);
        client.setAudioSourceProvider(url -> source);

        client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, http.sessionCreateCalls);
        assertEquals(3, http.translateHeaders.size());
        assertEquals(1, http.audioHeaders.size());

        String sessionKey = http.translateHeaders.get(0).get("Sec-Vtrans-Sk");
        assertNotNull("Session key must exist", sessionKey);
        assertEquals("Audio upload must use identical session key", sessionKey, http.audioHeaders.get(0).get("Sec-Vtrans-Sk"));
        assertEquals("Post-upload translate must use identical session key", sessionKey, http.translateHeaders.get(1).get("Sec-Vtrans-Sk"));
        assertEquals("Polling translate must use identical session key", sessionKey, http.translateHeaders.get(2).get("Sec-Vtrans-Sk"));

        String expectedAuth = "OAuth " + OAUTH_TOKEN;
        assertEquals(expectedAuth, http.translateHeaders.get(0).get("Authorization"));
        assertEquals(expectedAuth, http.audioHeaders.get(0).get("Authorization"));
        assertEquals(expectedAuth, http.translateHeaders.get(1).get("Authorization"));
        assertEquals(expectedAuth, http.translateHeaders.get(2).get("Authorization"));
    }

    // =============================================================================================
    // 6. After-upload translate: Only after STATUS_DONE
    // =============================================================================================
    @Test
    public void testAfterUploadTranslate_OnlyAfterStatusDone() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));
        // Server rejects upload with non-DONE status
        http.audioResponses.add(audioResponse(VotTranslationAudioResponse.STATUS_UNKNOWN, null));

        TrackingAudioSource source = new TrackingAudioSource(new byte[]{1, 2, 3});
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals("Translate after upload must NOT be executed if upload did not succeed",
                1, http.translateBodies.size());
    }

    // =============================================================================================
    // 7. After-upload translate sends firstRequest=true
    // =============================================================================================
    @Test
    public void testAfterUploadTranslate_SendsFirstRequestTrue() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/done.mp3"));

        TrackingAudioSource source = new TrackingAudioSource(new byte[]{1, 2, 3});
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(2, http.translateBodies.size());
        assertTrue("Initial translate must have firstRequest=true (wire tag 5 varint 1)",
                hasVarintField(http.translateBodies.get(0), 5, 1));
        assertTrue("Post-upload translate must have firstRequest=true (wire tag 5 varint 1)",
                hasVarintField(http.translateBodies.get(1), 5, 1));
    }

    // =============================================================================================
    // 8. Regular polling sends firstRequest=true
    // =============================================================================================
    @Test
    public void testRegularPolling_SendsFirstRequestTrue() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 60, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 20, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/done.mp3"));

        TrackingAudioSource source = new TrackingAudioSource(new byte[]{1, 2, 3});
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(4, http.translateBodies.size());
        for (int i = 0; i < 4; i++) {
            assertTrue("Translate request #" + (i + 1) + " must have firstRequest=true",
                    hasVarintField(http.translateBodies.get(i), 5, 1));
        }
    }

    // =============================================================================================
    // 9. WAITING -> FINISHED: Emits TYPE_WAITING then TYPE_READY
    // =============================================================================================
    @Test
    public void testWaitingToFinished_EmitsWaitingThenReady() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 45, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 45, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/final.mp3"));

        TrackingAudioSource source = new TrackingAudioSource(new byte[]{1, 2, 3});
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(45, progress.get(0).remainingTimeSec);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
        assertEquals("https://example.invalid/final.mp3", progress.get(1).audioUrl);
    }

    // =============================================================================================
    // 10. Missing source: Controlled error
    // =============================================================================================
    @Test
    public void testMissingSource_ControlledError() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));

        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        // Provider returns null
        client.setAudioSourceProvider(url -> null);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO, progress.get(0).message);
        assertTrue("No audio upload attempt should be made when source is missing", http.audioBodies.isEmpty());
    }

    // =============================================================================================
    // 11. Empty source: Controlled error
    // =============================================================================================
    @Test
    public void testEmptySource_ControlledError() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));

        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        // Source returns 0 bytes
        client.setAudioSourceProvider(url -> VotAudioSource.fromBytes(new byte[0]));

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO, progress.get(0).message);
        assertTrue("Zero-byte payload must not be uploaded", http.audioBodies.isEmpty());
    }

    // =============================================================================================
    // 12. Expired media URL: Controlled error
    // =============================================================================================
    @Test
    public void testExpiredMediaUrl_ControlledError() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));

        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> new VotAudioSource() {
            @Override
            public void open() throws IOException {
                throw new IOException("HTTP 403 Forbidden: Expired media URL");
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return -1;
            }

            @Override
            public long getContentLength() {
                return 1000;
            }

            @Override
            public void close() {}
        });

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO, progress.get(0).message);
        assertTrue("No audio upload attempt should be made if source open fails", http.audioBodies.isEmpty());
    }

    // =============================================================================================
    // 13. Audio upload rejection: Controlled error
    // =============================================================================================
    @Test
    public void testAudioUploadRejection_ControlledError() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));
        // Server throws HTTP 500 on audio upload
        http.throwErrorOnAudioUpload = new VotHttpException(500, "Internal Server Error", 10);

        TrackingAudioSource source = new TrackingAudioSource(new byte[]{1, 2, 3});
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_SERVER_UNAVAILABLE, progress.get(0).message);
    }

    // =============================================================================================
    // 14. Upload cancellation: No further requests
    // =============================================================================================
    @Test
    public void testUploadCancellation_NoFurtherRequests() throws Exception {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));

        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch canProceed = new CountDownLatch(1);

        BlockingAudioSource blockingSource = new BlockingAudioSource(readStarted, canProceed);
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> blockingSource);

        List<VotProgress> received = new ArrayList<>();
        Disposable disposable = client.observeTranslation(VIDEO_URL, 300).subscribe(received::add);

        // Wait until upload read begins
        assertTrue(readStarted.await(5, TimeUnit.SECONDS));

        // Dispose subscription
        disposable.dispose();
        canProceed.countDown();

        Thread.sleep(100);

        // Verify active uploader was cancelled and source closed
        assertTrue("Blocking source must be closed on cancellation", blockingSource.closed.get());
        assertEquals("Post-upload translate must not execute after cancellation", 1, http.translateBodies.size());
    }

    // =============================================================================================
    // 15. Video generation changes: Stale upload cancelled
    // =============================================================================================
    @Test
    public void testVideoGenerationChanges_StaleUploadCancelled() throws Exception {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/done_new.mp3"));

        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch canProceed = new CountDownLatch(1);
        BlockingAudioSource staleSource = new BlockingAudioSource(readStarted, canProceed);

        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);

        // Subscription 1 (stale generation)
        client.setAudioSourceProvider(url -> staleSource);
        Disposable sub1 = client.observeTranslation(VIDEO_URL, 300).subscribe();

        assertTrue(readStarted.await(5, TimeUnit.SECONDS));

        // Video change: dispose sub 1, start sub 2
        sub1.dispose();
        canProceed.countDown();

        assertTrue(staleSource.closed.get());

        // Subscription 2 (new generation)
        TrackingAudioSource newSource = new TrackingAudioSource(new byte[]{9, 9, 9});
        client.setAudioSourceProvider(url -> newSource);
        List<VotProgress> progress2 = client.observeTranslation("https://www.youtube.com/watch?v=NEW_VIDEO", 300).toList().blockingGet();

        assertEquals(1, progress2.size());
        assertEquals(VotProgress.TYPE_READY, progress2.get(0).type);
    }

    // =============================================================================================
    // 16. Repeated AUDIO_REQUESTED: No infinite loop
    // =============================================================================================
    @Test
    public void testRepeatedAudioRequested_TerminatesWithoutInfiniteLoop() {
        MockHttp http = new MockHttp();
        // 1. Initial translate -> STATUS_AUDIO_REQUESTED
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));
        // 2. Post-upload translate -> STATUS_AUDIO_REQUESTED again!
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));

        TrackingAudioSource source = new TrackingAudioSource(new byte[]{1, 2, 3});
        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        client.setAudioSourceProvider(url -> source);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO, progress.get(0).message);
        assertEquals("Audio upload must be attempted at most once", 1, http.audioBodies.size());
        assertEquals("Translate must be called at most twice (initial + post-upload)", 2, http.translateBodies.size());
    }

    // =============================================================================================
    // 17. HTTP 400: Single terminal error
    // =============================================================================================
    @Test
    public void testHttp400_SingleTerminalError() {
        MockHttp http = new MockHttp();
        http.throwHttpErrorOnTranslateAttempt = 1;
        http.httpErrorCodeToThrow = 400;

        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false);
        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_GENERIC, progress.get(0).message);
        assertEquals("HTTP 400 must NOT be retried", 1, http.translateBodies.size());
    }

    // =============================================================================================
    // 18. Original Russian audio: Not incorrectly excluded
    // =============================================================================================
    @Test
    public void testOriginalRussianAudio_NotIncorrectlyExcluded() {
        // Case A: Video has ONLY Russian audio -> Must NOT be excluded
        TestMediaFormat ruOriginal = new TestMediaFormat("https://cdn.example.invalid/ru_orig.webm", "audio/webm", "64000", "1000", "ru", null, false);
        List<MediaFormat> onlyRussian = Collections.singletonList(ruOriginal);
        MediaFormat selectedA = VotMediaFormatSelector.selectBestAudioFormat(onlyRussian);
        assertNotNull("Russian audio must be selected when no foreign tracks exist", selectedA);
        assertEquals("ru", selectedA.getLanguage());

        // Case B: Video has English original and Russian dubbed track -> Select English original
        TestMediaFormat enOriginal = new TestMediaFormat("https://cdn.example.invalid/en_orig.webm", "audio/webm", "64000", "1000", "en", null, false);
        TestMediaFormat ruDubbed = new TestMediaFormat("https://cdn.example.invalid/ru_dubbed.webm", "audio/webm", "64000", "1000", "ru", "ru-dubbed", false);
        List<MediaFormat> multilingual = Arrays.asList(ruDubbed, enOriginal);
        MediaFormat selectedB = VotMediaFormatSelector.selectBestAudioFormat(multilingual);
        assertNotNull(selectedB);
        assertEquals("en", selectedB.getLanguage());
    }

    // =============================================================================================
    // 19. Multi-part chunked audio upload wire verification
    // =============================================================================================
    @Test
    public void testMultipartChunkedAudioUpload_WireVerification() throws Exception {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, TRANSLATION_ID, null));
        // Chunk 0 -> STATUS_WAITING_CHUNKS (expects chunk 1)
        http.audioResponses.add(audioResponse(VotTranslationAudioResponse.STATUS_WAITING_CHUNKS, Collections.singletonList("1")));
        // Chunk 1 -> STATUS_DONE
        http.audioResponses.add(audioResponse(VotTranslationAudioResponse.STATUS_DONE, null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0,
                TRANSLATION_ID, "https://example.invalid/multipart_done.mp3"));

        // 1500 bytes audio data with chunk size 1024 => 2 chunks (chunk 0: 1024 intermediate, chunk 1: 476 final)
        byte[] audioData = new byte[1500];
        Arrays.fill(audioData, (byte) 77);
        TrackingAudioSource source = new TrackingAudioSource(audioData);

        VotClient client = new VotClient(null, http, null, new ImmediateWaitStrategy(), false) {
            @Override
            void handleAudioRequested(String youtubeUrl, long durationSec, String translationId,
                                      boolean useLively, String requestOAuthToken, VotAudioSource s)
                    throws IOException, VotException {
                ensureSession();
                requestFailAudioDirect(youtubeUrl);
                VotAudioUploader uploader = new VotAudioUploader(http, 1024);
                setActiveAudioUploaderDirect(uploader);
                try {
                    VotTranslationAudioResponse resp = uploader.uploadAudio(
                            youtubeUrl, translationId, "smarttube-chunk-test", getSessionDirect(), requestOAuthToken, s);
                    if (resp == null || resp.status != VotTranslationAudioResponse.STATUS_DONE) {
                        throw new VotException("Upload incomplete");
                    }
                } finally {
                    setActiveAudioUploaderDirect(null);
                }
            }

            private void requestFailAudioDirect(String youtubeUrl) throws IOException, VotException {
                String json = "{\"video_url\":\"" + youtubeUrl.replace("\"", "\\\"") + "\"}";
                http.putJson("/video-translation/fail-audio-js", json,
                        VotHeaders.simpleTranslate(json.getBytes(StandardCharsets.UTF_8)));
            }

            private void setActiveAudioUploaderDirect(VotAudioUploader uploader) {
                try {
                    java.lang.reflect.Field f = VotClient.class.getDeclaredField("mActiveAudioUploader");
                    f.setAccessible(true);
                    f.set(this, uploader);
                } catch (Exception ignored) {}
            }

            private VotSession getSessionDirect() {
                try {
                    java.lang.reflect.Field f = VotClient.class.getDeclaredField("mSession");
                    f.setAccessible(true);
                    return (VotSession) f.get(this);
                } catch (Exception ignored) {
                    return null;
                }
            }
        };
        client.setAudioSourceProvider(url -> source);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_READY, progress.get(0).type);
        assertEquals("https://example.invalid/multipart_done.mp3", progress.get(0).audioUrl);

        // Verify multiple chunk requests sent
        assertEquals("Multi-part upload should send exactly 2 chunks", 2, http.audioBodies.size());

        // Inspect chunk 0: partialAudioInfo (field 4)
        ParsedWire chunk0 = ParsedWire.parse(http.audioBodies.get(0));
        assertTrue("Chunk 0 must have partialAudioInfo (field 4)", chunk0.hasField(4));
        ParsedWire part0 = ParsedWire.parse(chunk0.getBytes(4));
        assertEquals("smarttube-chunk-test", part0.getString(3)); // fileId
        ParsedWire buf0 = ParsedWire.parse(part0.getBytes(1));
        assertEquals(0, buf0.getInt(1)); // chunkId = 0
        assertEquals(1024, buf0.getBytes(2).length);

        // Inspect chunk 1: partialAudioInfo (field 4)
        ParsedWire chunk1 = ParsedWire.parse(http.audioBodies.get(1));
        assertTrue("Chunk 1 must have partialAudioInfo (field 4)", chunk1.hasField(4));
        ParsedWire part1 = ParsedWire.parse(chunk1.getBytes(4));
        assertEquals("smarttube-chunk-test", part1.getString(3)); // fileId
        assertEquals(2, part1.getInt(2)); // audioPartsLength = 2 (total parts)
        ParsedWire buf1 = ParsedWire.parse(part1.getBytes(1));
        assertEquals(1, buf1.getInt(1)); // chunkId = 1
        assertEquals(476, buf1.getBytes(2).length);
    }

    // =============================================================================================
    // Helpers, Mocks, and Sources
    // =============================================================================================

    private static class TrackingAudioSource implements VotAudioSource {
        final byte[] data;
        final AtomicBoolean opened = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);
        private ByteArrayInputStream stream;

        TrackingAudioSource(byte[] data) {
            this.data = data;
        }

        @Override
        public void open() {
            opened.set(true);
            stream = new ByteArrayInputStream(data);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (stream == null) return -1;
            return stream.read(buffer, offset, length);
        }

        @Override
        public long getContentLength() {
            return data.length;
        }

        @Override
        public void close() {
            closed.set(true);
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private static class BlockingAudioSource implements VotAudioSource {
        final CountDownLatch readStarted;
        final CountDownLatch canProceed;
        final AtomicBoolean closed = new AtomicBoolean(false);

        BlockingAudioSource(CountDownLatch readStarted, CountDownLatch canProceed) {
            this.readStarted = readStarted;
            this.canProceed = canProceed;
        }

        @Override
        public void open() {}

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            readStarted.countDown();
            try {
                if (!canProceed.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Blocking timed out");
                }
            } catch (InterruptedException e) {
                throw new IOException("Interrupted");
            }
            if (closed.get()) {
                throw new IOException("Stream closed");
            }
            buffer[offset] = 42;
            return 1;
        }

        @Override
        public long getContentLength() {
            return 10;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static class TestMediaFormat implements MediaFormat {
        private final String mUrl;
        private final String mMimeType;
        private final String mBitrate;
        private final String mClen;
        private final String mLanguage;
        private final String mAudioTrackId;
        private final boolean mIsOtf;

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

    private static class ImmediateWaitStrategy implements VotClient.WaitStrategy {
        @Override
        public void waitSeconds(int sec, ObservableEmitter<?> emitter) {}
    }

    private static byte[] sessionResponse() {
        VotWireWriter writer = new VotWireWriter();
        writer.writeString(1, "synthetic-session-key-69");
        writer.writeInt32(2, 3600);
        return writer.toByteArray();
    }

    private static byte[] translationResponse(int status, int remainingTimeSec,
                                              String translationId, String audioUrl) {
        VotWireWriter writer = new VotWireWriter();
        writer.writeString(1, audioUrl);
        writer.writeInt32(4, status);
        writer.writeInt32(5, remainingTimeSec);
        writer.writeString(7, translationId);
        return writer.toByteArray();
    }

    private static byte[] audioResponse(int status, List<String> remainingChunks) {
        VotWireWriter writer = new VotWireWriter();
        writer.writeInt32(1, status);
        if (remainingChunks != null) {
            for (String rc : remainingChunks) {
                writer.writeString(2, rc);
            }
        }
        return writer.toByteArray();
    }

    private static boolean hasVarintField(byte[] data, int wantedField, int wantedValue) {
        int pos = 0;
        while (pos < data.length) {
            int[] tag = readVarint(data, pos);
            pos = tag[1];
            int field = tag[0] >>> 3;
            int wireType = tag[0] & 7;
            if (wireType == 0) {
                int[] value = readVarint(data, pos);
                pos = value[1];
                if (field == wantedField && value[0] == wantedValue) {
                    return true;
                }
            } else if (wireType == 1) {
                pos += 8;
            } else if (wireType == 2) {
                int[] length = readVarint(data, pos);
                pos = length[1] + length[0];
            } else if (wireType == 5) {
                pos += 4;
            } else {
                return false;
            }
        }
        return false;
    }

    private static int[] readVarint(byte[] data, int pos) {
        int value = 0;
        int shift = 0;
        while (pos < data.length) {
            int b = data[pos++] & 0xff;
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return new int[]{value, pos};
            }
            shift += 7;
        }
        return new int[]{-1, data.length};
    }

    private static final class MockHttp extends VotHttp {
        final List<String> events = new ArrayList<>();
        final List<Map<String, String>> translateHeaders = new ArrayList<>();
        final List<byte[]> translateBodies = new ArrayList<>();
        final List<byte[]> translateResponses = new ArrayList<>();

        final List<Map<String, String>> audioHeaders = new ArrayList<>();
        final List<byte[]> audioBodies = new ArrayList<>();
        final List<byte[]> audioResponses = new ArrayList<>();

        int sessionCreateCalls;
        int throwHttpErrorOnTranslateAttempt = -1;
        int httpErrorCodeToThrow = 500;
        IOException throwErrorOnAudioUpload = null;

        @Override
        public byte[] postProtobuf(String path, byte[] body, Map<String, String> headers) throws IOException {
            if ("/session/create".equals(path)) {
                events.add("session");
                sessionCreateCalls++;
                return sessionResponse();
            }
            if ("/video-translation/translate".equals(path)) {
                int attempt = translateBodies.size() + 1;
                events.add("translate-" + attempt);
                translateHeaders.add(new HashMap<>(headers));
                translateBodies.add(body.clone());
                if (attempt == throwHttpErrorOnTranslateAttempt) {
                    throw new VotHttpException(httpErrorCodeToThrow, "HTTP Error", 5);
                }
                if (!translateResponses.isEmpty()) {
                    return translateResponses.remove(0);
                }
                return translationResponse(VotTranslationResponse.STATUS_FINISHED, 0, "default", "https://example.invalid/default.mp3");
            }
            throw new AssertionError("Unexpected POST path: " + path);
        }

        @Override
        public byte[] putJson(String path, String json, Map<String, String> headers) {
            if ("/video-translation/fail-audio-js".equals(path)) {
                events.add("fail-audio");
                return "{\"status\":1}".getBytes(StandardCharsets.UTF_8);
            }
            throw new AssertionError("Unexpected JSON path: " + path);
        }

        @Override
        public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers, CallHolder callHolder) throws IOException {
            if ("/video-translation/audio".equals(path)) {
                events.add("audio");
                audioHeaders.add(new HashMap<>(headers));
                audioBodies.add(body.clone());
                if (throwErrorOnAudioUpload != null) {
                    throw throwErrorOnAudioUpload;
                }
                if (!audioResponses.isEmpty()) {
                    return audioResponses.remove(0);
                }
                return audioResponse(VotTranslationAudioResponse.STATUS_DONE, null);
            }
            throw new AssertionError("Unexpected PUT path: " + path);
        }

        @Override
        public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers) throws IOException {
            return putProtobuf(path, body, headers, null);
        }
    }

    private static class ParsedWire {
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

        final List<Field> fields;

        ParsedWire(List<Field> fields) {
            this.fields = fields;
        }

        static ParsedWire parse(byte[] data) {
            List<Field> list = new ArrayList<>();
            int pos = 0;
            while (pos < data.length) {
                int[] tagAndWire = readVarint(data, pos);
                pos = tagAndWire[1];
                int key = tagAndWire[0];
                int tag = key >>> 3;
                int wireType = key & 7;

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
    }
}

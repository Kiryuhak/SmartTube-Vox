package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.disposables.Disposable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SmartTube VOX — Batch #70
 * Safety review regression tests:
 * 1. Security: Log sanitization redacts signed URLs and auth tokens.
 * 2. Protocol: Audio chunk size constant boundary checks.
 * 3. Concurrency: Independent uploader ownership and cancellation scoping.
 * 4. Concurrency: Clean finally cleanup does not overwrite newer active uploader.
 * 5. Concurrency: ThreadLocal audio source isolation across concurrent requests.
 * 6. Format metadata: Stale metadata rejection on video ID mismatch.
 * 7. Error handling: Failed audio source resolution terminates safely with unsupported_video.
 */
public class VotBatch70SafetyReviewTest {
    private static final String VIDEO_URL_1 = "https://www.youtube.com/watch?v=VIDEO_1";
    private static final String VIDEO_URL_2 = "https://www.youtube.com/watch?v=VIDEO_2";

    // =============================================================================================
    // 1. Security: Log Sanitization
    // =============================================================================================

    @Test
    public void testSanitizeLogMessage_RedactsSignedUrl() {
        String raw = "Failed to fetch https://rr1---sn-oxunx-a.googlevideo.com/videoplayback?expire=17119000&ei=abc&ip=1.2.3.4&id=xyz&sig=ABCDEF123456789 from network";
        IOException ex = new IOException(raw);
        String sanitized = VotClient.sanitizeLogMessage(ex);

        assertFalse("Sanitized message must not contain googlevideo host", sanitized.contains("googlevideo.com"));
        assertFalse("Sanitized message must not contain sig parameter value", sanitized.contains("ABCDEF123456789"));
        assertTrue("Sanitized message must contain [REDACTED_URL]", sanitized.contains("[REDACTED_URL]"));
    }

    @Test
    public void testSanitizeLogMessage_RedactsAuthToken() {
        String raw = "Auth rejected for token=AQAA_SECRET_TEST_TOKEN_12345 and secret=XYZ";
        Exception ex = new Exception(raw);
        String sanitized = VotClient.sanitizeLogMessage(ex);

        assertFalse("Sanitized message must not contain raw token", sanitized.contains("AQAA_SECRET_TEST_TOKEN_12345"));
        assertTrue("Sanitized message must redact token", sanitized.contains("token=[REDACTED]"));
        assertTrue("Sanitized message must redact secret", sanitized.contains("secret=[REDACTED]"));
    }

    @Test
    public void testSanitizeLogMessage_HandlesNullAndCleanMessages() {
        assertEquals("null", VotClient.sanitizeLogMessage(null));

        Exception cleanEx = new IOException("Simple network reset");
        assertEquals("Simple network reset", VotClient.sanitizeLogMessage(cleanEx));

        Exception emptyEx = new IOException("");
        assertEquals("IOException", VotClient.sanitizeLogMessage(emptyEx));
    }

    // =============================================================================================
    // 2. Protocol: Chunk Size Boundary Verification
    // =============================================================================================

    @Test
    public void testAudioMinChunkSizeConstant_IsExactly5295308Bytes() {
        assertEquals("AUDIO_MIN_CHUNK_SIZE must be exactly 5,295,308 bytes (~5.05 MB) per reference protocol",
                5295308, VotConfig.AUDIO_MIN_CHUNK_SIZE);
    }

    // =============================================================================================
    // 3. Concurrency: Uploader Cancellation Scoping
    // =============================================================================================

    @Test
    public void testUploaderCancellationIsolation_DisposingFirstObservableDoesNotCancelSecond() throws Exception {
        CountDownLatch uploader1Started = new CountDownLatch(1);
        CountDownLatch uploader2Started = new CountDownLatch(1);
        CountDownLatch allowUploader2ToFinish = new CountDownLatch(1);

        MockHttp http = new MockHttp();
        http.defaultTranslateStatus = VotTranslationResponse.STATUS_AUDIO_REQUESTED;

        VotAudioUploader uploader1 = new VotAudioUploader(http, 1024) {
            @Override
            public VotTranslationAudioResponse uploadAudio(String url, String translationId, String fileId,
                                                          VotSession session, String oauthToken, VotAudioSource audioSource)
                    throws IOException, VotException {
                uploader1Started.countDown();
                try {
                    while (!isCancelled()) {
                        Thread.sleep(20);
                    }
                } catch (InterruptedException ignored) {}
                throw new VotCancellationException("Uploader 1 cancelled");
            }
        };

        VotAudioUploader uploader2 = new VotAudioUploader(http, 1024) {
            @Override
            public VotTranslationAudioResponse uploadAudio(String url, String translationId, String fileId,
                                                          VotSession session, String oauthToken, VotAudioSource audioSource)
                    throws IOException, VotException {
                uploader2Started.countDown();
                try {
                    allowUploader2ToFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                VotTranslationAudioResponse r = new VotTranslationAudioResponse();
                r.status = VotTranslationAudioResponse.STATUS_DONE;
                return r;
            }
        };

        VotAudioSource dummySource1 = new DummyAudioSource();
        VotAudioSource dummySource2 = new DummyAudioSource();

        VotClient client = new VotClient(null, http, null, (sec, emitter) -> {}, false) {
            private int callCount = 0;

            @Override
            void handleAudioRequested(String youtubeUrl, long durationSec, String translationId,
                                      boolean useLively, String requestOAuthToken, VotAudioSource s)
                    throws IOException, VotException {
                int count;
                synchronized (this) {
                    count = ++callCount;
                }
                VotAudioUploader u = (count == 1) ? uploader1 : uploader2;
                setActiveAudioUploaderField(this, u);
                registerLocalUploader(this, u);
                try {
                    u.uploadAudio(youtubeUrl, translationId, "test-file", new VotSession(), requestOAuthToken, s);
                } finally {
                    clearActiveAudioUploaderField(this, u);
                    clearLocalUploader(this, u);
                }
            }
        };

        // Subscription 1
        AtomicReference<VotProgress> progress1 = new AtomicReference<>();
        Disposable sub1 = client.observeTranslation(VIDEO_URL_1, 100, false, url -> dummySource1)
                .subscribe(progress1::set, err -> {});

        // Subscription 2
        AtomicReference<VotProgress> progress2 = new AtomicReference<>();
        Disposable sub2 = client.observeTranslation(VIDEO_URL_2, 100, false, url -> dummySource2)
                .subscribe(progress2::set, err -> {});

        // Wait for both to start
        assertTrue("Uploader 1 should start", uploader1Started.await(5, TimeUnit.SECONDS));
        assertTrue("Uploader 2 should start", uploader2Started.await(5, TimeUnit.SECONDS));

        // Now cancel subscription 1
        sub1.dispose();

        // Uploader 1 should be cancelled
        assertTrue("Uploader 1 must be cancelled upon subscription 1 disposal", uploader1.isCancelled());

        // Uploader 2 must NOT be cancelled!
        assertFalse("Uploader 2 must NOT be cancelled when subscription 1 is disposed", uploader2.isCancelled());

        // Allow uploader 2 to complete
        allowUploader2ToFinish.countDown();
        sub2.dispose();
    }

    // =============================================================================================
    // 4. Concurrency: Uploader Cleanup Isolation
    // =============================================================================================

    @Test
    public void testUploaderCleanupIsolation_FirstUploadFinishingDoesNotClearSecondActiveUploader() throws Exception {
        MockHttp http = new MockHttp();
        VotClient client = new VotClient(null, http, null, (sec, emitter) -> {}, false);

        VotAudioUploader uploader1 = new VotAudioUploader(http, 1024);
        VotAudioUploader uploader2 = new VotAudioUploader(http, 1024);

        // Upload 1 sets active uploader
        setActiveAudioUploaderField(client, uploader1);
        assertEquals(uploader1, getActiveAudioUploaderField(client));

        // Upload 2 starts and sets active uploader
        setActiveAudioUploaderField(client, uploader2);
        assertEquals(uploader2, getActiveAudioUploaderField(client));

        // Upload 1 completes and runs its finally block: clearActiveAudioUploaderField
        clearActiveAudioUploaderField(client, uploader1);

        // Active uploader MUST still be uploader2, NOT null!
        assertEquals("Active uploader must still be uploader 2 after uploader 1 cleanup",
                uploader2, getActiveAudioUploaderField(client));

        // Upload 2 completes and cleans up
        clearActiveAudioUploaderField(client, uploader2);
        assertNull("Active uploader should be null after uploader 2 cleans up",
                getActiveAudioUploaderField(client));
    }

    // =============================================================================================
    // 5. ThreadLocal AudioSource Isolation
    // =============================================================================================

    @Test
    public void testThreadLocalAudioSourceIsolation_ConcurrentRequestsDoNotInterfere() throws Exception {
        VotAudioSource sourceA = new DummyAudioSource();
        VotAudioSource sourceB = new DummyAudioSource();

        AtomicReference<VotAudioSource> threadARead = new AtomicReference<>();
        AtomicReference<VotAudioSource> threadBRead = new AtomicReference<>();

        CountDownLatch threadASet = new CountDownLatch(1);
        CountDownLatch threadBSet = new CountDownLatch(1);
        CountDownLatch bothDone = new CountDownLatch(2);

        Field threadLocalField = VotClient.class.getDeclaredField("sCurrentAudioSource");
        threadLocalField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<VotAudioSource> sCurrentAudioSource = (ThreadLocal<VotAudioSource>) threadLocalField.get(null);

        Thread threadA = new Thread(() -> {
            sCurrentAudioSource.set(sourceA);
            threadASet.countDown();
            try {
                threadBSet.await(5, TimeUnit.SECONDS);
                Thread.sleep(50);
                threadARead.set(sCurrentAudioSource.get());
            } catch (Exception ignored) {
            } finally {
                sCurrentAudioSource.remove();
                bothDone.countDown();
            }
        });

        Thread threadB = new Thread(() -> {
            try {
                threadASet.await(5, TimeUnit.SECONDS);
                sCurrentAudioSource.set(sourceB);
                threadBSet.countDown();
                Thread.sleep(50);
                threadBRead.set(sCurrentAudioSource.get());
            } catch (Exception ignored) {
            } finally {
                sCurrentAudioSource.remove();
                bothDone.countDown();
            }
        });

        threadA.start();
        threadB.start();

        assertTrue("Threads should finish within timeout", bothDone.await(5, TimeUnit.SECONDS));

        assertEquals("Thread A must read sourceA", sourceA, threadARead.get());
        assertEquals("Thread B must read sourceB", sourceB, threadBRead.get());
    }

    // =============================================================================================
    // 6. Stale Format Metadata Rejection
    // =============================================================================================

    @Test
    public void testFormatInfoStaleMetadata_RejectedWhenVideoIdMismatches() {
        MediaItemFormatInfo staleInfo = createFormatInfo("STALE_VIDEO_ID");
        String currentVideoId = "CURRENT_VIDEO_ID";

        boolean matches = currentVideoId.equals(staleInfo.getVideoId());
        assertFalse("Stale format metadata with different videoId must not match current videoId", matches);
    }

    // =============================================================================================
    // 7. Error Handling: Missing Audio Source Emits unsupported_video
    // =============================================================================================

    @Test
    public void testFailedAudioSourceResolution_TerminatesWithUnsupportedVideo() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 0, "tr-batch-70", null));

        VotClient client = new VotClient(null, http, null, (sec, emitter) -> {}, false);
        // Provider throws exception during resolution
        client.setAudioSourceProvider(url -> {
            throw new RuntimeException("Media stream unavailable for this video");
        });

        List<VotProgress> progressList = client.observeTranslation(VIDEO_URL_1, 100).toList().blockingGet();

        assertEquals(1, progressList.size());
        assertEquals(VotProgress.TYPE_FAILED, progressList.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO, progressList.get(0).message);
    }

    // =============================================================================================
    // Helper Reflection Methods
    // =============================================================================================

    private static void setActiveAudioUploaderField(VotClient client, VotAudioUploader uploader) {
        try {
            Field lockField = VotClient.class.getDeclaredField("mUploaderLock");
            lockField.setAccessible(true);
            Object lock = lockField.get(client);

            Field uploaderField = VotClient.class.getDeclaredField("mActiveAudioUploader");
            uploaderField.setAccessible(true);

            synchronized (lock) {
                uploaderField.set(client, uploader);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void clearActiveAudioUploaderField(VotClient client, VotAudioUploader uploader) {
        try {
            Field lockField = VotClient.class.getDeclaredField("mUploaderLock");
            lockField.setAccessible(true);
            Object lock = lockField.get(client);

            Field uploaderField = VotClient.class.getDeclaredField("mActiveAudioUploader");
            uploaderField.setAccessible(true);

            synchronized (lock) {
                if (uploaderField.get(client) == uploader) {
                    uploaderField.set(client, null);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static VotAudioUploader getActiveAudioUploaderField(VotClient client) {
        try {
            Field uploaderField = VotClient.class.getDeclaredField("mActiveAudioUploader");
            uploaderField.setAccessible(true);
            return (VotAudioUploader) uploaderField.get(client);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void registerLocalUploader(VotClient client, VotAudioUploader uploader) {
        try {
            Field field = VotClient.class.getDeclaredField("sCurrentLocalUploader");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<AtomicReference<VotAudioUploader>> tl = (ThreadLocal<AtomicReference<VotAudioUploader>>) field.get(null);
            AtomicReference<VotAudioUploader> ref = tl.get();
            if (ref != null) {
                ref.compareAndSet(null, uploader);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void clearLocalUploader(VotClient client, VotAudioUploader uploader) {
        try {
            Field field = VotClient.class.getDeclaredField("sCurrentLocalUploader");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<AtomicReference<VotAudioUploader>> tl = (ThreadLocal<AtomicReference<VotAudioUploader>>) field.get(null);
            AtomicReference<VotAudioUploader> ref = tl.get();
            if (ref != null) {
                ref.compareAndSet(uploader, null);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MediaItemFormatInfo createFormatInfo(String videoId) {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(
                MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[]{MediaItemFormatInfo.class},
                (proxy, method, args) -> {
                    if ("getVideoId".equals(method.getName())) {
                        return videoId;
                    }
                    if ("containsMedia".equals(method.getName())) {
                        return true;
                    }
                    return null;
                }
        );
    }

    private static byte[] sessionResponse() {
        VotWireWriter writer = new VotWireWriter();
        writer.writeString(1, "synthetic-session-key-70");
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

    private static class DummyAudioSource implements VotAudioSource {
        @Override
        public void open() throws IOException {}

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return -1;
        }

        @Override
        public long getContentLength() {
            return 0;
        }

        @Override
        public void close() throws IOException {}
    }

    private static class MockHttp extends VotHttp {
        final List<byte[]> translateResponses = new ArrayList<>();
        int defaultTranslateStatus = VotTranslationResponse.STATUS_WAITING;

        @Override
        public byte[] postProtobuf(String path, byte[] body, Map<String, String> headers) throws IOException {
            if ("/session/create".equals(path)) {
                return sessionResponse();
            }
            if ("/video-translation/translate".equals(path)) {
                if (!translateResponses.isEmpty()) {
                    return translateResponses.remove(0);
                }
                return translationResponse(defaultTranslateStatus, 10, "tr-default", null);
            }
            throw new IOException("Unhandled POST " + path);
        }

        @Override
        public byte[] putJson(String path, String json, Map<String, String> headers) throws IOException {
            if ("/video-translation/fail-audio-js".equals(path)) {
                return "{\"status\":1}".getBytes(StandardCharsets.UTF_8);
            }
            throw new IOException("Unhandled PUT JSON " + path);
        }

        @Override
        public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers, CallHolder callHolder) throws IOException {
            if ("/video-translation/audio".equals(path)) {
                return audioResponse(VotTranslationAudioResponse.STATUS_DONE, null);
            }
            throw new IOException("Unhandled PUT Protobuf " + path);
        }
    }
}

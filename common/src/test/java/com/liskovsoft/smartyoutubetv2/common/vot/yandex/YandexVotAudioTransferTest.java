/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class YandexVotAudioTransferTest {

    private static class FakeReader implements YandexVotAudioStreamReader {
        private final byte[] sourceData;
        private boolean throwOnRead = false;
        private boolean returnTruncated = false;
        private Exception exceptionToThrow;

        public FakeReader(byte[] sourceData) {
            this.sourceData = sourceData;
        }

        public void setThrowOnRead(Exception e) {
            this.throwOnRead = true;
            this.exceptionToThrow = e;
        }

        public void setReturnTruncated(boolean truncated) {
            this.returnTruncated = truncated;
        }

        @NonNull
        @Override
        public byte[] readRange(long startByte, int length) throws Exception {
            if (throwOnRead) {
                throw exceptionToThrow != null ? exceptionToThrow : new RuntimeException("Read error");
            }
            if (returnTruncated) {
                return new byte[Math.max(1, length - 10)];
            }
            int start = (int) startByte;
            int actualLength = Math.min(length, sourceData.length - start);
            byte[] chunk = new byte[actualLength];
            System.arraycopy(sourceData, start, chunk, 0, actualLength);
            return chunk;
        }
    }

    private static class FakeTransport implements YandexVotAudioUploadTransport {
        private final List<Integer> uploadedChunks = new ArrayList<>();
        private final List<Integer> uploadedPartSizes = new ArrayList<>();
        private int failureCountBeforeSuccess = 0;
        private int currentFailures = 0;
        private UploadOutcome permanentOutcome = null;
        private Runnable onUploadAction = null;

        public void setFailureCountBeforeSuccess(int count) {
            this.failureCountBeforeSuccess = count;
        }

        public void setPermanentOutcome(UploadOutcome outcome) {
            this.permanentOutcome = outcome;
        }

        public void setOnUploadAction(Runnable action) {
            this.onUploadAction = action;
        }

        public List<Integer> getUploadedChunks() {
            return uploadedChunks;
        }

        public List<Integer> getUploadedPartSizes() {
            return uploadedPartSizes;
        }

        @Override
        public UploadOutcome uploadPart(
                @NonNull String videoUrl,
                @NonNull String translationId,
                @NonNull String fileId,
                int totalParts,
                int version,
                int chunkId,
                @NonNull byte[] audioData
        ) {
            if (onUploadAction != null) {
                onUploadAction.run();
            }

            if (permanentOutcome != null) {
                return permanentOutcome;
            }

            if (currentFailures < failureCountBeforeSuccess) {
                currentFailures++;
                return UploadOutcome.TRANSIENT_ERROR;
            }

            uploadedChunks.add(chunkId);
            uploadedPartSizes.add(audioData.length);
            return UploadOutcome.SUCCESS;
        }
    }

    private static class RecordingListener implements YandexVotAudioTransfer.TransferListener {
        private boolean prepared = false;
        private long preparedBytes = -1;
        private int preparedParts = -1;
        private final List<Integer> startedParts = new ArrayList<>();
        private final List<Integer> completedParts = new ArrayList<>();
        private boolean completed = false;
        private long completedBytes = -1;
        private YandexVotAudioResult errorResult = null;
        private boolean cancelled = false;

        @Override
        public void onPreparing(long totalBytes, int totalParts) {
            this.prepared = true;
            this.preparedBytes = totalBytes;
            this.preparedParts = totalParts;
        }

        @Override
        public void onPartStarted(int partIndex, int totalParts, long startByte, int partLength) {
            startedParts.add(partIndex);
        }

        @Override
        public void onPartCompleted(int partIndex, int totalParts) {
            completedParts.add(partIndex);
        }

        @Override
        public void onCompleted(long totalBytesTransferred) {
            this.completed = true;
            this.completedBytes = totalBytesTransferred;
        }

        @Override
        public void onError(@NonNull YandexVotAudioResult error) {
            this.errorResult = error;
        }

        @Override
        public void onCancelled() {
            this.cancelled = true;
        }
    }

    // =========================================================================
    // Selection Tests (1, 2, 3)
    // =========================================================================

    @Test
    public void test1_SelectsSupportedAudioOnlySource() {
        YandexVotAudioSourceSelector.Candidate videoFormat = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/video", "video/mp4", "avc1", 500000, 10000000, 137, true, false, "main", "en"
        );
        YandexVotAudioSourceSelector.Candidate audioFormat = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/audio", "audio/mp4", "mp4a.40.2", 128000, 5000000, 140, true, false, "main", "en"
        );

        YandexVotAudioSource selected = YandexVotAudioSourceSelector.selectBestAudioSource(
                Arrays.asList(videoFormat, audioFormat)
        );

        assertNotNull(selected);
        assertEquals("audio/mp4", selected.getMimeType());
        assertEquals(140, selected.getItag());
        assertEquals("vid1", selected.getVideoId());
    }

    @Test
    public void test2_PrefersLowerBitrateOpus() {
        YandexVotAudioSourceSelector.Candidate aacLow = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/aac_low", "audio/mp4", "mp4a", 50000, 3000000, 139, true, false, "main", "en"
        );
        YandexVotAudioSourceSelector.Candidate opusLow = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/opus_low", "audio/webm", "opus", 50000, 3000000, 249, true, false, "main", "en"
        );
        YandexVotAudioSourceSelector.Candidate opusHigh = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/opus_high", "audio/webm", "opus", 160000, 9000000, 251, true, false, "main", "en"
        );

        YandexVotAudioSource selected = YandexVotAudioSourceSelector.selectBestAudioSource(
                Arrays.asList(aacLow, opusHigh, opusLow)
        );

        assertNotNull(selected);
        // At 50k equal bitrate, Opus (249) is preferred over AAC (139)
        assertEquals(249, selected.getItag());
        assertTrue(selected.isOpus());
        assertEquals(50000, selected.getBitrate());
    }

    @Test
    public void test3_RejectsSabrAndUnsupportedSource() {
        YandexVotAudioSourceSelector.Candidate sabrFormat = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/sabr_audio", "audio/webm", "opus", 50000, 3000000, 249, true, true, "main", "en"
        );
        YandexVotAudioSourceSelector.Candidate noRangeFormat = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/no_range", "audio/webm", "opus", 50000, 3000000, 249, false, false, "main", "en"
        );

        YandexVotAudioSource selected = YandexVotAudioSourceSelector.selectBestAudioSource(
                Arrays.asList(sabrFormat, noRangeFormat)
        );

        assertNull(selected);
    }

    // =========================================================================
    // Part Range & Arithmetic Tests (4, 5, 6, 7, 8)
    // =========================================================================

    @Test
    public void test4_CorrectPartCount() {
        long partSize = YandexVotAudioParts.PART_SIZE_BYTES; // 5,295,308 bytes
        assertEquals(0, YandexVotAudioParts.partCount(0));
        assertEquals(1, YandexVotAudioParts.partCount(100));
        assertEquals(1, YandexVotAudioParts.partCount(partSize));
        assertEquals(2, YandexVotAudioParts.partCount(partSize + 1));
        assertEquals(3, YandexVotAudioParts.partCount(partSize * 2 + 500));
    }

    @Test
    public void test5_CorrectFirstRange() {
        long fileSize = YandexVotAudioParts.PART_SIZE_BYTES * 3 + 1024;
        List<YandexVotAudioParts.Part> parts = YandexVotAudioParts.parts(fileSize);

        assertEquals(4, parts.size());
        assertEquals(0, parts.get(0).start());
        assertEquals(YandexVotAudioParts.PART_SIZE_BYTES, parts.get(0).length());
    }

    @Test
    public void test6_CorrectMiddleRange() {
        long partSize = YandexVotAudioParts.PART_SIZE_BYTES;
        long fileSize = partSize * 3 + 1024;
        List<YandexVotAudioParts.Part> parts = YandexVotAudioParts.parts(fileSize);

        assertEquals(partSize, parts.get(1).start());
        assertEquals(partSize, parts.get(1).length());

        assertEquals(partSize * 2, parts.get(2).start());
        assertEquals(partSize, parts.get(2).length());
    }

    @Test
    public void test7_CorrectLastRange() {
        long partSize = YandexVotAudioParts.PART_SIZE_BYTES;
        long fileSize = partSize * 2 + 777;
        List<YandexVotAudioParts.Part> parts = YandexVotAudioParts.parts(fileSize);

        assertEquals(3, parts.size());
        assertEquals(partSize * 2, parts.get(2).start());
        assertEquals(777, parts.get(2).length());
    }

    @Test
    public void test8_ExactPartSizeMultiple() {
        long partSize = YandexVotAudioParts.PART_SIZE_BYTES;
        long fileSize = partSize * 2;
        List<YandexVotAudioParts.Part> parts = YandexVotAudioParts.parts(fileSize);

        assertEquals(2, parts.size());
        assertEquals(0, parts.get(0).start());
        assertEquals(partSize, parts.get(0).length());
        assertEquals(partSize, parts.get(1).start());
        assertEquals(partSize, parts.get(1).length());
    }

    // =========================================================================
    // Transfer, Ordering, and Progress Tests (9, 10, 11, 12, 13, 14, 15, 16)
    // =========================================================================

    @Test
    public void test9_OrderedUpload() {
        int partSize = YandexVotAudioParts.PART_SIZE_BYTES;
        int totalSize = partSize * 2 + 1000;
        byte[] dummyData = new byte[totalSize];

        FakeReader reader = new FakeReader(dummyData);
        FakeTransport transport = new FakeTransport();
        YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid_order", "https://example.com/stream", "audio/webm", "opus", 50000, totalSize, 249, true, null
        );

        YandexVotAudioResult result = transfer.transfer(
                source, "https://youtube.com/watch?v=vid_order", "trans_123", "file_123", reader, transport, null
        );

        assertEquals(YandexVotAudioResult.SUCCESS, result);
        assertEquals(Arrays.asList(0, 1, 2), transport.getUploadedChunks());
        assertEquals(Arrays.asList(partSize, partSize, 1000), transport.getUploadedPartSizes());
    }

    @Test
    public void test10_ProgressCallbacks() {
        int partSize = YandexVotAudioParts.PART_SIZE_BYTES;
        int totalSize = partSize + 500;
        byte[] dummyData = new byte[totalSize];

        FakeReader reader = new FakeReader(dummyData);
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid_progress", "https://example.com/stream", "audio/webm", "opus", 50000, totalSize, 249, true, null
        );

        YandexVotAudioResult result = transfer.transfer(
                source, "https://youtube.com/watch?v=vid_progress", "trans_123", "file_123", reader, transport, listener
        );

        assertEquals(YandexVotAudioResult.SUCCESS, result);
        assertTrue(listener.prepared);
        assertEquals(totalSize, listener.preparedBytes);
        assertEquals(2, listener.preparedParts);
        assertEquals(Arrays.asList(0, 1), listener.startedParts);
        assertEquals(Arrays.asList(0, 1), listener.completedParts);
        assertTrue(listener.completed);
        assertEquals(totalSize, listener.completedBytes);
    }

    @Test
    public void test11_TransientFailureRetry() {
        int totalSize = 1000;
        byte[] dummyData = new byte[totalSize];

        FakeReader reader = new FakeReader(dummyData);
        FakeTransport transport = new FakeTransport();
        // Fail first 2 attempts, then succeed on 3rd attempt (attempt index 2 <= MAX_PART_RETRIES)
        transport.setFailureCountBeforeSuccess(2);
        YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid_retry", "https://example.com/stream", "audio/webm", "opus", 50000, totalSize, 249, true, null
        );

        YandexVotAudioResult result = transfer.transfer(
                source, "https://youtube.com/watch?v=vid_retry", "trans_123", "file_123", reader, transport, null
        );

        assertEquals(YandexVotAudioResult.SUCCESS, result);
        assertEquals(Collections.singletonList(0), transport.getUploadedChunks());
    }

    @Test
    public void test12_PermanentFailureNoRetry() {
        int totalSize = 1000;
        byte[] dummyData = new byte[totalSize];

        FakeReader reader = new FakeReader(dummyData);
        FakeTransport transport = new FakeTransport();
        transport.setPermanentOutcome(YandexVotAudioUploadTransport.UploadOutcome.PERMANENT_ERROR);
        RecordingListener listener = new RecordingListener();
        YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid_perm_fail", "https://example.com/stream", "audio/webm", "opus", 50000, totalSize, 249, true, null
        );

        YandexVotAudioResult result = transfer.transfer(
                source, "https://youtube.com/watch?v=vid_perm_fail", "trans_123", "file_123", reader, transport, listener
        );

        assertEquals(YandexVotAudioResult.UPLOAD_FAILED, result);
        assertEquals(YandexVotAudioResult.UPLOAD_FAILED, listener.errorResult);
        assertEquals(0, transport.getUploadedChunks().size());
    }

    @Test
    public void test13_CancellationBeforeTransfer() {
        byte[] dummyData = new byte[1000];
        FakeReader reader = new FakeReader(dummyData);
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();

        transfer.cancel();
        assertTrue(transfer.isCancelled());

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid_cancel_early", "https://example.com/stream", "audio/webm", "opus", 50000, 1000, 249, true, null
        );

        YandexVotAudioResult result = transfer.transfer(
                source, "https://youtube.com/watch?v=vid_cancel_early", "trans_123", "file_123", reader, transport, listener
        );

        assertEquals(YandexVotAudioResult.CANCELLED, result);
        assertTrue(listener.cancelled);
        assertEquals(0, transport.getUploadedChunks().size());
    }

    @Test
    public void test14_CancellationBetweenChunks() {
        int partSize = YandexVotAudioParts.PART_SIZE_BYTES;
        int totalSize = partSize * 3;
        byte[] dummyData = new byte[totalSize];

        FakeReader reader = new FakeReader(dummyData);
        final FakeTransport transport = new FakeTransport();
        final YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();

        RecordingListener listener = new RecordingListener() {
            @Override
            public void onPartCompleted(int partIndex, int totalParts) {
                super.onPartCompleted(partIndex, totalParts);
                if (partIndex == 0) {
                    transfer.cancel();
                }
            }
        };

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid_cancel_mid", "https://example.com/stream", "audio/webm", "opus", 50000, totalSize, 249, true, null
        );

        YandexVotAudioResult result = transfer.transfer(
                source, "https://youtube.com/watch?v=vid_cancel_mid", "trans_123", "file_123", reader, transport, listener
        );

        assertEquals(YandexVotAudioResult.CANCELLED, result);
        assertTrue(listener.cancelled);
        // Uploaded exactly chunk 0 before stopping
        assertEquals(Collections.singletonList(0), transport.getUploadedChunks());
    }

    @Test
    public void test15_IncompleteSourceReturnsFailure() {
        YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();
        YandexVotAudioResult resultZero = transfer.transfer(
                new YandexVotAudioSource("vid", "https://url", "audio/webm", "opus", 50000, 0, 249, true, null),
                "https://url", "trans", "file", new FakeReader(new byte[0]), new FakeTransport(), null
        );
        assertEquals(YandexVotAudioResult.SOURCE_UNAVAILABLE, resultZero);

        YandexVotAudioResult resultNullReader = transfer.transfer(
                new YandexVotAudioSource("vid", "https://url", "audio/webm", "opus", 50000, 1000, 249, true, null),
                "https://url", "trans", "file", null, new FakeTransport(), null
        );
        assertEquals(YandexVotAudioResult.SOURCE_UNAVAILABLE, resultNullReader);
    }

    @Test
    public void test16_ShortPartialRangeReturnsFailure() {
        byte[] dummyData = new byte[1000];
        FakeReader reader = new FakeReader(dummyData);
        reader.setReturnTruncated(true);
        FakeTransport transport = new FakeTransport();
        YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid_trunc", "https://example.com/stream", "audio/webm", "opus", 50000, 1000, 249, true, null
        );

        YandexVotAudioResult result = transfer.transfer(
                source, "https://youtube.com/watch?v=vid_trunc", "trans_123", "file_123", reader, transport, null
        );

        assertEquals(YandexVotAudioResult.SOURCE_READ_FAILED, result);
    }

    // =========================================================================
    // Security & Privacy Test (17)
    // =========================================================================

    @Test
    public void test17_NoSensitiveUrlOrHeaderInToString() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer secret_token_xyz");
        headers.put("Cookie", "session=private_session_val");
        headers.put("X-Goog-Po-Token", "potoken_secret_abc");

        YandexVotAudioSource source = new YandexVotAudioSource(
                "vid_sec", "https://googlevideo.com/videoplayback?expire=12345&sig=signed_private_sig",
                "audio/webm", "opus", 50000, 5000000, 249, true, headers
        );

        String str = source.toString();
        assertTrue(str.contains("[PROTECTED]"));
        assertFalse(str.contains("secret_token_xyz"));
        assertFalse(str.contains("private_session_val"));
        assertFalse(str.contains("potoken_secret_abc"));
        assertFalse(str.contains("signed_private_sig"));
        assertFalse(str.contains("googlevideo.com"));
    }
}

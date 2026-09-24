package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SmartTube VOX — Batch #61
 * Verifies that regular polling sends firstRequest=true (wire tag 5 varint 1)
 * across the entire translation lifecycle (Standard, Lively, post-upload, transient retries),
 * preventing Yandex HTTP 400 Bad Request.
 */
public class VotBatch61PollingProtocolTest {
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=TEST_BATCH_61";

    @Test
    public void livelyAudioUploadFollowedByMultiplePollsAllSendFirstRequestTrue() {
        MockHttp http = new MockHttp();
        // 1. Initial request -> STATUS_AUDIO_REQUESTED
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 86, "tid-61-lively", null));
        // 2. Post-upload translate -> STATUS_WAITING (remaining 86s)
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 86, "tid-61-lively", null));
        // 3. Regular poll 1 -> STATUS_WAITING (remaining 25s)
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 25, "tid-61-lively", null));
        // 4. Regular poll 2 -> STATUS_FINISHED
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0, "tid-61-lively", "https://example.invalid/final_lively.mp3"));

        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 600).toList().blockingGet();

        assertEquals(Arrays.asList(
                "session", "translate-1", "fail-audio", "audio",
                "translate-2", "translate-3", "translate-4"), http.events);

        // Verify single cryptographic session key across ALL requests
        assertEquals(1, http.sessionCreateCalls);
        assertEquals(4, http.translateHeaders.size());
        String sessionKey = http.translateHeaders.get(0).get("Sec-Vtrans-Sk");
        assertEquals(sessionKey, http.audioHeaders.get("Sec-Vtrans-Sk"));
        assertEquals(sessionKey, http.translateHeaders.get(1).get("Sec-Vtrans-Sk"));
        assertEquals(sessionKey, http.translateHeaders.get(2).get("Sec-Vtrans-Sk"));
        assertEquals(sessionKey, http.translateHeaders.get(3).get("Sec-Vtrans-Sk"));

        // ALL 4 translation requests MUST have firstRequest=true (wire tag 5 = 1)
        assertTrue("Initial translate MUST have firstRequest=true",
                hasVarintField(http.translateBodies.get(0), 5, 1));
        assertTrue("Post-upload translate MUST have firstRequest=true",
                hasVarintField(http.translateBodies.get(1), 5, 1));
        assertTrue("Regular poll 1 MUST have firstRequest=true to prevent HTTP 400",
                hasVarintField(http.translateBodies.get(2), 5, 1));
        assertTrue("Regular poll 2 MUST have firstRequest=true to prevent HTTP 400",
                hasVarintField(http.translateBodies.get(3), 5, 1));

        // Wait strategy intervals: attempt 0 (remaining=86) -> 45s, attempt 1 (remaining=25) -> DEFAULT (20s)
        assertEquals(Arrays.asList(45, 20), waits.waitsSec);

        // Progress events: WAITING (86s), WAITING (25s), READY
        assertEquals(3, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(86, progress.get(0).remainingTimeSec);
        assertEquals(VotProgress.TYPE_WAITING, progress.get(1).type);
        assertEquals(25, progress.get(1).remainingTimeSec);
        assertEquals(VotProgress.TYPE_READY, progress.get(2).type);
        assertEquals("https://example.invalid/final_lively.mp3", progress.get(2).audioUrl);
    }

    @Test
    public void standardFlowWithMultiplePollsAllSendFirstRequestTrue() {
        MockHttp http = new MockHttp();
        // 1. Initial translate -> STATUS_WAITING (remaining 60s)
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 60, "tid-61-std", null));
        // 2. Regular poll 1 -> STATUS_WAITING (remaining 15s)
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 15, "tid-61-std", null));
        // 3. Regular poll 2 -> STATUS_FINISHED
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0, "tid-61-std", "https://example.invalid/final_std.mp3"));

        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 600).toList().blockingGet();

        assertEquals(Arrays.asList("session", "translate-1", "translate-2", "translate-3"), http.events);
        assertEquals(1, http.sessionCreateCalls);

        assertTrue("Initial translate MUST have firstRequest=true",
                hasVarintField(http.translateBodies.get(0), 5, 1));
        assertTrue("Regular poll 1 MUST have firstRequest=true",
                hasVarintField(http.translateBodies.get(1), 5, 1));
        assertTrue("Regular poll 2 MUST have firstRequest=true",
                hasVarintField(http.translateBodies.get(2), 5, 1));

        assertEquals(Arrays.asList(25, 15), waits.waitsSec);
        assertEquals(3, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_WAITING, progress.get(1).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(2).type);
    }

    @Test
    public void transientErrorDuringPollingRetriesWithFirstRequestTrue() {
        MockHttp http = new MockHttp();
        // 1. Initial translate -> STATUS_WAITING (remaining 30s)
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 30, "tid-61-transient", null));
        // 2. Poll 1 throws transient 502
        // 3. Poll 1 retry -> STATUS_FINISHED
        http.throwHttpErrorOnTranslateAttempt = 2;
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0, "tid-61-transient", "https://example.invalid/retry_ok.mp3"));

        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(Arrays.asList("session", "translate-1", "translate-2", "translate-3"), http.events);
        assertTrue(hasVarintField(http.translateBodies.get(0), 5, 1));
        assertTrue(hasVarintField(http.translateBodies.get(1), 5, 1));
        assertTrue(hasVarintField(http.translateBodies.get(2), 5, 1));

        assertEquals(Arrays.asList(25, 5), waits.waitsSec);
        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
    }

    // --- Helpers and Mocks ---

    private static byte[] sessionResponse() {
        VotWireWriter writer = new VotWireWriter();
        writer.writeString(1, "synthetic-session-key-61");
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

    private static final class RecordingWaitStrategy implements VotClient.WaitStrategy {
        final List<Integer> waitsSec = new ArrayList<>();

        @Override
        public void waitSeconds(int sec, io.reactivex.ObservableEmitter<?> emitter) {
            waitsSec.add(sec);
        }
    }

    private static final class MockHttp extends VotHttp {
        final List<String> events = new ArrayList<>();
        final List<Map<String, String>> translateHeaders = new ArrayList<>();
        final List<byte[]> translateBodies = new ArrayList<>();
        final List<byte[]> translateResponses = new ArrayList<>();
        Map<String, String> audioHeaders = new HashMap<>();
        int sessionCreateCalls;
        int throwHttpErrorOnTranslateAttempt = -1;

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
                    throw new VotHttpException(502, "Bad Gateway", 5);
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
        public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers) {
            if ("/video-translation/audio".equals(path)) {
                events.add("audio");
                audioHeaders = new HashMap<>(headers);
                return new byte[]{0x08, 0x02};
            }
            throw new AssertionError("Unexpected PUT path: " + path);
        }
    }
}

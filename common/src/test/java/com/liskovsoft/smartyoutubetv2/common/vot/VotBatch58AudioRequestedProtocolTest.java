package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VotBatch58AudioRequestedProtocolTest {
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=TEST_BATCH58";

    @Test
    public void initialFinishedYieldsReadyDirectlyWithoutPolling() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0, "tid-1", "https://example.invalid/direct.mp3"));
        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(Arrays.asList("session", "translate-1"), http.events);
        assertTrue(hasVarintField(http.translateBodies.get(0), 5, 1)); // firstRequest = true
        assertEquals(0, waits.waitsSec.size());
        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_READY, progress.get(0).type);
        assertEquals("https://example.invalid/direct.mp3", progress.get(0).audioUrl);
    }

    @Test
    public void waitingYieldsWaitingThenFinishedOnSubsequentPoll() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 10, "tid-1", null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0, "tid-1", "https://example.invalid/polled.mp3"));
        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(Arrays.asList("session", "translate-1", "translate-2"), http.events);
        assertTrue("Initial translate must have firstRequest=true",
                hasVarintField(http.translateBodies.get(0), 5, 1));
        assertTrue("Subsequent poll must have firstRequest=true",
                hasVarintField(http.translateBodies.get(1), 5, 1));
        assertEquals(Arrays.asList(10), waits.waitsSec);
        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
    }

    @Test
    public void audioRequestedThenWaitingThenFinishedWithFirstRequestTrueAfterUpload() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, "tid-audio", null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_WAITING, 5, "tid-audio", null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0, "tid-audio", "https://example.invalid/synth.mp3"));
        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(Arrays.asList(
                "session", "translate-1", "fail-audio", "audio",
                "translate-2", "translate-3"), http.events);
        assertEquals(1, http.sessionCreateCalls);

        String sessionKey = http.translateHeaders.get(0).get("Sec-Vtrans-Sk");
        assertEquals(sessionKey, http.audioHeaders.get("Sec-Vtrans-Sk"));
        assertEquals(sessionKey, http.translateHeaders.get(1).get("Sec-Vtrans-Sk"));
        assertEquals(sessionKey, http.translateHeaders.get(2).get("Sec-Vtrans-Sk"));

        assertTrue("Initial request has firstRequest=true",
                hasVarintField(http.translateBodies.get(0), 5, 1));
        assertTrue("Post-upload retry MUST have firstRequest=true to queue task on Yandex backend",
                hasVarintField(http.translateBodies.get(1), 5, 1));
        assertTrue("Subsequent poll after post-upload waiting MUST have firstRequest=true",
                hasVarintField(http.translateBodies.get(2), 5, 1));

        assertEquals(Arrays.asList(5), waits.waitsSec);
        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
    }

    @Test
    public void audioRequestedThenFinishedImmediatelyAfterUpload() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 40, "tid-fast", null));
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_FINISHED, 0, "tid-fast", "https://example.invalid/immediate.mp3"));
        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(Arrays.asList(
                "session", "translate-1", "fail-audio", "audio", "translate-2"), http.events);
        assertTrue("Retry after upload has firstRequest=true",
                hasVarintField(http.translateBodies.get(1), 5, 1));
        assertEquals(0, waits.waitsSec.size());
        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_READY, progress.get(0).type);
        assertEquals("https://example.invalid/immediate.mp3", progress.get(0).audioUrl);
    }

    @Test
    public void audioRequestedThenHttp400YieldsTerminalGenericError() {
        MockHttp http = new MockHttp();
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 50, "tid-err", null));
        http.throwHttpErrorOnTranslateAttempt = 2; // Throw on translate-2
        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        assertEquals(Arrays.asList(
                "session", "translate-1", "fail-audio", "audio", "translate-2"), http.events);
        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_GENERIC, progress.get(0).message);
    }

    @Test
    public void repeatedAudioRequestedTerminatesWithUnsupportedVideoWithoutInfiniteLoop() {
        MockHttp http = new MockHttp();
        // translate-1 returns AUDIO_REQUESTED
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, "tid-loop", null));
        // translate-2 (after audio upload) also returns AUDIO_REQUESTED
        http.translateResponses.add(translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED, 60, "tid-loop", null));
        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 300).toList().blockingGet();

        // Must stop at translate-2 and NOT upload audio a second time
        assertEquals(Arrays.asList(
                "session", "translate-1", "fail-audio", "audio", "translate-2"), http.events);
        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO, progress.get(0).message);
    }

    private static byte[] sessionResponse() {
        VotWireWriter writer = new VotWireWriter();
        writer.writeString(1, "batch58-test-session-key");
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
        public byte[] postProtobuf(String path, byte[] body, Map<String, String> headers)
                throws IOException {
            if ("/session/create".equals(path)) {
                events.add("session");
                sessionCreateCalls++;
                return sessionResponse();
            }
            if (!"/video-translation/translate".equals(path)) {
                throw new AssertionError("Unexpected POST path: " + path);
            }

            int requestNumber = translateHeaders.size() + 1;
            events.add("translate-" + requestNumber);
            translateHeaders.add(new HashMap<>(headers));
            translateBodies.add(body.clone());

            if (requestNumber == throwHttpErrorOnTranslateAttempt) {
                throw new VotHttpException(400, "Bad Request", -1);
            }

            int index = requestNumber - 1;
            if (index < translateResponses.size()) {
                return translateResponses.get(index);
            }
            throw new IOException("No mock response for translate attempt " + requestNumber);
        }

        @Override
        public byte[] putJson(String path, String json, Map<String, String> headers) {
            if (!"/video-translation/fail-audio-js".equals(path)) {
                throw new AssertionError("Unexpected JSON path: " + path);
            }
            events.add("fail-audio");
            return "{\"status\":1}".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] putProtobuf(String path, byte[] body, Map<String, String> headers) {
            if (!"/video-translation/audio".equals(path)) {
                throw new AssertionError("Unexpected PUT path: " + path);
            }
            events.add("audio");
            audioHeaders = new HashMap<>(headers);
            return new byte[]{0x08, 0x02};
        }
    }
}

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

public class VotBatch55AudioRequestedSessionTest {
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=TEST_VIDEO";

    @Test
    public void audioRequestedKeepsOneSessionFromInitialRequestThroughPolling() {
        RecordingHttp http = new RecordingHttp(false);
        RecordingWaitStrategy waits = new RecordingWaitStrategy();
        VotClient client = new VotClient(null, http, null, waits, false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 1453)
                .toList().blockingGet();

        assertEquals(Arrays.asList(
                "session", "translate-1", "fail-audio", "audio",
                "translate-2", "translate-3"), http.events);
        assertEquals(1, http.sessionCreateCalls);
        assertEquals(3, http.translateHeaders.size());
        assertTrue(http.audioHeaders.containsKey("Sec-Vtrans-Sk"));

        String sessionKey = http.translateHeaders.get(0).get("Sec-Vtrans-Sk");
        assertEquals(sessionKey, http.audioHeaders.get("Sec-Vtrans-Sk"));
        assertEquals(sessionKey, http.translateHeaders.get(1).get("Sec-Vtrans-Sk"));
        assertEquals(sessionKey, http.translateHeaders.get(2).get("Sec-Vtrans-Sk"));

        assertTrue("Initial request must keep firstRequest=true",
                hasVarintField(http.translateBodies.get(0), 5, 1));
        assertTrue("Request after audio upload must have firstRequest=true to queue task",
                hasVarintField(http.translateBodies.get(1), 5, 1));
        assertTrue("Regular polling must send firstRequest=true to prevent HTTP 400",
                hasVarintField(http.translateBodies.get(2), 5, 1));
        assertEquals(Arrays.asList(5), waits.waitsSec);
        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
    }

    @Test
    public void continuationHttp400ProducesOneTerminalError() {
        RecordingHttp http = new RecordingHttp(true);
        VotClient client = new VotClient(
                null, http, null, new RecordingWaitStrategy(), false);

        List<VotProgress> progress = client.observeTranslation(VIDEO_URL, 1453)
                .toList().blockingGet();

        assertEquals(1, http.sessionCreateCalls);
        assertEquals(2, http.translateHeaders.size());
        assertEquals(1, progress.size());
        assertEquals(VotProgress.TYPE_FAILED, progress.get(0).type);
        assertEquals(VotClient.ERROR_MARKER_GENERIC, progress.get(0).message);
    }

    private static byte[] sessionResponse() {
        VotWireWriter writer = new VotWireWriter();
        writer.writeString(1, "synthetic-session-key");
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

    private static final class RecordingHttp extends VotHttp {
        final List<String> events = new ArrayList<>();
        final List<Map<String, String>> translateHeaders = new ArrayList<>();
        final List<byte[]> translateBodies = new ArrayList<>();
        Map<String, String> audioHeaders = new HashMap<>();
        int sessionCreateCalls;
        private final boolean failContinuation;

        RecordingHttp(boolean failContinuation) {
            this.failContinuation = failContinuation;
        }

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
            if (failContinuation && requestNumber == 2) {
                throw new VotHttpException(400, "synthetic bad request", -1);
            }
            if (requestNumber == 1) {
                return translationResponse(VotTranslationResponse.STATUS_AUDIO_REQUESTED,
                        67, "synthetic-translation-id", null);
            }
            if (requestNumber == 2) {
                return translationResponse(VotTranslationResponse.STATUS_WAITING,
                        5, "synthetic-translation-id", null);
            }
            return translationResponse(VotTranslationResponse.STATUS_FINISHED,
                    0, "synthetic-translation-id", "https://example.invalid/audio.mp3");
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

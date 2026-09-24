package com.liskovsoft.smartyoutubetv2.common.vot;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class VotProtobuf {
    private VotProtobuf() {
    }

    public static byte[] encodeTranslationRequest(
            String url,
            double duration,
            String requestLang,
            String responseLang,
            boolean firstRequest,
            boolean useLivelyVoice
    ) {
        VotWireWriter w = new VotWireWriter();
        w.writeString(3, url);
        w.writeBool(5, firstRequest);
        w.writeDouble(6, duration);
        w.writeInt32(7, 1);
        w.writeString(8, requestLang);
        w.writeBool(9, false);
        w.writeInt32(10, 0);
        w.writeString(14, responseLang);
        w.writeInt32(15, 1);
        w.writeInt32(16, 2);
        w.writeBool(18, useLivelyVoice);
        return w.toByteArray();
    }

    public static byte[] encodeSessionRequest(String uuid, String module) {
        VotWireWriter w = new VotWireWriter();
        w.writeString(1, uuid);
        w.writeString(2, module);
        return w.toByteArray();
    }

    public static byte[] encodeTranslationAudioRequest(String url, String translationId, String fileId) {
        VotWireWriter audioInfo = new VotWireWriter();
        audioInfo.writeString(1, fileId);
        audioInfo.writeBytes(2, new byte[0]);

        VotWireWriter w = new VotWireWriter();
        w.writeString(1, translationId);
        w.writeString(2, url);
        w.writeEmbedded(6, audioInfo.toByteArray());
        return w.toByteArray();
    }

    public static byte[] encodeAudioRequestSinglePart(String url, String translationId, String fileId, byte[] audioFile) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        if (translationId == null || translationId.trim().isEmpty()) {
            throw new IllegalArgumentException("translationId must not be empty");
        }
        if (fileId == null || fileId.trim().isEmpty()) {
            throw new IllegalArgumentException("fileId must not be empty");
        }
        if (audioFile == null || audioFile.length == 0) {
            throw new IllegalArgumentException("audioFile must be non-empty");
        }

        VotWireWriter audioInfo = new VotWireWriter();
        audioInfo.writeString(1, fileId);
        audioInfo.writeBytes(2, audioFile);

        VotWireWriter root = new VotWireWriter();
        root.writeString(1, translationId);
        root.writeString(2, url);
        root.writeEmbedded(6, audioInfo.toByteArray());
        return root.toByteArray();
    }

    public static byte[] encodeAudioRequestChunk(String url, String translationId, String fileId,
                                                int chunkId, int audioPartsLength, byte[] audioFile) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        if (translationId == null || translationId.trim().isEmpty()) {
            throw new IllegalArgumentException("translationId must not be empty");
        }
        if (fileId == null || fileId.trim().isEmpty()) {
            throw new IllegalArgumentException("fileId must not be empty");
        }
        if (chunkId < 0) {
            throw new IllegalArgumentException("chunkId must be non-negative: " + chunkId);
        }
        if (audioPartsLength < 0) {
            throw new IllegalArgumentException("audioPartsLength must be non-negative: " + audioPartsLength);
        }
        if (audioFile == null || audioFile.length == 0) {
            throw new IllegalArgumentException("audioFile must be non-empty");
        }

        VotWireWriter audioBuffer = new VotWireWriter();
        audioBuffer.writeInt32IncludeZero(1, chunkId);
        audioBuffer.writeBytes(2, audioFile);

        VotWireWriter partialAudioInfo = new VotWireWriter();
        partialAudioInfo.writeEmbedded(1, audioBuffer.toByteArray());
        if (audioPartsLength > 0) {
            partialAudioInfo.writeInt32(2, audioPartsLength);
        }
        partialAudioInfo.writeString(3, fileId);
        partialAudioInfo.writeInt32(4, 1); // version = 1

        VotWireWriter root = new VotWireWriter();
        root.writeString(1, translationId);
        root.writeString(2, url);
        root.writeEmbedded(4, partialAudioInfo.toByteArray());
        return root.toByteArray();
    }

    public static VotTranslationAudioResponse decodeTranslationAudioResponse(byte[] data) {
        VotTranslationAudioResponse r = new VotTranslationAudioResponse();
        if (data == null || data.length == 0) {
            return r;
        }
        int pos = 0;
        parseLoop:
        while (pos < data.length) {
            int[] tag = readVarint(data, pos);
            if (tag[0] <= 0) {
                break;
            }
            pos = tag[1];
            int fieldNumber = tag[0] >>> 3;
            int wireType = tag[0] & 0x7;

            switch (wireType) {
                case 0: { // varint
                    int[] val = readVarint(data, pos);
                    if (val[0] < 0) {
                        break parseLoop;
                    }
                    pos = val[1];
                    if (fieldNumber == 1) {
                        r.status = val[0];
                    }
                    break;
                }
                case 1: { // 64-bit
                    if (data.length - pos < 8) {
                        break parseLoop;
                    }
                    pos += 8;
                    break;
                }
                case 2: { // length-delimited
                    int[] len = readVarint(data, pos);
                    if (len[0] < 0) {
                        break parseLoop;
                    }
                    pos = len[1];
                    int length = len[0];
                    if (length < 0 || length > data.length - pos) {
                        break parseLoop;
                    }
                    if (fieldNumber == 2) {
                        byte[] chunk = new byte[length];
                        System.arraycopy(data, pos, chunk, 0, length);
                        r.remainingChunks.add(new String(chunk, StandardCharsets.UTF_8));
                    }
                    pos += length;
                    break;
                }
                case 5: { // 32-bit
                    if (data.length - pos < 4) {
                        break parseLoop;
                    }
                    pos += 4;
                    break;
                }
                default:
                    break parseLoop;
            }
        }
        return r;
    }

    public static VotTranslationResponse decodeTranslationResponse(byte[] data) {
        Map<Integer, Object> fields = parseFields(data);
        VotTranslationResponse r = new VotTranslationResponse();
        r.url = (String) fields.get(1);
        Object status = fields.get(4);
        r.status = status instanceof Integer ? (Integer) status : 0;
        Object remaining = fields.get(5);
        r.remainingTimeSec = remaining instanceof Integer ? (Integer) remaining : 0;
        r.translationId = (String) fields.get(7);
        r.message = (String) fields.get(9);
        return r;
    }

    public static VotSession decodeSessionResponse(byte[] data) {
        Map<Integer, Object> fields = parseFields(data);
        VotSession s = new VotSession();
        s.secretKey = (String) fields.get(1);
        Object expires = fields.get(2);
        s.expiresSec = expires instanceof Integer ? (Integer) expires : 3600;
        return s;
    }

    private static Map<Integer, Object> parseFields(byte[] data) {
        Map<Integer, Object> result = new HashMap<>();
        int pos = 0;
        parseLoop:
        while (pos < data.length) {
            int[] tag = readVarint(data, pos);
            if (tag[0] <= 0) {
                break;
            }
            pos = tag[1];
            int fieldNumber = tag[0] >>> 3;
            int wireType = tag[0] & 0x7;

            switch (wireType) {
                case 0: {
                    int[] val = readVarint(data, pos);
                    if (val[0] < 0) {
                        break parseLoop;
                    }
                    pos = val[1];
                    result.put(fieldNumber, val[0]);
                    break;
                }
                case 1: {
                    if (data.length - pos < 8) {
                        break parseLoop;
                    }
                    pos += 8;
                    break;
                }
                case 2: {
                    int[] len = readVarint(data, pos);
                    if (len[0] < 0) {
                        break parseLoop;
                    }
                    pos = len[1];
                    int length = len[0];
                    if (length > data.length - pos) {
                        break parseLoop;
                    }
                    byte[] chunk = new byte[length];
                    System.arraycopy(data, pos, chunk, 0, length);
                    pos += length;
                    if (isUtf8String(chunk)) {
                        result.put(fieldNumber, new String(chunk, StandardCharsets.UTF_8));
                    }
                    break;
                }
                case 5: {
                    if (data.length - pos < 4) {
                        break parseLoop;
                    }
                    pos += 4;
                    break;
                }
                default:
                    pos = data.length;
                    break;
            }
        }
        return result;
    }

    private static boolean isUtf8String(byte[] chunk) {
        for (byte b : chunk) {
            if (b < 0x09) {
                return false;
            }
        }
        return true;
    }

    private static int[] readVarint(byte[] data, int pos) {
        int result = 0;
        int shift = 0;
        while (pos < data.length) {
            int b = data[pos++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[]{result, pos};
            }
            shift += 7;
            if (shift > 35) {
                return new int[]{-1, pos};
            }
        }
        return new int[]{-1, pos};
    }
}

/*
 * Copyright (C) 2026 anddea
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Original author(s):
 * - Jav1x (https://github.com/Jav1x)
 *
 * Ported to morphe-patches: https://github.com/MorpheApp/morphe-patches
 * Modified by: Jav1x (https://github.com/Jav1x)
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Attribution Notice
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Attribution (Section 7(b)): This specific copyright notice and the
 *    list of original authors above must be preserved in any copy or
 *    derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin (Section 7(c)): Modified versions must be clearly marked as
 *    such (e.g., by adding a "Modified by" line or a new copyright notice).
 *    They must not be misrepresented as the original work.
 *
 * ------------------------------------------------------------------------
 * Version Control Acknowledgement (Non-binding Request)
 * ------------------------------------------------------------------------
 *
 * While not a legal requirement of the GPLv3, the original author(s)
 * respectfully request that ports or substantial modifications retain
 * historical authorship credit in version control systems (e.g., Git),
 * listing original author(s) appropriately and modifiers as committers
 * or co-authors.
 */

/* Modified for Dual VoT Patches: namespaced to coexist with the official Morphe engine. */
/* Adapted for SmartTube VOX: native package integration foundation. */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Manual protobuf encoder/decoder for VOT API communication.
 */
public class YandexVotProtobuf {

    // Wire types
    private static final int WIRETYPE_VARINT = 0;
    private static final int WIRETYPE_64BIT = 1;
    private static final int WIRETYPE_LENGTH_DELIMITED = 2;

    public static class TranslationHelp {
        public final String target;
        public final String targetUrl;

        public TranslationHelp(String target, String targetUrl) {
            this.target = target;
            this.targetUrl = targetUrl;
        }
    }

    // ==================== ENCODER ====================

    /**
     * Encode a VideoTranslationRequest matching VOT userscript v1.11.17.
     * Field numbers from reference protobuf:
     *   url = 3 (string)
     *   deviceId = 4 (string)
     *   firstRequest = 5 (bool)
     *   duration = 6 (double)
     *   unknown0 = 7 (bool: true)
     *   language = 8 (string)
     *   forceSourceLang = 9 (bool: false)
     *   unknown1 = 10 (bool: false)
     *   translationHelp = 11 (repeated message: 1=target, 2=targetUrl)
     *   wasStream = 13 (bool: false)
     *   responseLanguage = 14 (string)
     *   unknown2 = 15 (bool: true)
     *   configVersion = 16 (int32: 2)
     *   bypassCache = 17 (bool: false)
     *   useLivelyVoice = 18 (bool)
     *   videoTitle = 19 (string)
     */
    public static byte[] encodeTranslationRequest(
            String url,
            String deviceId,
            boolean firstRequest,
            double duration,
            String language,
            boolean forceSourceLang,
            java.util.List<TranslationHelp> translationHelp,
            boolean wasStream,
            String responseLanguage,
            int configVersion,
            boolean bypassCache,
            boolean useLiveVoices,
            String videoTitle
    ) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (url != null && !url.isEmpty()) {
                writeString(out, 3, url);
            }
            if (deviceId != null && !deviceId.isEmpty()) {
                writeString(out, 4, deviceId);
            }
            if (firstRequest) {
                writeBool(out, 5, true);
            }
            if (duration != 0.0) {
                writeDouble(out, duration);
            }
            writeBool(out, 7, true);          // unknown0 = true
            if (language != null && !language.isEmpty()) {
                writeString(out, 8, language);
            }
            if (forceSourceLang) {
                writeBool(out, 9, true);       // forceSourceLang
            }
            writeInt32(out, 10, 0);         // unknown1 = false (varint 0)

            if (translationHelp != null && !translationHelp.isEmpty()) {
                for (TranslationHelp help : translationHelp) {
                    if (help != null) {
                        ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
                        if (help.target != null && !help.target.isEmpty()) {
                            writeString(helpOut, 1, help.target);
                        }
                        if (help.targetUrl != null && !help.targetUrl.isEmpty()) {
                            writeString(helpOut, 2, help.targetUrl);
                        }
                        writeMessage(out, 11, helpOut.toByteArray());
                    }
                }
            }

            if (wasStream) {
                writeBool(out, 13, true);
            }
            if (responseLanguage != null && !responseLanguage.isEmpty()) {
                writeString(out, 14, responseLanguage);
            }
            writeBool(out, 15, true);         // unknown2 = true
            writeInt32(out, 16, configVersion > 0 ? configVersion : 2); // configVersion = 2
            if (bypassCache) {
                writeBool(out, 17, true);
            }
            if (useLiveVoices) {
                writeBool(out, 18, true);
            }
            if (videoTitle != null && !videoTitle.isEmpty()) {
                writeString(out, 19, videoTitle);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode translation request", e);
        }
    }

    public static byte[] encodeTranslationRequest(
            String url, boolean firstRequest, double duration,
            String language, String responseLanguage, String videoTitle,
            boolean useLiveVoices
    ) {
        return encodeTranslationRequest(
                url, null, firstRequest, duration, language, false,
                null, false, responseLanguage, 2, false, useLiveVoices, videoTitle
        );
    }

    public static byte[] encodeTranslationCacheRequest(String url, double duration, String language, String responseLanguage) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (url != null && !url.isEmpty()) {
                writeString(out, 1, url);
            }
            if (duration > 0) {
                writeDouble(out, 2, duration);
            }
            if (language != null && !language.isEmpty()) {
                writeString(out, 3, language);
            }
            if (responseLanguage != null && !responseLanguage.isEmpty()) {
                writeString(out, 4, responseLanguage);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode translation cache request", e);
        }
    }

    public static byte[] encodeAudioRequest(
            String translationId,
            String url,
            String fileId,
            byte[] audioData
    ) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeString(out, 1, translationId);
            writeString(out, 2, url);

            ByteArrayOutputStream audioInfoOut = new ByteArrayOutputStream();
            writeString(audioInfoOut, 1, fileId);
            if (audioData != null && audioData.length > 0) {
                writeBytes(audioInfoOut, audioData);
            }

            byte[] audioInfoBytes = audioInfoOut.toByteArray();
            writeMessage(out, 6, audioInfoBytes);

            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode audio request", e);
        }
    }

    public static byte[] encodePartialAudioRequest(
            String translationId,
            String url,
            String fileId,
            int audioPartsLength,
            int version,
            int chunkId,
            byte[] audioData
    ) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeString(out, 1, translationId);
            writeString(out, 2, url);

            ByteArrayOutputStream partialAudioBufferOut = new ByteArrayOutputStream();
            writeInt32(partialAudioBufferOut, 1, chunkId);
            writeBytes(partialAudioBufferOut, audioData);

            ByteArrayOutputStream partialAudioInfoOut = new ByteArrayOutputStream();
            writeMessage(partialAudioInfoOut, 1, partialAudioBufferOut.toByteArray());
            writeInt32(partialAudioInfoOut, 2, audioPartsLength);
            writeString(partialAudioInfoOut, 3, fileId);
            writeInt32(partialAudioInfoOut, 4, version);

            writeMessage(out, 4, partialAudioInfoOut.toByteArray());

            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode partial audio request", e);
        }
    }

    public static byte[] encodeVideoLangCacheRequest(String url, String title) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (url != null && !url.isEmpty()) {
                writeString(out, 1, url);
            }
            if (title != null && !title.isEmpty()) {
                writeString(out, 2, title);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode video lang cache request", e);
        }
    }

    public static byte[] encodeSubtitlesRequest(String url, String requestLang) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (url != null && !url.isEmpty()) {
                writeString(out, 1, url);
            }
            if (requestLang != null && !requestLang.isEmpty()) {
                writeString(out, 2, requestLang);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode subtitles request", e);
        }
    }

    // ==================== DECODER ====================

    /**
     * Decoded translation response fields matching VOT userscript v1.11.17.
     */
    public static class TranslationResponse {
        public String url;
        public double duration;
        public int status;
        public int remainingTime = -1;
        public String translationId;
        public String language;
        public String message;
        public Boolean isLivelyVoice;
        public Boolean allowToTranslateVideo;
        public Integer shouldRetry;
        public Integer unknown3;
    }

    public static class TranslationCacheItem {
        public int status;
        public int remainingTime = -1;
        public String message;
        public Integer unknown0;
        public java.util.List<Integer> flags = new java.util.ArrayList<>();
    }

    public static class TranslationCacheResponse {
        public TranslationCacheItem defaultItem;
        public TranslationCacheItem cloningItem;
    }

    public static TranslationCacheResponse decodeTranslationCacheResponse(byte[] data) {
        TranslationCacheResponse response = new TranslationCacheResponse();
        if (data == null || data.length == 0) return response;
        int pos = 0;
        while (pos < data.length) {
            int[] tagResult = readVarint(data, pos);
            int tag = tagResult[0];
            pos = tagResult[1];
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // defaultItem (message)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.defaultItem = decodeTranslationCacheItem(data, pos, len);
                        pos += len;
                    }
                    break;
                case 2: // cloningItem (message)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.cloningItem = decodeTranslationCacheItem(data, pos, len);
                        pos += len;
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }
        return response;
    }

    private static TranslationCacheItem decodeTranslationCacheItem(byte[] data, int startPos, int length) {
        TranslationCacheItem item = new TranslationCacheItem();
        int pos = startPos;
        int end = startPos + length;
        while (pos < end && pos < data.length) {
            int[] tagResult = readVarint(data, pos);
            int tag = tagResult[0];
            pos = tagResult[1];
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // status (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        item.status = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                case 2: // remainingTime (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        item.remainingTime = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                case 3: // message (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        item.message = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 4: // unknown0 (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        item.unknown0 = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                case 5: // flags (int32 / packed varint)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        item.flags.add(valResult[0]);
                        pos = valResult[1];
                    } else if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int packedLen = lenResult[0];
                        pos = lenResult[1];
                        int packedEnd = pos + packedLen;
                        while (pos < packedEnd && pos < data.length) {
                            int[] flagVal = readVarint(data, pos);
                            item.flags.add(flagVal[0]);
                            pos = flagVal[1];
                        }
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }
        return item;
    }

    public static class VideoLangCacheResponse {
        public String url;
        public int status;
        public int unknown1;
    }

    public static VideoLangCacheResponse decodeVideoLangCacheResponse(byte[] data) {
        VideoLangCacheResponse response = new VideoLangCacheResponse();
        if (data == null || data.length == 0) return response;
        int pos = 0;
        while (pos < data.length) {
            int[] tagResult = readVarint(data, pos);
            int tag = tagResult[0];
            pos = tagResult[1];
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // url (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.url = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 3: // status (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.status = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                case 5: // unknown1 (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.unknown1 = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }
        return response;
    }

    public static class SubtitlesObject {
        public String language = "";
        public String url = "";
        public boolean hasTranslation = false;
        public String translatedLanguage = "";
        public String translatedUrl = "";
        public boolean unknown1 = false;
        public long subtitleId = 0;
    }

    public static class SubtitlesResponse {
        public boolean waiting = false;
        public java.util.List<SubtitlesObject> subtitles = new java.util.ArrayList<>();
    }

    public static SubtitlesResponse decodeSubtitlesResponse(byte[] data) {
        SubtitlesResponse response = new SubtitlesResponse();
        if (data == null || data.length == 0) return response;
        int pos = 0;
        while (pos < data.length) {
            int[] tagResult = readVarint(data, pos);
            int tag = tagResult[0];
            pos = tagResult[1];
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // waiting (bool)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.waiting = valResult[0] != 0;
                        pos = valResult[1];
                    }
                    break;
                case 2: // subtitles (repeated SubtitlesObject)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.subtitles.add(decodeSubtitlesObject(data, pos, len));
                        pos += len;
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }
        return response;
    }

    private static SubtitlesObject decodeSubtitlesObject(byte[] data, int startPos, int length) {
        SubtitlesObject obj = new SubtitlesObject();
        int pos = startPos;
        int end = startPos + length;
        while (pos < end && pos < data.length) {
            int[] tagResult = readVarint(data, pos);
            int tag = tagResult[0];
            pos = tagResult[1];
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // language (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        obj.language = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 2: // url (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        obj.url = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 3: // hasTranslation (bool)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        obj.hasTranslation = valResult[0] != 0;
                        pos = valResult[1];
                    }
                    break;
                case 4: // translatedLanguage (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        obj.translatedLanguage = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 5: // translatedUrl (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        obj.translatedUrl = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 6: // unknown1 (bool)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        obj.unknown1 = valResult[0] != 0;
                        pos = valResult[1];
                    }
                    break;
                case 7: // subtitleId (uint32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        obj.subtitleId = valResult[0] & 0xFFFFFFFFL;
                        pos = valResult[1];
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }
        return obj;
    }

    /**
     * Decode a VideoTranslationResponse:
     *   url = 1 (string), duration = 2 (double), status = 4 (int32),
     *   remainingTime = 5 (int32), translationId = 7 (string),
     *   language = 8 (string), message = 9 (string),
     *   isLivelyVoice = 10 (bool), allowToTranslateVideo = 11 (bool),
     *   shouldRetry = 12 (int32), unknown3 = 13 (int32)
     */
    public static TranslationResponse decodeTranslationResponse(byte[] data) {
        TranslationResponse response = new TranslationResponse();
        int pos = 0;

        while (pos < data.length) {
            int[] tagResult = readVarint(data, pos);
            int tag = tagResult[0];
            pos = tagResult[1];

            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // url (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.url = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 2: // duration (double)
                    if (wireType == WIRETYPE_64BIT) {
                        response.duration = readDouble(data, pos);
                        pos += 8;
                    }
                    break;
                case 4: // status (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.status = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                case 5: // remainingTime (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.remainingTime = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                case 7: // translationId (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.translationId = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 8: // language (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.language = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 9: // message (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.message = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 10: // isLivelyVoice (bool)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.isLivelyVoice = valResult[0] != 0;
                        pos = valResult[1];
                    }
                    break;
                case 11: // allowToTranslateVideo (bool)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.allowToTranslateVideo = valResult[0] != 0;
                        pos = valResult[1];
                    }
                    break;
                case 12: // shouldRetry (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.shouldRetry = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                case 13: // unknown3 (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.unknown3 = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }

        return response;
    }

    // ==================== LOW-LEVEL ENCODING ====================

    private static void writeTag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeRawVarint(out, (fieldNumber << 3) | wireType);
    }

    private static void writeString(ByteArrayOutputStream out, int fieldNumber, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeTag(out, fieldNumber, WIRETYPE_LENGTH_DELIMITED);
        writeRawVarint(out, bytes.length);
        out.write(bytes);
    }

    private static void writeMessage(
            ByteArrayOutputStream out,
            int fieldNumber,
            byte[] value
    ) throws IOException {
        writeTag(out, fieldNumber, WIRETYPE_LENGTH_DELIMITED);
        writeRawVarint(out, value.length);
        out.write(value);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] value) throws IOException {
        writeMessage(out, 2, value);
    }

    private static void writeInt32(ByteArrayOutputStream out, int fieldNumber, int value) throws IOException {
        writeTag(out, fieldNumber, WIRETYPE_VARINT);
        writeRawVarint(out, value);
    }

    private static void writeBool(ByteArrayOutputStream out, int fieldNumber, boolean value) throws IOException {
        writeTag(out, fieldNumber, WIRETYPE_VARINT);
        out.write(value ? 1 : 0);
    }

    private static void writeDouble(ByteArrayOutputStream out, int fieldNumber, double value) throws IOException {
        writeTag(out, fieldNumber, WIRETYPE_64BIT);
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(value);
        out.write(buf.array());
    }

    private static void writeDouble(ByteArrayOutputStream out, double value) throws IOException {
        writeDouble(out, 6, value);
    }

    private static void writeRawVarint(ByteArrayOutputStream out, int value) {
        // Handle unsigned encoding for potentially large values
        long unsigned = value & 0xFFFFFFFFL;
        while (unsigned > 0x7F) {
            out.write((int) ((unsigned & 0x7F) | 0x80));
            unsigned >>>= 7;
        }
        out.write((int) unsigned);
    }

    // ==================== LOW-LEVEL DECODING ====================

    /**
     * Read a varint from data at the given position.
     * Returns [value, newPosition].
     */
    private static int[] readVarint(byte[] data, int pos) {
        int result = 0;
        int shift = 0;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            pos++;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return new int[]{result, pos};
    }

    /**
     * Read a double (8 bytes, little-endian) from data at the given position.
     */
    private static double readDouble(byte[] data, int pos) {
        ByteBuffer buf = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.LITTLE_ENDIAN);
        return buf.getDouble();
    }

    // ==================== SESSION ====================

    /**
     * Encode a YandexSessionRequest.
     *   uuid = 1 (string)
     *   module = 2 (string)
     */
    public static byte[] encodeSessionRequest(String uuid, String module) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeString(out, 1, uuid);
            writeString(out, 2, module);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode session request", e);
        }
    }

    /**
     * Decoded session response.
     */
    public static class SessionResponse {
        public String secretKey;
        public int expires;
    }

    /**
     * Decode a YandexSessionResponse.
     *   secretKey = 1 (string)
     *   expires = 2 (int32)
     */
    public static SessionResponse decodeSessionResponse(byte[] data) {
        SessionResponse response = new SessionResponse();
        int pos = 0;

        while (pos < data.length) {
            int[] tagResult = readVarint(data, pos);
            int tag = tagResult[0];
            pos = tagResult[1];

            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // secretKey (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.secretKey = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 2: // expires (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.expires = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }

        return response;
    }

    /**
     * Skip a field of the given wire type. Returns new position.
     */
    private static int skipField(byte[] data, int pos, int wireType) {
        switch (wireType) {
            case WIRETYPE_VARINT:
                while (pos < data.length && (data[pos] & 0x80) != 0) {
                    pos++;
                }
                return pos + 1;
            case WIRETYPE_64BIT:
                return pos + 8;
            case WIRETYPE_LENGTH_DELIMITED:
                int[] lenResult = readVarint(data, pos);
                return lenResult[1] + lenResult[0];
            case 5: // 32-bit
                return pos + 4;
            default:
                return data.length; // unknown wire type, skip to end
        }
    }
}

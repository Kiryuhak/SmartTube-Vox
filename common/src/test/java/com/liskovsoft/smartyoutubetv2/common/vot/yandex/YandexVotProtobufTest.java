/*
 * Copyright (C) 2026 anddea
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YandexVotProtobufTest {

    @Test
    public void testEncodeTranslationRequestAndDecodeSession() {
        byte[] encoded = YandexVotProtobuf.encodeTranslationRequest(
                "https://www.youtube.com/watch?v=test",
                true,
                120.0,
                "en",
                "ru",
                "Test Title",
                true
        );
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        byte[] sessionReq = YandexVotProtobuf.encodeSessionRequest("uuid-12345", "video-translation");
        assertNotNull(sessionReq);
        assertTrue(sessionReq.length > 0);
    }

    @Test
    public void testEncodeFullTranslationRequest() {
        java.util.List<YandexVotProtobuf.TranslationHelp> helpList = new java.util.ArrayList<>();
        helpList.add(new YandexVotProtobuf.TranslationHelp("video_file_url", "https://cdn.example.com/video.mp4"));
        helpList.add(new YandexVotProtobuf.TranslationHelp("subtitles_file_url", "https://cdn.example.com/subs.vtt"));

        byte[] encoded = YandexVotProtobuf.encodeTranslationRequest(
                "https://youtu.be/dQw4w9WgXcQ",
                "device-uuid-999",
                true,
                210.5,
                "en",
                false,
                helpList,
                false,
                "ru",
                2,
                false,
                true,
                "Never Gonna Give You Up"
        );
        assertNotNull(encoded);
        assertTrue(encoded.length > 50);
    }

    @Test
    public void testDecodeTranslationResponseExtended() {
        // Construct protobuf manually with tags 10 (isLivelyVoice=true), 11 (allowToTranslateVideo=true), 12 (shouldRetry=1), 13 (unknown3=42)
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        // tag 1 (url)
        out.write((1 << 3) | 2);
        byte[] urlBytes = "https://storage.yandex.net/trans.mp3".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(urlBytes.length);
        out.write(urlBytes, 0, urlBytes.length);
        // tag 4 (status=1)
        out.write((4 << 3) | 0);
        out.write(1);
        // tag 5 (remainingTime=0)
        out.write((5 << 3) | 0);
        out.write(0);
        // tag 10 (isLivelyVoice=true)
        out.write((10 << 3) | 0);
        out.write(1);
        // tag 11 (allowToTranslateVideo=true)
        out.write((11 << 3) | 0);
        out.write(1);
        // tag 12 (shouldRetry=2)
        out.write((12 << 3) | 0);
        out.write(2);
        // tag 13 (unknown3=42)
        out.write((13 << 3) | 0);
        out.write(42);

        byte[] data = out.toByteArray();
        YandexVotProtobuf.TranslationResponse res = YandexVotProtobuf.decodeTranslationResponse(data);

        assertEquals("https://storage.yandex.net/trans.mp3", res.url);
        assertEquals(1, res.status);
        assertEquals(0, res.remainingTime);
        assertEquals(Boolean.TRUE, res.isLivelyVoice);
        assertEquals(Boolean.TRUE, res.allowToTranslateVideo);
        assertEquals(Integer.valueOf(2), res.shouldRetry);
        assertEquals(Integer.valueOf(42), res.unknown3);
    }

    @Test
    public void testDecodeTranslationCacheResponseWithFlags() {
        // Item with tag 1 (status=1), tag 2 (remainingTime=5), tag 3 (message="ready"), tag 4 (unknown0=1), tag 5 (flags=10, 20)
        java.io.ByteArrayOutputStream itemOut = new java.io.ByteArrayOutputStream();
        itemOut.write((1 << 3) | 0);
        itemOut.write(1);
        itemOut.write((2 << 3) | 0);
        itemOut.write(5);
        itemOut.write((3 << 3) | 2);
        byte[] msgBytes = "ready".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        itemOut.write(msgBytes.length);
        itemOut.write(msgBytes, 0, msgBytes.length);
        itemOut.write((4 << 3) | 0);
        itemOut.write(1);
        itemOut.write((5 << 3) | 0);
        itemOut.write(10);
        itemOut.write((5 << 3) | 0);
        itemOut.write(20);

        byte[] itemBytes = itemOut.toByteArray();

        java.io.ByteArrayOutputStream respOut = new java.io.ByteArrayOutputStream();
        // defaultItem = tag 1
        respOut.write((1 << 3) | 2);
        respOut.write(itemBytes.length);
        respOut.write(itemBytes, 0, itemBytes.length);

        YandexVotProtobuf.TranslationCacheResponse cacheResp =
                YandexVotProtobuf.decodeTranslationCacheResponse(respOut.toByteArray());

        assertNotNull(cacheResp.defaultItem);
        assertEquals(1, cacheResp.defaultItem.status);
        assertEquals(5, cacheResp.defaultItem.remainingTime);
        assertEquals("ready", cacheResp.defaultItem.message);
        assertEquals(Integer.valueOf(1), cacheResp.defaultItem.unknown0);
        assertEquals(2, cacheResp.defaultItem.flags.size());
        assertEquals(Integer.valueOf(10), cacheResp.defaultItem.flags.get(0));
        assertEquals(Integer.valueOf(20), cacheResp.defaultItem.flags.get(1));
    }

    @Test
    public void testEncodePartialAudioRequest() {
        byte[] fakeChunk = new byte[]{1, 2, 3, 4, 5};
        byte[] encoded = YandexVotProtobuf.encodePartialAudioRequest(
                "trans-1",
                "https://www.youtube.com/watch?v=test",
                "file-1",
                2,
                1,
                0,
                fakeChunk
        );
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
    }
}

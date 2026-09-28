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

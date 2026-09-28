/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class YandexVotAudioPartsTest {

    @Test
    public void emptyOrZeroFileSizeYieldsNoParts() {
        assertTrue(YandexVotAudioParts.parts(0).isEmpty());
        assertTrue(YandexVotAudioParts.parts(-100).isEmpty());
        assertEquals(0, YandexVotAudioParts.partCount(0));
        assertEquals(0, YandexVotAudioParts.partCount(-100));
    }

    @Test
    public void singleSmallPart() {
        List<YandexVotAudioParts.Part> parts = YandexVotAudioParts.parts(1000);
        assertEquals(1, parts.size());
        assertEquals(0, parts.get(0).start());
        assertEquals(1000, parts.get(0).length());
        assertEquals(1, YandexVotAudioParts.partCount(1000));
    }

    @Test
    public void exactSinglePartSize() {
        long size = YandexVotAudioParts.PART_SIZE_BYTES;
        List<YandexVotAudioParts.Part> parts = YandexVotAudioParts.parts(size);
        assertEquals(1, parts.size());
        assertEquals(0, parts.get(0).start());
        assertEquals(size, parts.get(0).length());
        assertEquals(1, YandexVotAudioParts.partCount(size));
    }

    @Test
    public void multiplePartsPartitioning() {
        long size = YandexVotAudioParts.PART_SIZE_BYTES * 2 + 500;
        List<YandexVotAudioParts.Part> parts = YandexVotAudioParts.parts(size);
        assertEquals(3, parts.size());

        assertEquals(0, parts.get(0).start());
        assertEquals(YandexVotAudioParts.PART_SIZE_BYTES, parts.get(0).length());

        assertEquals(YandexVotAudioParts.PART_SIZE_BYTES, parts.get(1).start());
        assertEquals(YandexVotAudioParts.PART_SIZE_BYTES, parts.get(1).length());

        assertEquals(YandexVotAudioParts.PART_SIZE_BYTES * 2L, parts.get(2).start());
        assertEquals(500, parts.get(2).length());

        assertEquals(3, YandexVotAudioParts.partCount(size));
    }
}

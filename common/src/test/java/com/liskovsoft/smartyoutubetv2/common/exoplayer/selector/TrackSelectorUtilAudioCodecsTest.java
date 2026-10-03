package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TrackSelectorUtilAudioCodecsTest {

    @Test
    public void testAc3AndEac3CodecNameShort() {
        assertEquals("ac3", TrackSelectorUtil.codecNameShort("ac-3"));
        assertEquals("ac3", TrackSelectorUtil.codecNameShort("ac3"));
        assertEquals("eac3", TrackSelectorUtil.codecNameShort("ec-3"));
        assertEquals("eac3", TrackSelectorUtil.codecNameShort("eac3"));
        assertEquals("opus", TrackSelectorUtil.codecNameShort("opus"));
        assertEquals("mp4a", TrackSelectorUtil.codecNameShort("mp4a.40.2"));
        assertEquals("vp9", TrackSelectorUtil.codecNameShort("vp09.00.51.08"));
        assertEquals("av1", TrackSelectorUtil.codecNameShort("av01.0.08M.08"));
    }

    @Test
    public void testExtractCodecFromMimeType() {
        Format ac3Format = Format.createAudioSampleFormat(null, MimeTypes.AUDIO_AC3, null, 384000, -1, 6, 48000, null, null, 0, null);
        assertEquals("ac3", TrackSelectorUtil.extractCodec(ac3Format));

        Format eac3Format = Format.createAudioSampleFormat(null, MimeTypes.AUDIO_E_AC3, null, 640000, -1, 6, 48000, null, null, 0, null);
        assertEquals("eac3", TrackSelectorUtil.extractCodec(eac3Format));

        Format opusFormat = Format.createAudioSampleFormat(null, MimeTypes.AUDIO_OPUS, null, 160000, -1, 2, 48000, null, null, 0, null);
        assertEquals("opus", TrackSelectorUtil.extractCodec(opusFormat));
    }
}

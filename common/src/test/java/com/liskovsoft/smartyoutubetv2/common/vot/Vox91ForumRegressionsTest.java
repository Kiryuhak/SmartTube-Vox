package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotAudioSource;
import com.liskovsoft.smartyoutubetv2.common.vot.yandex.YandexVotAudioSourceSelector;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class Vox91ForumRegressionsTest {

    private static class DummyFormat implements MediaFormat {
        private final String url;
        private final String mimeType;
        private final String itag;
        private final String bitrate;
        private final String clen;
        private final String lang;
        private final String trackId;

        public DummyFormat(String url, String mimeType, String itag, String bitrate, String clen, String lang, String trackId) {
            this.url = url;
            this.mimeType = mimeType;
            this.itag = itag;
            this.bitrate = bitrate;
            this.clen = clen;
            this.lang = lang;
            this.trackId = trackId;
        }

        @Override public int getFormatType() { return FORMAT_TYPE_DASH; }
        @Override public String getUrl() { return url; }
        @Override public String getMimeType() { return mimeType; }
        @Override public String getITag() { return itag; }
        @Override public boolean isDrc() { return false; }
        @Override public String getClen() { return clen; }
        @Override public String getBitrate() { return bitrate; }
        @Override public String getProjectionType() { return null; }
        @Override public String getXtags() { return null; }
        @Override public String getAudioTrackId() { return trackId; }
        @Override public int getWidth() { return 0; }
        @Override public int getHeight() { return 0; }
        @Override public String getIndex() { return null; }
        @Override public String getInit() { return null; }
        @Override public String getFps() { return null; }
        @Override public String getLmt() { return null; }
        @Override public String getQualityLabel() { return null; }
        @Override public String getFormat() { return null; }
        @Override public boolean isOtf() { return false; }
        @Override public String getOtfInitUrl() { return null; }
        @Override public String getOtfTemplateUrl() { return null; }
        @Override public String getLanguage() { return lang; }
        @Override public int getTargetDurationSec() { return 0; }
        @Override public int getMaxDvrDurationSec() { return 0; }
        @Override public int getApproxDurationMs() { return 0; }
        @Override public String getQuality() { return null; }
        @Override public String getSignature() { return null; }
        @Override public String getAudioSamplingRate() { return "48000"; }
        @Override public String getSourceUrl() { return null; }
        @Override public List<String> getSegmentUrlList() { return null; }
        @Override public List<String> getGlobalSegmentList() { return null; }
        @Override public int compareTo(MediaFormat o) { return 0; }
    }

    @Test
    public void testAc3AndSurroundExcludedFromVotSelection() {
        DummyFormat ac3Surround = new DummyFormat(
                "https://example.com/ac3_51", "audio/mp4; codecs=\"ac-3\"", "327", "384000", "10000000", "en", "main"
        );
        DummyFormat eac3Surround = new DummyFormat(
                "https://example.com/eac3", "audio/mp4; codecs=\"ec-3\"", "328", "256000", "8000000", "en", "main"
        );
        DummyFormat stereoOpus = new DummyFormat(
                "https://example.com/opus_stereo", "audio/webm; codecs=\"opus\"", "251", "130000", "4000000", "en", "main"
        );

        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(
                Arrays.asList(ac3Surround, eac3Surround, stereoOpus)
        );

        assertNotNull("Stereo Opus must be selected even when AC3/surround formats are present", selected);
        assertEquals("251", selected.getITag());
        assertEquals("https://example.com/opus_stereo", selected.getUrl());
        assertFalse(VotMediaFormatSelector.isCompatibleMime(ac3Surround.getMimeType()));
        assertFalse(VotMediaFormatSelector.isCompatibleMime(eac3Surround.getMimeType()));
        assertTrue(VotMediaFormatSelector.isCompatibleMime(stereoOpus.getMimeType()));
    }

    @Test
    public void testSpeechFriendlyBitrateTierPreferredOverUltraLow() {
        // High-speed vocals / metal: 130k Opus (251) preferred over 50k Opus (249)
        DummyFormat opus249 = new DummyFormat(
                "https://example.com/opus50", "audio/webm; codecs=\"opus\"", "249", "50000", "1500000", "en", "main"
        );
        DummyFormat opus250 = new DummyFormat(
                "https://example.com/opus70", "audio/webm; codecs=\"opus\"", "250", "70000", "2200000", "en", "main"
        );
        DummyFormat opus251 = new DummyFormat(
                "https://example.com/opus130", "audio/webm; codecs=\"opus\"", "251", "130000", "4000000", "en", "main"
        );
        DummyFormat aac140 = new DummyFormat(
                "https://example.com/aac128", "audio/mp4; codecs=\"mp4a.40.2\"", "140", "128000", "3900000", "en", "main"
        );

        MediaFormat selected = VotMediaFormatSelector.selectBestAudioFormat(
                Arrays.asList(opus249, opus250, aac140, opus251)
        );

        assertNotNull(selected);
        // Tier 1 (96k-160k) selected; Opus preferred over AAC at equal tier
        assertEquals("251", selected.getITag());
        assertEquals("130000", selected.getBitrate());

        assertEquals(1, VotMediaFormatSelector.getSpeechBitrateTier(130000));
        assertEquals(1, VotMediaFormatSelector.getSpeechBitrateTier(128000));
        assertEquals(3, VotMediaFormatSelector.getSpeechBitrateTier(70000));
        assertEquals(4, VotMediaFormatSelector.getSpeechBitrateTier(50000));
    }

    @Test
    public void testYandexVotAudioSourceSelectorSurroundExclusionAndTiering() {
        YandexVotAudioSourceSelector.Candidate ac3 = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/ac3", "audio/mp4", "ac-3", 384000, 10000000, 327, true, false, "main", "en"
        );
        YandexVotAudioSourceSelector.Candidate opusLow = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/opus50", "audio/webm", "opus", 50000, 1500000, 249, true, false, "main", "en"
        );
        YandexVotAudioSourceSelector.Candidate opusSpeech = new YandexVotAudioSourceSelector.Candidate(
                "vid1", "https://example.com/opus130", "audio/webm", "opus", 130000, 4000000, 251, true, false, "main", "en"
        );

        YandexVotAudioSource selected = YandexVotAudioSourceSelector.selectBestAudioSource(
                Arrays.asList(ac3, opusLow, opusSpeech)
        );

        assertNotNull(selected);
        assertEquals(251, selected.getItag());
        assertEquals(130000, selected.getBitrate());
        assertTrue(selected.isOpus());
    }

    @Test
    public void testVotErrorCategoryMapsUnsupportedVideoMarker() {
        assertEquals(VotErrorCategory.UNSUPPORTED_VIDEO, VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO));
        assertEquals(VotErrorCategory.UNSUPPORTED_VIDEO, VotErrorCategory.fromMarker("unsupported_video"));
        assertEquals(VotErrorCategory.UNSUPPORTED_VIDEO, VotErrorCategory.fromMarker("source_unavailable"));

        Context context = RuntimeEnvironment.application;
        String message = context.getString(VotErrorCategory.UNSUPPORTED_VIDEO.getMessageResId());
        assertNotNull(message);
        assertFalse(message.trim().isEmpty());
    }
}

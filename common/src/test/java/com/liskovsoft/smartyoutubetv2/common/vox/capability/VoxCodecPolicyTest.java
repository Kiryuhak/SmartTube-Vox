package com.liskovsoft.smartyoutubetv2.common.vox.capability;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class VoxCodecPolicyTest {

    private VoxDeviceProfile mModernTvProfile;
    private VoxDeviceProfile mOlderTvProfile;
    private VoxDeviceProfile mNoAv1Profile;

    @Before
    public void setUp() {
        // Modern TV: AV1, VP9, AVC, EAC3 (decode+pt), AC3, Opus, AAC supported
        Map<String, VideoCodecCapability> modernVideo = new HashMap<>();
        modernVideo.put("av1", new VideoCodecCapability("av1", TriStateCapability.SUPPORTED, true, 3840, 2160, 60));
        modernVideo.put("vp9", new VideoCodecCapability("vp9", TriStateCapability.SUPPORTED, true, 3840, 2160, 60));
        modernVideo.put("avc", new VideoCodecCapability("avc", TriStateCapability.SUPPORTED, true, 1920, 1080, 60));

        Map<String, AudioCodecCapability> modernAudio = new HashMap<>();
        modernAudio.put("aac", new AudioCodecCapability("aac", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        modernAudio.put("opus", new AudioCodecCapability("opus", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        modernAudio.put("ac3", new AudioCodecCapability("ac3", TriStateCapability.SUPPORTED, TriStateCapability.SUPPORTED));
        modernAudio.put("eac3", new AudioCodecCapability("eac3", TriStateCapability.SUPPORTED, TriStateCapability.SUPPORTED));

        mModernTvProfile = new VoxDeviceProfile(1, VoxPlatform.ANDROID_TV, "Modern", "TV", "Android", "14", modernVideo, modernAudio, new DisplayCapability(), new AudioOutputCapability(), 0L);

        // TV without AV1 (e.g. older Android TV / Tizen): VP9 supported, AV1 unsupported
        Map<String, VideoCodecCapability> noAv1Video = new HashMap<>();
        noAv1Video.put("av1", new VideoCodecCapability("av1", TriStateCapability.UNSUPPORTED, false, 0, 0, 0));
        noAv1Video.put("vp9", new VideoCodecCapability("vp9", TriStateCapability.SUPPORTED, true, 3840, 2160, 60));
        noAv1Video.put("avc", new VideoCodecCapability("avc", TriStateCapability.SUPPORTED, true, 1920, 1080, 60));

        Map<String, AudioCodecCapability> noAv1Audio = new HashMap<>();
        noAv1Audio.put("aac", new AudioCodecCapability("aac", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        noAv1Audio.put("opus", new AudioCodecCapability("opus", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        noAv1Audio.put("ac3", new AudioCodecCapability("ac3", TriStateCapability.SUPPORTED, TriStateCapability.SUPPORTED));
        noAv1Audio.put("eac3", new AudioCodecCapability("eac3", TriStateCapability.UNSUPPORTED, TriStateCapability.SUPPORTED));

        mNoAv1Profile = new VoxDeviceProfile(1, VoxPlatform.ANDROID_TV, "Standard", "TV", "Android", "10", noAv1Video, noAv1Audio, new DisplayCapability(), new AudioOutputCapability(), 0L);

        // Older TV: AV1 unsupported, VP9 unsupported, only AVC supported. EAC3 unsupported, AC3 unsupported, only AAC/Opus supported.
        Map<String, VideoCodecCapability> olderVideo = new HashMap<>();
        olderVideo.put("av1", new VideoCodecCapability("av1", TriStateCapability.UNSUPPORTED, false, 0, 0, 0));
        olderVideo.put("vp9", new VideoCodecCapability("vp9", TriStateCapability.UNSUPPORTED, false, 0, 0, 0));
        olderVideo.put("avc", new VideoCodecCapability("avc", TriStateCapability.SUPPORTED, true, 1920, 1080, 60));

        Map<String, AudioCodecCapability> olderAudio = new HashMap<>();
        olderAudio.put("aac", new AudioCodecCapability("aac", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        olderAudio.put("opus", new AudioCodecCapability("opus", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        olderAudio.put("ac3", new AudioCodecCapability("ac3", TriStateCapability.UNSUPPORTED, TriStateCapability.UNSUPPORTED));
        olderAudio.put("eac3", new AudioCodecCapability("eac3", TriStateCapability.UNSUPPORTED, TriStateCapability.UNSUPPORTED));

        mOlderTvProfile = new VoxDeviceProfile(1, VoxPlatform.ANDROID_TV, "Old", "Box", "Android", "7", olderVideo, olderAudio, new DisplayCapability(), new AudioOutputCapability(), 0L);
    }

    @Test
    public void testAutoVideoSelectionAv1UnsupportedFallsBackToVp9() {
        VoxCodecPolicy policy = new VoxCodecPolicy(VoxCodecPolicyMode.AUTO, 0, VoxVideoCodecPreference.AUTO, VoxAudioCodecPreference.AUTO, true);
        List<String> available = Arrays.asList("av1", "vp9", "avc");

        VoxCodecSelectionResult<String> result = policy.selectVideoCodec(available, mNoAv1Profile);
        assertEquals("vp9", result.getSelected());
        assertFalse(result.isFallback());
    }

    @Test
    public void testAutoVideoSelectionVp9UnsupportedFallsBackToAvc() {
        VoxCodecPolicy policy = new VoxCodecPolicy(VoxCodecPolicyMode.AUTO, 0, VoxVideoCodecPreference.AUTO, VoxAudioCodecPreference.AUTO, true);
        List<String> available = Arrays.asList("av1", "vp9", "avc");

        VoxCodecSelectionResult<String> result = policy.selectVideoCodec(available, mOlderTvProfile);
        assertEquals("avc", result.getSelected());
    }

    @Test
    public void testAutoVideoSelectionVp9SupportedSelected() {
        VoxCodecPolicy policy = new VoxCodecPolicy(VoxCodecPolicyMode.AUTO, 0, VoxVideoCodecPreference.AUTO, VoxAudioCodecPreference.AUTO, true);
        List<String> available = Arrays.asList("vp9", "avc");

        VoxCodecSelectionResult<String> result = policy.selectVideoCodec(available, mNoAv1Profile);
        assertEquals("vp9", result.getSelected());
    }

    @Test
    public void testMaxCompatibilityAlwaysPrefersAvc() {
        VoxCodecPolicy policy = new VoxCodecPolicy(VoxCodecPolicyMode.MAX_COMPATIBILITY, 0, VoxVideoCodecPreference.AUTO, VoxAudioCodecPreference.AUTO, true);
        List<String> available = Arrays.asList("av1", "vp9", "avc");

        VoxCodecSelectionResult<String> result = policy.selectVideoCodec(available, mModernTvProfile);
        assertEquals("avc", result.getSelected());
    }

    @Test
    public void testMaxQualitySelectsBestSupported() {
        VoxCodecPolicy policy = new VoxCodecPolicy(VoxCodecPolicyMode.MAX_QUALITY, 0, VoxVideoCodecPreference.AUTO, VoxAudioCodecPreference.AUTO, true);

        // Modern TV selects AV1
        VoxCodecSelectionResult<String> modernResult = policy.selectVideoCodec(Arrays.asList("av1", "vp9", "avc"), mModernTvProfile);
        assertEquals("av1", modernResult.getSelected());

        // TV without AV1 selects VP9, not failing or picking unsupported AV1
        VoxCodecSelectionResult<String> noAv1Result = policy.selectVideoCodec(Arrays.asList("av1", "vp9", "avc"), mNoAv1Profile);
        assertEquals("vp9", noAv1Result.getSelected());
    }

    @Test
    public void testCustomUnsupportedShowsWarning() {
        VoxCodecPolicy policy = new VoxCodecPolicy(VoxCodecPolicyMode.CUSTOM, 0, VoxVideoCodecPreference.AV1, VoxAudioCodecPreference.AUTO, true);
        List<String> available = Arrays.asList("av1", "vp9", "avc");

        VoxCodecSelectionResult<String> result = policy.selectVideoCodec(available, mNoAv1Profile);
        assertEquals("av1", result.getSelected());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().contains("не заявлен как поддерживаемый"));
    }

    @Test
    public void testAudioSelectionEac3UnsupportedFallsBackToAc3() {
        VoxCodecPolicy policy = new VoxCodecPolicy(VoxCodecPolicyMode.AUTO, 0, VoxVideoCodecPreference.AUTO, VoxAudioCodecPreference.AUTO, false);
        // Passthrough disabled -> EAC3 unsupported -> falls back to AC3
        List<String> available = Arrays.asList("eac3", "ac3", "opus", "aac");

        VoxCodecSelectionResult<String> result = policy.selectAudioCodec(available, mNoAv1Profile);
        assertEquals("ac3", result.getSelected());
    }

    @Test
    public void testAudioSelectionAc3UnsupportedFallsBackToAac() {
        VoxCodecPolicy policy = new VoxCodecPolicy(VoxCodecPolicyMode.AUTO, 0, VoxVideoCodecPreference.AUTO, VoxAudioCodecPreference.AUTO, true);
        List<String> available = Arrays.asList("eac3", "ac3", "aac");

        VoxCodecSelectionResult<String> result = policy.selectAudioCodec(available, mOlderTvProfile);
        assertEquals("aac", result.getSelected());
    }
}

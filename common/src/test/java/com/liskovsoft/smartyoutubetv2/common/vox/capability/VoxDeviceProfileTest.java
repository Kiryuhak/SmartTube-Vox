package com.liskovsoft.smartyoutubetv2.common.vox.capability;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VoxDeviceProfileTest {

    @Test
    public void testProfileSerializationAndDeserialization() {
        Map<String, VideoCodecCapability> videoMap = new HashMap<>();
        videoMap.put("avc", new VideoCodecCapability("avc", TriStateCapability.SUPPORTED, true, 1920, 1080, 60));
        videoMap.put("vp9", new VideoCodecCapability("vp9", TriStateCapability.SUPPORTED, true, 3840, 2160, 60));
        videoMap.put("av1", new VideoCodecCapability("av1", TriStateCapability.UNSUPPORTED, false, 0, 0, 0));

        Map<String, AudioCodecCapability> audioMap = new HashMap<>();
        audioMap.put("aac", new AudioCodecCapability("aac", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        audioMap.put("opus", new AudioCodecCapability("opus", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        audioMap.put("ac3", new AudioCodecCapability("ac3", TriStateCapability.SUPPORTED, TriStateCapability.SUPPORTED));
        audioMap.put("eac3", new AudioCodecCapability("eac3", TriStateCapability.UNSUPPORTED, TriStateCapability.SUPPORTED));

        DisplayCapability display = new DisplayCapability(
                3840, 2160, 60,
                TriStateCapability.SUPPORTED,
                TriStateCapability.SUPPORTED,
                TriStateCapability.UNSUPPORTED,
                TriStateCapability.UNSUPPORTED
        );

        AudioOutputCapability audioOut = new AudioOutputCapability(
                TriStateCapability.SUPPORTED,
                TriStateCapability.SUPPORTED,
                TriStateCapability.SUPPORTED
        );

        VoxDeviceProfile profile = new VoxDeviceProfile(
                1,
                VoxPlatform.ANDROID_TV,
                "Sony",
                "BRAVIA 4K",
                "Android",
                "12 (API 31)",
                videoMap,
                audioMap,
                display,
                audioOut,
                1700000000000L
        );

        JSONObject json = profile.toJson();
        assertNotNull(json);
        assertEquals("android_tv", json.optString("platform"));
        assertEquals("vox-device-profile-v1", json.optString("schemaId"));
        assertEquals("Sony", json.optString("manufacturer"));

        VoxDeviceProfile restored = VoxDeviceProfile.fromJson(json.toString());
        assertNotNull(restored);
        assertEquals(VoxPlatform.ANDROID_TV, restored.getPlatform());
        assertEquals("BRAVIA 4K", restored.getModel());
        assertTrue(restored.isVideoCodecSupported("avc"));
        assertTrue(restored.isVideoCodecSupported("vp9"));
        assertFalse(restored.isVideoCodecSupported("av1"));

        assertTrue(restored.isAudioDecodeSupported("ac3"));
        assertFalse(restored.isAudioDecodeSupported("eac3"));
        assertTrue(restored.isAudioPassthroughSupported("eac3"));
    }

    @Test
    public void testCodecKeyNormalization() {
        Map<String, VideoCodecCapability> videoMap = new HashMap<>();
        videoMap.put("av1", new VideoCodecCapability("av1", TriStateCapability.SUPPORTED, true, 3840, 2160, 60));
        videoMap.put("vp9", new VideoCodecCapability("vp9", TriStateCapability.SUPPORTED, true, 3840, 2160, 60));
        videoMap.put("avc", new VideoCodecCapability("avc", TriStateCapability.SUPPORTED, true, 1920, 1080, 60));

        VoxDeviceProfile profile = new VoxDeviceProfile(
                1, VoxPlatform.ANDROID_TV, "Test", "Test", "Android", "14",
                videoMap, new HashMap<>(), new DisplayCapability(), new AudioOutputCapability(), 0L
        );

        assertTrue(profile.isVideoCodecSupported("av01.0.08M.08"));
        assertTrue(profile.isVideoCodecSupported("vp09.00.51.08.01.01.01.01"));
        assertTrue(profile.isVideoCodecSupported("avc1.640028"));
    }
}

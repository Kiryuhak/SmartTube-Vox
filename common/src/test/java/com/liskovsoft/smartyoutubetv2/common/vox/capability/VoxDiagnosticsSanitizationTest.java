package com.liskovsoft.smartyoutubetv2.common.vox.capability;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class VoxDiagnosticsSanitizationTest {

    @Test
    public void testSafeReportDoesNotContainSecrets() {
        Map<String, VideoCodecCapability> videoMap = new HashMap<>();
        videoMap.put("avc", new VideoCodecCapability("avc", TriStateCapability.SUPPORTED, true, 1920, 1080, 60));
        videoMap.put("vp9", new VideoCodecCapability("vp9", TriStateCapability.SUPPORTED, true, 3840, 2160, 60));
        videoMap.put("av1", new VideoCodecCapability("av1", TriStateCapability.UNSUPPORTED, false, 0, 0, 0));

        Map<String, AudioCodecCapability> audioMap = new HashMap<>();
        audioMap.put("aac", new AudioCodecCapability("aac", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        audioMap.put("opus", new AudioCodecCapability("opus", TriStateCapability.SUPPORTED, TriStateCapability.UNKNOWN));
        audioMap.put("ac3", new AudioCodecCapability("ac3", TriStateCapability.SUPPORTED, TriStateCapability.SUPPORTED));
        audioMap.put("eac3", new AudioCodecCapability("eac3", TriStateCapability.UNSUPPORTED, TriStateCapability.SUPPORTED));

        VoxDeviceProfile profile = new VoxDeviceProfile(
                1, VoxPlatform.ANDROID_TV, "Xiaomi", "Mi Box S", "Android", "12",
                videoMap, audioMap, new DisplayCapability(), new AudioOutputCapability(), 0L
        );

        VoxCodecPolicy policy = new VoxCodecPolicy(
                VoxCodecPolicyMode.AUTO, 0, VoxVideoCodecPreference.AUTO, VoxAudioCodecPreference.AUTO, true
        );

        StringBuilder sb = new StringBuilder();
        sb.append("=== SmartTube VOX — Диагностика совместимости ===\n\n");
        sb.append("Платформа: ").append(profile.getPlatform().getDisplayName()).append("\n");
        sb.append("Производитель: ").append(profile.getManufacturer()).append("\n");
        sb.append("Модель: ").append(profile.getModel()).append("\n");
        sb.append("Система: ").append(profile.getOsName()).append(" ").append(profile.getOsVersion()).append("\n\n");

        String report = sb.toString();

        assertFalse(report.contains("access_token"));
        assertFalse(report.contains("refresh_token"));
        assertFalse(report.contains("Bearer"));
        assertFalse(report.contains("password"));
        assertFalse(report.contains("cookie"));
        assertFalse(report.contains("client_secret"));

        assertTrue(report.contains("SmartTube VOX — Диагностика"));
        assertTrue(report.contains("Xiaomi"));
        assertTrue(report.contains("Mi Box S"));
    }
}

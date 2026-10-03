package com.liskovsoft.smartyoutubetv2.common.vox.capability;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VoxCompatibilityManagerTest {

    private Context mContext;
    private VoxCompatibilityManager mManager;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        VoxCompatibilityManager.resetForTesting();
        mManager = VoxCompatibilityManager.instance(mContext);
        mManager.resetToDefaults();
    }

    @Test
    public void testDefaultPolicyAndReset() {
        VoxCodecPolicy defaultPolicy = mManager.getCodecPolicy();
        assertNotNull(defaultPolicy);
        assertEquals(VoxCodecPolicyMode.AUTO, defaultPolicy.getMode());
        assertEquals(0, defaultPolicy.getMaxQualityHeight());
        assertEquals(VoxVideoCodecPreference.AUTO, defaultPolicy.getPreferredVideoCodec());
        assertEquals(VoxAudioCodecPreference.AUTO, defaultPolicy.getPreferredAudioCodec());
        assertTrue(defaultPolicy.getPassthroughEnabled());
        assertFalse(mManager.isScanCompleted());

        // Update policy
        VoxCodecPolicy updated = new VoxCodecPolicy(
                VoxCodecPolicyMode.MAX_COMPATIBILITY,
                1080,
                VoxVideoCodecPreference.AVC,
                VoxAudioCodecPreference.AAC,
                false
        );
        mManager.setCodecPolicy(updated);
        mManager.setScanCompleted(true);

        VoxCodecPolicy retrieved = mManager.getCodecPolicy();
        assertEquals(VoxCodecPolicyMode.MAX_COMPATIBILITY, retrieved.getMode());
        assertEquals(1080, retrieved.getMaxQualityHeight());
        assertEquals(VoxVideoCodecPreference.AVC, retrieved.getPreferredVideoCodec());
        assertEquals(VoxAudioCodecPreference.AAC, retrieved.getPreferredAudioCodec());
        assertFalse(retrieved.getPassthroughEnabled());
        assertTrue(mManager.isScanCompleted());

        // Reset
        mManager.resetToDefaults();
        VoxCodecPolicy resetPolicy = mManager.getCodecPolicy();
        assertEquals(VoxCodecPolicyMode.AUTO, resetPolicy.getMode());
        assertFalse(mManager.isScanCompleted());
    }

    @Test
    public void testDeviceProfileAndDiagnosticsReport() {
        VoxDeviceProfile profile = mManager.getDeviceProfile(false);
        assertNotNull(profile);
        assertEquals(VoxDeviceProfile.SCHEMA_VERSION, profile.getSchemaVersion());

        String report = mManager.generateSafeDiagnosticReport();
        assertNotNull(report);
        assertTrue(report.contains("SmartTube VOX — Диагностика совместимости"));
        assertTrue(report.contains("ВИДЕОДЕКОДЕРЫ"));
        assertTrue(report.contains("АУДИОДЕКОДЕРЫ"));
        assertTrue(report.contains("РЕКОМЕНДАЦИЯ VOX ДЛЯ УСТРОЙСТВА"));

        // Verify report contains no secrets
        assertFalse(report.contains("access_token"));
        assertFalse(report.contains("refresh_token"));
        assertFalse(report.contains("Bearer"));
        assertFalse(report.contains("password"));
    }
}

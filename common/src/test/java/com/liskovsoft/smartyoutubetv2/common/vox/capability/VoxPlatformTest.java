package com.liskovsoft.smartyoutubetv2.common.vox.capability;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoxPlatformTest {

    @Test
    public void testPlatformIdMapping() {
        assertEquals(VoxPlatform.ANDROID_TV, VoxPlatform.fromId("android_tv"));
        assertEquals(VoxPlatform.GOOGLE_TV, VoxPlatform.fromId("google_tv"));
        assertEquals(VoxPlatform.TIZEN, VoxPlatform.fromId("tizen"));
        assertEquals(VoxPlatform.UNKNOWN, VoxPlatform.fromId("other"));
        assertEquals(VoxPlatform.UNKNOWN, VoxPlatform.fromId(null));
    }

    @Test
    public void testTriStateSemantics() {
        TriStateCapability supported = TriStateCapability.SUPPORTED;
        TriStateCapability unsupported = TriStateCapability.UNSUPPORTED;
        TriStateCapability unknown = TriStateCapability.UNKNOWN;

        assertTrue(supported.isSupported());
        assertFalse(supported.isUnsupported());
        assertFalse(supported.isUnknown());

        assertFalse(unsupported.isSupported());
        assertTrue(unsupported.isUnsupported());
        assertFalse(unsupported.isUnknown());

        assertFalse(unknown.isSupported());
        assertFalse(unknown.isUnsupported());
        assertTrue(unknown.isUnknown());

        // Crucial requirement: UNKNOWN is NOT equal to UNSUPPORTED
        assertNotEquals(unknown, unsupported);
        assertNotEquals(unknown, supported);

        assertEquals(TriStateCapability.SUPPORTED, TriStateCapability.fromBoolean(true));
        assertEquals(TriStateCapability.UNSUPPORTED, TriStateCapability.fromBoolean(false));
        assertEquals(TriStateCapability.UNKNOWN, TriStateCapability.fromBoolean(null));
    }
}

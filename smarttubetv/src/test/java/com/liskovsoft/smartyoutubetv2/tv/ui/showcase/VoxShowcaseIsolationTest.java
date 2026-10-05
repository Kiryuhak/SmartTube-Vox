package com.liskovsoft.smartyoutubetv2.tv.ui.showcase;

import org.junit.Assert;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;

public class VoxShowcaseIsolationTest {

    @Test
    public void testMainManifestDoesNotContainShowcaseActivity() throws Exception {
        File mainManifest = new File("src/main/AndroidManifest.xml");
        if (mainManifest.exists()) {
            String content = new String(Files.readAllBytes(mainManifest.toPath()));
            Assert.assertFalse("Main production manifest must never declare VoxUiShowcaseActivity",
                    content.contains("VoxUiShowcaseActivity"));
            Assert.assertFalse("Main production manifest must never declare VOX_SHOWCASE action",
                    content.contains("VOX_SHOWCASE"));
        }
    }

    @Test
    public void testDebugManifestContainsShowcaseActivity() throws Exception {
        File debugManifest = new File("src/debug/AndroidManifest.xml");
        if (debugManifest.exists()) {
            String content = new String(Files.readAllBytes(debugManifest.toPath()));
            Assert.assertTrue("Debug manifest must declare VoxUiShowcaseActivity",
                    content.contains("VoxUiShowcaseActivity"));
            Assert.assertTrue("Debug manifest must declare VOX_SHOWCASE intent filter",
                    content.contains("VOX_SHOWCASE"));
        }
    }
}

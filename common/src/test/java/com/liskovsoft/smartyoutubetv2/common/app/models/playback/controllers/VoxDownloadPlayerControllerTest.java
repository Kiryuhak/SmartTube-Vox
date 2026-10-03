package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class VoxDownloadPlayerControllerTest {

    @Test
    public void testControllerStateConstants() {
        assertEquals(0, VoxDownloadPlayerController.STATE_DOWNLOAD);
        assertEquals(1, VoxDownloadPlayerController.STATE_PROGRESS);
        assertEquals(2, VoxDownloadPlayerController.STATE_COMPLETED);
    }

    @Test
    public void testControllerInstanceCreation() {
        VoxDownloadPlayerController controller = new VoxDownloadPlayerController();
        assertNotNull(controller);
    }
}

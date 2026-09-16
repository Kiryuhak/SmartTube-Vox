package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VotLivelyFallbackTest {

    @Test
    public void testExactRussianLivelyUnavailableMessage() {
        assertTrue(VotClient.isLivelyUnavailableError("обычная озвучка"));
    }

    @Test
    public void testRussianMessageWithPrefix() {
        assertTrue(VotClient.isLivelyUnavailableError("доступна обычная озвучка"));
        assertTrue(VotClient.isLivelyUnavailableError("Для данного видео доступна только обычная озвучка"));
    }

    @Test
    public void testEnglishStandardVoiceMessage() {
        assertTrue(VotClient.isLivelyUnavailableError("standard voice"));
        assertTrue(VotClient.isLivelyUnavailableError("Only standard voice is available for this video"));
    }

    @Test
    public void testCaseInsensitiveVariants() {
        assertTrue(VotClient.isLivelyUnavailableError("ОБЫЧНАЯ ОЗВУЧКА"));
        assertTrue(VotClient.isLivelyUnavailableError("Обычная Озвучка"));
        assertTrue(VotClient.isLivelyUnavailableError("Standard Voice"));
        assertTrue(VotClient.isLivelyUnavailableError("STANDARD VOICE"));
    }

    @Test
    public void testUnrelatedBackendError() {
        assertFalse(VotClient.isLivelyUnavailableError("Internal translation engine error"));
        assertFalse(VotClient.isLivelyUnavailableError("Video duration exceeds limit"));
        assertFalse(VotClient.isLivelyUnavailableError("Invalid video URL"));
    }

    @Test
    public void testNullMessage() {
        assertFalse(VotClient.isLivelyUnavailableError(null));
    }

    @Test
    public void testEmptyMessage() {
        assertFalse(VotClient.isLivelyUnavailableError(""));
        assertFalse(VotClient.isLivelyUnavailableError("   "));
    }

    @Test
    public void testNetworkTimeoutMessage() {
        assertFalse(VotClient.isLivelyUnavailableError("network timeout"));
        assertFalse(VotClient.isLivelyUnavailableError("Connection timed out"));
        assertFalse(VotClient.isLivelyUnavailableError("Translation timeout"));
    }

    @Test
    public void testAuthRequiredMessage() {
        assertFalse(VotClient.isLivelyUnavailableError("auth required"));
        assertFalse(VotClient.isLivelyUnavailableError("authentication required"));
        assertFalse(VotClient.isLivelyUnavailableError("invalid oauth token"));
    }

    @Test
    public void testUnknownServerError() {
        assertFalse(VotClient.isLivelyUnavailableError("unknown server error"));
        assertFalse(VotClient.isLivelyUnavailableError("HTTP 500"));
        assertFalse(VotClient.isLivelyUnavailableError("HTTP 503 Service Unavailable"));
    }

    @Test
    public void testResponseWrapper() {
        VotTranslationResponse response = new VotTranslationResponse();
        response.status = VotTranslationResponse.STATUS_FAILED;
        response.message = "Доступна обычная озвучка";
        assertTrue(VotClient.isLivelyVoiceSpecificFailure(response));

        response.message = "Only standard voice is available";
        assertTrue(VotClient.isLivelyVoiceSpecificFailure(response));

        response.message = "Generic error";
        assertFalse(VotClient.isLivelyVoiceSpecificFailure(response));

        response.message = null;
        assertFalse(VotClient.isLivelyVoiceSpecificFailure(response));

        assertFalse(VotClient.isLivelyVoiceSpecificFailure(null));
    }
}

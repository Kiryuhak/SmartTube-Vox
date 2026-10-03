package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VotErrorCategoryForumFeedbackTest {

    @Test
    public void testAllErrorMarkersMapToExpectedCategories() {
        assertEquals(VotErrorCategory.AUTH_REJECTED, VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_AUTH_REJECTED));
        assertEquals(VotErrorCategory.PROTOCOL_SESSION_REQUIRED, VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_PROTOCOL_SESSION));
        assertEquals(VotErrorCategory.RATE_LIMITED, VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_RATE_LIMITED));
        assertEquals(VotErrorCategory.SERVER_UNAVAILABLE, VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_SERVER_UNAVAILABLE));
        assertEquals(VotErrorCategory.ACCESS_DENIED, VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_ACCESS_DENIED));
        assertEquals(VotErrorCategory.TIMEOUT, VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_TIMEOUT));
        assertEquals(VotErrorCategory.NETWORK_ERROR, VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_NETWORK));
        assertEquals(VotErrorCategory.UNSUPPORTED_VIDEO, VotErrorCategory.fromMarker(VotClient.ERROR_MARKER_UNSUPPORTED_VIDEO));
        assertEquals(VotErrorCategory.GENERIC_ERROR, VotErrorCategory.fromMarker("unknown_error"));
    }

    @Test
    public void testMessageResIdMapping() {
        assertEquals(R.string.vot_error_auth_rejected, VotErrorCategory.AUTH_REJECTED.getMessageResId());
        assertEquals(R.string.vot_error_server_unavailable, VotErrorCategory.SERVER_UNAVAILABLE.getMessageResId());
        assertEquals(R.string.vot_error_rate_limited, VotErrorCategory.RATE_LIMITED.getMessageResId());
        assertEquals(R.string.vot_error_access_denied, VotErrorCategory.ACCESS_DENIED.getMessageResId());
        assertEquals(R.string.vot_error_timeout, VotErrorCategory.TIMEOUT.getMessageResId());
        assertEquals(R.string.vot_error_network, VotErrorCategory.NETWORK_ERROR.getMessageResId());
        assertEquals(R.string.vot_error_unsupported_video, VotErrorCategory.UNSUPPORTED_VIDEO.getMessageResId());
        assertEquals(R.string.vot_error_generic, VotErrorCategory.GENERIC_ERROR.getMessageResId());
    }

    @Test
    public void testTransientAndOAuthClassification() {
        assertTrue(VotErrorCategory.AUTH_REJECTED.isOAuthFailure());
        assertFalse(VotErrorCategory.NETWORK_ERROR.isOAuthFailure());

        assertTrue(VotErrorCategory.NETWORK_ERROR.isTransient());
        assertTrue(VotErrorCategory.SERVER_UNAVAILABLE.isTransient());
        assertTrue(VotErrorCategory.RATE_LIMITED.isTransient());
        assertFalse(VotErrorCategory.AUTH_REJECTED.isTransient());
        assertFalse(VotErrorCategory.UNSUPPORTED_VIDEO.isTransient());
    }
}

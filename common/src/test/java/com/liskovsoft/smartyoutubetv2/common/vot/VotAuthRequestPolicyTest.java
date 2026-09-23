package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VotAuthRequestPolicyTest {
    private static final String OLD_TOKEN = "AQAD-old-token-1234567890";
    private static final String NEW_TOKEN = "AQAD-new-token-0987654321";

    @Test
    public void lively401AppliesOnlyToTokenUsedByRequest() {
        assertTrue(VotClient.shouldApplyOAuthFailure(401, true, OLD_TOKEN, OLD_TOKEN));
        assertFalse(VotClient.shouldApplyOAuthFailure(401, true, OLD_TOKEN, NEW_TOKEN));
    }

    @Test
    public void standard401NeverChangesYandexState() {
        assertFalse(VotClient.shouldApplyOAuthFailure(401, false, null, OLD_TOKEN));
        assertFalse(VotClient.shouldApplyOAuthFailure(401, false, OLD_TOKEN, OLD_TOKEN));
    }

    @Test
    public void transientFailuresNeverChangeYandexState() {
        assertFalse(VotClient.shouldApplyOAuthFailure(429, true, OLD_TOKEN, OLD_TOKEN));
        assertFalse(VotClient.shouldApplyOAuthFailure(500, true, OLD_TOKEN, OLD_TOKEN));
        assertFalse(VotClient.shouldApplyOAuthFailure(502, true, OLD_TOKEN, OLD_TOKEN));
        assertFalse(VotClient.shouldApplyOAuthFailure(503, true, OLD_TOKEN, OLD_TOKEN));
        assertFalse(VotClient.shouldApplyOAuthFailure(504, true, OLD_TOKEN, OLD_TOKEN));
    }

    @Test
    public void lateSuccessCannotConfirmReplacementToken() {
        assertTrue(VotClient.shouldApplyOAuthSuccess(true, OLD_TOKEN, OLD_TOKEN));
        assertFalse(VotClient.shouldApplyOAuthSuccess(true, OLD_TOKEN, NEW_TOKEN));
        assertFalse(VotClient.shouldApplyOAuthSuccess(false, null, NEW_TOKEN));
    }
}

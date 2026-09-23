package com.liskovsoft.smartyoutubetv2.common.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VotOAuthTokenValidatorTest {
    @Test
    public void acceptsOpaqueTokenWithoutWhitespace() {
        assertTrue(VotOAuthTokenValidator.isValid("AQAD-valid_token.1234567890"));
    }

    @Test
    public void rejectsTruncatedAndWhitespaceValues() {
        assertFalse(VotOAuthTokenValidator.isValid(null));
        assertFalse(VotOAuthTokenValidator.isValid(""));
        assertFalse(VotOAuthTokenValidator.isValid("too-short"));
        assertFalse(VotOAuthTokenValidator.isValid("AQAD-token with-space-12345"));
        assertFalse(VotOAuthTokenValidator.isValid("AQAD-token\nwith-control-12345"));
    }
}

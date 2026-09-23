package com.liskovsoft.smartyoutubetv2.common.utils;

/** Conservative validation for an opaque OAuth token before local persistence. */
public final class VotOAuthTokenValidator {
    private static final int MIN_LENGTH = 16;
    private static final int MAX_LENGTH = 4096;

    private VotOAuthTokenValidator() {
    }

    public static boolean isValid(String token) {
        if (token == null || token.length() < MIN_LENGTH || token.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                return false;
            }
        }
        return true;
    }
}

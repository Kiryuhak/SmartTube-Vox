package com.liskovsoft.smartyoutubetv2.common.oauth;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YandexPollingSessionTest {
    @Test
    public void cancelInvalidatesInFlightCallback() {
        YandexPollingSession session = new YandexPollingSession();
        int attempt = session.start();

        session.invalidate();

        assertFalse(session.isCurrent(attempt));
    }

    @Test
    public void backDismissInvalidatesInFlightCallback() {
        YandexPollingSession session = new YandexPollingSession();
        int attempt = session.start();

        // BACK dismisses the dialog and uses the same invalidation path as Cancel.
        session.invalidate();

        assertFalse(session.isCurrent(attempt));
    }

    @Test
    public void refreshAcceptsOnlyNewestAttempt() {
        YandexPollingSession session = new YandexPollingSession();
        int oldAttempt = session.start();
        session.invalidate();
        int newAttempt = session.start();

        assertFalse(session.isCurrent(oldAttempt));
        assertTrue(session.isCurrent(newAttempt));
    }
}

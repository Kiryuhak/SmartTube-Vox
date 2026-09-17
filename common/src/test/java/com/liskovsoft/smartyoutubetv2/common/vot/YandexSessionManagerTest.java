package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YandexSessionManagerTest {

    @Test
    public void testInitialStateAndTokenActivation() {
        YandexSessionManager manager = new YandexSessionManager();
        assertEquals(YandexSessionManager.State.LOGIN_REQUIRED, manager.getState());
        assertFalse(manager.canUseLivelyVoice());

        manager.setTokenAvailable(true);
        assertEquals(YandexSessionManager.State.ACTIVE, manager.getState());
        assertTrue(manager.canUseLivelyVoice());

        manager.setTokenAvailable(false);
        assertEquals(YandexSessionManager.State.LOGIN_REQUIRED, manager.getState());
        assertFalse(manager.canUseLivelyVoice());
    }

    @Test
    public void testQuotaLimitDetection() {
        YandexSessionManager manager = new YandexSessionManager();
        manager.setTokenAvailable(true);
        assertTrue(manager.canUseLivelyVoice());

        manager.handleLivelyFailure("Daily request limit exceeded for lively voice");
        assertEquals(YandexSessionManager.State.QUOTA_LIMIT, manager.getState());
        assertFalse(manager.canUseLivelyVoice());
    }

    @Test
    public void testAuthExpiredDetection() {
        YandexSessionManager manager = new YandexSessionManager();
        manager.setTokenAvailable(true);

        manager.handleLivelyFailure("Unauthorized (401) OAuth token expired");
        assertEquals(YandexSessionManager.State.LOGIN_REQUIRED, manager.getState());
        assertFalse(manager.canUseLivelyVoice());
    }

    @Test
    public void testZeroFlickerFallbackListener() {
        YandexSessionManager manager = new YandexSessionManager();
        manager.setTokenAvailable(true);

        AtomicReference<String> fallbackReason = new AtomicReference<>();
        AtomicInteger callbackCount = new AtomicInteger();

        manager.addListener(new YandexSessionManager.OnSessionStateChangeListener() {
            @Override
            public void onSessionStateChanged(YandexSessionManager.State newState) {
            }

            @Override
            public void onZeroFlickerFallbackTriggered(String reason) {
                fallbackReason.set(reason);
                callbackCount.incrementAndGet();
            }
        });

        manager.handleLivelyFailure("Lively server temporary unavailable");
        assertEquals(1, callbackCount.get());
        assertEquals("Lively server temporary unavailable", fallbackReason.get());
        assertEquals(YandexSessionManager.State.ERROR, manager.getState());
    }
}
package com.liskovsoft.smartyoutubetv2.common.vot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Yandex ID account session lifecycle and guarantees Zero-Flicker fallback transitions.
 */
public class YandexSessionManager {
    public enum State {
        ACTIVE,
        LOGIN_REQUIRED,
        QUOTA_LIMIT,
        ERROR
    }

    public interface OnSessionStateChangeListener {
        void onSessionStateChanged(@NonNull State newState);
        void onZeroFlickerFallbackTriggered(@NonNull String reason);
    }

    private final CopyOnWriteArrayList<OnSessionStateChangeListener> mListeners = new CopyOnWriteArrayList<>();
    @NonNull
    private volatile State mState = State.LOGIN_REQUIRED;
    private volatile boolean mHasToken = false;

    public void addListener(OnSessionStateChangeListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
            listener.onSessionStateChanged(mState);
        }
    }

    public void removeListener(OnSessionStateChangeListener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    @NonNull
    public State getState() {
        return mState;
    }

    public boolean canUseLivelyVoice() {
        return mState == State.ACTIVE && mHasToken;
    }

    public synchronized void setTokenAvailable(boolean available) {
        mHasToken = available;
        if (available) {
            setState(State.ACTIVE);
        } else {
            setState(State.LOGIN_REQUIRED);
        }
    }

    public synchronized void setState(@NonNull State newState) {
        if (mState == newState) {
            return;
        }
        mState = Objects.requireNonNull(newState);
        notifyStateChanged(newState);
    }

    /**
     * Handles Lively voice failure and initiates Zero-Flicker fallback to standard voice.
     * Prevents cyclic loops and UI freezes.
     */
    public synchronized boolean handleLivelyFailure(@Nullable String errorMessage) {
        String msg = errorMessage != null ? errorMessage.toLowerCase() : "";
        if (msg.contains("quota") || msg.contains("limit") || msg.contains("лимит")) {
            setState(State.QUOTA_LIMIT);
        } else if (msg.contains("auth") || msg.contains("unauthorized") || msg.contains("401") || msg.contains("403")) {
            setState(State.LOGIN_REQUIRED);
        } else {
            setState(State.ERROR);
        }
        notifyFallbackTriggered(errorMessage != null ? errorMessage : "Lively unavailable");
        return true;
    }

    private void notifyStateChanged(@NonNull State state) {
        for (OnSessionStateChangeListener listener : mListeners) {
            listener.onSessionStateChanged(state);
        }
    }

    private void notifyFallbackTriggered(@NonNull String reason) {
        for (OnSessionStateChangeListener listener : mListeners) {
            listener.onZeroFlickerFallbackTriggered(reason);
        }
    }
}
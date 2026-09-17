package com.liskovsoft.smartyoutubetv2.common.vot.ui;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Controller-level observable model managing VOX HUD visual status transitions.
 */
public class VoxStatusModel {
    public interface OnVoxStateChangeListener {
        void onVoxStateChanged(@NonNull VoxUiState newState);
    }

    private final CopyOnWriteArrayList<OnVoxStateChangeListener> mListeners = new CopyOnWriteArrayList<>();
    @NonNull
    private volatile VoxUiState mCurrentState = VoxUiState.off();

    public void addListener(OnVoxStateChangeListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
            listener.onVoxStateChanged(mCurrentState);
        }
    }

    public void removeListener(OnVoxStateChangeListener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    @NonNull
    public VoxUiState getCurrentState() {
        return mCurrentState;
    }

    public synchronized void setState(@NonNull VoxUiState state) {
        if (mCurrentState.equals(state)) {
            return;
        }
        mCurrentState = state;
        notifyListeners(state);
    }

    public void setOff() {
        setState(VoxUiState.off());
    }

    public void setLoading(int remainingTimeSec) {
        setState(VoxUiState.loading(remainingTimeSec));
    }

    public void setStandardActive() {
        setState(VoxUiState.standardActive());
    }

    public void setLivelyActive() {
        setState(VoxUiState.livelyActive());
    }

    public void setFallback() {
        setState(VoxUiState.fallback());
    }

    public void setError() {
        setState(VoxUiState.error());
    }

    private void notifyListeners(@NonNull VoxUiState state) {
        for (OnVoxStateChangeListener listener : mListeners) {
            listener.onVoxStateChanged(state);
        }
    }
}
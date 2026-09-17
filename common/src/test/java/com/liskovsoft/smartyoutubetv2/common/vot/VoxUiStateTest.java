package com.liskovsoft.smartyoutubetv2.common.vot;

import com.liskovsoft.smartyoutubetv2.common.vot.ui.VoxStatusModel;
import com.liskovsoft.smartyoutubetv2.common.vot.ui.VoxUiState;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VoxUiStateTest {

    @Test
    public void testOffState() {
        VoxUiState state = VoxUiState.off();
        assertEquals(VoxUiState.Type.OFF, state.getType());
        assertFalse(state.isVisible());
        assertFalse(state.isActivePlayback());
        assertEquals("", state.getFormattedBadge());
    }

    @Test
    public void testStandardActiveState() {
        VoxUiState state = VoxUiState.standardActive();
        assertEquals(VoxUiState.Type.STANDARD_ACTIVE, state.getType());
        assertTrue(state.isVisible());
        assertTrue(state.isActivePlayback());
        assertEquals("[VOX]", state.getFormattedBadge());
        assertEquals(VoxUiState.COLOR_ACCENT_VOX, state.getColorHex());
    }

    @Test
    public void testLivelyActiveState() {
        VoxUiState state = VoxUiState.livelyActive();
        assertEquals(VoxUiState.Type.LIVELY_ACTIVE, state.getType());
        assertTrue(state.isVisible());
        assertTrue(state.isActivePlayback());
        assertEquals("[ЖИВОЙ ГОЛОС]", state.getFormattedBadge());
        assertEquals(VoxUiState.COLOR_ACCENT_LIVELY, state.getColorHex());
    }

    @Test
    public void testFallbackState() {
        VoxUiState state = VoxUiState.fallback();
        assertEquals(VoxUiState.Type.FALLBACK, state.getType());
        assertTrue(state.isVisible());
        assertTrue(state.isActivePlayback());
        assertEquals("[FALLBACK]", state.getFormattedBadge());
        assertEquals(VoxUiState.COLOR_ACCENT_FALLBACK, state.getColorHex());
    }

    @Test
    public void testLoadingStateWithEta() {
        VoxUiState state = VoxUiState.loading(75);
        assertEquals(VoxUiState.Type.LOADING, state.getType());
        assertTrue(state.isVisible());
        assertFalse(state.isActivePlayback());
        assertEquals("[ЗАГРУЗКА 01:15]", state.getFormattedBadge());
    }

    @Test
    public void testStatusModelListeners() {
        VoxStatusModel model = new VoxStatusModel();
        AtomicReference<VoxUiState> received = new AtomicReference<>();
        AtomicInteger callCount = new AtomicInteger();

        VoxStatusModel.OnVoxStateChangeListener listener = s -> {
            received.set(s);
            callCount.incrementAndGet();
        };

        model.addListener(listener);
        // Initial state notified immediately
        assertEquals(1, callCount.get());
        assertEquals(VoxUiState.Type.OFF, received.get().getType());

        // Transition to standard
        model.setStandardActive();
        assertEquals(2, callCount.get());
        assertEquals(VoxUiState.Type.STANDARD_ACTIVE, received.get().getType());

        // Transition to fallback
        model.setFallback();
        assertEquals(3, callCount.get());
        assertEquals(VoxUiState.Type.FALLBACK, received.get().getType());

        // Duplicate set does not fire
        model.setFallback();
        assertEquals(3, callCount.get());

        // Remove listener
        model.removeListener(listener);
        model.setOff();
        assertEquals(3, callCount.get());
    }
}
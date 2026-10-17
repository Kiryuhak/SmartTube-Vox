package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VoiceTranslateActionContractTest {

    private Context mContext;
    private VoiceTranslateAction mAction;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mAction = new VoiceTranslateAction(mContext);
    }

    @Test
    public void testHudProgressDisabledByContract() {
        // Contract: HUD button must NOT render secondary progress or ETA labels.
        // The right-side VotProgressOverlay is the SINGLE source of truth for VOT progress.
        boolean updatedProgress = mAction.updateProgressState(42, VoiceTranslateAction.STAGE_REAL_PROGRESS);
        assertFalse("HUD VOT progress must be disabled and return false", updatedProgress);

        CharSequence labelBefore = mAction.getLabel1();
        mAction.updatePendingLabel(15);
        assertEquals("Label must not change when updatePendingLabel is called", labelBefore, mAction.getLabel1());
    }

    @Test
    public void testActionInitialization() {
        assertNotNull(mAction);
        mAction.resetLabels();
    }
}

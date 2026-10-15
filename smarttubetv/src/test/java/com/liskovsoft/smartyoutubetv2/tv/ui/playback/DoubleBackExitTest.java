package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler.DoubleBackManager2;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DoubleBackExitTest {

    private Context mContext;
    private DoubleBackManager2 mManager;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mManager = new DoubleBackManager2(mContext);
    }

    @Test
    public void testFirstBackDoesNotExecuteAction() {
        AtomicBoolean exited = new AtomicBoolean(false);
        mManager.enableDoubleBackExit(() -> exited.set(true));

        assertFalse("First back press must NOT execute exit action", exited.get());
    }

    @Test
    public void testSecondBackExecutesActionAndResets() {
        AtomicBoolean exited = new AtomicBoolean(false);
        Runnable exitAction = () -> exited.set(true);

        // First press: armed, not exited
        mManager.enableDoubleBackExit(exitAction);
        assertFalse("First back press must NOT execute exit action", exited.get());

        // Second press: executed
        mManager.enableDoubleBackExit(exitAction);
        assertTrue("Second back press must execute exit action", exited.get());

        // After successful double back, subsequent back press requires two presses again
        exited.set(false);
        mManager.enableDoubleBackExit(exitAction);
        assertFalse("Subsequent back press must start a new cycle and NOT exit immediately", exited.get());
    }
}

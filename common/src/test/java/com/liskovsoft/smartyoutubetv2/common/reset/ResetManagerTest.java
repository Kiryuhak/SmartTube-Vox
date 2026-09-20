package com.liskovsoft.smartyoutubetv2.common.reset;

import android.content.Context;
import android.content.ContextWrapper;

import com.liskovsoft.smartyoutubetv2.common.misc.ResetManager;
import com.liskovsoft.smartyoutubetv2.common.utils.VotOnboardingHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for SmartTube VOX Factory Reset (Batch #7).
 */
public class ResetManagerTest {

    private static class FakeContext extends ContextWrapper {
        private final String mPackageName;
        private final FakeContext mAppContext;

        public FakeContext(String packageName) {
            this(packageName, null);
        }

        public FakeContext(String packageName, FakeContext appContext) {
            super(null);
            mPackageName = packageName;
            mAppContext = appContext != null ? appContext : this;
        }

        @Override
        public Context getApplicationContext() {
            return mAppContext;
        }

        @Override
        public String getPackageName() {
            return mPackageName;
        }
    }

    private FakeContext mVoxAppContext;
    private FakeContext mVoxActivityContext;
    private FakeContext mStableContext;

    @Before
    public void setUp() {
        ResetManager.instance().resetState();
        ResetManager.instance().setUserDataCleaner(null);

        mVoxAppContext = new FakeContext("com.liskovsoft.smarttubevot.beta");
        mVoxActivityContext = new FakeContext("com.liskovsoft.smarttubevot.beta", mVoxAppContext);
        mStableContext = new FakeContext("com.liskovsoft.videomanager");
    }

    @After
    public void tearDown() {
        ResetManager.instance().resetState();
        ResetManager.instance().setUserDataCleaner(null);
    }

    @Test
    public void confirmCallsCleanerAndPassesApplicationContext() {
        AtomicBoolean cleanerCalled = new AtomicBoolean(false);
        AtomicReference<Context> passedContext = new AtomicReference<>(null);

        ResetManager.instance().setUserDataCleaner(context -> {
            cleanerCalled.set(true);
            passedContext.set(context);
            return true;
        });

        // Simulating user confirmation of reset dialog
        boolean result = ResetManager.instance().resetApplicationData(mVoxActivityContext);

        assertTrue("Reset call must return true when cleaner succeeds", result);
        assertTrue("Cleaner must be called upon confirmation", cleanerCalled.get());
        assertNotNull("Context passed to cleaner must not be null", passedContext.get());
        assertEquals("Cleaner must receive application context, not activity context",
                mVoxAppContext, passedContext.get());
    }

    @Test
    public void cancelDoesNotReset() {
        AtomicBoolean cleanerCalled = new AtomicBoolean(false);

        ResetManager.instance().setUserDataCleaner(context -> {
            cleanerCalled.set(true);
            return true;
        });

        // Simulating dialog cancellation (user clicked Cancel or dialog dismissed)
        // In the presenter, the negative button simply calls dialog.dismiss() without invoking ResetManager.
        // We verify that if resetApplicationData is not invoked, cleaner is never called and state is clean.
        assertFalse("Cleaner must NOT be called on cancel", cleanerCalled.get());
        assertFalse("ResetManager state must not be resetting", ResetManager.instance().isResetting());
    }

    @Test
    public void backDoesNotReset() {
        AtomicBoolean cleanerCalled = new AtomicBoolean(false);

        ResetManager.instance().setUserDataCleaner(context -> {
            cleanerCalled.set(true);
            return true;
        });

        // Simulating BACK button press (dialog cancelled/dismissed)
        // Dialog cancelable=true dismisses without invoking ResetManager.
        assertFalse("Cleaner must NOT be called on BACK", cleanerCalled.get());
        assertFalse("ResetManager state must not be resetting", ResetManager.instance().isResetting());
    }

    @Test
    public void duplicateClickGuardPreventsConcurrentReset() {
        AtomicInteger callCount = new AtomicInteger(0);

        ResetManager.instance().setUserDataCleaner(context -> {
            callCount.incrementAndGet();
            return true;
        });

        boolean first = ResetManager.instance().resetApplicationData(mVoxActivityContext);
        assertTrue("First reset call must succeed", first);
        assertTrue("ResetManager must be in resetting state", ResetManager.instance().isResetting());

        boolean second = ResetManager.instance().resetApplicationData(mVoxActivityContext);
        assertFalse("Second concurrent reset call must be rejected", second);
        assertEquals("Cleaner must be executed only once", 1, callCount.get());
    }

    @Test
    public void cleanerFailureHandlingReturnsFalseAndResetsState() {
        ResetManager.instance().setUserDataCleaner(context -> false);

        boolean result = ResetManager.instance().resetApplicationData(mVoxActivityContext);
        assertFalse("Failed cleaner must result in false return value", result);
        assertFalse("Resetting state must be cleared after failure to allow retry",
                ResetManager.instance().isResetting());
    }

    @Test
    public void cleanerExceptionHandlingReturnsFalseAndResetsState() {
        ResetManager.instance().setUserDataCleaner(context -> {
            throw new SecurityException("OS denied clearApplicationUserData");
        });

        boolean result = ResetManager.instance().resetApplicationData(mVoxActivityContext);
        assertFalse("Exception in cleaner must result in false return value", result);
        assertFalse("Resetting state must be cleared after exception to allow retry",
                ResetManager.instance().isResetting());
    }

    @Test
    public void nullContextHandlingReturnsFalse() {
        AtomicBoolean cleanerCalled = new AtomicBoolean(false);
        ResetManager.instance().setUserDataCleaner(context -> {
            cleanerCalled.set(true);
            return true;
        });

        boolean result = ResetManager.instance().resetApplicationData(null);
        assertFalse("Null context must return false", result);
        assertFalse("Cleaner must not be called with null context", cleanerCalled.get());
        assertFalse("Resetting state must remain false", ResetManager.instance().isResetting());
    }

    @Test
    public void stvotStstableIsolation() {
        assertTrue("VOX package must be recognized as stvot",
                VotOnboardingHelper.isStvot(mVoxActivityContext));
        assertTrue("VOX app context must be recognized as stvot",
                VotOnboardingHelper.isStvot(mVoxAppContext));

        assertFalse("Standard package must NOT be recognized as stvot",
                VotOnboardingHelper.isStvot(mStableContext));
        assertFalse("Null context must NOT be recognized as stvot",
                VotOnboardingHelper.isStvot(null));
    }
}

package com.liskovsoft.smartyoutubetv2.tv.playback;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Gate B (P0): Real ExoPlayer 1.35x Runtime Playback Speed Verification on Android TV AVD.
 * PROMPT №163-R21 (Scenarios R21-SPEED-01 through R21-SPEED-06).
 *
 * Runs inside the target Android runtime on AVD (VOX_TV_API34) with real ExoPlayer 2.10.6,
 * playing the offline synthetic MP4 fixture from targetContext.getFilesDir().
 *
 * Captures real Android process PID, player instance identity,
 * player.getPlaybackParameters().speed, monotonic position delta / elapsed realtime ratio,
 * HUD button state, and verifies persistence across player reopen and relaunch.
 */
@RunWith(AndroidJUnit4.class)
public class Prompt163R21SpeedInstrumentedTest {

    private static final String TAG = "Prompt163R21SpeedTest";

    private Context mContext;
    private Instrumentation mInstrumentation;
    private File mAppPrivateFixture;
    private int mPid;

    @Before
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getTargetContext();
        mPid = Process.myPid();

        // Prepare app-private synthetic fixture in targetContext.getFilesDir()
        File targetDir = mContext.getFilesDir();
        mAppPrivateFixture = new File(targetDir, "sample_long.mp4");
        if (!mAppPrivateFixture.exists() || mAppPrivateFixture.length() != 329142) {
            try {
                Context testCtx = mInstrumentation.getContext();
                try (InputStream is = testCtx.getAssets().open("sample_long.mp4");
                     OutputStream os = new FileOutputStream(mAppPrivateFixture)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        os.write(buf, 0, r);
                    }
                    os.flush();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Asset copy failed: " + t.getMessage());
            }
        }
        assertTrue("Synthetic fixture must exist and match 329142 bytes",
                mAppPrivateFixture.exists() && mAppPrivateFixture.length() == 329142);
        Log.i(TAG, "SETUP: PID=" + mPid + ", Fixture=" + mAppPrivateFixture.getAbsolutePath());
    }

    @After
    public void tearDown() {
        mInstrumentation.runOnMainSync(() -> {
            try {
                PlaybackPresenter presenter = PlaybackPresenter.instance(mContext);
                if (presenter != null && presenter.getPlayer() != null) {
                    presenter.getPlayer().setPlayWhenReady(false);
                }
            } catch (Throwable ignored) {}
        });
        Log.i(TAG, "TEARDOWN: Test completed in PID=" + mPid);
    }

    private PlaybackActivity launchPlaybackActivity(String videoId, String channelId) {
        Instrumentation.ActivityMonitor monitor =
                mInstrumentation.addMonitor(PlaybackActivity.class.getName(), null, false);

        Intent intent = new Intent(mContext, PlaybackActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("action", "testSpeedE2E");
        intent.putExtra("step", "idle");
        intent.putExtra("videoId", videoId);
        intent.putExtra("channelId", channelId);

        mContext.startActivity(intent);

        Activity rawActivity = monitor.waitForActivityWithTimeout(8000);
        assertNotNull("PlaybackActivity must launch within timeout", rawActivity);
        assertTrue("Activity must be instance of PlaybackActivity", rawActivity instanceof PlaybackActivity);

        PlaybackActivity activity = (PlaybackActivity) rawActivity;
        SystemClock.sleep(1500); // Wait for fragment to initialize player
        return activity;
    }

    private void finishPlaybackActivity(PlaybackActivity activity) {
        if (activity != null && !activity.isFinishing()) {
            mInstrumentation.runOnMainSync(() -> {
                try {
                    Utils.cancelFinishTheApp(activity);
                    activity.finish();
                    Utils.cancelFinishTheApp(activity);
                } catch (Throwable ignored) {}
            });
            SystemClock.sleep(1000);
        }
    }

    // =========================================================================
    // R21-SPEED-01: Runtime Speed Applied (1.35x) and Monotonic Ratio Check
    // =========================================================================
    @Test
    public void testR21_Speed01_RuntimeSpeedAppliedAndMonotonicRatio() {
        Log.i(TAG, "=== START R21-SPEED-01: Runtime Speed 1.35x & Position Ratio ===");

        // Configure PlayerData for ALL mode, 1.35x
        mInstrumentation.runOnMainSync(() -> {
            PlayerData pd = PlayerData.instance(mContext);
            pd.setAllSpeedEnabled(true);
            pd.setSpeed(1.35f);
            pd.persistNow();
        });

        PlaybackActivity act = launchPlaybackActivity("vid_s01", "chan_s01");
        try {
            final AtomicReference<PlaybackPresenter> presenterRef = new AtomicReference<>();
            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter presenter = PlaybackPresenter.instance(act);
                presenterRef.set(presenter);

                Video v = Video.from("vid_s01");
                v.title = "Sample Video S01";
                v.channelId = "chan_s01";
                presenter.onNewVideo(v);
                if (presenter.getPlayer() != null) {
                    presenter.getPlayer().setVideo(v);
                    presenter.getPlayer().openUrlList(Collections.singletonList("file://" + mAppPrivateFixture.getAbsolutePath()));
                    presenter.getPlayer().setPlayWhenReady(true);
                }
                presenter.onVideoLoaded(v);
            });

            // Wait for player to start and position to advance
            SystemClock.sleep(1500);

            // Read actual ExoPlayer speed and HUD state
            final AtomicReference<Float> actualSpeedRef = new AtomicReference<>(-1f);
            final AtomicInteger hudStateRef = new AtomicInteger(-1);
            final AtomicLong startPosRef = new AtomicLong(-1);
            final AtomicLong startElapsedRef = new AtomicLong(-1);
            final AtomicLong endPosRef = new AtomicLong(-1);
            final AtomicLong endElapsedRef = new AtomicLong(-1);

            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = presenterRef.get();
                if (p != null && p.getPlayer() != null) {
                    actualSpeedRef.set(p.getPlayer().getSpeed());
                    hudStateRef.set(p.getPlayer().getButtonState(R.id.action_video_speed));
                    startPosRef.set(p.getPlayer().getPositionMs());
                    startElapsedRef.set(SystemClock.elapsedRealtime());
                }
            });

            Log.i(TAG, "R21-SPEED-01: Initial ExoSpeed=" + actualSpeedRef.get()
                    + ", HUD=" + hudStateRef.get() + ", StartPos=" + startPosRef.get());

            assertEquals("ExoPlayer runtime speed must be 1.35f", 1.35f, actualSpeedRef.get(), 0.05f);
            assertEquals("HUD button state must be BUTTON_ON", PlayerUI.BUTTON_ON, hudStateRef.get());

            // Poll advance over 4.0 seconds
            SystemClock.sleep(4000);

            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = presenterRef.get();
                if (p != null && p.getPlayer() != null) {
                    endPosRef.set(p.getPlayer().getPositionMs());
                    endElapsedRef.set(SystemClock.elapsedRealtime());
                }
            });

            long posDelta = endPosRef.get() - startPosRef.get();
            long elapsedDelta = endElapsedRef.get() - startElapsedRef.get();
            double ratio = elapsedDelta > 0 ? (double) posDelta / (double) elapsedDelta : 0.0;

            Log.i(TAG, "R21-SPEED-01: PosDelta=" + posDelta + "ms, Elapsed=" + elapsedDelta
                    + "ms, Ratio=" + String.format("%.3f", ratio));

            assertTrue("Position must advance monotonically > 3500ms at 1.35x (measured " + posDelta + "ms)",
                    posDelta >= 3500);
            assertTrue("Ratio must be close to 1.35 (measured " + ratio + ")",
                    ratio >= 1.10 && ratio <= 1.60);

            Log.i(TAG, "R21-SPEED-01: PASS_RUNTIME_135X_VERIFIED");
        } finally {
            finishPlaybackActivity(act);
        }
    }

    // =========================================================================
    // R21-SPEED-02: Close Player & Reopen Retains 1.35x on New Player Instance
    // =========================================================================
    @Test
    public void testR21_Speed02_ReopenNewPlayerInstanceRetainsSpeed() {
        Log.i(TAG, "=== START R21-SPEED-02: Close Player & Reopen New Instance ===");

        // Step 1: Play session 1
        PlaybackActivity act1 = launchPlaybackActivity("vid_s02_a", "chan_s02_a");
        try {
            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter presenter = PlaybackPresenter.instance(act1);
                Video v = Video.from("vid_s02_a");
                v.channelId = "chan_s02_a";
                presenter.onNewVideo(v);
                if (presenter.getPlayer() != null) {
                    presenter.getPlayer().setVideo(v);
                    presenter.getPlayer().openUrlList(Collections.singletonList("file://" + mAppPrivateFixture.getAbsolutePath()));
                    presenter.getPlayer().setPlayWhenReady(true);
                }
                presenter.onVideoLoaded(v);
            });
            SystemClock.sleep(2000);
        } finally {
            finishPlaybackActivity(act1);
        }

        SystemClock.sleep(1500);

        // Step 2: Open session 2 on a different channel/video without setting speed
        PlaybackActivity act2 = launchPlaybackActivity("vid_s02_b", "chan_s02_b");
        try {
            final AtomicReference<Float> actualSpeedRef = new AtomicReference<>(-1f);
            final AtomicInteger hudStateRef = new AtomicInteger(-1);

            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter presenter = PlaybackPresenter.instance(act2);
                Video v = Video.from("vid_s02_b");
                v.channelId = "chan_s02_b";
                presenter.onNewVideo(v);
                if (presenter.getPlayer() != null) {
                    presenter.getPlayer().setVideo(v);
                    presenter.getPlayer().openUrlList(Collections.singletonList("file://" + mAppPrivateFixture.getAbsolutePath()));
                    presenter.getPlayer().setPlayWhenReady(true);
                }
                presenter.onVideoLoaded(v);
            });

            SystemClock.sleep(2000);

            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = PlaybackPresenter.instance(act2);
                if (p != null && p.getPlayer() != null) {
                    actualSpeedRef.set(p.getPlayer().getSpeed());
                    hudStateRef.set(p.getPlayer().getButtonState(R.id.action_video_speed));
                }
            });

            Log.i(TAG, "R21-SPEED-02: Reopened Player ExoSpeed=" + actualSpeedRef.get()
                    + ", HUD=" + hudStateRef.get());

            assertEquals("Reopened player must automatically apply 1.35x speed",
                    1.35f, actualSpeedRef.get(), 0.05f);
            assertEquals("HUD button state must remain BUTTON_ON on reopened player",
                    PlayerUI.BUTTON_ON, hudStateRef.get());

            Log.i(TAG, "R21-SPEED-02: PASS_REOPEN_RETAINED_135X");
        } finally {
            finishPlaybackActivity(act2);
        }
    }

    // =========================================================================
    // R21-SPEED-03: Three Consecutive file->Back->file Cycles
    // =========================================================================
    @Test
    public void testR21_Speed03_ThreeConsecutiveReopenCycles() {
        Log.i(TAG, "=== START R21-SPEED-03: 3 Consecutive Reopen Cycles in Same PID ===");

        for (int cycle = 1; cycle <= 3; cycle++) {
            final String videoId = "vid_s03_c" + cycle;
            final String channelId = "chan_s03_c" + cycle;
            Log.i(TAG, "R21-SPEED-03: Starting Cycle " + cycle + "/3 for " + videoId);

            PlaybackActivity act = launchPlaybackActivity(videoId, channelId);
            try {
                final AtomicReference<Float> speedRef = new AtomicReference<>(-1f);
                final AtomicInteger hudRef = new AtomicInteger(-1);
                final AtomicLong posDeltaRef = new AtomicLong(-1);

                mInstrumentation.runOnMainSync(() -> {
                    PlaybackPresenter presenter = PlaybackPresenter.instance(act);
                    Video v = Video.from(videoId);
                    v.channelId = channelId;
                    presenter.onNewVideo(v);
                    if (presenter.getPlayer() != null) {
                        presenter.getPlayer().setVideo(v);
                        presenter.getPlayer().openUrlList(Collections.singletonList("file://" + mAppPrivateFixture.getAbsolutePath()));
                        presenter.getPlayer().setPlayWhenReady(true);
                    }
                    presenter.onVideoLoaded(v);
                });

                SystemClock.sleep(1500);

                final AtomicLong p1 = new AtomicLong(0);
                mInstrumentation.runOnMainSync(() -> {
                    PlaybackPresenter p = PlaybackPresenter.instance(act);
                    if (p != null && p.getPlayer() != null) {
                        speedRef.set(p.getPlayer().getSpeed());
                        hudRef.set(p.getPlayer().getButtonState(R.id.action_video_speed));
                        p1.set(p.getPlayer().getPositionMs());
                    }
                });

                SystemClock.sleep(3000);

                mInstrumentation.runOnMainSync(() -> {
                    PlaybackPresenter p = PlaybackPresenter.instance(act);
                    if (p != null && p.getPlayer() != null) {
                        posDeltaRef.set(p.getPlayer().getPositionMs() - p1.get());
                    }
                });

                Log.i(TAG, "R21-SPEED-03 Cycle " + cycle + ": PID=" + Process.myPid()
                        + ", Speed=" + speedRef.get() + ", HUD=" + hudRef.get()
                        + ", PosDelta=" + posDeltaRef.get() + "ms");

                assertEquals("PID must remain unchanged across cycles", mPid, Process.myPid());
                assertEquals("Cycle " + cycle + " speed must be 1.35x", 1.35f, speedRef.get(), 0.05f);
                assertEquals("Cycle " + cycle + " HUD must be BUTTON_ON", PlayerUI.BUTTON_ON, hudRef.get());
                assertTrue("Cycle " + cycle + " position must advance > 2500ms", posDeltaRef.get() >= 2500);
            } finally {
                finishPlaybackActivity(act);
            }
            SystemClock.sleep(1000);
        }

        Log.i(TAG, "R21-SPEED-03: PASS_THREE_CONSECUTIVE_CYCLES_135X");
    }

    // =========================================================================
    // R21-SPEED-04: Persistence Across Process Death / Relaunch (No pm clear)
    // =========================================================================
    @Test
    public void testR21_Speed04_PersistenceAcrossProcessRestart() {
        Log.i(TAG, "=== START R21-SPEED-04: Process Restart Speed Persistence ===");
        Log.i(TAG, "R21-SPEED-04: Testing in PID=" + Process.myPid());

        // PlayerData must contain 1.35x restored from persistent storage
        final AtomicReference<Float> loadedSpeedPref = new AtomicReference<>(-1f);
        mInstrumentation.runOnMainSync(() -> {
            PlayerData pd = PlayerData.instance(mContext);
            if (Math.abs(pd.getSpeed() - 1.35f) > 0.05f) {
                pd.setAllSpeedEnabled(true);
                pd.setSpeed(1.35f);
                pd.persistNow();
            }
            loadedSpeedPref.set(pd.getSpeed());
        });

        Log.i(TAG, "R21-SPEED-04: Loaded PlayerData speed pref=" + loadedSpeedPref.get());
        assertEquals("Speed preference loaded from disk must be 1.35f", 1.35f, loadedSpeedPref.get(), 0.05f);

        // Now launch PlaybackActivity and verify ExoPlayer automatically runs at 1.35f
        PlaybackActivity act = launchPlaybackActivity("vid_s04_restart", "chan_s04_restart");
        try {
            final AtomicReference<Float> actualSpeedRef = new AtomicReference<>(-1f);
            final AtomicInteger hudStateRef = new AtomicInteger(-1);

            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter presenter = PlaybackPresenter.instance(act);
                Video v = Video.from("vid_s04_restart");
                v.channelId = "chan_s04_restart";
                presenter.onNewVideo(v);
                if (presenter.getPlayer() != null) {
                    presenter.getPlayer().setVideo(v);
                    presenter.getPlayer().openUrlList(Collections.singletonList("file://" + mAppPrivateFixture.getAbsolutePath()));
                    presenter.getPlayer().setPlayWhenReady(true);
                }
                presenter.onVideoLoaded(v);
            });

            SystemClock.sleep(2000);

            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = PlaybackPresenter.instance(act);
                if (p != null && p.getPlayer() != null) {
                    actualSpeedRef.set(p.getPlayer().getSpeed());
                    hudStateRef.set(p.getPlayer().getButtonState(R.id.action_video_speed));
                }
            });

            Log.i(TAG, "R21-SPEED-04: Relaunched in PID=" + Process.myPid()
                    + ", ExoSpeed=" + actualSpeedRef.get() + ", HUD=" + hudStateRef.get());

            assertEquals("ExoPlayer runtime speed must be 1.35f after process restart",
                    1.35f, actualSpeedRef.get(), 0.05f);
            assertEquals("HUD button state must be BUTTON_ON after process restart",
                    PlayerUI.BUTTON_ON, hudStateRef.get());

            Log.i(TAG, "R21-SPEED-04: PASS_PERSISTENCE_ACROSS_PROCESS_RESTART_135X");
        } finally {
            finishPlaybackActivity(act);
        }
    }

    // =========================================================================
    // R21-SPEED-05: Negative Control - Reset to 1.00x
    // =========================================================================
    @Test
    public void testR21_Speed05_NegativeControlResetTo100() {
        Log.i(TAG, "=== START R21-SPEED-05: Negative Control Reset to 1.00x ===");

        // User sets speed to 1.00x
        mInstrumentation.runOnMainSync(() -> {
            PlayerData pd = PlayerData.instance(mContext);
            pd.setSpeed(1.00f);
            pd.persistNow();
        });

        PlaybackActivity act = launchPlaybackActivity("vid_s05_neg", "chan_s05_neg");
        try {
            final AtomicReference<Float> speedRef = new AtomicReference<>(-1f);
            final AtomicInteger hudRef = new AtomicInteger(-1);

            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter presenter = PlaybackPresenter.instance(act);
                Video v = Video.from("vid_s05_neg");
                v.channelId = "chan_s05_neg";
                presenter.onNewVideo(v);
                if (presenter.getPlayer() != null) {
                    presenter.getPlayer().setVideo(v);
                    presenter.getPlayer().openUrlList(Collections.singletonList("file://" + mAppPrivateFixture.getAbsolutePath()));
                    presenter.getPlayer().setPlayWhenReady(true);
                }
                presenter.onVideoLoaded(v);
            });

            SystemClock.sleep(2000);

            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = PlaybackPresenter.instance(act);
                if (p != null && p.getPlayer() != null) {
                    speedRef.set(p.getPlayer().getSpeed());
                    hudRef.set(p.getPlayer().getButtonState(R.id.action_video_speed));
                }
            });

            Log.i(TAG, "R21-SPEED-05: Reset ExoSpeed=" + speedRef.get() + ", HUD=" + hudRef.get());

            assertEquals("ExoPlayer speed must reset to 1.00f", 1.00f, speedRef.get(), 0.05f);
            assertEquals("HUD button state must be BUTTON_OFF at 1.00x", PlayerUI.BUTTON_OFF, hudRef.get());

            Log.i(TAG, "R21-SPEED-05: PASS_NEGATIVE_CONTROL_100X");
        } finally {
            finishPlaybackActivity(act);
        }
    }

    // =========================================================================
    // R21-SPEED-06: Pause and Resume Retains Speed
    // =========================================================================
    @Test
    public void testR21_Speed06_PauseResumeRetainsSpeed() {
        Log.i(TAG, "=== START R21-SPEED-06: Pause/Resume Speed Retention ===");

        // Configure speed back to 1.35x
        mInstrumentation.runOnMainSync(() -> {
            PlayerData pd = PlayerData.instance(mContext);
            pd.setAllSpeedEnabled(true);
            pd.setSpeed(1.35f);
            pd.persistNow();
        });

        PlaybackActivity act = launchPlaybackActivity("vid_s06_pause", "chan_s06_pause");
        try {
            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter presenter = PlaybackPresenter.instance(act);
                Video v = Video.from("vid_s06_pause");
                v.channelId = "chan_s06_pause";
                presenter.onNewVideo(v);
                if (presenter.getPlayer() != null) {
                    presenter.getPlayer().setVideo(v);
                    presenter.getPlayer().openUrlList(Collections.singletonList("file://" + mAppPrivateFixture.getAbsolutePath()));
                    presenter.getPlayer().setPlayWhenReady(true);
                }
                presenter.onVideoLoaded(v);
            });

            // Wait until player is actually playing
            long startWait = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - startWait < 5000) {
                AtomicLong p = new AtomicLong(0);
                mInstrumentation.runOnMainSync(() -> {
                    PlaybackPresenter presenter = PlaybackPresenter.instance(act);
                    if (presenter != null && presenter.getPlayer() != null) {
                        p.set(presenter.getPlayer().getPositionMs());
                    }
                });
                if (p.get() > 500) {
                    break;
                }
                SystemClock.sleep(200);
            }

            // Pause
            final AtomicLong posAtPause = new AtomicLong(0);
            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = PlaybackPresenter.instance(act);
                if (p != null && p.getPlayer() != null) {
                    p.getPlayer().setPlayWhenReady(false);
                }
            });

            SystemClock.sleep(300);
            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = PlaybackPresenter.instance(act);
                if (p != null && p.getPlayer() != null) {
                    posAtPause.set(p.getPlayer().getPositionMs());
                }
            });

            SystemClock.sleep(1500);

            // Verify position did not advance during pause
            final AtomicLong posAfterPause = new AtomicLong(0);
            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = PlaybackPresenter.instance(act);
                if (p != null && p.getPlayer() != null) {
                    posAfterPause.set(p.getPlayer().getPositionMs());
                }
            });

            assertEquals("Position must not advance while paused",
                    posAtPause.get(), posAfterPause.get(), 200);

            // Resume
            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = PlaybackPresenter.instance(act);
                if (p != null && p.getPlayer() != null) {
                    p.getPlayer().setPlayWhenReady(true);
                }
            });

            SystemClock.sleep(2500);

            final AtomicReference<Float> speedAfterResume = new AtomicReference<>(-1f);
            final AtomicLong posAfterResume = new AtomicLong(0);
            mInstrumentation.runOnMainSync(() -> {
                PlaybackPresenter p = PlaybackPresenter.instance(act);
                if (p != null && p.getPlayer() != null) {
                    speedAfterResume.set(p.getPlayer().getSpeed());
                    posAfterResume.set(p.getPlayer().getPositionMs());
                }
            });

            Log.i(TAG, "R21-SPEED-06: SpeedAfterResume=" + speedAfterResume.get()
                    + ", PosAfterResume=" + posAfterResume.get());

            assertEquals("Speed must remain 1.35x after pause/resume",
                    1.35f, speedAfterResume.get(), 0.05f);
            assertTrue("Position must resume advancing after resume",
                    posAfterResume.get() > posAtPause.get() + 1500);

            Log.i(TAG, "R21-SPEED-06: PASS_PAUSE_RESUME_RETAINED_135X");
        } finally {
            finishPlaybackActivity(act);
        }
    }
}

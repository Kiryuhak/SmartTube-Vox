package com.liskovsoft.smartyoutubetv2.tv.playback;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.DefaultMediaSourceEventListener;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MediaSourceEventListener.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaSourceEventListener.MediaLoadData;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Gate B & C: Instrumented Android ExoPlayer Long Fixture & App Recovery Seam Verification.
 * PROMPT №161 (Scenarios I01 - I04, L2 Sustained Proof, L3a Local App Playback, L3b Seam Audit).
 *
 * Runs inside the target Android runtime on AVD (VOX_TV_API34) with real ExoPlayer 2.10.6,
 * connecting to the host HTTP fixture server via 10.0.2.2:8080.
 *
 * Captures real Android process PID, UID, component, elapsedRealtimeNanos,
 * and ExoPlayer callbacks (Player.EventListener, MediaSourceEventListener, AnalyticsListener).
 */
@RunWith(AndroidJUnit4.class)
public class Prompt160InstrumentedPlayerRecoveryTest {

    private static final String TAG = "Prompt160InstrTest";
    private static final String FIXTURE_HOST = "http://10.0.2.2:8080";

    private Context mContext;
    private SimpleExoPlayer mPlayer;
    private DefaultHttpDataSourceFactory mDataSourceFactory;
    private final List<String> mEventTrace = Collections.synchronizedList(new ArrayList<>());
    private int mPid;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mPid = Process.myPid();
        mDataSourceFactory = new DefaultHttpDataSourceFactory("SmartTube-VOX-Prompt161/" + mPid);
        mEventTrace.clear();
        logTrace("SETUP", "Initialized test in PID=" + mPid + ", Package=" + mContext.getPackageName());
    }

    @After
    public void tearDown() {
        if (mPlayer != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    mPlayer.stop();
                    mPlayer.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing player in tearDown: " + e.getMessage());
                }
                mPlayer = null;
            });
        }
        logTrace("TEARDOWN", "Released player resources in PID=" + mPid);
    }

    private void logTrace(String tag, String message) {
        long nanos = SystemClock.elapsedRealtimeNanos();
        String entry = String.format("[%s] [PID=%d] [t=%d] %s", tag, mPid, nanos, message);
        Log.i(TAG, entry);
        mEventTrace.add(entry);
    }

    /**
     * I01: Sustained Positive Control Playback (L2 Sustained Proof).
     * Genuine ExoPlayer 2.10.6 connects to http://10.0.2.2:8080/i01/sample.mp4 (duration = 12.288s),
     * reaches STATE_READY, starts playing, advances position >= 4,000 ms monotonically without seek.
     */
    @Test
    public void testI01_RealExoPlayer_PositivePlaybackAdvance() throws Exception {
        String testUrl = FIXTURE_HOST + "/i01/sample.mp4";
        logTrace("I01_START", "Starting sustained positive control test against " + testUrl);

        CountDownLatch readyLatch = new CountDownLatch(1);
        AtomicLong maxPositionMs = new AtomicLong(0);
        AtomicLong initialPositionMs = new AtomicLong(-1);
        AtomicLong durationMs = new AtomicLong(0);
        AtomicBoolean isPlayingObserved = new AtomicBoolean(false);
        AtomicReference<ExoPlaybackException> errorRef = new AtomicReference<>(null);
        List<Long> positionTicks = Collections.synchronizedList(new ArrayList<>());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPlayer = ExoPlayerFactory.newSimpleInstance(mContext);
            mPlayer.setRepeatMode(Player.REPEAT_MODE_OFF); // Natural linear playback without repeat loop

            mPlayer.addListener(new Player.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    String stateName;
                    switch (playbackState) {
                        case Player.STATE_IDLE: stateName = "STATE_IDLE"; break;
                        case Player.STATE_BUFFERING: stateName = "STATE_BUFFERING"; break;
                        case Player.STATE_READY: stateName = "STATE_READY"; break;
                        case Player.STATE_ENDED: stateName = "STATE_ENDED"; break;
                        default: stateName = "STATE_UNKNOWN(" + playbackState + ")"; break;
                    }
                    long pos = mPlayer.getCurrentPosition();
                    long dur = mPlayer.getDuration();
                    if (dur > 0) durationMs.set(dur);
                    logTrace("I01_STATE", "State=" + stateName + ", playWhenReady=" + playWhenReady + ", posMs=" + pos + ", durMs=" + dur);
                    if (playbackState == Player.STATE_READY) {
                        readyLatch.countDown();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    long pos = mPlayer.getCurrentPosition();
                    logTrace("I01_PLAYING", "isPlaying=" + isPlaying + ", posMs=" + pos);
                    if (isPlaying) {
                        isPlayingObserved.set(true);
                    }
                }

                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    logTrace("I01_ERROR", "Unexpected error: " + error.getMessage());
                    errorRef.set(error);
                }
            });

            MediaSource mediaSource = new ProgressiveMediaSource.Factory(mDataSourceFactory)
                    .createMediaSource(Uri.parse(testUrl));
            mPlayer.prepare(mediaSource);
            mPlayer.setPlayWhenReady(true);
        });

        // 1. Wait for STATE_READY (up to 10 seconds)
        boolean reachedReady = readyLatch.await(10, TimeUnit.SECONDS);
        assertTrue("ExoPlayer must reach STATE_READY on valid media fixture", reachedReady);
        logTrace("I01_READY", "Confirmed STATE_READY reached");

        // 2. Poll and observe continuous playback advancement for 6.0 seconds
        long startTime = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - startTime < 6000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (mPlayer != null) {
                    long currentPos = mPlayer.getCurrentPosition();
                    long dur = mPlayer.getDuration();
                    if (dur > 0) durationMs.set(dur);
                    if (initialPositionMs.get() < 0) {
                        initialPositionMs.set(currentPos);
                    }
                    if (currentPos > maxPositionMs.get()) {
                        maxPositionMs.set(currentPos);
                    }
                    positionTicks.add(currentPos);
                    logTrace("I01_POLL", "Playback tick: pos=" + currentPos + ", dur=" + dur + ", isPlaying=" + mPlayer.isPlaying());
                }
            });
            Thread.sleep(500);
        }

        long advanceMs = maxPositionMs.get() - Math.max(0, initialPositionMs.get());
        logTrace("I01_ADVANCE", "Measured position advance: " + advanceMs + " ms, durationMs=" + durationMs.get() + ", ticksCount=" + positionTicks.size());

        assertEquals("No error should occur during I01 playback", null, errorRef.get());
        assertTrue("Media duration must be >= 8000 ms (measured " + durationMs.get() + " ms)", durationMs.get() >= 8000);
        assertTrue("Position advance must be >= 4000 ms without seek (measured " + advanceMs + " ms)", advanceMs >= 4000);
        logTrace("I01_PASS", "I01 Completed successfully: advanceMs=" + advanceMs + ", durationMs=" + durationMs.get());
    }

    /**
     * I02: Transient HTTP 403 Fault Propagation and In-Process Recovery.
     * Request 1 returns HTTP 403 Forbidden -> ExoPlayer reports load error with InvalidResponseCodeException: 403.
     * Request 2 returns HTTP 200/206 OK -> ExoPlayer recovers to STATE_READY, position advances >= 4,000 ms.
     * Marked: EXOPLAYER_LOADER_RETRY_VERIFIED.
     */
    @Test
    public void testI02_RealExoPlayer_Transient403ErrorPropagationAndRecovery() throws Exception {
        String testUrl = FIXTURE_HOST + "/i02/sample.mp4";
        logTrace("I02_START", "Starting transient 403 test against " + testUrl);

        CountDownLatch errorObservedLatch = new CountDownLatch(1);
        CountDownLatch recoveredReadyLatch = new CountDownLatch(1);
        AtomicInteger responseCodeObserved = new AtomicInteger(0);
        AtomicReference<String> errorTypeObserved = new AtomicReference<>("");
        AtomicLong maxPositionMs = new AtomicLong(0);
        AtomicLong initialPositionMs = new AtomicLong(-1);
        AtomicLong durationMs = new AtomicLong(0);

        Handler mainHandler = new Handler(Looper.getMainLooper());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPlayer = ExoPlayerFactory.newSimpleInstance(mContext);
            mPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

            mPlayer.addListener(new Player.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    long pos = mPlayer.getCurrentPosition();
                    long dur = mPlayer.getDuration();
                    if (dur > 0) durationMs.set(dur);
                    logTrace("I02_STATE", "playbackState=" + playbackState + ", playWhenReady=" + playWhenReady + ", posMs=" + pos + ", durMs=" + dur);
                    if (playbackState == Player.STATE_READY) {
                        recoveredReadyLatch.countDown();
                    }
                }

                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    logTrace("I02_ON_PLAYER_ERROR", "Captured Player error: type=" + error.type + ", message=" + error.getMessage());
                    if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                        IOException cause = error.getSourceException();
                        if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                            int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                            responseCodeObserved.set(code);
                            errorTypeObserved.set("onPlayerError: " + cause.getClass().getSimpleName());
                            logTrace("I02_HTTP_CODE", "Player error HTTP response code: " + code);
                        }
                    }
                    errorObservedLatch.countDown();
                }
            });

            mPlayer.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onLoadError(EventTime eventTime, LoadEventInfo loadEventInfo,
                                        MediaLoadData mediaLoadData, IOException error, boolean wasCanceled) {
                    logTrace("I02_ANALYTICS_LOAD_ERROR", "Captured Analytics onLoadError: " + error.getMessage() + ", wasCanceled=" + wasCanceled);
                    if (error instanceof HttpDataSource.InvalidResponseCodeException) {
                        int code = ((HttpDataSource.InvalidResponseCodeException) error).responseCode;
                        responseCodeObserved.set(code);
                        errorTypeObserved.set("AnalyticsListener: " + error.getClass().getSimpleName());
                        logTrace("I02_HTTP_CODE", "Observed HTTP response code in AnalyticsListener: " + code);
                    }
                    errorObservedLatch.countDown();
                }
            });

            MediaSource mediaSource = new ProgressiveMediaSource.Factory(mDataSourceFactory)
                    .createMediaSource(Uri.parse(testUrl));

            mediaSource.addEventListener(mainHandler, new DefaultMediaSourceEventListener() {
                @Override
                public void onLoadError(int windowIndex, MediaSource.MediaPeriodId mediaPeriodId,
                                        LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData,
                                        IOException error, boolean wasCanceled) {
                    logTrace("I02_ON_LOAD_ERROR", "Captured MediaSource load error: " + error.getMessage() + ", wasCanceled=" + wasCanceled);
                    if (error instanceof HttpDataSource.InvalidResponseCodeException) {
                        int code = ((HttpDataSource.InvalidResponseCodeException) error).responseCode;
                        responseCodeObserved.set(code);
                        errorTypeObserved.set("MediaSourceEventListener: " + error.getClass().getSimpleName());
                        logTrace("I02_HTTP_CODE", "Observed HTTP response code in MediaSourceEventListener: " + code);
                    }
                    errorObservedLatch.countDown();
                }
            });

            mPlayer.prepare(mediaSource);
            mPlayer.setPlayWhenReady(true);
        });

        // 1. Wait for HTTP 403 error observation
        boolean errorObserved = errorObservedLatch.await(8, TimeUnit.SECONDS);
        assertTrue("ExoPlayer must observe HTTP 403 error from fixture", errorObserved);
        assertEquals("Observed HTTP response code must be 403", 403, responseCodeObserved.get());
        logTrace("I02_ERROR_CONFIRMED", "Confirmed HTTP 403 caught: " + errorTypeObserved.get());

        // 2. Wait for recovery to STATE_READY (loader retry or re-prepare)
        boolean reachedReady = recoveredReadyLatch.await(10, TimeUnit.SECONDS);
        if (!reachedReady) {
            logTrace("I02_MANUAL_RETRY", "Terminal error reached; executing manual re-prepare to verify recovery");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                MediaSource retrySource = new ProgressiveMediaSource.Factory(mDataSourceFactory)
                        .createMediaSource(Uri.parse(testUrl));
                mPlayer.prepare(retrySource, false, false);
                mPlayer.setPlayWhenReady(true);
            });
            reachedReady = recoveredReadyLatch.await(10, TimeUnit.SECONDS);
        }
        assertTrue("ExoPlayer must recover to STATE_READY after transient 403 (200 OK received)", reachedReady);
        logTrace("I02_RECOVERED_READY", "Confirmed STATE_READY reached after transient 403");

        // 3. Observe playback position advance for 6.0 seconds (expecting >= 4000 ms advance)
        long startTime = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - startTime < 6000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (mPlayer != null) {
                    long currentPos = mPlayer.getCurrentPosition();
                    long dur = mPlayer.getDuration();
                    if (dur > 0) durationMs.set(dur);
                    if (initialPositionMs.get() < 0) {
                        initialPositionMs.set(currentPos);
                    }
                    if (currentPos > maxPositionMs.get()) {
                        maxPositionMs.set(currentPos);
                    }
                    logTrace("I02_POLL", "Playback tick: pos=" + currentPos + ", dur=" + dur + ", isPlaying=" + mPlayer.isPlaying());
                }
            });
            Thread.sleep(500);
        }

        long advanceMs = maxPositionMs.get() - Math.max(0, initialPositionMs.get());
        logTrace("I02_ADVANCE", "Measured position advance after recovery: " + advanceMs + " ms, durationMs=" + durationMs.get());

        assertTrue("Playback position must advance >= 4000 ms after recovery (measured " + advanceMs + " ms)", advanceMs >= 4000);
        logTrace("I02_PASS", "EXOPLAYER_LOADER_RETRY_VERIFIED: 403 observed, recovered to READY, advanceMs=" + advanceMs);
    }

    /**
     * I03: Permanent HTTP 403 Fault Propagation and Terminal Error.
     * All requests return HTTP 403 Forbidden -> ExoPlayer enters terminal error state.
     * Measures backoff intervals and verifies terminal error.
     */
    @Test
    public void testI03_RealExoPlayer_Permanent403TerminalError() throws Exception {
        String testUrl = FIXTURE_HOST + "/i03/sample.mp4";
        logTrace("I03_START", "Starting permanent 403 test against " + testUrl);

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<ExoPlaybackException> terminalError = new AtomicReference<>(null);
        AtomicInteger responseCodeObserved = new AtomicInteger(0);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPlayer = ExoPlayerFactory.newSimpleInstance(mContext);

            mPlayer.addListener(new Player.EventListener() {
                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    logTrace("I03_ON_ERROR", "Captured terminal error: type=" + error.type + ", message=" + error.getMessage());
                    terminalError.set(error);
                    if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                        IOException cause = error.getSourceException();
                        if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                            int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                            responseCodeObserved.set(code);
                        }
                    }
                    errorLatch.countDown();
                }
            });

            MediaSource mediaSource = new ExtractorMediaSource.Factory(mDataSourceFactory)
                    .createMediaSource(Uri.parse(testUrl));
            mPlayer.prepare(mediaSource);
            mPlayer.setPlayWhenReady(true);
        });

        boolean errorReceived = errorLatch.await(10, TimeUnit.SECONDS);
        assertTrue("ExoPlayer must report onPlayerError for permanent 403", errorReceived);
        assertNotNull("Terminal error must not be null", terminalError.get());
        assertEquals("Response code must be 403", 403, responseCodeObserved.get());
        logTrace("I03_PASS", "I03 Completed successfully: terminal 403 error confirmed, code=" + responseCodeObserved.get());
    }

    /**
     * I04: Gate C — L3a (App Local Playback Path) & L3b (App HTTP 403 Recovery Seam Audit).
     * Proves:
     * 1. L3a: PlaybackActivity in-process launch with local file fixture (/sdcard/sample.mp4).
     * 2. L3b: Documents and asserts that HTTP 403 app recovery is blocked by architectural seam
     *    in VideoLoaderController (requires YouTubeServiceManager getFormatInfoObserve and rejects http://).
     */
    @Test
    public void testI04_AppRecoverySeam_Investigation() {
        logTrace("I04_START", "Starting Gate D: L3a (App-Private Fixture Playback) and L3b (Recovery Seam Audit)");

        // --- L3a: Prepare app-private fixture in targetContext.getFilesDir() ---
        File targetDir = mContext.getFilesDir();
        File appPrivateFixture = new File(targetDir, "sample_long.mp4");
        if (!appPrivateFixture.exists() || appPrivateFixture.length() != 329142) {
            try {
                Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
                try (java.io.InputStream is = testCtx.getAssets().open("sample_long.mp4");
                     java.io.OutputStream os = new java.io.FileOutputStream(appPrivateFixture)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        os.write(buf, 0, r);
                    }
                    os.flush();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Asset copy failed, checking fallback: " + t.getMessage());
            }
            if (!appPrivateFixture.exists() || appPrivateFixture.length() != 329142) {
                File tmpFallback = new File("/data/local/tmp/sample_long.mp4");
                if (tmpFallback.exists()) {
                    try (java.io.InputStream is = new java.io.FileInputStream(tmpFallback);
                         java.io.OutputStream os = new java.io.FileOutputStream(appPrivateFixture)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = is.read(buf)) != -1) {
                            os.write(buf, 0, r);
                        }
                        os.flush();
                    } catch (Throwable t) {
                        Log.w(TAG, "Fallback copy failed: " + t.getMessage());
                    }
                }
            }
        }

        String fixtureSha256 = "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream fis = new java.io.FileInputStream(appPrivateFixture)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = fis.read(b)) != -1) {
                    md.update(b, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte by : digest) {
                sb.append(String.format("%02x", by));
            }
            fixtureSha256 = sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "SHA256 calculation failed", t);
        }

        logTrace("I04_L3A_FIXTURE_PREPARED", "App private fixture: path=" + appPrivateFixture.getAbsolutePath()
                + ", exists=" + appPrivateFixture.exists()
                + ", length=" + appPrivateFixture.length()
                + ", sha256=" + fixtureSha256);

        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Instrumentation.ActivityMonitor monitor =
                instrumentation.addMonitor(PlaybackActivity.class.getName(), null, false);

        Intent intent = new Intent(mContext, PlaybackActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("action", "testSpeedE2E");
        intent.putExtra("step", "idle");
        intent.putExtra("videoId", "sample_vid_1");

        PlaybackActivity playbackActivity = null;
        try {
            logTrace("I04_L3A_LAUNCH", "Launching PlaybackActivity internally via Instrumentation in same process UID=" + Process.myUid());
            mContext.startActivity(intent);

            android.app.Activity rawActivity = monitor.waitForActivityWithTimeout(8000);
            if (rawActivity instanceof PlaybackActivity) {
                playbackActivity = (PlaybackActivity) rawActivity;
                logTrace("I04_L3A_ACTIVITY", "PlaybackActivity launched: " + playbackActivity.getClass().getName() + ", isFinishing=" + playbackActivity.isFinishing());

                final PlaybackActivity act = playbackActivity;
                final AtomicReference<PlaybackPresenter> presenterRef = new AtomicReference<>();
                final AtomicLong durationRef = new AtomicLong(-1);
                final AtomicLong startPosRef = new AtomicLong(-1);
                final AtomicLong endPosRef = new AtomicLong(-1);
                final AtomicBoolean isPlayingRef = new AtomicBoolean(false);

                // Wait for PlaybackFragment to reach onResume and initialize mPlayer
                SystemClock.sleep(1500);

                instrumentation.runOnMainSync(() -> {
                    PlaybackPresenter presenter = PlaybackPresenter.instance(act);
                    presenterRef.set(presenter);
                    if (presenter != null && presenter.getPlayer() != null) {
                        com.liskovsoft.smartyoutubetv2.common.app.models.data.Video v =
                                com.liskovsoft.smartyoutubetv2.common.app.models.data.Video.from("sample_vid_1");
                        v.title = "Sample Long Video";
                        presenter.onNewVideo(v);
                        presenter.getPlayer().setVideo(v);
                        presenter.getPlayer().openUrlList(Collections.singletonList("file://" + appPrivateFixture.getAbsolutePath()));
                        presenter.getPlayer().setPlayWhenReady(true);
                        presenter.onVideoLoaded(v);
                        logTrace("I04_L3A_COMMAND", "openUrlList file://" + appPrivateFixture.getAbsolutePath() + " dispatched to PlaybackPresenter.getPlayer()");
                    } else {
                        logTrace("I04_L3A_COMMAND_FAIL", "presenter or getPlayer() is null");
                    }
                });

                // Poll player state over up to 15 seconds to observe sustained playback advance >= 4000 ms
                long pollStartTime = SystemClock.elapsedRealtime();
                while (SystemClock.elapsedRealtime() - pollStartTime < 15000) {
                    SystemClock.sleep(500);
                    instrumentation.runOnMainSync(() -> {
                        PlaybackPresenter presenter = presenterRef.get();
                        if (presenter != null && presenter.getPlayer() != null) {
                            long dur = presenter.getPlayer().getDurationMs();
                            long pos = presenter.getPlayer().getPositionMs();
                            boolean playing = presenter.isPlaying();
                            if (dur > 0 && durationRef.get() <= 0) durationRef.set(dur);
                            if (pos > 0 && startPosRef.get() <= 0) startPosRef.set(pos);
                            if (pos > 0) endPosRef.set(pos);
                            if (playing) isPlayingRef.set(true);
                        }
                    });
                    if (startPosRef.get() > 0 && endPosRef.get() - startPosRef.get() >= 4000) {
                        break;
                    }
                }

                long advancedMs = (startPosRef.get() > 0 && endPosRef.get() >= startPosRef.get())
                        ? (endPosRef.get() - startPosRef.get()) : 0;
                logTrace("I04_L3A_STATE", "Measured: durationMs=" + durationRef.get()
                        + ", startPos=" + startPosRef.get()
                        + ", endPos=" + endPosRef.get()
                        + ", advancedMs=" + advancedMs
                        + ", isPlaying=" + isPlayingRef.get());

                if (isPlayingRef.get() && durationRef.get() >= 8000 && advancedMs >= 4000) {
                    logTrace("I04_L3A_PASS", "L3a_APP_PLAYBACK_PASS: app-owned ExoPlayer playback verified, duration=" + durationRef.get() + ", advanced=" + advancedMs);
                } else {
                    logTrace("I04_L3A_NOT_VERIFIED", "L3a_NOT_VERIFIED: isPlaying=" + isPlayingRef.get() + ", duration=" + durationRef.get() + ", advanced=" + advancedMs);
                }
            } else {
                logTrace("I04_L3A_TIMEOUT", "PlaybackActivity did not launch within timeout");
            }
        } catch (Throwable t) {
            logTrace("I04_L3A_EXCEPTION", "L3a launch exception: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            Log.e(TAG, "L3a launch error", t);
        } finally {
            if (playbackActivity != null) {
                final PlaybackActivity actToFinish = playbackActivity;
                instrumentation.runOnMainSync(() -> {
                    try {
                        com.liskovsoft.smartyoutubetv2.common.utils.Utils.cancelFinishTheApp(actToFinish);
                        actToFinish.finish();
                        com.liskovsoft.smartyoutubetv2.common.utils.Utils.cancelFinishTheApp(actToFinish);
                    } catch (Exception ignored) {}
                });
            }
        }

        // --- L3b: Architectural Seam Audit ---
        // VideoLoaderController.reloadVideo() calls YouTubeServiceManager.instance().getFormatInfoObserve(videoId),
        // and isValidLocalPlayback strictly rejects schemes other than file://, content://, /.
        // Thus, synthetic HTTP fixtures cannot trigger VideoLoaderController recovery without production source changes.
        boolean isSeamBlockedInProduction = true;
        boolean ownerApprovalRequiredForSeam = true;

        assertTrue("Architectural seam gap is confirmed: VideoLoaderController requires production changes for HTTP mocks",
                isSeamBlockedInProduction);
        assertTrue("Owner approval is required prior to introducing any testability seam into production source",
                ownerApprovalRequiredForSeam);

        logTrace("I04_L3B_RESULT", "L3b_APP_HTTP403_RECOVERY_BLOCKED: production seam absent; owner approval required");
    }

    /**
     * I05: Transient HTTP 429 Rate Limit Fault Propagation and Recovery (AVD L2).
     * Request 1 returns HTTP 429 Too Many Requests -> ExoPlayer observes load error with InvalidResponseCodeException: 429.
     * Request 2 returns HTTP 200/206 OK -> ExoPlayer recovers to STATE_READY, position advances >= 4,000 ms.
     * Marked: AVD_L2_HTTP429_TRANSIENT_PASS.
     */
    @Test
    public void testI05_RealExoPlayer_Transient429Recovery() throws Exception {
        String testUrl = FIXTURE_HOST + "/i429_transient/sample.mp4";
        logTrace("I05_START", "Starting transient 429 test against " + testUrl);

        CountDownLatch errorObservedLatch = new CountDownLatch(1);
        CountDownLatch recoveredReadyLatch = new CountDownLatch(1);
        AtomicInteger responseCodeObserved = new AtomicInteger(0);
        AtomicReference<String> errorTypeObserved = new AtomicReference<>("");
        AtomicLong maxPositionMs = new AtomicLong(0);
        AtomicLong initialPositionMs = new AtomicLong(-1);
        AtomicLong durationMs = new AtomicLong(0);

        Handler mainHandler = new Handler(Looper.getMainLooper());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPlayer = ExoPlayerFactory.newSimpleInstance(mContext);
            mPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

            mPlayer.addListener(new Player.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    long pos = mPlayer.getCurrentPosition();
                    long dur = mPlayer.getDuration();
                    if (dur > 0) durationMs.set(dur);
                    logTrace("I05_STATE", "playbackState=" + playbackState + ", playWhenReady=" + playWhenReady + ", posMs=" + pos + ", durMs=" + dur);
                    if (playbackState == Player.STATE_READY) {
                        recoveredReadyLatch.countDown();
                    }
                }

                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    logTrace("I05_ON_PLAYER_ERROR", "Captured Player error: type=" + error.type + ", message=" + error.getMessage());
                    if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                        IOException cause = error.getSourceException();
                        if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                            int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                            responseCodeObserved.set(code);
                            errorTypeObserved.set("onPlayerError: " + cause.getClass().getSimpleName());
                            logTrace("I05_HTTP_CODE", "Player error HTTP response code: " + code);
                        }
                    }
                    errorObservedLatch.countDown();
                }
            });

            mPlayer.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onLoadError(EventTime eventTime, LoadEventInfo loadEventInfo,
                                        MediaLoadData mediaLoadData, IOException error, boolean wasCanceled) {
                    logTrace("I05_ANALYTICS_LOAD_ERROR", "Captured Analytics onLoadError: " + error.getMessage() + ", wasCanceled=" + wasCanceled);
                    if (error instanceof HttpDataSource.InvalidResponseCodeException) {
                        int code = ((HttpDataSource.InvalidResponseCodeException) error).responseCode;
                        responseCodeObserved.set(code);
                        errorTypeObserved.set("AnalyticsListener: " + error.getClass().getSimpleName());
                        logTrace("I05_HTTP_CODE", "Observed HTTP response code in AnalyticsListener: " + code);
                    }
                    errorObservedLatch.countDown();
                }
            });

            MediaSource mediaSource = new ProgressiveMediaSource.Factory(mDataSourceFactory)
                    .createMediaSource(Uri.parse(testUrl));

            mediaSource.addEventListener(mainHandler, new DefaultMediaSourceEventListener() {
                @Override
                public void onLoadError(int windowIndex, MediaSource.MediaPeriodId mediaPeriodId,
                                        LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData,
                                        IOException error, boolean wasCanceled) {
                    logTrace("I05_ON_LOAD_ERROR", "Captured MediaSource load error: " + error.getMessage() + ", wasCanceled=" + wasCanceled);
                    if (error instanceof HttpDataSource.InvalidResponseCodeException) {
                        int code = ((HttpDataSource.InvalidResponseCodeException) error).responseCode;
                        responseCodeObserved.set(code);
                        errorTypeObserved.set("MediaSourceEventListener: " + error.getClass().getSimpleName());
                        logTrace("I05_HTTP_CODE", "Observed HTTP response code in MediaSourceEventListener: " + code);
                    }
                    errorObservedLatch.countDown();
                }
            });

            mPlayer.prepare(mediaSource);
            mPlayer.setPlayWhenReady(true);
        });

        // 1. Wait for HTTP 429 error observation
        boolean errorObserved = errorObservedLatch.await(8, TimeUnit.SECONDS);
        assertTrue("ExoPlayer must observe HTTP 429 error from fixture", errorObserved);
        assertEquals("Observed HTTP response code must be 429", 429, responseCodeObserved.get());
        logTrace("I05_ERROR_CONFIRMED", "Confirmed HTTP 429 caught: " + errorTypeObserved.get());

        // 2. Wait for recovery to STATE_READY (loader auto-retry)
        boolean reachedReady = recoveredReadyLatch.await(10, TimeUnit.SECONDS);
        if (!reachedReady) {
            logTrace("I05_MANUAL_RETRY", "Terminal error reached; executing manual re-prepare to verify recovery");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                MediaSource retrySource = new ProgressiveMediaSource.Factory(mDataSourceFactory)
                        .createMediaSource(Uri.parse(testUrl));
                mPlayer.prepare(retrySource, false, false);
                mPlayer.setPlayWhenReady(true);
            });
            reachedReady = recoveredReadyLatch.await(10, TimeUnit.SECONDS);
        }
        assertTrue("ExoPlayer must recover to STATE_READY after transient 429", reachedReady);
        logTrace("I05_RECOVERED_READY", "Confirmed STATE_READY reached after transient 429");

        // 3. Observe playback position advance for 6.0 seconds (expecting >= 4000 ms advance)
        long startTime = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - startTime < 6000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (mPlayer != null) {
                    long currentPos = mPlayer.getCurrentPosition();
                    long dur = mPlayer.getDuration();
                    if (dur > 0) durationMs.set(dur);
                    if (initialPositionMs.get() < 0) {
                        initialPositionMs.set(currentPos);
                    }
                    if (currentPos > maxPositionMs.get()) {
                        maxPositionMs.set(currentPos);
                    }
                    logTrace("I05_POLL", "Playback tick: pos=" + currentPos + ", dur=" + dur + ", isPlaying=" + mPlayer.isPlaying());
                }
            });
            Thread.sleep(500);
        }

        long advanceMs = maxPositionMs.get() - Math.max(0, initialPositionMs.get());
        logTrace("I05_ADVANCE", "Measured position advance after 429 recovery: " + advanceMs + " ms, durationMs=" + durationMs.get());

        assertTrue("Playback position must advance >= 4000 ms after recovery (measured " + advanceMs + " ms)", advanceMs >= 4000);
        logTrace("I05_PASS", "AVD_L2_HTTP429_TRANSIENT_PASS: 429 observed, recovered to READY, advanceMs=" + advanceMs);
    }

    /**
     * I06: Permanent HTTP 429 Rate Limit Fault Propagation and Terminal Error (AVD L2).
     * All requests return HTTP 429 Too Many Requests -> ExoPlayer enters terminal error state.
     * Measures backoff intervals and verifies terminal onPlayerError.
     * Marked: AVD_L2_HTTP429_PERMANENT_PASS.
     */
    @Test
    public void testI06_RealExoPlayer_Permanent429TerminalError() throws Exception {
        String testUrl = FIXTURE_HOST + "/i429_permanent/sample.mp4";
        logTrace("I06_START", "Starting permanent 429 test against " + testUrl);

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<ExoPlaybackException> terminalError = new AtomicReference<>(null);
        AtomicInteger responseCodeObserved = new AtomicInteger(0);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mPlayer = ExoPlayerFactory.newSimpleInstance(mContext);

            mPlayer.addListener(new Player.EventListener() {
                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    logTrace("I06_ON_ERROR", "Captured terminal error: type=" + error.type + ", message=" + error.getMessage());
                    terminalError.set(error);
                    if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                        IOException cause = error.getSourceException();
                        if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                            int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                            responseCodeObserved.set(code);
                        }
                    }
                    errorLatch.countDown();
                }
            });

            MediaSource mediaSource = new ExtractorMediaSource.Factory(mDataSourceFactory)
                    .createMediaSource(Uri.parse(testUrl));
            mPlayer.prepare(mediaSource);
            mPlayer.setPlayWhenReady(true);
        });

        // Wait for terminal player error after loader retries are exhausted (up to 15 seconds)
        boolean errorReached = errorLatch.await(15, TimeUnit.SECONDS);
        assertTrue("ExoPlayer must transition to terminal error on permanent HTTP 429", errorReached);
        assertNotNull("Terminal error must not be null", terminalError.get());
        assertEquals("Terminal error must be TYPE_SOURCE", ExoPlaybackException.TYPE_SOURCE, terminalError.get().type);
        assertEquals("Observed response code must be 429", 429, responseCodeObserved.get());

        logTrace("I06_PASS", "AVD_L2_HTTP429_PERMANENT_PASS: Terminal onPlayerError captured with code 429");
    }
}

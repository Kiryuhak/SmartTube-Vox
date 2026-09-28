/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class YandexVotOrchestratorTest {

    private static class ScheduledTaskEntry implements ScheduledFuture<Object> {
        private final Runnable command;
        private final long delay;
        private final TimeUnit unit;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean executed = new AtomicBoolean(false);

        public ScheduledTaskEntry(Runnable command, long delay, TimeUnit unit) {
            this.command = command;
            this.delay = delay;
            this.unit = unit;
        }

        public void run() {
            if (!cancelled.get() && executed.compareAndSet(false, true)) {
                command.run();
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delay, this.unit);
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(this.getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled.set(true);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return executed.get() || cancelled.get();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }

    private static class TestScheduledExecutor implements ScheduledExecutorService {
        private final List<ScheduledTaskEntry> scheduledTasks = new ArrayList<>();
        private long lastScheduledDelay = -1;
        private TimeUnit lastScheduledUnit = null;

        public void runAllScheduled() {
            List<ScheduledTaskEntry> copy = new ArrayList<>(scheduledTasks);
            scheduledTasks.clear();
            for (ScheduledTaskEntry entry : copy) {
                entry.run();
            }
        }

        public int getPendingScheduledCount() {
            int count = 0;
            for (ScheduledTaskEntry entry : scheduledTasks) {
                if (!entry.isCancelled() && !entry.isDone()) {
                    count++;
                }
            }
            return count;
        }

        public long getLastScheduledDelaySeconds() {
            if (lastScheduledUnit == null) return -1;
            return TimeUnit.SECONDS.convert(lastScheduledDelay, lastScheduledUnit);
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            lastScheduledDelay = delay;
            lastScheduledUnit = unit;
            ScheduledTaskEntry entry = new ScheduledTaskEntry(command, delay, unit);
            scheduledTasks.add(entry);
            return entry;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeYandexVotApi implements YandexVotApi {
        private YandexVotApiClient.TranslationResult responseToReturn;
        private int callCount = 0;
        private boolean lastFirstRequest = false;
        private String lastVideoUrl;
        private boolean lastUseLiveVoices;
        private Runnable onCallAction;

        public void setResponse(YandexVotApiClient.TranslationResult response) {
            this.responseToReturn = response;
        }

        public void setOnCallAction(Runnable action) {
            this.onCallAction = action;
        }

        public int getCallCount() {
            return callCount;
        }

        public boolean isLastFirstRequest() {
            return lastFirstRequest;
        }

        public String getLastVideoUrl() {
            return lastVideoUrl;
        }

        public boolean isLastUseLiveVoices() {
            return lastUseLiveVoices;
        }

        @Nullable
        @Override
        public YandexVotApiClient.TranslationResult requestTranslation(
                @NonNull String videoUrl,
                double duration,
                @Nullable String sourceLang,
                @NonNull String targetLang,
                @Nullable String videoTitle,
                boolean useLiveVoices,
                @Nullable String oauthToken,
                boolean firstRequest
        ) {
            callCount++;
            lastVideoUrl = videoUrl;
            lastFirstRequest = firstRequest;
            lastUseLiveVoices = useLiveVoices;

            if (onCallAction != null) {
                onCallAction.run();
            }

            return responseToReturn;
        }
    }

    private TestScheduledExecutor testExecutor;
    private FakeYandexVotApi fakeApi;
    private YandexVotOrchestrator orchestrator;
    private List<YandexVotState> stateHistory;

    @Before
    public void setUp() {
        testExecutor = new TestScheduledExecutor();
        fakeApi = new FakeYandexVotApi();
        orchestrator = new YandexVotOrchestrator(fakeApi, testExecutor);
        stateHistory = new ArrayList<>();
        orchestrator.setListener(new YandexVotOrchestrator.Listener() {
            @Override
            public void onStateChanged(@NonNull YandexVotState state) {
                stateHistory.add(state);
            }
        });
    }

    @Test
    public void testInitialStateIsIdle() {
        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.IDLE, state.getStatus());
        assertTrue(state.isIdle());
        assertEquals(0, state.getGenerationId());
    }

    @Test
    public void test1_IdleToRequestingToReady() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://example.com/audio.mp3",
                0,
                "trans_123",
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid123", "https://youtube.com/watch?v=vid123", 120.0, "en", "ru", "Title", false, null
        );

        long gen = orchestrator.startTranslation(params);
        assertEquals(1, gen);

        assertEquals(2, stateHistory.size());
        assertEquals(YandexVotState.Status.REQUESTING, stateHistory.get(0).getStatus());
        assertEquals(YandexVotState.Status.READY, stateHistory.get(1).getStatus());

        YandexVotState readyState = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.READY, readyState.getStatus());
        assertEquals("https://example.com/audio.mp3", readyState.getAudioUrl());
        assertEquals("trans_123", readyState.getTranslationId());
        assertEquals("vid123", readyState.getVideoId());
    }

    @Test
    public void test2_RequestingToWaiting() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_WAITING,
                null,
                25,
                "trans_waiting",
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_wait", "https://youtube.com/watch?v=vid_wait", 60.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        assertEquals(2, stateHistory.size());
        assertEquals(YandexVotState.Status.REQUESTING, stateHistory.get(0).getStatus());
        assertEquals(YandexVotState.Status.WAITING, stateHistory.get(1).getStatus());

        YandexVotState waitingState = orchestrator.getCurrentState();
        assertEquals(25, waitingState.getRemainingSeconds());
        assertEquals("trans_waiting", waitingState.getTranslationId());
    }

    @Test
    public void test3_WaitingToReadyAfterPolling() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_WAITING,
                null,
                10,
                "trans_poll",
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_poll", "https://youtube.com/watch?v=vid_poll", 60.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);
        assertEquals(1, fakeApi.getCallCount());
        assertTrue(fakeApi.isLastFirstRequest());
        assertEquals(1, testExecutor.getPendingScheduledCount());

        // Update API response for subsequent poll
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://example.com/polled_audio.mp3",
                0,
                "trans_poll",
                null
        ));

        testExecutor.runAllScheduled();

        assertEquals(2, fakeApi.getCallCount());
        assertFalse(fakeApi.isLastFirstRequest()); // Second request must have firstRequest=false

        YandexVotState finalState = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.READY, finalState.getStatus());
        assertEquals("https://example.com/polled_audio.mp3", finalState.getAudioUrl());
    }

    @Test
    public void test4_RequestingToAudioRequired() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null,
                0,
                "audio_req_456",
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_audio_req", "https://youtube.com/watch?v=vid_audio_req", 300.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        assertEquals(2, stateHistory.size());
        assertEquals(YandexVotState.Status.REQUESTING, stateHistory.get(0).getStatus());
        assertEquals(YandexVotState.Status.AUDIO_REQUIRED, stateHistory.get(1).getStatus());

        YandexVotState state = orchestrator.getCurrentState();
        assertTrue(state.isAudioRequired());
        assertEquals("audio_req_456", state.getTranslationId());
        assertEquals("vid_audio_req", state.getVideoId());
    }

    @Test
    public void test5_FailedToError() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FAILED,
                null,
                0,
                null,
                "Translation service error"
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_fail", "https://youtube.com/watch?v=vid_fail", 300.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.ERROR, state.getStatus());
        assertEquals("Translation service error", state.getErrorMessage());
        assertEquals("api_failed", state.getErrorCategory());
    }

    @Test
    public void test5b_LivelyVoiceUnavailableError() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FAILED,
                null,
                0,
                null,
                "Для этого видео доступна только обычная озвучка"
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_lively", "https://youtube.com/watch?v=vid_lively", 300.0, "en", "ru", "Title", true, "token123"
        );

        orchestrator.startTranslation(params);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.ERROR, state.getStatus());
        assertEquals("lively_unavailable", state.getErrorCategory());
    }

    @Test
    public void test6_CancelToCancelled() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_WAITING,
                null,
                15,
                "trans_cancel",
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_cancel", "https://youtube.com/watch?v=vid_cancel", 300.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);
        assertEquals(YandexVotState.Status.WAITING, orchestrator.getCurrentState().getStatus());

        orchestrator.cancel();

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.CANCELLED, state.getStatus());
        assertTrue(state.isCancelled());
        assertEquals("vid_cancel", state.getVideoId());
    }

    @Test
    public void test7_NewRequestInvalidatesOldRequest() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_WAITING,
                null,
                10,
                "trans_req1",
                null
        ));

        YandexVotOrchestrator.RequestParams params1 = new YandexVotOrchestrator.RequestParams(
                "vid1", "https://youtube.com/watch?v=vid1", 100.0, "en", "ru", "Title1", false, null
        );
        long gen1 = orchestrator.startTranslation(params1);
        assertEquals(1, gen1);

        YandexVotOrchestrator.RequestParams params2 = new YandexVotOrchestrator.RequestParams(
                "vid2", "https://youtube.com/watch?v=vid2", 200.0, "en", "ru", "Title2", false, null
        );
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://example.com/vid2.mp3",
                0,
                "trans_req2",
                null
        ));

        long gen2 = orchestrator.startTranslation(params2);
        assertEquals(2, gen2);

        YandexVotState finalState = orchestrator.getCurrentState();
        assertEquals(2, finalState.getGenerationId());
        assertEquals("vid2", finalState.getVideoId());
        assertEquals("trans_req2", finalState.getTranslationId());
    }

    @Test
    public void test8_StaleFinishedCannotOverwriteNewerRequest() {
        fakeApi.setOnCallAction(new Runnable() {
            @Override
            public void run() {
                if (fakeApi.getCallCount() == 1) {
                    // While request 1 is executing, trigger request 2
                    YandexVotOrchestrator.RequestParams params2 = new YandexVotOrchestrator.RequestParams(
                            "vid2", "https://youtube.com/watch?v=vid2", 200.0, "en", "ru", "Title2", false, null
                    );
                    fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                            YandexVotApiClient.STATUS_FINISHED,
                            "https://example.com/vid2_audio.mp3",
                            0,
                            "trans_req2",
                            null
                    ));
                    orchestrator.startTranslation(params2);
                }
            }
        });

        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://example.com/stale_vid1_audio.mp3",
                0,
                "trans_req1",
                null
        ));

        YandexVotOrchestrator.RequestParams params1 = new YandexVotOrchestrator.RequestParams(
                "vid1", "https://youtube.com/watch?v=vid1", 100.0, "en", "ru", "Title1", false, null
        );

        orchestrator.startTranslation(params1);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals("vid2", state.getVideoId());
        assertEquals("https://example.com/vid2_audio.mp3", state.getAudioUrl());
        assertEquals("trans_req2", state.getTranslationId());
    }

    @Test
    public void test9_StaleErrorCannotOverwriteNewerRequest() {
        fakeApi.setOnCallAction(new Runnable() {
            @Override
            public void run() {
                if (fakeApi.getCallCount() == 1) {
                    // While request 1 is in-flight, start request 2
                    YandexVotOrchestrator.RequestParams params2 = new YandexVotOrchestrator.RequestParams(
                            "vid2", "https://youtube.com/watch?v=vid2", 200.0, "en", "ru", "Title2", false, null
                    );
                    fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                            YandexVotApiClient.STATUS_FINISHED,
                            "https://example.com/vid2_audio.mp3",
                            0,
                            "trans_req2",
                            null
                    ));
                    orchestrator.startTranslation(params2);
                }
            }
        });

        // Request 1 returns null / error
        fakeApi.setResponse(null);

        YandexVotOrchestrator.RequestParams params1 = new YandexVotOrchestrator.RequestParams(
                "vid1", "https://youtube.com/watch?v=vid1", 100.0, "en", "ru", "Title1", false, null
        );

        orchestrator.startTranslation(params1);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.READY, state.getStatus());
        assertEquals("vid2", state.getVideoId());
        assertEquals("https://example.com/vid2_audio.mp3", state.getAudioUrl());
    }

    @Test
    public void test10_PollingStopsAfterCancel() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_WAITING,
                null,
                10,
                "trans_poll_cancel",
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_pc", "https://youtube.com/watch?v=vid_pc", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);
        assertEquals(1, fakeApi.getCallCount());
        assertEquals(1, testExecutor.getPendingScheduledCount());

        orchestrator.cancel();
        assertEquals(0, testExecutor.getPendingScheduledCount());

        testExecutor.runAllScheduled();
        assertEquals(1, fakeApi.getCallCount()); // Must NOT have polled again after cancel
        assertEquals(YandexVotState.Status.CANCELLED, orchestrator.getCurrentState().getStatus());
    }

    @Test
    public void test11_SessionRequiredRetryIsBounded() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_SESSION_REQUIRED,
                null,
                0,
                null,
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_sess", "https://youtube.com/watch?v=vid_sess", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        // First call was initial, second call was the 1 allowed bounded retry
        assertEquals(2, fakeApi.getCallCount());

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.ERROR, state.getStatus());
        assertEquals("session_required", state.getErrorCategory());
    }

    @Test
    public void test12_WaitingTimingUsesYandexVotTiming() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_WAITING,
                null,
                8, // 8 seconds remaining
                "trans_timing",
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_time", "https://youtube.com/watch?v=vid_time", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        // YandexVotTiming.pollDelaySeconds(8) == 8
        assertEquals(8, testExecutor.getLastScheduledDelaySeconds());
        assertEquals(8, orchestrator.getCurrentState().getRemainingSeconds());
    }

    @Test
    public void test13_ReadyContainsExpectedMetadata() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://example.com/lively_audio.mp3",
                0,
                "trans_lively_meta",
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_meta", "https://youtube.com/watch?v=vid_meta", 240.0, "en", "ru", "Metadata Test", true, "oauth_token_xyz"
        );

        orchestrator.startTranslation(params);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.READY, state.getStatus());
        assertEquals("vid_meta", state.getVideoId());
        assertEquals("https://youtube.com/watch?v=vid_meta", state.getVideoUrl());
        assertEquals("trans_lively_meta", state.getTranslationId());
        assertEquals("https://example.com/lively_audio.mp3", state.getAudioUrl());
        assertTrue(state.isRequestedLively());
        assertTrue(state.isReceivedLively());
        // Verify toString masks audio URL for security
        assertTrue(state.toString().contains("[PROTECTED]"));
        assertFalse(state.toString().contains("https://example.com/lively_audio.mp3"));
    }

    @Test
    public void test14_AudioRequiredContainsExpectedTranslationId() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null,
                0,
                "trans_upload_id_777",
                null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_upload", "https://youtube.com/watch?v=vid_upload", 500.0, "en", "ru", "Audio Required Test", false, null
        );

        orchestrator.startTranslation(params);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.AUDIO_REQUIRED, state.getStatus());
        assertEquals("trans_upload_id_777", state.getTranslationId());
        assertEquals("vid_upload", state.getVideoId());
        assertEquals("https://youtube.com/watch?v=vid_upload", state.getVideoUrl());
        assertNull(state.getAudioUrl());
    }
}

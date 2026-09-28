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

public class YandexVotAudioRequiredIntegrationTest {

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

        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public <T> java.util.concurrent.Future<T> submit(Callable<T> task) { throw new UnsupportedOperationException(); }
        @Override public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) { throw new UnsupportedOperationException(); }
        @Override public java.util.concurrent.Future<?> submit(Runnable task) { throw new UnsupportedOperationException(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks) { throw new UnsupportedOperationException(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks) { throw new UnsupportedOperationException(); }
        @Override public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
    }

    private static class FakeYandexVotApi implements YandexVotApi {
        private YandexVotApiClient.TranslationResult responseToReturn;
        private int callCount = 0;
        private boolean lastFirstRequest = false;

        public void setResponse(YandexVotApiClient.TranslationResult response) {
            this.responseToReturn = response;
        }

        public int getCallCount() {
            return callCount;
        }

        public boolean isLastFirstRequest() {
            return lastFirstRequest;
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
            lastFirstRequest = firstRequest;
            return responseToReturn;
        }
    }

    private static class FakeSourceProvider implements YandexVotAudioSourceProvider {
        private YandexVotAudioSource sourceToReturn;
        private YandexVotAudioStreamReader readerToReturn;
        private boolean throwOnGetSource = false;
        private Runnable onGetSourceAction = null;

        public void setSource(YandexVotAudioSource source, YandexVotAudioStreamReader reader) {
            this.sourceToReturn = source;
            this.readerToReturn = reader;
        }

        public void setThrowOnGetSource(boolean throwError) {
            this.throwOnGetSource = throwError;
        }

        public void setOnGetSourceAction(Runnable action) {
            this.onGetSourceAction = action;
        }

        @Nullable
        @Override
        public YandexVotAudioSource getAudioSource(@Nullable String videoId, @NonNull String videoUrl) throws Exception {
            if (onGetSourceAction != null) {
                onGetSourceAction.run();
            }
            if (throwOnGetSource) {
                throw new RuntimeException("Source provider error");
            }
            return sourceToReturn;
        }

        @Nullable
        @Override
        public YandexVotAudioStreamReader getStreamReader(@NonNull YandexVotAudioSource source) throws Exception {
            return readerToReturn;
        }
    }

    private static class FakeReader implements YandexVotAudioStreamReader {
        private final byte[] data;

        public FakeReader(byte[] data) {
            this.data = data;
        }

        @NonNull
        @Override
        public byte[] readRange(long startByte, int length) throws Exception {
            int start = (int) startByte;
            int actual = Math.min(length, data.length - start);
            byte[] chunk = new byte[actual];
            System.arraycopy(data, start, chunk, 0, actual);
            return chunk;
        }
    }

    private static class FakeTransport implements YandexVotAudioUploadTransport {
        private final List<Integer> uploadedChunks = new ArrayList<>();
        private UploadOutcome outcome = UploadOutcome.SUCCESS;
        private Runnable onUploadAction = null;

        public void setOutcome(UploadOutcome outcome) {
            this.outcome = outcome;
        }

        public void setOnUploadAction(Runnable action) {
            this.onUploadAction = action;
        }

        public List<Integer> getUploadedChunks() {
            return uploadedChunks;
        }

        @Override
        public UploadOutcome uploadPart(
                @NonNull String videoUrl,
                @NonNull String translationId,
                @NonNull String fileId,
                int totalParts,
                int version,
                int chunkId,
                @NonNull byte[] audioData
        ) {
            if (onUploadAction != null) {
                onUploadAction.run();
            }
            if (outcome == UploadOutcome.SUCCESS) {
                uploadedChunks.add(chunkId);
            }
            return outcome;
        }
    }

    private TestScheduledExecutor testExecutor;
    private FakeYandexVotApi fakeApi;
    private FakeSourceProvider fakeSourceProvider;
    private FakeTransport fakeTransport;
    private YandexVotOrchestrator orchestrator;
    private List<YandexVotState> stateHistory;

    @Before
    public void setUp() {
        testExecutor = new TestScheduledExecutor();
        fakeApi = new FakeYandexVotApi();
        fakeSourceProvider = new FakeSourceProvider();
        fakeTransport = new FakeTransport();

        // Default valid source
        int partSize = YandexVotAudioParts.PART_SIZE_BYTES;
        byte[] dummyData = new byte[partSize * 2 + 1000];
        YandexVotAudioSource defaultSource = new YandexVotAudioSource(
                "vid123", "https://example.com/audio", "audio/webm", "opus", 50000, dummyData.length, 249, true, null
        );
        fakeSourceProvider.setSource(defaultSource, new FakeReader(dummyData));

        orchestrator = new YandexVotOrchestrator(fakeApi, fakeSourceProvider, fakeTransport, testExecutor);
        stateHistory = new ArrayList<>();
        orchestrator.setListener(new YandexVotOrchestrator.Listener() {
            @Override
            public void onStateChanged(@NonNull YandexVotState state) {
                stateHistory.add(state);
            }
        });
    }

    @Test
    public void test1_AudioRequiredToPreparingAudio() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_ar_1", null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid123", "https://youtube.com/watch?v=vid123", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        // Sequence: REQUESTING -> AUDIO_REQUIRED -> PREPARING_AUDIO -> UPLOADING -> WAITING
        assertTrue(stateHistory.size() >= 3);
        assertEquals(YandexVotState.Status.REQUESTING, stateHistory.get(0).getStatus());
        assertEquals(YandexVotState.Status.AUDIO_REQUIRED, stateHistory.get(1).getStatus());
        assertEquals(YandexVotState.Status.PREPARING_AUDIO, stateHistory.get(2).getStatus());
    }

    @Test
    public void test2_PreparingAudioToUploading() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_upload", null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid123", "https://youtube.com/watch?v=vid123", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        boolean sawUploading = false;
        for (YandexVotState s : stateHistory) {
            if (s.getStatus() == YandexVotState.Status.UPLOADING) {
                sawUploading = true;
                assertEquals(3, s.getTotalParts()); // 2 * PART_SIZE + 1000 = 3 parts
            }
        }
        assertTrue(sawUploading);
    }

    @Test
    public void test3_UploadSuccessToWaiting() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_succ", null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid123", "https://youtube.com/watch?v=vid123", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        YandexVotState currentState = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.WAITING, currentState.getStatus());
        assertEquals(1, testExecutor.getPendingScheduledCount());
        assertEquals(YandexVotTiming.DEFAULT_POLL_DELAY_SECONDS, testExecutor.getLastScheduledDelaySeconds());
    }

    @Test
    public void test4_WaitingAfterUploadToReady() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_ready_flow", null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid123", "https://youtube.com/watch?v=vid123", 100.0, "en", "ru", "Title", true, "oauth_tok"
        );

        orchestrator.startTranslation(params);
        assertEquals(YandexVotState.Status.WAITING, orchestrator.getCurrentState().getStatus());

        // Now mock API returning FINISHED on subsequent poll
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://example.com/yandex_translated_audio.mp3",
                0, "trans_ready_flow", null
        ));

        testExecutor.runAllScheduled();

        YandexVotState finalState = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.READY, finalState.getStatus());
        assertEquals("https://example.com/yandex_translated_audio.mp3", finalState.getAudioUrl());
        assertEquals("trans_ready_flow", finalState.getTranslationId());
        assertTrue(finalState.isReceivedLively());
    }

    @Test
    public void test5_SourceUnavailableToError() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_no_src", null
        ));

        // Source provider returns null
        fakeSourceProvider.setSource(null, null);

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_no_src", "https://youtube.com/watch?v=vid_no_src", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.ERROR, state.getStatus());
        assertEquals("source", state.getErrorCategory());
    }

    @Test
    public void test6_TransferPermanentFailureToError() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_perm_fail", null
        ));

        fakeTransport.setOutcome(YandexVotAudioUploadTransport.UploadOutcome.PERMANENT_ERROR);

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_pf", "https://youtube.com/watch?v=vid_pf", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.ERROR, state.getStatus());
        assertEquals("upload", state.getErrorCategory());
    }

    @Test
    public void test7_TransferCancelToCancelled() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_canc", null
        ));

        fakeTransport.setOutcome(YandexVotAudioUploadTransport.UploadOutcome.CANCELLED);

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_canc", "https://youtube.com/watch?v=vid_canc", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.CANCELLED, state.getStatus());
    }

    @Test
    public void test8_CancelDuringPreparingAudio() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_cancel_prep", null
        ));

        fakeSourceProvider.setOnGetSourceAction(new Runnable() {
            @Override
            public void run() {
                orchestrator.cancel();
            }
        });

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_cp", "https://youtube.com/watch?v=vid_cp", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.CANCELLED, state.getStatus());
        assertEquals(0, fakeTransport.getUploadedChunks().size());
    }

    @Test
    public void test9_CancelDuringUploading() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_cancel_up", null
        ));

        fakeTransport.setOnUploadAction(new Runnable() {
            @Override
            public void run() {
                if (fakeTransport.getUploadedChunks().size() == 1) {
                    orchestrator.cancel();
                }
            }
        });

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_cu", "https://youtube.com/watch?v=vid_cu", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.CANCELLED, state.getStatus());
    }

    @Test
    public void test10_StaleUploadCompletionIgnored() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_req1", null
        ));

        fakeTransport.setOnUploadAction(new Runnable() {
            @Override
            public void run() {
                if (fakeTransport.getUploadedChunks().isEmpty()) {
                    // While request 1 is uploading chunk 0, request 2 starts
                    fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                            YandexVotApiClient.STATUS_FINISHED,
                            "https://example.com/req2.mp3", 0, "trans_req2", null
                    ));
                    YandexVotOrchestrator.RequestParams params2 = new YandexVotOrchestrator.RequestParams(
                            "vid2", "https://youtube.com/watch?v=vid2", 100.0, "en", "ru", "Title2", false, null
                    );
                    orchestrator.startTranslation(params2);
                }
            }
        });

        YandexVotOrchestrator.RequestParams params1 = new YandexVotOrchestrator.RequestParams(
                "vid1", "https://youtube.com/watch?v=vid1", 100.0, "en", "ru", "Title1", false, null
        );

        orchestrator.startTranslation(params1);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals("vid2", state.getVideoId());
        assertEquals("trans_req2", state.getTranslationId());
        assertEquals(YandexVotState.Status.READY, state.getStatus());
    }

    @Test
    public void test11_StaleUploadErrorIgnored() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_req1", null
        ));

        fakeTransport.setOnUploadAction(new Runnable() {
            @Override
            public void run() {
                fakeTransport.setOutcome(YandexVotAudioUploadTransport.UploadOutcome.PERMANENT_ERROR);
                // Start request 2
                fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                        YandexVotApiClient.STATUS_FINISHED,
                        "https://example.com/req2.mp3", 0, "trans_req2", null
                ));
                YandexVotOrchestrator.RequestParams params2 = new YandexVotOrchestrator.RequestParams(
                        "vid2", "https://youtube.com/watch?v=vid2", 100.0, "en", "ru", "Title2", false, null
                );
                orchestrator.startTranslation(params2);
            }
        });

        YandexVotOrchestrator.RequestParams params1 = new YandexVotOrchestrator.RequestParams(
                "vid1", "https://youtube.com/watch?v=vid1", 100.0, "en", "ru", "Title1", false, null
        );

        orchestrator.startTranslation(params1);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.READY, state.getStatus());
        assertEquals("vid2", state.getVideoId());
    }

    @Test
    public void test12_NewGenerationInvalidatesPreviousUpload() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_gen1", null
        ));

        YandexVotOrchestrator.RequestParams params1 = new YandexVotOrchestrator.RequestParams(
                "vid1", "https://youtube.com/watch?v=vid1", 100.0, "en", "ru", "Title1", false, null
        );

        long gen1 = orchestrator.startTranslation(params1);
        assertEquals(1, gen1);

        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://example.com/gen2.mp3", 0, "trans_gen2", null
        ));

        YandexVotOrchestrator.RequestParams params2 = new YandexVotOrchestrator.RequestParams(
                "vid2", "https://youtube.com/watch?v=vid2", 100.0, "en", "ru", "Title2", false, null
        );

        long gen2 = orchestrator.startTranslation(params2);
        assertEquals(2, gen2);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(2, state.getGenerationId());
        assertEquals("vid2", state.getVideoId());
    }

    @Test
    public void test13_UploadProgressStateUpdatesCorrectly() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_prog", null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid123", "https://youtube.com/watch?v=vid123", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        List<Integer> partsSeen = new ArrayList<>();
        for (YandexVotState s : stateHistory) {
            if (s.isUploading()) {
                partsSeen.add(s.getCurrentPart());
            }
        }

        // Must have published progress for parts 0, 1, 2
        assertEquals(3, partsSeen.size());
        assertEquals(Integer.valueOf(0), partsSeen.get(0));
        assertEquals(Integer.valueOf(1), partsSeen.get(1));
        assertEquals(Integer.valueOf(2), partsSeen.get(2));
    }

    @Test
    public void test14_UploadSuccessResumesPollingExactlyOnce() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_poll_once", null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid123", "https://youtube.com/watch?v=vid123", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        assertEquals(1, fakeApi.getCallCount());
        assertEquals(1, testExecutor.getPendingScheduledCount());

        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://example.com/done.mp3", 0, "trans_poll_once", null
        ));

        testExecutor.runAllScheduled();

        assertEquals(2, fakeApi.getCallCount());
        assertFalse(fakeApi.isLastFirstRequest());
        assertEquals(YandexVotState.Status.READY, orchestrator.getCurrentState().getStatus());
    }

    @Test
    public void test15_NoDuplicateUploadStart() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_no_dup", null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid123", "https://youtube.com/watch?v=vid123", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        // Uploaded exactly 3 chunks (parts 0, 1, 2)
        assertEquals(3, fakeTransport.getUploadedChunks().size());
    }

    @Test
    public void test16_ServerWaitingAfterUploadUsesTimingPolicy() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_timing_test", null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid123", "https://youtube.com/watch?v=vid123", 100.0, "en", "ru", "Title", false, null
        );

        orchestrator.startTranslation(params);

        // Default post-upload poll delay is YandexVotTiming.DEFAULT_POLL_DELAY_SECONDS (10s)
        assertEquals(YandexVotTiming.DEFAULT_POLL_DELAY_SECONDS, testExecutor.getLastScheduledDelaySeconds());

        // When poll returns WAITING with 12s remaining
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_WAITING,
                null, 12, "trans_timing_test", null
        ));

        testExecutor.runAllScheduled();

        assertEquals(YandexVotState.Status.WAITING, orchestrator.getCurrentState().getStatus());
        assertEquals(12, orchestrator.getCurrentState().getRemainingSeconds());
        assertEquals(12, testExecutor.getLastScheduledDelaySeconds());
    }

    @Test
    public void test17_ReadyPreservesTranslationAndAudioMetadata() {
        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_AUDIO_REQUESTED,
                null, 0, "trans_meta_check", null
        ));

        YandexVotOrchestrator.RequestParams params = new YandexVotOrchestrator.RequestParams(
                "vid_meta_1", "https://youtube.com/watch?v=vid_meta_1", 300.0, "en", "ru", "Title Meta", true, "oauth_tok"
        );

        orchestrator.startTranslation(params);

        fakeApi.setResponse(new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://example.com/final_meta_audio.mp3", 0, "trans_meta_check", null
        ));

        testExecutor.runAllScheduled();

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.READY, state.getStatus());
        assertEquals("vid_meta_1", state.getVideoId());
        assertEquals("https://youtube.com/watch?v=vid_meta_1", state.getVideoUrl());
        assertEquals("trans_meta_check", state.getTranslationId());
        assertEquals("https://example.com/final_meta_audio.mp3", state.getAudioUrl());
        assertTrue(state.isRequestedLively());
        assertTrue(state.isReceivedLively());
        assertTrue(state.toString().contains("[PROTECTED]"));
        assertFalse(state.toString().contains("final_meta_audio.mp3"));
    }
}

/*
 * Copyright (C) 2026 Dual VoT contributors
 *
 * Licensed under the GNU General Public License v3.0.
 */

package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Isolated state machine orchestrating Yandex VOT translation requests, polling loops,
 * audio upload flow, generation safety, and lifecycle state transitions.
 */
public class YandexVotOrchestrator {
    private static final String TAG = YandexVotOrchestrator.class.getSimpleName();

    public interface Listener {
        void onStateChanged(@NonNull YandexVotState state);
    }

    public static final class RequestParams {
        private final String videoId;
        private final String videoUrl;
        private final double duration;
        private final String sourceLang;
        private final String targetLang;
        private final String videoTitle;
        private final boolean useLiveVoices;
        private final String oauthToken;

        public RequestParams(
                @Nullable String videoId,
                @NonNull String videoUrl,
                double duration,
                @Nullable String sourceLang,
                @NonNull String targetLang,
                @Nullable String videoTitle,
                boolean useLiveVoices,
                @Nullable String oauthToken
        ) {
            this.videoId = videoId;
            this.videoUrl = Objects.requireNonNull(videoUrl, "videoUrl cannot be null");
            this.duration = duration;
            this.sourceLang = sourceLang;
            this.targetLang = Objects.requireNonNull(targetLang, "targetLang cannot be null");
            this.videoTitle = videoTitle;
            this.useLiveVoices = useLiveVoices;
            this.oauthToken = oauthToken;
        }

        @Nullable public String getVideoId() { return videoId; }
        @NonNull public String getVideoUrl() { return videoUrl; }
        public double getDuration() { return duration; }
        @Nullable public String getSourceLang() { return sourceLang; }
        @NonNull public String getTargetLang() { return targetLang; }
        @Nullable public String getVideoTitle() { return videoTitle; }
        public boolean isUseLiveVoices() { return useLiveVoices; }
        @Nullable public String getOauthToken() { return oauthToken; }
    }

    private final YandexVotApi api;
    @Nullable private final YandexVotAudioSourceProvider sourceProvider;
    @Nullable private final YandexVotAudioUploadTransport uploadTransport;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    private final Object lock = new Object();
    private long generationCounter = 0;
    @NonNull private YandexVotState currentState = YandexVotState.idle();
    @Nullable private Listener listener;
    @Nullable private ScheduledFuture<?> activePollFuture;
    @Nullable private YandexVotAudioTransfer activeTransfer;

    public YandexVotOrchestrator() {
        this(YandexVotApi.DEFAULT);
    }

    public YandexVotOrchestrator(@NonNull YandexVotApi api) {
        this(api, null, null, createDefaultScheduler(), true);
    }

    public YandexVotOrchestrator(@NonNull YandexVotApi api, @NonNull ScheduledExecutorService scheduler) {
        this(api, null, null, scheduler, false);
    }

    public YandexVotOrchestrator(
            @NonNull YandexVotApi api,
            @Nullable YandexVotAudioSourceProvider sourceProvider,
            @Nullable YandexVotAudioUploadTransport uploadTransport,
            @NonNull ScheduledExecutorService scheduler
    ) {
        this(api, sourceProvider, uploadTransport, scheduler, false);
    }

    private YandexVotOrchestrator(
            @NonNull YandexVotApi api,
            @Nullable YandexVotAudioSourceProvider sourceProvider,
            @Nullable YandexVotAudioUploadTransport uploadTransport,
            @NonNull ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.api = Objects.requireNonNull(api, "api cannot be null");
        this.sourceProvider = sourceProvider;
        this.uploadTransport = uploadTransport;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.ownsScheduler = ownsScheduler;
    }

    private static ScheduledExecutorService createDefaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "YandexVotOrchestrator-Worker");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public void setListener(@Nullable Listener listener) {
        synchronized (lock) {
            this.listener = listener;
        }
    }

    @NonNull
    public YandexVotState getCurrentState() {
        synchronized (lock) {
            return currentState;
        }
    }

    public long startTranslation(@NonNull RequestParams params) {
        long gen;
        YandexVotState newState;
        synchronized (lock) {
            cancelActiveOperationsLocked();
            gen = ++generationCounter;
            newState = YandexVotState.requesting(
                    gen,
                    params.getVideoId(),
                    params.getVideoUrl(),
                    params.isUseLiveVoices()
            );
            currentState = newState;
            YandexVotLog.d(TAG, "VOT startTranslation: gen=" + gen + " liveVoices=" + params.isUseLiveVoices());
            notifyListenerLocked(newState);
        }

        scheduler.execute(new Runnable() {
            @Override
            public void run() {
                executeRequest(gen, params, true, 0);
            }
        });

        return gen;
    }

    public void cancel() {
        YandexVotState newState = null;
        synchronized (lock) {
            cancelActiveOperationsLocked();
            long gen = ++generationCounter;
            if (currentState.getStatus() != YandexVotState.Status.IDLE &&
                    currentState.getStatus() != YandexVotState.Status.CANCELLED) {
                newState = YandexVotState.cancelled(
                        gen,
                        currentState.getVideoId(),
                        currentState.getVideoUrl()
                );
                currentState = newState;
                YandexVotLog.d(TAG, "VOT cancelled: gen=" + gen);
                notifyListenerLocked(newState);
            }
        }
    }

    public void reset() {
        synchronized (lock) {
            cancelActiveOperationsLocked();
            long gen = ++generationCounter;
            currentState = YandexVotState.idle();
            YandexVotLog.d(TAG, "VOT reset: gen=" + gen);
            notifyListenerLocked(currentState);
        }
    }

    public void shutdown() {
        cancel();
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private void cancelActiveOperationsLocked() {
        if (activePollFuture != null) {
            activePollFuture.cancel(true);
            activePollFuture = null;
        }
        if (activeTransfer != null) {
            activeTransfer.cancel();
            activeTransfer = null;
        }
    }

    private void executeRequest(final long gen, final RequestParams params, final boolean firstRequest, final int sessionRetryCount) {
        synchronized (lock) {
            if (gen != generationCounter) {
                YandexVotLog.d(TAG, "VOT dropping stale executeRequest: gen=" + gen + ", currentGen=" + generationCounter);
                return;
            }
        }

        YandexVotApiClient.TranslationResult result = null;
        try {
            result = api.requestTranslation(
                    params.getVideoUrl(),
                    params.getDuration(),
                    params.getSourceLang(),
                    params.getTargetLang(),
                    params.getVideoTitle(),
                    params.isUseLiveVoices(),
                    params.getOauthToken(),
                    firstRequest
            );
        } catch (Exception e) {
            YandexVotLog.d(TAG, "VOT requestTranslation threw exception: gen=" + gen + " error=" + e.getClass().getSimpleName());
        }

        synchronized (lock) {
            if (gen != generationCounter) {
                YandexVotLog.d(TAG, "VOT dropping stale response callback: gen=" + gen + ", currentGen=" + generationCounter);
                return;
            }

            if (result == null) {
                YandexVotState errorState = YandexVotState.error(
                        gen,
                        params.getVideoId(),
                        params.getVideoUrl(),
                        "Translation request failed",
                        "network",
                        params.isUseLiveVoices()
                );
                currentState = errorState;
                YandexVotLog.d(TAG, "VOT request error (null result): gen=" + gen);
                notifyListenerLocked(errorState);
                return;
            }

            int status = result.getStatus();
            YandexVotLog.d(TAG, "VOT response: gen=" + gen + " status=" + status + " remaining=" + result.getRemainingTime());

            switch (status) {
                case YandexVotApiClient.STATUS_FINISHED: {
                    boolean receivedLively = params.isUseLiveVoices() && result.getAudioUrl() != null;
                    YandexVotState readyState = YandexVotState.ready(
                            gen,
                            params.getVideoId(),
                            params.getVideoUrl(),
                            result.getTranslationId(),
                            result.getAudioUrl(),
                            params.isUseLiveVoices(),
                            receivedLively
                    );
                    currentState = readyState;
                    notifyListenerLocked(readyState);
                    break;
                }

                case YandexVotApiClient.STATUS_PART_CONTENT: {
                    if (result.getAudioUrl() != null) {
                        boolean receivedLively = params.isUseLiveVoices();
                        YandexVotState readyState = YandexVotState.ready(
                                gen,
                                params.getVideoId(),
                                params.getVideoUrl(),
                                result.getTranslationId(),
                                result.getAudioUrl(),
                                params.isUseLiveVoices(),
                                receivedLively
                        );
                        currentState = readyState;
                        notifyListenerLocked(readyState);
                    } else {
                        schedulePollLocked(gen, params, result);
                    }
                    break;
                }

                case YandexVotApiClient.STATUS_WAITING:
                case YandexVotApiClient.STATUS_LONG_WAITING: {
                    schedulePollLocked(gen, params, result);
                    break;
                }

                case YandexVotApiClient.STATUS_AUDIO_REQUESTED: {
                    final String translationId = result.getTranslationId();
                    YandexVotState audioRequiredState = YandexVotState.audioRequired(
                            gen,
                            params.getVideoId(),
                            params.getVideoUrl(),
                            translationId,
                            params.isUseLiveVoices()
                    );
                    currentState = audioRequiredState;
                    notifyListenerLocked(audioRequiredState);

                    if (sourceProvider != null) {
                        scheduler.execute(new Runnable() {
                            @Override
                            public void run() {
                                handleAudioRequired(gen, params, translationId);
                            }
                        });
                    }
                    break;
                }

                case YandexVotApiClient.STATUS_SESSION_REQUIRED: {
                    if (sessionRetryCount < 1) {
                        YandexVotLog.d(TAG, "VOT SESSION_REQUIRED: retrying once (gen=" + gen + ")");
                        scheduler.execute(new Runnable() {
                            @Override
                            public void run() {
                                executeRequest(gen, params, firstRequest, sessionRetryCount + 1);
                            }
                        });
                    } else {
                        YandexVotState errorState = YandexVotState.error(
                                gen,
                                params.getVideoId(),
                                params.getVideoUrl(),
                                "Session required",
                                "session_required",
                                params.isUseLiveVoices()
                        );
                        currentState = errorState;
                        notifyListenerLocked(errorState);
                    }
                    break;
                }

                case YandexVotApiClient.STATUS_FAILED:
                default: {
                    String msg = result.getMessage() != null ? result.getMessage() : "Translation failed";
                    String category = YandexVotApiClient.isLivelyVoiceUnavailableError(msg)
                            ? "lively_unavailable"
                            : "api_failed";
                    YandexVotState errorState = YandexVotState.error(
                            gen,
                            params.getVideoId(),
                            params.getVideoUrl(),
                            msg,
                            category,
                            params.isUseLiveVoices()
                    );
                    currentState = errorState;
                    notifyListenerLocked(errorState);
                    break;
                }
            }
        }
    }

    private void handleAudioRequired(final long gen, final RequestParams params, final String translationId) {
        synchronized (lock) {
            if (gen != generationCounter) {
                YandexVotLog.d(TAG, "VOT dropping stale handleAudioRequired: gen=" + gen);
                return;
            }
            currentState = YandexVotState.preparingAudio(
                    gen,
                    params.getVideoId(),
                    params.getVideoUrl(),
                    translationId,
                    params.isUseLiveVoices()
            );
            notifyListenerLocked(currentState);
        }

        YandexVotAudioSource source = null;
        YandexVotAudioStreamReader reader = null;
        try {
            if (sourceProvider != null) {
                source = sourceProvider.getAudioSource(params.getVideoId(), params.getVideoUrl());
                if (source != null) {
                    reader = sourceProvider.getStreamReader(source);
                }
            }
        } catch (Exception e) {
            YandexVotLog.d(TAG, "VOT sourceProvider error: " + e.getMessage());
        }

        if (source == null || reader == null) {
            synchronized (lock) {
                if (gen != generationCounter) return;
                YandexVotState errorState = YandexVotState.error(
                        gen,
                        params.getVideoId(),
                        params.getVideoUrl(),
                        "Audio source unavailable",
                        "source",
                        params.isUseLiveVoices()
                );
                currentState = errorState;
                notifyListenerLocked(errorState);
            }
            return;
        }

        final YandexVotAudioTransfer transfer = new YandexVotAudioTransfer();
        synchronized (lock) {
            if (gen != generationCounter) return;
            activeTransfer = transfer;
        }

        YandexVotAudioUploadTransport transportToUse = uploadTransport != null
                ? uploadTransport
                : YandexVotAudioUploadTransport.DEFAULT;

        YandexVotAudioResult transferResult = transfer.transfer(
                source,
                params.getVideoUrl(),
                translationId,
                params.getVideoId() != null ? params.getVideoId() : "video",
                reader,
                transportToUse,
                new YandexVotAudioTransfer.TransferListener() {
                    @Override
                    public void onPreparing(long totalBytes, int totalParts) {
                        synchronized (lock) {
                            if (gen != generationCounter) return;
                            currentState = YandexVotState.preparingAudio(
                                    gen,
                                    params.getVideoId(),
                                    params.getVideoUrl(),
                                    translationId,
                                    params.isUseLiveVoices()
                            );
                            notifyListenerLocked(currentState);
                        }
                    }

                    @Override
                    public void onPartStarted(int partIndex, int totalParts, long startByte, int partLength) {
                        synchronized (lock) {
                            if (gen != generationCounter) return;
                            currentState = YandexVotState.uploading(
                                    gen,
                                    params.getVideoId(),
                                    params.getVideoUrl(),
                                    translationId,
                                    partIndex,
                                    totalParts,
                                    params.isUseLiveVoices()
                            );
                            notifyListenerLocked(currentState);
                        }
                    }

                    @Override
                    public void onPartCompleted(int partIndex, int totalParts) {
                    }

                    @Override
                    public void onCompleted(long totalBytesTransferred) {
                    }

                    @Override
                    public void onError(@NonNull YandexVotAudioResult error) {
                    }

                    @Override
                    public void onCancelled() {
                    }
                }
        );

        synchronized (lock) {
            if (activeTransfer == transfer) {
                activeTransfer = null;
            }
            if (gen != generationCounter) {
                YandexVotLog.d(TAG, "VOT dropping stale transfer outcome: gen=" + gen);
                return;
            }

            if (transferResult == YandexVotAudioResult.SUCCESS) {
                YandexVotLog.d(TAG, "VOT audio upload complete, scheduling post-upload poll: gen=" + gen);
                schedulePostUploadPollLocked(gen, params, translationId);
            } else if (transferResult == YandexVotAudioResult.CANCELLED) {
                if (!currentState.isCancelled()) {
                    currentState = YandexVotState.cancelled(gen, params.getVideoId(), params.getVideoUrl());
                    notifyListenerLocked(currentState);
                }
            } else {
                String category = transferResult.failureKind().name().toLowerCase();
                String msg = "Audio upload failed: " + transferResult.name();
                YandexVotState errorState = YandexVotState.error(
                        gen,
                        params.getVideoId(),
                        params.getVideoUrl(),
                        msg,
                        category,
                        params.isUseLiveVoices()
                );
                currentState = errorState;
                notifyListenerLocked(errorState);
            }
        }
    }

    private void schedulePostUploadPollLocked(final long gen, final RequestParams params, final String translationId) {
        int delaySeconds = YandexVotTiming.DEFAULT_POLL_DELAY_SECONDS;
        YandexVotState waitingState = YandexVotState.waiting(
                gen,
                params.getVideoId(),
                params.getVideoUrl(),
                translationId,
                0,
                params.isUseLiveVoices()
        );
        currentState = waitingState;
        notifyListenerLocked(waitingState);

        YandexVotLog.d(TAG, "VOT scheduling post-upload poll in " + delaySeconds + "s: gen=" + gen);
        activePollFuture = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                executeRequest(gen, params, false, 0);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void schedulePollLocked(final long gen, final RequestParams params, final YandexVotApiClient.TranslationResult result) {
        int delaySeconds = YandexVotTiming.pollDelaySeconds(result.getRemainingTime());
        YandexVotState waitingState = YandexVotState.waiting(
                gen,
                params.getVideoId(),
                params.getVideoUrl(),
                result.getTranslationId(),
                result.getRemainingTime(),
                params.isUseLiveVoices()
        );
        currentState = waitingState;
        notifyListenerLocked(waitingState);

        YandexVotLog.d(TAG, "VOT scheduling poll in " + delaySeconds + "s: gen=" + gen);
        activePollFuture = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                executeRequest(gen, params, false, 0);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void notifyListenerLocked(@NonNull YandexVotState state) {
        if (listener != null) {
            try {
                listener.onStateChanged(state);
            } catch (Exception e) {
                YandexVotLog.d(TAG, "VOT state listener exception: " + e.getMessage());
            }
        }
    }
}

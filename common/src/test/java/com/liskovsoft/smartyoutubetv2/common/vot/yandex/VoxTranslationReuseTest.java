package com.liskovsoft.smartyoutubetv2.common.vot.yandex;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VoxTranslationReuseTest {

    private ScheduledExecutorService testScheduler;

    @Before
    public void setUp() {
        testScheduler = Executors.newSingleThreadScheduledExecutor();
        YandexVotApiClient.clearTranslationCache();
    }

    @After
    public void tearDown() {
        if (testScheduler != null) {
            testScheduler.shutdownNow();
        }
        YandexVotApiClient.clearTranslationCache();
    }

    @Test
    public void testCacheHitAndExpiration() {
        String videoUrl = "https://www.youtube.com/watch?v=reuseVideo1";
        assertFalse(YandexVotApiClient.hasValidCachedResult(videoUrl, "en", "ru", false));
        assertNull(YandexVotApiClient.getCachedResult(videoUrl, "en", "ru", false));

        // Create cached result
        YandexVotApiClient.TranslationResult finishedResult = new YandexVotApiClient.TranslationResult(
                YandexVotApiClient.STATUS_FINISHED,
                "https://storage.yandex.net/audio/trans1.mp3",
                0,
                "trans1",
                "OK"
        );

        YandexVotApiClient.CachedResult cached = new YandexVotApiClient.CachedResult(finishedResult, System.currentTimeMillis());
        assertFalse(cached.isExpired());

        // Simulated expired entry (older than 30 mins)
        YandexVotApiClient.CachedResult expiredCached = new YandexVotApiClient.CachedResult(
                finishedResult,
                System.currentTimeMillis() - (35 * 60_000L)
        );
        assertTrue(expiredCached.isExpired());
    }

    @Test
    public void testOrchestratorExecutionAndStateTransition() throws Exception {
        AtomicInteger apiCallCount = new AtomicInteger(0);

        YandexVotApi mockApi = new YandexVotApi() {
            @Override
            public YandexVotApiClient.TranslationResult requestTranslation(
                    String videoUrl, double duration, String sourceLang,
                    String targetLang, String videoTitle, boolean useLiveVoices,
                    String oauthToken, boolean firstRequest
            ) {
                apiCallCount.incrementAndGet();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}
                return new YandexVotApiClient.TranslationResult(
                        YandexVotApiClient.STATUS_FINISHED,
                        "https://storage.yandex.net/audio/res.mp3",
                        0,
                        "id123",
                        "OK"
                );
            }
        };

        YandexVotOrchestrator orchestrator = new YandexVotOrchestrator(mockApi, testScheduler);
        final AtomicReference<YandexVotState> lastState = new AtomicReference<>();
        orchestrator.setListener(lastState::set);

        YandexVotOrchestrator.RequestParams params1 = new YandexVotOrchestrator.RequestParams(
                "video1",
                "https://www.youtube.com/watch?v=video1",
                120.0,
                "en",
                "ru",
                "Test Video",
                false,
                null
        );

        long gen1 = orchestrator.startTranslation(params1);
        assertEquals(1, gen1);

        // Wait for request execution
        Thread.sleep(200);

        YandexVotState state = orchestrator.getCurrentState();
        assertEquals(YandexVotState.Status.READY, state.getStatus());
        assertEquals("https://storage.yandex.net/audio/res.mp3", state.getAudioUrl());
        assertEquals(1, apiCallCount.get());

        // Different video should increment generation
        YandexVotOrchestrator.RequestParams params2 = new YandexVotOrchestrator.RequestParams(
                "video2",
                "https://www.youtube.com/watch?v=video2",
                120.0,
                "en",
                "ru",
                "Different Video",
                false,
                null
        );
        long genNew = orchestrator.startTranslation(params2);
        assertEquals(2, genNew);
    }

    @Test
    public void testStaleCallbackIgnored() throws Exception {
        YandexVotApi slowApi = new YandexVotApi() {
            @Override
            public YandexVotApiClient.TranslationResult requestTranslation(
                    String videoUrl, double duration, String sourceLang,
                    String targetLang, String videoTitle, boolean useLiveVoices,
                    String oauthToken, boolean firstRequest
            ) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {}
                return new YandexVotApiClient.TranslationResult(
                        YandexVotApiClient.STATUS_FINISHED,
                        "https://stale.url",
                        0,
                        "stale",
                        "OK"
                );
            }
        };

        YandexVotOrchestrator orchestrator = new YandexVotOrchestrator(slowApi, testScheduler);
        YandexVotOrchestrator.RequestParams paramsA = new YandexVotOrchestrator.RequestParams(
                "videoA",
                "https://www.youtube.com/watch?v=videoA",
                60.0,
                "en",
                "ru",
                "Video A",
                false,
                null
        );

        long genA = orchestrator.startTranslation(paramsA);
        // User immediately switches video
        orchestrator.cancel();
        assertEquals(YandexVotState.Status.CANCELLED, orchestrator.getCurrentState().getStatus());

        Thread.sleep(200);
        // Stale callback must NOT flip state back to READY
        assertNotEquals(YandexVotState.Status.READY, orchestrator.getCurrentState().getStatus());
    }
}

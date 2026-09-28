package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class VotBatch53TvGenericErrorTest {
    @Test
    public void audioFallbackContinuesExistingServerTask() {
        VotTranslationResponse audioRequested = new VotTranslationResponse();
        audioRequested.status = VotTranslationResponse.STATUS_AUDIO_REQUESTED;
        audioRequested.translationId = "translation-id";

        VotTranslationResponse waiting = new VotTranslationResponse();
        waiting.status = VotTranslationResponse.STATUS_WAITING;
        waiting.remainingTimeSec = 55;

        VotTranslationResponse ready = new VotTranslationResponse();
        ready.status = VotTranslationResponse.STATUS_FINISHED;
        ready.url = "https://fake.invalid/translated-audio.mp3";

        ScriptedRequester backend = new ScriptedRequester(audioRequested, waiting, ready);
        RecordingWaitStrategy scheduler = new RecordingWaitStrategy();
        int[] audioFallbackCalls = {0};
        VotClient client = new VotClient(null, backend, scheduler) {
            @Override
            void handleAudioRequested(String youtubeUrl, long durationSec,
                                      String translationId, boolean useLively,
                                      String requestOAuthToken) {
                audioFallbackCalls[0]++;
            }
        };

        List<VotProgress> progress = client.observeTranslation(
                "https://www.youtube.com/watch?v=8Dgdk555G0g", 1453).toList().blockingGet();

        assertEquals(1, audioFallbackCalls[0]);
        assertEquals(Arrays.asList(false, false, true), backend.subsequentFlags);
        assertEquals(2, countFirstRequests(backend.subsequentFlags));
        assertEquals(Arrays.asList(25), scheduler.waitsSec);
        assertEquals(2, progress.size());
        assertEquals(VotProgress.TYPE_WAITING, progress.get(0).type);
        assertEquals(VotProgress.TYPE_READY, progress.get(1).type);
    }

    private static int countFirstRequests(List<Boolean> subsequentFlags) {
        int count = 0;
        for (boolean subsequent : subsequentFlags) {
            if (!subsequent) {
                count++;
            }
        }
        return count;
    }

    private static final class RecordingWaitStrategy implements VotClient.WaitStrategy {
        final List<Integer> waitsSec = new ArrayList<>();

        @Override
        public void waitSeconds(int sec, io.reactivex.ObservableEmitter<?> emitter) {
            waitsSec.add(sec);
        }
    }

    private static final class ScriptedRequester implements VotClient.TranslationRequester {
        final List<Boolean> subsequentFlags = new ArrayList<>();
        private final List<VotTranslationResponse> responses;
        private int index;

        ScriptedRequester(VotTranslationResponse... responses) {
            this.responses = Arrays.asList(responses);
        }

        @Override
        public VotTranslationResponse request(String youtubeUrl, long durationSec,
                                              boolean subsequent, boolean useLively,
                                              String requestOAuthToken) throws IOException {
            subsequentFlags.add(subsequent);
            if (index >= responses.size()) {
                throw new IOException("No scripted response");
            }
            return responses.get(index++);
        }
    }
}

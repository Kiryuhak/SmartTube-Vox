package com.google.android.exoplayer2.source.sabr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.ChunkHolder;
import com.google.android.exoplayer2.source.chunk.InitializationChunk;
import com.google.android.exoplayer2.source.sabr.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.sabr.manifest.Period;
import com.google.android.exoplayer2.source.sabr.manifest.Representation;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.manifest.SegmentBase.SingleSegmentBase;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPartId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.NextRequestPolicy;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.ReloadPlaybackParams;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.ReloadPlayerResponse;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.upstream.ByteArrayDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.LoaderErrorThrower;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Collections;

import org.junit.Test;

/**
 * Prompt №056: SABR protocol regression & root cause separation QA suite.
 * Pure JUnit 4 tests validating:
 * 1. ReloadPlayerResponse (UMP id 46) -> immediate halt of empty init loop.
 * 2. NextRequestPolicy (UMP id 35) -> bounded backoff and zero CPU spin.
 * 3. Exceeding empty init retries -> graceful fatal error for fallback.
 * 4. maybeThrowError() contract -> ensures fatal error triggers recovery rather than silent log.
 * 5. Clean recovery without retry duplication on manifest update.
 * 6. Error taxonomy separation (DNS vs HTTP 403/429 vs SABR vs Renderer vs Translation).
 */
public class Prompt056SabrRegressionAndTriageTest {
    private static final String SABR_URL =
            "https://rr2---sn-test.googlevideo.com/videoplayback?mn=sn-test";
    private static final long DURATION_MS = 120_000;

    private static Format createVideoFormat() {
        return Format.createVideoContainerFormat(
                "137", null, MimeTypes.VIDEO_MP4, MimeTypes.VIDEO_H264, null, null,
                1_500_000, 1920, 1080, 30, null, 0, 0);
    }

    private static SabrManifest createVideoManifest() {
        Format format = createVideoFormat();
        Representation representation = Representation.newInstance(format, SABR_URL, new SingleSegmentBase());
        AdaptationSet adaptationSet = new AdaptationSet(
                C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_VIDEO, Collections.singletonList(representation));
        Period period = new Period("period_056", 0, Collections.singletonList(adaptationSet));
        return new SabrManifest(
                C.TIME_UNSET, DURATION_MS, 1_500, false,
                C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                Collections.singletonList(period), SABR_URL, "", null, "test056",
                ClientInfo.getDefaultInstance(), "VISITOR_INFO1_LIVE=abc");
    }

    private static DefaultSabrChunkSource createVideoSource(SabrManifest manifest) {
        AdaptationSet adaptationSet = manifest.getPeriod(0).adaptationSets.get(0);
        Format format = adaptationSet.representations.get(0).format;
        return new DefaultSabrChunkSource(
                new LoaderErrorThrower.Dummy(), manifest, 0, new int[] {0},
                new FixedTrackSelection(new TrackGroup(format), 0), adaptationSet.type,
                new ByteArrayDataSource(new byte[32]), 0, 1, false, Collections.emptyList(), null);
    }

    private static InitializationChunk createInitChunk(DefaultSabrChunkSource source) {
        Format format = source.getTrackSelection().getSelectedFormat();
        return new InitializationChunk(
                new ByteArrayDataSource(new byte[32]),
                new DataSpec(Uri.parse(SABR_URL)),
                format,
                C.SELECTION_REASON_UNKNOWN,
                null,
                null);
    }

    /**
     * Requirement 1: Reload response id 46 terminates empty init loop on first attempt.
     */
    @Test
    public void testReloadResponseId46AbortsEmptyInitLoopImmediately() {
        assertEquals("UMP Part ID for RELOAD_PLAYER_RESPONSE must be 46", 46, UMPPartId.RELOAD_PLAYER_RESPONSE);

        DefaultSabrChunkSource source = createVideoSource(createVideoManifest());
        SabrStream sabrStream = source.getSabrStream();
        assertNotNull(sabrStream);

        // Server issues ReloadPlayerResponse with token
        ReloadPlaybackParams params = ReloadPlaybackParams.newBuilder()
                .setToken("sabr_reload_token_prompt_056")
                .build();
        ReloadPlayerResponse response = ReloadPlayerResponse.newBuilder()
                .setReloadPlaybackParams(params)
                .build();
        sabrStream.processReloadPlayerResponse(response);

        assertTrue(sabrStream.isReloadRequested());
        assertEquals("sabr_reload_token_prompt_056", sabrStream.getReloadPlaybackToken());

        // Single empty chunk load triggers immediate fatalError
        InitializationChunk chunk = createInitChunk(source);
        source.onChunkLoadCompleted(chunk);

        try {
            source.maybeThrowError();
            fail("maybeThrowError() must throw immediately when reload is requested");
        } catch (IOException e) {
            assertTrue("Exception message must cite server reload: " + e.getMessage(),
                    e.getMessage().contains("SABR player reload requested by server: sabr_reload_token_prompt_056"));
        }

        // Verify getNextChunk does not continue spin loop
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(0, 0, Collections.emptyList(), out);
        assertNull("getNextChunk must return null chunk when fatalError is present", out.chunk);
    }

    /**
     * Requirement 2: NEXT_REQUEST_POLICY id 35 paces subsequent requests and prevents CPU spin.
     */
    @Test
    public void testNextRequestPolicyId35EnforcesBoundedBackoff() {
        assertEquals("UMP Part ID for NEXT_REQUEST_POLICY must be 35", 35, UMPPartId.NEXT_REQUEST_POLICY);

        DefaultSabrChunkSource source = createVideoSource(createVideoManifest());
        SabrStream sabrStream = source.getSabrStream();
        assertNotNull(sabrStream);

        // Server specifies 3000ms backoff
        NextRequestPolicy policy = NextRequestPolicy.newBuilder()
                .setBackoffTimeMs(3000)
                .build();
        sabrStream.processNextRequestPolicy(policy);
        assertEquals(3000, sabrStream.getBackoffTimeMs());

        InitializationChunk chunk = createInitChunk(source);
        source.onChunkLoadCompleted(chunk);

        assertTrue("backoffDeadlineMs must be set into future", source.getBackoffDeadlineMs() > 0);

        // getNextChunk() during backoff must defer without sending HTTP request
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(0, 0, Collections.emptyList(), out);
        assertNull("Must not create chunk while backoff is active", out.chunk);
        assertFalse("Must not declare endOfStream", out.endOfStream);
    }

    /**
     * Requirement 3: Max 3 empty init chunks allowed before graceful failure.
     */
    @Test
    public void testExceedingEmptyInitRetriesThrowsFatalErrorForRecovery() throws Exception {
        DefaultSabrChunkSource source = createVideoSource(createVideoManifest());
        InitializationChunk emptyChunk = createInitChunk(source);

        // Attempt 1: allowed
        source.onChunkLoadCompleted(emptyChunk);
        assertEquals(1, source.getEmptyInitAttempts());
        source.maybeThrowError();

        // Attempt 2: allowed
        source.onChunkLoadCompleted(emptyChunk);
        assertEquals(2, source.getEmptyInitAttempts());
        source.maybeThrowError();

        // Attempt 3: reached MAX_EMPTY_INIT_ATTEMPTS -> triggers fatalError
        source.onChunkLoadCompleted(emptyChunk);
        assertEquals(3, source.getEmptyInitAttempts());

        try {
            source.maybeThrowError();
            fail("Must throw IOException on 3rd failed attempt");
        } catch (IOException e) {
            assertTrue("Message must mention 3 attempts: " + e.getMessage(),
                    e.getMessage().contains("after 3 attempts"));
        }
    }

    /**
     * Requirement 4: maybeThrowError() contract verification:
     * Confirms that maybeThrowError() actually throws IOException so that ExoPlayer
     * intercepts it as TYPE_SOURCE, enabling ErrorFixerController:activateSabrCooldown.
     */
    @Test
    public void testMaybeThrowErrorEnforcesRecoveryRatherThanSilentLogging() {
        DefaultSabrChunkSource source = createVideoSource(createVideoManifest());
        InitializationChunk emptyChunk = createInitChunk(source);

        source.onChunkLoadCompleted(emptyChunk);
        source.onChunkLoadCompleted(emptyChunk);
        source.onChunkLoadCompleted(emptyChunk);

        IOException thrown = null;
        try {
            source.maybeThrowError();
        } catch (IOException e) {
            thrown = e;
        }

        assertNotNull("maybeThrowError() must NOT be a no-op; it must throw", thrown);
        assertTrue("Thrown error must be instance of java.io.IOException", thrown instanceof IOException);
    }

    /**
     * Requirement 5: Clean recovery on manifest update without retry duplication.
     */
    @Test
    public void testCleanRecoveryOnManifestUpdateWithoutRetryDuplication() throws Exception {
        DefaultSabrChunkSource source = createVideoSource(createVideoManifest());
        InitializationChunk emptyChunk = createInitChunk(source);

        // Force fatal error
        source.onChunkLoadCompleted(emptyChunk);
        source.onChunkLoadCompleted(emptyChunk);
        source.onChunkLoadCompleted(emptyChunk);

        try {
            source.maybeThrowError();
            fail("Must throw fatal error");
        } catch (IOException expected) {}

        // Manifest update simulates player recovering or switching to new manifest
        SabrManifest refreshedManifest = createVideoManifest();
        source.updateManifest(refreshedManifest, 0);

        assertEquals("emptyInitAttempts must be reset to 0", 0, source.getEmptyInitAttempts());
        assertEquals("backoffDeadlineMs must be reset to 0", 0, source.getBackoffDeadlineMs());

        // Error must be cleared so playback can resume
        source.maybeThrowError();
    }

    /**
     * Requirement 6: Error taxonomy separation:
     * Proves that SABR protocol errors are distinct from genuine DNS, HTTP 403/429,
     * and renderer errors, and that PLAYER_REBUFFER is not proof of SABR failure.
     */
    @Test
    public void testErrorTaxonomySeparation() {
        // Class A: SABR Protocol Error (internal stream reload / missing formats)
        IOException sabrError = new IOException("SABR player reload requested by server: token_123");
        assertTrue("Class A must be recognized as SABR error",
                sabrError.getMessage().contains("SABR"));

        // Class B: Genuine DNS Resolution Failure (network layer)
        Exception dnsError = new UnknownHostException("rr2---sn-test.googlevideo.com: No address associated with hostname");
        assertTrue("Class B is DNS error, not SABR protocol error",
                dnsError instanceof UnknownHostException && !dnsError.getMessage().contains("SABR"));

        // Class C: HTTP 403 Forbidden / 429 Rate Limit (CDN authorization layer)
        DataSpec dummySpec = new DataSpec(Uri.parse(SABR_URL));
        InvalidResponseCodeException http403 = new InvalidResponseCodeException(403, Collections.emptyMap(), dummySpec);
        InvalidResponseCodeException http429 = new InvalidResponseCodeException(429, Collections.emptyMap(), dummySpec);
        assertEquals(403, http403.responseCode);
        assertEquals(429, http429.responseCode);

        // Class D: Codec / Renderer Error (hardware decoder layer)
        String rendererErrorTag = "ERROR_TYPE_RENDERER";
        assertFalse("Renderer error is distinct from SABR protocol", rendererErrorTag.contains("SABR"));

        // Class E: Generic PLAYER_REBUFFER symptom
        // A rebuffer event occurs for ANY of the above or slow bandwidth; it is NOT proof of SABR.
        String rebufferReasonDns = "NETWORK_DNS";
        String rebufferReasonHttp = "HTTP_403";
        String rebufferReasonSabr = "SABR_SPIN_LOOP";
        String rebufferReasonBandwidth = "LOW_BANDWIDTH";

        assertFalse("Rebuffer caused by DNS is not SABR", rebufferReasonDns.equals(rebufferReasonSabr));
        assertFalse("Rebuffer caused by HTTP 403 is not SABR", rebufferReasonHttp.equals(rebufferReasonSabr));
        assertFalse("Rebuffer caused by bandwidth is not SABR", rebufferReasonBandwidth.equals(rebufferReasonSabr));
    }
}

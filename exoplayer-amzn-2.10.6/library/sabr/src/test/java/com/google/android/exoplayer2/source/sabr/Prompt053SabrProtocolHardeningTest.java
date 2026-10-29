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
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.NextRequestPolicy;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.ReloadPlaybackParams;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.ReloadPlayerResponse;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.upstream.ByteArrayDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.LoaderErrorThrower;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.util.Collections;

import org.junit.Test;

/**
 * Prompt №053: Unit test suite for SABR protocol hardening & issue #6293 mitigation.
 * Pure JUnit 4 tests without Robolectric classloader dependencies.
 */
public class Prompt053SabrProtocolHardeningTest {
    private static final String SABR_URL =
            "https://rr1---sn-primary.googlevideo.com/videoplayback?mn=sn-primary%2Csn-secondary";
    private static final long DURATION_MS = 60_000;

    private static Format createVideoFormat() {
        return Format.createVideoContainerFormat(
                "137", null, MimeTypes.VIDEO_MP4, MimeTypes.VIDEO_H264, null, null,
                1_000_000, 1920, 1080, 30, null, 0, 0);
    }

    private static SabrManifest createVideoManifest() {
        Format format = createVideoFormat();
        Representation representation = Representation.newInstance(format, SABR_URL, new SingleSegmentBase());
        AdaptationSet adaptationSet = new AdaptationSet(
                C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_VIDEO, Collections.singletonList(representation));
        Period period = new Period("test_period", 0, Collections.singletonList(adaptationSet));
        return new SabrManifest(
                C.TIME_UNSET, DURATION_MS, 1_500, false,
                C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                Collections.singletonList(period), SABR_URL, "", null, "test",
                ClientInfo.getDefaultInstance(), "VISITOR_COOKIE=123");
    }

    private static DefaultSabrChunkSource createVideoSource(SabrManifest manifest) {
        AdaptationSet adaptationSet = manifest.getPeriod(0).adaptationSets.get(0);
        Format format = adaptationSet.representations.get(0).format;
        return new DefaultSabrChunkSource(
                new LoaderErrorThrower.Dummy(), manifest, 0, new int[] {0},
                new FixedTrackSelection(new TrackGroup(format), 0), adaptationSet.type,
                new ByteArrayDataSource(new byte[16]), 0, 1, false, Collections.emptyList(), null);
    }

    private static InitializationChunk createInitChunk(DefaultSabrChunkSource source) {
        Format format = source.getTrackSelection().getSelectedFormat();
        return new InitializationChunk(
                new ByteArrayDataSource(new byte[16]),
                new DataSpec(Uri.parse(SABR_URL)),
                format,
                C.SELECTION_REASON_UNKNOWN,
                null,
                null);
    }

    @Test
    public void testSabrStreamReloadStateLifecycle() {
        DefaultSabrChunkSource source = createVideoSource(createVideoManifest());
        SabrStream stream = source.getSabrStream();
        assertNotNull(stream);

        assertFalse("Initial state must not have reload requested", stream.isReloadRequested());
        assertNull("Initial reload token must be null", stream.getReloadPlaybackToken());

        // Process reload player response proto
        ReloadPlaybackParams params = ReloadPlaybackParams.newBuilder()
                .setToken("token_abc_123")
                .build();
        ReloadPlayerResponse response = ReloadPlayerResponse.newBuilder()
                .setReloadPlaybackParams(params)
                .build();

        stream.processReloadPlayerResponse(response);

        assertTrue("Reload flag must be true after processReloadPlayerResponse", stream.isReloadRequested());
        assertEquals("token_abc_123", stream.getReloadPlaybackToken());

        // Clear reload
        stream.clearReloadRequested();
        assertFalse("Reload flag must be cleared", stream.isReloadRequested());
        assertNull("Reload token must be cleared to null", stream.getReloadPlaybackToken());
    }

    @Test
    public void testSabrStreamBackoffParsing() {
        DefaultSabrChunkSource source = createVideoSource(createVideoManifest());
        SabrStream stream = source.getSabrStream();
        assertNotNull(stream);

        assertEquals(0, stream.getBackoffTimeMs());

        NextRequestPolicy policy = NextRequestPolicy.newBuilder()
                .setBackoffTimeMs(2500)
                .build();
        stream.processNextRequestPolicy(policy);

        assertEquals(2500, stream.getBackoffTimeMs());
    }

    @Test
    public void testImmediateFatalErrorOnServerReloadRequest() throws Exception {
        SabrManifest manifest = createVideoManifest();
        DefaultSabrChunkSource source = createVideoSource(manifest);
        SabrStream sabrStream = source.getSabrStream();
        assertNotNull(sabrStream);

        // Simulate server asking for reload
        ReloadPlaybackParams params = ReloadPlaybackParams.newBuilder()
                .setToken("server_reload_token_xyz")
                .build();
        sabrStream.processReloadPlayerResponse(ReloadPlayerResponse.newBuilder().setReloadPlaybackParams(params).build());

        InitializationChunk chunkWithoutFormats = createInitChunk(source);

        // Calling onChunkLoadCompleted once should IMMEDIATELY set fatalError without spinning
        source.onChunkLoadCompleted(chunkWithoutFormats);

        try {
            source.maybeThrowError();
            fail("Expected maybeThrowError() to throw IOException on reload requested");
        } catch (IOException e) {
            assertTrue("Exception message should cite reload requested: " + e.getMessage(),
                    e.getMessage().contains("SABR player reload requested by server: server_reload_token_xyz"));
        }
    }

    @Test
    public void testBoundingEmptyInitRetriesToThree() throws Exception {
        SabrManifest manifest = createVideoManifest();
        DefaultSabrChunkSource source = createVideoSource(manifest);
        InitializationChunk emptyChunk = createInitChunk(source);

        // Attempt 1: should not throw yet
        source.onChunkLoadCompleted(emptyChunk);
        assertEquals(1, source.getEmptyInitAttempts());
        source.maybeThrowError(); // No exception

        // Attempt 2: should not throw yet
        source.onChunkLoadCompleted(emptyChunk);
        assertEquals(2, source.getEmptyInitAttempts());
        source.maybeThrowError(); // No exception

        // Attempt 3: reached MAX_EMPTY_INIT_ATTEMPTS (3) -> sets fatalError
        source.onChunkLoadCompleted(emptyChunk);
        assertEquals(3, source.getEmptyInitAttempts());

        try {
            source.maybeThrowError();
            fail("Expected maybeThrowError() to throw IOException after 3 failed init attempts");
        } catch (IOException e) {
            assertTrue("Exception should indicate exhausted retries: " + e.getMessage(),
                    e.getMessage().contains("SABR initialization failed to obtain sample formats after 3 attempts"));
        }
    }

    @Test
    public void testUpdateManifestResetsAttemptsAndFatalError() throws Exception {
        SabrManifest manifest = createVideoManifest();
        DefaultSabrChunkSource source = createVideoSource(manifest);
        InitializationChunk emptyChunk = createInitChunk(source);

        // Trigger fatal error with 3 attempts
        source.onChunkLoadCompleted(emptyChunk);
        source.onChunkLoadCompleted(emptyChunk);
        source.onChunkLoadCompleted(emptyChunk);

        try {
            source.maybeThrowError();
            fail("Should throw fatal error");
        } catch (IOException expected) {}

        // Manifest update (recovery)
        SabrManifest newManifest = createVideoManifest();
        source.updateManifest(newManifest, 0);

        assertEquals("emptyInitAttempts should be reset to 0", 0, source.getEmptyInitAttempts());
        assertEquals("backoffDeadlineMs should be reset to 0", 0, source.getBackoffDeadlineMs());

        // Error should be cleared
        source.maybeThrowError();
    }

    @Test
    public void testBackoffThrottlingPacesNextChunk() {
        SabrManifest manifest = createVideoManifest();
        DefaultSabrChunkSource source = createVideoSource(manifest);
        SabrStream sabrStream = source.getSabrStream();
        assertNotNull(sabrStream);

        // Inject 5000ms backoff
        NextRequestPolicy policy = NextRequestPolicy.newBuilder()
                .setBackoffTimeMs(5000)
                .build();
        sabrStream.processNextRequestPolicy(policy);

        InitializationChunk chunk = createInitChunk(source);
        source.onChunkLoadCompleted(chunk);

        // Backoff deadline should be set
        assertTrue("Backoff deadline should be set", source.getBackoffDeadlineMs() > 0);

        // When getNextChunk is called during backoff, out.chunk must be null (no redundant burst requests)
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(0, 0, Collections.emptyList(), out);

        assertNull("Should not generate chunk during server-requested backoff interval", out.chunk);
        assertFalse(out.endOfStream);
    }
}

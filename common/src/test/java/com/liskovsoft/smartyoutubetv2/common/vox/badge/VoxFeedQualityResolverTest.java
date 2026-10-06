package com.liskovsoft.smartyoutubetv2.common.vox.badge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VoxFeedQualityResolverTest {
    private long now = 1_000_000L;

    private VoxFeedQualityResolver resolver(VoxFeedQualityResolver.Lookup lookup) {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = context.getSharedPreferences("quality-test-" + System.nanoTime(), 0);
        return new VoxFeedQualityResolver(prefs, lookup, () -> now, new Handler(Looper.getMainLooper()));
    }

    @Test public void positiveAndNegativeTtlAndLru() throws Exception {
        VoxFeedQualityResolver resolver = resolver(id -> null);
        resolver.remember("played", 1080, VoxFeedQualityResolver.Source.PLAYBACK);
        assertEquals("1080p", resolver.cached("played"));
        resolver.remember("played", 480, VoxFeedQualityResolver.Source.PLAYER_RESOLVE);
        assertEquals("1080p", resolver.cached("played"));
        resolver.rememberBadge("feed", "4K", VoxFeedQualityResolver.Source.FEED);
        resolver.remember("feed", 720, VoxFeedQualityResolver.Source.PLAYBACK);
        assertEquals("4K", resolver.cached("feed"));
        resolver.rememberBadge("download", "360p", VoxFeedQualityResolver.Source.DOWNLOAD);
        resolver.remember("download", 480, VoxFeedQualityResolver.Source.PLAYER_RESOLVE);
        assertEquals("360p", resolver.cached("download"));
        for (int i = 0; i < 260; i++) resolver.remember("id" + i, 720, VoxFeedQualityResolver.Source.FEED);
        assertNull(resolver.cached("id0"));
        assertEquals("720p", resolver.cached("id259"));
        now += TimeUnit.DAYS.toMillis(8);
        assertNull(resolver.cached("id259"));
    }

    @Test public void deduplicatesAndLimitsConcurrencyAndUnknown() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        VoxFeedQualityResolver resolver = resolver(id -> {
            calls.incrementAndGet();
            int current = running.incrementAndGet();
            peak.updateAndGet(old -> Math.max(old, current));
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            running.decrementAndGet();
            return null;
        });
        try {
            resolver.resolve("A", (id, badge) -> fail("Unknown must be hidden"));
            resolver.resolve("A", (id, badge) -> fail("Unknown must be hidden"));
            resolver.resolve("B", (id, badge) -> fail("Unknown must be hidden"));
            resolver.resolve("C", (id, badge) -> fail("Unknown must be hidden"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(2, peak.get());
        } finally {
            release.countDown();
        }
        Thread.sleep(100);
        assertEquals(3, calls.get());
        assertTrue(peak.get() <= VoxFeedQualityResolver.MAX_CONCURRENT_RESOLVES);
        resolver.resolve("A", (id, badge) -> fail("Negative cache must suppress retry"));
        assertEquals(3, calls.get());
        now += TimeUnit.HOURS.toMillis(4);
        resolver.resolve("A", (id, badge) -> { });
        Thread.sleep(100);
        assertEquals(4, calls.get());
    }

    @Test public void formatHeightAndHolderGeneration() {
        MediaFormat format = (MediaFormat) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MediaFormat.class}, (proxy, method, args) ->
                        method.getName().equals("getHeight") ? 2160 : defaultValue(method.getReturnType()));
        MediaItemFormatInfo info = (MediaItemFormatInfo) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAdaptiveFormats")) return Collections.singletonList(format);
                    if (method.getName().equals("getUrlFormats")) return Collections.emptyList();
                    return defaultValue(method.getReturnType());
                });
        assertEquals(2160, VoxFeedQualityResolver.heightOf(info));
        assertEquals("4K", VoxQualityBadgeFormatter.formatFromHeight(VoxFeedQualityResolver.heightOf(info)));
        assertNull(VoxQualityBadgeFormatter.formatFromHeight(0));

        VoxQualityBindingGuard guard = new VoxQualityBindingGuard();
        long old = guard.bind("A");
        long next = guard.bind("B");
        assertFalse(guard.accepts("A", old));
        assertTrue(guard.accepts("B", next));
        guard.unbind();
        assertFalse(guard.accepts("B", next));
    }

    @Test public void http429StartsBackoff() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch attempted = new CountDownLatch(1);
        VoxFeedQualityResolver resolver = resolver(id -> {
            calls.incrementAndGet();
            attempted.countDown();
            throw new IllegalStateException("Response code: 429");
        });
        resolver.resolve("limited", (id, badge) -> fail());
        assertTrue(attempted.await(3, TimeUnit.SECONDS));
        Thread.sleep(50);
        resolver.resolve("another", (id, badge) -> fail());
        assertEquals(1, calls.get());
        now += TimeUnit.MINUTES.toMillis(31);
        CountDownLatch retry = new CountDownLatch(1);
        resolver.resolve("another", (id, badge) -> retry.countDown());
        Thread.sleep(50);
        assertEquals(2, calls.get());
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }
}

package com.liskovsoft.smartyoutubetv2.common.vox.badge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Metadata-only quality enrichment for cards that have remained visible. */
public final class VoxFeedQualityResolver {
    public static final int MAX_CONCURRENT_RESOLVES = 2;
    public static final long VISIBLE_DWELL_MS = 650;
    private static final int MAX_CACHE = 256;
    private static final long POSITIVE_TTL_MS = TimeUnit.DAYS.toMillis(7);
    private static final long NEGATIVE_TTL_MS = TimeUnit.HOURS.toMillis(3);
    private static final long RATE_LIMIT_MS = TimeUnit.MINUTES.toMillis(30);
    private static final String PREFS = "vox_feed_quality_v2";
    private static final String TAG = "VoxFeedQuality";
    private static volatile VoxFeedQualityResolver instance;

    public interface Lookup { MediaItemFormatInfo get(String videoId) throws Exception; }
    public interface Callback { void onQuality(String videoId, String badge); }
    public interface Clock { long now(); }
    public enum Source { FEED, PLAYBACK, DOWNLOAD, PLAYER_RESOLVE, UNKNOWN }

    private final SharedPreferences prefs;
    private final Lookup lookup;
    private final Clock clock;
    private final Handler main;
    private final ThreadPoolExecutor workers;
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(32, .75f, true);
    private final Map<String, List<Callback>> inFlight = new LinkedHashMap<>();
    private long backoffUntil;

    public static VoxFeedQualityResolver get(Context context) {
        if (instance == null) {
            synchronized (VoxFeedQualityResolver.class) {
                if (instance == null) instance = new VoxFeedQualityResolver(
                        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                        id -> YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(id),
                        System::currentTimeMillis, new Handler(Looper.getMainLooper()));
            }
        }
        return instance;
    }

    VoxFeedQualityResolver(SharedPreferences prefs, Lookup lookup, Clock clock, Handler main) {
        this.prefs = prefs;
        this.lookup = lookup;
        this.clock = clock;
        this.main = main;
        workers = new ThreadPoolExecutor(MAX_CONCURRENT_RESOLVES, MAX_CONCURRENT_RESOLVES,
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
                new ThreadPoolExecutor.AbortPolicy());
        readCache();
    }

    public synchronized String cached(String videoId) {
        Entry entry = cache.get(videoId);
        if (entry == null) return null;
        long age = clock.now() - entry.resolvedAt;
        if (age < 0 || age > (entry.height > 0 ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS)) {
            cache.remove(videoId);
            persist();
            return null;
        }
        debug("VOX_QUALITY_CACHE_HIT", videoId);
        return entry.badge;
    }

    public synchronized void remember(String videoId, int height, Source source) {
        if (videoId == null || videoId.isEmpty() || height <= 0) return;
        String badge = VoxQualityBadgeFormatter.formatFromHeight(height);
        if (badge == null) return;
        Entry previous = cache.get(videoId);
        if (previous != null && previous.height > 0 && getPriority(previous.source) > getPriority(source)) return;
        cache.put(videoId, new Entry(height, badge, source, clock.now()));
        trimAndPersist();
    }

    public synchronized void rememberBadge(String videoId, String rawBadge, Source source) {
        VoxQualityBadge parsed = VoxQualityBadgeFormatter.parse(rawBadge);
        if (videoId == null || videoId.isEmpty() || parsed == null || parsed.getResolutionP() == null) return;
        String badge = VoxQualityBadgeFormatter.format(parsed);
        Entry previous = cache.get(videoId);
        if (previous != null && previous.height > 0 && getPriority(previous.source) > getPriority(source)) return;
        if (previous != null && previous.height == parsed.getResolutionP() &&
                previous.source == source && badge.equals(previous.badge)) return;
        cache.put(videoId, new Entry(parsed.getResolutionP(), badge, source, clock.now()));
        trimAndPersist();
    }

    /** Invoked only after the presenter has confirmed actual visibility for VISIBLE_DWELL_MS. */
    public Subscription resolve(String videoId, Callback callback) {
        if (videoId == null || videoId.isEmpty()) return () -> { };
        synchronized (this) {
            Entry known = cache.get(videoId);
            if (known != null && clock.now() - known.resolvedAt <=
                    (known.height > 0 ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS)) {
                if (known.badge != null) main.post(() -> callback.onQuality(videoId, known.badge));
                return () -> { };
            }
            if (clock.now() < backoffUntil) {
                debug("VOX_QUALITY_RESOLVE_RATE_LIMIT", videoId);
                return () -> { };
            }
            List<Callback> listeners = inFlight.get(videoId);
            if (listeners != null) {
                listeners.add(callback);
                return () -> cancel(videoId, callback);
            }
            listeners = new ArrayList<>();
            listeners.add(callback);
            inFlight.put(videoId, listeners);
            try {
                workers.execute(() -> fetch(videoId));
            } catch (RuntimeException queueFull) {
                inFlight.remove(videoId);
                debug("VOX_QUALITY_RESOLVE_CANCEL", videoId);
            }
            return () -> cancel(videoId, callback);
        }
    }

    public interface Subscription { void cancel(); }

    private synchronized void cancel(String id, Callback callback) {
        List<Callback> listeners = inFlight.get(id);
        if (listeners != null) listeners.remove(callback);
        debug("VOX_QUALITY_RESOLVE_CANCEL", id);
    }

    private void fetch(String id) {
        synchronized (this) {
            List<Callback> listeners = inFlight.get(id);
            if (listeners == null || listeners.isEmpty() || clock.now() < backoffUntil) {
                inFlight.remove(id);
                return;
            }
        }
        int height = 0;
        boolean rateLimited = false;
        try {
            debug("VOX_QUALITY_RESOLVE_START", id);
            MediaItemFormatInfo info = lookup.get(id);
            if (info != null && !info.isLive()) {
                height = Math.max(maxHeight(info.getAdaptiveFormats()), maxHeight(info.getUrlFormats()));
            }
        } catch (Exception error) {
            if (isRateLimited(error)) {
                synchronized (this) { backoffUntil = clock.now() + RATE_LIMIT_MS; }
                rateLimited = true;
                debug("VOX_QUALITY_RESOLVE_RATE_LIMIT", id);
            }
        }
        String badge = VoxQualityBadgeFormatter.formatFromHeight(height);
        List<Callback> listeners;
        synchronized (this) {
            // Playback or explicit feed metadata may have supplied a stronger value meanwhile.
            Entry previous = cache.get(id);
            if (!rateLimited && (previous == null || getPriority(previous.source) <= getPriority(Source.PLAYER_RESOLVE))) {
                cache.put(id, new Entry(height, badge, height > 0 ? Source.PLAYER_RESOLVE : Source.UNKNOWN, clock.now()));
                trimAndPersist();
            } else if (previous != null) {
                badge = previous.badge;
            }
            listeners = inFlight.remove(id);
        }
        debug("VOX_QUALITY_RESOLVE_DONE", id);
        if (badge != null && listeners != null) {
            String result = badge;
            for (Callback listener : listeners) main.post(() -> listener.onQuality(id, result));
        }
    }

    private static int maxHeight(List<MediaFormat> formats) {
        int height = 0;
        if (formats != null) for (MediaFormat format : formats) {
            if (format != null) height = Math.max(height, format.getHeight());
        }
        return height;
    }

    public static int heightOf(MediaItemFormatInfo info) {
        return info == null || info.isLive() ? 0 :
                Math.max(maxHeight(info.getAdaptiveFormats()), maxHeight(info.getUrlFormats()));
    }

    private static boolean isRateLimited(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("429") || message.toLowerCase(java.util.Locale.ROOT).contains("too many requests"))) return true;
        }
        return false;
    }

    public static int getPriority(Source source) {
        if (source == null) return 0;
        switch (source) {
            case PLAYBACK: return 40;
            case DOWNLOAD: return 30;
            case PLAYER_RESOLVE: return 20;
            case FEED: return 10;
            default: return 0;
        }
    }

    private void trimAndPersist() {
        while (cache.size() > MAX_CACHE) cache.remove(cache.keySet().iterator().next());
        persist();
    }

    private void persist() {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, Entry> item : cache.entrySet()) {
            Entry entry = item.getValue();
            if (entry.source == Source.FEED) continue;
            try {
                array.put(new JSONObject().put("id", item.getKey()).put("height", entry.height)
                        .put("badge", entry.badge).put("source", entry.source.name())
                        .put("at", entry.resolvedAt));
            } catch (Exception ignored) { }
        }
        prefs.edit().putString("entries", array.toString()).apply();
    }

    private void readCache() {
        try {
            JSONArray array = new JSONArray(prefs.getString("entries", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Source source = Source.valueOf(obj.getString("source"));
                if (source == Source.FEED) continue;
                Entry entry = new Entry(obj.getInt("height"), obj.optString("badge", null),
                        source, obj.getLong("at"));
                long age = clock.now() - entry.resolvedAt;
                if (age >= 0 && age <= (entry.height > 0 ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS))
                    cache.put(obj.getString("id"), entry);
            }
            while (cache.size() > MAX_CACHE) cache.remove(cache.keySet().iterator().next());
        } catch (Exception ignored) { cache.clear(); }
    }

    private static void debug(String event, String id) {
        if (com.liskovsoft.smartyoutubetv2.common.BuildConfig.DEBUG) Log.d(TAG, event + " " + id);
    }

    private static final class Entry {
        final int height;
        final String badge;
        final Source source;
        final long resolvedAt;
        Entry(int height, String badge, Source source, long resolvedAt) {
            this.height = height; this.badge = badge; this.source = source; this.resolvedAt = resolvedAt;
        }
    }
}

package dev.scyphoz.dappermap.service;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.scyphoz.dappermap.DapperMap;
import dev.scyphoz.dappermap.config.DapperMapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicCreatureIcons;
import dev.scyphoz.dappermap.dynamic.DynamicAssetPublisher;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

/**
 * Server-side load figures for {@code /dappermap admin stats} and the optional adaptive load
 * shedding that steps live minimaps down one update-speed profile while the server is busy.
 *
 * <p>Hot paths only add to lock-free counters (one {@code LongAdder} add per render job or
 * published image). Once a second the DapperMap heartbeat folds them into a 60-second window,
 * which is also where the load-shedding decision is made, so there is no extra thread and no
 * per-tick allocation.
 */
public final class DapperMapServerLoad {
    private static final HytaleLogger LOGGER = HytaleLogger.get("DapperMap");
    private static final int WINDOW = 60;
    /** Load must stay below the thresholds this long before full speed is restored. */
    private static final long RECOVERY_MS = 60_000L;

    // Counters written from the render pool and world threads.
    private static final LongAdder RENDER_JOBS = new LongAdder();
    private static final LongAdder RENDER_NANOS = new LongAdder();
    private static final AtomicLong RENDER_PEAK_NANOS = new AtomicLong();
    private static final LongAdder REJECTIONS = new LongAdder();
    private static final LongAdder BYTES_SENT = new LongAdder();
    private static final LongAdder ASSETS_SENT = new LongAdder();
    // Terrain image encodes (north-up renders and player-up frames) and how many fit a palette.
    private static final LongAdder TERRAIN_ENCODED = new LongAdder();
    private static final LongAdder TERRAIN_PALETTE = new LongAdder();
    private static final LongAdder TERRAIN_REDUCED = new LongAdder();
    private static final LongAdder TERRAIN_BYTES = new LongAdder();

    // Per-second samples for the last minute (heartbeat thread only, read under the class lock).
    private static final long[] JOBS = new long[WINDOW], NANOS = new long[WINDOW], PEAKS = new long[WINDOW],
            REJECTED = new long[WINDOW], BYTES = new long[WINDOW], ASSETS = new long[WINDOW],
            TERRAIN = new long[WINDOW], TERRAIN_PAL = new long[WINDOW], TERRAIN_RED = new long[WINDOW],
            TERRAIN_SIZE = new long[WINDOW];
    private static int slot;
    private static long startedMs;

    /** 0 = normal, 1 = live minimaps are running one speed profile slower. */
    private static volatile int sheddingLevel;
    private static long lastBusyMs;
    private static String sheddingReason = "";
    private static ScheduledFuture<?> ticker;
    private static DapperMapSessionManager sessions;

    private DapperMapServerLoad() { }

    // ------------------------------------------------------------------------------------
    // Hot-path recording (cheap, lock-free)
    // ------------------------------------------------------------------------------------

    /** One finished render-pool job and how long it ran. */
    public static void recordRender(long nanos) {
        RENDER_JOBS.increment();
        RENDER_NANOS.add(nanos);
        long peak;
        while (nanos > (peak = RENDER_PEAK_NANOS.get()) && !RENDER_PEAK_NANOS.compareAndSet(peak, nanos)) { }
    }

    /** Render work refused because the bounded queue was full. */
    public static void recordRejection() {
        REJECTIONS.increment();
    }

    /** One generated image sent to a client. */
    public static void recordAsset(int bytes) {
        ASSETS_SENT.increment();
        BYTES_SENT.add(bytes);
    }

    /** One terrain image encoded: PNG size and kind (0 exact palette, 1 colour-reduced, 2 full colour). */
    public static void recordTerrainImage(int bytes, int kind) {
        TERRAIN_ENCODED.increment();
        TERRAIN_BYTES.add(bytes);
        if (kind == 0) TERRAIN_PALETTE.increment();
        else if (kind == 1) TERRAIN_REDUCED.increment();
    }

    /** Extra update-speed steps applied to live minimaps (0 or 1). */
    public static int sheddingLevel() {
        return sheddingLevel;
    }

    // ------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------

    /** Starts the once-a-second sampler on DapperMap's heartbeat thread. */
    public static synchronized void start(DapperMapSessionManager manager) {
        sessions = manager;
        startedMs = System.currentTimeMillis();
        if (ticker == null) ticker = DynamicMinimapSession.heartbeat().scheduleAtFixedRate(
                DapperMapServerLoad::tickSafe, 1L, 1L, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (ticker != null) ticker.cancel(false);
        ticker = null;
        sheddingLevel = 0;
    }

    private static void tickSafe() {
        try { tick(); } catch (RuntimeException ignored) { }
    }

    /** Folds the counters into this second's slot and updates the load-shedding level. */
    private static void tick() {
        long now = System.currentTimeMillis();
        int rejectedLastMinute;
        int liveMaps;
        synchronized (DapperMapServerLoad.class) {
            slot = (slot + 1) % WINDOW;
            JOBS[slot] = RENDER_JOBS.sumThenReset();
            NANOS[slot] = RENDER_NANOS.sumThenReset();
            PEAKS[slot] = RENDER_PEAK_NANOS.getAndSet(0);
            REJECTED[slot] = REJECTIONS.sumThenReset();
            BYTES[slot] = BYTES_SENT.sumThenReset();
            ASSETS[slot] = ASSETS_SENT.sumThenReset();
            TERRAIN[slot] = TERRAIN_ENCODED.sumThenReset();
            TERRAIN_PAL[slot] = TERRAIN_PALETTE.sumThenReset();
            TERRAIN_RED[slot] = TERRAIN_REDUCED.sumThenReset();
            TERRAIN_SIZE[slot] = TERRAIN_BYTES.sumThenReset();
            rejectedLastMinute = (int) Math.min(Integer.MAX_VALUE, sum(REJECTED));
        }
        DapperMapSessionManager manager = sessions;
        liveMaps = manager == null ? 0 : manager.liveSessionCount();

        DapperMapConfig config = DapperMap.getConfig() == null ? null : DapperMap.getConfig().get();
        int level = 0;
        String reason = "";
        if (config != null && config.isLoadShedding()) {
            boolean refused = rejectedLastMinute >= config.getLoadSheddingRejectionsPerMinute();
            boolean crowded = config.getLoadSheddingPlayers() > 0 && liveMaps > config.getLoadSheddingPlayers();
            boolean busy = refused || crowded;
            if (busy) lastBusyMs = now;
            // Step down at once; step back up only after a quiet minute (no flapping).
            level = busy || (sheddingLevel > 0 && now - lastBusyMs < RECOVERY_MS) ? 1 : 0;
            // The reason text is only built when the level changes (not every busy second).
            if (level > 0 && sheddingLevel == 0) reason = refused
                    ? rejectedLastMinute + " render jobs refused in the last minute"
                    : liveMaps + " minimaps open (limit " + config.getLoadSheddingPlayers() + ")";
        }
        if (level != sheddingLevel) {
            sheddingLevel = level;
            sheddingReason = reason;
            LOGGER.at(Level.INFO).log(level > 0
                    ? "DapperMap: load shedding on (" + reason + "); live minimaps run one update speed slower."
                    : "DapperMap: load shedding off; live minimaps back to their own update speed.");
            if (manager != null) manager.refreshLiveSettings();
        }
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long value : values) total += value;
        return total;
    }

    private static long max(long[] values) {
        long best = 0;
        for (long value : values) best = Math.max(best, value);
        return best;
    }

    // ------------------------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------------------------

    /** Lines for {@code /dappermap admin stats}. Work is done only when the command runs. */
    public static List<String> report(DapperMapSessionManager manager, DapperMapConfig config) {
        long jobs, nanos, peak, rejected, bytes, assets, terrain, terrainPalette, terrainReduced, terrainBytes;
        synchronized (DapperMapServerLoad.class) {
            jobs = sum(JOBS);
            nanos = sum(NANOS);
            peak = max(PEAKS);
            rejected = sum(REJECTED);
            bytes = sum(BYTES);
            assets = sum(ASSETS);
            terrain = sum(TERRAIN);
            terrainPalette = sum(TERRAIN_PAL);
            terrainReduced = sum(TERRAIN_RED);
            terrainBytes = sum(TERRAIN_SIZE);
        }
        long seconds = Math.max(1, Math.min(WINDOW, (System.currentTimeMillis() - startedMs) / 1000L));
        int[] pool = DynamicMinimapSession.renderPoolStatus();
        int[] speeds = manager == null ? new int[4] : manager.liveSpeedCounts();
        List<String> lines = new ArrayList<>();
        lines.add("DapperMap stats (last " + seconds + " s):");
        lines.add(String.format(Locale.ROOT, "Render pool: %d jobs, avg %.1f ms, peak %.1f ms, %d refused; now %d running, %d queued (%d threads)",
                jobs, jobs == 0 ? 0.0 : nanos / 1e6 / jobs, peak / 1e6, rejected, pool[0], pool[1], pool[2]));
        lines.add(String.format(Locale.ROOT, "Images sent: %d (%.1f KB/min across all players, PNG level %d)",
                assets, bytes / 1024.0 * 60.0 / seconds, dev.scyphoz.dappermap.util.DapperMapUtil.pngCompression()));
        // Encoded (not sent): images the client already has are not sent again.
        lines.add(String.format(Locale.ROOT, "Terrain images: %d encoded (%d exact palette, %d colour-reduced, %d full colour), avg %.1f KB",
                terrain, terrainPalette, terrainReduced, terrain - terrainPalette - terrainReduced,
                terrain == 0 ? 0.0 : terrainBytes / 1024.0 / terrain));
        lines.add("Live minimaps: " + (speeds[0] + speeds[1] + speeds[2] + speeds[3])
                + " (very-fast " + speeds[0] + ", fast " + speeds[1] + ", normal " + speeds[2] + ", slow " + speeds[3] + ")");
        if (config != null) {
            lines.add("Limits: " + (config.hasLimits() ? config.describeLimits() : "none")
                    + (manager == null ? "" : " - " + manager.limitedPlayerCount() + " player(s) currently limited"));
            lines.add("Load shedding: " + (!config.isLoadShedding() ? "disabled"
                    : sheddingLevel > 0 ? "ACTIVE (" + sheddingReason + ")" : "enabled, not active")
                    + " [trigger: " + config.getLoadSheddingRejectionsPerMinute() + " refused/min"
                    + (config.getLoadSheddingPlayers() > 0 ? " or > " + config.getLoadSheddingPlayers() + " minimaps" : "") + "]");
        }
        lines.add("Caches: " + DynamicCreatureIcons.cacheSummary() + ", delivered-image records " + DynamicAssetPublisher.deliveredEntries());
        return lines;
    }
}

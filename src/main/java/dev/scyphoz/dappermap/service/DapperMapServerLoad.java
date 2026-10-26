package dev.scyphoz.dappermap.service;

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
    private static final int WINDOW = 60;
    /** Load must stay below the thresholds this long before full speed is restored. */
    private static final long RECOVERY_MS = 60_000L;

    /** Summed counters, written from the render pool and world threads (one LongAdder each). */
    private enum Counter {
        RENDER_JOBS, RENDER_NANOS, REJECTIONS, BYTES_SENT, ASSETS_SENT,
        // Terrain image encodes (north-up renders and player-up frames), how many fit a palette, and their bytes.
        TERRAIN_ENCODED, TERRAIN_PALETTE, TERRAIN_REDUCED, TERRAIN_BYTES;
        final LongAdder adder = new LongAdder();
    }
    private static final Counter[] COUNTERS = Counter.values();
    /** Longest render job this second (a maximum, not a sum). */
    private static final AtomicLong RENDER_PEAK_NANOS = new AtomicLong();

    // Per-second samples for the last minute, one row per counter (heartbeat thread only, read under the class lock).
    private static final long[][] WINDOWS = new long[COUNTERS.length][WINDOW];
    private static final long[] PEAKS = new long[WINDOW];
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
        Counter.RENDER_JOBS.adder.increment();
        Counter.RENDER_NANOS.adder.add(nanos);
        long peak;
        while (nanos > (peak = RENDER_PEAK_NANOS.get()) && !RENDER_PEAK_NANOS.compareAndSet(peak, nanos)) { }
    }

    /** Render work refused because the bounded queue was full. */
    public static void recordRejection() {
        Counter.REJECTIONS.adder.increment();
    }

    /** One generated image sent to a client. */
    public static void recordAsset(int bytes) {
        Counter.ASSETS_SENT.adder.increment();
        Counter.BYTES_SENT.adder.add(bytes);
    }

    /** One terrain image encoded: PNG size and kind (0 exact palette, 1 colour-reduced, 2 full colour). */
    public static void recordTerrainImage(int bytes, int kind) {
        Counter.TERRAIN_ENCODED.adder.increment();
        Counter.TERRAIN_BYTES.adder.add(bytes);
        if (kind == 0) Counter.TERRAIN_PALETTE.adder.increment();
        else if (kind == 1) Counter.TERRAIN_REDUCED.adder.increment();
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
        try { tick(); } catch (RuntimeException failure) { dev.scyphoz.dappermap.util.DapperMapUtil.logFailure("load sampler", failure); }
    }

    /** Folds the counters into this second's slot and updates the load-shedding level. */
    private static void tick() {
        long now = System.currentTimeMillis();
        int rejectedLastMinute;
        int liveMaps;
        synchronized (DapperMapServerLoad.class) {
            slot = (slot + 1) % WINDOW;
            for (Counter counter : COUNTERS) WINDOWS[counter.ordinal()][slot] = counter.adder.sumThenReset();
            PEAKS[slot] = RENDER_PEAK_NANOS.getAndSet(0);
            rejectedLastMinute = (int) Math.min(Integer.MAX_VALUE, total(Counter.REJECTIONS));
        }
        DapperMapSessionManager manager = sessions;
        liveMaps = manager == null ? 0 : manager.liveSessionCount();

        DapperMapConfig config = DapperMap.config();
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
            dev.scyphoz.dappermap.util.DapperMapUtil.logInfo(level > 0
                    ? "DapperMap: load shedding on (" + reason + "); live minimaps run one update speed slower."
                    : "DapperMap: load shedding off; live minimaps back to their own update speed.");
            if (manager != null) manager.refreshLiveSettings();
        }
    }

    /** One counter's total over the last minute (caller holds the class lock). */
    private static long total(Counter counter) {
        long total = 0;
        for (long value : WINDOWS[counter.ordinal()]) total += value;
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
            jobs = total(Counter.RENDER_JOBS);
            nanos = total(Counter.RENDER_NANOS);
            peak = max(PEAKS);
            rejected = total(Counter.REJECTIONS);
            bytes = total(Counter.BYTES_SENT);
            assets = total(Counter.ASSETS_SENT);
            terrain = total(Counter.TERRAIN_ENCODED);
            terrainPalette = total(Counter.TERRAIN_PALETTE);
            terrainReduced = total(Counter.TERRAIN_REDUCED);
            terrainBytes = total(Counter.TERRAIN_BYTES);
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
                    + (manager == null ? "" : " - " + manager.limitedPlayerCount() + " player(s) currently limited")
                    + "; terrain colours " + DapperMapConfig.terrainColoursText(config.getTerrainColours()));
            lines.add("Load shedding: " + (!config.isLoadShedding() ? "disabled"
                    : sheddingLevel > 0 ? "ACTIVE (" + sheddingReason + ")" : "enabled, not active")
                    + " [trigger: " + config.getLoadSheddingRejectionsPerMinute() + " refused/min"
                    + (config.getLoadSheddingPlayers() > 0 ? " or > " + config.getLoadSheddingPlayers() + " minimaps" : "") + "]");
        }
        lines.add("Caches: " + DynamicCreatureIcons.cacheSummary() + ", delivered-image records " + DynamicAssetPublisher.deliveredEntries());
        long failures = dev.scyphoz.dappermap.util.DapperMapUtil.failureCount();
        if (failures > 0) lines.add("Errors caught since start: " + failures + " (details in the server log)");
        return lines;
    }
}

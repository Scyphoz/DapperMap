package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.TimeModule;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapMarker.Style;
import dev.scyphoz.dappermap.integration.BetterMapIntegration;
import dev.scyphoz.dappermap.integration.NativeWorldMapIntegration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.scyphoz.dappermap.service.DapperMapServerLoad;
import org.joml.Vector3d;
import dev.scyphoz.dappermap.util.DapperMapUtil;

/**
 * Runs one player's minimap update loop, coordinating terrain renders, marker scans,
 * dynamic-asset delivery and incremental HUD updates.
 */
public final class DynamicMinimapSession {
    private static final int SELF_BUCKETS = DynamicMinimapRenderer.SELF_ARROW_BUCKETS;
    private static final int RENDER_THREADS = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 4));
    private static final ExecutorService RENDER_POOL = new java.util.concurrent.ThreadPoolExecutor(
            RENDER_THREADS, RENDER_THREADS, 0L, TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64), DapperMapUtil.daemonThreads("DapperMap-Render"),
            (rejectedTask, executor) -> { throw PoolSaturated.INSTANCE; });

    /**
     * Thrown when the render queue is full (or the pool is shut down). One shared instance with
     * no stack trace: under overload every session retries each tick, and AbortPolicy built a
     * new exception with a full stack trace for every refusal.
     */
    private static final class PoolSaturated extends java.util.concurrent.RejectedExecutionException {
        private static final long serialVersionUID = 1L;
        static final PoolSaturated INSTANCE = new PoolSaturated();
        private PoolSaturated() { super("DapperMap render pool saturated"); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    /** False means saturation or shutdown; callers can retry on a later tick. */
    public static boolean renderAsync(Runnable work) {
        try { RENDER_POOL.execute(timed(work)); return true; }
        catch (java.util.concurrent.RejectedExecutionException rejected) {
            DapperMapServerLoad.recordRejection(); // Admin stats / load shedding.
            return false;
        }
    }

    /** Wraps a render job so its run time feeds the admin stats (two nanoTime reads per job). */
    private static Runnable timed(Runnable work) {
        return () -> {
            long start = System.nanoTime();
            try { work.run(); }
            finally { DapperMapServerLoad.recordRender(System.nanoTime() - start); }
        };
    }

    /** {running jobs, queued jobs, pool threads} for /dappermap admin stats. */
    public static int[] renderPoolStatus() {
        var pool = (java.util.concurrent.ThreadPoolExecutor) RENDER_POOL;
        return new int[]{pool.getActiveCount(), pool.getQueue().size(), pool.getPoolSize()};
    }

    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.AtomicBoolean> renderPending = new java.util.concurrent.ConcurrentHashMap<>();
    /** One queued world task per kind and world; {@code next} holds the newest work for it. */
    private record WorldTask(World world, java.util.concurrent.atomic.AtomicReference<Runnable> next) {}
    private final java.util.concurrent.ConcurrentMap<String, WorldTask> worldPending = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * DapperMap's own heartbeat thread. Hytale's HytaleServer.SCHEDULED_EXECUTOR is a single
     * thread shared by the whole server, so per-player minimap timers must not run there.
     * Work on this thread is limited to cheap checks and handing tasks to world threads.
     */
    private static final java.util.concurrent.ScheduledExecutorService HEARTBEAT =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    DapperMapUtil.daemonThreads("DapperMap-Heartbeat"));

    /** Shared scheduler for DapperMap's periodic housekeeping (e.g. cave image pruning). */
    public static java.util.concurrent.ScheduledExecutorService heartbeat() { return HEARTBEAT; }

    public static void shutdownRendering() {
        HEARTBEAT.shutdownNow();
        RENDER_POOL.shutdownNow();
    }

    // Coalesce each kind of work: slow PNG compression must not build an unbounded backlog.
    /** True while a render of this kind is queued or running (submitRender would drop a new one). */
    private boolean renderBusy(String kind) {
        var pending = renderPending.get(kind);
        return pending != null && pending.get();
    }

    private boolean submitRender(String kind, Runnable work) {
        if (!this.canRenderHud(this.hud)) return false;
        var pending = renderPending.computeIfAbsent(kind, ignored -> new java.util.concurrent.atomic.AtomicBoolean());
        if (!pending.compareAndSet(false, true)) return false;
        World sourceWorld = this.lastObservedWorld;
        DynamicMinimapHud sourceHud = this.hud;
        if (!renderAsync(() -> {
            try {
                if (this.canRenderHud(sourceHud) && sourceWorld != null
                        && sourceWorld.isAlive() && sourceWorld == currentWorld()) work.run();
            } finally { pending.set(false); }
        })) {
            pending.set(false);
            return false;
        }
        return true;
    }

    /** The supplied server wraps stopped-world rejection in SkipSentryException. */
    public static boolean isStoppedWorldException(RuntimeException failure) {
        return failure instanceof IllegalThreadStateException
                || (failure instanceof com.hypixel.hytale.logger.sentry.SkipSentryException
                && failure.getCause() instanceof IllegalThreadStateException);
    }

    private World currentWorld() {
        UUID worldUuid = this.playerRef.getWorldUuid();
        return worldUuid == null ? null : Universe.get().getWorld(worldUuid);
    }

    private boolean canRenderHud(DynamicMinimapHud expected) {
        return this.running && this.settings.enabled && expected != null
                && expected == this.hud && expected.isShown();
    }

    private boolean worldTaskAllowed(String kind) {
        // Tick handles disabling; overlay completion must clear its admission flag.
        return this.running && (kind.equals("tick") || kind.equals("overlay delivery")
                || this.canRenderHud(this.hud));
    }

    /**
     * Runs a delivery and sends its queued HUD commands immediately (they follow their asset on
     * the same channel). Waiting for the next tick to flush them added ~45 ms of display latency
     * and an extra world task per delivery.
     */
    private void executeDelivery(World world, String kind, Runnable work) {
        executeWorld(world, kind, () -> {
            work.run();
            DynamicMinimapHud current = this.hud;
            if (current != null) current.flushPendingCommands();
        });
    }

    /** Marks a world task of this kind as running (its work was taken). */
    private static final Runnable RUNNING = () -> { };

    /**
     * Queues work for the world thread, one task per kind. Latest wins: work arriving while a
     * task of the same kind is still queued replaces the older work (it would only be rejected
     * as stale), so a newer render is never discarded under world-thread lag. A delivery that
     * arrives while one is running is queued after it; a tick then is dropped as before (ticks
     * read live state, and stacking them would only add world-thread load under lag).
     */
    private void executeWorld(World world, String kind, Runnable work) {
        var token = worldPending.compute(kind, (ignored, old) -> old != null && old.world() == world
                ? old : new WorldTask(world, new java.util.concurrent.atomic.AtomicReference<>()));
        var next = token.next();
        if (!this.worldTaskAllowed(kind) || !world.isAlive()) return;
        if (kind.equals("tick")) {
            if (!next.compareAndSet(null, work)) return; // One queued or running tick at a time.
        } else if (next.getAndSet(work) != null) {
            return; // A queued task will run this newer work (a running one re-queues for it).
        }
        this.queueWorldTask(world, kind, next);
    }

    private void queueWorldTask(World world, String kind, java.util.concurrent.atomic.AtomicReference<Runnable> next) {
        try {
            world.execute(labelled(kind, () -> {
                Runnable latest = next.getAndSet(RUNNING);
                try {
                    if (latest != null && latest != RUNNING && this.worldTaskAllowed(kind) && currentWorld() == world
                            && (kind.equals("tick") || world == lastObservedWorld)) latest.run();
                } finally {
                    // Newer work arrived while this ran: run it in a fresh task (ticks are dropped).
                    if (!next.compareAndSet(RUNNING, null)) {
                        if (kind.equals("tick")) next.set(null);
                        else this.queueWorldTask(world, kind, next);
                    }
                }
            }));
        } catch (RuntimeException failure) {
            next.set(null);
            if (!isStoppedWorldException(failure)) throw failure;
        }
    }

    private final PlayerRef playerRef;
    private final UUID uuid;
    private final String slug;
    private final DynamicMinimapSetting settings;
    private volatile ScheduledFuture<?> task;
    private volatile boolean running = false;
    private volatile CompletableFuture<Void> staticAssetsReady;
    private final java.util.concurrent.atomic.AtomicBoolean staticAssetsQueued = new java.util.concurrent.atomic.AtomicBoolean();

    private void queueStaticAssets() {
        if (!this.running || !this.settings.enabled) return;
        CompletableFuture<Void> ready = this.staticAssetsReady;
        if (ready == null || ready.isDone() || !this.staticAssetsQueued.compareAndSet(false, true)) return;
        DynamicMinimapSetting snapshot = this.settings.copy();
        if (!renderAsync(() -> {
            try {
                if (!this.running || !this.settings.enabled) return;
                DynamicMinimapHud.prepareStaticAssets(snapshot);
                ready.complete(null);
            } catch (RuntimeException failure) { ready.completeExceptionally(failure); }
            finally { this.staticAssetsQueued.set(false); }
        })) this.staticAssetsQueued.set(false);
    }
    // Initial overlay pre-render state (world thread reads/writes the plain flags).
    private final java.util.concurrent.atomic.AtomicBoolean overlayPrepareQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean overlayPrepareAttempted;
    private boolean overlayPrepareFellBack;
    private volatile DynamicMinimapHud hud;
    private int builtSize = -1;
    private int builtShape = -1;
    private int builtZoom = -1;
    private int builtHudPositionX = -1;
    private int builtHudPositionY = -1;
    private boolean builtInfoPanelEnabled;
    private int builtInfoPanelPosition = -1;
    private int builtInfoPanelTextAlignment = -1;
    private int builtInterfaceScale = -1;
    private int builtTextureResolution = -1;
    private int builtRotationMode = -1;
    private boolean builtShowCoordinates;
    private boolean builtShowCompass;
    private boolean builtShowClock;
    private boolean builtShowBiome;
    /** Creature marker style the cached markers were scanned with (dots / icons). */
    private boolean builtCreatureIcons;
    private boolean builtHostileOnly;
    private int lastMapRotationBucket = -1;
    private long terrainGeneration = 0L;
    private DynamicMinimapRenderer.RotationSource rotationSource = null;
    private byte[] displayedTerrainPng = null;
    private final DynamicAssetPublisher.ImagePublicationCache terrainPublications = new DynamicAssetPublisher.ImagePublicationCache();

    public DynamicMinimapRenderer.LiveSnapshot capturePreview() {
        return this.hud == null ? null : this.hud.snapshot(this.displayedTerrainPng,
                this.lastSelfBucket < 0 ? 0.0D : this.lastSelfBucket * (Math.PI * 2 / SELF_BUCKETS));
    }

    private double bufferCenterX = Double.NaN;
    private double bufferCenterZ = Double.NaN;
    private final AtomicInteger frameIndex = new AtomicInteger();
    private final AtomicLong renderSequence = new AtomicLong();
    private final AtomicLong rotationSequence = new AtomicLong();
    private long deliveredSequence = 0L;
    private long lastTickMs = 0L;
    private long lastSignature = 0L;
    private long lastSignatureMs = 0L;
    private boolean signatureChangedPending = false;
    private BetterMapIntegration.CaveView lastCaveView;
    private long lastCaveCheckMs = 0L;
    private static final long CAVE_CHECK_MS = 500L;
    private long lastMarkerMs = 0L;
    private DynamicMinimapColours renderedColours;
    private List<DynamicMinimapMarker> cachedMarkers = new ArrayList<>();
    private final EdgeTracker edgeTracker = new EdgeTracker();
    /** Creature threat colours for this player's view (see DynamicEntityScanner.ThreatCache). */
    private final DynamicEntityScanner.ThreatCache threatCache = new DynamicEntityScanner.ThreatCache();
    /** Creature icon keys for this player's view (Creature Markers: Icons). */
    private final DynamicCreatureIcons.KeyCache creatureIconKeys = new DynamicCreatureIcons.KeyCache();
    private final MarkerOrder markerOrder = new MarkerOrder();
    private record MarkerAssetKey(Style style, String source, int rgb) {}
    /** Icons sent to this client: least recently used dropped beyond 256 (clearing all made every marker blink). */
    private final java.util.Map<MarkerAssetKey, String> markerAssets = lru(256, true);
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, DynamicAssetPublisher.PreparedAsset> preparedMarkerAssets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, Boolean> markerAssetsPending = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long markerAssetGeneration;
    /** Icons whose preparation failed, with the failure time, to avoid retry storms. */
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, Long> failedMarkerAssets = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FAILED_ICON_RETRY_MS = 30000L;
    /** Last icon path shown per creature type, used while a new colour variant is prepared. */
    private final java.util.Map<String, String> creatureIconFallback = lru(256, false);

    /** Access-ordered map dropping its least recently used entry beyond {@code limit}. */
    private static <K, V> java.util.Map<K, V> lru(int limit, boolean synchronize) {
        java.util.Map<K, V> map = new java.util.LinkedHashMap<>(64, 0.75F, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<K, V> eldest) { return size() > limit; }
        };
        // markerAssets is also read on the render pool (prepareThreatColours).
        return synchronize ? java.util.Collections.synchronizedMap(map) : map;
    }
    /** Attacking creature icons alternate between two edge colours at this interval (or slower). */
    private static final long PULSE_MS = 300L;
    private boolean pulseOn;
    private long lastPulseToggleMs;

    private void invalidateMarkerAssets() {
        ++this.markerAssetGeneration;
        this.markerAssets.clear();
        this.preparedMarkerAssets.clear();
        this.failedMarkerAssets.clear(); // New generation (teleport/colours): allow a fresh attempt.
        this.creatureIconFallback.clear(); // The client may no longer hold those images.
        // Pending jobs finish naturally; their generation check rejects stale results.
    }

    private String markerIcon(DynamicMinimapMarker marker) {
        return this.markerIcon(marker, marker.color == null ? 0xFFFFFF : marker.color.getRGB() & 0xFFFFFF);
    }

    /**
     * Creature icon edged in its threat colour. Attacking creatures alternate with a pale edge
     * (a pulse: one AssetPath swap per toggle, only for creatures attacking this player). While
     * a colour variant is still being prepared, the creature type's last shown icon is reused
     * so it never blinks out. World thread only.
     */
    private String creatureIcon(DynamicMinimapMarker marker, int attackingRgb) {
        int rgb = marker.color == null ? 0xFFFFFF : marker.color.getRGB() & 0xFFFFFF;
        String path = null;
        if (this.pulseOn && rgb == attackingRgb) path = this.markerIcon(marker, DynamicCreatureIcons.pulseColour(rgb));
        if (path == null) path = this.markerIcon(marker, rgb);
        if (path == null) return this.creatureIconFallback.get(marker.assetPath);
        this.creatureIconFallback.put(marker.assetPath, path);
        return path;
    }

    /** Resource loading, PNG tinting and hashing never block the entity/world tick. */
    private String markerIcon(DynamicMinimapMarker marker, int rgb) {
        if (!this.canRenderHud(this.hud)) return null;
        if (marker.style == Style.DOT && marker.color == null) return null;
        DynamicMinimapColours palette = this.settings.colours;
        MarkerAssetKey key = new MarkerAssetKey(marker.style, marker.assetPath, rgb);
        String ready = this.markerAssets.get(key);
        if (ready != null) return ready;
        DynamicAssetPublisher.PreparedAsset prepared = this.preparedMarkerAssets.get(key);
        if (prepared != null) {
            if (!DynamicAssetPublisher.deliver(this.playerRef, prepared)) return null;
            this.markerAssets.put(key, prepared.path());
            this.preparedMarkerAssets.remove(key, prepared);
            return prepared.path();
        }
        // An icon that could not be prepared (missing or unreadable source) is not retried
        // on every projection; it is retried after a back-off or a new asset generation.
        Long failedAt = this.failedMarkerAssets.get(key);
        long nowMs = System.currentTimeMillis();
        if (failedAt != null) {
            if (nowMs - failedAt < FAILED_ICON_RETRY_MS) return null;
            this.failedMarkerAssets.remove(key, failedAt);
        }
        if (this.markerAssetsPending.size() >= 16
                || this.markerAssetsPending.putIfAbsent(key, Boolean.TRUE) != null) return null;
        long generation = this.markerAssetGeneration;
        World sourceWorld = this.lastObservedWorld;
        DynamicMinimapHud targetHud = this.hud;
        if (!renderAsync(() -> {
            try {
                if (!this.canRenderHud(targetHud) || generation != this.markerAssetGeneration
                        || sourceWorld == null || sourceWorld != currentWorld()) return;
                DynamicAssetPublisher.PreparedAsset asset;
                switch (marker.style) {
                    case BETTERMAP -> asset = BetterMapIntegration.prepareIconAsset(
                            marker.assetPath, marker.color, this.slug);
                    case NATIVE_WORLDMAP -> asset = NativeWorldMapIntegration.prepareIconAsset(
                            marker.assetPath, marker.color, this.slug);
                    // Creature face icons are built once per creature type and shared by all players.
                    default -> asset = this.prepareCreatureAsset(marker.style, marker.assetPath, rgb, palette);
                }
                if (!this.canRenderHud(targetHud) || generation != this.markerAssetGeneration
                        || sourceWorld != currentWorld()) return;
                // Another player's request is building this creature's icon: retry on a later
                // projection (pending is cleared below), without the failure back-off.
                if (asset == DynamicCreatureIcons.BUSY) return;
                if (asset == null) {
                    // Remember the failure for this generation instead of re-queuing it each tick.
                    if (this.failedMarkerAssets.size() >= 256) this.failedMarkerAssets.clear();
                    this.failedMarkerAssets.put(key, System.currentTimeMillis());
                    return;
                }
                this.putPrepared(key, asset);
                // Creature icons and dots: also prepare the threat colours now, so a creature
                // turning hostile or attacking changes colour on the very next projection.
                if (marker.style == Style.CREATURE || marker.style == Style.DOT)
                    this.prepareThreatColours(marker, rgb, palette, generation);
            } finally { this.markerAssetsPending.remove(key); }
        })) this.markerAssetsPending.remove(key);
        return null;
    }

    /** Bound on prepared-but-unsent icons (several threat colours per creature type on screen). */
    private static final int PREPARED_LIMIT = 512;

    private void putPrepared(MarkerAssetKey key, DynamicAssetPublisher.PreparedAsset asset) {
        if (this.preparedMarkerAssets.size() >= PREPARED_LIMIT) this.preparedMarkerAssets.clear();
        this.preparedMarkerAssets.put(key, asset);
    }

    /** Creature face icon (CREATURE) or coloured dot (DOT) in {@code rgb}. Render pool only. */
    private DynamicAssetPublisher.PreparedAsset prepareCreatureAsset(Style style, String iconKey, int rgb,
                                                                     DynamicMinimapColours palette) {
        if (style == Style.CREATURE) return DynamicCreatureIcons.prepareAsset(iconKey, this.slug, rgb);
        byte[] png = DynamicMinimapRenderer.renderDot(rgb, palette);
        return DynamicAssetPublisher.prepare("UI/DapperMap/mk-" + this.slug + "-" + DapperMapUtil.hex6(rgb) + ".png", png);
    }

    /**
     * Prepares the hostile, attacking (and, for icons, attack-pulse) colours of a creature marker
     * on the server only. Nothing is sent to the player unless the creature actually changes to
     * that colour; then markerIcon delivers it immediately instead of waiting for a render.
     * The base icon is already built at this point, so each colour is one small tint and encode,
     * and creature icon colours are shared by every player. Render pool only.
     */
    private void prepareThreatColours(DynamicMinimapMarker marker, int shownRgb,
                                      DynamicMinimapColours palette, long generation) {
        int hostile = palette.rgb(DynamicMinimapColours.Key.HOSTILE);
        int attacking = palette.rgb(DynamicMinimapColours.Key.HOSTILE_ACTIVE);
        boolean icon = marker.style == Style.CREATURE;
        int count = icon ? 3 : 2;
        for (int i = 0; i < count; i++) {
            int rgb = i == 0 ? hostile : i == 1 ? attacking : DynamicCreatureIcons.pulseColour(attacking);
            if (rgb == shownRgb || generation != this.markerAssetGeneration) continue;
            MarkerAssetKey key = new MarkerAssetKey(marker.style, marker.assetPath, rgb);
            if (this.markerAssets.containsKey(key) || this.preparedMarkerAssets.containsKey(key)) continue;
            DynamicAssetPublisher.PreparedAsset asset = this.prepareCreatureAsset(marker.style, marker.assetPath, rgb, palette);
            if (asset != null && asset != DynamicCreatureIcons.BUSY && generation == this.markerAssetGeneration)
                this.putPrepared(key, asset);
        }
    }
    private List<DynamicMinimapMarker> cachedWorldMapMarkers = new ArrayList<>();
    /** Integration switches used by the last world-map scan; a change forces a rescan. */
    private boolean scannedNative, scannedBetterMap, scannedDeaths;
    private int emptyWorldMapScans = 0;
    private long lastWorldMapScanMs = 0L;
    /** Consecutive world-map scans that found nothing new; after 3 the scan slows from 2 s to 4 s. */
    private int stableWorldMapScans = 0;
    private long lastTerrainMs = 0L;
    private boolean bufferComplete = false;
    private long warmupUntilMs = 0L;
    private int lastSelfBucket = -1;
    /** Locator images already sent to this client for the current colour (skips re-hashing). */
    private final boolean[] selfArrowDelivered = new boolean[SELF_BUCKETS];
    /** Scratch array for marker projection (world thread only). */
    private final DynamicMinimapHud.Slot[] projectionSlots = new DynamicMinimapHud.Slot[DynamicMinimapHud.MARKER_SLOTS];
    /** True once every locator image for the current colour is on the client. */
    private boolean selfArrowsComplete = false;
    /** Last locator heading change; the tick runs at the faster rotation cadence while turning. */
    private volatile long lastHeadingChangeMs = 0L;
    private static final long TURNING_WINDOW_MS = 500L;
    /** Smallest heading change (2°, below the 5° locator step) that triggers an immediate tick. */
    private static final double HEADING_EPSILON = Math.toRadians(2.0D);
    /** Yaw seen by the last dispatched tick; only touched on the scheduler thread. */
    private float lastDispatchedYaw = Float.NaN;
    /** Position seen by the last dispatched tick (scheduler thread only); used to spot idle players. */
    private double lastDispatchedX = Double.NaN, lastDispatchedZ = Double.NaN;
    /** Smallest movement (blocks) since the last dispatch that counts as moving. */
    private static final double IDLE_MOVE_EPSILON = 1.0D / 16.0D;
    /**
     * Player-up only: a rotation request or sharpen is still due, so keep the fast tick cadence.
     * Written by the world thread at the end of each tick, read by the heartbeat.
     */
    private volatile boolean rotationCatchUp;
    /** Set when HUD commands are waiting (deliveries, settings refresh): dispatch at the next poll. */
    private volatile boolean flushSoon;
    /** Set by PlayerReady on an existing session: rebuild the client-side HUD in place on the next tick. */
    private volatile boolean clientResyncPending;
    /** When the last map-rotation render was requested; paces rotation by request, not delivery. */
    private long lastRotationRequestMs = 0L;
    /** Once the heading has been steady this long, a reduced frame is replaced by a sharp one. */
    private static final long ROTATION_SETTLE_MS = 350L;
    /** True while the shown player-up frame is a reduced mid-turn frame (world thread only). */
    private boolean displayedRotationReduced;
    private final ReadoutCache readouts = new ReadoutCache();
    private boolean wasAlive = true;
    // Detect discontinuous movement even when the player stays in the same world.
    private volatile World lastObservedWorld;
    private double lastObservedX = Double.NaN;
    private double lastObservedZ = Double.NaN;
    private static final double TELEPORT_DISTANCE_SQUARED = 64.0D * 64.0D;


    public DynamicMinimapSession(PlayerRef playerRef, DynamicMinimapSetting settings) {
        this.playerRef = playerRef;
        this.uuid = playerRef.getUuid();
        this.slug = DapperMapUtil.playerSlug(this.uuid);
        this.settings = settings;
    }

    public DynamicMinimapSetting settings() {
        return this.settings;
    }

    public PlayerRef playerRef() {
        return this.playerRef;
    }

    public boolean isRunning() {
        return this.running;
    }

    /** A custom page was open on the last tick, so the map's work was paused (world thread). */
    private boolean pausedForPage;
    /** The world the player just joined while its PlayerReady is still to come (world thread). */
    private World awaitingReadyWorld;

    /**
     * The client is (re)joining a world (PlayerReadyEvent fires on every world join, and after
     * Universe.resetPlayer, which can wipe custom UI even in the same world). Instead of a full
     * stop/start, the next tick rebuilds the HUD and re-sends client assets in place, keeping the
     * session's server-side caches. Any thread.
     */
    public void resyncClient() {
        this.clientResyncPending = true;
        this.flushSoon = true;
    }

    /** Live settings changed: run a tick at the next heartbeat poll so the change shows promptly. */
    public void requestPromptTick() {
        this.flushSoon = true;
    }

    /**
     * Drops the current terrain buffer so the next tick renders a fresh, player-centred one, and
     * makes any render already in flight stale. Shared by teleports, world changes and setting
     * changes; each caller adds what else its change invalidates.
     */
    private void forceTerrainRender() {
        this.bufferCenterX = Double.NaN;
        this.bufferCenterZ = Double.NaN;
        this.bufferComplete = false;
        this.lastTerrainMs = 0L;
        ++this.terrainGeneration;
    }

    public void start() {
        if (!this.running) {
            this.running = true;
            this.staticAssetsReady = new CompletableFuture<>();
            this.queueStaticAssets();
            this.scheduleNextTick(500L);
        }

    }

    public void stop() {
        this.stop(true);
    }

    public void stop(boolean removeHud) {
        this.running = false;
        if (this.task != null) {
            this.task.cancel(false);
            this.task = null;
        }

        if (removeHud && this.hud != null && this.hud.isShown()) {
            try { this.hud.removeFromClient(); }
            catch (RuntimeException failure) { if (!isStoppedWorldException(failure)) throw failure; }
        }

        if (this.hud != null) this.hud.detach();
        this.hud = null;
        this.rotationSource = null;
        this.displayedTerrainPng = null;
        this.terrainPublications.clear();
        this.lastObservedWorld = null;
        this.lastCaveView = null;
        this.markerOrder.clear();
        this.edgeTracker.clear();
        this.worldPending.clear();
        this.invalidateMarkerAssets();
        // Delivered-image records are kept: the client still holds those images on this
        // connection, so turning the minimap off and on again re-sends nothing. They are cleared
        // on disconnect and world join (DapperMapSessionManager) and on teleport (renderTick).
        java.util.Arrays.fill(this.selfArrowDelivered, false);
        this.selfArrowsComplete = false;
    }

    /**
     * Self-rescheduling tick. Previously a fixed 25 ms task woke for every player and
     * discarded most wake-ups; now each player wakes only at its update-speed interval,
     * which is re-read every tick so speed/rotation changes apply immediately.
     */
    private void scheduleNextTick(long delayMs) {
        if (!this.running) return;
        try {
            this.task = HEARTBEAT.schedule(this::tickSafe, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // DapperMap is shutting down; the session ends with it.
        }
    }

    /**
     * The fast rotation cadence is used only while the player is turning, or (player-up) while a
     * rotation request or reduced-to-sharp swap is still due. Otherwise the cheaper pan cadence,
     * and the marker cadence for a player who is neither moving nor turning.
     */
    private long tickIntervalMs(boolean idle) {
        boolean turning = System.currentTimeMillis() - this.lastHeadingChangeMs < TURNING_WINDOW_MS;
        if (turning || this.rotationCatchUp) {
            return Math.min(this.settings.panIntervalMs(), this.rotationPollIntervalMs());
        }
        long pan = this.settings.panIntervalMs();
        return idle ? Math.max(pan, this.settings.markerIntervalMs()) : pan;
    }

    /**
     * Scheduler-thread heartbeat. It wakes at the fast rotation cadence but only queues the
     * world-thread tick when the heading has moved or the normal update interval is due.
     * The heading check is one read of PlayerRef's cached transform (refreshed by the server
     * every tick), so starting to turn is noticed within one poll at almost no idle cost.
     */
    private void tickSafe() {
        try {
            long now = System.currentTimeMillis();
            float yaw = this.peekYaw();
            boolean headingMoved = !Float.isNaN(yaw) && (Float.isNaN(this.lastDispatchedYaw)
                    || Math.abs(Math.IEEEremainder(yaw - this.lastDispatchedYaw, Math.PI * 2.0D)) >= HEADING_EPSILON);
            // Player-up: a heading move starts the turning window, so pending rotations keep the
            // fast cadence (north-up sets this from the locator bucket instead).
            if (headingMoved && this.settings.rotationMode == 1) this.lastHeadingChangeMs = now;
            // Cheap peek at the cached position: an idle player is ticked at the marker cadence.
            double px = Double.NaN, pz = Double.NaN;
            try {
                var position = this.playerRef.getTransform().getPosition();
                px = position.x();
                pz = position.z();
            } catch (RuntimeException ignored) {
                // Unavailable: treat the player as moving.
            }
            boolean idle = !Double.isNaN(px) && Math.abs(px - this.lastDispatchedX) < IDLE_MOVE_EPSILON
                    && Math.abs(pz - this.lastDispatchedZ) < IDLE_MOVE_EPSILON;
            boolean flush = this.flushSoon;
            if (headingMoved || flush || now - this.lastTickMs >= this.tickIntervalMs(idle)) {
                this.flushSoon = false;
                this.lastTickMs = now;
                this.lastDispatchedYaw = yaw;
                this.lastDispatchedX = px;
                this.lastDispatchedZ = pz;
                this.tick();
            }
        } catch (Throwable failure) {
            // Never let one bad tick stop the heartbeat; the next tick retries.
            DapperMapUtil.logFailure("minimap heartbeat", failure);
        } finally {
            // stop() clears running first, so a stopped session never reschedules itself.
            this.scheduleNextTick(Math.min(this.settings.panIntervalMs(), this.rotationPollIntervalMs()));
        }
    }

    /** Racy but benign read of the server's cached player yaw (radians); NaN when unavailable. */
    private float peekYaw() {
        try {
            // Head (camera) yaw, matching the readout and locator; see renderTick.
            var rotation = this.playerRef.getHeadRotation();
            return rotation == null ? Float.NaN : rotation.yaw();
        } catch (RuntimeException ignored) {
            return Float.NaN;
        }
    }

    /** Give Hytale's slow-task warning an actionable name for each world callback. */
    private static Runnable labelled(String name, Runnable action) {
        return new Runnable() {
            @Override public void run() { action.run(); }
            @Override public String toString() { return "DapperMap " + name; }
        };
    }

    private void tick() {
        if (this.running) {
            UUID worldUuid = this.playerRef.getWorldUuid();
            World world = worldUuid == null ? null : Universe.get().getWorld(worldUuid);
            if (world != null) {
                executeWorld(world, "tick", () -> {
                    Ref<EntityStore> ref = world.getEntityRef(this.uuid);
                    if (ref != null && ref.isValid()) this.renderTick(world, ref);
                });
            }
        }

    }

    private void renderTick(World world, Ref<EntityStore> ref) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                return;
            }

            Player player = store.getComponent(ref, Player.getComponentType());
            Vector3d position = transform.getPosition();
            double x = position.x();
            double y = position.y();
            double z = position.z();
            // PlayerReady on a live session (world join / resetPlayer): rebuild the client HUD in place.
            boolean resync = this.clientResyncPending;
            this.clientResyncPending = false;
            boolean worldSwitch = lastObservedWorld != null && lastObservedWorld != world;
            // The PlayerReady that belongs to a world join (it can arrive a tick before or after
            // the tick that sees the new world) keeps the client's images. Any other PlayerReady
            // is Universe.resetPlayer, which can wipe them: that one still resets everything.
            boolean joinReady = resync && (worldSwitch || world == this.awaitingReadyWorld);
            if (resync) this.awaitingReadyWorld = null;
            else if (worldSwitch) this.awaitingReadyWorld = world;
            boolean clientWiped = resync && !joinReady;
            boolean worldChanged = worldSwitch || resync;
            boolean teleported = lastObservedWorld != null
                    && (worldChanged
                    || (x - lastObservedX) * (x - lastObservedX) + (z - lastObservedZ) * (z - lastObservedZ)
                    > TELEPORT_DISTANCE_SQUARED);
            lastObservedWorld = world;
            lastObservedX = x;
            lastObservedZ = z;
            if (teleported) {
                // Any teleport: the map recentres on the new spot (fresh terrain, edge positions
                // and marker scan). Renders still in flight for the old spot are discarded.
                this.edgeTracker.clear();
                this.forceTerrainRender();
                this.lastSignatureMs = 0L;
                this.deliveredSequence = this.renderSequence.incrementAndGet();
                this.rotationSequence.incrementAndGet();
                if (worldChanged) {
                    // The HUD is rebuilt for the new world (or after a resync). A same-world
                    // teleport keeps the client's images (verified in 0.6.8 and 0.7.0), and a
                    // world change is treated the same way: images already sent (masks, frames,
                    // locator, marker and creature icons) are not sent again, saving ~100-250 KB
                    // and a common-asset rebuild per world change. Only Universe.resetPlayer
                    // (a PlayerReady without a world join) forgets them and re-sends everything.
                    if (clientWiped) {
                        this.invalidateMarkerAssets();
                        DynamicAssetPublisher.clear(this.uuid);
                        java.util.Arrays.fill(this.selfArrowDelivered, false);
                        this.selfArrowsComplete = false;
                    }
                    this.terrainPublications.clear();
                    if (this.hud != null) this.hud.invalidateVisualAssets();
                    this.lastSelfBucket = -1;
                    if (this.hud != null) this.hud.invalidateMarkerSlots();
                    // Creature references from the old world would keep its entity store alive.
                    this.threatCache.clear();
                    this.creatureIconKeys.clear();
                    // The BetterMap cave-image cache is shared by every player: leave it (it is
                    // bounded, expires, and dead worlds are pruned) instead of flushing it here.
                    this.worldPending.clear();
                    if (this.hud != null) this.hud.detach();
                    this.hud = null;
                    this.lastCaveView = null;
                    this.lastCaveCheckMs = 0L; // Re-read cave mode immediately in the new world.
                    this.rotationSource = null;
                    this.displayedTerrainPng = null;
                    this.terrainPublications.clear();
                    this.forceTerrainRender();
                    this.rotationSequence.incrementAndGet();
                    this.cachedMarkers = new ArrayList<>();
                    this.cachedWorldMapMarkers = List.of();
                    this.emptyWorldMapScans = 0;
                }
                this.lastMarkerMs = 0L;
                this.lastWorldMapScanMs = 0L;
                this.stableWorldMapScans = 0; // Teleports (incl. respawn) bring new markers: scan fast again.
            }
            // Use the head (camera) yaw, as Hytale's compass and world map do. The body yaw
            // only follows once the view turns ~45° past it, which made the locator start
            // late and point up to 45° away from the real view direction.
            HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
            Rotation3f rotation = head != null ? head.getRotation() : transform.getRotation();
            double yaw = rotation == null ? Double.NaN : (double)rotation.yaw();
            if (!this.settings.enabled) {
                if (this.hud != null && this.hud.isShown()) {
                    this.hud.removeFromClient();
                }

                return;
            }

            this.ensureHud(world);
            if (this.hud == null || !this.hud.isShown()) {
                return;
            }

            if (this.settings.textureResolution != this.builtTextureResolution) {
                this.builtTextureResolution = this.settings.textureResolution;
                this.hud.setTextureResolution(this.settings.textureResolution);

                // Resolution changes only the raster assets. Keep the mounted
                // HUD tree and invalidate the terrain image for a clean rerender.
                // Force a genuinely fresh, player-centred terrain render. Keeping the
                // previous buffer centre can leave needsTerrain false until the next
                // signature/recenter event, making the resolution control appear inert.
                this.forceTerrainRender();
                this.warmupUntilMs = 0L;
                this.lastSignature = 0L;
                this.lastSignatureMs = 0L;
                this.rotationSource = null;
                this.displayedTerrainPng = null;
                this.terrainPublications.clear();
                this.rotationSequence.incrementAndGet();
            }

            if (this.settings.sizePixels != this.builtSize) {
                this.builtSize = this.settings.sizePixels;
                this.hud.setMapSize(this.settings.sizePixels);

                // Logical size changes pixels-per-block and buffer dimensions.
                // Invalidate the old terrain buffer and let the normal render
                // path create a fresh one without recreating the Custom HUD.
                this.forceTerrainRender();
                this.warmupUntilMs = 0L;
                this.lastSignature = 0L;
                this.lastSignatureMs = 0L;
                this.lastMarkerMs = 0L;
                this.rotationSource = null;
                this.displayedTerrainPng = null;
                this.terrainPublications.clear();
            }

            if (this.settings.interfaceScalePercent != this.builtInterfaceScale) {
                this.builtInterfaceScale = this.settings.interfaceScalePercent;
                this.hud.setInterfaceScale(this.settings.interfaceScalePercent);
            }

            if (this.settings.infoPanelEnabled != this.builtInfoPanelEnabled) {
                this.builtInfoPanelEnabled = this.settings.infoPanelEnabled;
                this.hud.setInfoPanelEnabled(this.settings.infoPanelEnabled);
            }

            if (this.settings.hudPositionX != this.builtHudPositionX
                    || this.settings.hudPositionY != this.builtHudPositionY
                    || this.settings.infoPanelPosition != this.builtInfoPanelPosition) {
                this.builtHudPositionX = this.settings.hudPositionX;
                this.builtHudPositionY = this.settings.hudPositionY;
                this.builtInfoPanelEnabled = this.settings.infoPanelEnabled;
                this.builtInfoPanelPosition = this.settings.infoPanelPosition;
                this.hud.setLayoutPosition(
                        this.settings.hudPositionX,
                        this.settings.hudPositionY,
                        this.settings.infoPanelPosition
                );
            }

            if (this.settings.infoPanelTextAlignment != this.builtInfoPanelTextAlignment) {
                this.builtInfoPanelTextAlignment = this.settings.infoPanelTextAlignment;
                this.hud.setInfoTextAlignment(this.settings.infoPanelTextAlignment);
            }

            if (this.settings.showCoordinates != this.builtShowCoordinates
                    || this.settings.showCompass != this.builtShowCompass
                    || this.settings.showClock != this.builtShowClock
                    || this.settings.showBiome != this.builtShowBiome) {
                this.builtShowCoordinates = this.settings.showCoordinates;
                this.builtShowCompass = this.settings.showCompass;
                this.builtShowClock = this.settings.showClock;
                this.builtShowBiome = this.settings.showBiome;
                this.hud.setInfoVisibility(
                        this.settings.showCoordinates,
                        this.settings.showCompass,
                        this.settings.showClock,
                        this.settings.showBiome
                );
            }

            long now = System.currentTimeMillis();
            // BetterMap cave state is read reflectively; poll it at most every
            // CAVE_CHECK_MS rather than on every tick (up to 40 times a second).
            BetterMapIntegration.CaveView caveView;
            if (!this.settings.betterMapEnabled || !this.settings.betterMapCaveModeEnabled) {
                caveView = null;
            } else if (now - this.lastCaveCheckMs >= CAVE_CHECK_MS) {
                this.lastCaveCheckMs = now;
                caveView = BetterMapIntegration.caveView(player, world);
            } else {
                caveView = this.lastCaveView;
            }
            boolean caveChanged = !java.util.Objects.equals(caveView, this.lastCaveView);
            if (caveChanged) {
                this.lastCaveView = caveView;
                this.lastSignatureMs = 0L;
                this.bufferComplete = false;
            }
            if (!this.settings.colours.equals(this.renderedColours)) {
                this.renderedColours = this.settings.colours;
                java.util.Arrays.fill(this.selfArrowDelivered, false);
                this.selfArrowsComplete = false;
                this.invalidateMarkerAssets();
                this.lastSelfBucket = -1;
                this.lastMarkerMs = 0L;
                this.updateRotatingFrame(Math.max(0, this.lastMapRotationBucket));
                this.hud.setColours(this.renderedColours);
            }
            // One overlay publish and one layout rebuild for all of this tick's setting changes.
            this.hud.applyPendingLayout();

            // Switching creature dots/icons or the hostile-only filter rescans markers straight away.
            if (this.settings.creatureIcons != this.builtCreatureIcons
                    || this.settings.hostileCreaturesOnly != this.builtHostileOnly) {
                this.builtCreatureIcons = this.settings.creatureIcons;
                this.builtHostileOnly = this.settings.hostileCreaturesOnly;
                this.lastMarkerMs = 0L;
            }


            // The client hides every custom HUD while a page is open (DapperMap's settings,
            // other mods' pages and Hytale's own were all checked). Pause the map's work then:
            // no creature scan, marker projection, terrain or rotation render, pan or readouts.
            // Settings changes above still apply, so closing a page shows them straight away.
            if (player != null && player.getPageManager().getCustomPage() != null) {
                this.pausedForPage = true;
                this.rotationCatchUp = false;
                this.hud.flushPendingVisuals(this.settings.updateSpeed);
                return;
            }
            if (this.pausedForPage) {
                // Page closed: refresh markers, terrain check and heading at once.
                this.pausedForPage = false;
                this.lastMarkerMs = 0L;
                this.lastSignatureMs = 0L;
                this.lastRotationRequestMs = 0L;
            }

            if (this.settings.rotationMode != this.builtRotationMode) {
                this.builtRotationMode = this.settings.rotationMode;
                // Changing mode also changes raster quality. Force new terrain;
                // the previous decoded source must not survive the transition.
                this.rotationSource = null;
                this.forceTerrainRender();
                this.lastSignatureMs = 0L;
                this.lastMapRotationBucket = -1;
                this.lastSelfBucket = -1;
                this.lastMarkerMs = 0L;
                this.cachedMarkers = new ArrayList<>();
                this.rotationSequence.incrementAndGet();
            }

            int mapRotationBucket = this.settings.rotationMode == 1
                    ? DynamicMinimapRenderer.rotationBucketForYaw(yaw)
                    : 0;

            if (mapRotationBucket != this.lastMapRotationBucket
                    && now - this.lastRotationRequestMs >= this.rotationIntervalMs()) {
                // Pace from the previous request so render time is not added on top of the
                // interval; only one rotation render is ever in flight (coalesced). While turning
                // a reduced frame is sent (about 40% of the bytes); a sharp one follows once the
                // heading has been steady for ROTATION_SETTLE_MS.
                if (this.scheduleRotationOnly(world, yaw, mapRotationBucket, true)) {
                    this.lastRotationRequestMs = now;
                }

                // The cached markers are projected again when the rotated terrain arrives.
            } else if (this.displayedRotationReduced && mapRotationBucket == this.lastMapRotationBucket
                    && now - this.lastRotationRequestMs >= ROTATION_SETTLE_MS) {
                // The turn has ended on a reduced frame: replace it with a sharp one.
                if (this.scheduleRotationOnly(world, yaw, mapRotationBucket, false)) {
                    this.lastRotationRequestMs = now;
                }
            }

            this.updateSelfArrow(yaw);
            double pixelsPerBlock = DynamicMinimapRenderer.pixelsPerBlock(this.settings);
            int bufferPixels = this.hud.bufferPixels();
            int mapCenter = this.settings.sizePixels / 2;
            double panMargin = (double)DynamicMinimapRenderer.panMarginPixels(this.settings);
            boolean signatureChanged = this.signatureChangedPending;
            // No probe while a redraw is already due or rendering: it could not add anything, and a
            // result arriving during a terrain render is discarded anyway.
            if (!Double.isNaN(this.bufferCenterX) && !signatureChanged && !this.renderBusy("terrain")
                    && now - this.lastSignatureMs > this.settings.signatureIntervalMs()) {
                this.lastSignatureMs = now;
                this.scheduleSignatureCheck(world, this.bufferCenterX, this.bufferCenterZ, caveView);
            }

            boolean noBuffer = Double.isNaN(this.bufferCenterX);
            double recenterFraction = this.settings.rotationMode == 1 ? 0.35D : 0.8D;
            boolean needsRecenter = !noBuffer
                    && (Math.abs(x - this.bufferCenterX) * pixelsPerBlock
                    > panMargin * recenterFraction
                    || Math.abs(z - this.bufferCenterZ) * pixelsPerBlock
                    > panMargin * recenterFraction);
            boolean warmupRetry = !this.bufferComplete && now < this.warmupUntilMs && !noBuffer && now - this.lastTerrainMs >= 1000L;
            boolean needsTerrain = noBuffer || needsRecenter || signatureChanged || warmupRetry || caveChanged;
            if (needsTerrain && now - this.lastTerrainMs >= this.settings.terrainIntervalMs()) {
                if (noBuffer || needsRecenter) {
                    this.warmupUntilMs = now + 15000L;
                    this.bufferComplete = false;
                }

                if (this.scheduleTerrainRender(world, x, z, yaw, caveView)) {
                    this.lastTerrainMs = now;
                    this.signatureChangedPending = false;
                } else if (!this.renderBusy("terrain")) {
                    // Render pool full: retry in about 250 ms rather than on every tick.
                    this.lastTerrainMs = now - this.settings.terrainIntervalMs() + 250L;
                }
            } else if (!noBuffer) {
                int[] pan = panOffset(x, z, this.bufferCenterX, this.bufferCenterZ, pixelsPerBlock,
                        bufferPixels, mapCenter, this.acceptedMapAngle(yaw));
                this.hud.pan(pan[0], pan[1]);
            }

            if (!Double.isNaN(this.bufferCenterX)
                    && now - this.lastMarkerMs >= this.settings.markerIntervalMs()) {
                this.lastMarkerMs = now;
                this.scanMarkers(store, ref, position, world, player);
                this.projectCachedMarkers(pixelsPerBlock, bufferPixels, yaw);
            }

            boolean panel = this.settings.infoPanelShown();
            String coords = panel && this.settings.showCoordinates ? this.readouts.coords(x, y, z) : "";
            String compass = panel && this.settings.showCompass ? this.readouts.compass(yaw) : "";
            String clock = panel && this.settings.showClock ? clockText(world, this.settings.clock24Hour, this.readouts) : "";
            String biome = panel && this.settings.showBiome ? this.readouts.biome(rawBiome(player)) : "";
            this.hud.setStatus(coords, compass, clock, biome);
            this.hud.flushPendingVisuals(this.settings.updateSpeed);
            // Player-up: keep the fast cadence while a rotation or a sharpen is still due.
            this.rotationCatchUp = this.settings.rotationMode == 1
                    && (mapRotationBucket != this.lastMapRotationBucket || this.displayedRotationReduced
                    || this.renderBusy("rotation"));
        } catch (Throwable failure) {
            // A failed tick only skips this update; the next tick starts afresh.
            DapperMapUtil.logFailure("minimap tick", failure);
        }
    }

    /**
     * Heading-only updates are intentionally faster than terrain stitching.
     * This keeps player-up rotation readable without increasing expensive world
     * map stitching to the same frequency.
     */
    /**
     * Terrain paths include raster resolution so the client never resolves a
     * newly rendered PNG through one of the old-resolution rotating cache keys.
     * We still keep the bounded 16-slot rotation within each resolution.
     */
    private String terrainAssetPath(int textureResolution, int frame) {
        int resolution = Math.max(256, Math.min(1024, textureResolution));
        return "UI/DapperMap/terrain-" + this.slug + "-r"
                + resolution + "-" + (frame & 15) + ".png";
    }

    /**
     * Compass-frame path paired with a terrain path. The rotating slot number must stay
     * at the end ("...-compass-7.png") because ImagePublicationCache rotates slots by
     * that trailing number; "...-7-compass.png" had none and could throw on reuse.
     */
    /**
     * Player-up compass frame path: one fixed name per heading for this frame artwork (shape,
     * size, resolution, info panel, colours). The publisher skips images the client already
     * holds under the same name, so after one full turn the frames cost no more data.
     */
    private String headingFramePath(DynamicMinimapSetting settings, int rotationBucket) {
        return "UI/DapperMap/compass-" + this.slug + "-" + (settings.isCircle() ? "c" : "s")
                + settings.sizePixels + "-r" + settings.textureResolution
                + (settings.infoPanelShown() ? "p" : "n") + "-" + settings.colours.frameKey()
                + "-" + rotationBucket + ".png";
    }

    private static String compassFramePath(String terrainPath) {
        int slot = terrainPath.lastIndexOf('-');
        return terrainPath.substring(0, slot) + "-compass" + terrainPath.substring(slot);
    }

    private long rotationPollIntervalMs() {
        return switch (this.settings.updateSpeed) {
            case 0 -> 25L;   // 40 Hz (scheduler ceiling)
            case 1 -> 40L;   // 25 Hz
            case 3 -> 100L;  // 10 Hz
            case 2 -> 60L;   // ~17 Hz
            default -> 60L;
        };
    }

    /** Limit network PNG/texture uploads independently of cheap position polling. */
    private long rotationIntervalMs() {
        return switch (this.settings.updateSpeed) {
            case 0 -> 100L;  // 10 Hz
            case 1 -> 125L;  // 8 Hz
            case 3 -> 300L;  // about 3 Hz
            default -> 200L; // 5 Hz
        };
    }

    private void ensureHud(World world) {
        if (this.hud != null
                && this.hud.isShown()) {
            if (this.settings.shape != this.builtShape) {
                this.builtShape = this.settings.shape;
                this.hud.setShape(this.settings.shape);
                // Shape affects edge-clamping of markers, so force a fresh marker
                // projection immediately while keeping the HUD root mounted.
                this.lastMarkerMs = 0L;
            }

            if (this.settings.zoomBlocks != this.builtZoom) {
                this.builtZoom = this.settings.zoomBlocks;
                this.bufferCenterX = Double.NaN;
                // Discard renders still in flight at the old zoom (they would show the wrong scale).
                ++this.terrainGeneration;
                this.rotationSequence.incrementAndGet();
                this.rotationSource = null;
            }
        } else {
            // Prepare PNG masks off the world thread before sending the initial HUD.
            // Waiting here would itself cause a slow world task.
            if (this.staticAssetsReady != null && !this.staticAssetsReady.isDone()) {
                this.queueStaticAssets();
                return;
            }
            if (this.hud != null && this.hud.isShown()) {
                this.hud.removeFromClient();
            }

            // One consistent snapshot builds the HUD and records what it was built with, so a
            // settings refresh landing mid-build is picked up on the next tick, never lost.
            DynamicMinimapSetting snap = this.settings.copy();
            // Render the frame/bezel overlay on the render pool first, so building the HUD
            // never rasterises it on the world thread. One failed/rejected attempt falls
            // through to the inline render in deliverStaticAssets rather than blocking forever.
            if (!this.overlayPrepareFellBack && !DynamicMinimapHud.initialOverlayReady(snap)) {
                if (this.overlayPrepareQueued.compareAndSet(false, true)) {
                    if (this.overlayPrepareAttempted) {
                        this.overlayPrepareQueued.set(false);
                        this.overlayPrepareFellBack = true;
                    } else {
                        // Only an accepted job counts as the attempt: a saturated pool is retried
                        // next tick instead of falling back to drawing on the world thread.
                        if (renderAsync(() -> {
                            try { DynamicMinimapHud.prepareInitialOverlay(snap); }
                            catch (RuntimeException ignored) { }
                            finally { this.overlayPrepareQueued.set(false); }
                        })) this.overlayPrepareAttempted = true;
                        else this.overlayPrepareQueued.set(false);
                        return;
                    }
                } else {
                    return;
                }
            }
            this.overlayPrepareAttempted = false;
            this.overlayPrepareFellBack = false;
            this.hud = new DynamicMinimapHud(this.playerRef, snap, work -> executeDelivery(world, "overlay delivery", work),
                    () -> this.running && this.settings.enabled);
            this.builtSize = snap.sizePixels;
            this.builtShape = snap.shape;
            this.builtZoom = snap.zoomBlocks;
            this.builtHudPositionX = snap.hudPositionX;
            this.builtHudPositionY = snap.hudPositionY;
            this.builtInfoPanelEnabled = snap.infoPanelEnabled;
            this.builtInfoPanelPosition = snap.infoPanelPosition;
            this.builtInfoPanelTextAlignment = snap.infoPanelTextAlignment;
            this.builtInterfaceScale = snap.interfaceScalePercent;
            this.builtTextureResolution = snap.textureResolution;
            this.builtRotationMode = -1;
            this.lastMapRotationBucket = -1;
            this.rotationSource = null;
            this.displayedTerrainPng = null;
            this.terrainPublications.clear();
            this.hud.deliverStaticAssets();
            this.hud.show();
            this.renderedColours = this.settings.colours;
            this.bufferCenterX = Double.NaN;
            this.bufferCenterZ = Double.NaN;
            this.lastTerrainMs = 0L;
            this.lastSelfBucket = -1;
            this.readouts.clear();
        }

    }

    private void updateRotatingFrame(int rotationBucket) {
        if (this.hud == null || !this.hud.isShown()) {
            return;
        }

        int bucket = this.settings.rotationMode == 1 ? rotationBucket : 0;
        double angle = DynamicMinimapRenderer.bucketAngle(bucket);
        this.hud.setFrameAngle(angle);
    }

    /**
     * Points the locator at the player's heading. All headings for the current colour are
     * pre-rendered once on the render pool (shared by every player with that colour), so a
     * heading change is an immediate asset swap in the same tick: no per-heading render,
     * no world-thread round trip, and each image is uploaded to a client at most once.
     */
    private void updateSelfArrow(double yaw) {
        if (Double.isNaN(yaw) || this.hud == null) return;
        // Player-up mode: the world rotates, so the locator always points up.
        int bucket = this.settings.rotationMode == 1 ? 0 : DynamicMinimapRenderer.selfArrowBucket(yaw);
        if (bucket == this.lastSelfBucket) {
            // Idle heading: trickle the remaining locator images to the client (two per tick)
            // so a first turn to a new heading never waits for an upload.
            if (this.settings.rotationMode != 1) this.presendSelfArrows(2);
            return;
        }
        this.lastHeadingChangeMs = System.currentTimeMillis();
        DynamicMinimapColours colours = this.settings.colours;
        byte[][] arrows = DynamicMinimapRenderer.cachedSelfArrows(colours);
        if (arrows == null) {
            // First use of this colour: build the set off-thread; a later tick applies it.
            submitRender("self", () -> DynamicMinimapRenderer.selfArrows(colours));
            return;
        }
        String path = "UI/DapperMap/self-" + this.slug + "-" + colours.arrowKey() + "-" + bucket + ".png";
        if (!this.selfArrowDelivered[bucket]) {
            if (!DynamicAssetPublisher.deliverShared(this.playerRef, path, arrows[bucket])) return;
            this.selfArrowDelivered[bucket] = true;
        }
        this.lastSelfBucket = bucket;
        this.hud.setSelf(path);
    }

    /** Sends up to {@code budget} not-yet-delivered locator images for the current colour. */
    private void presendSelfArrows(int budget) {
        if (this.selfArrowsComplete) return;
        DynamicMinimapColours colours = this.settings.colours;
        byte[][] arrows = DynamicMinimapRenderer.cachedSelfArrows(colours);
        if (arrows == null) return;
        for (int bucket = 0; bucket < SELF_BUCKETS && budget > 0; bucket++) {
            if (this.selfArrowDelivered[bucket]) continue;
            String path = "UI/DapperMap/self-" + this.slug + "-" + colours.arrowKey() + "-" + bucket + ".png";
            if (!DynamicAssetPublisher.deliverShared(this.playerRef, path, arrows[bucket])) return;
            this.selfArrowDelivered[bucket] = true;
            budget--;
        }
        if (budget > 0) this.selfArrowsComplete = true; // Loop finished with budget left: all sent.
    }

    /** Probe map-image changes on the render pool so cave lookups and image hashing
     * do not hold up world ticks. Only the latest matching buffer can request a redraw. */
    private void scheduleSignatureCheck(World world, double centerX, double centerZ,
                                        BetterMapIntegration.CaveView caveView) {
        if (this.renderBusy("signature")) return; // Would be dropped anyway: skip the copy.
        DynamicMinimapSetting probeSettings = this.settings.copy();
        DynamicMinimapHud probeHud = this.hud;
        long generation = this.terrainGeneration;
        submitRender("signature", () -> {
            long signature;
            try {
                signature = DynamicMinimapRenderer.terrainSignature(
                        world.getWorldMapManager(), centerX, centerZ, probeSettings, caveView);
            } catch (Throwable failure) {
                DapperMapUtil.logFailure("terrain change check", failure);
                signature = 0L;
            }
            if (!this.canRenderHud(probeHud)) return;
            long result = signature;
            executeWorld(world, "signature result", () -> {
                if (!this.canRenderHud(probeHud)
                        || generation != this.terrainGeneration
                        || Double.compare(centerX, this.bufferCenterX) != 0
                        || Double.compare(centerZ, this.bufferCenterZ) != 0
                        || !java.util.Objects.equals(caveView, this.lastCaveView)) return;
                if (result != 0L && result != this.lastSignature) {
                    this.lastSignature = result;
                    this.signatureChangedPending = true;
                }
            });
        });
    }

    private boolean scheduleTerrainRender(
            World world,
            double centerX,
            double centerZ,
            double yaw,
            BetterMapIntegration.CaveView caveView
    ) {
        if (this.renderBusy("terrain")) return false; // Would be dropped anyway: skip the copy.
        long sequence = this.renderSequence.incrementAndGet();
        DynamicMinimapSetting renderSettings = this.settings.copy();
        DynamicMinimapHud renderHud = this.hud;
        int rotationBucket = renderSettings.rotationMode == 1
                ? DynamicMinimapRenderer.rotationBucketForYaw(yaw)
                : 0;
        double mapAngle = DynamicMinimapRenderer.bucketAngle(rotationBucket);

        return submitRender("terrain", () -> {
            if (!this.canRenderHud(renderHud)) return;

            boolean playerUp = renderSettings.rotationMode == 1;
            // Player-up maps rotate the stitched image directly, so no north-up PNG is encoded
            // (and decoded again) unless the player is facing exactly north.
            DynamicMinimapRenderer.Terrain terrain =
                    DynamicMinimapRenderer.renderTerrain(
                            world.getWorldMapManager(),
                            centerX,
                            centerZ,
                            renderSettings,
                            caveView,
                            !playerUp
                    );

            if (terrain.present == 0) {
                return;
            }

            DynamicMinimapRenderer.RotationSource source = playerUp
                    ? DynamicMinimapRenderer.prepareRotationSource(terrain.image, renderSettings) : null;
            byte[] displayPng = playerUp
                    ? DynamicMinimapRenderer.rotateTerrain(source, mapAngle)
                    : terrain.png;

            if (displayPng == null || displayPng.length == 0) {
                return;
            }

            if (!this.canRenderHud(renderHud)) return;
            DynamicAssetPublisher.PreparedAsset prepared = DynamicAssetPublisher.prepare(
                    this.terrainAssetPath(renderSettings.textureResolution, this.frameIndex.incrementAndGet()), displayPng);
            if (source != null) source.rememberFrame(rotationBucket, prepared); // Seed the rotation cache.
            byte[] framePng = DynamicMinimapRenderer.renderNativeHudFrame(renderSettings, renderSettings.sizePixels, mapAngle);
            if (framePng == null) return;
            DynamicAssetPublisher.PreparedAsset preparedFrame = DynamicAssetPublisher.prepareShared( // Cached frame bytes: remembered hash.
                    playerUp ? this.headingFramePath(renderSettings, rotationBucket)
                            : compassFramePath(prepared.path()), framePng);
            // Change signature of exactly the chunk images drawn, hashed while stitching.
            long renderSignature = terrain.signature;
            executeDelivery(world, "terrain delivery", () -> {
                if (!this.canRenderHud(renderHud)
                        || !java.util.Objects.equals(caveView, this.lastCaveView)
                        || sequence <= this.deliveredSequence
                        || this.settings.textureResolution != renderSettings.textureResolution
                        || this.settings.zoomBlocks != renderSettings.zoomBlocks
                        || this.settings.rotationMode != renderSettings.rotationMode
                        || this.settings.sizePixels != renderSettings.sizePixels
                        || this.settings.shape != renderSettings.shape
                        || this.settings.infoPanelShown() != renderSettings.infoPanelShown() // Picks the frame variant.
                        || !this.settings.colours.equals(renderSettings.colours)) {
                    return;
                }

                String path = this.terrainPublications.pathFor(prepared.hash());
                if (path == null) {
                    DynamicAssetPublisher.PreparedAsset publication = prepared.withPath(
                            this.terrainPublications.nonActivePath(prepared.path()));
                    if (!DynamicAssetPublisher.deliver(this.playerRef, publication)) return;
                    path = publication.path();
                    this.terrainPublications.remember(path, publication.hash());
                }

                if (!renderHud.acceptTerrainFrame(mapAngle, preparedFrame)) return;
                this.deliveredSequence = sequence;
                this.rotationSource = source;
                ++this.terrainGeneration;

                this.displayedTerrainPng = displayPng;
                this.bufferComplete = terrain.complete();
                this.bufferCenterX = centerX;
                this.bufferCenterZ = centerZ;
                this.lastSignature = renderSignature;
                this.lastMapRotationBucket = rotationBucket;
                this.displayedRotationReduced = false; // Terrain renders are always full sharpness.

                double pixelsPerBlock =
                        DynamicMinimapRenderer.pixelsPerBlock(renderSettings);
                int bufferPixels = this.hud.bufferPixels();
                int mapCenter = renderSettings.sizePixels / 2;
                int[] pan = this.currentPan(
                        world,
                        centerX,
                        centerZ,
                        pixelsPerBlock,
                        bufferPixels,
                        mapCenter,
                        mapAngle
                );
                // Player-up frames are cropped around the buffer centre; the HUD sizes the terrain to match.
                this.hud.setTerrain(path, pan[0], pan[1], DynamicMinimapRenderer.terrainImagePixels(renderSettings));
                this.terrainPublications.shown(path);

                // Keep marker projection in the exact same accepted orientation
                // as the terrain/frame that was just delivered.
                this.projectCachedMarkers(
                        pixelsPerBlock,
                        bufferPixels,
                        Double.NaN
                );
            });
        });
    }

    /**
     * Reuses the most recently stitched north-up terrain and rotates only the
     * bitmap when the player changes heading. This keeps follow-player rotation
     * responsive without re-fetching/re-stitching world-map chunks each turn.
     */
    /** @return true when a rotation render was queued. */
    private boolean scheduleRotationOnly(
            World world,
            double yaw,
            int rotationBucket,
            boolean reduced
    ) {
        long rotationRequest = this.rotationSequence.get();
        if (this.rotationSource == null
                || Double.isNaN(this.bufferCenterX)
                || this.hud == null
                || !this.hud.isShown()) {
            return false;
        }

        if (this.renderBusy("rotation")) return false; // Would be dropped anyway: skip the copy.
        DynamicMinimapRenderer.RotationSource base = this.rotationSource;
        long generation = this.terrainGeneration;
        DynamicMinimapHud renderHud = this.hud;
        DynamicMinimapSetting renderSettings = this.settings.copy();
        int expectedRotationMode = renderSettings.rotationMode;
        double centerX = this.bufferCenterX;
        double centerZ = this.bufferCenterZ;
        double mapAngle = DynamicMinimapRenderer.bucketAngle(rotationBucket);

        return submitRender("rotation", () -> {
            if (rotationRequest != this.rotationSequence.get()) return;
            String framePath = this.terrainAssetPath(renderSettings.textureResolution, this.frameIndex.incrementAndGet());
            // Reuse a recent rotation of this same terrain when turning back to a heading.
            // Reduced and sharp frames of one heading are cached separately.
            // A sharp frame of this heading made moments ago is usually still on the client
            // (terrain slots), so it is shown again for free instead of a new reduced one.
            DynamicAssetPublisher.PreparedAsset sharpRecent = reduced ? base.cachedFrame(rotationBucket) : null;
            boolean useReduced = reduced && sharpRecent == null;
            int frameKey = useReduced ? rotationBucket + DynamicMinimapRenderer.ROTATION_BUCKETS : rotationBucket;
            DynamicAssetPublisher.PreparedAsset recent = sharpRecent != null ? sharpRecent : base.cachedFrame(frameKey);
            byte[] displayPng;
            DynamicAssetPublisher.PreparedAsset prepared;
            if (recent != null) {
                displayPng = recent.data();
                prepared = recent.withPath(framePath);
            } else {
                displayPng = DynamicMinimapRenderer.rotateTerrain(base, mapAngle, useReduced);
                if (displayPng == null || displayPng.length == 0) {
                    return;
                }
                prepared = DynamicAssetPublisher.prepare(framePath, displayPng);
                base.rememberFrame(frameKey, prepared);
            }

            if (!this.canRenderHud(renderHud)) return;
            byte[] framePng = DynamicMinimapRenderer.renderNativeHudFrame(renderSettings, renderSettings.sizePixels, mapAngle);
            if (framePng == null) return;
            // Rotation-only renders are player-up: one fixed frame path per heading.
            DynamicAssetPublisher.PreparedAsset preparedFrame = DynamicAssetPublisher.prepareShared( // Cached frame bytes: remembered hash.
                    this.headingFramePath(renderSettings, rotationBucket), framePng);
            executeDelivery(world, "rotation delivery", () -> {
                if (!this.canRenderHud(renderHud)
                        || generation != this.terrainGeneration
                        || rotationRequest != this.rotationSequence.get()
                        || this.settings.rotationMode != expectedRotationMode
                        || this.settings.zoomBlocks != renderSettings.zoomBlocks
                        || this.settings.sizePixels != renderSettings.sizePixels
                        || this.settings.shape != renderSettings.shape
                        || this.settings.textureResolution != renderSettings.textureResolution
                        || this.settings.infoPanelShown() != renderSettings.infoPanelShown() // Picks the frame variant.
                        || !this.settings.colours.equals(renderSettings.colours)) {
                    return;
                }

                String path = this.terrainPublications.pathFor(prepared.hash());
                if (path == null) {
                    DynamicAssetPublisher.PreparedAsset publication = prepared.withPath(
                            this.terrainPublications.nonActivePath(prepared.path()));
                    if (!DynamicAssetPublisher.deliver(this.playerRef, publication)) return;
                    path = publication.path();
                    this.terrainPublications.remember(path, publication.hash());
                }

                if (!renderHud.acceptTerrainFrame(mapAngle, preparedFrame)) return;
                this.displayedTerrainPng = displayPng;
                this.lastMapRotationBucket = rotationBucket;
                this.displayedRotationReduced = useReduced; // A reduced frame is sharpened once the turn ends.

                double pixelsPerBlock =
                        DynamicMinimapRenderer.pixelsPerBlock(renderSettings);
                int bufferPixels = this.hud.bufferPixels();
                int mapCenter = renderSettings.sizePixels / 2;
                int[] pan = this.currentPan(
                        world,
                        centerX,
                        centerZ,
                        pixelsPerBlock,
                        bufferPixels,
                        mapCenter,
                        mapAngle
                );
                // Player-up frames are cropped around the buffer centre; the HUD sizes the terrain to match.
                this.hud.setTerrain(path, pan[0], pan[1], DynamicMinimapRenderer.terrainImagePixels(renderSettings));
                this.terrainPublications.shown(path);

                // Rotation-only updates do not rescan entities. Re-project the
                // cached world-space markers immediately against the accepted
                // rotation bucket so terrain/frame/markers move as one.
                this.projectCachedMarkers(
                        pixelsPerBlock,
                        bufferPixels,
                        Double.NaN
                );
            });
        });
    }

    private int[] currentPan(
            World world,
            double centerX,
            double centerZ,
            double pixelsPerBlock,
            int bufferPixels,
            int mapCenter,
            double mapAngle
    ) {
        double currentX = centerX;
        double currentZ = centerZ;

        try {
            Ref<EntityStore> ref = world.getEntityRef(this.uuid);
            if (ref != null && ref.isValid()) {
                TransformComponent transform = world.getEntityStore().getStore().getComponent(ref, TransformComponent.getComponentType());
                if (transform != null) {
                    Vector3d position = transform.getPosition();
                    currentX = position.x();
                    currentZ = position.z();
                }
            }
        } catch (Throwable ignored) {
            // Position unavailable: keep the buffer centre.
        }

        return panOffset(currentX, currentZ, centerX, centerZ, pixelsPerBlock, bufferPixels, mapCenter, mapAngle);
    }

    /**
     * Pan offset {left, top} (logical px) of a terrain buffer centred on (centerX, centerZ) that
     * puts the player at (playerX, playerZ) in the middle of the map, at the given map rotation.
     */
    private static int[] panOffset(double playerX, double playerZ, double centerX, double centerZ,
                                   double pixelsPerBlock, int bufferPixels, int mapCenter, double mapAngle) {
        double[] rotated = DynamicMinimapRenderer.rotateMapDelta(
                (playerX - centerX) * pixelsPerBlock, (playerZ - centerZ) * pixelsPerBlock, mapAngle);
        double origin = (double)mapCenter - (double)bufferPixels / 2.0D;
        return new int[]{(int)Math.round(origin - rotated[0]), (int)Math.round(origin - rotated[1])};
    }

    /**
     * Performs the comparatively expensive entity-store scan. This remains on
     * markerIntervalMs(); rotation-only frames reuse this cached world-space set.
     */
    private void scanMarkers(
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            Vector3d playerPosition,
            World world,
            Player player
    ) {
        List<DynamicMinimapMarker> markers;
        try {
            markers = DynamicEntityScanner.scan(
                    store,
                    playerRef,
                    playerPosition,
                    (double)(this.settings.zoomBlocks + 16),
                    this.settings,
                    this.threatCache,
                    this.creatureIconKeys
            );
        } catch (Throwable failure) {
            DapperMapUtil.logFailure("creature scan", failure);
            markers = new ArrayList<>();
        }

        // One provider pass serves Hytale world-map markers, BetterMap waypoints (BetterMap
        // replaces Hytale's waypoint providers with its own) and death markers (Hytale's own
        // provider, or BetterMap's privacy wrapper of it). A switch change forces an
        // immediate rescan instead of waiting for the 2 s cadence.
        boolean includeNative = this.settings.showWorldMapMarkers;
        boolean betterMapWaypoints = this.betterMapWaypointsActive();
        boolean includeDeaths = this.settings.showDeathMarker;
        if (includeNative || betterMapWaypoints || includeDeaths) {
            if (includeNative != this.scannedNative || betterMapWaypoints != this.scannedBetterMap
                    || includeDeaths != this.scannedDeaths) {
                this.scannedNative = includeNative;
                this.scannedBetterMap = betterMapWaypoints;
                this.scannedDeaths = includeDeaths;
                this.lastWorldMapScanMs = 0L;
                this.stableWorldMapScans = 0;
                this.emptyWorldMapScans = 3; // Accept the new switch state's result immediately.
            }
            // The tick's own Player component and world: no second component lookup per scan.
            long now = System.currentTimeMillis();
            // Each pass runs every marker provider on the world thread: back off while the
            // markers stay the same (a teleport or switch change still rescans at once).
            long interval = this.stableWorldMapScans >= 3 ? 4000L : 2000L;
            if (player != null && now - this.lastWorldMapScanMs >= interval) {
                this.lastWorldMapScanMs = now;
                try {
                    List<DynamicMinimapMarker> current = NativeWorldMapIntegration.getMarkers(
                            world, player, this.settings.colours,
                            includeNative, betterMapWaypoints, includeDeaths);
                    if (current.isEmpty() && !this.cachedWorldMapMarkers.isEmpty()
                            && ++this.emptyWorldMapScans < 3) {
                        // A provider can briefly return no markers during a map refresh.
                    } else {
                        if (!current.equals(this.cachedWorldMapMarkers)) {
                            this.cachedWorldMapMarkers = current;
                            this.stableWorldMapScans = 0; // Something changed: keep the fast cadence.
                        } else this.stableWorldMapScans++;
                        this.emptyWorldMapScans = 0;
                    }
                } catch (Throwable failure) { DapperMapUtil.logFailure("world-map marker scan", failure); }
            }
        } else {
            this.cachedWorldMapMarkers = List.of();
            this.emptyWorldMapScans = 0;
            this.lastWorldMapScanMs = 0L;
            this.scannedNative = false;
            this.scannedBetterMap = false;
            this.scannedDeaths = false;
        }

        if (!markers.equals(this.cachedMarkers)) this.cachedMarkers = markers;
    }

    /** True when BetterMap waypoints are drawn with BetterMap styling and icons. */
    private boolean betterMapWaypointsActive() {
        return this.settings.betterMapEnabled && this.settings.betterMapMarkersEnabled
                && BetterMapIntegration.isAvailable();
    }

    /**
     * Projects the latest scanned markers into the orientation that is actually
     * visible on screen. The accepted terrain bucket is authoritative in
     * follow-player mode so markers never lead or lag the rendered map angle.
     */
    private void projectCachedMarkers(
            double pixelsPerBlock,
            int bufferPixels,
            double yaw
    ) {
        if (this.hud == null || Double.isNaN(this.bufferCenterX)) {
            return;
        }

        double mapAngle = this.acceptedMapAngle(yaw);
        double cos = Math.cos(mapAngle), sin = Math.sin(mapAngle);
        // Reused every projection (world thread only); cleared after the HUD has read it.
        DynamicMinimapHud.Slot[] slots = this.projectionSlots;
        int slot = 0;
        // Each HUD layer has its own capacity: edge icons and map dots never crowd each other out.
        int edgeFree = DynamicMinimapHud.EDGE_MARKER_SLOTS, mapFree = DynamicMinimapHud.MAP_MARKER_SLOTS;
        long projectionTime = System.currentTimeMillis();
        // Advance the attacking-creature pulse at most every PULSE_MS (it follows projection cadence).
        if (projectionTime - this.lastPulseToggleMs >= PULSE_MS) {
            this.pulseOn = !this.pulseOn;
            this.lastPulseToggleMs = projectionTime;
        }
        int attackingRgb = this.settings.colours.rgb(DynamicMinimapColours.Key.HOSTILE_ACTIVE);

        // The native POI provider returns world-wide markers from a map with no
        // useful display order. Select nearby markers before the fixed HUD slots
        // are exhausted by distant POIs clamped to the edge.
        List<DynamicMinimapMarker> orderedMarkers = this.markerOrder.get(
                this.cachedMarkers, this.cachedWorldMapMarkers,
                this.bufferCenterX, this.bufferCenterZ);

        for (DynamicMinimapMarker marker : orderedMarkers) {
            if (edgeFree == 0 && mapFree == 0) break;
            if (marker.clampToEdge ? edgeFree == 0 : mapFree == 0) continue; // Layer full: skip its icon work.

            double dx =
                    (marker.x - this.bufferCenterX) * pixelsPerBlock;
            double dz =
                    (marker.z - this.bufferCenterZ) * pixelsPerBlock;

            double markerX = dx * cos - dz * sin + (double)bufferPixels / 2.0D;
            double markerZ = dx * sin + dz * cos + (double)bufferPixels / 2.0D;

            int size = Math.max(8, marker.size);

            if (marker.clampToEdge) {
                // Edge bearings belong to the viewport, not the stitched map's
                // integer pan offset or its changing buffer center.
                double playerX = Double.isNaN(this.lastObservedX) ? this.bufferCenterX : this.lastObservedX;
                double playerZ = Double.isNaN(this.lastObservedZ) ? this.bufferCenterZ : this.lastObservedZ;
                double relativeX = (marker.x - playerX) * pixelsPerBlock;
                double relativeZ = (marker.z - playerZ) * pixelsPerBlock;
                double center = this.settings.sizePixels / 2.0;
                double visibleX = center + relativeX * cos - relativeZ * sin;
                double visibleZ = center + relativeX * sin + relativeZ * cos;
                double[] clamped = this.clampMarkerToVisibleMap(visibleX, visibleZ, size);
                boolean outside = Math.abs(clamped[0] - visibleX) > 0.001
                        || Math.abs(clamped[1] - visibleZ) > 0.001;
                double[] stable = this.edgeTracker.update(marker, clamped[0], clamped[1], outside,
                        this.settings.sizePixels, this.settings.shape, size, projectionTime);
                // Only a held (previous) position needs re-clamping; a fresh one is already clamped.
                if (outside && (stable[0] != clamped[0] || stable[1] != clamped[1])) stable = EdgeTracker.clamp(this.settings, stable[0], stable[1], size,
                        DynamicMinimapRenderer.bucketAngle(Math.max(0, this.lastMapRotationBucket)));
                markerX = stable[0];
                markerZ = stable[1];
            } else if (markerX < 0.0D
                    || markerX > (double)bufferPixels
                    || markerZ < 0.0D
                    || markerZ > (double)bufferPixels) {
                continue;
            }

            String path = marker.style == Style.CREATURE
                    ? this.creatureIcon(marker, attackingRgb) : this.markerIcon(marker);

            if (path == null || path.isBlank()) {
                continue;
            }

            int left = (int)Math.round(markerX) - size / 2;
            int top = (int)Math.round(markerZ) - size / 2;

            if (marker.clampToEdge) edgeFree--; else mapFree--;
            // Hostile and attacking creatures go in the HUD's top layer, above other markers.
            slots[slot++] =
                    new DynamicMinimapHud.Slot(
                            path,
                            left,
                            top,
                            size, marker.clampToEdge, marker.threat
                    );
        }

        this.hud.setMarkers(slots);
        java.util.Arrays.fill(slots, null); // Don't hold marker objects until the next projection.
    }

    /**
     * Clamp an edge-tracked marker in visible minimap coordinates. Markers that
     * are already inside remain untouched and therefore behave exactly like
     * ordinary position markers once the player gets close enough.
     */
    private double[] clampMarkerToVisibleMap(double x, double z, int markerSize) {
        return EdgeTracker.clamp(this.settings, x, z, markerSize,
                DynamicMinimapRenderer.bucketAngle(Math.max(0, this.lastMapRotationBucket)));
    }

    /**
     * Angle of the terrain/frame currently visible to the player.
     *
     * During an asynchronous heading change, keep pan and markers on the last
     * accepted bucket until the new rotated terrain has actually been delivered.
     */
    private double acceptedMapAngle(double yaw) {
        if (this.settings.rotationMode != 1) {
            return 0.0D;
        }

        if (this.lastMapRotationBucket >= 0) {
            return DynamicMinimapRenderer.bucketAngle(
                    this.lastMapRotationBucket
            );
        }

        if (Double.isNaN(yaw)) {
            return 0.0D;
        }

        return DynamicMinimapRenderer.bucketAngle(
                DynamicMinimapRenderer.rotationBucketForYaw(yaw)
        );
    }


    private static String coordsText(double x, double y, double z) {
        return (int)Math.floor(x) + ", " + (int)Math.floor(y) + ", " + (int)Math.floor(z);
    }

    private static final String[] COMPASS_DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /**
     * Compass bearing, clockwise from north (90 = east). Hytale's yaw increases
     * anticlockwise, so raw yaw degrees showed east/west mirrored. Uses the same
     * conversion as the locator so the readout and arrow always agree.
     */
    private static int bearingDegrees(double yaw) {
        return Math.floorMod((int)Math.round(Math.toDegrees(DynamicMinimapRenderer.screenAngleForYaw(yaw))), 360);
    }

    private static String compassText(double yaw) {
        if (Double.isNaN(yaw)) {
            return "—°  ?";
        } else {
            int degrees = bearingDegrees(yaw);
            String[] directions = COMPASS_DIRECTIONS;
            int index = (int)Math.floor(((double)degrees + (double)22.5F) / (double)45.0F) & 7;
            return degrees + "°  " + directions[index];
        }
    }

    private static String clockText(World world, boolean twentyFourHour, ReadoutCache cache) {
        WorldTimeResource worldTime = getWorldTime(world);
        if (worldTime != null) {
            LocalDateTime gameDateTime = worldTime.getGameDateTime();
            if (gameDateTime != null) {
                int secondsOfDay = gameDateTime.getHour() * 3600 + gameDateTime.getMinute() * 60 + gameDateTime.getSecond();
                return cache.clock(gameDateTime.getHour(), gameDateTime.getMinute(), isSunlit(secondsOfDay), twentyFourHour);
            }
        }

        int daySeconds = Math.max(1, world.getDaytimeDurationSeconds());
        int nightSeconds = Math.max(1, world.getNighttimeDurationSeconds());
        long cycleTicks = Math.max(1L, (long)(daySeconds + nightSeconds) * 20L);
        long tickOfDay = Math.floorMod(world.getTick(), cycleTicks);
        double dayFraction = (double)tickOfDay / (double)cycleTicks;
        int minutes = (int)Math.floor(dayFraction * (double)24.0F * (double)60.0F);
        int hour = (minutes / 60 + 6) % 24;
        int minute = minutes % 60;
        boolean isDay = tickOfDay < (long)daySeconds * 20L;
        return cache.clock(hour, minute, isDay, twentyFourHour);
    }

    private static WorldTimeResource getWorldTime(World world) {
        try {
            TimeModule timeModule = TimeModule.get();
            return timeModule == null ? null : world.getEntityStore().getStore().getResource(timeModule.getWorldTimeResourceType());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isSunlit(int secondsOfDay) {
        int sunrise = WorldTimeResource.SUNRISE_SECONDS;
        int sunset = sunrise + WorldTimeResource.DAYTIME_SECONDS;
        return secondsOfDay >= sunrise && secondsOfDay < sunset;
    }

    private static String formatClock(int hour, int minute, boolean isDay, boolean twentyFourHour) {
        String body;
        if (twentyFourHour) {
            body = twoDigits(hour) + ":" + twoDigits(minute);
        } else {
            int displayHour = hour % 12;
            if (displayHour == 0) {
                displayHour = 12;
            }

            body = displayHour + ":" + twoDigits(minute) + " " + (hour < 12 ? "AM" : "PM");
        }

        return (isDay ? "DAY  " : "NIGHT  ") + body;
    }

    private static String twoDigits(int value) { return value < 10 ? "0" + value : Integer.toString(value); }

    private static String rawBiome(Player player) {
        try {
            if (player != null && player.getWorldMapTracker() != null)
                return player.getWorldMapTracker().getCurrentBiomeName();
        } catch (RuntimeException ignored) { }
        return null;
    }

    /** Readouts change only at displayed precision, with no periodic refresh penalty. */
    static final class ReadoutCache {
        private int x, y, z, degrees, hour, minute;
        private boolean day, format;
        private String coords, compass, clock, rawBiome, biome;
        String coords(double px, double py, double pz) {
            int nx = (int)Math.floor(px), ny = (int)Math.floor(py), nz = (int)Math.floor(pz);
            if (coords == null || nx != x || ny != y || nz != z) {
                x = nx; y = ny; z = nz; coords = coordsText(px, py, pz);
            }
            return coords;
        }
        String compass(double yaw) {
            int next = Double.isNaN(yaw) ? -1 : bearingDegrees(yaw);
            if (compass == null || degrees != next) { degrees = next; compass = compassText(yaw); }
            return compass;
        }
        String clock(int nh, int nm, boolean nd, boolean nf) {
            if (clock == null || nh != hour || nm != minute || nd != day || nf != format) {
                hour = nh; minute = nm; day = nd; format = nf; clock = formatClock(nh, nm, nd, nf);
            }
            return clock;
        }
        String biome(String raw) {
            if (biome == null || !java.util.Objects.equals(raw, rawBiome)) {
                rawBiome = raw; String label = prettyName(raw);
                biome = label.length() <= 22 ? label : label.substring(0, 19) + "...";
            }
            return biome;
        }
        void clear() { coords = compass = clock = biome = rawBiome = null; }
    }

    private static String prettyName(String raw) {
        if (raw != null && !raw.isBlank()) {
            String text = raw.replace('_', ' ').replace('-', ' ').trim();
            StringBuilder out = new StringBuilder(text.length());
            boolean nextUpper = true;

            for(int i = 0; i < text.length(); ++i) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                        out.append(' ');
                    }

                    nextUpper = true;
                } else if (nextUpper) {
                    out.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    out.append(Character.toLowerCase(c));
                }
            }

            return out.toString();
        } else {
            return "Unknown";
        }
    }

    /** Small edge movements do not need another client anchor update. */
    static final class EdgeTracker {
        private final java.util.Map<DynamicMinimapMarker, Position> positions = new java.util.HashMap<>();
        double[] update(DynamicMinimapMarker key, double x, double z, boolean outside,
                        int mapSize, int shape, int iconSize, long now) {
            if (!outside) {
                positions.remove(key);
                return new double[]{x, z};
            }
            Position previous = positions.get(key);
            if (previous != null && previous.mapSize == mapSize && previous.shape == shape
                    && previous.iconSize == iconSize) {
                if (now - previous.time < 200L || (x - previous.x) * (x - previous.x) + (z - previous.z) * (z - previous.z) < 4.0)
                    return new double[]{previous.x, previous.z};
            }
            if (positions.size() >= 256 && previous == null) positions.clear();
            positions.put(key, new Position(x, z, mapSize, shape, iconSize, now));
            return new double[]{x, z};
        }
        /**
         * Pulls a marker position (visible map coordinates) in along its bearing from the map
         * centre so it sits inside the aperture edge, and stays clear of the N/E/S/W badges.
         */
        static double[] clamp(DynamicMinimapSetting settings, double x, double z, int markerSize, double angle) {
            double center = settings.sizePixels / 2.0D;
            double uiScale = settings.sizePixels / 220.0D;
            double directionX = x - center, directionZ = z - center;
            double distance = Math.hypot(directionX, directionZ);
            if (distance < .0001) return new double[]{x, z};
            directionX /= distance;
            directionZ /= distance;
            // Inner edge of the frame; for a square map the edge distance depends on the bearing.
            double perimeter = Math.max(1, center - 11 * uiScale - markerSize / 2.0D - 1);
            double radius = Math.min(distance, settings.isCircle() ? perimeter
                    : perimeter / Math.max(Math.abs(directionX), Math.abs(directionZ)));
            // Intersect the bearing ray with expanded upright cardinal badge boxes.
            // Move radially inward so the marker still indicates its true direction.
            double clearance = 11 * uiScale + markerSize / 2.0D + 1;
            // Cardinal k sits at bearing -90° + k·90° + angle; its unit vector follows from one
            // cos/sin pair: (sin, -cos), (cos, sin), (-sin, cos), (-cos, -sin).
            double cos = Math.cos(angle), sin = Math.sin(angle);
            for (int cardinal = 0; cardinal < 4; cardinal++) {
                double badgeDirectionX = switch (cardinal) { case 0 -> sin; case 1 -> cos; case 2 -> -sin; default -> -cos; };
                double badgeDirectionZ = switch (cardinal) { case 0 -> -cos; case 1 -> sin; case 2 -> cos; default -> -sin; };
                double badgeRadius = (center - 27 * uiScale)
                        / (settings.isCircle() ? 1 : Math.max(Math.abs(badgeDirectionX), Math.abs(badgeDirectionZ)));
                double badgeX = badgeDirectionX * badgeRadius, badgeZ = badgeDirectionZ * badgeRadius;
                // Ray distances where the bearing enters and leaves this badge's box (slab method).
                double enter = Double.NEGATIVE_INFINITY, exit = Double.POSITIVE_INFINITY;
                if (Math.abs(directionX) < 1e-9) {
                    if (Math.abs(badgeX) > clearance) continue;
                } else {
                    double nearSide = (badgeX - clearance) / directionX, farSide = (badgeX + clearance) / directionX;
                    enter = Math.max(enter, Math.min(nearSide, farSide));
                    exit = Math.min(exit, Math.max(nearSide, farSide));
                }
                if (Math.abs(directionZ) < 1e-9) {
                    if (Math.abs(badgeZ) > clearance) continue;
                } else {
                    double nearSide = (badgeZ - clearance) / directionZ, farSide = (badgeZ + clearance) / directionZ;
                    enter = Math.max(enter, Math.min(nearSide, farSide));
                    exit = Math.min(exit, Math.max(nearSide, farSide));
                }
                if (enter <= exit && radius >= enter && radius <= exit) radius = Math.max(0, enter - .5);
            }
            return new double[]{center + directionX * radius, center + directionZ * radius};
        }

        void clear() { positions.clear(); }
        private record Position(double x, double z, int mapSize, int shape, int iconSize, long time) {}
    }

    /** Static integration ordering is independent of frequently moving entity snapshots. */
    static final class MarkerOrder {
        private List<DynamicMinimapMarker> source;
        /** World-map markers (Hytale, BetterMap waypoints, deaths) from one provider pass. */
        private List<DynamicMinimapMarker> nativeSource;
        private List<DynamicMinimapMarker> integrations = List.of();
        private List<DynamicMinimapMarker> ordered = List.of();
        private double centerX = Double.NaN, centerZ = Double.NaN;

        private static Comparator<DynamicMinimapMarker> comparator(double x, double z) {
            return Comparator.comparingInt((DynamicMinimapMarker marker) -> switch (marker.style) {
                case BETTERMAP, NATIVE_WORLDMAP -> 1;
                case DOT, CREATURE -> 2;
            }).thenComparingDouble(marker -> {
                double dx = marker.x - x, dz = marker.z - z;
                return dx * dx + dz * dz;
            });
        }

        List<DynamicMinimapMarker> get(List<DynamicMinimapMarker> snapshot,
                                       List<DynamicMinimapMarker> nativeMarkers,
                                       double x, double z) {
            boolean recentered = Double.compare(centerX, x) != 0 || Double.compare(centerZ, z) != 0;
            boolean integrationChanged = recentered
                    || (nativeMarkers != nativeSource && !nativeMarkers.equals(nativeSource));
            boolean movingChanged = snapshot != source && !snapshot.equals(source);
            if (integrationChanged || movingChanged) {
                Comparator<DynamicMinimapMarker> compare = comparator(x, z);
                if (integrationChanged) {
                    var sorted = new ArrayList<>(nativeMarkers);
                    sorted.sort(compare);
                    integrations = sorted;
                }
                var moving = new ArrayList<>(snapshot);
                moving.sort(compare);
                // Normal local snapshots contain only in-map markers (dots or creature icons).
                // Reuse the entire immutable integration segment rather than copying every
                // waypoint whenever a creature moves. General mixed snapshots keep stable merge.
                boolean localOnly = true;
                for (int i = 0; i < moving.size(); i++)
                    localOnly &= !moving.get(i).clampToEdge;
                if (integrations.isEmpty()) ordered = moving;
                else if (localOnly) ordered = new SegmentedMarkers(moving, integrations);
                else {
                    var merged = new ArrayList<DynamicMinimapMarker>(moving.size() + integrations.size());
                    int local = 0, integration = 0;
                    while (local < moving.size() || integration < integrations.size()) {
                        if (integration >= integrations.size() || (local < moving.size()
                                && compare.compare(moving.get(local), integrations.get(integration)) <= 0))
                            merged.add(moving.get(local++));
                        else merged.add(integrations.get(integration++));
                    }
                    ordered = merged;
                }
            }
            source = snapshot;
            nativeSource = nativeMarkers;
            centerX = x;
            centerZ = z;
            return ordered;
        }

        private static final class SegmentedMarkers extends java.util.AbstractList<DynamicMinimapMarker>
                implements java.util.RandomAccess {
            private final List<DynamicMinimapMarker> moving, integrations;
            SegmentedMarkers(List<DynamicMinimapMarker> moving, List<DynamicMinimapMarker> integrations) {
                this.moving = moving; this.integrations = integrations;
            }
            @Override public int size() { return moving.size() + integrations.size(); }
            @Override public DynamicMinimapMarker get(int index) {
                java.util.Objects.checkIndex(index, size());
                if (index < integrations.size()) return integrations.get(index);
                return moving.get(index - integrations.size());
            }
        }

        void clear() {
            source = nativeSource = null;
            ordered = integrations = List.of();
            centerX = centerZ = Double.NaN;
        }
    }
}

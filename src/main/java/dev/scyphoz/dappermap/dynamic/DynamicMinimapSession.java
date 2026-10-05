package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
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
import org.joml.Vector3d;

/**
 * Runs one player's minimap update loop, coordinating terrain renders, marker scans,
 * dynamic-asset delivery and incremental HUD updates.
 */
public final class DynamicMinimapSession {
    private static final int SELF_BUCKETS = DynamicMinimapRenderer.ROTATION_BUCKETS;
    private static final int RENDER_THREADS = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 4));
    private static final ExecutorService RENDER_POOL = new java.util.concurrent.ThreadPoolExecutor(
            RENDER_THREADS, RENDER_THREADS, 0L, TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64), runnable -> {
        Thread thread = new Thread(runnable, "DapperMap-Render");
        thread.setDaemon(true);
        return thread;
    }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** False means saturation or shutdown; callers can retry on a later tick. */
    public static boolean renderAsync(Runnable work) {
        try { RENDER_POOL.execute(work); return true; }
        catch (java.util.concurrent.RejectedExecutionException rejected) { return false; }
    }

    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.AtomicBoolean> renderPending = new java.util.concurrent.ConcurrentHashMap<>();
    private record WorldTask(World world, java.util.concurrent.atomic.AtomicBoolean pending) {}
    private final java.util.concurrent.ConcurrentMap<String, WorldTask> worldPending = new java.util.concurrent.ConcurrentHashMap<>();

    public static void shutdownRendering() { RENDER_POOL.shutdownNow(); }

    // Coalesce each kind of work: slow PNG compression must not build an unbounded backlog.
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

    private void executeWorld(World world, String kind, Runnable work) {
        var token = worldPending.compute(kind, (ignored, old) -> old != null && old.world() == world
                ? old : new WorldTask(world, new java.util.concurrent.atomic.AtomicBoolean()));
        var pending = token.pending();
        if (!this.worldTaskAllowed(kind) || !world.isAlive() || !pending.compareAndSet(false, true)) return;
        try {
            world.execute(labelled(kind, () -> {
                try {
                    if (this.worldTaskAllowed(kind) && currentWorld() == world
                            && (kind.equals("tick") || world == lastObservedWorld)) work.run();
                } finally { pending.set(false); }
            }));
        } catch (RuntimeException failure) {
            pending.set(false);
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
    private volatile DynamicMinimapHud hud;
    private int builtSize = -1;
    private int builtShape = -1;
    private int builtZoom = -1;
    private int builtPosition = -1;
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
    private int lastMapRotationBucket = -1;
    private long lastRotationDeliveryMs = 0L;
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
    private MinimapColours renderedColours;
    private List<DynamicMinimapMarker> cachedMarkers = new ArrayList<>();
    private final EdgeTracker edgeTracker = new EdgeTracker();
    private final MarkerOrder markerOrder = new MarkerOrder();
    private record MarkerAssetKey(Style style, String source, int rgb) {}
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, String> markerAssets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, DynamicAssetPublisher.PreparedAsset> preparedMarkerAssets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, Boolean> markerAssetsPending = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long markerAssetGeneration;
    /** Icons whose preparation failed, with the failure time, to avoid retry storms. */
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, Long> failedMarkerAssets = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FAILED_ICON_RETRY_MS = 30000L;

    private void invalidateMarkerAssets() {
        ++this.markerAssetGeneration;
        this.markerAssets.clear();
        this.preparedMarkerAssets.clear();
        this.failedMarkerAssets.clear(); // New generation (teleport/colours): allow a fresh attempt.
        // Pending jobs finish naturally; their generation check rejects stale results.
    }

    /** Resource loading, PNG tinting and hashing never block the entity/world tick. */
    private String markerIcon(DynamicMinimapMarker marker) {
        if (!this.canRenderHud(this.hud)) return null;
        if (marker.style == Style.DOT && marker.color == null) return null;
        MinimapColours palette = this.settings.colours;
        int rgb = marker.style == Style.DEATH ? palette.rgb(MinimapColours.Key.DEATH)
                : marker.color == null ? 0xFFFFFF : marker.color.getRGB() & 0xFFFFFF;
        MarkerAssetKey key = new MarkerAssetKey(marker.style, marker.assetPath, rgb);
        String ready = this.markerAssets.get(key);
        if (ready != null) return ready;
        DynamicAssetPublisher.PreparedAsset prepared = this.preparedMarkerAssets.get(key);
        if (prepared != null) {
            if (!DynamicAssetPublisher.deliver(this.playerRef, prepared)) return null;
            if (this.markerAssets.size() >= 256) this.markerAssets.clear();
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
                    default -> {
                        byte[] png = marker.style == Style.DEATH
                                ? DynamicMinimapRenderer.renderDeath(palette)
                                : DynamicMinimapRenderer.renderDot(rgb, palette);
                        String path = "UI/DapperMap/" + (marker.style == Style.DEATH ? "death-" : "mk-")
                                + this.slug + "-" + String.format("%06X", rgb) + ".png";
                        asset = DynamicAssetPublisher.prepare(path, png);
                    }
                }
                if (!this.canRenderHud(targetHud) || generation != this.markerAssetGeneration
                        || sourceWorld != currentWorld()) return;
                if (asset == null) {
                    // Remember the failure for this generation instead of re-queuing it each tick.
                    if (this.failedMarkerAssets.size() >= 256) this.failedMarkerAssets.clear();
                    this.failedMarkerAssets.put(key, System.currentTimeMillis());
                    return;
                }
                if (this.preparedMarkerAssets.size() >= 256) this.preparedMarkerAssets.clear();
                this.preparedMarkerAssets.put(key, asset);
            } finally { this.markerAssetsPending.remove(key); }
        })) this.markerAssetsPending.remove(key);
        return null;
    }
    private List<DynamicMinimapMarker> cachedWorldMapMarkers = new ArrayList<>();
    /** Integration switches used by the last world-map scan; a change forces a rescan. */
    private boolean scannedNative, scannedBetterMap;
    private int emptyWorldMapScans = 0;
    private long lastWorldMapScanMs = 0L;
    private long lastTerrainMs = 0L;
    private boolean bufferComplete = false;
    private long warmupUntilMs = 0L;
    private int lastSelfBucket = -1;
    private int requestedSelfBucket = -1;
    private final byte[][] selfArrowCache = new byte[SELF_BUCKETS][];
    private final ReadoutCache readouts = new ReadoutCache();
    private boolean wasAlive = true;
    private boolean hasDeath = false;
    private double deathX;
    private double deathZ;
    // Detect discontinuous movement even when the player stays in the same world.
    private volatile World lastObservedWorld;
    private double lastObservedX = Double.NaN;
    private double lastObservedZ = Double.NaN;
    private static final double TELEPORT_DISTANCE_SQUARED = 64.0D * 64.0D;


    public DynamicMinimapSession(PlayerRef playerRef, DynamicMinimapSetting settings) {
        this.playerRef = playerRef;
        this.uuid = playerRef.getUuid();
        this.slug = this.uuid != null ? this.uuid.toString().replace("-", "") : "anon";
        this.settings = settings;
    }

    public DynamicMinimapSetting settings() {
        return this.settings;
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
        DynamicAssetPublisher.clear(this.uuid);
    }

    /**
     * Self-rescheduling tick. Previously a fixed 25 ms task woke for every player and
     * discarded most wake-ups; now each player wakes only at its update-speed interval,
     * which is re-read every tick so speed/rotation changes apply immediately.
     */
    private void scheduleNextTick(long delayMs) {
        if (!this.running) return;
        try {
            this.task = HytaleServer.SCHEDULED_EXECUTOR.schedule(this::tickSafe, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Server scheduler is shutting down; the session ends with it.
        }
    }

    private long tickIntervalMs() {
        return this.settings.rotationMode == 1
                ? Math.min(this.settings.panIntervalMs(), this.rotationPollIntervalMs())
                : this.settings.panIntervalMs();
    }

    private void tickSafe() {
        try {
            this.lastTickMs = System.currentTimeMillis();
            this.tick();
        } catch (Throwable var3) {
        } finally {
            // stop() clears running first, so a stopped session never reschedules itself.
            this.scheduleNextTick(this.tickIntervalMs());
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
            TransformComponent transform = (TransformComponent)store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                return;
            }

            Player player = (Player)store.getComponent(ref, Player.getComponentType());
            Vector3d position = transform.getPosition();
            double x = position.x();
            double y = position.y();
            double z = position.z();
            boolean worldChanged = lastObservedWorld != null && lastObservedWorld != world;
            boolean teleported = lastObservedWorld != null
                    && (worldChanged
                    || (x - lastObservedX) * (x - lastObservedX) + (z - lastObservedZ) * (z - lastObservedZ)
                    > TELEPORT_DISTANCE_SQUARED);
            lastObservedWorld = world;
            lastObservedX = x;
            lastObservedZ = z;
            if (teleported) {
                this.edgeTracker.clear();
                // A teleport can reset the client's dynamic assets without ending
                // the DapperMap session. Re-send integration icons and force HUD
                // slot paths to be rebound rather than relying on cached values.
                this.invalidateMarkerAssets();
                this.terrainPublications.clear();
                this.bufferCenterX = Double.NaN;
                this.bufferCenterZ = Double.NaN;
                this.bufferComplete = false;
                this.lastTerrainMs = 0L;
                this.lastSignatureMs = 0L;
                this.deliveredSequence = this.renderSequence.incrementAndGet();
                ++this.terrainGeneration;
                this.rotationSequence.incrementAndGet();
                if (this.hud != null) this.hud.invalidateVisualAssets();
                DynamicAssetPublisher.clear(this.uuid);
                if (this.hud != null) this.hud.invalidateMarkerSlots();
                if (worldChanged) {
                    BetterMapIntegration.clearWorld(this.lastCaveView == null ? null : this.lastCaveView.world());
                    this.worldPending.clear();
                    if (this.hud != null) this.hud.detach();
                    this.hud = null;
                    this.lastCaveView = null;
                    this.lastCaveCheckMs = 0L; // Re-read cave mode immediately in the new world.
                    this.rotationSource = null;
                    this.displayedTerrainPng = null;
                    this.terrainPublications.clear();
                    this.bufferCenterX = Double.NaN;
                    this.bufferCenterZ = Double.NaN;
                    this.bufferComplete = false;
                    this.lastTerrainMs = 0L;
                    ++this.terrainGeneration;
                    this.rotationSequence.incrementAndGet();
                    this.cachedMarkers = new ArrayList<>();
                    this.cachedWorldMapMarkers = List.of();
                    this.emptyWorldMapScans = 0;
                }
                this.lastMarkerMs = 0L;
                this.lastWorldMapScanMs = 0L;
            }
            Rotation3f rotation = transform.getRotation();
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
                this.bufferCenterX = Double.NaN;
                this.bufferCenterZ = Double.NaN;
                this.bufferComplete = false;
                this.warmupUntilMs = 0L;
                this.lastSignature = 0L;
                this.lastSignatureMs = 0L;
                this.lastTerrainMs = 0L;
                this.rotationSource = null;
                this.displayedTerrainPng = null;
                this.terrainPublications.clear();
                ++this.terrainGeneration;
                this.rotationSequence.incrementAndGet();
            }

            if (this.settings.sizePixels != this.builtSize) {
                this.builtSize = this.settings.sizePixels;
                this.hud.setMapSize(this.settings.sizePixels);

                // Logical size changes pixels-per-block and buffer dimensions.
                // Invalidate the old terrain buffer and let the normal render
                // path create a fresh one without recreating the Custom HUD.
                this.bufferCenterX = Double.NaN;
                this.bufferCenterZ = Double.NaN;
                this.bufferComplete = false;
                this.warmupUntilMs = 0L;
                this.lastSignature = 0L;
                this.lastSignatureMs = 0L;
                this.lastTerrainMs = 0L;
                this.lastMarkerMs = 0L;
                this.rotationSource = null;
                this.displayedTerrainPng = null;
                this.terrainPublications.clear();
                ++this.terrainGeneration;
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
                java.util.Arrays.fill(this.selfArrowCache, null);
                this.invalidateMarkerAssets();
                this.lastSelfBucket = -1;
                this.lastMarkerMs = 0L;
                this.updateRotatingFrame(Math.max(0, this.lastMapRotationBucket));
                this.hud.setColours(this.renderedColours);
            }


            if (this.settings.rotationMode != this.builtRotationMode) {
                this.builtRotationMode = this.settings.rotationMode;
                // Changing mode also changes raster quality. Force new terrain;
                // the previous decoded source must not survive the transition.
                this.rotationSource = null;
                this.bufferCenterX = Double.NaN;
                this.bufferCenterZ = Double.NaN;
                this.bufferComplete = false;
                this.lastTerrainMs = 0L;
                this.lastSignatureMs = 0L;
                ++this.terrainGeneration;
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
                    && now - this.lastRotationDeliveryMs >= this.rotationIntervalMs()) {
                this.scheduleRotationOnly(
                        world,
                        yaw,
                        mapRotationBucket
                );

                // The cached markers are projected again when the rotated terrain arrives.
            }

            this.detectDeath(store, ref, x, z);
            this.updateSelfArrow(yaw);
            double pixelsPerBlock = DynamicMinimapRenderer.pixelsPerBlock(this.settings);
            int bufferPixels = this.hud.bufferPixels();
            int mapCenter = this.settings.sizePixels / 2;
            double panMargin = (double)DynamicMinimapRenderer.panMarginPixels(this.settings);
            boolean signatureChanged = this.signatureChangedPending;
            if (!Double.isNaN(this.bufferCenterX)
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
                }
            } else if (!noBuffer) {
                double dx = (x - this.bufferCenterX) * pixelsPerBlock;
                double dz = (z - this.bufferCenterZ) * pixelsPerBlock;
                double mapAngle = this.acceptedMapAngle(yaw);
                double[] rotated =
                        DynamicMinimapRenderer.rotateMapDelta(dx, dz, mapAngle);
                int left = (int)Math.round(
                        (double)mapCenter - (double)bufferPixels / 2.0D - rotated[0]
                );
                int top = (int)Math.round(
                        (double)mapCenter - (double)bufferPixels / 2.0D - rotated[1]
                );
                this.hud.pan(left, top);
            }

            if (!Double.isNaN(this.bufferCenterX)
                    && now - this.lastMarkerMs > this.settings.markerIntervalMs()) {
                this.lastMarkerMs = now;
                this.scanMarkers(store, ref, position);
                this.projectCachedMarkers(pixelsPerBlock, bufferPixels, yaw);
            }

            boolean panel = this.settings.infoPanelEnabled;
            String coords = panel && this.settings.showCoordinates ? this.readouts.coords(x, y, z) : "";
            String compass = panel && this.settings.showCompass ? this.readouts.compass(yaw) : "";
            String clock = panel && this.settings.showClock ? clockText(world, this.settings.clock24Hour, this.readouts) : "";
            String biome = panel && this.settings.showBiome ? this.readouts.biome(rawBiome(player)) : "";
            this.hud.setStatus(coords, compass, clock, biome);
            this.hud.flushPendingVisuals(this.settings.updateSpeed);
        } catch (Throwable var34) {
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

            this.settings.normalize();
            this.hud = new DynamicMinimapHud(this.playerRef, this.settings, work -> executeWorld(world, "overlay delivery", work),
                    () -> this.running && this.settings.enabled);
            this.builtSize = this.settings.sizePixels;
            this.builtShape = this.settings.shape;
            this.builtZoom = this.settings.zoomBlocks;
            this.builtPosition = this.settings.position;
            this.builtHudPositionX = this.settings.hudPositionX;
            this.builtHudPositionY = this.settings.hudPositionY;
            this.builtInfoPanelEnabled = this.settings.infoPanelEnabled;
            this.builtInfoPanelPosition = this.settings.infoPanelPosition;
            this.builtInfoPanelTextAlignment = this.settings.infoPanelTextAlignment;
            this.builtInterfaceScale = this.settings.interfaceScalePercent;
            this.builtTextureResolution = this.settings.textureResolution;
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

    private void updateSelfArrow(double yaw) {
        if (!Double.isNaN(yaw) && this.hud != null) {
            int bucket;
            double angle;

            if (this.settings.rotationMode == 1) {
                // Player-up mode: the world rotates, so the self marker stays up.
                bucket = 0;
                angle = 0.0D;
            } else {
                angle = DynamicMinimapRenderer.screenAngleForYaw(yaw);
                bucket = (int)Math.round(
                        angle / (Math.PI * 2D)
                                * (double)DynamicMinimapRenderer.ROTATION_BUCKETS
                );
                bucket = Math.floorMod(bucket, DynamicMinimapRenderer.ROTATION_BUCKETS);
                angle = (double)bucket * (Math.PI * 2D)
                        / (double)DynamicMinimapRenderer.ROTATION_BUCKETS;
            }

            this.requestedSelfBucket = bucket;
            if (bucket != this.lastSelfBucket) {
                String path = "UI/DapperMap/self-" + this.slug + "-" + this.settings.colours.arrowKey() + "-" + bucket + ".png";
                byte[] png = this.selfArrowCache[bucket];
                if (png != null) {
                    if (DynamicAssetPublisher.deliver(this.playerRef, path, png)) {
                        this.lastSelfBucket = bucket;
                        this.hud.setSelf(path);
                    }
                } else {
                    final int renderBucket = bucket;
                    final double renderAngle = angle;
                    MinimapColours colours = this.settings.colours;
                    DynamicMinimapHud targetHud = this.hud;
                    World world = this.lastObservedWorld;
                    if (world == null) return;
                    submitRender("self", () -> {
                        byte[] rendered = DynamicMinimapRenderer.renderSelfArrow(renderAngle, colours);
                        if (rendered == null || !this.canRenderHud(targetHud)) return;
                        executeWorld(world, "self arrow delivery", () -> {
                            if (!this.canRenderHud(targetHud)
                                    || this.settings.colours != colours) return;
                            this.selfArrowCache[renderBucket] = rendered;
                            if (this.requestedSelfBucket == renderBucket
                                    && DynamicAssetPublisher.deliver(this.playerRef, path, rendered)) {
                                this.lastSelfBucket = renderBucket;
                                targetHud.setSelf(path);
                            }
                        });
                    });
                }
            }
        }
    }

    /** Probe map-image changes on the render pool so cave lookups and image hashing
     * do not hold up world ticks. Only the latest matching buffer can request a redraw. */
    private void scheduleSignatureCheck(World world, double centerX, double centerZ,
                                        BetterMapIntegration.CaveView caveView) {
        DynamicMinimapSetting probeSettings = this.settings.copy();
        DynamicMinimapHud probeHud = this.hud;
        long generation = this.terrainGeneration;
        submitRender("signature", () -> {
            long signature;
            try {
                signature = DynamicMinimapRenderer.terrainSignature(
                        world.getWorldMapManager(), centerX, centerZ, probeSettings, caveView);
            } catch (Throwable ignored) {
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
        long sequence = this.renderSequence.incrementAndGet();
        DynamicMinimapSetting renderSettings = this.settings.copy();
        DynamicMinimapHud renderHud = this.hud;
        int rotationBucket = renderSettings.rotationMode == 1
                ? DynamicMinimapRenderer.rotationBucketForYaw(yaw)
                : 0;
        double mapAngle = DynamicMinimapRenderer.bucketAngle(rotationBucket);

        return submitRender("terrain", () -> {
            if (!this.canRenderHud(renderHud)) return;

            DynamicMinimapRenderer.Terrain terrain =
                    DynamicMinimapRenderer.renderTerrain(
                            world.getWorldMapManager(),
                            centerX,
                            centerZ,
                            renderSettings,
                            caveView
                    );

            byte[] northUpPng = terrain.png;
            if (northUpPng == null || northUpPng.length == 0) {
                return;
            }

            DynamicMinimapRenderer.RotationSource source = renderSettings.rotationMode == 1
                    ? DynamicMinimapRenderer.prepareRotationSource(northUpPng) : null;
            byte[] displayPng = renderSettings.rotationMode == 1
                    ? DynamicMinimapRenderer.rotateTerrain(source, mapAngle)
                    : northUpPng;

            if (displayPng == null || displayPng.length == 0) {
                return;
            }

            if (!this.canRenderHud(renderHud)) return;
            DynamicAssetPublisher.PreparedAsset prepared = DynamicAssetPublisher.prepare(
                    this.terrainAssetPath(renderSettings.textureResolution, this.frameIndex.incrementAndGet()), displayPng);
            byte[] framePng = DynamicMinimapRenderer.renderNativeHudFrame(renderSettings, renderSettings.sizePixels, mapAngle);
            if (framePng == null) return;
            DynamicAssetPublisher.PreparedAsset preparedFrame = DynamicAssetPublisher.prepare(
                    compassFramePath(prepared.path()), framePng);
            // Hash the stitched map off the world thread as well as rendering its PNG.
            long renderSignature = DynamicMinimapRenderer.terrainSignature(
                    world.getWorldMapManager(), centerX, centerZ, renderSettings, caveView);
            executeWorld(world, "terrain delivery", () -> {
                if (!this.canRenderHud(renderHud)
                        || !java.util.Objects.equals(caveView, this.lastCaveView)
                        || sequence <= this.deliveredSequence
                        || this.settings.textureResolution != renderSettings.textureResolution
                        || this.settings.rotationMode != renderSettings.rotationMode
                        || this.settings.sizePixels != renderSettings.sizePixels
                        || this.settings.shape != renderSettings.shape
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
                this.lastRotationDeliveryMs = System.currentTimeMillis();

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
                this.hud.setTerrain(path, pan[0], pan[1]);

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
    private void scheduleRotationOnly(
            World world,
            double yaw,
            int rotationBucket
    ) {
        long rotationRequest = this.rotationSequence.get();
        if (this.rotationSource == null
                || Double.isNaN(this.bufferCenterX)
                || this.hud == null
                || !this.hud.isShown()) {
            return;
        }

        DynamicMinimapRenderer.RotationSource base = this.rotationSource;
        long generation = this.terrainGeneration;
        DynamicMinimapHud renderHud = this.hud;
        DynamicMinimapSetting renderSettings = this.settings.copy();
        int expectedRotationMode = renderSettings.rotationMode;
        double centerX = this.bufferCenterX;
        double centerZ = this.bufferCenterZ;
        double mapAngle = DynamicMinimapRenderer.bucketAngle(rotationBucket);

        submitRender("rotation", () -> {
            if (rotationRequest != this.rotationSequence.get()) return;
            byte[] displayPng =
                    DynamicMinimapRenderer.rotateTerrain(base, mapAngle);
            if (displayPng == null || displayPng.length == 0) {
                return;
            }

            if (!this.canRenderHud(renderHud)) return;
            DynamicAssetPublisher.PreparedAsset prepared = DynamicAssetPublisher.prepare(
                    this.terrainAssetPath(renderSettings.textureResolution, this.frameIndex.incrementAndGet()), displayPng);
            byte[] framePng = DynamicMinimapRenderer.renderNativeHudFrame(renderSettings, renderSettings.sizePixels, mapAngle);
            if (framePng == null) return;
            DynamicAssetPublisher.PreparedAsset preparedFrame = DynamicAssetPublisher.prepare(
                    compassFramePath(prepared.path()), framePng);
            executeWorld(world, "rotation delivery", () -> {
                if (!this.canRenderHud(renderHud)
                        || generation != this.terrainGeneration
                        || rotationRequest != this.rotationSequence.get()
                        || this.settings.rotationMode != expectedRotationMode
                        || this.settings.sizePixels != renderSettings.sizePixels
                        || this.settings.shape != renderSettings.shape
                        || this.settings.textureResolution != renderSettings.textureResolution
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
                this.lastRotationDeliveryMs = System.currentTimeMillis();

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
                this.hud.setTerrain(path, pan[0], pan[1]);

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
                TransformComponent transform = (TransformComponent)world.getEntityStore().getStore().getComponent(ref, TransformComponent.getComponentType());
                if (transform != null) {
                    Vector3d position = transform.getPosition();
                    currentX = position.x();
                    currentZ = position.z();
                }
            }
        } catch (Throwable var17) {
        }

        double dx = (currentX - centerX) * pixelsPerBlock;
        double dz = (currentZ - centerZ) * pixelsPerBlock;
        double[] rotated = DynamicMinimapRenderer.rotateMapDelta(dx, dz, mapAngle);

        int left = (int)Math.round(
                (double)mapCenter - (double)bufferPixels / 2.0D - rotated[0]
        );
        int top = (int)Math.round(
                (double)mapCenter - (double)bufferPixels / 2.0D - rotated[1]
        );
        return new int[]{left, top};
    }

    /**
     * Performs the comparatively expensive entity-store scan. This remains on
     * markerIntervalMs(); rotation-only frames reuse this cached world-space set.
     */
    private void scanMarkers(
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            Vector3d playerPosition
    ) {
        List<DynamicMinimapMarker> markers;
        try {
            markers = DynamicEntityScanner.scan(
                    store,
                    playerRef,
                    playerPosition,
                    (double)(this.settings.zoomBlocks + 16),
                    this.settings
            );
        } catch (Throwable ignored) {
            markers = new ArrayList<>();
        }

        if (this.settings.showDeathMarker && this.hasDeath) {
            markers.add(
                    DynamicMinimapMarker.death(
                            this.deathX,
                            this.deathZ,
                            12
                    )
            );
        }

        // One provider pass serves both integrations: Hytale world-map markers and BetterMap
        // waypoints (BetterMap replaces Hytale's waypoint providers with its own). A switch
        // change forces an immediate rescan instead of waiting for the 2 s cadence.
        boolean includeNative = this.settings.showWorldMapMarkers;
        boolean betterMapWaypoints = this.betterMapWaypointsActive();
        if (includeNative || betterMapWaypoints) {
            if (includeNative != this.scannedNative || betterMapWaypoints != this.scannedBetterMap) {
                this.scannedNative = includeNative;
                this.scannedBetterMap = betterMapWaypoints;
                this.lastWorldMapScanMs = 0L;
                this.emptyWorldMapScans = 3; // Accept the new switch state's result immediately.
            }
            Player player = store.getComponent(playerRef, Player.getComponentType());
            long now = System.currentTimeMillis();
            if (player != null && now - this.lastWorldMapScanMs >= 2000L) {
                this.lastWorldMapScanMs = now;
                try {
                    List<DynamicMinimapMarker> current = NativeWorldMapIntegration.getMarkers(
                            store.getExternalData().getWorld(), player, this.settings.colours,
                            includeNative, betterMapWaypoints);
                    if (current.isEmpty() && !this.cachedWorldMapMarkers.isEmpty()
                            && ++this.emptyWorldMapScans < 3) {
                        // A provider can briefly return no markers during a map refresh.
                    } else {
                        if (!current.equals(this.cachedWorldMapMarkers)) this.cachedWorldMapMarkers = current;
                        this.emptyWorldMapScans = 0;
                    }
                } catch (Throwable ignored) { }
            }
        } else {
            this.cachedWorldMapMarkers = List.of();
            this.emptyWorldMapScans = 0;
            this.lastWorldMapScanMs = 0L;
            this.scannedNative = false;
            this.scannedBetterMap = false;
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
        DynamicMinimapHud.Slot[] slots =
                new DynamicMinimapHud.Slot[DynamicMinimapHud.MARKER_SLOTS];
        int slot = 0;
        long projectionTime = System.currentTimeMillis();

        // The native POI provider returns world-wide markers from a map with no
        // useful display order. Select nearby markers before the fixed HUD slots
        // are exhausted by distant POIs clamped to the edge.
        List<DynamicMinimapMarker> orderedMarkers = this.markerOrder.get(
                this.cachedMarkers, this.cachedWorldMapMarkers, List.of(),
                this.bufferCenterX, this.bufferCenterZ);

        for (DynamicMinimapMarker marker : orderedMarkers) {
            if (slot >= slots.length) {
                break;
            }

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
                if (outside) stable = EdgeTracker.clamp(this.settings, stable[0], stable[1], size,
                        DynamicMinimapRenderer.bucketAngle(Math.max(0, this.lastMapRotationBucket)));
                markerX = stable[0];
                markerZ = stable[1];
            } else if (markerX < 0.0D
                    || markerX > (double)bufferPixels
                    || markerZ < 0.0D
                    || markerZ > (double)bufferPixels) {
                continue;
            }

            String path = this.markerIcon(marker);

            if (path == null || path.isBlank()) {
                continue;
            }

            int left = (int)Math.round(markerX) - size / 2;
            int top = (int)Math.round(markerZ) - size / 2;

            slots[slot++] =
                    new DynamicMinimapHud.Slot(
                            path,
                            left,
                            top,
                            size, marker.clampToEdge
                    );
        }

        this.hud.setMarkers(slots);
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


    private void detectDeath(Store<EntityStore> store, Ref<EntityStore> ref, double x, double z) {
        try {
            EntityStatMap statMap = (EntityStatMap)store.getComponent(ref, EntityStatMap.getComponentType());
            if (statMap == null) {
                return;
            }

            // Hytale keeps the Health stat index cached; no per-tick asset-map lookup.
            int healthIndex = DefaultEntityStatTypes.getHealth();
            EntityStatValue health = statMap.get(healthIndex);
            if (health == null) {
                return;
            }

            boolean alive = health.get() > 0.001F;
            if (this.wasAlive && !alive) {
                this.deathX = x;
                this.deathZ = z;
                this.hasDeath = true;
            }

            this.wasAlive = alive;
        } catch (Throwable var11) {
        }

    }

    private static String coordsText(double x, double y, double z) {
        return (int)Math.floor(x) + ", " + (int)Math.floor(y) + ", " + (int)Math.floor(z);
    }

    private static final String[] COMPASS_DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private static String compassText(double yaw) {
        if (Double.isNaN(yaw)) {
            return "—°  ?";
        } else {
            int degrees = Math.floorMod((int)Math.round(Math.toDegrees(yaw)), 360);
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
            return timeModule == null ? null : (WorldTimeResource)world.getEntityStore().getStore().getResource(timeModule.getWorldTimeResourceType());
        } catch (RuntimeException var2) {
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
            int next = Double.isNaN(yaw) ? -1 : Math.floorMod((int)Math.round(Math.toDegrees(yaw)), 360);
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
        static double[] clamp(DynamicMinimapSetting settings, double x, double z, int markerSize) {
            return clamp(settings, x, z, markerSize, 0);
        }
        static double[] clamp(DynamicMinimapSetting settings, double x, double z, int markerSize, double angle) {
            double center = settings.sizePixels / 2.0D;
            double uiScale = settings.sizePixels / 220.0D;
            double dx = x-center, dz = z-center, distance = Math.hypot(dx,dz);
            if(distance < .0001)return new double[]{x,z};
            dx/=distance;dz/=distance;
            double perimeter = Math.max(1, center - 11*uiScale - markerSize/2.0D - 1);
            double radius = Math.min(distance, settings.isCircle() ? perimeter
                    : perimeter / Math.max(Math.abs(dx),Math.abs(dz)));
            // Intersect the bearing ray with expanded upright cardinal badge boxes.
            // Move radially inward so the marker still indicates its true direction.
            double clearance = 11*uiScale + markerSize/2.0D + 1;
            for(int i=0;i<4;i++){
                double bearing = -Math.PI/2 + i*Math.PI/2 + angle;
                double bx=Math.cos(bearing),bz=Math.sin(bearing);
                double badgeRadius=(center-27*uiScale)/(settings.isCircle()?1:Math.max(Math.abs(bx),Math.abs(bz)));
                double lx=bx*badgeRadius,lz=bz*badgeRadius;
                double enter=Double.NEGATIVE_INFINITY,exit=Double.POSITIVE_INFINITY;
                if(Math.abs(dx)<1e-9){if(Math.abs(lx)>clearance)continue;}
                else {double t1=(lx-clearance)/dx,t2=(lx+clearance)/dx;enter=Math.max(enter,Math.min(t1,t2));exit=Math.min(exit,Math.max(t1,t2));}
                if(Math.abs(dz)<1e-9){if(Math.abs(lz)>clearance)continue;}
                else {double t1=(lz-clearance)/dz,t2=(lz+clearance)/dz;enter=Math.max(enter,Math.min(t1,t2));exit=Math.min(exit,Math.max(t1,t2));}
                if(enter<=exit && radius>=enter && radius<=exit)radius=Math.max(0,enter-.5);
            }
            return new double[]{center+dx*radius,center+dz*radius};
        }

        void clear() { positions.clear(); }
        private record Position(double x, double z, int mapSize, int shape, int iconSize, long time) {}
    }

    /** Static integration ordering is independent of frequently moving entity snapshots. */
    static final class MarkerOrder {
        private List<DynamicMinimapMarker> source;
        private List<DynamicMinimapMarker> nativeSource, betterSource;
        private List<DynamicMinimapMarker> integrations = List.of();
        private List<DynamicMinimapMarker> ordered = List.of();
        private double centerX = Double.NaN, centerZ = Double.NaN;

        private static Comparator<DynamicMinimapMarker> comparator(double x, double z) {
            return Comparator.comparingInt((DynamicMinimapMarker marker) -> switch (marker.style) {
                case DEATH -> 0;
                case BETTERMAP, NATIVE_WORLDMAP -> 1;
                case DOT -> 2;
            }).thenComparingDouble(marker -> {
                double dx = marker.x - x, dz = marker.z - z;
                return dx * dx + dz * dz;
            });
        }

        List<DynamicMinimapMarker> get(List<DynamicMinimapMarker> snapshot, double x, double z) {
            return get(snapshot, List.of(), List.of(), x, z);
        }

        List<DynamicMinimapMarker> get(List<DynamicMinimapMarker> snapshot,
                                       List<DynamicMinimapMarker> nativeMarkers, List<DynamicMinimapMarker> betterMarkers,
                                       double x, double z) {
            boolean recentered = Double.compare(centerX, x) != 0 || Double.compare(centerZ, z) != 0;
            boolean integrationChanged = recentered
                    || (nativeMarkers != nativeSource && !nativeMarkers.equals(nativeSource))
                    || (betterMarkers != betterSource && !betterMarkers.equals(betterSource));
            boolean movingChanged = snapshot != source && !snapshot.equals(source);
            if (integrationChanged || movingChanged) {
                Comparator<DynamicMinimapMarker> compare = comparator(x, z);
                if (integrationChanged) {
                    var sorted = new ArrayList<DynamicMinimapMarker>(nativeMarkers.size() + betterMarkers.size());
                    sorted.addAll(nativeMarkers);
                    sorted.addAll(betterMarkers);
                    sorted.sort(compare);
                    integrations = sorted;
                }
                var moving = new ArrayList<>(snapshot);
                moving.sort(compare);
                // Normal local snapshots contain deaths and dots. Reuse the entire
                // immutable integration segment rather than copying every waypoint
                // whenever a creature moves. General mixed snapshots keep stable merge.
                int deaths = 0;
                while (deaths < moving.size() && moving.get(deaths).style == DynamicMinimapMarker.Style.DEATH) deaths++;
                boolean localOnly = true;
                for (int i = deaths; i < moving.size(); i++)
                    localOnly &= moving.get(i).style == DynamicMinimapMarker.Style.DOT;
                if (integrations.isEmpty()) ordered = moving;
                else if (localOnly) ordered = new SegmentedMarkers(moving, integrations, deaths);
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
            betterSource = betterMarkers;
            centerX = x;
            centerZ = z;
            return ordered;
        }

        private static final class SegmentedMarkers extends java.util.AbstractList<DynamicMinimapMarker>
                implements java.util.RandomAccess {
            private final List<DynamicMinimapMarker> moving, integrations;
            private final int deaths;
            SegmentedMarkers(List<DynamicMinimapMarker> moving, List<DynamicMinimapMarker> integrations, int deaths) {
                this.moving = moving; this.integrations = integrations; this.deaths = deaths;
            }
            @Override public int size() { return moving.size() + integrations.size(); }
            @Override public DynamicMinimapMarker get(int index) {
                java.util.Objects.checkIndex(index, size());
                if (index < deaths) return moving.get(index);
                if (index < deaths + integrations.size()) return integrations.get(index - deaths);
                return moving.get(index - integrations.size());
            }
        }

        void clear() {
            source = nativeSource = betterSource = null;
            ordered = integrations = List.of();
            centerX = centerZ = Double.NaN;
        }
    }
}

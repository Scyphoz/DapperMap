package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
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
        var pending = renderPending.computeIfAbsent(kind, ignored -> new java.util.concurrent.atomic.AtomicBoolean());
        if (!pending.compareAndSet(false, true)) return false;
        World sourceWorld = this.lastObservedWorld;
        DynamicMinimapHud sourceHud = this.hud;
        if (!renderAsync(() -> {
            try {
                if (running && sourceHud == this.hud && sourceWorld != null
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

    private void executeWorld(World world, String kind, Runnable work) {
        var token = worldPending.compute(kind, (ignored, old) -> old != null && old.world() == world
                ? old : new WorldTask(world, new java.util.concurrent.atomic.AtomicBoolean()));
        var pending = token.pending();
        if (!running || !world.isAlive() || !pending.compareAndSet(false, true)) return;
        try {
            world.execute(labelled(kind, () -> {
                try {
                    if (running && currentWorld() == world
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
        CompletableFuture<Void> ready = this.staticAssetsReady;
        if (ready == null || ready.isDone() || !this.staticAssetsQueued.compareAndSet(false, true)) return;
        DynamicMinimapSetting snapshot = this.settings.copy();
        if (!renderAsync(() -> {
            try {
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
    private byte[] northUpTerrainPng = null;
    private byte[] displayedTerrainPng = null;
    private final ImagePublicationCache terrainPublications = new ImagePublicationCache();

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
    private long lastMarkerMs = 0L;
    private MinimapColours renderedColours;
    private List<DynamicMinimapMarker> cachedMarkers = new ArrayList<>();
    private final MarkerOrder markerOrder = new MarkerOrder();
    private record MarkerAssetKey(Style style, String source, int rgb) {}
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, String> markerAssets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, DynamicAssetPublisher.PreparedAsset> preparedMarkerAssets = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<MarkerAssetKey, Boolean> markerAssetsPending = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long markerAssetGeneration;

    private void invalidateMarkerAssets() {
        ++this.markerAssetGeneration;
        this.markerAssets.clear();
        this.preparedMarkerAssets.clear();
        // Pending jobs finish naturally; their generation check rejects stale results.
    }

    /** Resource loading, PNG tinting and hashing never block the entity/world tick. */
    private String markerIcon(DynamicMinimapMarker marker) {
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
        if (this.markerAssetsPending.size() >= 16
                || this.markerAssetsPending.putIfAbsent(key, Boolean.TRUE) != null) return null;
        long generation = this.markerAssetGeneration;
        World sourceWorld = this.lastObservedWorld;
        DynamicMinimapHud targetHud = this.hud;
        if (!renderAsync(() -> {
            try {
                if (!this.running || generation != this.markerAssetGeneration || targetHud != this.hud
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
                if (asset != null && this.running && generation == this.markerAssetGeneration
                        && targetHud == this.hud && sourceWorld == currentWorld()) {
                    if (this.preparedMarkerAssets.size() >= 256) this.preparedMarkerAssets.clear();
                    this.preparedMarkerAssets.put(key, asset);
                }
            } finally { this.markerAssetsPending.remove(key); }
        })) this.markerAssetsPending.remove(key);
        return null;
    }
    private List<DynamicMinimapMarker> cachedBetterMapMarkers = new ArrayList<>();
    private List<DynamicMinimapMarker> cachedWorldMapMarkers = new ArrayList<>();
    private int emptyBetterMapScans = 0;
    private int emptyWorldMapScans = 0;
    private long lastBetterMapScanMs = 0L;
    private long lastWorldMapScanMs = 0L;
    private long lastTerrainMs = 0L;
    private boolean bufferComplete = false;
    private long warmupUntilMs = 0L;
    private int lastSelfBucket = -1;
    private int requestedSelfBucket = -1;
    private final byte[][] selfArrowCache = new byte[SELF_BUCKETS][];
    private String lastCoords = null;
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
            this.task = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::tickSafe, 500L, 25L, TimeUnit.MILLISECONDS);
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
        this.northUpTerrainPng = null;
        this.displayedTerrainPng = null;
        this.terrainPublications.clear();
        this.lastObservedWorld = null;
        this.lastCaveView = null;
        this.markerOrder.clear();
        this.worldPending.clear();
        this.invalidateMarkerAssets();
        DynamicAssetPublisher.clear(this.uuid);
    }

    private void tickSafe() {
        try {
            long now = System.currentTimeMillis();
            long tickInterval = this.settings.rotationMode == 1
                    ? Math.min(this.settings.panIntervalMs(), this.rotationPollIntervalMs())
                    : this.settings.panIntervalMs();
            if (now - this.lastTickMs < tickInterval) {
                return;
            }

            this.lastTickMs = now;
            this.tick();
        } catch (Throwable var3) {
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
                    || Math.pow(x - lastObservedX, 2) + Math.pow(z - lastObservedZ, 2)
                    > TELEPORT_DISTANCE_SQUARED);
            lastObservedWorld = world;
            lastObservedX = x;
            lastObservedZ = z;
            if (teleported) {
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
                    this.northUpTerrainPng = null;
                    this.displayedTerrainPng = null;
                    this.terrainPublications.clear();
                    this.bufferCenterX = Double.NaN;
                    this.bufferCenterZ = Double.NaN;
                    this.bufferComplete = false;
                    this.lastTerrainMs = 0L;
                    ++this.terrainGeneration;
                    this.rotationSequence.incrementAndGet();
                    this.cachedMarkers = new ArrayList<>();
                    this.cachedBetterMapMarkers = new ArrayList<>();
                    this.cachedWorldMapMarkers = new ArrayList<>();
                    this.emptyBetterMapScans = 0;
                    this.emptyWorldMapScans = 0;
                }
                this.lastMarkerMs = 0L;
                this.lastBetterMapScanMs = 0L;
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
                this.northUpTerrainPng = null;
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
                this.northUpTerrainPng = null;
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
            BetterMapIntegration.CaveView caveView = this.settings.betterMapEnabled
                    && this.settings.betterMapCaveModeEnabled
                    ? BetterMapIntegration.caveView(player, world) : null;
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

            String coords = this.settings.showCoordinates ? coordsText(x, y, z) : "";
            String compass = this.settings.showCompass ? compassText(yaw) : "";
            String clock = this.settings.showClock ? clockText(world, this.settings.clock24Hour) : "";
            String biome = this.settings.showBiome ? biomeText(player, this.settings.sizePixels) : "";
            String statusKey = coords + "|" + compass + "|" + clock + "|" + biome;
            if (!statusKey.equals(this.lastCoords)) {
                this.lastCoords = statusKey;
                this.hud.setStatus(coords, compass, clock, biome);
            }
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
            this.hud = new DynamicMinimapHud(this.playerRef, this.settings, work -> executeWorld(world, "overlay delivery", work));
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
            this.northUpTerrainPng = null;
            this.displayedTerrainPng = null;
            this.terrainPublications.clear();
            this.hud.deliverStaticAssets();
            this.hud.show();
            this.renderedColours = this.settings.colours;
            this.bufferCenterX = Double.NaN;
            this.bufferCenterZ = Double.NaN;
            this.lastTerrainMs = 0L;
            this.lastSelfBucket = -1;
            this.lastCoords = null;
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
                        if (rendered == null) return;
                        executeWorld(world, "self arrow delivery", () -> {
                            if (!this.running || this.hud != targetHud || !targetHud.isShown()
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
            long result = signature;
            executeWorld(world, "signature result", () -> {
                if (!this.running || probeHud != this.hud
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
            if (!this.running) {
                return;
            }

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

            byte[] displayPng = renderSettings.rotationMode == 1
                    ? DynamicMinimapRenderer.rotateTerrain(northUpPng, mapAngle, renderSettings.colours)
                    : northUpPng;

            if (displayPng == null || displayPng.length == 0) {
                return;
            }

            DynamicAssetPublisher.PreparedAsset prepared = DynamicAssetPublisher.prepare(
                    this.terrainAssetPath(renderSettings.textureResolution, this.frameIndex.incrementAndGet()), displayPng);
            // Hash the stitched map off the world thread as well as rendering its PNG.
            long renderSignature = DynamicMinimapRenderer.terrainSignature(
                    world.getWorldMapManager(), centerX, centerZ, renderSettings, caveView);
            executeWorld(world, "terrain delivery", () -> {
                if (!this.running
                        || renderHud != this.hud
                        || !java.util.Objects.equals(caveView, this.lastCaveView)
                        || sequence <= this.deliveredSequence
                        || this.settings.textureResolution != renderSettings.textureResolution) {
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

                this.deliveredSequence = sequence;
                this.northUpTerrainPng = northUpPng;
                ++this.terrainGeneration;

                this.displayedTerrainPng = displayPng;
                this.bufferComplete = terrain.complete();
                this.bufferCenterX = centerX;
                this.bufferCenterZ = centerZ;
                this.lastSignature = renderSignature;
                this.updateRotatingFrame(rotationBucket);
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
        if (this.northUpTerrainPng == null
                || Double.isNaN(this.bufferCenterX)
                || this.hud == null
                || !this.hud.isShown()) {
            return;
        }

        byte[] basePng = this.northUpTerrainPng;
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
                    DynamicMinimapRenderer.rotateTerrain(basePng, mapAngle, renderSettings.colours);
            if (displayPng == null || displayPng.length == 0) {
                return;
            }

            DynamicAssetPublisher.PreparedAsset prepared = DynamicAssetPublisher.prepare(
                    this.terrainAssetPath(renderSettings.textureResolution, this.frameIndex.incrementAndGet()), displayPng);
            executeWorld(world, "rotation delivery", () -> {
                if (!this.running
                        || renderHud != this.hud
                        || generation != this.terrainGeneration
                        || rotationRequest != this.rotationSequence.get()
                        || this.settings.rotationMode != expectedRotationMode) {
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

                this.displayedTerrainPng = displayPng;
                this.updateRotatingFrame(rotationBucket);
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

        if (this.settings.showWorldMapMarkers) {
            Player player = store.getComponent(playerRef, Player.getComponentType());
            long now = System.currentTimeMillis();
            if (player != null && now - this.lastWorldMapScanMs >= 1000L) {
                this.lastWorldMapScanMs = now;
                try {
                    List<DynamicMinimapMarker> current = NativeWorldMapIntegration.getMarkers(
                            store.getExternalData().getWorld(),
                            player, this.settings.colours);
                    if (current.isEmpty() && !this.cachedWorldMapMarkers.isEmpty()
                            && ++this.emptyWorldMapScans < 3) {
                        // A provider can briefly return no markers during a map refresh.
                    } else {
                        this.cachedWorldMapMarkers = current;
                        this.emptyWorldMapScans = 0;
                    }
                } catch (Throwable ignored) { }
            }
            markers.addAll(this.cachedWorldMapMarkers);
        } else {
            this.cachedWorldMapMarkers = new ArrayList<>();
            this.emptyWorldMapScans = 0;
            this.lastWorldMapScanMs = 0L;
        }

        if (this.settings.betterMapEnabled && this.settings.betterMapMarkersEnabled
                && BetterMapIntegration.isAvailable()) {
            long now = System.currentTimeMillis();
            if (now - this.lastBetterMapScanMs >= 750L) {
                this.lastBetterMapScanMs = now;
                Player player = store.getComponent(playerRef, Player.getComponentType());
                List<DynamicMinimapMarker> current = BetterMapIntegration.getMarkers(player);
                if (current != null) {
                    if (current.isEmpty() && !this.cachedBetterMapMarkers.isEmpty()
                            && ++this.emptyBetterMapScans < 3) {
                        // Wait for a repeated empty snapshot before removing visible waypoints.
                    } else {
                        this.cachedBetterMapMarkers = new ArrayList<>(current);
                        this.emptyBetterMapScans = 0;
                    }
                }
            }
            markers.addAll(this.cachedBetterMapMarkers);
        } else {
            this.cachedBetterMapMarkers = new ArrayList<>();
            this.emptyBetterMapScans = 0;
            this.lastBetterMapScanMs = 0L;
        }

        this.cachedMarkers = markers;
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
        int panLeft = this.hud.panLeft();
        int panTop = this.hud.panTop();

        // The native POI provider returns world-wide markers from a map with no
        // useful display order. Select nearby markers before the fixed HUD slots
        // are exhausted by distant POIs clamped to the edge.
        List<DynamicMinimapMarker> orderedMarkers = this.markerOrder.get(
                this.cachedMarkers, this.bufferCenterX, this.bufferCenterZ);

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
                double[] clamped = this.clampMarkerToVisibleMap(
                        markerX + panLeft,
                        markerZ + panTop,
                        size
                );
                markerX = clamped[0] - panLeft;
                markerZ = clamped[1] - panTop;
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
                            size
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
        double mapSize = this.settings.sizePixels;
        double center = mapSize / 2.0D;
        double inset = Math.max(3.0D, markerSize / 2.0D + 2.0D);
        double dx = x - center;
        double dz = z - center;

        if (this.settings.isCircle()) {
            double radius = Math.max(1.0D, center - inset);
            double distance = Math.hypot(dx, dz);
            if (distance <= radius || distance < 0.0001D) {
                return new double[]{x, z};
            }

            double scale = radius / distance;
            return new double[]{
                    center + dx * scale,
                    center + dz * scale
            };
        }

        double halfExtent = Math.max(1.0D, center - inset);
        if (Math.abs(dx) <= halfExtent && Math.abs(dz) <= halfExtent) {
            return new double[]{x, z};
        }

        double scaleX = Math.abs(dx) < 0.0001D
                ? Double.POSITIVE_INFINITY
                : halfExtent / Math.abs(dx);
        double scaleZ = Math.abs(dz) < 0.0001D
                ? Double.POSITIVE_INFINITY
                : halfExtent / Math.abs(dz);
        double scale = Math.min(scaleX, scaleZ);

        return new double[]{
                center + dx * scale,
                center + dz * scale
        };
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

            int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
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
        return String.format("%d, %d, %d", (int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
    }

    private static String compassText(double yaw) {
        if (Double.isNaN(yaw)) {
            return "—°  ?";
        } else {
            int degrees = Math.floorMod((int)Math.round(Math.toDegrees(yaw)), 360);
            String[] directions = new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            int index = (int)Math.floor(((double)degrees + (double)22.5F) / (double)45.0F) & 7;
            return degrees + "°  " + directions[index];
        }
    }

    private static String clockText(World world, boolean twentyFourHour) {
        WorldTimeResource worldTime = getWorldTime(world);
        if (worldTime != null) {
            LocalDateTime gameDateTime = worldTime.getGameDateTime();
            if (gameDateTime != null) {
                int secondsOfDay = gameDateTime.toLocalTime().toSecondOfDay();
                return formatClock(gameDateTime.getHour(), gameDateTime.getMinute(), isSunlit(secondsOfDay), twentyFourHour);
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
        return formatClock(hour, minute, isDay, twentyFourHour);
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
            body = String.format("%02d:%02d", hour, minute);
        } else {
            int displayHour = hour % 12;
            if (displayHour == 0) {
                displayHour = 12;
            }

            body = String.format("%d:%02d %s", displayHour, minute, hour < 12 ? "AM" : "PM");
        }

        return (isDay ? "DAY  " : "NIGHT  ") + body;
    }

    private static String biomeText(Player player, int mapSize) {
        String biome = null;

        try {
            if (player != null && player.getWorldMapTracker() != null) {
                biome = player.getWorldMapTracker().getCurrentBiomeName();
            }
        } catch (RuntimeException var6) {
        }

        String label = prettyName(biome);
        int maxChars = 22;
        if (label.length() <= maxChars) {
            return label;
        } else {
            String var10000 = label.substring(0, Math.max(0, maxChars - 3));
            return var10000 + "...";
        }
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
}

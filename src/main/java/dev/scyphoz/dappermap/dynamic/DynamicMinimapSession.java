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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.joml.Vector3d;

public final class DynamicMinimapSession {
    private static final long INITIAL_DELAY_MS = 500L;
    private static final double RECENTER_FRAC = 0.8;
    private static final long WARMUP_MS = 1000L;
    private static final long WARMUP_TOTAL_MS = 15000L;
    private static final int SELF_BUCKETS = 72;
    private static final ExecutorService RENDER_POOL = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 4), (runnable) -> {
        Thread thread = new Thread(runnable, "DapperMap-Render");
        thread.setDaemon(true);
        return thread;
    });
    private final PlayerRef playerRef;
    private final UUID uuid;
    private final String slug;
    private final DynamicMinimapSetting settings;
    private volatile ScheduledFuture<?> task;
    private volatile boolean running = false;
    private DynamicMinimapHud hud;
    private int builtSize = -1;
    private int builtShape = -1;
    private int builtZoom = -1;
    private int builtPosition = -1;
    private int builtInterfaceScale = -1;
    private int builtTextureResolution = -1;
    private boolean firstTerrainForHud = false;
    private double bufferCenterX = Double.NaN;
    private double bufferCenterZ = Double.NaN;
    private final AtomicInteger frameIndex = new AtomicInteger();
    private final AtomicLong renderSequence = new AtomicLong();
    private long deliveredSequence = 0L;
    private long lastTickMs = 0L;
    private long lastSignature = 0L;
    private long lastSignatureMs = 0L;
    private long lastMarkerMs = 0L;
    private long lastTerrainMs = 0L;
    private boolean bufferComplete = false;
    private long warmupUntilMs = 0L;
    private int lastSelfBucket = -1;
    private final byte[][] selfArrowCache = new byte[72][];
    private String lastCoords = null;
    private final Map<Integer, String> iconCache = new HashMap();
    private String deathIconPath = null;
    private boolean wasAlive = true;
    private boolean hasDeath = false;
    private double deathX;
    private double deathZ;

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
            this.task = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::tickSafe, 500L, 50L, TimeUnit.MILLISECONDS);
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
            this.hud.removeFromClient();
        }

        DynamicAssetPublisher.clear(this.uuid);
    }

    private void tickSafe() {
        try {
            long now = System.currentTimeMillis();
            if (now - this.lastTickMs < this.settings.panIntervalMs()) {
                return;
            }

            this.lastTickMs = now;
            this.tick();
        } catch (Throwable var3) {
        }

    }

    private void tick() {
        if (this.running) {
            for(World world : Universe.get().getWorlds().values()) {
                if (world.getPlayerCount() > 0) {
                    world.execute(() -> {
                        if (this.running) {
                            Ref<EntityStore> ref = world.getEntityRef(this.uuid);
                            if (ref != null && ref.isValid()) {
                                this.renderTick(world, ref);
                            }
                        }

                    });
                }
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
            Rotation3f rotation = transform.getRotation();
            double yaw = rotation == null ? Double.NaN : (double)rotation.yaw();
            if (!this.settings.enabled) {
                if (this.hud != null && this.hud.isShown()) {
                    this.hud.removeFromClient();
                }

                return;
            }

            this.ensureHud();
            if (this.hud == null || !this.hud.isShown()) {
                return;
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
            }

            if (this.settings.interfaceScalePercent != this.builtInterfaceScale) {
                this.builtInterfaceScale = this.settings.interfaceScalePercent;
                this.hud.setInterfaceScale(this.settings.interfaceScalePercent);
            }

            if (this.settings.position != this.builtPosition) {
                this.builtPosition = this.settings.position;
                this.hud.setPosition(this.settings.position);
            }

            long now = System.currentTimeMillis();
            this.detectDeath(store, ref, x, z);
            this.updateSelfArrow(yaw);
            double pixelsPerBlock = DynamicMinimapRenderer.pixelsPerBlock(this.settings);
            int bufferPixels = this.hud.bufferPixels();
            int mapCenter = this.settings.sizePixels / 2;
            double panMargin = (double)DynamicMinimapRenderer.panMarginPixels(this.settings);
            boolean signatureChanged = false;
            if (!Double.isNaN(this.bufferCenterX) && now - this.lastSignatureMs > this.settings.signatureIntervalMs()) {
                this.lastSignatureMs = now;
                long signature = DynamicMinimapRenderer.terrainSignature(world.getWorldMapManager(), this.bufferCenterX, this.bufferCenterZ, this.settings);
                if (signature != this.lastSignature) {
                    this.lastSignature = signature;
                    signatureChanged = true;
                }
            }

            boolean noBuffer = Double.isNaN(this.bufferCenterX);
            boolean needsRecenter = !noBuffer && (Math.abs(x - this.bufferCenterX) * pixelsPerBlock > panMargin * 0.8 || Math.abs(z - this.bufferCenterZ) * pixelsPerBlock > panMargin * 0.8);
            boolean warmupRetry = !this.bufferComplete && now < this.warmupUntilMs && !noBuffer && now - this.lastTerrainMs >= 1000L;
            boolean needsTerrain = noBuffer || needsRecenter || signatureChanged || warmupRetry;
            if (needsTerrain && now - this.lastTerrainMs >= this.settings.terrainIntervalMs()) {
                if (noBuffer || needsRecenter) {
                    this.warmupUntilMs = now + 15000L;
                    this.bufferComplete = false;
                }

                this.lastTerrainMs = now;
                this.scheduleTerrainRender(world, x, z);
            } else if (!noBuffer) {
                int left = (int)Math.round((double)mapCenter - (double)bufferPixels / (double)2.0F - (x - this.bufferCenterX) * pixelsPerBlock);
                int top = (int)Math.round((double)mapCenter - (double)bufferPixels / (double)2.0F - (z - this.bufferCenterZ) * pixelsPerBlock);
                this.hud.pan(left, top);
            }

            if (!Double.isNaN(this.bufferCenterX) && now - this.lastMarkerMs > this.settings.markerIntervalMs()) {
                this.lastMarkerMs = now;
                this.updateMarkers(store, ref, position, pixelsPerBlock, bufferPixels);
            }

            String coords = coordsText(x, y, z);
            String compass = this.settings.showCompass ? compassText(yaw) : "";
            String clock = this.settings.showClock ? clockText(world, this.settings.clock24Hour) : "";
            String biome = this.settings.showBiome ? biomeText(player, this.settings.sizePixels) : "";
            String statusKey = coords + "|" + compass + "|" + clock + "|" + biome;
            if (!statusKey.equals(this.lastCoords)) {
                this.lastCoords = statusKey;
                this.hud.setStatus(coords, compass, clock, biome);
            }
        } catch (Throwable var34) {
        }

    }

    private void ensureHud() {
        if (this.hud != null
                && this.hud.isShown()
                && this.settings.shape == this.builtShape
                && this.settings.textureResolution == this.builtTextureResolution) {
            if (this.settings.zoomBlocks != this.builtZoom) {
                this.builtZoom = this.settings.zoomBlocks;
                this.bufferCenterX = Double.NaN;
            }
        } else {
            if (this.hud != null && this.hud.isShown()) {
                this.hud.removeFromClient();
            }

            this.settings.normalize();
            this.hud = new DynamicMinimapHud(this.playerRef, this.settings);
            this.builtSize = this.settings.sizePixels;
            this.builtShape = this.settings.shape;
            this.builtZoom = this.settings.zoomBlocks;
            this.builtPosition = this.settings.position;
            this.builtInterfaceScale = this.settings.interfaceScalePercent;
            this.builtTextureResolution = this.settings.textureResolution;
            this.hud.deliverStaticAssets();
            this.hud.show();
            this.bufferCenterX = Double.NaN;
            this.bufferCenterZ = Double.NaN;
            this.lastTerrainMs = 0L;
            this.firstTerrainForHud = true;
            this.lastSelfBucket = -1;
            this.lastCoords = null;
        }

    }

    private void updateSelfArrow(double yaw) {
        if (!Double.isNaN(yaw) && this.hud != null) {
            double angle = DynamicMinimapRenderer.screenAngleForYaw(yaw);
            int bucket = (int)Math.round(angle / (Math.PI * 2D) * (double)72.0F);
            bucket = (bucket % 72 + 72) % 72;
            if (bucket != this.lastSelfBucket) {
                this.lastSelfBucket = bucket;
                String path = "UI/DapperMap/self-" + this.slug + "-" + bucket + ".png";
                byte[] png = this.selfArrowCache[bucket];
                if (png == null) {
                    png = DynamicMinimapRenderer.renderSelfArrow((double)bucket * (Math.PI * 2D) / (double)72.0F);
                    this.selfArrowCache[bucket] = png;
                }

                if (png != null) {
                    DynamicAssetPublisher.deliver(this.playerRef, path, png);
                    this.hud.setSelf(path);
                }
            }
        }

    }

    private void scheduleTerrainRender(World world, double centerX, double centerZ) {
        long sequence = this.renderSequence.incrementAndGet();
        DynamicMinimapSetting renderSettings = this.settings.copy();
        DynamicMinimapHud renderHud = this.hud;
        RENDER_POOL.execute(() -> {
            if (this.running) {
                DynamicMinimapRenderer.Terrain terrain = DynamicMinimapRenderer.renderTerrain(world.getWorldMapManager(), centerX, centerZ, renderSettings);
                byte[] png = terrain.png;
                if (png != null && png.length != 0) {
                    world.execute(() -> {
                        if (this.running && renderHud == this.hud && sequence > this.deliveredSequence) {
                            this.deliveredSequence = sequence;
                            int frame = this.frameIndex.incrementAndGet();
                            String path = "UI/DapperMap/terrain-" + this.slug + "-" + (frame & 15) + ".png";
                            boolean delivered = this.firstTerrainForHud ? DynamicAssetPublisher.deliverWithRebuild(this.playerRef, path, png) : DynamicAssetPublisher.deliver(this.playerRef, path, png);
                            if (delivered) {
                                this.firstTerrainForHud = false;
                                this.bufferComplete = terrain.complete();
                                this.bufferCenterX = centerX;
                                this.bufferCenterZ = centerZ;
                                this.lastSignature = DynamicMinimapRenderer.terrainSignature(world.getWorldMapManager(), centerX, centerZ, renderSettings);
                                double pixelsPerBlock = DynamicMinimapRenderer.pixelsPerBlock(renderSettings);
                                int bufferPixels = this.hud.bufferPixels();
                                int mapCenter = renderSettings.sizePixels / 2;
                                int[] pan = this.currentPan(world, centerX, centerZ, pixelsPerBlock, bufferPixels, mapCenter);
                                this.hud.setTerrain(path, pan[0], pan[1]);
                                this.lastMarkerMs = 0L;
                            }
                        }

                    });
                }
            }

        });
    }

    private int[] currentPan(World world, double centerX, double centerZ, double pixelsPerBlock, int bufferPixels, int mapCenter) {
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

        int left = (int)Math.round((double)mapCenter - (double)bufferPixels / (double)2.0F - (currentX - centerX) * pixelsPerBlock);
        int top = (int)Math.round((double)mapCenter - (double)bufferPixels / (double)2.0F - (currentZ - centerZ) * pixelsPerBlock);
        return new int[]{left, top};
    }

    private void updateMarkers(Store<EntityStore> store, Ref<EntityStore> playerRef, Vector3d playerPosition, double pixelsPerBlock, int bufferPixels) {
        List<DynamicMinimapMarker> markers;
        try {
            markers = DynamicEntityScanner.scan(store, playerRef, playerPosition, (double)(this.settings.zoomBlocks + 16), this.settings);
        } catch (Throwable var20) {
            markers = new ArrayList();
        }

        if (this.settings.showDeathMarker && this.hasDeath) {
            markers.add(DynamicMinimapMarker.death(this.deathX, this.deathZ, 12));
        }

        DynamicMinimapHud.Slot[] slots = new DynamicMinimapHud.Slot[48];
        int slot = 0;

        for(DynamicMinimapMarker marker : markers) {
            if (slot >= slots.length) {
                break;
            }

            double markerX = (marker.x - this.bufferCenterX) * pixelsPerBlock + (double)bufferPixels / (double)2.0F;
            double markerZ = (marker.z - this.bufferCenterZ) * pixelsPerBlock + (double)bufferPixels / (double)2.0F;
            if (!(markerX < (double)0.0F) && !(markerX > (double)bufferPixels) && !(markerZ < (double)0.0F) && !(markerZ > (double)bufferPixels)) {
                String path = marker.style == Style.DEATH ? this.deathIcon() : this.colorIcon(marker.color.getRGB() & 16777215);
                if (path != null) {
                    int size = Math.max(8, marker.size);
                    int left = (int)Math.round(markerX) - size / 2;
                    int top = (int)Math.round(markerZ) - size / 2;
                    slots[slot++] = new DynamicMinimapHud.Slot(path, left, top, size);
                }
            }
        }

        this.hud.setMarkers(slots);
    }

    private String colorIcon(int rgb) {
        String path = (String)this.iconCache.get(rgb);
        if (path != null) {
            return path;
        } else {
            byte[] png = DynamicMinimapRenderer.renderDot(rgb);
            if (png == null) {
                return null;
            } else {
                String var10000 = this.slug;
                path = "UI/DapperMap/mk-" + var10000 + "-" + String.format("%06X", rgb) + ".png";
                DynamicAssetPublisher.deliver(this.playerRef, path, png);
                this.iconCache.put(rgb, path);
                return path;
            }
        }
    }

    private String deathIcon() {
        if (this.deathIconPath != null) {
            return this.deathIconPath;
        } else {
            byte[] png = DynamicMinimapRenderer.renderDeath();
            if (png == null) {
                return null;
            } else {
                this.deathIconPath = "UI/DapperMap/death-" + this.slug + ".png";
                DynamicAssetPublisher.deliver(this.playerRef, this.deathIconPath, png);
                return this.deathIconPath;
            }
        }
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
        return String.format("X %d   Y %d   Z %d", (int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
    }

    private static String compassText(double yaw) {
        if (Double.isNaN(yaw)) {
            return "?: --- deg";
        } else {
            int degrees = Math.floorMod((int)Math.round(Math.toDegrees(yaw)), 360);
            String[] directions = new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            int index = (int)Math.floor(((double)degrees + (double)22.5F) / (double)45.0F) & 7;
            return directions[index] + " " + degrees + " deg";
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

        return (isDay ? "Sun " : "Moon ") + body;
    }

    private static String biomeText(Player player, int mapSize) {
        String biome = null;

        try {
            if (player != null && player.getWorldMapTracker() != null) {
                biome = player.getWorldMapTracker().getCurrentBiomeName();
            }
        } catch (RuntimeException var6) {
        }

        String label = "Biome: " + prettyName(biome);
        int maxChars = Math.max(16, mapSize / 7);
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

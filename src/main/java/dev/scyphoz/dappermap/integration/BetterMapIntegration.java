package dev.scyphoz.dappermap.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import dev.scyphoz.dappermap.dynamic.DynamicAssetPublisher;
import dev.scyphoz.dappermap.util.DapperMapUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Optional BetterMap compatibility layer.
 *
 * DapperMap deliberately does not link against BetterMap at compile time. Cave mode and
 * BetterMap's icons are reached reflectively through its own plugin class loader, and its
 * waypoints arrive through its world-map provider, keeping DapperMap usable as a standalone mod.
 */
public final class BetterMapIntegration {
    private static final PluginIdentifier BETTERMAP_ID =
            new PluginIdentifier("dev.ninesliced", "BetterMap");
    private static final String ICON_RESOURCE_PREFIX = "Common/UI/Custom/Common/";

    private static volatile ClassLoader resolvedLoader;
    private static final Map<String, byte[]> ICON_BYTES = new ConcurrentHashMap<>();

    private BetterMapIntegration() {
    }

    /** How long a BetterMap availability answer is reused before the plugin is looked up again. */
    private static final long AVAILABILITY_RECHECK_MS = 1000L;
    private static volatile boolean available;
    private static volatile long availabilityCheckedMs; // 0: the first call always looks BetterMap up.

    /**
     * Returns true only when an enabled BetterMap instance is installed.
     * Called on every marker scan and cave check for every player, so the answer is reused for
     * up to a second: {@code PluginManager.getPlugin} takes a server-wide read lock on each call.
     */
    public static boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - availabilityCheckedMs < AVAILABILITY_RECHECK_MS) return available;
        boolean result = resolve() != null;
        available = result;
        availabilityCheckedMs = now;
        return result;
    }

    /**
     * Prepares a BetterMap waypoint icon as a DapperMap dynamic asset. BetterMap's own icon
     * resources are read through its plugin class loader (no artwork is bundled with
     * DapperMap); Hytale's common marker assets are the fallback for waypoints using
     * Hytale's own icon names. Waypoints themselves are collected by
     * NativeWorldMapIntegration from BetterMap's world-map provider. Off the world thread.
     */
    public static DynamicAssetPublisher.PreparedAsset prepareIconAsset(
            String icon, java.awt.Color tint, String playerSlug) {
        String image = NativeWorldMapIntegration.markerImage(icon);
        if (playerSlug == null || playerSlug.isBlank() || image == null) return null;
        String filename = NativeWorldMapIntegration.fileName(image);
        byte[] bytes = loaderIcon(filename);
        if (bytes == null) bytes = NativeWorldMapIntegration.registryIcon(image);
        if (bytes == null) { NativeWorldMapIntegration.reportMissing(image); return null; }
        return NativeWorldMapIntegration.publishTinted("bettermap-", filename, bytes, tint, playerSlug);
    }

    /** Raw bytes of one of BetterMap's bundled icons, or null when unavailable. */
    static byte[] loaderIcon(String filename) {
        if (filename == null || !isAvailable()) return null;
        ClassLoader loader = resolvedLoader;
        if (loader == null) return null;
        byte[] bytes = ICON_BYTES.get(filename);
        if (bytes != null) return bytes;
        try (InputStream stream = loader.getResourceAsStream(ICON_RESOURCE_PREFIX + filename)) {
            if (stream == null) return null;
            bytes = stream.readAllBytes();
            if (bytes.length == 0) return null;
            if (ICON_BYTES.size() >= 256) ICON_BYTES.clear();
            ICON_BYTES.put(filename, bytes);
            return bytes;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * BetterMap's class loader when an enabled BetterMap is installed, else null. Only the plugin
     * itself is required: DapperMap reads waypoints through BetterMap's world-map provider and
     * resolves the cave-mode methods separately, so a change to an unrelated BetterMap class
     * (previously WaypointManager.getUserMarkers, never called) can no longer switch the whole
     * integration off.
     */
    private static ClassLoader resolve() {
        try {
            PluginManager pluginManager = PluginManager.get();
            PluginBase plugin = pluginManager == null ? null : pluginManager.getPlugin(BETTERMAP_ID);
            if (plugin == null || !plugin.isEnabled()) {
                clearResolved();
                return null;
            }
            ClassLoader loader = plugin.getClass().getClassLoader();
            if (loader != resolvedLoader) {
                ICON_BYTES.clear(); // A reloaded BetterMap may ship different icons.
                resolvedLoader = loader;
            }
            return loader;
        } catch (RuntimeException | LinkageError ignored) {
            clearResolved();
            return null;
        }
    }

    private static void clearResolved() {
        if (resolvedLoader != null) {
            ICON_BYTES.clear();
        }
        resolvedLoader = null;
    }

    /**
     * Multiplies every pixel of a PNG icon by {@code rgb} (alpha kept). Reads and writes whole
     * pixel rows at once and encodes in memory. Render pool only; results are cached by callers.
     */
    static byte[] tintPng(byte[] source, int rgb) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                return null;
            }

            int tintR = (rgb >>> 16) & 0xFF;
            int tintG = (rgb >>> 8) & 0xFF;
            int tintB = rgb & 0xFF;
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            for (int i = 0; i < pixels.length; i++) {
                int argb = pixels[i];
                int r = ((argb >>> 16) & 0xFF) * tintR / 255;
                int g = ((argb >>> 8) & 0xFF) * tintG / 255;
                int b = (argb & 0xFF) * tintB / 255;
                pixels[i] = (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
            }

            BufferedImage tinted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            tinted.setRGB(0, 0, width, height, pixels, 0, width);
            return DapperMapUtil.encodePng(tinted, 2048);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static final BoundedImageRequests<Key, MapImage> IMAGES =
            new BoundedImageRequests<>(512, 16, 8, 100L, 30000L, System::currentTimeMillis);
    private static volatile ClassLoader caveLoader;
    private static volatile Method managerInstance, enabled, state, underground, renderY, verticalRange, build, image;

    /** BetterMap's own global and per-player cave-mode switch. */
    public static boolean isCaveModeEnabled(Player player) {
        if (player == null || !isAvailable()) return false;
        try {
            resolve(resolvedLoader);
            return Boolean.TRUE.equals(enabled.invoke(null, player));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Capture cave mode on the world thread before handing an image supplier to the render pool. */
    public static CaveView caveView(Player player, World world) {
        if (player == null || world == null || !isAvailable()) return null;
        try {
            ClassLoader current = resolvedLoader;
            if (current == null) return null;
            resolve(current);
            if (!Boolean.TRUE.equals(enabled.invoke(null, player))) return null;
            Object manager = managerInstance.invoke(null);
            if (!Boolean.TRUE.equals(underground.invoke(manager, player))) return null;
            Object currentState = state.invoke(manager, player);
            if (currentState == null) return null;
            int y = (Integer) renderY.invoke(currentState);
            int range = (Integer) verticalRange.invoke(currentState);
            if (range <= 0) return null;
            // BetterMap's builder takes an image width and height after the chunk index.
            // Its world map overlay uses floor(32 * imageScale) for both dimensions.
            int pixels = Math.max(1, (int)Math.floor(32.0F * world.getWorldMapManager()
                    .getWorldMapSettings().getImageScale()));
            return new CaveView(world, y, range, pixels);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static void resolve(ClassLoader current) throws ReflectiveOperationException {
        // Lock-free fast path for the usual case (already resolved for this loader); caveLoader is
        // written last below, so seeing it means every method handle is already published.
        if (current == caveLoader && build != null) return;
        resolveLocked(current);
    }

    private static synchronized void resolveLocked(ClassLoader current) throws ReflectiveOperationException {
        if (current == caveLoader && build != null) return;
        Class<?> caveManager = Class.forName("dev.ninesliced.managers.CaveModeManager", false, current);
        Class<?> caveState = Class.forName("dev.ninesliced.managers.CaveModeManager$DynamicCaveModeState", false, current);
        Class<?> builder = Class.forName("dev.ninesliced.providers.CaveModeImageBuilder", false, current);
        managerInstance = caveManager.getMethod("getInstance");
        enabled = caveManager.getMethod("isEffectivelyEnabledForPlayer", Player.class);
        state = caveManager.getMethod("getState", Player.class);
        underground = caveManager.getMethod("isPlayerUnderground", Player.class);
        renderY = caveState.getMethod("getRenderYLevel");
        verticalRange = caveState.getMethod("getVerticalRange");
        build = builder.getMethod("build", long.class, int.class, int.class, World.class, int.class, int.class);
        image = builder.getMethod("getImage");
        IMAGES.clear();
        caveLoader = current;
    }

    public static void clearWorld(World world) {
        if (world != null) IMAGES.removeIf(key -> key.world == world);
    }

    private static final java.util.concurrent.atomic.AtomicLong lastImagePrune = new java.util.concurrent.atomic.AtomicLong();

    public static void pruneImages() {
        long now = System.currentTimeMillis();
        long previous = lastImagePrune.get();
        if (now - previous >= 1000L && lastImagePrune.compareAndSet(previous, now)) {
            IMAGES.prune(key -> !key.world.isAlive());
        }
    }

    public static void clearImages() { IMAGES.clear(); }

    public record CaveView(World world, int y, int range, int pixels) implements BiFunction<Integer, Integer, MapImage> {
        /** Signature checks inspect ready images without starting chunk loads. */
        public MapImage peek(Integer x, Integer z) {
            return IMAGES.peek(new Key(world, y, range, pixels, x, z));
        }

        @Override public MapImage apply(Integer x, Integer z) {
            if (!world.isAlive()) return null;
            pruneImages();
            Key key = new Key(world, y, range, pixels, x, z);
            return IMAGES.request(key, () -> {
                try {
                    CompletableFuture<?> builder = (CompletableFuture<?>) build.invoke(
                            null, ChunkUtil.indexChunk(x, z), pixels, pixels, world, y, range);
                    // The builder retains WorldChunk and sections. Store only its
                    // finished MapImage, releasing those references on completion.
                    return builder.thenApply(result -> {
                        if (result == null) return null;
                        try { return (MapImage) image.invoke(result); }
                        catch (ReflectiveOperationException e) { throw new java.util.concurrent.CompletionException(e); }
                    });
                } catch (ReflectiveOperationException | LinkageError e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
        }
    }

    private record Key(World world, int y, int range, int pixels, int x, int z) {}

    /** Shared admission limits; completed values retain images, never rendering builders. */
    public static final class BoundedImageRequests<K, V> {
        private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();
        private final LongSupplier clock;
        private final int capacity, maxPending, startsPerWindow;
        private final long ttlMs, windowMs;
        private int pending, starts;
        private long windowStart;

        public BoundedImageRequests(int capacity, int maxPending, int startsPerWindow,
                                    long windowMs, long ttlMs, LongSupplier clock) {
            this.capacity=capacity; this.maxPending=maxPending; this.startsPerWindow=startsPerWindow;
            this.windowMs=windowMs; this.ttlMs=ttlMs; this.clock=clock;
        }

        public synchronized V peek(K key) {
            Entry<V> entry=entries.get(key);
            if(entry==null || !entry.future.isDone()) return null;
            if(entry.future.isCompletedExceptionally() || entry.future.isCancelled()
                    || clock.getAsLong()-entry.completedAt >= ttlMs) {
                entries.remove(key); return null;
            }
            return entry.future.getNow(null);
        }

        /**
         * Starts (or returns) the image for {@code key}. A slot is reserved under the lock, but the
         * builder ({@code start}, BetterMap code that touches the chunk store) runs outside it, so
         * other render threads and the signature probe never wait behind a BetterMap build.
         */
        public V request(K key, Supplier<CompletableFuture<V>> start) {
            Entry<V> placeholder;
            synchronized (this) {
                V cached=peek(key);
                if(cached!=null || entries.containsKey(key)) return cached;
                long now=clock.getAsLong();
                if(now-windowStart>=windowMs) { windowStart=now; starts=0; }
                if(pending>=maxPending || starts>=startsPerWindow) return null;
                if(entries.size()>=capacity) {
                    var iterator=entries.entrySet().iterator();
                    boolean removed=false;
                    while(iterator.hasNext()) {
                        if(iterator.next().getValue().future.isDone()) {iterator.remove();removed=true;break;}
                    }
                    if(!removed) return null;
                }
                ++starts; ++pending;
                // Reserve the key: concurrent requests see it as pending and do not start a second build.
                placeholder=new Entry<>(new CompletableFuture<>());
                entries.put(key,placeholder);
            }
            CompletableFuture<V> future;
            try { future=start.get(); if(future==null) throw new IllegalStateException("Missing image future"); }
            catch(RuntimeException e) {
                synchronized (this) { --pending; entries.remove(key,placeholder); } // Identity: never a newer entry.
                return null;
            }
            future.whenComplete((value,error)-> {
                synchronized(this) {
                    --pending;
                    placeholder.completedAt=clock.getAsLong();
                    // Identity remove: a clear()/removeIf racing this build must not drop a newer entry.
                    if(error!=null || value==null) entries.remove(key,placeholder);
                }
                // Completed outside the lock; peek reads the placeholder's result.
                if(error!=null) placeholder.future.completeExceptionally(error); else placeholder.future.complete(value);
            });
            synchronized (this) { return peek(key); }
        }

        public synchronized void prune(Predicate<K> dead) {
            long now=clock.getAsLong();
            entries.entrySet().removeIf(e->dead.test(e.getKey())
                    || (e.getValue().future.isDone() && now-e.getValue().completedAt>=ttlMs));
        }
        public synchronized void removeIf(Predicate<K> predicate) { entries.keySet().removeIf(predicate); }
        public synchronized void clear() { entries.clear(); }
        private static final class Entry<V> {
            final CompletableFuture<V> future;
            long completedAt;
            Entry(CompletableFuture<V> future) {this.future=future;}
        }
    }
}

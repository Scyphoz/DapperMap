package dev.scyphoz.dappermap.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.user.UserMapMarker;
import dev.scyphoz.dappermap.dynamic.DynamicAssetPublisher;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapMarker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * DapperMap deliberately does not link against BetterMap at compile time. The
 * marker accessor is resolved reflectively through BetterMap's own plugin class
 * loader, keeping DapperMap usable as a standalone mod.
 */
public final class BetterMapIntegration {
    private static final PluginIdentifier BETTERMAP_ID =
            new PluginIdentifier("dev.ninesliced", "BetterMap");
    private static final String WAYPOINT_MANAGER = "dev.ninesliced.managers.WaypointManager";
    private static final String ICON_RESOURCE_PREFIX = "Common/UI/Custom/Common/";
    private static final int MARKER_SIZE = 20;

    private static volatile ClassLoader resolvedLoader;
    private static volatile Method getUserMarkers;
    private static final Map<String, byte[]> ICON_BYTES = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> TINTED_ICON_BYTES = new ConcurrentHashMap<>();

    private BetterMapIntegration() {
    }

    /** Returns true only when an enabled BetterMap instance and its waypoint API are available. */
    public static boolean isAvailable() {
        return resolve() != null;
    }

    /** Reads the player's markers; null means the scan failed and the last snapshot should be kept. */
    public static List<DynamicMinimapMarker> getMarkers(Player player) {
        if (player == null) return null;

        Method method = resolve();
        if (method == null) return null;

        try {
            Object value = method.invoke(null, player);
            if (!(value instanceof List<?> source)) return null;
            if (source.isEmpty()) return Collections.emptyList();

            List<DynamicMinimapMarker> result = new ArrayList<>(source.size());
            for (Object item : source) {
                if (!(item instanceof UserMapMarker marker)) {
                    continue;
                }

                String icon = sanitizeIcon(marker.getIcon());
                if (icon == null) {
                    continue;
                }

                java.awt.Color tint = toAwtColor(marker.getColorTint());
                result.add(DynamicMinimapMarker.betterMap(
                        marker.getX(),
                        marker.getZ(),
                        icon,
                        tint,
                        MARKER_SIZE
                ));
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Resolves one of BetterMap's icon resources through BetterMap's own class
     * loader and republishes the original bytes as a DapperMap dynamic asset.
     * This avoids bundling/copying BetterMap artwork into DapperMap while also
     * avoiding cross-plugin UI asset namespace resolution, which Hytale does
     * not perform for DapperMap's dynamically-created HUD elements.
     */
    public static String resolveIconAsset(PlayerRef playerRef, String icon, java.awt.Color tint, String playerSlug) {
        DynamicAssetPublisher.PreparedAsset prepared = prepareIconAsset(icon, tint, playerSlug);
        return DynamicAssetPublisher.deliver(playerRef, prepared) ? prepared.path() : null;
    }

    public static DynamicAssetPublisher.PreparedAsset prepareIconAsset(
            String icon, java.awt.Color tint, String playerSlug) {
        if (playerSlug == null || playerSlug.isBlank()) return null;

        String filename = sanitizeIcon(icon);
        if (filename == null) {
            return null;
        }

        Method resolved = resolve();
        ClassLoader loader = resolvedLoader;
        if (resolved == null || loader == null) {
            return null;
        }

        byte[] bytes = ICON_BYTES.get(filename);
        if (bytes == null) {
            String resourcePath = ICON_RESOURCE_PREFIX + filename;
            try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    return null;
                }
                bytes = stream.readAllBytes();
                if (bytes.length == 0) {
                    return null;
                }
                if (ICON_BYTES.size() >= 256) ICON_BYTES.clear();
                ICON_BYTES.put(filename, bytes);
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        }

        int rgb = tint != null ? tint.getRGB() & 0xFFFFFF : 0xFFFFFF;
        String tintKey = filename + "#" + String.format("%06X", rgb);
        byte[] publishedBytes = bytes;

        // BetterMap's source icons are designed to be UI-tinted. Replicate that
        // behaviour for DapperMap's dynamic image assets by multiplying each
        // source RGB channel by the marker tint while preserving alpha.
        if (rgb != 0xFFFFFF) {
            publishedBytes = TINTED_ICON_BYTES.get(tintKey);
            if (publishedBytes == null) {
                publishedBytes = tintPng(bytes, rgb);
                if (publishedBytes == null || publishedBytes.length == 0) {
                    return null;
                }
                if (TINTED_ICON_BYTES.size() >= 256) TINTED_ICON_BYTES.clear();
                TINTED_ICON_BYTES.put(tintKey, publishedBytes);
            }
        }

        String path = "UI/DapperMap/bettermap-" + playerSlug + "-"
                + stripExtension(filename) + "-" + String.format("%06X", rgb) + ".png";
        return DynamicAssetPublisher.prepare(path, publishedBytes);
    }

    private static Method resolve() {
        try {
            PluginManager pluginManager = PluginManager.get();
            if (pluginManager == null) {
                clearResolved();
                return null;
            }

            PluginBase plugin = pluginManager.getPlugin(BETTERMAP_ID);
            if (plugin == null || !plugin.isEnabled()) {
                clearResolved();
                return null;
            }

            ClassLoader loader = plugin.getClass().getClassLoader();
            Method cached = getUserMarkers;
            if (cached != null && loader == resolvedLoader) {
                return cached;
            }

            synchronized (BetterMapIntegration.class) {
                cached = getUserMarkers;
                if (cached != null && loader == resolvedLoader) {
                    return cached;
                }

                Class<?> manager = Class.forName(WAYPOINT_MANAGER, false, loader);
                Method resolved = manager.getMethod("getUserMarkers", Player.class);
                resolvedLoader = loader;
                getUserMarkers = resolved;
                return resolved;
            }
        } catch (ClassNotFoundException | NoSuchMethodException | RuntimeException | LinkageError ignored) {
            clearResolved();
            return null;
        }
    }

    private static void clearResolved() {
        if (resolvedLoader != null) {
            ICON_BYTES.clear();
            TINTED_ICON_BYTES.clear();
        }
        resolvedLoader = null;
        getUserMarkers = null;
    }

    private static java.awt.Color toAwtColor(com.hypixel.hytale.protocol.Color color) {
        if (color == null) {
            return java.awt.Color.WHITE;
        }
        return new java.awt.Color(
                Byte.toUnsignedInt(color.red),
                Byte.toUnsignedInt(color.green),
                Byte.toUnsignedInt(color.blue)
        );
    }

    static byte[] tintPng(byte[] source, int rgb) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                return null;
            }

            int tintR = (rgb >>> 16) & 0xFF;
            int tintG = (rgb >>> 8) & 0xFF;
            int tintB = rgb & 0xFF;

            BufferedImage tinted = new BufferedImage(
                    image.getWidth(),
                    image.getHeight(),
                    BufferedImage.TYPE_INT_ARGB
            );

            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;

                    r = r * tintR / 255;
                    g = g * tintG / 255;
                    b = b * tintB / 255;

                    tinted.setRGB(x, y,
                            (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(tinted, "png", output)) {
                    return null;
                }
                return output.toByteArray();
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String sanitizeIcon(String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }

        // BetterMap 1.3.8 stores simple names such as UserA.png. Restrict the
        // value to a filename so a malformed/custom marker cannot escape the
        // expected shared UI asset path.
        String normalized = icon.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }

        if (normalized.isBlank() || normalized.contains("..")) {
            return null;
        }
        return normalized;
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

    private static synchronized void resolve(ClassLoader current) throws ReflectiveOperationException {
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

        public synchronized V request(K key, Supplier<CompletableFuture<V>> start) {
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
            CompletableFuture<V> future;
            try { future=start.get(); if(future==null) throw new IllegalStateException("Missing image future"); }
            catch(RuntimeException e) {--pending;return null;}
            Entry<V> entry=new Entry<>(future);
            entries.put(key,entry);
            future.whenComplete((value,error)-> {
                synchronized(this) {
                    --pending;
                    entry.completedAt=clock.getAsLong();
                    if(error!=null || value==null) entries.remove(key,entry);
                }
            });
            return peek(key);
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

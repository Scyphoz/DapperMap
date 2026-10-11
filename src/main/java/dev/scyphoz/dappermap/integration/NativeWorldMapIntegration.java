package dev.scyphoz.dappermap.integration;

import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarkerComponent;
import com.hypixel.hytale.protocol.packets.worldmap.TintComponent;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapMarker;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapColours;
import dev.scyphoz.dappermap.dynamic.DynamicAssetPublisher;
import dev.scyphoz.dappermap.util.DapperMapUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Collects the world-map markers visible to the current player in one pass over the world's
 * marker providers. This is also the single source of BetterMap waypoints: BetterMap
 * replaces Hytale's "personal"/"shared" providers with its own, so routing that one
 * provider here means every waypoint appears exactly once and BetterMap's hide settings apply.
 */
public final class NativeWorldMapIntegration {
    // Built-in systems also register "portals", "warps", "objectives" and
    // "prefabs". Keep their markers (and future providers), excluding only
    // marker types rendered separately by the minimap ("death" is handled by its own
    // Death Marker switch below). "BetterMapPlayerRadar" is
    // BetterMap's own player-position provider: DapperMap draws players itself
    // (honouring the Other Players toggle), so radar icons would only duplicate them.
    private static final Set<String> EXCLUDED = Set.of("playerIcons", "BetterMapPlayerRadar");
    /** Hytale's death-marker provider key (BetterMap wraps it under the same key). */
    public static final String DEATH_PROVIDER = "death";
    /** BetterMap swaps Hytale's "personal"/"shared" waypoint providers for this one. */
    public static final String BETTERMAP_WAYPOINT_PROVIDER = "bettermap_usermarkers";
    /** A single filename, or a forward-slash relative path without "..", ending in .png. */
    private static final java.util.regex.Pattern ICON_PATH = java.util.regex.Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*\\.png");
    private static final String ICON_PREFIX = "UI/WorldMap/MapMarkers/";
    private static final int MARKER_SIZE = 18;
    /** Markers Hytale flags as Major (0.7.0+, e.g. objective targets) are drawn a little larger. */
    private static final int MAJOR_MARKER_SIZE = 24;
    /**
     * MapMarker.iconSize, added in Hytale 0.7.0 (null on 0.6.x, where every marker is Default).
     * Read reflectively so one build runs on both versions; a public field, so no access tricks.
     */
    private static final java.lang.reflect.Field ICON_SIZE = markerField("iconSize");
    private static final int BETTERMAP_MARKER_SIZE = 20;
    private static final Map<String, byte[]> ICON_BYTES = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> TINTED_ICON_BYTES = new ConcurrentHashMap<>();
    /** Icon names already reported as unresolvable, so each is logged only once. */
    private static final Set<String> REPORTED_MISSING = ConcurrentHashMap.newKeySet();
    /** Longest wait for a common asset's bytes to load; render pool only, never the world thread. */
    private static final long ICON_LOAD_TIMEOUT_MS = 2000L;
    private static volatile Map<String, String> filenameIndex;
    private static volatile long filenameIndexBuiltMs;
    /** Common asset entry count when the filename index was last built. */
    private static volatile int filenameIndexAssetCount = -1;
    private static final com.hypixel.hytale.logger.HytaleLogger LOGGER =
            com.hypixel.hytale.logger.HytaleLogger.get("DapperMap");
    private NativeWorldMapIntegration() { }

    /**
     * One pass over the world's marker providers. World thread only.
     *
     * @param includeNative       Hytale world-map markers enabled (POIs, spawn, warps, ...).
     * @param betterMapWaypoints  BetterMap integration and its waypoint markers enabled; its
     *                            waypoints then use BetterMap styling. When false they are
     *                            still shown as ordinary world-map markers if includeNative.
     * @param includeDeaths       Death Marker switch: Hytale's (or BetterMap's) death markers,
     *                            independent of includeNative and tinted with the Death colour.
     */
    public static List<DynamicMinimapMarker> getMarkers(World world, Player player, DynamicMinimapColours colours,
                                                        boolean includeNative, boolean betterMapWaypoints,
                                                        boolean includeDeaths) {
        List<DynamicMinimapMarker> result = new ArrayList<>();
        // World marker overrides are not applied: WorldMapManager.getMarkerOverridesView() may
        // only be called on the world map's own thread (it throws on the world thread), and the
        // overrides map has no other public accessor in 0.6.8 or 0.7.0.
        MarkersCollector nativeCollector = collector(marker -> {
            double mx = marker.transform.position.x, mz = marker.transform.position.z;
            String image = markerImage(marker.markerImage);
            if (image == null) {
                result.add(DynamicMinimapMarker.dot(
                        mx, mz, colours.awt(DynamicMinimapColours.Key.NEUTRAL, 255), 9));
                return;
            }
            result.add(DynamicMinimapMarker.nativeWorldMap(mx, mz, image, markerTint(marker),
                    isMajor(marker) ? MAJOR_MARKER_SIZE : MARKER_SIZE));
        });
        MarkersCollector betterMapCollector = collector(marker -> {
            String image = markerImage(marker.markerImage);
            if (image == null) return;
            result.add(DynamicMinimapMarker.betterMap(marker.transform.position.x,
                    marker.transform.position.z, image, markerTint(marker), BETTERMAP_MARKER_SIZE));
        });
        java.awt.Color deathTint = colours.awt(DynamicMinimapColours.Key.DEATH, 255);
        MarkersCollector deathCollector = collector(marker -> {
            String image = markerImage(marker.markerImage);
            if (image == null) return;
            result.add(DynamicMinimapMarker.nativeWorldMap(marker.transform.position.x,
                    marker.transform.position.z, image, deathTint, MARKER_SIZE));
        });
        world.getWorldMapManager().getMarkerProviders().forEach((key, provider) -> {
            if (EXCLUDED.contains(key)) return;
            boolean waypoints = BETTERMAP_WAYPOINT_PROVIDER.equals(key);
            MarkersCollector target = DEATH_PROVIDER.equals(key) ? (includeDeaths ? deathCollector : null)
                    : waypoints && betterMapWaypoints ? betterMapCollector
                    : includeNative ? nativeCollector : null;
            if (target == null) return;
            // Hytale 0.7.0's spawn provider blocks (join, up to 10 s) on a lookup that can need the
            // world thread itself: draw the spawn marker from a non-blocking lookup instead.
            if (SPAWN_ASYNC != null && target == nativeCollector
                    && provider instanceof com.hypixel.hytale.server.core.universe.world.worldmap.markers.providers.SpawnMarkerProvider) {
                addSpawnMarker(world, player, result);
                return;
            }
            try {
                provider.update(world, player, target);
            } catch (RuntimeException | LinkageError ignored) {
                // One faulty (often third-party) provider must not hide every other marker.
            }
        });
        return result;
    }

    // ---------------------------------------------------------------------------------
    // Spawn marker without blocking the world thread (Hytale 0.7.0+)
    // ---------------------------------------------------------------------------------

    /**
     * ISpawnProvider.getSpawnPointAsync(World, UUID), added in 0.7.0 (null on 0.6.x, where the
     * spawn provider is called normally: its lookup there is synchronous and doesn't block).
     */
    private static final java.lang.reflect.Method SPAWN_ASYNC = spawnAsyncMethod();
    /** How often a player's spawn point is looked up again (it rarely changes). */
    private static final long SPAWN_REFRESH_MS = 30_000L;
    /** Per player: the world, the lookup in flight, when it started, and the last known x/z. */
    private record SpawnLookup(World world, java.util.concurrent.CompletableFuture<?> future,
                               long startedMs, double[] lastXZ) { }
    private static final Map<java.util.UUID, SpawnLookup> SPAWN_LOOKUPS = new ConcurrentHashMap<>();

    private static java.lang.reflect.Method spawnAsyncMethod() {
        try {
            return com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider.class
                    .getMethod("getSpawnPointAsync", World.class, java.util.UUID.class);
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
    }

    /**
     * Adds the world's spawn marker for this player, as Hytale's provider would, from a lookup
     * that is started here and only read once it has finished (never joined). The last known
     * position stays drawn while a refresh is in flight. World thread only.
     */
    private static void addSpawnMarker(World world, Player player, List<DynamicMinimapMarker> result) {
        try {
            if (!world.getGameplayConfig().getWorldMapConfig().isDisplaySpawn()) return;
            java.util.UUID uuid = player.getUuid();
            if (uuid == null) return;
            long now = System.currentTimeMillis();
            SpawnLookup lookup = SPAWN_LOOKUPS.get(uuid);
            if (lookup != null && lookup.world() != world) lookup = null; // Another world: start afresh.
            double[] lastXZ = lookup == null ? null : lookup.lastXZ();
            if (lookup != null && lookup.future().isDone()) {
                // A finished lookup: remember its position (a failed one keeps the last known).
                if (!lookup.future().isCompletedExceptionally()
                        && lookup.future().getNow(null) instanceof com.hypixel.hytale.math.vector.Transform spawn
                        && spawn.getPosition() != null) {
                    lastXZ = new double[]{spawn.getPosition().x(), spawn.getPosition().z()};
                }
            }
            if (lookup == null || (lookup.future().isDone() && now - lookup.startedMs() >= SPAWN_REFRESH_MS)) {
                var spawnProvider = world.getWorldConfig().getSpawnProvider();
                Object future = spawnProvider == null ? null : SPAWN_ASYNC.invoke(spawnProvider, world, uuid);
                if (future instanceof java.util.concurrent.CompletableFuture<?> started) {
                    if (SPAWN_LOOKUPS.size() >= 512 && !SPAWN_LOOKUPS.containsKey(uuid)) SPAWN_LOOKUPS.clear();
                    SPAWN_LOOKUPS.put(uuid, new SpawnLookup(world, started, now, lastXZ));
                }
            } else if (lastXZ != lookup.lastXZ()) {
                SPAWN_LOOKUPS.put(uuid, new SpawnLookup(world, lookup.future(), lookup.startedMs(), lastXZ));
            }
            if (lastXZ != null) result.add(DynamicMinimapMarker.nativeWorldMap(lastXZ[0], lastXZ[1],
                    "Spawn.png", java.awt.Color.WHITE, MARKER_SIZE));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // No spawn marker this scan; the other providers are unaffected.
        }
    }

    /** Drops spawn lookups for a removed world (and the world reference they hold). */
    public static void clearWorld(World world) {
        SPAWN_LOOKUPS.values().removeIf(lookup -> lookup.world() == world);
    }

    /** True when Hytale 0.7.0+ flags the marker as Major. Always false on 0.6.x. */
    private static boolean isMajor(MapMarker marker) {
        if (ICON_SIZE == null) return false;
        try {
            Object size = ICON_SIZE.get(marker);
            return size instanceof Enum<?> value && "Major".equals(value.name());
        } catch (IllegalAccessException | RuntimeException unreadable) {
            return false;
        }
    }

    /** A public MapMarker field by name, or null when this server version doesn't have it. */
    private static java.lang.reflect.Field markerField(String name) {
        try {
            return MapMarker.class.getField(name);
        } catch (NoSuchFieldException | RuntimeException missing) {
            return null;
        }
    }

    /** View distance is ignored: the minimap edge-tracks distant markers itself. */
    private static MarkersCollector collector(java.util.function.Consumer<MapMarker> sink) {
        return new MarkersCollector() {
            @Override public void add(MapMarker marker) { collect(marker); }
            @Override public void addIgnoreViewDistance(MapMarker marker) { collect(marker); }
            @SuppressWarnings("deprecation")
            @Override public Predicate<PlayerRef> getPlayerMapFilter() { return null; }
            @Override public boolean isInViewDistance(double mx, double mz) { return true; }
            private void collect(MapMarker marker) {
                if (marker != null && marker.transform != null && marker.transform.position != null) sink.accept(marker);
            }
        };
    }

    /**
     * Publishes a tinted copy of a world-map marker image. Hytale's common assets are tried
     * first, then BetterMap's bundled icons (waypoints created through BetterMap use icon
     * names that only BetterMap ships). Off the world thread.
     */
    public static DynamicAssetPublisher.PreparedAsset prepareIconAsset(
            String image, java.awt.Color tint, String playerSlug) {
        if (playerSlug == null || playerSlug.isBlank() || markerImage(image) == null) return null;
        byte[] source = registryIcon(image);
        if (source == null) source = BetterMapIntegration.loaderIcon(fileName(image));
        if (source == null) { reportMissing(image); return null; }
        return publishTinted("worldmap-", image, source, tint, playerSlug);
    }

    /**
     * Reads an icon from Hytale's common assets. Tries the MapMarkers folder and the given
     * path first, then a filename index of every common UI image (for icons stored in other
     * folders). Runs on the render pool: CommonAsset blobs are only weakly cached and may
     * reload asynchronously, so a short bounded wait is used instead of getNow(null), which
     * previously reported existing icons such as Spawn.png as missing.
     */
    static byte[] registryIcon(String image) {
        String[] candidates = image.startsWith("UI/") ? new String[]{image}
                : new String[]{ICON_PREFIX + image, "UI/" + image};
        for (String path : candidates) {
            byte[] bytes = registryBytes(path);
            if (bytes != null) return bytes;
        }
        String indexed = indexedPath(fileName(image));
        return indexed == null ? null : registryBytes(indexed);
    }

    /** Bytes of one named common asset (cached), or null when absent/unreadable. */
    private static byte[] registryBytes(String path) {
        byte[] cached = ICON_BYTES.get(path);
        if (cached != null) return cached;
        byte[] bytes = DapperMapUtil.commonAssetBytes(path, ICON_LOAD_TIMEOUT_MS);
        if (bytes == null) return null;
        if (ICON_BYTES.size() >= 256) ICON_BYTES.clear();
        ICON_BYTES.put(path, bytes);
        return bytes;
    }

    /**
     * Full common-asset path for a lower-cased image filename. Built lazily from the
     * registry the first time a lookup misses, and rebuilt at most once a minute so asset
     * packs loaded later are picked up without rescanning on every miss.
     */
    private static String indexedPath(String filename) {
        String key = filename.toLowerCase(java.util.Locale.ROOT);
        Map<String, String> index = filenameIndex;
        long now = System.currentTimeMillis();
        if (index == null || (!index.containsKey(key) && filenameIndexRebuildDue(now))) {
            synchronized (NativeWorldMapIntegration.class) {
                if (filenameIndex == null || (!filenameIndex.containsKey(key) && filenameIndexRebuildDue(now))) {
                    filenameIndex = buildFilenameIndex();
                    filenameIndexBuiltMs = now;
                }
                index = filenameIndex;
            }
        }
        return index.get(key);
    }

    /**
     * A miss rebuilds the index at most once a minute, and only when the number of common
     * asset entries changed (an asset pack was loaded) or 10 minutes have passed. A name that is
     * simply missing therefore no longer walks the whole registry every minute.
     */
    private static boolean filenameIndexRebuildDue(long now) {
        long age = now - filenameIndexBuiltMs;
        if (age < 60_000L) return false;
        if (age >= 600_000L) return true;
        return commonAssetCount() != filenameIndexAssetCount;
    }

    /** Number of common asset entries (cheap: the registry's collection size), or -1 when unreadable. */
    private static int commonAssetCount() {
        try {
            return CommonAssetRegistry.getAllAssets().size();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /** Indexes every common UI .png by filename, preferring world-map marker folders. */
    private static Map<String, String> buildFilenameIndex() {
        Map<String, String> index = new java.util.HashMap<>();
        filenameIndexAssetCount = commonAssetCount();
        try {
            for (List<CommonAssetRegistry.PackAsset> assets : CommonAssetRegistry.getAllAssets()) {
                if (assets == null || assets.isEmpty()) continue;
                String name = assets.get(0).asset().getName();
                if (name == null || !name.startsWith("UI/") || !name.endsWith(".png")) continue;
                String key = fileName(name).toLowerCase(java.util.Locale.ROOT);
                String existing = index.get(key);
                if (existing == null || (!existing.contains("WorldMap") && name.contains("WorldMap"))) index.put(key, name);
            }
        } catch (RuntimeException ignored) {
            // Registry is being reloaded; the next miss after the back-off retries.
        }
        return index;
    }

    /** Tints (when needed) and prepares a per-player dynamic asset; tinted bytes are shared. */
    static DynamicAssetPublisher.PreparedAsset publishTinted(String kind, String image, byte[] source,
                                                             java.awt.Color tint, String playerSlug) {
        int rgb = tint != null ? tint.getRGB() & 0xFFFFFF : 0xFFFFFF;
        String hex = DapperMapUtil.hex6(rgb);
        byte[] published = source;
        if (rgb != 0xFFFFFF) {
            String cacheKey = kind + image + "#" + hex;
            published = TINTED_ICON_BYTES.get(cacheKey);
            if (published == null) {
                published = BetterMapIntegration.tintPng(source, rgb);
                if (published == null || published.length == 0) return null;
                if (TINTED_ICON_BYTES.size() >= 256) TINTED_ICON_BYTES.clear();
                TINTED_ICON_BYTES.put(cacheKey, published);
            }
        }
        String baseName = image.substring(0, image.length() - 4).replace('/', '_');
        // Source and tinted arrays are cached and never modified: hash each once, not once per player.
        return DynamicAssetPublisher.prepareShared("UI/DapperMap/" + kind + playerSlug + "-" + baseName + "-" + hex + ".png", published);
    }

    /** Logs each unresolvable icon name once, to help identify missing marker artwork. */
    static void reportMissing(String image) {
        if (REPORTED_MISSING.size() < 256 && REPORTED_MISSING.add(image)) {
            LOGGER.at(java.util.logging.Level.INFO).log(
                    "DapperMap: no icon found for world-map marker image '%s'; it will not be drawn on the minimap.", image);
        }
    }

    /**
     * Normalised image name per raw marker image name ("" = rejected). Marker scans run on the
     * world thread for every marker every 2 s, and only a handful of distinct names exist, so
     * the string clean-up and pattern match run once per name instead of once per marker.
     */
    private static final Map<String, String> MARKER_IMAGES = new ConcurrentHashMap<>();

    /** Normalised, safe marker image path (relative, forward slashes), or null. */
    static String markerImage(String image) {
        if (image == null) return null;
        String known = MARKER_IMAGES.get(image);
        if (known == null) {
            known = normaliseMarkerImage(image);
            if (MARKER_IMAGES.size() >= 512) MARKER_IMAGES.clear(); // Bounded (mod packs may add many).
            MARKER_IMAGES.put(image, known);
        }
        return known.isEmpty() ? null : known;
    }

    /** The image name cleaned and validated, or "" when it is unusable. */
    private static String normaliseMarkerImage(String image) {
        if (image.isBlank()) return "";
        String normalized = image.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return !normalized.contains("..") && ICON_PATH.matcher(normalized).matches() ? normalized : "";
    }

    static String fileName(String image) {
        int slash = image.lastIndexOf('/');
        return slash >= 0 ? image.substring(slash + 1) : image;
    }

    /** Tint carried by a marker's TintComponent; white when untinted. */
    static java.awt.Color markerTint(MapMarker marker) {
        if (marker.components != null) {
            for (MapMarkerComponent component : marker.components) {
                if (component instanceof TintComponent tint && tint.color != null) {
                    return new java.awt.Color(
                            Byte.toUnsignedInt(tint.color.red),
                            Byte.toUnsignedInt(tint.color.green),
                            Byte.toUnsignedInt(tint.color.blue));
                }
            }
        }
        return java.awt.Color.WHITE;
    }
}

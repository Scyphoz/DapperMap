package dev.scyphoz.dappermap.integration;

import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarkerComponent;
import com.hypixel.hytale.protocol.packets.worldmap.TintComponent;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapMarker;
import dev.scyphoz.dappermap.dynamic.MinimapColours;
import dev.scyphoz.dappermap.dynamic.DynamicAssetPublisher;
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
    private static final int BETTERMAP_MARKER_SIZE = 20;
    private static final Map<String, byte[]> ICON_BYTES = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> TINTED_ICON_BYTES = new ConcurrentHashMap<>();
    /** Icon names already reported as unresolvable, so each is logged only once. */
    private static final Set<String> REPORTED_MISSING = ConcurrentHashMap.newKeySet();
    /** Longest wait for a common asset's bytes to load; render pool only, never the world thread. */
    private static final long ICON_LOAD_TIMEOUT_MS = 2000L;
    private static volatile Map<String, String> filenameIndex;
    private static volatile long filenameIndexBuiltMs;
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
    public static List<DynamicMinimapMarker> getMarkers(World world, Player player, MinimapColours colours,
                                                        boolean includeNative, boolean betterMapWaypoints,
                                                        boolean includeDeaths) {
        List<DynamicMinimapMarker> result = new ArrayList<>();
        MarkersCollector nativeCollector = collector(marker -> {
            double mx = marker.transform.position.x, mz = marker.transform.position.z;
            String image = markerImage(marker.markerImage);
            if (image == null) {
                result.add(DynamicMinimapMarker.dot(
                        mx, mz, colours.awt(MinimapColours.Key.NEUTRAL, 255), 9));
                return;
            }
            result.add(DynamicMinimapMarker.nativeWorldMap(mx, mz, image, markerTint(marker), MARKER_SIZE));
        });
        MarkersCollector betterMapCollector = collector(marker -> {
            String image = markerImage(marker.markerImage);
            if (image == null) return;
            result.add(DynamicMinimapMarker.betterMap(marker.transform.position.x,
                    marker.transform.position.z, image, markerTint(marker), BETTERMAP_MARKER_SIZE));
        });
        java.awt.Color deathTint = colours.awt(MinimapColours.Key.DEATH, 255);
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
            try {
                provider.update(world, player, target);
            } catch (RuntimeException | LinkageError ignored) {
                // One faulty (often third-party) provider must not hide every other marker.
            }
        });
        return result;
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
        try {
            CommonAsset asset = CommonAssetRegistry.getByName(path);
            if (asset == null) return null;
            byte[] bytes = asset.getBlob().get(ICON_LOAD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (bytes == null || bytes.length == 0) return null;
            if (ICON_BYTES.size() >= 256) ICON_BYTES.clear();
            ICON_BYTES.put(path, bytes);
            return bytes;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        }
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
        if (index == null || (!index.containsKey(key) && now - filenameIndexBuiltMs >= 60000L)) {
            synchronized (NativeWorldMapIntegration.class) {
                if (filenameIndex == null || (!filenameIndex.containsKey(key) && now - filenameIndexBuiltMs >= 60000L)) {
                    filenameIndex = buildFilenameIndex();
                    filenameIndexBuiltMs = now;
                }
                index = filenameIndex;
            }
        }
        return index.get(key);
    }

    /** Indexes every common UI .png by filename, preferring world-map marker folders. */
    private static Map<String, String> buildFilenameIndex() {
        Map<String, String> index = new java.util.HashMap<>();
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
        String hex = String.format("%06X", rgb);
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
        return DynamicAssetPublisher.prepare("UI/DapperMap/" + kind + playerSlug + "-" + baseName + "-" + hex + ".png", published);
    }

    /** Logs each unresolvable icon name once, to help identify missing marker artwork. */
    static void reportMissing(String image) {
        if (REPORTED_MISSING.size() < 256 && REPORTED_MISSING.add(image)) {
            LOGGER.at(java.util.logging.Level.INFO).log(
                    "DapperMap: no icon found for world-map marker image '%s'; it will not be drawn on the minimap.", image);
        }
    }

    /** Normalised, safe marker image path (relative, forward slashes), or null. */
    static String markerImage(String image) {
        if (image == null || image.isBlank()) return null;
        String normalized = image.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return !normalized.contains("..") && ICON_PATH.matcher(normalized).matches() ? normalized : null;
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

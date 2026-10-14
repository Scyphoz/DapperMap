package dev.scyphoz.dappermap.integration;

import com.hypixel.hytale.protocol.packets.worldmap.CompassAlertComponent;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarkerComponent;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarkerIconSize;
import com.hypixel.hytale.protocol.packets.worldmap.TintComponent;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerOverride;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapMarker;
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
    /** Markers Hytale flags as Major (block and objective markers with IconSize Major) are drawn a little larger. */
    private static final int MAJOR_MARKER_SIZE = 24;
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

    /** One marker pass in flight: the world it reads and the markers it will produce. */
    public record Pass(World world, long startedMs, java.util.concurrent.CompletableFuture<List<DynamicMinimapMarker>> future) { }

    /**
     * Starts one marker pass on DapperMap's marker thread and returns at once; the session
     * picks the result up on a later tick. Hytale itself runs every provider on the world
     * map's own thread, so none of them needs the world thread. Colours are resolved by the
     * caller (world thread), so the pass never reads the player's live settings.
     * Returns null when the marker thread's queue is full (try again on a later scan).
     */
    public static Pass requestMarkers(World world, Player player, java.util.UUID viewer,
                                      java.awt.Color neutral, java.awt.Color deathTint,
                                      boolean includeNative, boolean betterMapWaypoints, boolean includeDeaths) {
        try {
            return new Pass(world, System.currentTimeMillis(), java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> getMarkers(world, player, viewer, neutral, deathTint,
                            includeNative, betterMapWaypoints, includeDeaths), PASS_EXECUTOR));
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return null;
        }
    }

    /**
     * One pass over the world's marker providers. Marker thread (see requestMarkers).
     *
     * @param includeNative       Hytale world-map markers enabled (POIs, spawn, warps, ...).
     * @param betterMapWaypoints  BetterMap integration and its waypoint markers enabled; its
     *                            waypoints then use BetterMap styling. When false they are
     *                            still shown as ordinary world-map markers if includeNative.
     * @param includeDeaths       Death Marker switch: Hytale's (or BetterMap's) death markers,
     *                            independent of includeNative and tinted with the Death colour.
     * @param viewer              the player's UUID (keys the blocking-provider results).
     * @param neutral             colour of markers whose image can't be used (drawn as dots).
     */
    static List<DynamicMinimapMarker> getMarkers(World world, Player player, java.util.UUID viewer,
                                                 java.awt.Color neutral, java.awt.Color deathTint,
                                                 boolean includeNative, boolean betterMapWaypoints,
                                                 boolean includeDeaths) {
        List<DynamicMinimapMarker> result = new ArrayList<>();
        if (!world.isAlive()) return result; // World shut down while the pass was queued.
        // World marker overrides (icon swaps from /worldmap markers override and world events),
        // applied the same way Hytale's own collector applies them. Usually empty: no lookups then.
        Map<String, MapMarkerOverride> overrides = markerOverrides(world);
        MarkersCollector nativeCollector = collector(marker -> {
            double mx = marker.transform.position.x, mz = marker.transform.position.z;
            String image = markerImage(overriddenImage(marker, overrides));
            if (image == null) {
                result.add(DynamicMinimapMarker.dot(mx, mz, neutral, 9));
                return;
            }
            result.add(withAlert(DynamicMinimapMarker.nativeWorldMap(mx, mz, image, markerTint(marker),
                    marker.iconSize == MapMarkerIconSize.Major ? MAJOR_MARKER_SIZE : MARKER_SIZE), marker));
        });
        MarkersCollector betterMapCollector = collector(marker -> {
            String image = markerImage(overriddenImage(marker, overrides));
            if (image == null) return;
            result.add(withAlert(DynamicMinimapMarker.betterMap(marker.transform.position.x,
                    marker.transform.position.z, image, markerTint(marker), BETTERMAP_MARKER_SIZE), marker));
        });
        MarkersCollector deathCollector = collector(marker -> {
            String image = markerImage(overriddenImage(marker, overrides));
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
            // Providers that block (Hytale's spawn provider, and BetterMap's spawn privacy wrapper
            // registered under the same key) run on their own thread, so a slow spawn lookup never
            // holds up the other markers; the pass replays their last finished result.
            if (BLOCKING_PROVIDERS.contains(key)) {
                for (MapMarker marker : offThreadMarkers(viewer, key, provider, world, player)) target.add(marker);
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
    // Compass alerts (Hytale 0.7 CompassAlertComponent)
    // ---------------------------------------------------------------------------------

    /** Longest alert Hytale allows (MapMarkerAlert.MAX_DURATION_S). */
    private static final int MAX_ALERT_MS = 30_000;

    /**
     * Copies a marker's compass alert onto the minimap marker: the alert icon (relative to
     * UI/WorldMap/MapMarkers, null = keep the marker icon), its tint (null = keep the marker
     * tint) and how long it lasts. The id lets the session start each alert once, when the
     * marker appears, as Hytale's compass does when the client adds a marker.
     */
    private static DynamicMinimapMarker withAlert(DynamicMinimapMarker minimap, MapMarker marker) {
        if (marker.components == null) return minimap;
        for (MapMarkerComponent component : marker.components) {
            if (!(component instanceof CompassAlertComponent alert)) continue;
            int durationMs = (int) Math.min(MAX_ALERT_MS, Math.max(0f, alert.durationSeconds) * 1000f);
            if (durationMs <= 0) return minimap;
            // Markers without an id (rare) are told apart by image and block position.
            String id = marker.id != null ? marker.id
                    : minimap.assetPath + "@" + Math.floor(minimap.x) + "," + Math.floor(minimap.z);
            java.awt.Color tint = alert.tint == null ? null : new java.awt.Color(
                    Byte.toUnsignedInt(alert.tint.red), Byte.toUnsignedInt(alert.tint.green), Byte.toUnsignedInt(alert.tint.blue));
            return minimap.withAlert(id, markerImage(alert.image), tint, durationMs);
        }
        return minimap;
    }

    // ---------------------------------------------------------------------------------
    // World marker overrides
    // ---------------------------------------------------------------------------------

    /**
     * WorldMapManager.markerOverrides: a ConcurrentHashMap, so it can be read from the world
     * thread. The public getMarkerOverridesView() only works on the world map's own thread, so
     * the private field is read instead; null (no overrides applied) if it is ever renamed.
     */
    private static final java.lang.reflect.Field OVERRIDES_FIELD = overridesField();

    private static java.lang.reflect.Field overridesField() {
        try {
            java.lang.reflect.Field field = com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager.class
                    .getDeclaredField("markerOverrides");
            if (!Map.class.isAssignableFrom(field.getType())) return null;
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** The world's marker overrides, keyed by marker name (translation key or raw text); empty when none. */
    @SuppressWarnings("unchecked")
    private static Map<String, MapMarkerOverride> markerOverrides(World world) {
        if (OVERRIDES_FIELD == null) return Map.of();
        try {
            Object value = OVERRIDES_FIELD.get(world.getWorldMapManager());
            return value instanceof Map<?, ?> map && !map.isEmpty() ? (Map<String, MapMarkerOverride>) map : Map.of();
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return Map.of();
        }
    }

    /** The marker's image after any override, matched as Hytale does (name key, then raw text). */
    private static String overriddenImage(MapMarker marker, Map<String, MapMarkerOverride> overrides) {
        if (overrides.isEmpty() || marker.name == null) return marker.markerImage;
        String name = marker.name.messageId != null ? marker.name.messageId : marker.name.rawText;
        MapMarkerOverride override = name == null ? null : overrides.get(name);
        return override != null && override.icon() != null ? override.icon() : marker.markerImage;
    }

    // ---------------------------------------------------------------------------------
    // Blocking providers, run off the world thread
    // ---------------------------------------------------------------------------------

    /**
     * Provider keys whose update() joins a lookup that may need the world thread itself (up to
     * a 10 s timeout): Hytale's SpawnMarkerProvider and BetterMap's SpawnPrivacyProvider. Hytale
     * itself calls providers on the world map thread, so calling them off the world thread is safe.
     */
    private static final Set<String> BLOCKING_PROVIDERS = Set.of("spawn");
    /** How often a blocking provider is asked again (spawn points and privacy settings rarely change). */
    private static final long OFF_THREAD_REFRESH_MS = 10_000L;
    /** One daemon thread for blocking providers; at most one lookup per player and provider is in flight. */
    private static final java.util.concurrent.ThreadPoolExecutor MARKER_EXECUTOR = markerExecutor("DapperMap-Spawn");
    /**
     * One daemon thread for marker passes (all players, one pass per player in flight). A single
     * thread mirrors Hytale's one world-map thread, so providers see no extra concurrency of
     * their own beyond what Hytale already does.
     */
    private static final java.util.concurrent.ThreadPoolExecutor PASS_EXECUTOR = markerExecutor("DapperMap-Markers");

    private record OffThreadKey(java.util.UUID viewer, String provider) { }
    /** The world and provider asked, the lookup in flight, when it started, and the last finished markers. */
    private record OffThreadLookup(World world, Object provider, java.util.concurrent.CompletableFuture<List<MapMarker>> future,
                                   long startedMs, List<MapMarker> last) { }
    private static final Map<OffThreadKey, OffThreadLookup> OFF_THREAD = new ConcurrentHashMap<>();

    private static java.util.concurrent.ThreadPoolExecutor markerExecutor(String name) {
        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(256),
                DapperMapUtil.daemonThreads(name),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true); // No idle thread when nobody needs these markers.
        return executor;
    }

    /**
     * The last finished markers of a blocking provider for this player, starting a fresh lookup
     * on the spawn thread when due. Never waits: the previous markers stay drawn while a
     * refresh is in flight, and a failed lookup keeps them. Marker thread (one pass per player
     * at a time, so each key has a single writer).
     */
    private static List<MapMarker> offThreadMarkers(java.util.UUID viewer, String key,
                                                    WorldMapManager.MarkerProvider provider,
                                                    World world, Player player) {
        if (viewer == null) return List.of();
        OffThreadKey id = new OffThreadKey(viewer, key);
        OffThreadLookup lookup = OFF_THREAD.get(id);
        // Another world, or BetterMap swapped the provider in or out: start afresh.
        if (lookup != null && (lookup.world() != world || lookup.provider() != provider)) lookup = null;
        List<MapMarker> last = lookup == null ? List.of() : lookup.last();
        if (lookup != null && lookup.future().isDone() && !lookup.future().isCompletedExceptionally()) {
            List<MapMarker> finished = lookup.future().getNow(null);
            if (finished != null) last = finished;
        }
        long now = System.currentTimeMillis();
        if (lookup == null || (lookup.future().isDone() && now - lookup.startedMs() >= OFF_THREAD_REFRESH_MS)) {
            try {
                java.util.concurrent.CompletableFuture<List<MapMarker>> started =
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            // Copies of the markers only: nothing here touches DapperMap state.
                            List<MapMarker> collected = new ArrayList<>(2);
                            provider.update(world, player, collector(collected::add));
                            return collected;
                        }, MARKER_EXECUTOR);
                if (OFF_THREAD.size() >= 1024 && !OFF_THREAD.containsKey(id)) OFF_THREAD.clear(); // Bounded.
                OFF_THREAD.put(id, new OffThreadLookup(world, provider, started, now, last));
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                // Queue full: keep the last markers and try again on the next scan.
                if (lookup != null && last != lookup.last()) OFF_THREAD.put(id, new OffThreadLookup(
                        world, provider, lookup.future(), lookup.startedMs(), last));
            }
        } else if (last != lookup.last()) {
            OFF_THREAD.put(id, new OffThreadLookup(world, provider, lookup.future(), lookup.startedMs(), last));
        }
        return last;
    }

    /** Drops a departed player's off-thread lookups (and the World references they hold). */
    public static void forgetPlayer(java.util.UUID uuid) {
        if (uuid != null) OFF_THREAD.keySet().removeIf(key -> uuid.equals(key.viewer()));
    }

    /** Drops off-thread lookups for a removed world. */
    public static void clearWorld(World world) {
        OFF_THREAD.values().removeIf(lookup -> lookup.world() == world);
    }

    /** Plugin shutdown: forget every lookup and stop the marker threads. */
    public static void shutdown() {
        OFF_THREAD.clear();
        MARKER_EXECUTOR.shutdownNow();
        PASS_EXECUTOR.shutdownNow();
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

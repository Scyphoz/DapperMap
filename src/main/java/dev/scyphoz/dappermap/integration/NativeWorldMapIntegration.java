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

/** Collects only non-player, non-death markers visible to the current player. */
public final class NativeWorldMapIntegration {
    private static final Set<String> INCLUDED = Set.of("personal", "shared", "poi", "spawn", "respawn");
    private static final String ICON_PREFIX = "UI/WorldMap/MapMarkers/";
    private static final int MARKER_SIZE = 18;
    private static final Map<String, byte[]> ICON_BYTES = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> TINTED_ICON_BYTES = new ConcurrentHashMap<>();
    private NativeWorldMapIntegration() { }

    public static List<DynamicMinimapMarker> getMarkers(
            World world,
            Player player,
            MinimapColours colours
    ) {
        List<DynamicMinimapMarker> result = new ArrayList<>();
        MarkersCollector collector = new MarkersCollector() {
            @Override public void add(MapMarker marker) { collect(marker); }
            @Override public void addIgnoreViewDistance(MapMarker marker) { collect(marker); }
            @Override public Predicate<PlayerRef> getPlayerMapFilter() {
                return null;
            }
            @Override public boolean isInViewDistance(double mx, double mz) {
                return true;
            }
            private void collect(MapMarker marker) {
                if (marker == null || marker.transform == null || marker.transform.position == null) return;
                double mx = marker.transform.position.x, mz = marker.transform.position.z;

                String image = markerImage(marker.markerImage);
                if (image == null) {
                    result.add(DynamicMinimapMarker.dot(
                            mx, mz, colours.awt(MinimapColours.Key.NEUTRAL, 255), 9));
                    return;
                }

                result.add(DynamicMinimapMarker.nativeWorldMap(
                        mx, mz, ICON_PREFIX + image, markerTint(marker), MARKER_SIZE));
            }
        };
        world.getWorldMapManager().getMarkerProviders().forEach((key, provider) -> {
            if (INCLUDED.contains(key)) provider.update(world, player, collector);
        });
        return result;
    }

    /** Publishes a tinted copy of Hytale's own world-map marker image for this HUD. */
    public static String resolveIconAsset(
            PlayerRef playerRef,
            String assetPath,
            java.awt.Color tint,
            String playerSlug
    ) {
        if (playerRef == null || playerSlug == null || playerSlug.isBlank()) return null;

        String image = markerImage(assetPath);
        if (image == null) return null;

        String sourcePath = ICON_PREFIX + image;
        byte[] source = ICON_BYTES.get(sourcePath);
        if (source == null) {
            try {
                CommonAsset asset = CommonAssetRegistry.getByName(sourcePath);
                if (asset == null) return null;
                source = asset.getBlob().join();
                if (source == null || source.length == 0) return null;
                ICON_BYTES.put(sourcePath, source);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        int rgb = tint != null ? tint.getRGB() & 0xFFFFFF : 0xFFFFFF;
        String cacheKey = sourcePath + "#" + String.format("%06X", rgb);
        byte[] published = source;
        if (rgb != 0xFFFFFF) {
            published = TINTED_ICON_BYTES.get(cacheKey);
            if (published == null) {
                published = BetterMapIntegration.tintPng(source, rgb);
                if (published == null || published.length == 0) return null;
                TINTED_ICON_BYTES.put(cacheKey, published);
            }
        }

        String baseName = image.substring(0, image.length() - 4);
        String path = "UI/DapperMap/worldmap-" + playerSlug + "-"
                + baseName + "-" + String.format("%06X", rgb) + ".png";
        return DynamicAssetPublisher.deliver(playerRef, path, published) ? path : null;
    }

    private static String markerImage(String image) {
        if (image == null || image.isBlank()) return null;
        String normalized = image.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String filename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return filename.matches("[A-Za-z0-9._-]+\\.png") ? filename : null;
    }

    private static java.awt.Color markerTint(MapMarker marker) {
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

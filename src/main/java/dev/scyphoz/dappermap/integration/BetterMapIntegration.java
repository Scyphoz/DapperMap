package dev.scyphoz.dappermap.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
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
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Reads the markers BetterMap currently exposes to this player.
     * Any compatibility failure is isolated and results in an empty list.
     */
    public static List<DynamicMinimapMarker> getMarkers(Player player) {
        if (player == null) {
            return Collections.emptyList();
        }

        Method method = resolve();
        if (method == null) {
            return Collections.emptyList();
        }

        try {
            Object value = method.invoke(null, player);
            if (!(value instanceof List<?> source) || source.isEmpty()) {
                return Collections.emptyList();
            }

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
            return Collections.emptyList();
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
        if (playerRef == null || playerSlug == null || playerSlug.isBlank()) {
            return null;
        }

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
                TINTED_ICON_BYTES.put(tintKey, publishedBytes);
            }
        }

        String path = "UI/DapperMap/bettermap-" + playerSlug + "-"
                + stripExtension(filename) + "-" + String.format("%06X", rgb) + ".png";
        return DynamicAssetPublisher.deliver(playerRef, path, publishedBytes) ? path : null;
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

    private static byte[] tintPng(byte[] source, int rgb) {
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
}

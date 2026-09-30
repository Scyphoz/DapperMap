package dev.scyphoz.dappermap.dynamic;

import java.awt.Color;

/** Immutable world-space marker description consumed by the session's HUD projection step. */
public final class DynamicMinimapMarker {
    public static final Color DEATH_COLOR = new Color(255, 255, 255, 235);
    public final double x;
    public final double z;
    public final Color color;
    public final int size;
    public final Style style;
    public final String assetPath;
    public final boolean clampToEdge;

    private DynamicMinimapMarker(
            double x,
            double z,
            Color color,
            int size,
            Style style,
            String assetPath,
            boolean clampToEdge
    ) {
        this.x = x;
        this.z = z;
        this.color = color;
        this.size = size;
        this.style = style;
        this.assetPath = assetPath;
        this.clampToEdge = clampToEdge;
    }

    public static DynamicMinimapMarker dot(double x, double z, Color color, int size) {
        return new DynamicMinimapMarker(x, z, color, size, Style.DOT, null, false);
    }

    public static DynamicMinimapMarker death(double x, double z, int size) {
        return new DynamicMinimapMarker(x, z, DEATH_COLOR, size, Style.DEATH, null, false);
    }

    public static DynamicMinimapMarker betterMap(
            double x,
            double z,
            String assetPath,
            Color color,
            int size
    ) {
        return new DynamicMinimapMarker(x, z, color, size, Style.BETTERMAP, assetPath, true);
    }

    public static DynamicMinimapMarker nativeWorldMap(
            double x,
            double z,
            String assetPath,
            Color color,
            int size
    ) {
        return new DynamicMinimapMarker(x, z, color, size, Style.NATIVE_WORLDMAP, assetPath, true);
    }

    /** Marker scans create fresh objects even when their map data is unchanged. */
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DynamicMinimapMarker marker)) return false;
        return Double.compare(x, marker.x) == 0 && Double.compare(z, marker.z) == 0
                && size == marker.size && style == marker.style && clampToEdge == marker.clampToEdge
                && java.util.Objects.equals(color, marker.color)
                && java.util.Objects.equals(assetPath, marker.assetPath);
    }

    @Override public int hashCode() {
        int hash = 31 + Double.hashCode(x);
        hash = 31 * hash + Double.hashCode(z);
        hash = 31 * hash + java.util.Objects.hashCode(color);
        hash = 31 * hash + size;
        hash = 31 * hash + java.util.Objects.hashCode(style);
        hash = 31 * hash + java.util.Objects.hashCode(assetPath);
        return 31 * hash + Boolean.hashCode(clampToEdge);
    }

    public enum Style {
        DOT,
        DEATH,
        BETTERMAP,
        NATIVE_WORLDMAP
    }
}

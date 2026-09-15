package dev.scyphoz.dappermap.dynamic;

import java.awt.Color;

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

    public enum Style {
        DOT,
        DEATH,
        BETTERMAP,
        NATIVE_WORLDMAP
    }
}

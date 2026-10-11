package dev.scyphoz.dappermap.dynamic;

import java.awt.Color;

/** Immutable world-space marker description consumed by the session's HUD projection step. */
public final class DynamicMinimapMarker {
    public final double x;
    public final double z;
    public final Color color;
    public final int size;
    public final Style style;
    public final String assetPath;
    public final boolean clampToEdge;
    /** Hostile or attacking creature: drawn in the HUD's top marker layer, above other markers. */
    public final boolean threat;

    private DynamicMinimapMarker(
            double x,
            double z,
            Color color,
            int size,
            Style style,
            String assetPath,
            boolean clampToEdge
    ) {
        this(x, z, color, size, style, assetPath, clampToEdge, false);
    }

    private DynamicMinimapMarker(
            double x,
            double z,
            Color color,
            int size,
            Style style,
            String assetPath,
            boolean clampToEdge,
            boolean threat
    ) {
        this.x = x;
        this.z = z;
        this.color = color;
        this.size = size;
        this.style = style;
        this.assetPath = assetPath;
        this.clampToEdge = clampToEdge;
        this.threat = threat;
    }

    public static DynamicMinimapMarker dot(double x, double z, Color color, int size) {
        return new DynamicMinimapMarker(x, z, color, size, Style.DOT, null, false);
    }

    /** Creature dot; {@code threat} marks hostile and attacking creatures (drawn on top). */
    public static DynamicMinimapMarker creatureDot(double x, double z, Color color, int size, boolean threat) {
        return new DynamicMinimapMarker(x, z, color, size, Style.DOT, null, false, threat);
    }

    /**
     * Creature face icon; {@code iconKey} identifies the creature type (see DynamicCreatureIcons) and
     * {@code color} is its threat colour, used for the icon's outer edge.
     */
    public static DynamicMinimapMarker creature(double x, double z, String iconKey, Color color, int size,
                                                boolean threat) {
        return new DynamicMinimapMarker(x, z, color, size, Style.CREATURE, iconKey, false, threat);
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
                && threat == marker.threat
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
        hash = 31 * hash + Boolean.hashCode(threat);
        return 31 * hash + Boolean.hashCode(clampToEdge);
    }

    public enum Style {
        DOT,
        /** Creature face icon (Creature Markers: Icons). */
        CREATURE,
        BETTERMAP,
        NATIVE_WORLDMAP
    }
}

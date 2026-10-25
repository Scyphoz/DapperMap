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
    /** Another player (icon or dot): drawn in the HUD's player layer, above every creature marker. */
    public final boolean player;
    /**
     * Compass alert (Hytale 0.7 CompassAlertComponent) on a world-map marker: the marker's id,
     * or null when it has no alert. The alert pulses the marker for {@link #alertMs} after it
     * first appears (see DynamicMinimapSession).
     */
    public final String alertId;
    /** Alert duration in milliseconds (0 without an alert). */
    public final int alertMs;
    /**
     * The marker as drawn during the "on" half of an alert pulse (alert icon and/or tint), or
     * null when the alert keeps the marker's own look (then only its size pulses).
     */
    public final DynamicMinimapMarker alertLook;

    /** The one constructor for a marker without an alert (the factories below pick the flags). */
    private DynamicMinimapMarker(double x, double z, Color color, int size, Style style, String assetPath,
                                 boolean clampToEdge, boolean threat, boolean player) {
        this.x = x;
        this.z = z;
        this.color = color;
        this.size = size;
        this.style = style;
        this.assetPath = assetPath;
        this.clampToEdge = clampToEdge;
        this.threat = threat;
        this.player = player;
        this.alertId = null;
        this.alertMs = 0;
        this.alertLook = null;
    }

    /** Copy of {@code base} carrying a compass alert. */
    private DynamicMinimapMarker(DynamicMinimapMarker base, String alertId, int alertMs, DynamicMinimapMarker alertLook) {
        this.x = base.x;
        this.z = base.z;
        this.color = base.color;
        this.size = base.size;
        this.style = base.style;
        this.assetPath = base.assetPath;
        this.clampToEdge = base.clampToEdge;
        this.threat = base.threat;
        this.player = base.player;
        this.alertId = alertId;
        this.alertMs = alertMs;
        this.alertLook = alertLook;
    }

    /**
     * This world-map marker with a compass alert. {@code image}/{@code tint}: the alert's icon
     * and tint, each null to keep the marker's own; the alert look is built once here.
     */
    public DynamicMinimapMarker withAlert(String id, String image, Color tint, int durationMs) {
        if (id == null || durationMs <= 0) return this;
        DynamicMinimapMarker look = image == null && tint == null ? null
                : new DynamicMinimapMarker(x, z, tint != null ? tint : color, size, style,
                image != null ? image : assetPath, clampToEdge, threat, player);
        return new DynamicMinimapMarker(this, id, durationMs, look);
    }

    /** Marker colour as 0xRRGGBB (white when it has none). */
    public int rgb() {
        return this.color == null ? 0xFFFFFF : this.color.getRGB() & 0xFFFFFF;
    }

    public static DynamicMinimapMarker dot(double x, double z, Color color, int size) {
        return new DynamicMinimapMarker(x, z, color, size, Style.DOT, null, false, false, false);
    }

    /** Creature dot; {@code threat} marks hostile and attacking creatures (drawn on top). */
    public static DynamicMinimapMarker creatureDot(double x, double z, Color color, int size, boolean threat) {
        return new DynamicMinimapMarker(x, z, color, size, Style.DOT, null, false, threat, false);
    }

    /**
     * Creature face icon; {@code iconKey} identifies the creature type (see DynamicCreatureIcons) and
     * {@code color} is its threat colour, used for the icon's outer edge.
     */
    public static DynamicMinimapMarker creature(double x, double z, String iconKey, Color color, int size,
                                                boolean threat) {
        return new DynamicMinimapMarker(x, z, color, size, Style.CREATURE, iconKey, false, threat, false);
    }

    /** Another player's dot, in the player colour (or the attacking colour after a PvP hit). */
    public static DynamicMinimapMarker playerDot(double x, double z, Color color, int size) {
        return new DynamicMinimapMarker(x, z, color, size, Style.DOT, null, false, false, true);
    }

    /**
     * Another player's head icon; {@code iconKey} identifies their look (skin and helmet, see
     * DynamicCreatureIcons.PlayerKeyCache) and {@code color} edges the icon.
     */
    public static DynamicMinimapMarker player(double x, double z, String iconKey, Color color, int size) {
        return new DynamicMinimapMarker(x, z, color, size, Style.PLAYER, iconKey, false, false, true);
    }

    public static DynamicMinimapMarker betterMap(
            double x,
            double z,
            String assetPath,
            Color color,
            int size
    ) {
        return new DynamicMinimapMarker(x, z, color, size, Style.BETTERMAP, assetPath, true, false, false);
    }

    public static DynamicMinimapMarker nativeWorldMap(
            double x,
            double z,
            String assetPath,
            Color color,
            int size
    ) {
        return new DynamicMinimapMarker(x, z, color, size, Style.NATIVE_WORLDMAP, assetPath, true, false, false);
    }

    /** Marker scans create fresh objects even when their map data is unchanged. */
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DynamicMinimapMarker marker)) return false;
        return Double.compare(x, marker.x) == 0 && Double.compare(z, marker.z) == 0
                && size == marker.size && style == marker.style && clampToEdge == marker.clampToEdge
                && threat == marker.threat && player == marker.player
                && java.util.Objects.equals(color, marker.color)
                && java.util.Objects.equals(assetPath, marker.assetPath)
                && alertMs == marker.alertMs && java.util.Objects.equals(alertId, marker.alertId)
                && java.util.Objects.equals(alertLook, marker.alertLook);
    }

    @Override public int hashCode() {
        int hash = 31 + Double.hashCode(x);
        hash = 31 * hash + Double.hashCode(z);
        hash = 31 * hash + java.util.Objects.hashCode(color);
        hash = 31 * hash + size;
        hash = 31 * hash + java.util.Objects.hashCode(style);
        hash = 31 * hash + java.util.Objects.hashCode(assetPath);
        hash = 31 * hash + Boolean.hashCode(threat);
        hash = 31 * hash + Boolean.hashCode(player);
        hash = 31 * hash + java.util.Objects.hashCode(alertId);
        return 31 * hash + Boolean.hashCode(clampToEdge);
    }

    public enum Style {
        DOT,
        /** Creature face icon (Creature Markers: Icons). */
        CREATURE,
        /** Another player's head icon (Player Markers: Icons). */
        PLAYER,
        BETTERMAP,
        NATIVE_WORLDMAP
    }
}

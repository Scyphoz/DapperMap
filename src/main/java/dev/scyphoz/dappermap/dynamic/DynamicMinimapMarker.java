package dev.scyphoz.dappermap.dynamic;

import java.awt.Color;

public final class DynamicMinimapMarker {
    public static final Color DEATH_COLOR = new Color(255, 255, 255, 235);
    public final double x;
    public final double z;
    public final Color color;
    public final int size;
    public final Style style;

    private DynamicMinimapMarker(double x, double z, Color color, int size, Style style) {
        this.x = x;
        this.z = z;
        this.color = color;
        this.size = size;
        this.style = style;
    }

    public static DynamicMinimapMarker dot(double x, double z, Color color, int size) {
        return new DynamicMinimapMarker(x, z, color, size, DynamicMinimapMarker.Style.DOT);
    }

    public static DynamicMinimapMarker death(double x, double z, int size) {
        return new DynamicMinimapMarker(x, z, DEATH_COLOR, size, DynamicMinimapMarker.Style.DEATH);
    }

    public static enum Style {
        DOT,
        DEATH;

        private Style() {
        }
    }
}

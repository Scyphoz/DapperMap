package dev.scyphoz.dappermap.dynamic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reuses marker ordering while an immutable scan snapshot and buffer centre stay unchanged. */
final class MarkerOrder {
    private List<DynamicMinimapMarker> source;
    private List<DynamicMinimapMarker> ordered = List.of();
    private double centerX = Double.NaN, centerZ = Double.NaN;

    List<DynamicMinimapMarker> get(List<DynamicMinimapMarker> snapshot, double x, double z) {
        if (snapshot != source || Double.compare(centerX, x) != 0 || Double.compare(centerZ, z) != 0) {
            List<DynamicMinimapMarker> sorted = new ArrayList<>(snapshot);
            sorted.sort(Comparator.comparingInt((DynamicMinimapMarker marker) -> switch (marker.style) {
                case DEATH -> 0;
                case BETTERMAP, NATIVE_WORLDMAP -> 1;
                case DOT -> 2;
            }).thenComparingDouble(marker -> {
                double dx = marker.x - x, dz = marker.z - z;
                return dx * dx + dz * dz;
            }));
            source = snapshot;
            ordered = sorted;
            centerX = x;
            centerZ = z;
        }
        return ordered;
    }

    void clear() {
        source = null;
        ordered = List.of();
        centerX = centerZ = Double.NaN;
    }
}

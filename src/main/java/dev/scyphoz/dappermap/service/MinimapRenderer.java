package dev.scyphoz.dappermap.service;

import dev.scyphoz.dappermap.model.MapMarker;
import dev.scyphoz.dappermap.model.MinimapSettings;
import java.util.List;

public class MinimapRenderer {
    public MinimapRenderer() {
    }

    public MinimapFrame buildFrame(MinimapSettings settings, int playerX, int playerZ, float yawDegrees, List<MapMarker> markers) {
        return new MinimapFrame(settings, playerX, playerZ, yawDegrees, List.copyOf(markers));
    }

    public static record MinimapFrame(MinimapSettings settings, int playerX, int playerZ, float yawDegrees, List<MapMarker> markers) {
    }
}

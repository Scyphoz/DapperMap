package dev.scyphoz.dappermap.model;

import dev.scyphoz.dappermap.config.MinimapConfig;

public record MinimapSettings(boolean enabled, int radiusBlocks, int updateIntervalTicks, boolean showCoordinates, boolean showWaypoints, MapShape shape) {
    public static MinimapSettings defaults() {
        return new MinimapSettings(true, 96, 10, true, true, MapShape.ROUND);
    }

    public static MinimapSettings fromConfig(MinimapConfig config) {
        return new MinimapSettings(config.isEnabledByDefault(), config.getDefaultRadiusBlocks(), config.getUpdateIntervalTicks(), config.isShowCoordinates(), config.isShowWaypoints(), MapShape.ROUND);
    }

    public MinimapSettings withEnabled(boolean enabled) {
        return new MinimapSettings(enabled, this.radiusBlocks, this.updateIntervalTicks, this.showCoordinates, this.showWaypoints, this.shape);
    }
}

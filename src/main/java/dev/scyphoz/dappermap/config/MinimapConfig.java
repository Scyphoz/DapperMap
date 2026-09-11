package dev.scyphoz.dappermap.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public class MinimapConfig {

    public static final BuilderCodec<MinimapConfig> CODEC;

    private boolean enabledByDefault = true;
    private int defaultRadiusBlocks = 96;
    private int updateIntervalTicks = 10;
    private boolean showCoordinates = true;
    private boolean showWaypoints = true;

    private MinimapConfig() {
    }

    public boolean isEnabledByDefault() {
        return this.enabledByDefault;
    }

    public int getDefaultRadiusBlocks() {
        return this.defaultRadiusBlocks;
    }

    public int getUpdateIntervalTicks() {
        return this.updateIntervalTicks;
    }

    public boolean isShowCoordinates() {
        return this.showCoordinates;
    }

    public boolean isShowWaypoints() {
        return this.showWaypoints;
    }

    static {
        CODEC = BuilderCodec.builder(MinimapConfig.class, MinimapConfig::new)
                .append(
                        new KeyedCodec<>("EnabledByDefault", Codec.BOOLEAN),
                        (config, value, extraInfo) -> config.enabledByDefault = value,
                        (config, extraInfo) -> config.enabledByDefault
                )
                .add()
                .append(
                        new KeyedCodec<>("DefaultRadiusBlocks", Codec.INTEGER),
                        (config, value, extraInfo) -> config.defaultRadiusBlocks = value,
                        (config, extraInfo) -> config.defaultRadiusBlocks
                )
                .add()
                .append(
                        new KeyedCodec<>("UpdateIntervalTicks", Codec.INTEGER),
                        (config, value, extraInfo) -> config.updateIntervalTicks = value,
                        (config, extraInfo) -> config.updateIntervalTicks
                )
                .add()
                .append(
                        new KeyedCodec<>("ShowCoordinates", Codec.BOOLEAN),
                        (config, value, extraInfo) -> config.showCoordinates = value,
                        (config, extraInfo) -> config.showCoordinates
                )
                .add()
                .append(
                        new KeyedCodec<>("ShowWaypoints", Codec.BOOLEAN),
                        (config, value, extraInfo) -> config.showWaypoints = value,
                        (config, extraInfo) -> config.showWaypoints
                )
                .add()
                .build();
    }
}
package dev.scyphoz.dappermap.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public class MinimapConfig {

    public static final BuilderCodec<MinimapConfig> CODEC;

    private boolean enabledByDefault = true;
    private int defaultRadiusBlocks = 96;
    private boolean showCoordinates = true;

    private MinimapConfig() {
    }

    public boolean isEnabledByDefault() {
        return this.enabledByDefault;
    }

    public int getDefaultRadiusBlocks() {
        return this.defaultRadiusBlocks;
    }

    public boolean isShowCoordinates() {
        return this.showCoordinates;
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
                        new KeyedCodec<>("ShowCoordinates", Codec.BOOLEAN),
                        (config, value, extraInfo) -> config.showCoordinates = value,
                        (config, extraInfo) -> config.showCoordinates
                )
                .add()
                .build();
    }
}

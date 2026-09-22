package dev.scyphoz.dappermap.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** Server-wide bootstrap values persisted by Hytale's codec-backed plugin configuration. */
public class MinimapConfig {

    public static final BuilderCodec<MinimapConfig> CODEC;

    private boolean enabledByDefault = true;
    private int defaultRadiusBlocks = 96;
    private boolean showCoordinates = true;
    /** When false, player commands/settings require explicit permission grants. */
    private boolean grantPlayerSettingsByDefault = true;

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

    public boolean isGrantPlayerSettingsByDefault() {
        return this.grantPlayerSettingsByDefault;
    }

    // Map external configuration keys to the private Java fields above.
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
                .append(
                        new KeyedCodec<>("GrantPlayerSettingsByDefault", Codec.BOOLEAN),
                        (config, value, extraInfo) -> config.grantPlayerSettingsByDefault = value,
                        (config, extraInfo) -> config.grantPlayerSettingsByDefault
                )
                .add()
                .build();
    }
}

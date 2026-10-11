package dev.scyphoz.dappermap.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import static dev.scyphoz.dappermap.util.DapperMapUtil.clamp;

/**
 * Server-wide values persisted by Hytale's codec-backed plugin configuration
 * ({@code dappermap_minimap.json}): bootstrap defaults, admin limits on expensive player
 * settings, adaptive load shedding and the creature icon disk cache.
 *
 * <p>Every key is optional, so configuration files written by older versions load with the
 * defaults below (which impose no limits) and gain the new keys on the next save.
 */
public class DapperMapConfig {

    public static final BuilderCodec<DapperMapConfig> CODEC;

    /** Update speed indexes, fastest first (matches DynamicMinimapSetting.updateSpeed). */
    public static final String[] SPEED_NAMES = {"very-fast", "fast", "normal", "slow"};

    private boolean enabledByDefault = true;
    private int defaultRadiusBlocks = 96;
    private boolean showCoordinates = true;
    /** When false, player commands/settings require explicit permission grants. */
    private boolean grantPlayerSettingsByDefault = true;

    // Admin limits. Defaults are the widest allowed values, i.e. no limit.
    /** Fastest update speed players may use: 0 very fast (no limit) .. 3 slow. */
    private int fastestUpdateSpeed = 0;
    private int maxTextureResolution = 1024;
    private int maxZoomRadius = 256;
    private int maxCreatureLimit = 64;

    // Adaptive load shedding (off by default).
    private boolean loadShedding = false;
    /** Shed load when more than this many players have a minimap; 0 ignores player count. */
    private int loadSheddingPlayers = 0;
    /** Shed load when the render queue rejected at least this much work in the last minute. */
    private int loadSheddingRejectionsPerMinute = 30;

    // Creature icon disk cache.
    private boolean creatureIconDiskCache = true;
    private int creatureIconDiskCacheMb = 16;

    /** PNG deflate level for generated images: higher = less data sent, more render CPU. */
    private int pngCompressionLevel = dev.scyphoz.dappermap.util.DapperMapUtil.DEFAULT_PNG_COMPRESSION;
    /** Colours kept in full-colour terrain images (32..256); 0 sends full colour. */
    private int terrainColours = 256;

    private DapperMapConfig() {
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

    // ------------------------------------------------------------------------------------
    // Admin limits
    // ------------------------------------------------------------------------------------

    public int getFastestUpdateSpeed() { return clamp(this.fastestUpdateSpeed, 0, 3); }
    public int getMaxTextureResolution() { return clamp(this.maxTextureResolution, 256, 1024); }
    public int getMaxZoomRadius() { return clamp(this.maxZoomRadius, 16, 256); }
    public int getMaxCreatureLimit() { return creatureLimit(this.maxCreatureLimit); }

    /** Sets the four limits (validated); callers save the config afterwards. */
    public void setLimits(int fastestUpdateSpeed, int maxTextureResolution, int maxZoomRadius, int maxCreatureLimit) {
        this.fastestUpdateSpeed = clamp(fastestUpdateSpeed, 0, 3);
        this.maxTextureResolution = clamp(maxTextureResolution, 256, 1024);
        this.maxZoomRadius = clamp(maxZoomRadius, 16, 256);
        this.maxCreatureLimit = creatureLimit(maxCreatureLimit);
    }

    /** True when at least one limit is narrower than the full range. */
    public boolean hasLimits() {
        return this.getFastestUpdateSpeed() > 0 || this.getMaxTextureResolution() < 1024
                || this.getMaxZoomRadius() < 256 || this.getMaxCreatureLimit() < 64;
    }

    /**
     * Clamps a live session's settings to the admin limits, in place. Only ever applied to
     * a session's own copy, so the player's saved choice returns when a limit is raised.
     */
    public DynamicMinimapSetting applyLimits(DynamicMinimapSetting settings) {
        // Higher index = slower, so the limit is a minimum index.
        settings.updateSpeed = Math.max(settings.updateSpeed, this.getFastestUpdateSpeed());
        settings.textureResolution = Math.min(settings.textureResolution, this.getMaxTextureResolution());
        settings.zoomBlocks = Math.min(settings.zoomBlocks, this.getMaxZoomRadius());
        settings.creatureMarkerLimit = Math.min(settings.creatureMarkerLimit, this.getMaxCreatureLimit());
        return settings;
    }

    /** "speed fast, resolution 512 px" for the limits a player's own settings exceed; "" when none apply. */
    public String describeLimitsApplied(DynamicMinimapSetting settings) {
        StringBuilder out = new StringBuilder();
        if (settings.updateSpeed < this.getFastestUpdateSpeed())
            append(out, "speed " + SPEED_NAMES[this.getFastestUpdateSpeed()]);
        if (settings.textureResolution > this.getMaxTextureResolution())
            append(out, "resolution " + this.getMaxTextureResolution() + " px");
        if (settings.zoomBlocks > this.getMaxZoomRadius())
            append(out, "zoom " + this.getMaxZoomRadius() + " blocks");
        if (settings.creatureMarkerLimit > this.getMaxCreatureLimit())
            append(out, "creature limit " + this.getMaxCreatureLimit());
        return out.toString();
    }

    /** All four limits for status and help output. */
    public String describeLimits() {
        return "fastest speed " + SPEED_NAMES[this.getFastestUpdateSpeed()]
                + ", max resolution " + this.getMaxTextureResolution() + " px"
                + ", max zoom " + this.getMaxZoomRadius() + " blocks"
                + ", max creature limit " + this.getMaxCreatureLimit();
    }

    // ------------------------------------------------------------------------------------
    // Load shedding and icon cache
    // ------------------------------------------------------------------------------------

    public boolean isLoadShedding() { return this.loadShedding; }
    public void setLoadShedding(boolean enabled) { this.loadShedding = enabled; }
    public int getLoadSheddingPlayers() { return Math.max(0, this.loadSheddingPlayers); }
    public int getLoadSheddingRejectionsPerMinute() { return Math.max(1, this.loadSheddingRejectionsPerMinute); }
    public boolean isCreatureIconDiskCache() { return this.creatureIconDiskCache; }
    public long getCreatureIconDiskCacheBytes() { return clamp(this.creatureIconDiskCacheMb, 1, 512) * 1024L * 1024L; }
    public int getPngCompressionLevel() { return clamp(this.pngCompressionLevel, 1, 9); }
    public int getTerrainColours() { return this.terrainColours <= 0 ? 0 : clamp(this.terrainColours, 32, 256); }

    private static void append(StringBuilder out, String text) {
        if (!out.isEmpty()) out.append(", ");
        out.append(text);
    }

    /** Creature limits are 16, 32 or 64; anything else rounds down to the nearest of those. */
    private static int creatureLimit(int value) {
        return value >= 64 ? 64 : value >= 32 ? 32 : 16;
    }

    // Map external configuration keys to the private Java fields above.
    static {
        CODEC = BuilderCodec.builder(DapperMapConfig.class, DapperMapConfig::new)
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
                // Admin limits. FastestUpdateSpeed is stored by name (very-fast|fast|normal|slow).
                .append(
                        new KeyedCodec<>("FastestUpdateSpeed", Codec.STRING),
                        (config, value, extraInfo) -> config.fastestUpdateSpeed = speedIndex(value),
                        (config, extraInfo) -> SPEED_NAMES[config.getFastestUpdateSpeed()]
                )
                .add()
                .append(
                        new KeyedCodec<>("MaxTextureResolution", Codec.INTEGER),
                        (config, value, extraInfo) -> config.maxTextureResolution = value,
                        (config, extraInfo) -> config.getMaxTextureResolution()
                )
                .add()
                .append(
                        new KeyedCodec<>("MaxZoomRadius", Codec.INTEGER),
                        (config, value, extraInfo) -> config.maxZoomRadius = value,
                        (config, extraInfo) -> config.getMaxZoomRadius()
                )
                .add()
                .append(
                        new KeyedCodec<>("MaxCreatureLimit", Codec.INTEGER),
                        (config, value, extraInfo) -> config.maxCreatureLimit = value,
                        (config, extraInfo) -> config.getMaxCreatureLimit()
                )
                .add()
                .append(
                        new KeyedCodec<>("LoadShedding", Codec.BOOLEAN),
                        (config, value, extraInfo) -> config.loadShedding = value,
                        (config, extraInfo) -> config.loadShedding
                )
                .add()
                .append(
                        new KeyedCodec<>("LoadSheddingPlayers", Codec.INTEGER),
                        (config, value, extraInfo) -> config.loadSheddingPlayers = value,
                        (config, extraInfo) -> config.getLoadSheddingPlayers()
                )
                .add()
                .append(
                        new KeyedCodec<>("LoadSheddingRejectionsPerMinute", Codec.INTEGER),
                        (config, value, extraInfo) -> config.loadSheddingRejectionsPerMinute = value,
                        (config, extraInfo) -> config.getLoadSheddingRejectionsPerMinute()
                )
                .add()
                .append(
                        new KeyedCodec<>("CreatureIconDiskCache", Codec.BOOLEAN),
                        (config, value, extraInfo) -> config.creatureIconDiskCache = value,
                        (config, extraInfo) -> config.creatureIconDiskCache
                )
                .add()
                .append(
                        new KeyedCodec<>("CreatureIconDiskCacheMB", Codec.INTEGER),
                        (config, value, extraInfo) -> config.creatureIconDiskCacheMb = value,
                        (config, extraInfo) -> config.creatureIconDiskCacheMb
                )
                .add()
                .append(
                        new KeyedCodec<>("PngCompressionLevel", Codec.INTEGER),
                        (config, value, extraInfo) -> config.pngCompressionLevel = value,
                        (config, extraInfo) -> config.getPngCompressionLevel()
                )
                .add()
                .append(
                        new KeyedCodec<>("TerrainColours", Codec.INTEGER),
                        (config, value, extraInfo) -> config.terrainColours = value,
                        (config, extraInfo) -> config.getTerrainColours()
                )
                .add()
                .build();
    }

    /** Speed name (or 0-3) to index; unknown values mean no limit. */
    public static int speedIndex(String value) {
        if (value == null) return 0;
        String name = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < SPEED_NAMES.length; i++) if (SPEED_NAMES[i].equals(name)) return i;
        return switch (name) {
            case "veryfast", "vf", "none", "off" -> 0;
            case "medium" -> 2;
            case "1", "2", "3" -> Integer.parseInt(name);
            default -> 0;
        };
    }
}

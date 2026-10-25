package dev.scyphoz.dappermap.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.util.DapperMapUtil;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting.RESOLUTION_MAX;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting.RESOLUTION_MIN;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting.SPEED_FASTEST;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting.SPEED_SLOWEST;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting.ZOOM_MAX;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting.ZOOM_MIN;
import static dev.scyphoz.dappermap.util.DapperMapUtil.clamp;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

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

    /**
     * The server limits on expensive player settings, one entry each. The config file, the
     * {@code admin limit} command, status text and both settings pages all read this table.
     */
    public enum Limit {
        // Speed is a minimum index (higher = slower); the others are maximums.
        SPEED("FastestUpdateSpeed", "speed", "speed", "speed", "speed", "fastest speed", "",
                SPEED_FASTEST, SPEED_SLOWEST, SPEED_FASTEST, true, s -> s.updateSpeed, (s, v) -> s.updateSpeed = v),
        RESOLUTION("MaxTextureResolution", "resolution", "resolution", "texture_resolution", "resolution", "max resolution", " px",
                RESOLUTION_MIN, RESOLUTION_MAX, RESOLUTION_MAX, false, s -> s.textureResolution, (s, v) -> s.textureResolution = v),
        ZOOM("MaxZoomRadius", "zoom", "zoom", "zoom", "zoom", "max zoom", " blocks",
                ZOOM_MIN, ZOOM_MAX, ZOOM_MAX, false, s -> s.zoomBlocks, (s, v) -> s.zoomBlocks = v),
        CREATURES("MaxCreatureLimit", "creatures", "creature-limit", "creature_limit", "creature limit", "max creature limit", "",
                16, 64, 64, false, s -> s.creatureMarkerLimit, (s, v) -> s.creatureMarkerLimit = v);

        /** Key in dappermap_minimap.json. */
        public final String configKey;
        /** Name in {@code /dappermap admin limit <name>}; the admin page row is "limit_" + this. */
        public final String command;
        /** The player setting command it limits (e.g. "creature-limit"). */
        public final String settingCommand;
        /** The player settings-page row it limits. */
        public final String pageRow;
        /** Labels in "limits applied" and "all limits" text. */
        public final String appliedLabel, statusLabel;
        /** Unit after a value (" px", " blocks"); speeds are shown by name. */
        public final String unit;
        public final int min, max;
        /** The value that limits nothing (the widest setting). */
        public final int noLimit;
        /** True when players may not go below the limit (speed), false when they may not go above it. */
        public final boolean minimum;
        private final ToIntFunction<DynamicMinimapSetting> getter;
        private final ObjIntConsumer<DynamicMinimapSetting> setter;
        private static final Limit[] ALL = values();

        Limit(String configKey, String command, String settingCommand, String pageRow, String appliedLabel,
              String statusLabel, String unit, int min, int max, int noLimit, boolean minimum,
              ToIntFunction<DynamicMinimapSetting> getter, ObjIntConsumer<DynamicMinimapSetting> setter) {
            this.configKey = configKey;
            this.command = command;
            this.settingCommand = settingCommand;
            this.pageRow = pageRow;
            this.appliedLabel = appliedLabel;
            this.statusLabel = statusLabel;
            this.unit = unit;
            this.min = min;
            this.max = max;
            this.noLimit = noLimit;
            this.minimum = minimum;
            this.getter = getter;
            this.setter = setter;
        }

        /** Brings a stored value into range (creature limits round down to 16, 32 or 64). */
        public int clamp(int value) {
            if (this == CREATURES) return value >= 64 ? 64 : value >= 32 ? 32 : 16;
            return DapperMapUtil.clamp(value, this.min, this.max);
        }

        /** True when a player's own value goes past this limit. */
        public boolean exceeds(int playerValue, int limit) {
            return this.minimum ? playerValue < limit : playerValue > limit;
        }

        /** "fast", "512 px", "128 blocks" or "32". */
        public String text(int value) {
            return this == SPEED ? SPEED_NAMES[this.clamp(value)] : value + this.unit;
        }

        /** Parses a command value ("fast", "512"); -1 when it isn't one this limit accepts. */
        public int parse(String value) {
            if (this == SPEED) return DapperMapUtil.indexOf(SPEED_NAMES, value);
            try {
                int parsed = Integer.parseInt(value);
                boolean valid = parsed >= this.min && parsed <= this.max
                        && (this != CREATURES || DynamicMinimapSetting.isCreatureLimit(parsed));
                return valid ? parsed : -1;
            } catch (NumberFormatException notANumber) {
                return -1;
            }
        }

        public int get(DynamicMinimapSetting settings) { return this.getter.applyAsInt(settings); }

        /** By {@code admin limit} name; null when unknown. */
        public static Limit byCommand(String name) {
            for (Limit limit : ALL) if (limit.command.equals(name)) return limit;
            return null;
        }

        /** By the player setting command it limits; null for settings without a limit. */
        public static Limit bySettingCommand(String name) {
            for (Limit limit : ALL) if (limit.settingCommand.equals(name)) return limit;
            return null;
        }

        /** By player settings-page row id; null for rows without a limit. */
        public static Limit byPageRow(String id) {
            for (Limit limit : ALL) if (limit.pageRow.equals(id)) return limit;
            return null;
        }

        /** By admin settings-page row id ("limit_zoom"); null for other rows. */
        public static Limit byAdminRow(String id) {
            return id.startsWith("limit_") ? byCommand(id.substring("limit_".length())) : null;
        }
    }

    // Admin limits, indexed by Limit ordinal. Defaults are the widest values, i.e. no limit.
    private final int[] limits = {SPEED_FASTEST, RESOLUTION_MAX, ZOOM_MAX, 64};

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

    /** One limit's current value (validated). */
    public int limit(Limit limit) {
        return limit.clamp(this.limits[limit.ordinal()]);
    }

    /** True when this limit holds players back (it isn't the widest value). */
    public boolean isLimited(Limit limit) {
        return this.limit(limit) != limit.noLimit;
    }

    /** All limits, indexed by Limit ordinal (a copy; the settings page edits it as its draft). */
    public int[] limits() {
        int[] values = new int[Limit.ALL.length];
        for (Limit limit : Limit.ALL) values[limit.ordinal()] = this.limit(limit);
        return values;
    }

    /** Sets one limit (validated); callers save the config afterwards. */
    public void setLimit(Limit limit, int value) {
        this.limits[limit.ordinal()] = limit.clamp(value);
    }

    /** Sets every limit from {@link #limits()}-ordered values; callers save the config afterwards. */
    public void setLimits(int[] values) {
        for (Limit limit : Limit.ALL) this.setLimit(limit, values[limit.ordinal()]);
    }

    /** True when at least one limit is narrower than the full range. */
    public boolean hasLimits() {
        for (Limit limit : Limit.ALL) if (this.isLimited(limit)) return true;
        return false;
    }

    /**
     * Clamps a live session's settings to the admin limits, in place. Only ever applied to
     * a session's own copy, so the player's saved choice returns when a limit is raised.
     */
    public DynamicMinimapSetting applyLimits(DynamicMinimapSetting settings) {
        for (Limit limit : Limit.ALL) {
            int value = this.limit(limit);
            if (limit.exceeds(limit.get(settings), value)) limit.setter.accept(settings, value);
        }
        return settings;
    }

    /** "speed fast, resolution 512 px" for the limits a player's own settings exceed; "" when none apply. */
    public String describeLimitsApplied(DynamicMinimapSetting settings) {
        StringBuilder out = new StringBuilder();
        for (Limit limit : Limit.ALL) {
            int value = this.limit(limit);
            if (limit.exceeds(limit.get(settings), value)) append(out, limit.appliedLabel + " " + limit.text(value));
        }
        return out.toString();
    }

    /** All four limits for status and help output. */
    public String describeLimits() {
        StringBuilder out = new StringBuilder();
        for (Limit limit : Limit.ALL) append(out, limit.statusLabel + " " + limit.text(this.limit(limit)));
        return out.toString();
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
                        new KeyedCodec<>(Limit.SPEED.configKey, Codec.STRING),
                        (config, value, extraInfo) -> config.limits[Limit.SPEED.ordinal()] = speedIndex(value),
                        (config, extraInfo) -> SPEED_NAMES[config.limit(Limit.SPEED)]
                )
                .add()
                .append(
                        new KeyedCodec<>(Limit.RESOLUTION.configKey, Codec.INTEGER),
                        (config, value, extraInfo) -> config.limits[Limit.RESOLUTION.ordinal()] = value,
                        (config, extraInfo) -> config.limit(Limit.RESOLUTION)
                )
                .add()
                .append(
                        new KeyedCodec<>(Limit.ZOOM.configKey, Codec.INTEGER),
                        (config, value, extraInfo) -> config.limits[Limit.ZOOM.ordinal()] = value,
                        (config, extraInfo) -> config.limit(Limit.ZOOM)
                )
                .add()
                .append(
                        new KeyedCodec<>(Limit.CREATURES.configKey, Codec.INTEGER),
                        (config, value, extraInfo) -> config.limits[Limit.CREATURES.ordinal()] = value,
                        (config, extraInfo) -> config.limit(Limit.CREATURES)
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

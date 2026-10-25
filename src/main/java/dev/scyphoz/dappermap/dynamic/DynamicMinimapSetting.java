package dev.scyphoz.dappermap.dynamic;

import dev.scyphoz.dappermap.config.DapperMapConfig;
import static dev.scyphoz.dappermap.util.DapperMapUtil.clamp;

/** Mutable per-player (or administrator-default) settings consumed by every minimap subsystem. */
public final class DynamicMinimapSetting {
    public static final int SHAPE_SQUARE = 0;
    public static final int SHAPE_CIRCLE = 1;
    public static final int POS_TOP_LEFT = 0;
    public static final int POS_TOP_RIGHT = 1;
    public static final int POS_BOTTOM_LEFT = 2;
    public static final int POS_BOTTOM_RIGHT = 3;
    public static final int ROTATION_NORTH_UP = 0;
    public static final int ROTATION_PLAYER_UP = 1;

    public static final int INFO_BELOW = 0;
    public static final int INFO_ABOVE = 1;
    public static final int INFO_LEFT = 2;
    public static final int INFO_RIGHT = 3;

    public static final int INFO_ALIGN_LEFT = 0;
    public static final int INFO_ALIGN_CENTER = 1;
    public static final int INFO_ALIGN_RIGHT = 2;

    // Value ranges, shared by normalize(), the admin limits, commands, the settings page and the HUD.
    public static final int ZOOM_MIN = 16, ZOOM_MAX = 256;
    public static final int SIZE_MIN = 120, SIZE_MAX = 360;
    public static final int SCALE_MIN = 75, SCALE_MAX = 160;
    public static final int RESOLUTION_MIN = 256, RESOLUTION_MAX = 1024;
    public static final int HEIGHT_RANGE_MIN = 4, HEIGHT_RANGE_MAX = 256;
    /** HUD position percentages: 0 = left/top edge, 100 = right/bottom edge. */
    public static final int POSITION_MIN = 0, POSITION_MAX = 100;
    /** Update speeds: 0 very fast .. 3 slow (DapperMapConfig.SPEED_NAMES). */
    public static final int SPEED_FASTEST = 0, SPEED_SLOWEST = 3;
    /** Creature marker limits a player can choose (the largest is "no limit" for admins). */
    public static final int[] CREATURE_LIMITS = {16, 32, 64};
    /** Texture resolutions offered by the settings page; every one has a pre-published clip mask. */
    public static final int[] TEXTURE_RESOLUTIONS = {256, 320, 384, 448, 512, 1024};

    // Command and status names, indexed by each setting's value.
    public static final String[] SHAPE_NAMES = {"square", "circle"};
    public static final String[] ROTATION_NAMES = {"north", "player"};
    public static final String[] PANEL_POSITION_NAMES = {"below", "above", "left", "right"};
    public static final String[] PANEL_ALIGNMENT_NAMES = {"left", "centre", "right"};

    /** True for one of {@link #CREATURE_LIMITS}. */
    public static boolean isCreatureLimit(int value) {
        return value == 16 || value == 32 || value == 64;
    }
    public volatile DynamicMinimapColours colours = DynamicMinimapColours.defaults();
    public volatile boolean enabled = true;
    public int rotationMode = 0;
    public int zoomBlocks = 96;
    /** Logical Hytale UI size of the minimap viewport. */
    public int sizePixels = 220;
    /** Raster resolution used to render minimap textures. Kept separate from UI size. */
    public int textureResolution = 384;
    /**
     * Presentation-only scale for the complete custom HUD.
     * 100 means the logical minimap/UI dimensions are sent unchanged.
     * This must not be used by world-space or terrain sampling calculations.
     */
    public int interfaceScalePercent = 100;
    public int shape = 1;
    public boolean showOtherPlayers = true;
    public boolean showEntities = true;
    public int creatureMarkerLimit = 16;
    /** Creature marker style: true = creature face icons (default), false = coloured threat dots. */
    public boolean creatureIcons = true;
    /** Creature markers shown: false = all creatures (default), true = hostile and attacking only. */
    public boolean hostileCreaturesOnly = false;
    /** Player marker style: true = head icons drawn from each player's skin (default), false = dots. */
    public boolean playerIcons = true;
    /** Player head icons show the helmet a player wears (default), or always their bare head. */
    public boolean playerIconHelmets = true;
    public boolean showDeathMarker = true;
    public boolean showWorldMapMarkers = true;
    public boolean limitMobHeight = true;
    public int mobVerticalRange = 16; // Blocks above/below the player; 16 hides most underground creatures.

    /**
     * Legacy four-corner setting retained for migration/backward compatibility.
     * New code should use hudPositionX / hudPositionY.
     */
    public int position = POS_TOP_RIGHT;

    /**
     * Position of the complete minimap widget within the available screen area.
     * 0 = left/top edge, 50 = centered, 100 = right/bottom edge.
     */
    public int hudPositionX = 100;
    public int hudPositionY = 0;

    /** Position of the information panel relative to the map image. */
    public boolean infoPanelEnabled = true;

    /** Position of the information panel relative to the map image. */
    public int infoPanelPosition = INFO_RIGHT;

    /** Horizontal alignment of all information-panel text. */
    public int infoPanelTextAlignment = INFO_ALIGN_LEFT;

    public int updateSpeed = 2;
    public boolean clock24Hour = true;
    public boolean showCoordinates = true;
    public boolean showClock = true;
    public boolean showCompass = true;
    public boolean showBiome = true;
    /** Compass ring: N/E/S/W letters (native game text drawn over the frame). */
    public boolean showCompassLetters = true;
    /** Compass ring: the small dashes around the map edge (not the large cardinal dashes). */
    public boolean showCompassDashes = true;
    /** Compass ring: the large dashes at N/E/S/W. When off, the letters move out to their place. */
    public boolean showCardinalDashes = true;
    /** Master switch for DapperMap's BetterMap waypoint and cave integrations. */
    public boolean betterMapEnabled = true;
    public boolean betterMapMarkersEnabled = true;
    public boolean betterMapCaveModeEnabled = true;
    public DynamicMinimapSetting() {
    }

    public static DynamicMinimapSetting fromConfig(DapperMapConfig config) {
        DynamicMinimapSetting settings = new DynamicMinimapSetting();
        settings.enabled = config.isEnabledByDefault();
        settings.zoomBlocks = Math.max(ZOOM_MIN, config.getDefaultRadiusBlocks());
        settings.showCoordinates = config.isShowCoordinates();
        settings.normalize();
        return settings;
    }

    /**
     * True when the info panel is drawn: switched on with at least one row to show. With every
     * row off the panel is left out entirely (no empty box with just the heading), while the
     * player's panel switch itself is kept, so turning a row back on brings the panel back.
     */
    public boolean infoPanelShown() {
        return this.infoPanelEnabled && (this.showCoordinates || this.showCompass || this.showClock || this.showBiome);
    }

    /** Compass ring parts as bits (1 letters, 2 small dashes, 4 cardinal dashes): keys frame caches and paths. */
    public int compassMarkings() {
        return (this.showCompassLetters ? 1 : 0) | (this.showCompassDashes ? 2 : 0) | (this.showCardinalDashes ? 4 : 0);
    }

    /**
     * A marker size given for the default 220 px map, grown in proportion for larger maps (never
     * shrunk below it), so every marker keeps the same size relative to the map at any Map Size.
     */
    public int scaledMarkerSize(int baseSize) {
        return Math.max(baseSize, Math.round(baseSize * this.sizePixels / 220.0F));
    }

    public boolean isCircle() {
        return this.shape == 1;
    }

    /** Synchronized: live session settings are updated under this object's lock (DapperMapSessionManager). */
    public synchronized DynamicMinimapSetting copy() {
        DynamicMinimapSetting copy = new DynamicMinimapSetting();
        copy.copyFrom(this);
        return copy;
    }

    /**
     * Copies every setting from {@code source} into this object (identity kept). The one field
     * list used by {@link #copy()} and by live-session refreshes, so a new setting only needs
     * adding here (plus the settings store and SettingPermissions). Callers hold any lock needed.
     */
    public void copyFrom(DynamicMinimapSetting source) {
        this.colours = source.colours; // immutable, safe to share
        this.enabled = source.enabled;
        this.rotationMode = source.rotationMode;
        this.zoomBlocks = source.zoomBlocks;
        this.sizePixels = source.sizePixels;
        this.textureResolution = source.textureResolution;
        this.interfaceScalePercent = source.interfaceScalePercent;
        this.shape = source.shape;
        this.showOtherPlayers = source.showOtherPlayers;
        this.showEntities = source.showEntities;
        this.creatureMarkerLimit = source.creatureMarkerLimit;
        this.creatureIcons = source.creatureIcons;
        this.hostileCreaturesOnly = source.hostileCreaturesOnly;
        this.playerIcons = source.playerIcons;
        this.playerIconHelmets = source.playerIconHelmets;
        this.showDeathMarker = source.showDeathMarker;
        this.showWorldMapMarkers = source.showWorldMapMarkers;
        this.limitMobHeight = source.limitMobHeight;
        this.mobVerticalRange = source.mobVerticalRange;
        this.position = source.position;
        this.hudPositionX = source.hudPositionX;
        this.hudPositionY = source.hudPositionY;
        this.infoPanelEnabled = source.infoPanelEnabled;
        this.infoPanelPosition = source.infoPanelPosition;
        this.infoPanelTextAlignment = source.infoPanelTextAlignment;
        this.updateSpeed = source.updateSpeed;
        this.clock24Hour = source.clock24Hour;
        this.showCoordinates = source.showCoordinates;
        this.showClock = source.showClock;
        this.showCompass = source.showCompass;
        this.showBiome = source.showBiome;
        this.showCompassLetters = source.showCompassLetters;
        this.showCompassDashes = source.showCompassDashes;
        this.showCardinalDashes = source.showCardinalDashes;
        this.betterMapEnabled = source.betterMapEnabled;
        this.betterMapMarkersEnabled = source.betterMapMarkersEnabled;
        this.betterMapCaveModeEnabled = source.betterMapCaveModeEnabled;
    }

    public void normalize() {
        if (this.rotationMode != ROTATION_NORTH_UP
                && this.rotationMode != ROTATION_PLAYER_UP) {
            this.rotationMode = ROTATION_NORTH_UP;
        }

        if (this.shape != 1 && this.shape != 0) {
            this.shape = 1;
        }

        this.zoomBlocks = clamp(this.zoomBlocks, ZOOM_MIN, ZOOM_MAX);
        this.sizePixels = clamp(this.sizePixels, SIZE_MIN, SIZE_MAX);
        this.textureResolution = clamp(this.textureResolution, RESOLUTION_MIN, RESOLUTION_MAX);
        this.interfaceScalePercent = clamp(this.interfaceScalePercent, SCALE_MIN, SCALE_MAX);
        if (!isCreatureLimit(this.creatureMarkerLimit)) this.creatureMarkerLimit = CREATURE_LIMITS[0];
        this.mobVerticalRange = clamp(this.mobVerticalRange, HEIGHT_RANGE_MIN, HEIGHT_RANGE_MAX);
        if (this.position < 0 || this.position > 3) {
            this.position = POS_TOP_RIGHT;
        }

        this.hudPositionX = clamp(this.hudPositionX, POSITION_MIN, POSITION_MAX);
        this.hudPositionY = clamp(this.hudPositionY, POSITION_MIN, POSITION_MAX);
        this.infoPanelPosition = clamp(this.infoPanelPosition, INFO_BELOW, INFO_RIGHT);
        this.infoPanelTextAlignment = clamp(this.infoPanelTextAlignment, INFO_ALIGN_LEFT, INFO_ALIGN_RIGHT);

        this.updateSpeed = clamp(this.updateSpeed, SPEED_FASTEST, SPEED_SLOWEST);
    }

    /**
     * Converts the old four-corner setting into the new normalized X/Y model.
     * Used only when loading an older player settings file.
     */
    public void applyLegacyPosition(int legacyPosition) {
        this.position = clamp(legacyPosition, POS_TOP_LEFT, POS_BOTTOM_RIGHT);
        // Corners 0..3: bit 0 = right, bit 1 = bottom.
        this.hudPositionX = (this.position & 1) * POSITION_MAX;
        this.hudPositionY = (this.position >> 1) * POSITION_MAX;
    }

    /** Convert a signed logical HUD coordinate into display-space units. */
    public int displayCoordinate(int logicalValue) {
        return dev.scyphoz.dappermap.util.DapperMapUtil.scaled(logicalValue, this.interfaceScalePercent);
    }

    /** Convert a logical HUD dimension into a positive display-space size. */
    public int displaySize(int logicalValue) {
        if (logicalValue <= 0) {
            return 0;
        }

        return Math.max(1, this.displayCoordinate(logicalValue));
    }

    // Timings (ms) per update speed: very fast, fast, normal, slow. One row per kind of work.
    private static final long[][] SPEED_TIMINGS = {
            {40L, 75L, 150L, 300L},     // PAN: map pan (25, ~13, ~7, ~3 Hz)
            {50L, 100L, 250L, 500L},    // MARKERS: creature and player rescans (20, 10, 4, 2 Hz)
            {150L, 300L, 750L, 1500L},  // SIGNATURE: terrain change probe
            {300L, 600L, 1200L, 2500L}, // TERRAIN: shortest gap between terrain renders
            {25L, 40L, 60L, 100L},      // ROTATION_POLL: heading polls (40 Hz scheduler ceiling .. 10 Hz)
            {100L, 125L, 200L, 300L},   // ROTATION: player-up frame renders (10, 8, 5, ~3 Hz)
            {125L, 150L, 250L, 500L}};  // OVERLAY: HUD overlay re-renders
    private static final int PAN = 0, MARKERS = 1, SIGNATURE = 2, TERRAIN = 3, ROTATION_POLL = 4, ROTATION = 5, OVERLAY = 6;

    /** Timing of one kind at the current speed; an out-of-range speed uses normal, as before. */
    private long timing(int kind) {
        int speed = this.updateSpeed;
        return SPEED_TIMINGS[kind][speed >= SPEED_FASTEST && speed <= SPEED_SLOWEST ? speed : 2];
    }

    /** How often the map pans (ms) at the current update speed. */
    public long panIntervalMs() { return this.timing(PAN); }

    /** How often creature and player markers are rescanned (ms). */
    public long markerIntervalMs() { return this.timing(MARKERS); }

    /** How often the terrain is probed for map changes (ms). */
    public long signatureIntervalMs() { return this.timing(SIGNATURE); }

    /** Shortest gap between terrain renders (ms). */
    public long terrainIntervalMs() { return this.timing(TERRAIN); }

    /**
     * How often the heading is polled (ms). Heading-only updates are intentionally faster than
     * terrain stitching, keeping player-up rotation readable without stitching more often.
     */
    public long rotationPollIntervalMs() { return this.timing(ROTATION_POLL); }

    /** Shortest gap between player-up frame renders (ms): limits PNG uploads, not position polling. */
    public long rotationIntervalMs() { return this.timing(ROTATION); }

    /** Shortest gap between HUD overlay re-renders (ms). */
    public long overlayIntervalMs() { return this.timing(OVERLAY); }

    /** "very-fast" | "fast" | "normal" | "slow" (the names used by commands and the config). */
    public String updateSpeedName() {
        return DapperMapConfig.SPEED_NAMES[clamp(this.updateSpeed, SPEED_FASTEST, SPEED_SLOWEST)];
    }

}

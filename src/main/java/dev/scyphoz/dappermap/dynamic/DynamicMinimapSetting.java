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

    /**
     * Font Size (percent of the standard size) and the stops its settings-page slider offers:
     * 100% sits in the middle, 5% steps below it and 10% steps above. Commands take any 5% step.
     */
    public static final int FONT_SCALE_MIN = 75, FONT_SCALE_MAX = 150, FONT_SCALE_STEP = 5;
    public static final int[] FONT_SCALE_STOPS = {75, 80, 85, 90, 95, 100, 110, 120, 130, 140, 150};
    /** Marker Size (percent): 100% in the middle, 10% steps below and 20% steps above. Commands take any 10% step. */
    public static final int MARKER_SCALE_MIN = 50, MARKER_SCALE_MAX = 200, MARKER_SCALE_STEP = 10;
    public static final int[] MARKER_SCALE_STOPS = {50, 60, 70, 80, 90, 100, 120, 140, 160, 180, 200};

    /** True for one of {@link #CREATURE_LIMITS}. */
    public static boolean isCreatureLimit(int value) {
        return value == 16 || value == 32 || value == 64;
    }
    public volatile DynamicMinimapColours colours = DynamicMinimapColours.defaults();
    public volatile boolean enabled = true;
    public int rotationMode = 0;
    public int zoomBlocks = 96;
    /** Logical Hytale UI size of the minimap viewport. */
    public int sizePixels = 240; // Mod default (was 220 before 3.2.1); sparse settings files follow it unless changed.
    /** Raster resolution used to render minimap textures. Kept separate from UI size. */
    public int textureResolution = 256; // Mod default (was 384 before 3.2.0); sparse settings files follow it unless changed.
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
    /** Creatures that still hold a memory for this player to collect flash slowly in the memory colour. */
    public boolean memoryHints = true;

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
    // Font Size (percent of the standard size): Info Panel title, headings and values, and the
    // compass letters (their ring badges grow with them).
    public int fontTitle = 100;
    public int fontHeadings = 100;
    public int fontValues = 100;
    public int fontCompass = 100;
    // Marker Size (percent of the standard size) per marker group.
    public int markerCreatures = 100;
    public int markerPlayers = 100;
    /** Map markers: Hytale world-map markers (incl. death and spawn) and BetterMap waypoints alike. */
    public int markerMap = 100;
    public int markerArrow = 100;
    public DynamicMinimapSetting() {
    }

    /**
     * Every stored player setting, one entry each, in settings-file key order. Copying, saving,
     * loading, per-setting permissions and the preview cache all loop over this table, so a new
     * setting needs one line here (plus its page row and command). Booleans are read and written
     * as 0/1 so nothing is boxed. The legacy four-corner {@link #position} and the colours are
     * handled separately.
     */
    public enum Field {
        ENABLED("enabled", true, s -> s.enabled ? 1 : 0, (s, v) -> s.enabled = v != 0),
        ROTATION_MODE("rotationMode", false, s -> s.rotationMode, (s, v) -> s.rotationMode = v),
        ZOOM_BLOCKS("zoomBlocks", false, s -> s.zoomBlocks, (s, v) -> s.zoomBlocks = v),
        SIZE_PIXELS("sizePixels", false, s -> s.sizePixels, (s, v) -> s.sizePixels = v),
        TEXTURE_RESOLUTION("textureResolution", false, s -> s.textureResolution, (s, v) -> s.textureResolution = v),
        INTERFACE_SCALE("interfaceScalePercent", false, s -> s.interfaceScalePercent, (s, v) -> s.interfaceScalePercent = v),
        SHAPE("shape", false, s -> s.shape, (s, v) -> s.shape = v),
        SHOW_OTHER_PLAYERS("showOtherPlayers", true, s -> s.showOtherPlayers ? 1 : 0, (s, v) -> s.showOtherPlayers = v != 0),
        SHOW_ENTITIES("showEntities", true, s -> s.showEntities ? 1 : 0, (s, v) -> s.showEntities = v != 0),
        CREATURE_MARKER_LIMIT("creatureMarkerLimit", false, s -> s.creatureMarkerLimit, (s, v) -> s.creatureMarkerLimit = v),
        CREATURE_ICONS("creatureIcons", true, s -> s.creatureIcons ? 1 : 0, (s, v) -> s.creatureIcons = v != 0),
        HOSTILE_CREATURES_ONLY("hostileCreaturesOnly", true, s -> s.hostileCreaturesOnly ? 1 : 0, (s, v) -> s.hostileCreaturesOnly = v != 0),
        PLAYER_ICONS("playerIcons", true, s -> s.playerIcons ? 1 : 0, (s, v) -> s.playerIcons = v != 0),
        PLAYER_ICON_HELMETS("playerIconHelmets", true, s -> s.playerIconHelmets ? 1 : 0, (s, v) -> s.playerIconHelmets = v != 0),
        SHOW_DEATH_MARKER("showDeathMarker", true, s -> s.showDeathMarker ? 1 : 0, (s, v) -> s.showDeathMarker = v != 0),
        SHOW_WORLD_MAP_MARKERS("showWorldMapMarkers", true, s -> s.showWorldMapMarkers ? 1 : 0, (s, v) -> s.showWorldMapMarkers = v != 0),
        LIMIT_MOB_HEIGHT("limitMobHeight", true, s -> s.limitMobHeight ? 1 : 0, (s, v) -> s.limitMobHeight = v != 0),
        MOB_VERTICAL_RANGE("mobVerticalRange", false, s -> s.mobVerticalRange, (s, v) -> s.mobVerticalRange = v),
        MEMORY_HINTS("memoryHints", true, s -> s.memoryHints ? 1 : 0, (s, v) -> s.memoryHints = v != 0),
        HUD_POSITION_X("hudPositionX", false, s -> s.hudPositionX, (s, v) -> s.hudPositionX = v),
        HUD_POSITION_Y("hudPositionY", false, s -> s.hudPositionY, (s, v) -> s.hudPositionY = v),
        INFO_PANEL_ENABLED("infoPanelEnabled", true, s -> s.infoPanelEnabled ? 1 : 0, (s, v) -> s.infoPanelEnabled = v != 0),
        INFO_PANEL_POSITION("infoPanelPosition", false, s -> s.infoPanelPosition, (s, v) -> s.infoPanelPosition = v),
        INFO_PANEL_TEXT_ALIGNMENT("infoPanelTextAlignment", false, s -> s.infoPanelTextAlignment, (s, v) -> s.infoPanelTextAlignment = v),
        UPDATE_SPEED("updateSpeed", false, s -> s.updateSpeed, (s, v) -> s.updateSpeed = v),
        CLOCK_24_HOUR("clock24Hour", true, s -> s.clock24Hour ? 1 : 0, (s, v) -> s.clock24Hour = v != 0),
        SHOW_COORDINATES("showCoordinates", true, s -> s.showCoordinates ? 1 : 0, (s, v) -> s.showCoordinates = v != 0),
        SHOW_CLOCK("showClock", true, s -> s.showClock ? 1 : 0, (s, v) -> s.showClock = v != 0),
        SHOW_COMPASS("showCompass", true, s -> s.showCompass ? 1 : 0, (s, v) -> s.showCompass = v != 0),
        SHOW_BIOME("showBiome", true, s -> s.showBiome ? 1 : 0, (s, v) -> s.showBiome = v != 0),
        SHOW_COMPASS_LETTERS("showCompassLetters", true, s -> s.showCompassLetters ? 1 : 0, (s, v) -> s.showCompassLetters = v != 0),
        SHOW_COMPASS_DASHES("showCompassDashes", true, s -> s.showCompassDashes ? 1 : 0, (s, v) -> s.showCompassDashes = v != 0),
        SHOW_CARDINAL_DASHES("showCardinalDashes", true, s -> s.showCardinalDashes ? 1 : 0, (s, v) -> s.showCardinalDashes = v != 0),
        BETTERMAP_ENABLED("betterMapEnabled", true, s -> s.betterMapEnabled ? 1 : 0, (s, v) -> s.betterMapEnabled = v != 0),
        BETTERMAP_MARKERS("betterMapMarkersEnabled", true, s -> s.betterMapMarkersEnabled ? 1 : 0, (s, v) -> s.betterMapMarkersEnabled = v != 0),
        BETTERMAP_CAVES("betterMapCaveModeEnabled", true, s -> s.betterMapCaveModeEnabled ? 1 : 0, (s, v) -> s.betterMapCaveModeEnabled = v != 0),
        FONT_TITLE("fontTitle", false, s -> s.fontTitle, (s, v) -> s.fontTitle = v),
        FONT_HEADINGS("fontHeadings", false, s -> s.fontHeadings, (s, v) -> s.fontHeadings = v),
        FONT_VALUES("fontValues", false, s -> s.fontValues, (s, v) -> s.fontValues = v),
        FONT_COMPASS("fontCompass", false, s -> s.fontCompass, (s, v) -> s.fontCompass = v),
        MARKER_CREATURES("markerCreatures", false, s -> s.markerCreatures, (s, v) -> s.markerCreatures = v),
        MARKER_PLAYERS("markerPlayers", false, s -> s.markerPlayers, (s, v) -> s.markerPlayers = v),
        MARKER_MAP("markerMap", false, s -> s.markerMap, (s, v) -> s.markerMap = v),
        MARKER_ARROW("markerArrow", false, s -> s.markerArrow, (s, v) -> s.markerArrow = v);

        /** Key in the settings files (unchanged since the setting was added). */
        public final String key;
        /** True for an on/off setting (stored as true/false), false for a number. */
        public final boolean bool;
        private final java.util.function.ToIntFunction<DynamicMinimapSetting> getter;
        private final java.util.function.ObjIntConsumer<DynamicMinimapSetting> setter;
        private static final Field[] ALL = values();

        Field(String key, boolean bool, java.util.function.ToIntFunction<DynamicMinimapSetting> getter,
              java.util.function.ObjIntConsumer<DynamicMinimapSetting> setter) {
            this.key = key;
            this.bool = bool;
            this.getter = getter;
            this.setter = setter;
        }

        /** The value (booleans as 0/1). */
        public int get(DynamicMinimapSetting settings) { return this.getter.applyAsInt(settings); }

        /** Sets the value (booleans: non-zero is true). Not normalised. */
        public void set(DynamicMinimapSetting settings, int value) { this.setter.accept(settings, value); }

        /** Copies this one setting from {@code source} into {@code target}. */
        public void copy(DynamicMinimapSetting target, DynamicMinimapSetting source) {
            this.setter.accept(target, this.getter.applyAsInt(source));
        }

        /** Every field, in file order (a shared array: do not modify). */
        public static Field[] all() { return ALL; }
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

    /**
     * Compass ring parts as bits (1 letters, 2 small dashes, 4 cardinal dashes), plus the compass
     * letter size from bit 3 when the letters show at other than 100% (their badges are part of
     * the ring artwork). Keys frame caches and paths; the default value is unchanged from 3.2.0.
     */
    public int compassMarkings() {
        int bits = (this.showCompassLetters ? 1 : 0) | (this.showCompassDashes ? 2 : 0) | (this.showCardinalDashes ? 4 : 0);
        return this.showCompassLetters && this.fontCompass != 100 ? bits | this.fontCompass << 3 : bits;
    }

    /** Compass letter (and badge) size as a fraction of the standard size. */
    public double compassLetterScale() {
        return this.fontCompass / 100.0D;
    }

    /**
     * The three Info Panel text sizes in one number (each 75..150 fits in 8 bits): keys the panel
     * image and its asset path, since the panel grows or shrinks with its text.
     */
    public int panelTextKey() {
        return this.fontTitle << 16 | this.fontHeadings << 8 | this.fontValues;
    }

    /** A marker size scaled by a Marker Size percentage (never below 4 HUD units). */
    public static int scaledBy(int size, int percent) {
        return percent == 100 ? size : Math.max(4, Math.round(size * percent / 100.0F));
    }

    /** Size of the player's own locator arrow for a map of {@code mapSize} units, with Marker Size: Your Arrow. */
    public int selfArrowSize(int mapSize) {
        return scaledBy(Math.max(18, mapSize / 10), this.markerArrow);
    }

    /**
     * A marker size given for a 220 px map (the size markers are drawn for; the default map is 240), grown in proportion for larger maps (never
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
     * Copies every setting from {@code source} into this object (identity kept). Used by
     * {@link #copy()} and by live-session refreshes. Callers hold any lock needed.
     */
    public void copyFrom(DynamicMinimapSetting source) {
        this.colours = source.colours; // immutable, safe to share
        this.position = source.position; // Legacy corner (not in Field: never saved).
        for (Field field : Field.ALL) field.copy(this, source);
    }

    /** True when every setting except the X/Y position matches (the preview HUD looks the same). */
    public boolean sameExceptPosition(DynamicMinimapSetting other) {
        if (this.position != other.position || !this.colours.equals(other.colours)) return false;
        for (Field field : Field.ALL)
            if (field != Field.HUD_POSITION_X && field != Field.HUD_POSITION_Y
                    && field.get(this) != field.get(other)) return false;
        return true;
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

        // Font and marker sizes: within range, on their step (hand-edited files included).
        this.fontTitle = snapScale(this.fontTitle, FONT_SCALE_MIN, FONT_SCALE_MAX, FONT_SCALE_STEP);
        this.fontHeadings = snapScale(this.fontHeadings, FONT_SCALE_MIN, FONT_SCALE_MAX, FONT_SCALE_STEP);
        this.fontValues = snapScale(this.fontValues, FONT_SCALE_MIN, FONT_SCALE_MAX, FONT_SCALE_STEP);
        this.fontCompass = snapScale(this.fontCompass, FONT_SCALE_MIN, FONT_SCALE_MAX, FONT_SCALE_STEP);
        this.markerCreatures = snapScale(this.markerCreatures, MARKER_SCALE_MIN, MARKER_SCALE_MAX, MARKER_SCALE_STEP);
        this.markerPlayers = snapScale(this.markerPlayers, MARKER_SCALE_MIN, MARKER_SCALE_MAX, MARKER_SCALE_STEP);
        this.markerMap = snapScale(this.markerMap, MARKER_SCALE_MIN, MARKER_SCALE_MAX, MARKER_SCALE_STEP);
        this.markerArrow = snapScale(this.markerArrow, MARKER_SCALE_MIN, MARKER_SCALE_MAX, MARKER_SCALE_STEP);
    }

    /** {@code value} clamped to [min, max] and rounded to the nearest {@code step}. */
    public static int snapScale(int value, int min, int max, int step) {
        int clamped = clamp(value, min, max);
        return clamp(Math.round(clamped / (float) step) * step, min, max);
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
            {125L, 150L, 250L, 500L},   // OVERLAY: HUD overlay re-renders
            {1000L, 1500L, 2500L, 5000L}}; // REFRESH: shortest gap between redraws for terrain changes only
    private static final int PAN = 0, MARKERS = 1, SIGNATURE = 2, TERRAIN = 3, ROTATION_POLL = 4, ROTATION = 5, OVERLAY = 6,
            REFRESH = 7;

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
     * Shortest gap (ms) before a redraw that is due only to changed terrain (no pan, recentre
     * or retry). Since Hytale 0.8.0-pre.5 a chunk's map image is rebuilt every second while its
     * blocks or fluids change (building, farms, flowing water); each redraw resends the whole
     * terrain image, so these are spaced further apart than ordinary terrain renders.
     */
    public long terrainRefreshIntervalMs() { return this.timing(REFRESH); }

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

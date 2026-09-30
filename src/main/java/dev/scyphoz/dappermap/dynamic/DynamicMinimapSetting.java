package dev.scyphoz.dappermap.dynamic;

import dev.scyphoz.dappermap.config.MinimapConfig;

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
    public volatile MinimapColours colours = MinimapColours.defaults();
    public boolean enabled = true;
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
    public boolean showDeathMarker = true;
    public boolean showWorldMapMarkers = true;
    public boolean limitMobHeight = true;
    public int mobVerticalRange = 32;

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
    /** Master switch for DapperMap's BetterMap waypoint and cave integrations. */
    public boolean betterMapEnabled = true;
    public boolean betterMapMarkersEnabled = true;
    public boolean betterMapCaveModeEnabled = true;
    public DynamicMinimapSetting() {
    }

    public static DynamicMinimapSetting fromConfig(MinimapConfig config) {
        DynamicMinimapSetting settings = new DynamicMinimapSetting();
        settings.enabled = config.isEnabledByDefault();
        settings.zoomBlocks = Math.max(16, config.getDefaultRadiusBlocks());
        settings.showCoordinates = config.isShowCoordinates();
        settings.normalize();
        return settings;
    }

    public boolean isCircle() {
        return this.shape == 1;
    }

    public DynamicMinimapSetting copy() {
        DynamicMinimapSetting copy = new DynamicMinimapSetting();
        copy.colours = this.colours; // immutable, safe to share
        copy.enabled = this.enabled;
        copy.rotationMode = this.rotationMode;
        copy.zoomBlocks = this.zoomBlocks;
        copy.sizePixels = this.sizePixels;
        copy.textureResolution = this.textureResolution;
        copy.interfaceScalePercent = this.interfaceScalePercent;
        copy.shape = this.shape;
        copy.showOtherPlayers = this.showOtherPlayers;
        copy.showEntities = this.showEntities;
        copy.showDeathMarker = this.showDeathMarker;
        copy.showWorldMapMarkers = this.showWorldMapMarkers;
        copy.limitMobHeight = this.limitMobHeight;
        copy.mobVerticalRange = this.mobVerticalRange;
        copy.position = this.position;
        copy.hudPositionX = this.hudPositionX;
        copy.hudPositionY = this.hudPositionY;
        copy.infoPanelEnabled = this.infoPanelEnabled;
        copy.infoPanelPosition = this.infoPanelPosition;
        copy.infoPanelTextAlignment = this.infoPanelTextAlignment;
        copy.updateSpeed = this.updateSpeed;
        copy.clock24Hour = this.clock24Hour;
        copy.showCoordinates = this.showCoordinates;
        copy.showClock = this.showClock;
        copy.showCompass = this.showCompass;
        copy.showBiome = this.showBiome;
        copy.betterMapEnabled = this.betterMapEnabled;
        copy.betterMapMarkersEnabled = this.betterMapMarkersEnabled;
        copy.betterMapCaveModeEnabled = this.betterMapCaveModeEnabled;
        return copy;
    }

    public void normalize() {
        if (this.rotationMode != ROTATION_NORTH_UP
                && this.rotationMode != ROTATION_PLAYER_UP) {
            this.rotationMode = ROTATION_NORTH_UP;
        }

        if (this.shape != 1 && this.shape != 0) {
            this.shape = 1;
        }

        this.zoomBlocks = clamp(this.zoomBlocks, 16, 256);
        this.sizePixels = clamp(this.sizePixels, 120, 360);
        this.textureResolution = clamp(this.textureResolution, 256, 1024);
        this.interfaceScalePercent = clamp(this.interfaceScalePercent, 75, 160);
        this.mobVerticalRange = clamp(this.mobVerticalRange, 4, 256);
        if (this.position < 0 || this.position > 3) {
            this.position = POS_TOP_RIGHT;
        }

        this.hudPositionX = clamp(this.hudPositionX, 0, 100);
        this.hudPositionY = clamp(this.hudPositionY, 0, 100);
        this.infoPanelPosition = clamp(this.infoPanelPosition, INFO_BELOW, INFO_RIGHT);
        this.infoPanelTextAlignment = clamp(this.infoPanelTextAlignment, INFO_ALIGN_LEFT, INFO_ALIGN_RIGHT);

        this.updateSpeed = clamp(this.updateSpeed, 0, 3);
    }

    /**
     * Converts the old four-corner setting into the new normalized X/Y model.
     * Used only when loading an older player settings file.
     */
    public void applyLegacyPosition(int legacyPosition) {
        this.position = clamp(legacyPosition, POS_TOP_LEFT, POS_BOTTOM_RIGHT);

        switch (this.position) {
            case POS_TOP_LEFT -> {
                this.hudPositionX = 0;
                this.hudPositionY = 0;
            }
            case POS_BOTTOM_LEFT -> {
                this.hudPositionX = 0;
                this.hudPositionY = 100;
            }
            case POS_BOTTOM_RIGHT -> {
                this.hudPositionX = 100;
                this.hudPositionY = 100;
            }
            case POS_TOP_RIGHT -> {
                this.hudPositionX = 100;
                this.hudPositionY = 0;
            }
            default -> {
                this.hudPositionX = 100;
                this.hudPositionY = 0;
            }
        }
    }

    /** Presentation scale as a multiplier, e.g. 125 -> 1.25. */
    public double interfaceScale() {
        return (double)this.interfaceScalePercent / 100.0D;
    }

    /** Convert a signed logical HUD coordinate into display-space units. */
    public int displayCoordinate(int logicalValue) {
        return (int)Math.round((double)logicalValue * this.interfaceScale());
    }

    /** Convert a logical HUD dimension into a positive display-space size. */
    public int displaySize(int logicalValue) {
        if (logicalValue <= 0) {
            return 0;
        }

        return Math.max(1, this.displayCoordinate(logicalValue));
    }

    public long panIntervalMs() {
        long var10000;
        switch (this.updateSpeed) {
            case 0:
                var10000 = 40L;   // 25 Hz
                break;
            case 1:
                var10000 = 75L;   // ~13 Hz
                break;
            case 2:
            default:
                var10000 = 150L;  // ~7 Hz
                break;
            case 3:
                var10000 = 300L;  // ~3 Hz
        }

        return var10000;
    }

    public long markerIntervalMs() {
        long var10000;
        switch (this.updateSpeed) {
            case 0:
                var10000 = 50L;   // 20 Hz
                break;
            case 1:
                var10000 = 100L;  // 10 Hz
                break;
            case 2:
            default:
                var10000 = 250L;  // 4 Hz
                break;
            case 3:
                var10000 = 500L;  // 2 Hz
        }

        return var10000;
    }

    public long signatureIntervalMs() {
        long var10000;
        switch (this.updateSpeed) {
            case 0:
                var10000 = 150L;
                break;
            case 1:
                var10000 = 300L;
                break;
            case 2:
            default:
                var10000 = 750L;
                break;
            case 3:
                var10000 = 1500L;
        }

        return var10000;
    }

    public long terrainIntervalMs() {
        long var10000;
        switch (this.updateSpeed) {
            case 0:
                var10000 = 300L;
                break;
            case 1:
                var10000 = 600L;
                break;
            case 2:
            default:
                var10000 = 1200L;
                break;
            case 3:
                var10000 = 2500L;
        }

        return var10000;
    }

    public String updateSpeedName() {
        String var10000;
        switch (this.updateSpeed) {
            case 0:
                var10000 = "very-fast";
                break;
            case 1:
                var10000 = "fast";
                break;
            case 2:
            default:
                var10000 = "normal";
                break;
            case 3:
                var10000 = "slow";
        }

        return var10000;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}

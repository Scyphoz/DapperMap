package dev.scyphoz.dappermap.dynamic;

import dev.scyphoz.dappermap.config.MinimapConfig;

public final class DynamicMinimapSetting {
    public static final int SHAPE_SQUARE = 0;
    public static final int SHAPE_CIRCLE = 1;
    public static final int POS_TOP_LEFT = 0;
    public static final int POS_TOP_RIGHT = 1;
    public static final int POS_BOTTOM_LEFT = 2;
    public static final int POS_BOTTOM_RIGHT = 3;
    public boolean enabled = true;
    public int rotationMode = 0;
    public int zoomBlocks = 96;
    /** Logical Hytale UI size of the minimap viewport. */
    public int sizePixels = 220;
    /** Raster resolution used to render minimap textures. Kept separate from UI size. */
    public int textureResolution = 384;
    public int shape = 1;
    public boolean showBorder = true;
    public boolean showOtherPlayers = true;
    public boolean showEntities = true;
    public boolean showDeathMarker = true;
    public boolean limitMobHeight = true;
    public int mobVerticalRange = 32;
    public int position = 1;
    public int updateSpeed = 2;
    public boolean clock24Hour = true;
    public boolean showClock = true;
    public boolean showCompass = true;
    public boolean showBiome = true;
    public static final int UPDATE_VERY_FAST = 0;
    public static final int UPDATE_FAST = 1;
    public static final int UPDATE_NORMAL = 2;
    public static final int UPDATE_SLOW = 3;

    public DynamicMinimapSetting() {
    }

    public static DynamicMinimapSetting fromConfig(MinimapConfig config) {
        DynamicMinimapSetting settings = new DynamicMinimapSetting();
        settings.enabled = config.isEnabledByDefault();
        settings.zoomBlocks = Math.max(16, config.getDefaultRadiusBlocks());
        settings.normalize();
        return settings;
    }

    public boolean isCircle() {
        return this.shape == 1;
    }

    public DynamicMinimapSetting copy() {
        DynamicMinimapSetting copy = new DynamicMinimapSetting();
        copy.enabled = this.enabled;
        copy.rotationMode = this.rotationMode;
        copy.zoomBlocks = this.zoomBlocks;
        copy.sizePixels = this.sizePixels;
        copy.textureResolution = this.textureResolution;
        copy.shape = this.shape;
        copy.showBorder = this.showBorder;
        copy.showOtherPlayers = this.showOtherPlayers;
        copy.showEntities = this.showEntities;
        copy.showDeathMarker = this.showDeathMarker;
        copy.limitMobHeight = this.limitMobHeight;
        copy.mobVerticalRange = this.mobVerticalRange;
        copy.position = this.position;
        copy.updateSpeed = this.updateSpeed;
        copy.clock24Hour = this.clock24Hour;
        copy.showClock = this.showClock;
        copy.showCompass = this.showCompass;
        copy.showBiome = this.showBiome;
        return copy;
    }

    public void normalize() {
        if (this.rotationMode != 0 && this.rotationMode != 1) {
            this.rotationMode = 0;
        }

        if (this.shape != 1 && this.shape != 0) {
            this.shape = 1;
        }

        this.zoomBlocks = clamp(this.zoomBlocks, 16, 256);
        this.sizePixels = clamp(this.sizePixels, 120, 360);
        this.textureResolution = clamp(this.textureResolution, 256, 512);
        this.mobVerticalRange = clamp(this.mobVerticalRange, 4, 256);
        if (this.position < 0 || this.position > 3) {
            this.position = 1;
        }

        this.updateSpeed = clamp(this.updateSpeed, 0, 3);
    }

    public long panIntervalMs() {
        long var10000;
        switch (this.updateSpeed) {
            case 0:
                var10000 = 50L;
                break;
            case 1:
                var10000 = 75L;
                break;
            case 2:
            default:
                var10000 = 125L;
                break;
            case 3:
                var10000 = 200L;
        }

        return var10000;
    }

    public long markerIntervalMs() {
        long var10000;
        switch (this.updateSpeed) {
            case 0:
                var10000 = 100L;
                break;
            case 1:
                var10000 = 150L;
                break;
            case 2:
            default:
                var10000 = 250L;
                break;
            case 3:
                var10000 = 500L;
        }

        return var10000;
    }

    public long signatureIntervalMs() {
        long var10000;
        switch (this.updateSpeed) {
            case 0:
                var10000 = 200L;
                break;
            case 1:
                var10000 = 350L;
                break;
            case 2:
            default:
                var10000 = 600L;
                break;
            case 3:
                var10000 = 1000L;
        }

        return var10000;
    }

    public long terrainIntervalMs() {
        long var10000;
        switch (this.updateSpeed) {
            case 0:
                var10000 = 450L;
                break;
            case 1:
                var10000 = 650L;
                break;
            case 2:
            default:
                var10000 = 1000L;
                break;
            case 3:
                var10000 = 1750L;
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

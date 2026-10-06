package dev.scyphoz.dappermap.permissions;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shared, permission-aware copying of player settings. Used by both the settings page and
 * the {@code /dappermap} commands so Apply, Reset and command changes all enforce the same
 * per-setting permission nodes ({@code scyphoz.dappermap.command.<name>}).
 */
public final class SettingPermissions {

    /** Every player-setting node that guards a field copied by {@link #merge}. */
    public static final List<String> PLAYER_SETTING_PERMISSIONS = List.of(
            "toggle", "zoom", "size", "scale", "shape", "rotation", "speed", "resolution",
            "info", "panel", "markers", "height", "height-range", "coordinates", "clock", "compass",
            "biome", "integration", "colours", "position"
    );

    private SettingPermissions() {
    }

    /** Permission check for a real player against the player command tree. */
    public static Predicate<String> forPlayer(PlayerRef playerRef) {
        return permission -> permission == null
                || playerRef.hasPermission(DapperMapPermissions.player(permission));
    }

    /** True when every setting may be edited, i.e. a full reset may drop the player override. */
    public static boolean hasAll(Predicate<String> allowed) {
        for (String permission : PLAYER_SETTING_PERMISSIONS) {
            if (!allowed.test(permission)) return false;
        }
        return true;
    }

    /**
     * Copies into {@code target} only the values from {@code source} that {@code allowed}
     * permits, then normalises. Denied settings keep the target's current value.
     */
    public static DynamicMinimapSetting merge(Predicate<String> allowed,
                                              DynamicMinimapSetting target,
                                              DynamicMinimapSetting source) {
        if (allowed.test("toggle")) target.enabled = source.enabled;
        if (allowed.test("zoom")) target.zoomBlocks = source.zoomBlocks;
        if (allowed.test("size")) target.sizePixels = source.sizePixels;
        if (allowed.test("scale")) target.interfaceScalePercent = source.interfaceScalePercent;
        if (allowed.test("shape")) target.shape = source.shape;
        if (allowed.test("rotation")) target.rotationMode = source.rotationMode;
        if (allowed.test("speed")) target.updateSpeed = source.updateSpeed;
        if (allowed.test("resolution")) target.textureResolution = source.textureResolution;
        if (allowed.test("info")) target.infoPanelEnabled = source.infoPanelEnabled;
        if (allowed.test("panel")) {
            target.infoPanelPosition = source.infoPanelPosition;
            target.infoPanelTextAlignment = source.infoPanelTextAlignment;
        }
        if (allowed.test("markers")) {
            target.showOtherPlayers = source.showOtherPlayers;
            target.showEntities = source.showEntities;
            target.creatureMarkerLimit = source.creatureMarkerLimit;
            target.creatureIcons = source.creatureIcons; // Creature marker style (dots / icons).
            target.hostileCreaturesOnly = source.hostileCreaturesOnly; // Creature filter (all / hostile).
            target.showDeathMarker = source.showDeathMarker;
        }
        if (allowed.test("height")) target.limitMobHeight = source.limitMobHeight;
        if (allowed.test("height-range")) target.mobVerticalRange = source.mobVerticalRange;
        if (allowed.test("position")) {
            target.hudPositionX = source.hudPositionX;
            target.hudPositionY = source.hudPositionY;
        }
        if (allowed.test("clock")) {
            target.showClock = source.showClock;
            target.clock24Hour = source.clock24Hour;
        }
        if (allowed.test("coordinates")) target.showCoordinates = source.showCoordinates;
        if (allowed.test("compass")) target.showCompass = source.showCompass;
        if (allowed.test("biome")) target.showBiome = source.showBiome;
        if (allowed.test("integration")) {
            target.showWorldMapMarkers = source.showWorldMapMarkers;
            target.betterMapEnabled = source.betterMapEnabled;
            target.betterMapMarkersEnabled = source.betterMapMarkersEnabled;
            target.betterMapCaveModeEnabled = source.betterMapCaveModeEnabled;
        }
        if (allowed.test("colours")) target.colours = source.colours;
        target.normalize();
        return target;
    }
}

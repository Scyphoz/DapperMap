package dev.scyphoz.dappermap.dynamic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DynamicMinimapSettingsStore {
    private final Path dataDir;
    private final ConcurrentHashMap<UUID, DynamicMinimapSetting> cache = new ConcurrentHashMap();

    public DynamicMinimapSettingsStore(Path pluginDataDir) {
        this.dataDir = pluginDataDir.resolve("players");
    }

    public DynamicMinimapSetting get(UUID uuid, DynamicMinimapSetting defaults) {
        return (DynamicMinimapSetting)this.cache.computeIfAbsent(uuid, (ignored) -> this.load(uuid, defaults));
    }

    /** True when this player has a settings file on disk, i.e. they are not a new player. */
    public boolean hasStoredSettings(UUID uuid) {
        return uuid != null && Files.exists(this.dataDir.resolve(String.valueOf(uuid) + ".json"), new LinkOption[0]);
    }

    /**
     * Drops the cached copy for a player without touching their file. Used on disconnect so a
     * rejoin re-derives from the current admin defaults when the player has no file of their own.
     */
    public void evict(UUID uuid) {
        if (uuid != null) {
            this.cache.remove(uuid);
        }
    }

    public void put(UUID uuid, DynamicMinimapSetting settings) {
        this.cache.put(uuid, settings);
        this.save(uuid);
    }

    public void save(UUID uuid) {
        DynamicMinimapSetting settings = this.cache.get(uuid);
        if (uuid != null && settings != null) {
            try {
                Files.createDirectories(this.dataDir);
                Path file = this.dataDir.resolve(String.valueOf(uuid) + ".json");

                Files.writeString(
                        file,
                        ("{\n"
                                + "  \"enabled\": %s,\n"
                                + "  \"rotationMode\": %d,\n"
                                + "  \"zoomBlocks\": %d,\n"
                                + "  \"sizePixels\": %d,\n"
                                + "  \"textureResolution\": %d,\n"
                                + "  \"interfaceScalePercent\": %d,\n"
                                + "  \"shape\": %d,\n"
                                + "  \"showOtherPlayers\": %s,\n"
                                + "  \"showEntities\": %s,\n"
                                + "  \"showDeathMarker\": %s,\n"
                                + "  \"showWorldMapMarkers\": %s,\n"
                                + "  \"limitMobHeight\": %s,\n"
                                + "  \"mobVerticalRange\": %d,\n"
                                + "  \"position\": %d,\n"
                                + "  \"hudPositionX\": %d,\n"
                                + "  \"hudPositionY\": %d,\n"
                                + "  \"infoPanelPosition\": %d,\n"
                                + "  \"updateSpeed\": %d,\n"
                                + "  \"clock24Hour\": %s,\n"
                                + "  \"showCoordinates\": %s,\n"
                                + "  \"showClock\": %s,\n"
                                + "  \"showCompass\": %s,\n"
                                + "  \"showBiome\": %s,\n"
                                + "  \"betterMapEnabled\": %s%s\n"
                                + "}\n").formatted(
                                settings.enabled,
                                settings.rotationMode,
                                settings.zoomBlocks,
                                settings.sizePixels,
                                settings.textureResolution,
                                settings.interfaceScalePercent,
                                settings.shape,
                                settings.showOtherPlayers,
                                settings.showEntities,
                                settings.showDeathMarker,
                                settings.showWorldMapMarkers,
                                settings.limitMobHeight,
                                settings.mobVerticalRange,
                                settings.position,
                                settings.hudPositionX,
                                settings.hudPositionY,
                                settings.infoPanelPosition,
                                settings.updateSpeed,
                                settings.clock24Hour,
                                settings.showCoordinates,
                                settings.showClock,
                                settings.showCompass,
                                settings.showBiome,
                                settings.betterMapEnabled,
                                colourFields(settings.colours)
                        )
                );
            } catch (IOException ignored) {
            }
        }
    }

    private DynamicMinimapSetting load(UUID uuid, DynamicMinimapSetting defaults) {
        DynamicMinimapSetting settings = defaults.copy();
        if (uuid == null) {
            settings.normalize();
            return settings;
        } else {
            Path file = this.dataDir.resolve(String.valueOf(uuid) + ".json");
            if (Files.exists(file, new LinkOption[0])) {
                try {
                    String json = Files.readString(file);
                    for (MinimapColours.Key key : MinimapColours.Key.values()) {
                        Integer rgb = extractColour(json, "colour_" + key.id);
                        if (rgb != null) settings.colours = settings.colours.with(key, rgb);
                    }
                    Boolean enabled = extractBool(json, "enabled");
                    Integer rotationMode = extractInt(json, "rotationMode");
                    Integer zoomBlocks = extractInt(json, "zoomBlocks");
                    Integer sizePixels = extractInt(json, "sizePixels");
                    Integer textureResolution = extractInt(json, "textureResolution");
                    Integer interfaceScalePercent = extractInt(json, "interfaceScalePercent");
                    Integer shape = extractInt(json, "shape");
                    Boolean showOtherPlayers = extractBool(json, "showOtherPlayers");
                    Boolean showEntities = extractBool(json, "showEntities");
                    Boolean showDeathMarker = extractBool(json, "showDeathMarker");
                    Boolean showWorldMapMarkers = extractBool(json, "showWorldMapMarkers");
                    Boolean limitMobHeight = extractBool(json, "limitMobHeight");
                    Integer mobVerticalRange = extractInt(json, "mobVerticalRange");
                    Integer position = extractInt(json, "position");
                    Integer hudPositionX = extractInt(json, "hudPositionX");
                    Integer hudPositionY = extractInt(json, "hudPositionY");
                    Integer infoPanelPosition = extractInt(json, "infoPanelPosition");
                    Integer updateSpeed = extractInt(json, "updateSpeed");
                    Boolean clock24Hour = extractBool(json, "clock24Hour");
                    Boolean showCoordinates = extractBool(json, "showCoordinates");
                    Boolean showClock = extractBool(json, "showClock");
                    Boolean showCompass = extractBool(json, "showCompass");
                    Boolean showBiome = extractBool(json, "showBiome");
                    Boolean betterMapEnabled = extractBool(json, "betterMapEnabled");
                    if (enabled != null) {
                        settings.enabled = enabled;
                    }

                    if (rotationMode != null) {
                        settings.rotationMode = rotationMode;
                    }

                    if (zoomBlocks != null) {
                        settings.zoomBlocks = zoomBlocks;
                    }

                    if (sizePixels != null) {
                        settings.sizePixels = sizePixels;
                    }

                    if (textureResolution != null) {
                        settings.textureResolution = textureResolution;
                    }

                    if (interfaceScalePercent != null) {
                        settings.interfaceScalePercent = interfaceScalePercent;
                    }

                    if (shape != null) {
                        settings.shape = shape;
                    }

                    if (showOtherPlayers != null) {
                        settings.showOtherPlayers = showOtherPlayers;
                    }

                    if (showEntities != null) {
                        settings.showEntities = showEntities;
                    }

                    if (showDeathMarker != null) {
                        settings.showDeathMarker = showDeathMarker;
                    }

                    if (showWorldMapMarkers != null) {
                        settings.showWorldMapMarkers = showWorldMapMarkers;
                    }

                    if (limitMobHeight != null) {
                        settings.limitMobHeight = limitMobHeight;
                    }

                    if (mobVerticalRange != null) {
                        settings.mobVerticalRange = mobVerticalRange;
                    }

                    if (position != null) {
                        settings.position = position;
                    }

                    // Old settings files only have the four-corner "position".
                    // Migrate that value unless the new X/Y fields are present.
                    if (hudPositionX == null || hudPositionY == null) {
                        settings.applyLegacyPosition(
                                position != null
                                        ? position
                                        : settings.position
                        );
                    }

                    if (hudPositionX != null) {
                        settings.hudPositionX = hudPositionX;
                    }

                    if (hudPositionY != null) {
                        settings.hudPositionY = hudPositionY;
                    }

                    if (infoPanelPosition != null) {
                        settings.infoPanelPosition = infoPanelPosition;
                    }

                    if (updateSpeed != null) {
                        settings.updateSpeed = updateSpeed;
                    }

                    if (clock24Hour != null) {
                        settings.clock24Hour = clock24Hour;
                    }

                    if (showCoordinates != null) {
                        settings.showCoordinates = showCoordinates;
                    }

                    if (showClock != null) {
                        settings.showClock = showClock;
                    }

                    if (showCompass != null) {
                        settings.showCompass = showCompass;
                    }

                    if (showBiome != null) {
                        settings.showBiome = showBiome;
                    }

                    if (betterMapEnabled != null) {
                        settings.betterMapEnabled = betterMapEnabled;
                    }
                } catch (IOException var23) {
                }
            }

            settings.normalize();
            return settings;
        }
    }

    private static String colourFields(MinimapColours colours) {
        StringBuilder out = new StringBuilder();
        for (MinimapColours.Key key : MinimapColours.Key.values())
            out.append(",\n  \"colour_").append(key.id).append("\": ").append(colours.rgb(key));
        return out.toString();
    }

    private static Integer extractColour(String json, String key) {
        try {
            Integer value = extractInt(json, key);
            return value != null && value >= 0 && value <= 0xffffff ? value : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static Boolean extractBool(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(json);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : null;
    }

    private static Integer extractInt(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }
}

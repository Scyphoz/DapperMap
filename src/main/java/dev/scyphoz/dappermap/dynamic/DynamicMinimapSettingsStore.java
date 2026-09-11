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

    public void put(UUID uuid, DynamicMinimapSetting settings) {
        this.cache.put(uuid, settings);
        this.save(uuid);
    }

    public void save(UUID uuid) {
        DynamicMinimapSetting settings = (DynamicMinimapSetting)this.cache.get(uuid);
        if (uuid != null && settings != null) {
            try {
                Files.createDirectories(this.dataDir);
                Path file = this.dataDir.resolve(String.valueOf(uuid) + ".json");
                Files.writeString(file, "{\n  \"enabled\": %s,\n  \"rotationMode\": %d,\n  \"zoomBlocks\": %d,\n  \"sizePixels\": %d,\n  \"shape\": %d,\n  \"showBorder\": %s,\n  \"showOtherPlayers\": %s,\n  \"showEntities\": %s,\n  \"showDeathMarker\": %s,\n  \"limitMobHeight\": %s,\n  \"mobVerticalRange\": %d,\n  \"position\": %d,\n  \"updateSpeed\": %d,\n  \"clock24Hour\": %s,\n  \"showClock\": %s,\n  \"showCompass\": %s,\n  \"showBiome\": %s\n}\n".formatted(settings.enabled, settings.rotationMode, settings.zoomBlocks, settings.sizePixels, settings.shape, settings.showBorder, settings.showOtherPlayers, settings.showEntities, settings.showDeathMarker, settings.limitMobHeight, settings.mobVerticalRange, settings.position, settings.updateSpeed, settings.clock24Hour, settings.showClock, settings.showCompass, settings.showBiome));
            } catch (IOException var4) {
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
                    Boolean enabled = extractBool(json, "enabled");
                    Integer rotationMode = extractInt(json, "rotationMode");
                    Integer zoomBlocks = extractInt(json, "zoomBlocks");
                    Integer sizePixels = extractInt(json, "sizePixels");
                    Integer shape = extractInt(json, "shape");
                    Boolean showBorder = extractBool(json, "showBorder");
                    Boolean showOtherPlayers = extractBool(json, "showOtherPlayers");
                    Boolean showEntities = extractBool(json, "showEntities");
                    Boolean showDeathMarker = extractBool(json, "showDeathMarker");
                    Boolean limitMobHeight = extractBool(json, "limitMobHeight");
                    Integer mobVerticalRange = extractInt(json, "mobVerticalRange");
                    Integer position = extractInt(json, "position");
                    Integer updateSpeed = extractInt(json, "updateSpeed");
                    Boolean clock24Hour = extractBool(json, "clock24Hour");
                    Boolean showClock = extractBool(json, "showClock");
                    Boolean showCompass = extractBool(json, "showCompass");
                    Boolean showBiome = extractBool(json, "showBiome");
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

                    if (shape != null) {
                        settings.shape = shape;
                    }

                    if (showBorder != null) {
                        settings.showBorder = showBorder;
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

                    if (limitMobHeight != null) {
                        settings.limitMobHeight = limitMobHeight;
                    }

                    if (mobVerticalRange != null) {
                        settings.mobVerticalRange = mobVerticalRange;
                    }

                    if (position != null) {
                        settings.position = position;
                    }

                    if (updateSpeed != null) {
                        settings.updateSpeed = updateSpeed;
                    }

                    if (clock24Hour != null) {
                        settings.clock24Hour = clock24Hour;
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
                } catch (IOException var23) {
                }
            }

            settings.normalize();
            return settings;
        }
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

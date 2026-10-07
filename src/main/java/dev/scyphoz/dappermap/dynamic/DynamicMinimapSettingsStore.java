package dev.scyphoz.dappermap.dynamic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persists normalized player settings as small JSON files and caches active values in memory. */
public final class DynamicMinimapSettingsStore {
    private final Path dataDir;
    private final ConcurrentHashMap<UUID, DynamicMinimapSetting> cache = new ConcurrentHashMap<>();
    /** Whether each player has a file, remembered so the check doesn't touch the disk again. */
    private final ConcurrentHashMap<UUID, Boolean> hasFile = new ConcurrentHashMap<>();
    /**
     * Writes waiting for the background writer: a settings snapshot, or DELETE. Several changes
     * to one player before the writer runs collapse into a single write of the latest values.
     */
    private final ConcurrentHashMap<UUID, DynamicMinimapSetting> pendingWrites = new ConcurrentHashMap<>();
    private static final DynamicMinimapSetting DELETE = new DynamicMinimapSetting();
    /** One background thread per store: settings files are never written on a world thread. */
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DapperMap-Settings-IO");
        thread.setDaemon(true);
        return thread;
    });
    /** Matches every "key": value pair (booleans and integers) in one pass over the file. */
    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z_][\\w-]*)\"\\s*:\\s*(true|false|-?\\d+)");

    public DynamicMinimapSettingsStore(Path pluginDataDir) {
        this.dataDir = pluginDataDir.resolve("players");
    }

    public DynamicMinimapSetting get(UUID uuid, DynamicMinimapSetting defaults) {
        return this.cache.computeIfAbsent(uuid, (ignored) -> this.load(uuid, defaults));
    }

    /** True when this player has a settings file on disk, i.e. they are not a new player. */
    public boolean hasStoredSettings(UUID uuid) {
        return uuid != null && this.hasFile.computeIfAbsent(uuid,
                ignored -> Files.exists(this.file(uuid), new LinkOption[0]));
    }

    private Path file(UUID uuid) {
        return this.dataDir.resolve(String.valueOf(uuid) + ".json");
    }

    /**
     * Drops the cached copy for a player without touching their file. Used on disconnect so a
     * rejoin re-derives from the current admin defaults when the player has no file of their own.
     */
    public void evict(UUID uuid) {
        if (uuid != null) {
            this.cache.remove(uuid);
            this.hasFile.remove(uuid);
        }
    }

    /** Removes both the cached value and persisted override for a player. */
    public void delete(UUID uuid) {
        if (uuid == null) return;
        this.cache.remove(uuid);
        this.hasFile.put(uuid, false);
        this.enqueue(uuid, DELETE); // Same queue as writes, so a queued write can't bring the file back.
    }

    public void put(UUID uuid, DynamicMinimapSetting settings) {
        this.cache.put(uuid, settings);
        this.save(uuid);
    }

    /** Queues a write of the player's current settings (a snapshot taken now). */
    public void save(UUID uuid) {
        DynamicMinimapSetting settings = uuid == null ? null : this.cache.get(uuid);
        if (settings == null) return;
        this.hasFile.put(uuid, true);
        this.enqueue(uuid, settings.copy());
    }

    private void enqueue(UUID uuid, DynamicMinimapSetting snapshot) {
        // Only the first queued change for a player schedules a job; later ones replace its data.
        if (this.pendingWrites.put(uuid, snapshot) != null) return;
        try {
            this.writer.execute(() -> this.flushOne(uuid));
        } catch (java.util.concurrent.RejectedExecutionException shutDown) {
            this.flushOne(uuid); // Writer stopped (server shutting down): write directly.
        }
    }

    private void flushOne(UUID uuid) {
        DynamicMinimapSetting snapshot = this.pendingWrites.remove(uuid);
        if (snapshot == null) return;
        try {
            if (snapshot == DELETE) Files.deleteIfExists(this.file(uuid));
            else this.write(uuid, snapshot);
        } catch (IOException | RuntimeException ignored) {
            // A failed write keeps the in-memory settings; the next change writes again.
        }
    }

    /** Writes everything still queued and stops the writer. Called on plugin shutdown. */
    public void shutdown() {
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) this.writer.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        for (UUID uuid : this.pendingWrites.keySet()) this.flushOne(uuid); // Anything left over.
    }

    private void write(UUID uuid, DynamicMinimapSetting settings) throws IOException {
        {
            { // (Indentation kept from the original block so the field list diff stays readable.)
                Files.createDirectories(this.dataDir);
                Path file = this.file(uuid);
                // Write a temp file, then swap it in, so a crash mid-write never leaves half a file.
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");

                Files.writeString(
                        temp,
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
                                + "  \"creatureMarkerLimit\": %d,\n"
                                + "  \"creatureIcons\": %s,\n"
                                + "  \"hostileCreaturesOnly\": %s,\n"
                                + "  \"showDeathMarker\": %s,\n"
                                + "  \"showWorldMapMarkers\": %s,\n"
                                + "  \"limitMobHeight\": %s,\n"
                                + "  \"mobVerticalRange\": %d,\n"
                                + "  \"position\": %d,\n"
                                + "  \"hudPositionX\": %d,\n"
                                + "  \"hudPositionY\": %d,\n"
                                + "  \"infoPanelEnabled\": %s,\n"
                                + "  \"infoPanelPosition\": %d,\n"
                                + "  \"infoPanelTextAlignment\": %d,\n"
                                + "  \"updateSpeed\": %d,\n"
                                + "  \"clock24Hour\": %s,\n"
                                + "  \"showCoordinates\": %s,\n"
                                + "  \"showClock\": %s,\n"
                                + "  \"showCompass\": %s,\n"
                                + "  \"showBiome\": %s,\n"
                                + "  \"betterMapEnabled\": %s,\n"
                                + "  \"betterMapMarkersEnabled\": %s,\n"
                                + "  \"betterMapCaveModeEnabled\": %s%s\n"
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
                                settings.creatureMarkerLimit,
                                settings.creatureIcons,
                                settings.hostileCreaturesOnly,
                                settings.showDeathMarker,
                                settings.showWorldMapMarkers,
                                settings.limitMobHeight,
                                settings.mobVerticalRange,
                                settings.position,
                                settings.hudPositionX,
                                settings.hudPositionY,
                                settings.infoPanelEnabled,
                                settings.infoPanelPosition,
                                settings.infoPanelTextAlignment,
                                settings.updateSpeed,
                                settings.clock24Hour,
                                settings.showCoordinates,
                                settings.showClock,
                                settings.showCompass,
                                settings.showBiome,
                                settings.betterMapEnabled,
                                settings.betterMapMarkersEnabled,
                                settings.betterMapCaveModeEnabled,
                                colourFields(settings.colours)
                        )
                );
                try {
                    Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                    Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private DynamicMinimapSetting load(UUID uuid, DynamicMinimapSetting defaults) {
        DynamicMinimapSetting settings = defaults.copy();
        if (uuid == null) {
            settings.normalize();
            return settings;
        } else {
            Path file = this.file(uuid);
            boolean exists = Files.exists(file, new LinkOption[0]);
            this.hasFile.put(uuid, exists);
            if (exists) {
                try {
                    java.util.Map<String, String> json = fields(Files.readString(file));
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
                    Integer creatureMarkerLimit = extractInt(json, "creatureMarkerLimit");
                    Boolean creatureIcons = extractBool(json, "creatureIcons");
                    Boolean hostileCreaturesOnly = extractBool(json, "hostileCreaturesOnly");
                    Integer mobVerticalRange = extractInt(json, "mobVerticalRange");
                    Integer position = extractInt(json, "position");
                    Integer hudPositionX = extractInt(json, "hudPositionX");
                    Integer hudPositionY = extractInt(json, "hudPositionY");
                    Boolean infoPanelEnabled = extractBool(json, "infoPanelEnabled");
                    Integer infoPanelPosition = extractInt(json, "infoPanelPosition");
                    Integer infoPanelTextAlignment = extractInt(json, "infoPanelTextAlignment");
                    Integer updateSpeed = extractInt(json, "updateSpeed");
                    Boolean clock24Hour = extractBool(json, "clock24Hour");
                    Boolean showCoordinates = extractBool(json, "showCoordinates");
                    Boolean showClock = extractBool(json, "showClock");
                    Boolean showCompass = extractBool(json, "showCompass");
                    Boolean showBiome = extractBool(json, "showBiome");
                    Boolean betterMapEnabled = extractBool(json, "betterMapEnabled");
                    Boolean betterMapMarkersEnabled = extractBool(json, "betterMapMarkersEnabled");
                    Boolean betterMapCaveModeEnabled = extractBool(json, "betterMapCaveModeEnabled");
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

                    if (creatureMarkerLimit != null) settings.creatureMarkerLimit = creatureMarkerLimit;
                    // Files without this key (pre-v12) take the inherited default (icons, unless an admin changed it).
                    if (creatureIcons != null) settings.creatureIcons = creatureIcons;
                    if (hostileCreaturesOnly != null) settings.hostileCreaturesOnly = hostileCreaturesOnly;
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

                    if (infoPanelEnabled != null) {
                        settings.infoPanelEnabled = infoPanelEnabled;
                    }

                    if (infoPanelPosition != null) {
                        settings.infoPanelPosition = infoPanelPosition;
                    }

                    if (infoPanelTextAlignment != null) {
                        settings.infoPanelTextAlignment = infoPanelTextAlignment;
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
                    if (betterMapMarkersEnabled != null) settings.betterMapMarkersEnabled = betterMapMarkersEnabled;
                    if (betterMapCaveModeEnabled != null) settings.betterMapCaveModeEnabled = betterMapCaveModeEnabled;
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

    /** Every "key": value pair in the file (first occurrence wins, as before). */
    private static java.util.Map<String, String> fields(String json) {
        java.util.Map<String, String> out = new java.util.HashMap<>(96);
        Matcher matcher = FIELD.matcher(json);
        while (matcher.find()) out.putIfAbsent(matcher.group(1), matcher.group(2));
        return out;
    }

    private static Integer extractColour(java.util.Map<String, String> json, String key) {
        try {
            Integer value = extractInt(json, key);
            return value != null && value >= 0 && value <= 0xffffff ? value : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static Boolean extractBool(java.util.Map<String, String> json, String key) {
        String value = json.get(key);
        return "true".equals(value) ? Boolean.TRUE : "false".equals(value) ? Boolean.FALSE : null;
    }

    private static Integer extractInt(java.util.Map<String, String> json, String key) {
        String value = json.get(key);
        if (value == null || value.equals("true") || value.equals("false")) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException tooLarge) { return null; }
    }
}

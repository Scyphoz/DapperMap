package dev.scyphoz.dappermap.dynamic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.scyphoz.dappermap.util.DapperMapUtil;

/**
 * Persists settings as small JSON files and caches active values in memory.
 *
 * <p>Files are sparse: each holds only the values that differ from what its owner inherits
 * (a player inherits the server defaults; the server defaults inherit the mod defaults). Values
 * that were never changed are left out, so they keep following new defaults from an admin or a
 * new DapperMap version. Files without a "format" key are full snapshots from before 3.1.0; they
 * are read as before and rewritten sparse on their next save.
 */
public final class DynamicMinimapSettingsStore {
    /**
     * Written into every file; its absence marks a pre-3.1.0 full-snapshot file. 3: Compass Dashes
     * has its own colour (format 2 and older files had one "ticks" colour for every dash).
     */
    private static final int FORMAT = 3;
    /**
     * Colour defaults that 3.1.0 changed (colour id -> the 3.0.x default). In a pre-3.1.0 file
     * every colour was saved, so a value equal to its old default was never chosen by the player:
     * it is skipped on load and the current default applies instead.
     */
    private static final Map<String, Integer> RETIRED_COLOUR_DEFAULTS = Map.of(
            "hostile", 0xFF9566,
            "hostile-active", 0xFF526B);

    /** Creature height range default before 3.1.0 (now 16), skipped in legacy files like the colours above. */
    private static final int RETIRED_VERTICAL_RANGE_DEFAULT = 32;

    private final Path dataDir;
    private final ConcurrentHashMap<UUID, DynamicMinimapSetting> cache = new ConcurrentHashMap<>();
    /**
     * The inherited values each owner's settings were built from (a private copy, never mutated).
     * Saves write only the values that differ from this. Missing: every value is written.
     */
    private final ConcurrentHashMap<UUID, DynamicMinimapSetting> bases = new ConcurrentHashMap<>();
    /** Whether each player has a file, remembered so the check doesn't touch the disk again. */
    private final ConcurrentHashMap<UUID, Boolean> hasFile = new ConcurrentHashMap<>();
    /**
     * File text read ahead on the settings I/O thread when a player connects (NO_FILE: none on
     * disk), so their first load on the world thread needs no disk access. Used once; any write
     * or delete drops it, so it is never older than the file.
     */
    private final ConcurrentHashMap<UUID, String> prefetched = new ConcurrentHashMap<>();
    private static final String NO_FILE = new String("<no file>"); // Identity sentinel.
    /** A queued write: a settings snapshot and the base it is diffed against, or DELETE. */
    private record Pending(DynamicMinimapSetting settings, DynamicMinimapSetting base) {}
    private static final Pending DELETE = new Pending(null, null);
    /**
     * Writes waiting for the background writer. Several changes to one player before the writer
     * runs collapse into a single write of the latest values.
     */
    private final ConcurrentHashMap<UUID, Pending> pendingWrites = new ConcurrentHashMap<>();
    /**
     * The exact text of each player's file as last written or read, so an unchanged save (every
     * disconnect and shutdown) skips the disk. Updated together with the file under fileLock.
     */
    private final ConcurrentHashMap<UUID, String> lastWritten = new ConcurrentHashMap<>();
    /** Pairs each file read or write with its lastWritten update, so the two never disagree. */
    private final Object fileLock = new Object();
    /** One background thread per store: settings files are never written on a world thread. */
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(
            DapperMapUtil.daemonThreads("DapperMap-Settings-IO"));
    /** Matches every "key": value pair (booleans and integers) in one pass over the file. */
    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z_][\\w-]*)\"\\s*:\\s*(true|false|-?\\d+)");

    /** Set for a single-owner store (the admin defaults): every owner id maps to this one file. */
    private final Path singleFile;

    /** Per-player store: one file per player in {@code <pluginDataDir>/players/}. */
    public DynamicMinimapSettingsStore(Path pluginDataDir) {
        this(pluginDataDir.resolve("players"), null);
    }

    private DynamicMinimapSettingsStore(Path dataDir, Path singleFile) {
        this.dataDir = dataDir;
        this.singleFile = singleFile;
    }

    /** Store backed by one fixed file, e.g. the server defaults in admin-defaults.json. */
    public static DynamicMinimapSettingsStore forFile(Path file) {
        return new DynamicMinimapSettingsStore(file.getParent(), file);
    }

    /** The owner's settings: {@code defaults} with their saved changes applied. */
    public DynamicMinimapSetting get(UUID uuid, DynamicMinimapSetting defaults) {
        return this.cache.computeIfAbsent(uuid, (ignored) -> this.load(uuid, defaults));
    }

    /**
     * Reads a connecting player's file on the settings I/O thread (queued behind any pending
     * write for them), ready for {@link #get}. Harmless if never used: load() reads as before.
     */
    public void prefetch(UUID uuid) {
        if (uuid == null || this.singleFile != null || this.cache.containsKey(uuid)) return;
        try {
            this.writer.execute(() -> {
                if (this.cache.containsKey(uuid) || this.pendingWrites.containsKey(uuid)) return;
                synchronized (this.fileLock) {
                    try {
                        Path file = this.file(uuid);
                        this.prefetched.put(uuid, Files.exists(file) ? Files.readString(file) : NO_FILE);
                        if (this.prefetched.size() > 256) this.prefetched.clear(); // Bounded (never-joined connects).
                    } catch (IOException | RuntimeException ignored) {
                        this.prefetched.remove(uuid); // load() reads it itself.
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException shutDown) {
            // Shutting down: load() reads the file directly.
        }
    }

    /** True when this player has a settings file on disk, i.e. they are not a new player. */
    public boolean hasStoredSettings(UUID uuid) {
        if (uuid == null) return false;
        String ready = this.prefetched.get(uuid);
        if (ready != null && !this.hasFile.containsKey(uuid)) return ready != NO_FILE; // No disk check.
        return this.hasFile.computeIfAbsent(uuid, ignored -> Files.exists(this.file(uuid)));
    }

    private Path file(UUID uuid) {
        return this.singleFile != null ? this.singleFile : this.dataDir.resolve(uuid + ".json");
    }

    /**
     * Drops the cached copy for a player without touching their file. Used on disconnect so a
     * rejoin re-derives from the current admin defaults when the player has no file of their own.
     */
    public void evict(UUID uuid) {
        if (uuid != null) {
            this.cache.remove(uuid);
            this.bases.remove(uuid);
            this.hasFile.remove(uuid);
            this.prefetched.remove(uuid);
            // Keep the file's known text while the disconnect save is still queued, so an
            // unchanged save skips the disk; the writer drops it afterwards (flushOne).
            if (!this.pendingWrites.containsKey(uuid)) this.lastWritten.remove(uuid);
        }
    }

    /**
     * Removes both the cached value and persisted override for a player. {@code newBase} is
     * what they inherit from now on (the defaults they were reset to), so later changes this
     * session are saved against it.
     */
    public void delete(UUID uuid, DynamicMinimapSetting newBase) {
        if (uuid == null) return;
        this.cache.remove(uuid);
        if (newBase != null) this.bases.put(uuid, newBase.copy());
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
        this.enqueue(uuid, new Pending(settings.copy(), this.bases.get(uuid)));
    }

    private void enqueue(UUID uuid, Pending pending) {
        // Only the first queued change for a player schedules a job; later ones replace its data.
        if (this.pendingWrites.put(uuid, pending) != null) return;
        try {
            this.writer.execute(() -> this.flushOne(uuid));
        } catch (java.util.concurrent.RejectedExecutionException shutDown) {
            this.flushOne(uuid); // Writer stopped (server shutting down): write directly.
        }
    }

    private void flushOne(UUID uuid) {
        // The entry stays queued until its file is written, so a rejoin in between (load())
        // reads these values instead of the old file.
        Pending pending = this.pendingWrites.get(uuid);
        if (pending == null) return;
        try {
            synchronized (this.fileLock) {
                if (pending == DELETE) {
                    this.deleteFile(uuid);
                } else {
                    this.write(uuid, pending.settings(), pending.base());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A failed write keeps the in-memory settings; the next change writes again.
        }
        if (this.pendingWrites.remove(uuid, pending)) {
            // Player gone (evicted) and nothing newer queued: the file's text is no longer needed.
            if (!this.cache.containsKey(uuid)) this.lastWritten.remove(uuid);
        } else {
            // A newer change arrived during this write (it scheduled no job of its own): write it too.
            try {
                this.writer.execute(() -> this.flushOne(uuid));
            } catch (java.util.concurrent.RejectedExecutionException shutDown) {
                this.flushOne(uuid);
            }
        }
    }

    /** Deletes the file (caller holds fileLock). */
    private void deleteFile(UUID uuid) throws IOException {
        this.lastWritten.remove(uuid); // A later save must write again.
        this.prefetched.remove(uuid);
        Files.deleteIfExists(this.file(uuid));
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

    /**
     * Writes one owner's settings file (temp file + atomic move, so a crash never leaves half a
     * file). Settings with no changes from the base delete the file instead: the owner then
     * inherits everything, exactly like a new player. Caller holds fileLock.
     */
    private void write(UUID uuid, DynamicMinimapSetting settings, DynamicMinimapSetting base) throws IOException {
        String json = json(settings, base);
        if (json == null) {
            this.deleteFile(uuid);
            this.hasFile.put(uuid, false);
            return;
        }
        this.prefetched.remove(uuid); // The file is about to change (or is confirmed): read-ahead is stale.
        if (json.equals(this.lastWritten.get(uuid))) return; // File already holds exactly this.
        this.lastWritten.remove(uuid); // Unknown state if the write below fails.
        DapperMapUtil.writeAtomically(this.file(uuid), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.lastWritten.put(uuid, json);
    }

    /**
     * The settings file text: only the values that differ from {@code base} (every value when
     * the base is unknown), or null when nothing differs. The legacy "position" key is never
     * written; hudPositionX/Y replaced it. Integer.toString always uses ASCII digits.
     */
    private static String json(DynamicMinimapSetting s, DynamicMinimapSetting b) {
        StringBuilder out = new StringBuilder(256).append("{\n  \"format\": ").append(FORMAT);
        int empty = out.length();
        boolean all = b == null;
        // One "key": value line per setting that differs from the base, in Field (file) order.
        for (DynamicMinimapSetting.Field field : DynamicMinimapSetting.Field.all()) {
            int value = field.get(s);
            if (!all && value == field.get(b)) continue;
            out.append(",\n  \"").append(field.key).append("\": ");
            if (field.bool) out.append(value != 0); else out.append(value);
        }
        // Colours are compared one by one, so changing one colour never pins the others.
        if (all || !s.colours.equals(b.colours)) {
            for (DynamicMinimapColours.Key key : DynamicMinimapColours.Key.values()) {
                int rgb = s.colours.rgb(key);
                if (all || rgb != b.colours.rgb(key)) field(out, "colour_" + key.storeId, rgb);
            }
        }
        return out.length() == empty ? null : out.append("\n}\n").toString();
    }

    private static void field(StringBuilder out, String key, int value) {
        out.append(",\n  \"").append(key).append("\": ").append(value);
    }

    private DynamicMinimapSetting load(UUID uuid, DynamicMinimapSetting defaults) {
        DynamicMinimapSetting settings = defaults.copy();
        if (uuid == null) {
            settings.normalize();
            return settings;
        }
        // Remember what this owner inherits: later saves keep only the values that differ from it.
        DynamicMinimapSetting base = defaults.copy();
        base.normalize();
        this.bases.put(uuid, base);
        // A quick rejoin can arrive before the disconnect save is written: use the queued values.
        Pending pending = this.pendingWrites.get(uuid);
        if (pending != null) {
            boolean deleted = pending == DELETE;
            this.hasFile.put(uuid, !deleted);
            if (!deleted) {
                settings.copyFrom(pending.settings());
                // Diff later saves against the same inherited values that queued save used.
                if (pending.base() != null) this.bases.put(uuid, pending.base());
            }
            settings.normalize();
            return settings;
        }
        Path file = this.file(uuid);
        // Read ahead at connect (settings I/O thread)? Then no disk access here (world thread).
        String ready = this.prefetched.remove(uuid);
        boolean exists = ready != null ? ready != NO_FILE : Files.exists(file);
        this.hasFile.put(uuid, exists);
        if (exists) {
            try {
                String text;
                synchronized (this.fileLock) { // Never between a write and its lastWritten update.
                    text = ready != null ? ready : Files.readString(file);
                    this.lastWritten.put(uuid, text);
                }
                Map<String, String> json = fields(text);
                // No "format" key: a pre-3.1.0 file holding every value, defaults included.
                boolean legacy = !json.containsKey("format");
                for (DynamicMinimapColours.Key key : DynamicMinimapColours.Key.values()) {
                    Integer rgb = extractColour(json, "colour_" + key.storeId);
                    if (rgb == null) continue;
                    // A legacy file's copy of a since-changed default was never the player's choice.
                    if (legacy && rgb.equals(RETIRED_COLOUR_DEFAULTS.get(key.storeId))) continue;
                    settings.colours = settings.colours.with(key, rgb);
                }
                // Format 2 and older: one "ticks" colour drew every dash, so a saved custom one keeps
                // colouring the small dashes too. (Newer files save Compass Dashes on its own.)
                Integer format = extractInt(json, "format");
                Integer ticks = extractColour(json, "colour_" + DynamicMinimapColours.Key.TICKS.storeId);
                if (ticks != null && (format == null || format < 3))
                    settings.colours = settings.colours.with(DynamicMinimapColours.Key.COMPASS_DASHES, ticks);
                // Very old files only have the four-corner "position": convert it to X/Y, unless
                // the file also has the newer X/Y keys (which then take precedence in the loop below).
                Integer position = extractInt(json, "position");
                if (position != null && (extractInt(json, DynamicMinimapSetting.Field.HUD_POSITION_X.key) == null
                        || extractInt(json, DynamicMinimapSetting.Field.HUD_POSITION_Y.key) == null)) {
                    settings.applyLegacyPosition(position);
                }
                // Every key is optional: a missing key (or one of the wrong kind) keeps the inherited
                // value. Files from before a setting existed simply inherit its default.
                for (DynamicMinimapSetting.Field field : DynamicMinimapSetting.Field.all()) {
                    Integer value;
                    if (field.bool) {
                        Boolean bool = extractBool(json, field.key);
                        value = bool == null ? null : bool ? 1 : 0;
                    } else {
                        value = extractInt(json, field.key);
                    }
                    if (value == null) continue;
                    // Legacy files hold the old 32-block default the player never chose: 16 applies instead.
                    if (legacy && field == DynamicMinimapSetting.Field.MOB_VERTICAL_RANGE
                            && value == RETIRED_VERTICAL_RANGE_DEFAULT) continue;
                    field.set(settings, value);
                }
            } catch (IOException unreadable) {
                // An unreadable file keeps the inherited defaults.
            }
        }
        settings.normalize();
        return settings;
    }

    /** Every "key": value pair in the file (first occurrence wins, as before). */
    private static Map<String, String> fields(String json) {
        Map<String, String> out = new java.util.HashMap<>(96);
        Matcher matcher = FIELD.matcher(json);
        while (matcher.find()) out.putIfAbsent(matcher.group(1), matcher.group(2));
        return out;
    }

    private static Integer extractColour(Map<String, String> json, String key) {
        Integer value = extractInt(json, key);
        return value != null && value >= 0 && value <= 0xffffff ? value : null;
    }

    private static Boolean extractBool(Map<String, String> json, String key) {
        String value = json.get(key);
        return "true".equals(value) ? Boolean.TRUE : "false".equals(value) ? Boolean.FALSE : null;
    }

    private static Integer extractInt(Map<String, String> json, String key) {
        String value = json.get(key);
        if (value == null || value.equals("true") || value.equals("false")) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException tooLarge) { return null; }
    }
}

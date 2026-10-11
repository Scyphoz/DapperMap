package dev.scyphoz.dappermap.service;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.scyphoz.dappermap.DapperMap;
import dev.scyphoz.dappermap.config.DapperMapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicAssetPublisher;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSession;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSettingsStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns active player sessions and mediates inheritance/persistence of player and admin settings. */
public class DapperMapSessionManager {

    private final Map<UUID, DynamicMinimapSetting> settingsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, DynamicMinimapSession> sessionsByPlayer = new ConcurrentHashMap<>();
    /**
     * Players who have changed at least one setting this session. Only these get written to
     * disk. Without this, every player who merely logged in once was given a frozen snapshot
     * file, which then shadowed any admin defaults configured afterwards.
     */
    private final Set<UUID> customisedPlayers = ConcurrentHashMap.newKeySet();
    /** Remembers whether a player opened the admin page from the player page or from a command. */
    private final Set<UUID> adminPageFromPlayerPage = ConcurrentHashMap.newKeySet();
    private DynamicMinimapSettingsStore settingsStore;
    private DynamicMinimapSettingsStore adminSettingsStore;
    private static final UUID ADMIN_DEFAULTS_ID = new UUID(0L, 1L);

    public void setDataDirectory(Path dataDirectory) {
        this.settingsStore = new DynamicMinimapSettingsStore(dataDirectory);
        // Server defaults live in one file in the mod's data folder.
        Path adminFile = dataDirectory.resolve(ADMIN_DEFAULTS_FILE);
        migrateAdminDefaultsFile(dataDirectory, adminFile);
        this.adminSettingsStore = DynamicMinimapSettingsStore.forFile(adminFile);
    }

    /** Server defaults file, in the mod's data folder. */
    private static final String ADMIN_DEFAULTS_FILE = "admin-defaults.json";

    /**
     * Before 3.1.0 the server defaults were saved as admin-defaults/players/&lt;fixed id&gt;.json.
     * Moves that file to admin-defaults.json (once, at startup) and removes the emptied folders.
     * An existing admin-defaults.json always wins; anything that cannot be moved is left in place.
     */
    private static void migrateAdminDefaultsFile(Path dataDirectory, Path target) {
        Path legacyDir = dataDirectory.resolve("admin-defaults");
        Path legacyPlayers = legacyDir.resolve("players");
        Path legacyFile = legacyPlayers.resolve(ADMIN_DEFAULTS_ID + ".json");
        try {
            if (!java.nio.file.Files.exists(legacyDir)) return; // Nothing to migrate (the usual case).
            if (java.nio.file.Files.exists(legacyFile) && !java.nio.file.Files.exists(target)) {
                java.nio.file.Files.createDirectories(dataDirectory);
                java.nio.file.Files.move(legacyFile, target);
            }
            // Only removes the folders once they are empty (otherwise this throws and stops here).
            java.nio.file.Files.deleteIfExists(legacyPlayers);
            java.nio.file.Files.deleteIfExists(legacyDir);
        } catch (java.io.IOException | RuntimeException ignored) {
            // Left as it was; the defaults still load from admin-defaults.json when present.
        }
    }

    public void showIfEnabledByDefault(Player player, PlayerRef playerRef, DapperMapConfig config) {
        DynamicMinimapSetting settings = this.getSettings(playerRef.getUuid(), config);
        // A world join may drop the client's dynamic images. A running session handles that
        // itself (resyncClient); otherwise forget the records so the next start re-sends them.
        DynamicMinimapSession running = this.sessionsByPlayer.get(playerRef.getUuid());
        if (running == null || !running.isRunning() || running.playerRef() != playerRef) {
            DynamicAssetPublisher.clear(playerRef.getUuid());
        }
        if (settings.enabled) {
            // PlayerReadyEvent fires on every world join (and after Universe.resetPlayer). A running
            // session for the same PlayerRef follows the player across worlds itself, so rebuild its
            // client-side HUD in place instead of a full stop/start (blank map, asset resends).
            DynamicMinimapSession live = this.sessionsByPlayer.get(playerRef.getUuid());
            if (live != null && live.isRunning() && live.playerRef() == playerRef) {
                live.resyncClient();
                return;
            }
            this.start(playerRef, settings);
        }

    }

    public boolean toggle(Player player, PlayerRef playerRef, DapperMapConfig config) {
        DynamicMinimapSetting settings = this.getSettings(playerRef.getUuid(), config);
        settings.enabled = !settings.enabled;
        if (settings.enabled) {
            this.start(playerRef, settings);
        } else {
            this.stop(playerRef.getUuid(), true);
        }

        this.customisedPlayers.add(playerRef.getUuid());
        this.save(playerRef.getUuid());
        return settings.enabled;
    }

    /**
     * Returns the player's settings, deriving them from the current admin defaults when the
     * player has no saved file. Reading settings never creates a file, so a player who has
     * never customised anything keeps inheriting admin defaults on every join.
     */
    public DynamicMinimapSetting getSettings(UUID playerUuid, DapperMapConfig config) {
        return this.settingsByPlayer.computeIfAbsent(playerUuid, (ignored) -> {
            DynamicMinimapSetting defaults = this.getAdminDefaults(config).copy();
            return this.settingsStore == null ? defaults : this.settingsStore.get(playerUuid, defaults);
        });
    }

    /** True when this player has their own saved settings and should not inherit admin defaults. */
    public boolean hasOwnSettings(UUID playerUuid) {
        return this.settingsStore != null && this.settingsStore.hasStoredSettings(playerUuid);
    }

    public void setAdminPageOrigin(UUID playerUuid, boolean fromPlayerPage) {
        if (playerUuid == null) return;
        if (fromPlayerPage) this.adminPageFromPlayerPage.add(playerUuid);
        else this.adminPageFromPlayerPage.remove(playerUuid);
    }

    public boolean adminPageOpenedFromPlayerPage(UUID playerUuid) {
        return playerUuid != null && this.adminPageFromPlayerPage.contains(playerUuid);
    }


    /**
     * Current server defaults. Returns a defensive copy: callers must go through
     * {@link #setAdminDefaults} to change them, so nothing can mutate the cached
     * instance without the change also reaching disk.
     */
    public DynamicMinimapSetting getAdminDefaults(DapperMapConfig config) {
        DynamicMinimapSetting modDefaults = DynamicMinimapSetting.fromConfig(config);
        return this.adminSettingsStore == null
                ? modDefaults
                : this.adminSettingsStore.get(ADMIN_DEFAULTS_ID, modDefaults).copy();
    }

    public void setAdminDefaults(DynamicMinimapSetting settings) {
        settings.normalize();
        if (this.adminSettingsStore != null) {
            // Load the admin file first (no-op once cached) so the store knows the mod defaults it
            // inherits and saves only what the admin changed; mod default changes then still apply.
            DapperMapConfig config = DapperMap.getConfig() == null ? null : DapperMap.getConfig().get();
            if (config != null) this.getAdminDefaults(config);
            this.adminSettingsStore.put(ADMIN_DEFAULTS_ID, settings.copy());
        }
    }

    public DynamicMinimapSetting resetAdminDefaults(DapperMapConfig config) {
        DynamicMinimapSetting settings = DynamicMinimapSetting.fromConfig(config);
        this.setAdminDefaults(settings);
        return settings;
    }

    public dev.scyphoz.dappermap.dynamic.DynamicMinimapRenderer.LiveSnapshot capturePreview(UUID uuid) {
        DynamicMinimapSession session = uuid == null ? null : this.sessionsByPlayer.get(uuid);
        return session == null ? null : session.capturePreview();
    }

    public void setSettings(UUID playerUuid, DynamicMinimapSetting settings) {
        settings.normalize();
        if (playerUuid == null) return; // ConcurrentHashMap rejects null keys.
        this.customisedPlayers.add(playerUuid);
        this.settingsByPlayer.put(playerUuid, settings);
        DynamicMinimapSession session = this.sessionsByPlayer.get(playerUuid);
        // The live session runs on its own copy, clamped to the admin limits.
        if (session != null) this.pushLive(playerUuid, session);

        this.save(playerUuid);
    }

    /**
     * Settings a live minimap actually runs with: the player's settings clamped to the admin
     * limits, then slowed one update-speed step while load shedding is active. Always a copy,
     * so the player's saved settings are never changed by limits or load shedding.
     */
    public static DynamicMinimapSetting liveSettings(DynamicMinimapSetting settings) {
        DynamicMinimapSetting live = settings.copy();
        DapperMapConfig config = DapperMap.getConfig() == null ? null : DapperMap.getConfig().get();
        if (config != null) config.applyLimits(live);
        live.updateSpeed = Math.min(3, live.updateSpeed + DapperMapServerLoad.sheddingLevel());
        live.normalize();
        return live;
    }

    /**
     * Re-applies limits and load shedding to every live minimap. Called when an admin changes
     * a limit or the load-shedding level changes; each session picks up the new values on its
     * next tick (only changed values cause any client update).
     */
    public void refreshLiveSettings() {
        for (Map.Entry<UUID, DynamicMinimapSession> entry : this.sessionsByPlayer.entrySet())
            this.pushLive(entry.getKey(), entry.getValue());
    }

    /**
     * Copies the player's current settings (limited) into their live session. The player's
     * settings are read inside the lock, so a refresh from the heartbeat thread can never
     * overwrite a newer change applied on the world thread with older values. The same lock
     * guards {@link DynamicMinimapSetting#copy()}, so render snapshots are never half-updated.
     */
    private void pushLive(UUID uuid, DynamicMinimapSession session) {
        DynamicMinimapSetting target = session.settings();
        synchronized (target) {
            DynamicMinimapSetting own = this.settingsByPlayer.get(uuid);
            if (own != null) target.copyFrom(liveSettings(own)); // Same field list as copy().
        }
        session.requestPromptTick(); // Idle players would otherwise see the change up to a marker interval late.
    }

    /** Number of players with a running minimap. */
    public int liveSessionCount() {
        return this.sessionsByPlayer.size();
    }

    /** Live minimaps by the update speed they currently run at (0 very fast .. 3 slow). */
    public int[] liveSpeedCounts() {
        int[] counts = new int[4];
        for (DynamicMinimapSession session : this.sessionsByPlayer.values())
            counts[Math.max(0, Math.min(3, session.settings().updateSpeed))]++;
        return counts;
    }

    /** Players with a running minimap whose own settings exceed at least one admin limit. */
    public int limitedPlayerCount() {
        DapperMapConfig config = DapperMap.getConfig() == null ? null : DapperMap.getConfig().get();
        if (config == null || !config.hasLimits()) return 0;
        int count = 0;
        for (UUID uuid : this.sessionsByPlayer.keySet()) {
            DynamicMinimapSetting own = this.settingsByPlayer.get(uuid);
            if (own != null && !config.describeLimitsApplied(own).isEmpty()) count++;
        }
        return count;
    }

    public void applySettings(PlayerRef playerRef, DynamicMinimapSetting settings) {
        this.setSettings(playerRef.getUuid(), settings);
        if (settings.enabled) {
            if (!this.sessionsByPlayer.containsKey(playerRef.getUuid())) {
                this.start(playerRef, settings);
            }
        } else {
            this.stop(playerRef.getUuid(), true);
        }

    }

    /**
     * Restores the current server defaults without leaving a player override
     * behind. This keeps future admin-default changes inheritable as expected.
     */
    public DynamicMinimapSetting resetSettings(PlayerRef playerRef, DapperMapConfig config) {
        DynamicMinimapSetting defaults = this.getAdminDefaults(config).copy();
        this.applySettings(playerRef, defaults);
        UUID uuid = playerRef.getUuid();
        this.customisedPlayers.remove(uuid);
        // The player now inherits these defaults; later changes are saved relative to them.
        if (this.settingsStore != null) this.settingsStore.delete(uuid, defaults);
        return defaults;
    }

    /** Reads a connecting player's settings file ahead, off the world thread (see the store). */
    public void onConnect(PlayerRef playerRef) {
        if (playerRef != null && this.settingsStore != null) this.settingsStore.prefetch(playerRef.getUuid());
    }

    public void onDisconnect(PlayerRef playerRef) {
        if (playerRef != null) {
            UUID uuid = playerRef.getUuid();
            this.save(uuid);
            this.stop(uuid, false);
            // Settings-page previews record delivered assets even with the minimap off; drop them.
            DynamicAssetPublisher.clear(uuid);
            dev.scyphoz.dappermap.integration.NativeWorldMapIntegration.forgetPlayer(uuid);
            // Drop every cached copy so the next join re-reads from disk, or re-derives from
            // the admin defaults as they stand at that moment.
            this.settingsByPlayer.remove(uuid);
            this.customisedPlayers.remove(uuid);
            this.adminPageFromPlayerPage.remove(uuid);
            if (this.settingsStore != null) {
                this.settingsStore.evict(uuid);
            }
        }

    }

    public void stopAll() {
        for(DynamicMinimapSession session : this.sessionsByPlayer.values()) {
            session.stop(false);
        }

        this.sessionsByPlayer.clear();
        if (this.settingsStore != null) {
            // Route through save(uuid) so the "only persist customised players" guard applies
            // on shutdown too.
            for(UUID uuid : this.settingsByPlayer.keySet()) {
                this.save(uuid);
            }
            // Settings files are written on a background thread; finish them before shutdown.
            this.settingsStore.shutdown();
        }
        if (this.adminSettingsStore != null) this.adminSettingsStore.shutdown();
    }

    private void start(PlayerRef playerRef, DynamicMinimapSetting settings) {
        UUID uuid = playerRef.getUuid();
        DynamicMinimapSession oldSession = this.sessionsByPlayer.remove(uuid);
        if (oldSession != null) {
            oldSession.stop(true);
        }

        settings.normalize();
        // The session gets its own (limited) copy; the player's settings object stays as saved.
        DynamicMinimapSession session = new DynamicMinimapSession(playerRef, liveSettings(settings));
        this.sessionsByPlayer.put(uuid, session);
        session.start();
    }

    private void stop(UUID uuid, boolean removeHud) {
        DynamicMinimapSession session = this.sessionsByPlayer.remove(uuid);
        if (session != null) {
            session.stop(removeHud);
        }

    }

    /**
     * Writes the player's settings, but only once they have actually changed something or
     * already had a file. Never creating a file for an untouched player is what lets admin
     * defaults keep applying to them.
     */
    private void save(UUID uuid) {
        if (uuid != null && !this.customisedPlayers.contains(uuid) && !this.hasOwnSettings(uuid)) {
            return;
        }
        if (this.settingsStore != null && uuid != null) {
            DynamicMinimapSetting settings = this.settingsByPlayer.get(uuid);
            if (settings != null) {
                this.settingsStore.put(uuid, settings);
            }
        }

    }
}

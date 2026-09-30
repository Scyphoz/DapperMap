package dev.scyphoz.dappermap.service;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.scyphoz.dappermap.config.MinimapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSession;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSettingsStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns active player sessions and mediates inheritance/persistence of player and admin settings. */
public class MinimapSessionManager {
    private final Map<UUID, DynamicMinimapSetting> settingsByPlayer = new ConcurrentHashMap();
    private final Map<UUID, DynamicMinimapSession> sessionsByPlayer = new ConcurrentHashMap();
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

    public MinimapSessionManager() {
    }

    public void setDataDirectory(Path dataDirectory) {
        this.settingsStore = new DynamicMinimapSettingsStore(dataDirectory);
        this.adminSettingsStore = new DynamicMinimapSettingsStore(dataDirectory.resolve("admin-defaults"));
    }

    public void showIfEnabledByDefault(Player player, PlayerRef playerRef, MinimapConfig config) {
        DynamicMinimapSetting settings = this.getSettings(playerRef.getUuid(), config);
        if (settings.enabled) {
            this.start(playerRef, settings);
        }

    }

    public boolean toggle(Player player, PlayerRef playerRef, MinimapConfig config) {
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
    public DynamicMinimapSetting getSettings(UUID playerUuid, MinimapConfig config) {
        return (DynamicMinimapSetting)this.settingsByPlayer.computeIfAbsent(playerUuid, (ignored) -> {
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
    public DynamicMinimapSetting getAdminDefaults(MinimapConfig config) {
        DynamicMinimapSetting modDefaults = DynamicMinimapSetting.fromConfig(config);
        return this.adminSettingsStore == null
                ? modDefaults
                : this.adminSettingsStore.get(ADMIN_DEFAULTS_ID, modDefaults).copy();
    }

    public void setAdminDefaults(DynamicMinimapSetting settings) {
        settings.normalize();
        if (this.adminSettingsStore != null) {
            this.adminSettingsStore.put(ADMIN_DEFAULTS_ID, settings.copy());
        }
    }

    public DynamicMinimapSetting resetAdminDefaults(MinimapConfig config) {
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
        if (playerUuid != null) {
            this.customisedPlayers.add(playerUuid);
        }
        this.settingsByPlayer.put(playerUuid, settings);
        DynamicMinimapSession session = (DynamicMinimapSession)this.sessionsByPlayer.get(playerUuid);
        if (session != null) {
            session.settings().colours = settings.colours;
            session.settings().enabled = settings.enabled;
            session.settings().rotationMode = settings.rotationMode;
            session.settings().zoomBlocks = settings.zoomBlocks;
            session.settings().sizePixels = settings.sizePixels;
            session.settings().textureResolution = settings.textureResolution;
            session.settings().interfaceScalePercent = settings.interfaceScalePercent;
            session.settings().shape = settings.shape;
            session.settings().showOtherPlayers = settings.showOtherPlayers;
            session.settings().showEntities = settings.showEntities;
            session.settings().creatureMarkerLimit = settings.creatureMarkerLimit;
            session.settings().showDeathMarker = settings.showDeathMarker;
            session.settings().showWorldMapMarkers = settings.showWorldMapMarkers;
            session.settings().limitMobHeight = settings.limitMobHeight;
            session.settings().mobVerticalRange = settings.mobVerticalRange;
            session.settings().position = settings.position;
            session.settings().hudPositionX = settings.hudPositionX;
            session.settings().hudPositionY = settings.hudPositionY;
            session.settings().infoPanelEnabled = settings.infoPanelEnabled;
            session.settings().infoPanelPosition = settings.infoPanelPosition;
            session.settings().infoPanelTextAlignment = settings.infoPanelTextAlignment;
            session.settings().updateSpeed = settings.updateSpeed;
            session.settings().clock24Hour = settings.clock24Hour;
            session.settings().showCoordinates = settings.showCoordinates;
            session.settings().showClock = settings.showClock;
            session.settings().showCompass = settings.showCompass;
            session.settings().showBiome = settings.showBiome;
            session.settings().betterMapEnabled = settings.betterMapEnabled;
            session.settings().betterMapMarkersEnabled = settings.betterMapMarkersEnabled;
            session.settings().betterMapCaveModeEnabled = settings.betterMapCaveModeEnabled;
        }

        this.save(playerUuid);
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
    public DynamicMinimapSetting resetSettings(PlayerRef playerRef, MinimapConfig config) {
        DynamicMinimapSetting defaults = this.getAdminDefaults(config).copy();
        this.applySettings(playerRef, defaults);
        UUID uuid = playerRef.getUuid();
        this.customisedPlayers.remove(uuid);
        if (this.settingsStore != null) this.settingsStore.delete(uuid);
        return defaults;
    }

    public void onDisconnect(PlayerRef playerRef) {
        if (playerRef != null) {
            UUID uuid = playerRef.getUuid();
            this.save(uuid);
            this.stop(uuid, false);
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
        }

    }

    private void start(PlayerRef playerRef, DynamicMinimapSetting settings) {
        UUID uuid = playerRef.getUuid();
        DynamicMinimapSession oldSession = (DynamicMinimapSession)this.sessionsByPlayer.remove(uuid);
        if (oldSession != null) {
            oldSession.stop(true);
        }

        settings.normalize();
        DynamicMinimapSession session = new DynamicMinimapSession(playerRef, settings);
        this.sessionsByPlayer.put(uuid, session);
        session.start();
    }

    private void stop(UUID uuid, boolean removeHud) {
        DynamicMinimapSession session = (DynamicMinimapSession)this.sessionsByPlayer.remove(uuid);
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
            DynamicMinimapSetting settings = (DynamicMinimapSetting)this.settingsByPlayer.get(uuid);
            if (settings != null) {
                this.settingsStore.put(uuid, settings);
            }
        }

    }
}

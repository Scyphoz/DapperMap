package dev.scyphoz.dappermap.service;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.scyphoz.dappermap.config.MinimapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSession;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSettingsStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MinimapSessionManager {
    private final Map<UUID, DynamicMinimapSetting> settingsByPlayer = new ConcurrentHashMap();
    private final Map<UUID, DynamicMinimapSession> sessionsByPlayer = new ConcurrentHashMap();
    private final AtomicInteger commandUses = new AtomicInteger();
    private DynamicMinimapSettingsStore settingsStore;

    public MinimapSessionManager() {
    }

    public void setDataDirectory(Path dataDirectory) {
        this.settingsStore = new DynamicMinimapSettingsStore(dataDirectory);
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

        this.save(playerRef.getUuid());
        return settings.enabled;
    }

    public DynamicMinimapSetting getSettings(UUID playerUuid, MinimapConfig config) {
        return (DynamicMinimapSetting)this.settingsByPlayer.computeIfAbsent(playerUuid, (ignored) -> {
            DynamicMinimapSetting defaults = DynamicMinimapSetting.fromConfig(config);
            return this.settingsStore == null ? defaults : this.settingsStore.get(playerUuid, defaults);
        });
    }

    public void setSettings(UUID playerUuid, DynamicMinimapSetting settings) {
        settings.normalize();
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
            session.settings().showBorder = settings.showBorder;
            session.settings().showOtherPlayers = settings.showOtherPlayers;
            session.settings().showEntities = settings.showEntities;
            session.settings().showDeathMarker = settings.showDeathMarker;
            session.settings().limitMobHeight = settings.limitMobHeight;
            session.settings().mobVerticalRange = settings.mobVerticalRange;
            session.settings().position = settings.position;
            session.settings().hudPositionX = settings.hudPositionX;
            session.settings().hudPositionY = settings.hudPositionY;
            session.settings().infoPanelPosition = settings.infoPanelPosition;
            session.settings().updateSpeed = settings.updateSpeed;
            session.settings().clock24Hour = settings.clock24Hour;
            session.settings().showClock = settings.showClock;
            session.settings().showCompass = settings.showCompass;
            session.settings().showBiome = settings.showBiome;
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

    public void noteCommandUsed() {
        this.commandUses.incrementAndGet();
    }

    public int getCommandUses() {
        return this.commandUses.get();
    }

    public void refreshIfDue(Player player, PlayerRef playerRef) {
    }

    public void onDisconnect(PlayerRef playerRef) {
        if (playerRef != null) {
            this.save(playerRef.getUuid());
            this.stop(playerRef.getUuid(), false);
        }

    }

    public void stopAll() {
        for(DynamicMinimapSession session : this.sessionsByPlayer.values()) {
            session.stop(false);
        }

        this.sessionsByPlayer.clear();
        if (this.settingsStore != null) {
            for(UUID uuid : this.settingsByPlayer.keySet()) {
                this.settingsStore.save(uuid);
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

    private void save(UUID uuid) {
        if (this.settingsStore != null && uuid != null) {
            DynamicMinimapSetting settings = (DynamicMinimapSetting)this.settingsByPlayer.get(uuid);
            if (settings != null) {
                this.settingsStore.put(uuid, settings);
            }
        }

    }
}

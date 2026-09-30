
package dev.scyphoz.dappermap;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;
import dev.scyphoz.dappermap.commands.MinimapCommand;
import dev.scyphoz.dappermap.config.MinimapConfig;
import dev.scyphoz.dappermap.events.PlayerReadyListener;
import dev.scyphoz.dappermap.permissions.DapperMapPermissions;
import dev.scyphoz.dappermap.service.MinimapSessionManager;
import javax.annotation.Nonnull;

/**
 * Plugin entry point. It owns configuration, permission/command registration and the
 * player-session lifecycle; rendering work is delegated to {@link MinimapSessionManager}.
 */
public class DapperMap extends JavaPlugin {
    private static Config<MinimapConfig> config = null;
    private final MinimapSessionManager sessions;
    private java.util.concurrent.ScheduledFuture<?> imageCleanup;

    public DapperMap(@Nonnull JavaPluginInit init) {
        super(init);
        config = this.withConfig("dappermap_minimap", MinimapConfig.CODEC);
        this.sessions = new MinimapSessionManager();
    }

    @Override
    protected void setup() {
        config.save();
        DapperMapPermissions.register(config.get().isGrantPlayerSettingsByDefault());
        this.imageCleanup = com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                dev.scyphoz.dappermap.integration.BetterMapIntegration::pruneImages,
                5L, 5L, java.util.concurrent.TimeUnit.SECONDS);
        this.sessions.setDataDirectory(this.getDataDirectory());
        this.getCommandRegistry().registerCommand(new MinimapCommand("dappermap", "Configure DapperMap", this.sessions));
        this.getEventRegistry().registerGlobal(
                com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent.class,
                event -> dev.scyphoz.dappermap.integration.BetterMapIntegration.clearWorld(event.getWorld()));
        PlayerReadyListener readyListener = new PlayerReadyListener(this.sessions);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, readyListener::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, (event) -> this.sessions.onDisconnect(event.getPlayerRef()));
    }

    @Override
    protected void shutdown() {
        if (this.imageCleanup != null) this.imageCleanup.cancel(false);
        this.sessions.stopAll();
        dev.scyphoz.dappermap.integration.BetterMapIntegration.clearImages();
        dev.scyphoz.dappermap.dynamic.DynamicMinimapSession.shutdownRendering();
    }

    public static Config<MinimapConfig> getConfig() {
        return config;
    }
}

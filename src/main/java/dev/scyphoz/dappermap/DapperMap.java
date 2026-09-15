
package dev.scyphoz.dappermap;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;
import dev.scyphoz.dappermap.commands.MinimapCommand;
import dev.scyphoz.dappermap.config.MinimapConfig;
import dev.scyphoz.dappermap.events.PlayerReadyListener;
import dev.scyphoz.dappermap.service.MinimapSessionManager;
import java.util.Objects;
import javax.annotation.Nonnull;

public class DapperMap extends JavaPlugin {
    private static Config<MinimapConfig> config = null;
    private final MinimapSessionManager sessions;

    public DapperMap(@Nonnull JavaPluginInit init) {
        super(init);
        config = this.withConfig("dappermap_minimap", MinimapConfig.CODEC);
        this.sessions = new MinimapSessionManager();
    }

    protected void setup() {
        config.save();
        this.sessions.setDataDirectory(this.getDataDirectory());
        this.getCommandRegistry().registerCommand(new MinimapCommand("dappermap", "Configure DapperMap", this.sessions));
        EventRegistry var10000 = this.getEventRegistry();
        PlayerReadyListener var10002 = new PlayerReadyListener(this.sessions);
        Objects.requireNonNull(var10002);
        var10000.registerGlobal(PlayerReadyEvent.class, var10002::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, (event) -> this.sessions.onDisconnect(event.getPlayerRef()));
    }

    protected void shutdown() {
        this.sessions.stopAll();
    }

    public static Config<MinimapConfig> getConfig() {
        return config;
    }
}

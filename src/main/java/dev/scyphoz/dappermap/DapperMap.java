
package dev.scyphoz.dappermap;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;
import dev.scyphoz.dappermap.commands.DapperMapCommand;
import dev.scyphoz.dappermap.config.DapperMapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicCreatureIcons;
import dev.scyphoz.dappermap.events.PlayerReadyListener;
import dev.scyphoz.dappermap.permissions.DapperMapPermissions;
import dev.scyphoz.dappermap.service.DapperMapServerLoad;
import dev.scyphoz.dappermap.service.DapperMapSessionManager;

import javax.annotation.Nonnull;

/**
 * Plugin entry point. It owns configuration, permission/command registration and the
 * player-session lifecycle; rendering work is delegated to {@link DapperMapSessionManager}.
 */
public class DapperMap extends JavaPlugin {
    private static Config<DapperMapConfig> config = null;
    private final DapperMapSessionManager sessions;
    private java.util.concurrent.ScheduledFuture<?> imageCleanup;

    public DapperMap(@Nonnull JavaPluginInit init) {
        super(init);
        config = this.withConfig("dappermap_minimap", DapperMapConfig.CODEC);
        this.sessions = new DapperMapSessionManager();
    }

    @Override
    protected void setup() {
        config.save();
        DapperMapPermissions.register(config.get().isGrantPlayerSettingsByDefault());
        // PNG deflate level for every generated image (terrain, frames, icons).
        dev.scyphoz.dappermap.util.DapperMapUtil.setPngCompression(config.get().getPngCompressionLevel());
        // Colour limit for full-colour terrain images (0 = full colour).
        dev.scyphoz.dappermap.dynamic.DynamicMinimapRenderer.setTerrainColours(config.get().getTerrainColours());
        // Runs on DapperMap's own heartbeat thread, not the server's shared scheduler.
        this.imageCleanup = dev.scyphoz.dappermap.dynamic.DynamicMinimapSession.heartbeat().scheduleAtFixedRate(
                dev.scyphoz.dappermap.integration.BetterMapIntegration::pruneImages,
                5L, 5L, java.util.concurrent.TimeUnit.SECONDS);
        this.sessions.setDataDirectory(this.getDataDirectory());
        // Creature face icons saved between restarts (render pool I/O only; size-capped).
        DynamicCreatureIcons.configureDiskCache(
                this.getDataDirectory().resolve("icon-cache"),
                config.get().isCreatureIconDiskCache(), config.get().getCreatureIconDiskCacheBytes());
        // PvP: remembers which players recently hit which, so attackers show in the attacking colour.
        dev.scyphoz.dappermap.dynamic.DynamicEntityScanner.PlayerAttacks.register(this.getEntityStoreRegistry());
        // Once-a-second load sampler for /dappermap admin stats and optional load shedding.
        DapperMapServerLoad.start(this.sessions);
        this.getCommandRegistry().registerCommand(new DapperMapCommand("dappermap", "Configure DapperMap", this.sessions));
        this.getEventRegistry().registerGlobal(
                com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent.class,
                event -> {
                    dev.scyphoz.dappermap.integration.BetterMapIntegration.clearWorld(event.getWorld());
                    dev.scyphoz.dappermap.integration.NativeWorldMapIntegration.clearWorld(event.getWorld());
                });
        PlayerReadyListener readyListener = new PlayerReadyListener(this.sessions);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, readyListener::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, (event) -> {
            this.sessions.onDisconnect(event.getPlayerRef());
            // A player who leaves is no longer anyone's attacker (or anyone's victim).
            var ref = event.getPlayerRef();
            if (ref != null) dev.scyphoz.dappermap.dynamic.DynamicEntityScanner.PlayerAttacks.forget(ref.getUuid());
        });
        // Settings file read-ahead before the player is ready, so the join tick needs no disk access.
        this.getEventRegistry().registerGlobal(
                com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent.class,
                event -> this.sessions.onConnect(event.getPlayerRef()));
    }

    @Override
    protected void shutdown() {
        if (this.imageCleanup != null) this.imageCleanup.cancel(false);
        DapperMapServerLoad.stop();
        this.sessions.stopAll();
        dev.scyphoz.dappermap.integration.BetterMapIntegration.clearImages();
        // Stops the thread that runs blocking world-map providers (spawn) off the world thread.
        dev.scyphoz.dappermap.integration.NativeWorldMapIntegration.shutdown();
        dev.scyphoz.dappermap.dynamic.DynamicMinimapSession.shutdownRendering();
    }

    public static Config<DapperMapConfig> getConfig() {
        return config;
    }

    /** Guards {@link #saving} and {@link #saveAgain}. */
    private static final Object SAVE_LOCK = new Object();
    /** A save is being written (Config.save writes through one shared .tmp file on a writer pool). */
    private static boolean saving;
    /** Another save was asked for while one was running: write once more afterwards. */
    private static boolean saveAgain;

    /**
     * Writes dappermap_minimap.json after an admin changes limits or load shedding in game.
     * Single-flight: two saves never write the shared temp file at once. Requests made while
     * one runs coalesce into exactly one more save, which writes the newest values.
     */
    public static void saveConfig() {
        Config<DapperMapConfig> current = config;
        if (current == null) return;
        synchronized (SAVE_LOCK) {
            if (saving) { saveAgain = true; return; }
            saving = true;
        }
        startSave(current);
    }

    /** Starts one write; when it finishes, runs the coalesced follow-up save if one was asked for. */
    private static void startSave(Config<DapperMapConfig> current) {
        java.util.concurrent.CompletableFuture<Void> write;
        try {
            write = current.save();
        } catch (RuntimeException failure) {
            write = java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        if (write == null) write = java.util.concurrent.CompletableFuture.completedFuture(null);
        // A write stuck for 30 s (e.g. writer pool stopped) releases the flag so later saves aren't
        // silently dropped; the copy leaves Hytale's own future untouched.
        write.copy().orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
            if (failure != null) dev.scyphoz.dappermap.util.DapperMapUtil.logFailure("config save", failure);
            boolean again;
            synchronized (SAVE_LOCK) {
                again = saveAgain;
                saveAgain = false;
                if (!again) saving = false;
            }
            // Runs on the writer thread, so the follow-up's encode is off the world thread too.
            if (again) startSave(current);
        });
    }
}

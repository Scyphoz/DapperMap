package dev.scyphoz.dappermap;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;
import dev.scyphoz.dappermap.commands.DapperMapCommand;
import dev.scyphoz.dappermap.config.DapperMapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicCreatureIcons;
import dev.scyphoz.dappermap.dynamic.DynamicEntityScanner;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapRenderer;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSession;
import dev.scyphoz.dappermap.integration.BetterMapIntegration;
import dev.scyphoz.dappermap.integration.NativeWorldMapIntegration;
import dev.scyphoz.dappermap.permissions.DapperMapPermissions;
import dev.scyphoz.dappermap.service.DapperMapServerLoad;
import dev.scyphoz.dappermap.service.DapperMapSessionManager;
import dev.scyphoz.dappermap.util.DapperMapUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

/**
 * Plugin entry point. It owns configuration, permission/command registration and the
 * player-session lifecycle; rendering work is delegated to {@link DapperMapSessionManager}.
 */
public class DapperMap extends JavaPlugin {
    private static Config<DapperMapConfig> config = null;
    private final DapperMapSessionManager sessions;
    private ScheduledFuture<?> imageCleanup;

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
        DapperMapUtil.setPngCompression(config.get().getPngCompressionLevel());
        // Colour limit for full-colour terrain images (0 = full colour).
        DynamicMinimapRenderer.setTerrainColours(config.get().getTerrainColours());
        // Runs on DapperMap's own heartbeat thread, not the server's shared scheduler.
        this.imageCleanup = DynamicMinimapSession.heartbeat().scheduleAtFixedRate(
                BetterMapIntegration::pruneImages, 5L, 5L, TimeUnit.SECONDS);
        this.sessions.setDataDirectory(this.getDataDirectory());
        // Creature face icons saved between restarts (render pool I/O only; size-capped).
        DynamicCreatureIcons.configureDiskCache(
                this.getDataDirectory().resolve("icon-cache"),
                config.get().isCreatureIconDiskCache(), config.get().getCreatureIconDiskCacheBytes());
        // PvP: remembers which players recently hit which, so attackers show in the attacking colour.
        DynamicEntityScanner.PlayerAttacks.register(this.getEntityStoreRegistry());
        // Once-a-second load sampler for /dappermap admin stats and optional load shedding.
        DapperMapServerLoad.start(this.sessions);
        this.getCommandRegistry().registerCommand(new DapperMapCommand("dappermap", "Configure DapperMap", this.sessions));
        this.getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> {
            BetterMapIntegration.clearWorld(event.getWorld());
            NativeWorldMapIntegration.clearWorld(event.getWorld());
        });
        // Starts or restores a player's minimap once Hytale reports the player is ready
        // (fires on every world join, and after Universe.resetPlayer).
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, (event) -> {
            this.sessions.onDisconnect(event.getPlayerRef());
            // A player who leaves is no longer anyone's attacker (or anyone's victim).
            var ref = event.getPlayerRef();
            if (ref != null) DynamicEntityScanner.PlayerAttacks.forget(ref.getUuid());
        });
        // Settings file read-ahead before the player is ready, so the join tick needs no disk access.
        this.getEventRegistry().registerGlobal(PlayerConnectEvent.class,
                event -> this.sessions.onConnect(event.getPlayerRef()));
    }

    /** PlayerReady: show (or resync) the minimap for this player. Previously PlayerReadyListener. */
    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        PlayerRef playerRef = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null) this.sessions.showIfEnabledByDefault(playerRef, config.get());
    }

    @Override
    protected void shutdown() {
        if (this.imageCleanup != null) this.imageCleanup.cancel(false);
        DapperMapServerLoad.stop();
        this.sessions.stopAll();
        BetterMapIntegration.clearImages();
        // Stops the thread that runs blocking world-map providers (spawn) off the world thread.
        NativeWorldMapIntegration.shutdown();
        DynamicMinimapSession.shutdownRendering();
    }

    public static Config<DapperMapConfig> getConfig() {
        return config;
    }

    /** The current server config, or null before the plugin is constructed. */
    public static DapperMapConfig config() {
        Config<DapperMapConfig> current = config;
        return current == null ? null : current.get();
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
    /**
     * Sets the server-wide terrain colour reduction (0 = off, else 32..256): stores it in the
     * config, applies it to new terrain renders and saves the config file. Callers redraw
     * live minimaps if the change should show straight away.
     */
    public static void applyTerrainColours(int colours) {
        DapperMapConfig current = config();
        if (current == null) return;
        current.setTerrainColours(colours);
        DynamicMinimapRenderer.setTerrainColours(current.getTerrainColours());
        saveConfig();
    }

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
        CompletableFuture<Void> write;
        try {
            write = current.save();
        } catch (RuntimeException failure) {
            write = CompletableFuture.failedFuture(failure);
        }
        if (write == null) write = CompletableFuture.completedFuture(null);
        // A write stuck for 30 s (e.g. writer pool stopped) releases the flag so later saves aren't
        // silently dropped; the copy leaves Hytale's own future untouched.
        write.copy().orTimeout(30, TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
            if (failure != null) DapperMapUtil.logFailure("config save", failure);
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

package dev.scyphoz.dappermap.events;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.scyphoz.dappermap.DapperMap;
import dev.scyphoz.dappermap.config.MinimapConfig;
import dev.scyphoz.dappermap.service.MinimapSessionManager;

public class PlayerReadyListener {

    private final MinimapSessionManager sessions;

    public PlayerReadyListener(MinimapSessionManager sessions) {
        this.sessions = sessions;
    }

    public void onPlayerReady(PlayerReadyEvent event) {
        Player player = event.getPlayer();

        Ref<EntityStore> ref = event.getPlayerRef();
        Store<EntityStore> store = ref.getStore();

        PlayerRef playerRef = store.getComponent(
                ref,
                PlayerRef.getComponentType()
        );

        if (playerRef == null) {
            return;
        }

        this.sessions.showIfEnabledByDefault(
                player,
                playerRef,
                (MinimapConfig) DapperMap.getConfig().get()
        );
    }
}
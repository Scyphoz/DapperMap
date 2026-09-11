package dev.scyphoz.dappermap.service;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class MinimapRefreshSystem extends EntityTickingSystem<EntityStore> {
    private static final ComponentType<EntityStore, Player> PLAYER_COMPONENT_TYPE = Player.getComponentType();
    private static final ComponentType<EntityStore, PlayerRef> PLAYER_REF_COMPONENT_TYPE = PlayerRef.getComponentType();
    private final MinimapSessionManager sessions;

    public MinimapRefreshSystem(MinimapSessionManager sessions) {
        this.sessions = sessions;
    }

    public Query<EntityStore> getQuery() {
        return Query.and(new Query[]{PLAYER_COMPONENT_TYPE, PLAYER_REF_COMPONENT_TYPE});
    }

    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return RootDependency.lastSet();
    }

    @Nullable
    public SystemGroup<EntityStore> getGroup() {
        return EntityStore.SEND_PACKET_GROUP;
    }

    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Player player = (Player)archetypeChunk.getComponent(index, PLAYER_COMPONENT_TYPE);
        PlayerRef playerRef = (PlayerRef)archetypeChunk.getComponent(index, PLAYER_REF_COMPONENT_TYPE);
        if (player != null && playerRef != null) {
            this.sessions.refreshIfDue(player, playerRef);
        }

    }
}

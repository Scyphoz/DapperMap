package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import java.awt.Color;
import static dev.scyphoz.dappermap.dynamic.MinimapColours.Key.*;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/** Converts nearby NPC and player entities into coloured minimap markers. */
public final class DynamicEntityScanner {

    private DynamicEntityScanner() {
    }

    public static List<DynamicMinimapMarker> scan(Store<EntityStore> store, Ref<EntityStore> playerRef, Vector3d playerPosition, double radius, DynamicMinimapSetting settings) {
        ArrayList<DynamicMinimapMarker> markers = new ArrayList();
        double radiusSq = radius * radius;
        double playerX = playerPosition.x();
        double playerY = playerPosition.y();
        double playerZ = playerPosition.z();
        double verticalRange = settings.limitMobHeight ? (double)settings.mobVerticalRange : (double)0.0F;
        if (settings.showEntities) {
            try {
                store.forEachChunk(NPCEntity.getComponentType(), (chunk, commandBuffer) -> {
                    for(int i = 0; i < chunk.size(); ++i) {
                        try {
                            TransformComponent transform = (TransformComponent)chunk.getComponent(i, TransformComponent.getComponentType());
                            if (transform != null) {
                                Vector3d position = transform.getPosition();
                                double dx = position.x() - playerX;
                                double dz = position.z() - playerZ;
                                if (!(dx * dx + dz * dz > radiusSq) && (!(verticalRange > (double)0.0F) || !(Math.abs(position.y() - playerY) > verticalRange))) {
                                    NPCEntity npc = (NPCEntity)chunk.getComponent(i, NPCEntity.getComponentType());
                                    if (npc != null) {
                                        Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                                        Color color = colorFor(npcRef, npc, playerRef, commandBuffer, settings.colours);
                                        if (color != null) {
                                            markers.add(DynamicMinimapMarker.dot(position.x(), position.z(), color, 7));
                                        }
                                    }
                                }
                            }
                        } catch (Exception var24) {
                        }
                    }

                });
            } catch (Exception var19) {
            }
        }

        if (settings.showOtherPlayers) {
            try {
                store.forEachChunk(Player.getComponentType(), (chunk, commandBuffer) -> {
                    for(int i = 0; i < chunk.size(); ++i) {
                        try {
                            TransformComponent transform = (TransformComponent)chunk.getComponent(i, TransformComponent.getComponentType());
                            if (transform != null) {
                                Vector3d position = transform.getPosition();
                                double dx = position.x() - playerX;
                                double dz = position.z() - playerZ;
                                double distanceSq = dx * dx + dz * dz;
                                if (distanceSq >= 0.01 && distanceSq <= radiusSq) {
                                    markers.add(DynamicMinimapMarker.dot(position.x(), position.z(), settings.colours.awt(PLAYERS, 255), 11));
                                }
                            }
                        } catch (Exception var18) {
                        }
                    }

                });
            } catch (Exception var18) {
            }
        }

        return markers;
    }

    private static Color colorFor(Ref<EntityStore> npcRef, NPCEntity npc, Ref<EntityStore> playerRef, CommandBuffer<EntityStore> commandBuffer, MinimapColours colours) {
        try {
            if (playerRef != null && playerRef.isValid()) {
                WorldSupport worldSupport = WorldSupport.get(npcRef, commandBuffer);
                Attitude dynamicAttitude = null;
                if (worldSupport != null) {
                    try {
                        dynamicAttitude = worldSupport.getAttitude(npcRef, playerRef, commandBuffer);
                    } catch (Exception var8) {
                    }
                }

                if (dynamicAttitude != Attitude.FRIENDLY && dynamicAttitude != Attitude.REVERED) {
                    Ref<EntityStore> lockedTarget = getLockedTarget(npcRef, commandBuffer);
                    boolean targetingPlayer = sameEntity(lockedTarget, playerRef);
                    if (targetingPlayer && isBackingAway(npc)) {
                        return colours.awt(NEUTRAL, 255);
                    } else if (dynamicAttitude == Attitude.HOSTILE && targetingPlayer) {
                        return colours.awt(MinimapColours.Key.HOSTILE_ACTIVE, 255);
                    } else {
                        return dynamicAttitude == Attitude.HOSTILE ? colours.awt(HOSTILE, 255) : colours.awt(NEUTRAL, 255);
                    }
                } else {
                    return colours.awt(MinimapColours.Key.FRIENDLY, 255);
                }
            } else {
                return colours.awt(NEUTRAL, 255);
            }
        } catch (Exception var9) {
            return colours.awt(NEUTRAL, 255);
        }
    }

    private static Ref<EntityStore> getLockedTarget(Ref<EntityStore> npcRef, CommandBuffer<EntityStore> commandBuffer) {
        try {
            MarkedEntitySupport markedEntitySupport = MarkedEntitySupport.get(npcRef, commandBuffer);
            if (markedEntitySupport == null) {
                return null;
            } else {
                Ref<EntityStore> target = markedEntitySupport.getMarkedEntityRef("LockedTarget");
                return target != null && target.isValid() ? target : null;
            }
        } catch (Exception var4) {
            return null;
        }
    }

    private static boolean isBackingAway(NPCEntity npc) {
        try {
            Role role = npc.getRole();
            return role != null && role.isBackingAway();
        } catch (Exception var2) {
            return false;
        }
    }

    private static boolean sameEntity(Ref<EntityStore> a, Ref<EntityStore> b) {
        return a != null && b != null && a.isValid() && b.isValid() && a.equals(b);
    }
}

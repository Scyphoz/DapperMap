package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.component.ComponentAccessor;
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
        NearestCreatures creatures = settings.showEntities ? new NearestCreatures(settings.creatureMarkerLimit, playerX, playerZ) : null;
        double verticalRange = settings.limitMobHeight ? (double)settings.mobVerticalRange : (double)0.0F;
        if (settings.showEntities) {
            List<Ref<EntityStore>> nearby = nearby(store, playerPosition, radius, verticalRange, true);
            if (nearby != null) {
                for (Ref<EntityStore> npcRef : nearby) {
                    try {
                        if (!npcRef.isValid()) continue;
                        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
                        if (transform == null) continue;
                        Vector3d position = transform.getPosition();
                        double dx = position.x() - playerX, dz = position.z() - playerZ;
                        if (dx * dx + dz * dz > radiusSq || (verticalRange > 0 && Math.abs(position.y() - playerY) > verticalRange)
                                || !creatures.wouldAccept(position.x(), position.z())) continue;
                        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
                        if (npc == null) continue;
                        Color color = colorFor(npcRef, npc, playerRef, store, settings.colours);
                        if (color != null) creatures.add(DynamicMinimapMarker.dot(position.x(), position.z(), color, 7));
                    } catch (RuntimeException ignored) { }
                }
            } else {
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
                                        if (!creatures.wouldAccept(position.x(), position.z())) continue;
                                        NPCEntity npc = (NPCEntity)chunk.getComponent(i, NPCEntity.getComponentType());
                                        if (npc != null) {
                                            Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                                            Color color = colorFor(npcRef, npc, playerRef, commandBuffer, settings.colours);
                                            if (color != null) {
                                                creatures.add(DynamicMinimapMarker.dot(position.x(), position.z(), color, 7));
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
        }

        if (creatures != null) markers.addAll(creatures.sorted());

        if (settings.showOtherPlayers) {
            List<Ref<EntityStore>> nearby = nearby(store, playerPosition, radius, 0, false);
            if (nearby != null) {
                Color playerColor = settings.colours.awt(PLAYERS, 255);
                for (Ref<EntityStore> other : nearby) {
                    try {
                        if (!other.isValid()) continue;
                        TransformComponent transform = store.getComponent(other, TransformComponent.getComponentType());
                        if (transform == null || store.getComponent(other, Player.getComponentType()) == null) continue;
                        Vector3d position = transform.getPosition();
                        double dx = position.x() - playerX, dz = position.z() - playerZ;
                        double distance = dx * dx + dz * dz;
                        if (distance >= .01 && distance <= radiusSq)
                            markers.add(DynamicMinimapMarker.dot(position.x(), position.z(), playerColor, 11));
                    } catch (RuntimeException ignored) { }
                }
            } else {
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
        }

        return markers;
    }

    /** Native spatial indices are rebuilt by Hytale. Query a padded box first,
     * then apply the original exact radius/height checks to current transforms.
     * No vertical filter means all elevations, not a three-dimensional sphere. */
    private static List<Ref<EntityStore>> nearby(Store<EntityStore> store, Vector3d position,
                                                 double radius, double height, boolean npc) {
        try {
            com.hypixel.hytale.component.spatial.SpatialResource<Ref<EntityStore>, EntityStore> resource;
            if (npc) {
                var plugin = com.hypixel.hytale.server.npc.NPCPlugin.get();
                if (plugin == null || plugin.getNpcSpatialResource() == null) return null;
                resource = store.getResource(plugin.getNpcSpatialResource());
            } else {
                var module = com.hypixel.hytale.server.core.modules.entity.EntityModule.get();
                if (module == null || module.getPlayerSpatialResourceType() == null) return null;
                resource = store.getResource(module.getPlayerSpatialResourceType());
            }
            if (resource == null || resource.getSpatialStructure() == null) return null;
            return collectNearby(resource.getSpatialStructure(), position, radius, height);
        } catch (RuntimeException | LinkageError ignored) {
            return null; // Compatibility/startup fallback retains the original chunk scan.
        }
    }

    static <T> List<T> collectNearby(com.hypixel.hytale.component.spatial.SpatialStructure<T> structure,
                                     Vector3d position, double radius, double height) {
        double padded = radius + 32.0; // Normal movement between spatial-index refreshes.
        double minY = height > 0 ? position.y() - height - 32 : -Double.MAX_VALUE;
        double maxY = height > 0 ? position.y() + height + 32 : Double.MAX_VALUE;
        List<T> result = new ArrayList<>();
        structure.collectBox(new Vector3d(position.x() - padded, minY, position.z() - padded),
                new Vector3d(position.x() + padded, maxY, position.z() + padded), result);
        return result;
    }

    /** Bounded nearest-first creature selection; players are appended separately. */
    static final class NearestCreatures {
        private final int limit;
        private final double x, z;
        private final java.util.Comparator<DynamicMinimapMarker> nearest;
        private final java.util.PriorityQueue<DynamicMinimapMarker> candidates;
        NearestCreatures(int limit, double x, double z) {
            this.limit = limit == 32 || limit == 64 ? limit : 16;
            this.x = x; this.z = z;
            this.nearest = java.util.Comparator.comparingDouble(this::distance);
            this.candidates = new java.util.PriorityQueue<>(this.limit, nearest.reversed());
        }
        private double distance(DynamicMinimapMarker marker) {
            double dx = marker.x - x, dz = marker.z - z;
            return dx * dx + dz * dz;
        }
        boolean wouldAccept(double markerX, double markerZ) {
            if (candidates.size() < limit) return true;
            double dx = markerX - x, dz = markerZ - z;
            return dx * dx + dz * dz < distance(candidates.peek());
        }
        void add(DynamicMinimapMarker marker) {
            if (candidates.size() < limit) candidates.add(marker);
            else if (nearest.compare(marker, candidates.peek()) < 0) {
                candidates.poll(); candidates.add(marker);
            }
        }
        List<DynamicMinimapMarker> sorted() {
            var result = new ArrayList<>(candidates);
            result.sort(nearest);
            return result;
        }
    }

    private static Color colorFor(Ref<EntityStore> npcRef, NPCEntity npc, Ref<EntityStore> playerRef, ComponentAccessor<EntityStore> commandBuffer, MinimapColours colours) {
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

    private static Ref<EntityStore> getLockedTarget(Ref<EntityStore> npcRef, ComponentAccessor<EntityStore> commandBuffer) {
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

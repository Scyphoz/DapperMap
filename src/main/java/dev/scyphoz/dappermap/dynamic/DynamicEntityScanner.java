package dev.scyphoz.dappermap.dynamic;

import com.google.gson.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.corecomponents.combat.ActionAttack;
import com.hypixel.hytale.server.npc.corecomponents.combat.BodyMotionCharge;
import com.hypixel.hytale.server.npc.corecomponents.movement.BodyMotionFind;
import com.hypixel.hytale.server.npc.corecomponents.movement.BodyMotionMaintainDistance;
import com.hypixel.hytale.server.npc.corecomponents.movement.BodyMotionMoveAway;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.instructions.Action;
import com.hypixel.hytale.server.npc.instructions.ActionList;
import com.hypixel.hytale.server.npc.instructions.BodyMotion;
import com.hypixel.hytale.server.npc.instructions.Instruction;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.CombatSupport;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.hypixel.hytale.server.npc.util.IAnnotatedComponent;
import com.hypixel.hytale.server.npc.util.IAnnotatedComponentCollection;
import java.awt.Color;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.joml.Vector3d;
import static dev.scyphoz.dappermap.dynamic.MinimapColours.Key.*;


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
        NearestNpcs creatures = settings.showEntities ? new NearestNpcs(settings.creatureMarkerLimit, playerX, playerZ) : null;
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
                        creatures.add(npcRef, position.x(), position.z());
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
                                            creatures.add(npcRef, position.x(), position.z());
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

        // Only classify the final displayed set. Intermediate candidates may be
        // replaced by closer creatures, and need no attitude or intent reads.
        if (creatures != null) {
            for (NearestNpcs.Candidate candidate : creatures.sorted()) {
                try {
                    if (!candidate.ref().isValid()) continue;
                    NPCEntity npc = store.getComponent(candidate.ref(), NPCEntity.getComponentType());
                    if (npc == null) continue;
                    Color color = colorFor(candidate.ref(), npc, playerRef, store, settings.colours);
                    markers.add(DynamicMinimapMarker.dot(candidate.x(), candidate.z(), color, 7));
                } catch (RuntimeException ignored) { }
            }
        }

        PlayerVisibility visibility = settings.showOtherPlayers ? PlayerVisibility.forViewer(store, playerRef) : null;
        if (visibility != null) {
            Color playerColor = settings.colours.awt(PLAYERS, 255);
            List<Ref<EntityStore>> nearby = nearby(store, playerPosition, radius, 0, false);
            if (nearby != null) {
                for (Ref<EntityStore> other : nearby) {
                    try {
                        if (!other.isValid()) continue;
                        TransformComponent transform = store.getComponent(other, TransformComponent.getComponentType());
                        if (transform == null || store.getComponent(other, Player.getComponentType()) == null) continue;
                        Vector3d position = transform.getPosition();
                        double dx = position.x() - playerX, dz = position.z() - playerZ;
                        double distance = dx * dx + dz * dz;
                        // Cheap distance test first; visibility lookups only for candidates.
                        if (distance >= .01 && distance <= radiusSq
                                && visibility.visible(store.getComponent(other, PlayerRef.getComponentType()),
                                store.getComponent(other, Spectating.getComponentType()) != null))
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
                                    if (distanceSq >= 0.01 && distanceSq <= radiusSq
                                            && visibility.visible(chunk.getComponent(i, PlayerRef.getComponentType()),
                                            chunk.getComponent(i, Spectating.getComponentType()) != null)) {
                                        markers.add(DynamicMinimapMarker.dot(position.x(), position.z(), playerColor, 11));
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

    /**
     * Mirrors Hytale's own world-map player rules (OtherPlayersMarkerProvider): players are
     * omitted when the world hides them, when the viewer has them hidden, or when they are
     * spectating and the viewer is not. Built once per scan so per-player checks stay cheap.
     */
    static final class PlayerVisibility {
        private final java.util.UUID viewer;
        private final com.hypixel.hytale.server.core.entity.entities.player.HiddenPlayersManager hidden;
        private final boolean viewerSpectating;

        private PlayerVisibility(java.util.UUID viewer,
                                 com.hypixel.hytale.server.core.entity.entities.player.HiddenPlayersManager hidden,
                                 boolean viewerSpectating) {
            this.viewer = viewer; this.hidden = hidden; this.viewerSpectating = viewerSpectating;
        }

        /** Null means no other players may be shown to this viewer at all. */
        static PlayerVisibility forViewer(Store<EntityStore> store, Ref<EntityStore> viewerRef) {
            try {
                var world = store.getExternalData().getWorld();
                if (world != null && !world.getGameplayConfig().getWorldMapConfig().isDisplayPlayers()) return null;
                PlayerRef viewer = store.getComponent(viewerRef, PlayerRef.getComponentType());
                if (viewer == null) return null; // Cannot evaluate privacy: fail closed.
                return new PlayerVisibility(viewer.getUuid(), viewer.getHiddenPlayersManager(),
                        store.getComponent(viewerRef, Spectating.getComponentType()) != null);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        boolean visible(PlayerRef other, boolean otherSpectating) {
            if (other == null || other.getUuid() == null || other.getUuid().equals(this.viewer)) return false;
            if (otherSpectating && !this.viewerSpectating) return false;
            return this.hidden == null || !this.hidden.isPlayerHidden(other.getUuid());
        }
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

    /** Retains positions/references only; expensive AI inspection follows selection. */
    static final class NearestNpcs {
        record Candidate(Ref<EntityStore> ref, double x, double z, double distanceSquared) { }
        private final int limit;
        private final double x, z;
        private static final Comparator<Candidate> NEAREST = Comparator.comparingDouble(Candidate::distanceSquared);
        private final PriorityQueue<Candidate> candidates;
        NearestNpcs(int limit, double x, double z) {
            this.limit = limit == 32 || limit == 64 ? limit : 16;
            this.x = x;
            this.z = z;
            candidates = new PriorityQueue<>(this.limit, NEAREST.reversed());
        }
        private double distance(double markerX, double markerZ) {
            double dx = markerX - x, dz = markerZ - z;
            return dx * dx + dz * dz;
        }
        boolean wouldAccept(double markerX, double markerZ) {
            return candidates.size() < limit || distance(markerX, markerZ) < candidates.peek().distanceSquared();
        }
        void add(Ref<EntityStore> ref, double markerX, double markerZ) {
            double distance = distance(markerX, markerZ);
            if (candidates.size() >= limit) {
                if (distance >= candidates.peek().distanceSquared()) return;
                candidates.poll();
            }
            candidates.add(new Candidate(ref, markerX, markerZ, distance));
        }
        List<Candidate> sorted() {
            var result = new ArrayList<>(candidates);
            result.sort(NEAREST);
            return result;
        }
    }

    private static Color colorFor(Ref<EntityStore> npcRef, NPCEntity npc, Ref<EntityStore> playerRef, ComponentAccessor<EntityStore> commandBuffer, MinimapColours colours) {
        return colours.awt(colourFor(npcRef, npc, playerRef, commandBuffer), 255);
    }

    private static MinimapColours.Key colourFor(Ref<EntityStore> npcRef, NPCEntity npc,
                                                Ref<EntityStore> playerRef, ComponentAccessor<EntityStore> commandBuffer) {
        try {
            if (playerRef == null || !playerRef.isValid()) return NEUTRAL;
            WorldSupport worldSupport = WorldSupport.get(npcRef, commandBuffer);
            Attitude dynamicAttitude = null;
            if (worldSupport != null) {
                try {
                    // Native lookup requires lazy cache setup, including passive roles
                    // whose own AI never requests an attitude sensor.
                    worldSupport.requireAttitudeCache();
                    dynamicAttitude = worldSupport.getAttitude(npcRef, playerRef, commandBuffer);
                } catch (Exception ignored) { }
            }
            Attitude defaultAttitude = worldSupport == null ? null : worldSupport.getDefaultPlayerAttitude();
            Attitude override = worldSupport == null ? null : worldSupport.getOverriddenAttitude(playerRef);
            Ref<EntityStore> lockedTarget = getLockedTarget(npcRef, commandBuffer);
            boolean targetingPlayer = sameEntity(lockedTarget, playerRef);
            String state = null;
            boolean executingAttack = false;
            try {
                StateSupport support = StateSupport.get(npcRef, commandBuffer);
                if (support != null) state = support.getStateName();
            } catch (RuntimeException ignored) { }
            try {
                CombatSupport support = CombatSupport.get(npcRef, commandBuffer);
                executingAttack = support != null && support.isExecutingAttack();
            } catch (RuntimeException ignored) { }
            var profile = NpcThreatClassifier.profileFor(npc.getRoleIndex());
            var signals = NpcBehaviourSignals.capture(npc.getRole(), playerRef, lockedTarget, npcRef, commandBuffer);
            return NpcThreatClassifier.classify(profile, dynamicAttitude, defaultAttitude,
                    override, targetingPlayer, state, executingAttack, signals);
        } catch (Exception ignored) {
            return NEUTRAL;
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

    private static boolean sameEntity(Ref<EntityStore> a, Ref<EntityStore> b) {
        return a != null && b != null && a.isValid() && b.isValid() && a.equals(b);
    }


    /** Runtime evidence from the built AI, including third-party JSON roles.
     * Never invokes sensors/actions or derives fleeing from a velocity or back-off flag. */
    static final class NpcBehaviourSignals {
        enum Capability { NO_OFFENSE, ATTACK_CAPABLE, UNKNOWN }
        enum Intent { TOWARDS_PLAYER, CHARGE_PLAYER, AWAY_FROM_PLAYER, OTHER }
        record Signals(Capability capability, Intent intent, boolean activeAttackTargetPlayer, String motion) {
            static final Signals UNKNOWN = new Signals(Capability.UNKNOWN, Intent.OTHER, false, "unavailable");
        }
        private record Tree(WeakReference<Instruction> root, Capability capability, List<WeakReference<ActionAttack>> attacks) { }
        private static final Map<Role, Tree> TREES = Collections.synchronizedMap(new WeakHashMap<>());
        // Instruction's public annotated enumeration can hide child instructions when a
        // node has both motions/actions and children. Read these two containers explicitly.
        // This version-bound bridge is read-only and fails to UNKNOWN if inaccessible.
        private static final Field CHILDREN = instructionField("instructionList");
        private static final Field ACTIONS = instructionField("actions");

        private NpcBehaviourSignals() { }

        static Signals capture(Role role, Ref<EntityStore> player, Ref<EntityStore> lockedTarget,
                               Ref<EntityStore> npcRef, ComponentAccessor<EntityStore> accessor) {
            if (role == null) return Signals.UNKNOWN;
            final Tree tree;
            try { tree = tree(role); }
            catch (RuntimeException | LinkageError ignored) { return Signals.UNKNOWN; }
            boolean activeAttackPlayer = false;
            try {
                for (WeakReference<ActionAttack> reference : tree.attacks) {
                    ActionAttack action = reference.get();
                    // This is only positive evidence while an action remains activated.
                    // Blocking lists deactivate completed actions in the same cycle; false
                    // does not mean no attack. isTriggered() is remembered, not live intent.
                    if (action != null && action.isActivated() && same(targetFromParents(action), player)) {
                        activeAttackPlayer = true;
                        break;
                    }
                }
                BodyMotion motion = role.getLastBodySteeringMotion();
                if (motion == null) return new Signals(tree.capability, Intent.OTHER, activeAttackPlayer, "none");
                Ref<EntityStore> target = motion.getDesiredTargetEntity();
                if (target == null) target = targetFromParents(motion);
                Intent intent = Intent.OTHER;
                if (motion instanceof BodyMotionFind && same(target, player)) intent = Intent.TOWARDS_PLAYER;
                if (motion instanceof BodyMotionCharge && same(target, player)) intent = Intent.CHARGE_PLAYER;
                if (motion instanceof BodyMotionMaintainDistance && same(target, player)) {
                    TransformComponent npcTransform = accessor.getComponent(npcRef, TransformComponent.getComponentType());
                    TransformComponent playerTransform = accessor.getComponent(player, TransformComponent.getComponentType());
                    if (npcTransform != null && playerTransform != null
                            && approaching(role.getBodySteering(), npcTransform.getPosition(), playerTransform.getPosition()))
                        intent = Intent.TOWARDS_PLAYER;
                }
                if (motion instanceof BodyMotionMoveAway
                        && same(target == null ? lockedTarget : target, player)) intent = Intent.AWAY_FROM_PLAYER;
                Capability capability = motion instanceof BodyMotionCharge ? Capability.ATTACK_CAPABLE : tree.capability;
                return new Signals(capability, intent, activeAttackPlayer, motion.getClass().getSimpleName());
            } catch (RuntimeException | LinkageError ignored) {
                return new Signals(tree.capability, Intent.OTHER, activeAttackPlayer, "unavailable");
            }
        }

        static boolean approaching(Steering steering, org.joml.Vector3dc npc, org.joml.Vector3dc player) {
            if (steering == null || !steering.hasTranslation()) return false;
            double dx = player.x() - npc.x(), dz = player.z() - npc.z();
            double sx = steering.getX(), sz = steering.getZ();
            double product = (dx * dx + dz * dz) * (sx * sx + sz * sz);
            return product > 1e-12 && dx * sx + dz * sz > 0.05 * Math.sqrt(product);
        }

        private static Tree tree(Role role) {
            Instruction root = role.getRootInstruction();
            Tree cached = TREES.get(role);
            if (cached != null && cached.root.get() == root) return cached;
            List<WeakReference<ActionAttack>> attacks = new ArrayList<>();
            Capability capability = inspect(root, attacks);
            Tree tree = new Tree(new WeakReference<>(root), capability, List.copyOf(attacks));
            TREES.put(role, tree);
            return tree;
        }

        static Capability inspect(IAnnotatedComponent root, List<WeakReference<ActionAttack>> attacks) {
            if (root == null) return Capability.UNKNOWN;
            Set<IAnnotatedComponent> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<IAnnotatedComponent> remaining = new ArrayDeque<>();
            remaining.add(root);
            boolean offensive = false, incomplete = false;
            while (!remaining.isEmpty() && seen.size() < 8192) {
                IAnnotatedComponent node = remaining.removeFirst();
                if (!seen.add(node)) continue;
                Capability kind = componentCapability(node.getClass());
                offensive |= kind == Capability.ATTACK_CAPABLE;
                incomplete |= kind == Capability.UNKNOWN;
                if (node instanceof ActionAttack attack) attacks.add(new WeakReference<>(attack));
                try {
                    if (node instanceof Instruction instruction) {
                        if (CHILDREN == null || ACTIONS == null) { incomplete = true; continue; }
                        for (Instruction child : (Instruction[]) CHILDREN.get(instruction)) enqueue(remaining, child);
                        ActionList actions = (ActionList) ACTIONS.get(instruction);
                        for (int i = 0; i < actions.actionCount(); i++) enqueue(remaining, actions.getComponent(i));
                        enqueue(remaining, instruction.getSensor());
                        enqueue(remaining, instruction.getBodyMotion());
                        enqueue(remaining, instruction.getHeadMotion());
                    } else if (node instanceof IAnnotatedComponentCollection collection) {
                        int count = collection.componentCount();
                        if (count < 0 || count > 8192) { incomplete = true; continue; }
                        for (int i = 0; i < count; i++) enqueue(remaining, collection.getComponent(i));
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    incomplete = true;
                }
            }
            incomplete |= !remaining.isEmpty();
            return offensive ? Capability.ATTACK_CAPABLE : incomplete ? Capability.UNKNOWN : Capability.NO_OFFENSE;
        }

        static Capability componentCapability(Class<?> type) {
            if (ActionAttack.class.isAssignableFrom(type) || BodyMotionCharge.class.isAssignableFrom(type))
                return Capability.ATTACK_CAPABLE;
            String name = type.getName();
            if (!name.startsWith("com.hypixel.hytale.server.npc.")) return Capability.UNKNOWN;
            // These can damage, change roles, or create combat through another interaction.
            // Their absence cannot be treated as a proof that an unfamiliar system is safe.
            if (Action.class.isAssignableFrom(type)) {
                String simple = type.getSimpleName();
                if (simple.equals("ActionEntityHitInteraction") || simple.equals("ActionBlockHitInteraction"))
                    return Capability.ATTACK_CAPABLE;
                if (simple.equals("ActionSpawnInteraction") || simple.equals("ActionApplyEntityEffect")
                        || simple.equals("ActionSetStat") || simple.equals("ActionSetHealthRegen")
                        || simple.equals("ActionRole") || simple.equals("ActionSpawn")
                        || simple.equals("ActionTriggerSpawners")) return Capability.UNKNOWN;
                if (name.contains(".corecomponents.combat.")) return Capability.UNKNOWN;
            }
            return Capability.NO_OFFENSE;
        }

        private static Ref<EntityStore> targetFromParents(IAnnotatedComponent node) {
            // The fixed bound also terminates malformed cyclic parent chains.
            // No per-query visited-set allocation is needed for this read-only walk.
            for (int i = 0; node != null && i < 64; i++, node = node.getParent()) {
                if (node instanceof Instruction instruction) {
                    var sensor = instruction.getSensor();
                    var info = sensor == null ? null : sensor.getSensorInfo();
                    var position = info == null ? null : info.getPositionProvider();
                    Ref<EntityStore> target = position == null ? null : position.getTarget();
                    if (target != null && target.isValid()) return target;
                }
            }
            return null;
        }

        private static Field instructionField(String name) {
            try {
                Field field = Instruction.class.getDeclaredField(name);
                return field.trySetAccessible() ? field : null;
            } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
        }
        private static void enqueue(ArrayDeque<IAnnotatedComponent> queue, IAnnotatedComponent node) {
            if (queue.size() >= 8192) throw new IllegalStateException("NPC inspection budget exceeded");
            if (node != null) queue.addLast(node);
        }
        private static boolean same(Ref<EntityStore> a, Ref<EntityStore> b) {
            return a != null && b != null && a.isValid() && b.isValid() && a.equals(b);
        }
    }

    /** Cached, read-only inspection of loaded role assets. No AI or attitude is modified. */
    static final class NpcThreatClassifier {
        private record Cached(BuilderInfo info, long generation, NpcThreatProfile profile) { }
        private static final ConcurrentHashMap<Integer, Cached> CACHE = new ConcurrentHashMap<>();
        private static final AtomicLong GENERATION = new AtomicLong();
        private static volatile BuilderManager observedManager;

        private NpcThreatClassifier() { }

        static NpcThreatProfile profileFor(int roleIndex) {
            try {
                NPCPlugin plugin = NPCPlugin.get();
                if (plugin == null) return NpcThreatProfile.UNKNOWN;
                BuilderManager manager = plugin.getBuilderManager();
                if (manager != observedManager) observe(manager);
                BuilderInfo info = plugin.getRoleBuilderInfo(roleIndex);
                if (info == null) return NpcThreatProfile.UNKNOWN;
                long generation = GENERATION.get();
                Cached cached = CACHE.get(roleIndex);
                if (cached != null && cached.info == info && cached.generation == generation) return cached.profile;
                // Components can be referenced repeatedly by one resolved role. Read
                // each asset once per resolution, including missing/unreadable assets.
                var assets = new java.util.HashMap<String, JsonObject>();
                NpcThreatProfile profile = NpcThreatProfile.resolve(info.getKeyName(), name -> {
                    if (!assets.containsKey(name)) assets.put(name, read(manager, name));
                    return assets.get(name);
                });
                CACHE.put(roleIndex, new Cached(info, generation, profile));
                return profile;
            } catch (RuntimeException | LinkageError failure) {
                return NpcThreatProfile.UNKNOWN;
            }
        }

        private static synchronized void observe(BuilderManager manager) {
            if (manager == observedManager) return;
            manager.addBuilderReloadListener(ignored -> {
                GENERATION.incrementAndGet();
                CACHE.clear();
            });
            GENERATION.incrementAndGet();
            CACHE.clear();
            // Publish only after listener registration and invalidation are complete.
            observedManager = manager;
        }

        private static JsonObject read(BuilderManager manager, String name) {
            BuilderInfo info = manager.tryGetBuilderInfo(manager.getIndex(name));
            if (info == null || info.getPath() == null) return null;
            try (Reader reader = Files.newBufferedReader(info.getPath(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            } catch (java.io.IOException | RuntimeException ignored) {
                return null;
            }
        }

        static MinimapColours.Key classify(NpcThreatProfile profile, Attitude attitude,
                                           Attitude defaultAttitude, Attitude override, boolean targetingPlayer,
                                           String state, boolean executingAttack) {
            return classify(profile, attitude, defaultAttitude, override, targetingPlayer, state,
                    executingAttack, NpcBehaviourSignals.Signals.UNKNOWN);
        }

        static MinimapColours.Key classify(NpcThreatProfile profile, Attitude attitude,
                                           Attitude defaultAttitude, Attitude override, boolean targetingPlayer,
                                           String state, boolean executingAttack, NpcBehaviourSignals.Signals signals) {
            var capability = signals.capability();
            boolean towards = signals.intent() == NpcBehaviourSignals.Intent.TOWARDS_PLAYER
                    || signals.intent() == NpcBehaviourSignals.Intent.CHARGE_PLAYER;
            boolean potentiallyHostile = !friendly(attitude) && !friendly(override)
                    && (attitude == Attitude.HOSTILE || defaultAttitude == Attitude.HOSTILE
                    || override == Attitude.HOSTILE || profile.kind() == NpcThreatProfile.Kind.AGGRESSIVE);
            // An activated attack action and its sensor target override assumptions about
            // species, templates, state names and the default target slot.
            if (signals.activeAttackTargetPlayer()) return HOSTILE_ACTIVE;
            boolean defensive = profile.kind() == NpcThreatProfile.Kind.DEFENSIVE || profile.kind() == NpcThreatProfile.Kind.EVASIVE;
            if (towards && capability == NpcBehaviourSignals.Capability.ATTACK_CAPABLE && potentiallyHostile
                    && (!defensive || override == Attitude.HOSTILE || profile.offensiveState(state)
                    || signals.intent() == NpcBehaviourSignals.Intent.CHARGE_PLAYER))
                return HOSTILE_ACTIVE;

            // Active engagement includes chasing and combat back-off, not just a strike frame.
            // Unknown roles require both hostility and actual attack execution.
            boolean executionFallback = (!profile.combatStates() || state == null)
                    && executingAttack && attitude == Attitude.HOSTILE;
            if (targetingPlayer && !profile.disengagingState(state)
                    && (profile.offensiveState(state) && capability != NpcBehaviourSignals.Capability.NO_OFFENSE
                    || executionFallback))
                return HOSTILE_ACTIVE;
            if (friendly(attitude) || friendly(override)) return FRIENDLY;
            if (capability == NpcBehaviourSignals.Capability.NO_OFFENSE) return NEUTRAL;
            if (profile.kind() == NpcThreatProfile.Kind.FLEE_ONLY
                    && capability != NpcBehaviourSignals.Capability.ATTACK_CAPABLE) return NEUTRAL;
            // A known dangerous predator does not become safe just because its attention
            // or temporary attitude is Neutral before detection.
            if (profile.kind() == NpcThreatProfile.Kind.AGGRESSIVE) return HOSTILE;
            if (signals.intent() == NpcBehaviourSignals.Intent.AWAY_FROM_PLAYER) return NEUTRAL;
            if (override == Attitude.HOSTILE) return HOSTILE;
            if (profile.kind() == NpcThreatProfile.Kind.DEFENSIVE || profile.kind() == NpcThreatProfile.Kind.EVASIVE) return NEUTRAL;
            if ((profile.kind() == NpcThreatProfile.Kind.STANDARD
                    || capability == NpcBehaviourSignals.Capability.ATTACK_CAPABLE) && defaultAttitude == Attitude.HOSTILE)
                return HOSTILE;
            return attitude == Attitude.HOSTILE ? HOSTILE : NEUTRAL;
        }

        private static boolean friendly(Attitude attitude) {
            return attitude == Attitude.FRIENDLY || attitude == Attitude.REVERED;
        }
    }

    /** Conservative native-template adapters plus structural state/action inspection.
     * Reads current asset definitions, never species names or predator group membership.
     * This is deliberately not a general Hytale expression interpreter. */
    static record NpcThreatProfile(Kind kind, boolean combatStates, Set<String> offensiveRoots) {
        NpcThreatProfile(Kind kind, boolean combatStates) {
            this(kind, combatStates, combatStates ? Set.of("Combat", "Flee.Attack") : Set.of());
        }
        enum Kind { UNKNOWN, FLEE_ONLY, EVASIVE, AGGRESSIVE, DEFENSIVE, STANDARD }
        static final NpcThreatProfile UNKNOWN = new NpcThreatProfile(Kind.UNKNOWN, false);

        static NpcThreatProfile resolve(String roleName, Function<String, JsonObject> assets) {
            List<JsonObject> variants = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            String name = roleName;
            JsonObject root = null;
            while (name != null && visited.add(name) && visited.size() <= 64) {
                JsonObject json = assets.apply(name);
                if (json == null) return UNKNOWN;
                if (!"Variant".equals(string(json.get("Type")))) {
                    root = json;
                    break;
                }
                variants.add(json);
                name = string(json.get("Reference"));
            }
            if (root == null) return UNKNOWN;
            // Replacing instructions invalidates our knowledge of the template's behaviour.
            for (JsonObject variant : variants) {
                JsonObject modify = object(variant.get("Modify"));
                if (modify != null && (modify.has("Instructions") || modify.has("StateTransitions")
                        || modify.has("StartState"))) return UNKNOWN;
            }
            Map<String, JsonElement> parameters = new HashMap<>();
            collectParameters(root, parameters);
            for (int i = variants.size() - 1; i >= 0; i--) collectParameters(variants.get(i), parameters);
            Map<String, JsonElement> values = new HashMap<>();
            root.entrySet().forEach(e -> values.put(e.getKey(), e.getValue()));
            for (int i = variants.size() - 1; i >= 0; i--) {
                JsonObject modify = object(variants.get(i).get("Modify"));
                if (modify != null) modify.entrySet().forEach(e -> values.put(e.getKey(), e.getValue()));
            }
            return switch (name) {
                case "Template_Beasts_Passive_Critter", "Template_Birds_Passive",
                     "Template_Swimming_Passive", "Template_Edible_Critter" ->
                        new NpcThreatProfile(Kind.FLEE_ONLY, false);
                case "Template_Predator" -> {
                    JsonElement flee = evaluate(values.get("FleeIfNotThreatened"), parameters, new HashSet<>());
                    if (flee == null || !flee.isJsonPrimitive() || !flee.getAsJsonPrimitive().isBoolean())
                        yield UNKNOWN;
                    yield new NpcThreatProfile(flee.getAsBoolean() ? Kind.DEFENSIVE : Kind.AGGRESSIVE, true);
                }
                case "Template_Intelligent", "Template_Flying_Aggressive", "Template_Swimming_Aggressive",
                     "Template_Animal_Neutral", "Template_Livestock" ->
                        new NpcThreatProfile(Kind.STANDARD, true);
                default -> inferStructure(values, parameters, assets);
            };
        }

        boolean offensiveState(String state) {
            if (!combatStates || state == null) return false;
            // Combat.Maybe decides whether to accept a flock request; it is not engagement yet.
            return !state.equals("Combat.Maybe") && offensiveRoots.stream()
                    .anyMatch(root -> state.equals(root) || state.startsWith(root + "."));
        }

        /** Recognise the actual state/action contract in renamed or independently built
         * templates. A parameter name alone never establishes predator disposition. */
        private static NpcThreatProfile inferStructure(Map<String, JsonElement> values, Map<String, JsonElement> parameters, Function<String, JsonObject> assets) {
            JsonElement instructions = values.get("Instructions");
            Set<String> offensive = new HashSet<>();
            collectOffensiveStates(instructions, "", offensive, 0);
            if (offensive.isEmpty()) return NpcAssetEvasion.avoidsPlayer(instructions, parameters, values, assets)
                    ? new NpcThreatProfile(Kind.EVASIVE, false) : UNKNOWN;
            Kind kind = Kind.STANDARD;
            // This explicit decision governs whether a detected player is fled from or
            // passed to combat. It excludes owner-follow/taming trees with different rules.
            JsonElement disposition = evaluate(values.get("DefaultPlayerAttitude"), parameters, new HashSet<>());
            String dispositionName = string(disposition);
            if (!"Friendly".equals(dispositionName) && !"Revered".equals(dispositionName)
                    && offensive.contains("Combat") && playerFleeDecision(instructions, 0)
                    && hasChaseDecision(instructions, 0)) {
                JsonElement raw = values.get("FleeIfNotThreatened");
                if (raw == null) raw = parameters.get("FleeIfNotThreatened");
                JsonElement flee = evaluate(raw, parameters, new HashSet<>());
                if (flee != null && flee.isJsonPrimitive() && flee.getAsJsonPrimitive().isBoolean())
                    kind = flee.getAsBoolean() ? Kind.DEFENSIVE : Kind.AGGRESSIVE;
            }
            return new NpcThreatProfile(kind, true, Set.copyOf(offensive));
        }

        private static void collectOffensiveStates(JsonElement element, String enclosing, Set<String> result, int depth) {
            if (element == null || depth > 64) return;
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) collectOffensiveStates(child, enclosing, result, depth + 1);
            } else if (element.isJsonObject()) {
                JsonObject node = element.getAsJsonObject();
                if (node.has("Enabled") && node.get("Enabled").isJsonPrimitive()
                        && node.get("Enabled").getAsJsonPrimitive().isBoolean() && !node.get("Enabled").getAsBoolean()) return;
                JsonObject sensor = object(node.get("Sensor"));
                String state = sensor != null && "State".equals(string(sensor.get("Type"))) ? string(sensor.get("State")) : null;
                String current = state == null ? enclosing : state.startsWith(".") ? enclosing + state : state;
                if (state != null && !current.isEmpty()
                        && ((!Set.of("Idle", "Alerted", "Flee", "Panic", "Sleep", "ReturnHome", "Search").contains(current)
                        && hasType(node.get("Instructions"), "Attack", depth + 1))
                        || hasType(node.get("Actions"), "Attack", depth + 1)))
                    result.add(current);
                collectOffensiveStates(node.get("Instructions"), current, result, depth + 1);
            }
        }

        private static boolean playerFleeDecision(JsonElement element, int depth) {
            if (element == null || depth > 64) return false;
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) if (playerFleeDecision(child, depth + 1)) return true;
            } else if (element.isJsonObject()) {
                JsonObject node = element.getAsJsonObject();
                if (disabled(node)) return false;
                JsonObject enabled = object(node.get("Enabled"));
                if (enabled != null && "FleeIfNotThreatened".equals(string(enabled.get("Compute")))
                        && hasPlayerGroup(node.get("Sensor"), depth + 1) && hasStateAction(node.get("Actions"), "Flee", depth + 1)) return true;
                for (var entry : node.entrySet()) if (playerFleeDecision(entry.getValue(), depth + 1)) return true;
            }
            return false;
        }

        private static boolean hasChaseDecision(JsonElement element, int depth) {
            if (element == null || depth > 64) return false;
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) if (hasChaseDecision(child, depth + 1)) return true;
            } else if (element.isJsonObject()) {
                JsonObject node = element.getAsJsonObject();
                if (disabled(node)) return false;
                JsonObject enabled = object(node.get("Enabled"));
                if (enabled != null && "!FleeIfNotThreatened".equals(string(enabled.get("Compute")))
                        && hasType(node.get("Sensor"), "Target", depth + 1)
                        && hasStateAction(node.get("Actions"), "Combat.Message", depth + 1)) return true;
                for (var entry : node.entrySet()) if (hasChaseDecision(entry.getValue(), depth + 1)) return true;
            }
            return false;
        }

        private static boolean hasPlayerGroup(JsonElement element, int depth) {
            if (element == null || depth > 64) return false;
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) if (hasPlayerGroup(child, depth + 1)) return true;
            } else if (element.isJsonObject()) {
                JsonObject node = element.getAsJsonObject();
                if ("NPCGroup".equals(string(node.get("Type"))) && node.has("IncludeGroups") && node.get("IncludeGroups").isJsonArray())
                    for (JsonElement group : node.getAsJsonArray("IncludeGroups")) if ("Player".equals(string(group))) return true;
                for (var entry : node.entrySet()) if (hasPlayerGroup(entry.getValue(), depth + 1)) return true;
            }
            return false;
        }

        private static boolean hasStateAction(JsonElement element, String state, int depth) {
            if (element == null || depth > 64) return false;
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) if (hasStateAction(child, state, depth + 1)) return true;
            } else if (element.isJsonObject()) {
                JsonObject node = element.getAsJsonObject();
                if ("State".equals(string(node.get("Type"))) && state.equals(string(node.get("State")))) return true;
                for (var entry : node.entrySet()) if (hasStateAction(entry.getValue(), state, depth + 1)) return true;
            }
            return false;
        }

        private static boolean hasType(JsonElement element, String type, int depth) {
            if (element == null || depth > 64) return false;
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) if (hasType(child, type, depth + 1)) return true;
            } else if (element.isJsonObject()) {
                JsonObject node = element.getAsJsonObject();
                if (node.has("Enabled") && node.get("Enabled").isJsonPrimitive()
                        && node.get("Enabled").getAsJsonPrimitive().isBoolean() && !node.get("Enabled").getAsBoolean()) return false;
                if (type.equals(string(node.get("Type")))) return true;
                for (var entry : node.entrySet()) if (hasType(entry.getValue(), type, depth + 1)) return true;
            }
            return false;
        }

        private static boolean disabled(JsonObject node) {
            return node.has("Enabled") && node.get("Enabled").isJsonPrimitive()
                    && node.get("Enabled").getAsJsonPrimitive().isBoolean() && !node.get("Enabled").getAsBoolean();
        }

        boolean disengagingState(String state) {
            if (!combatStates || state == null || state.equals("Flee.Attack")) return false;
            return state.equals("Flee") || state.startsWith("Flee.")
                    || state.equals("Panic") || state.startsWith("Panic.")
                    || state.equals("ReturnHome") || state.startsWith("ReturnHome.")
                    || state.equals("Combat.Maybe");
        }

        private static void collectParameters(JsonObject json, Map<String, JsonElement> result) {
            JsonObject parameters = object(json.get("Parameters"));
            if (parameters == null) return;
            parameters.entrySet().forEach(e -> {
                JsonObject definition = object(e.getValue());
                if (definition != null && definition.has("Value")) result.put(e.getKey(), definition.get("Value"));
            });
        }

        private static JsonElement evaluate(JsonElement value, Map<String, JsonElement> parameters, Set<String> seen) {
            if (value == null || value.isJsonNull()) return null;
            if (value.isJsonPrimitive()) return value;
            JsonObject expression = object(value);
            String parameter = expression == null ? null : string(expression.get("Compute"));
            if (parameter == null || !parameter.matches("[A-Za-z_][A-Za-z0-9_]*") || !seen.add(parameter)) return null;
            return evaluate(parameters.get(parameter), parameters, seen);
        }

        private static JsonObject object(JsonElement value) {
            return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
        }

        private static String string(JsonElement value) {
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    ? value.getAsString() : null;
        }
    }

    /** Positive avoidance policy from resolved instruction components, not a proof that
     * arbitrary external mod code cannot damage. Unknown references fail conservatively. */
    static final class NpcAssetEvasion {
        static boolean avoidsPlayer(JsonElement instructions, Map<String, JsonElement> parameters,
                                    Map<String, JsonElement> values, Function<String, JsonObject> assets) {
            Map<String, JsonElement> environment = new HashMap<>(parameters);
            values.forEach((key, value) -> environment.put(key, resolve(value, parameters)));
            try {
                int[] budget = {32768};
                JsonElement tree = expand(instructions, environment, Map.of(), assets, 0, budget);
                // Never suppress a native attack/hit/charge route, including one hidden
                // behind a component reference. It needs an offensive profile instead.
                for (String offense : List.of("Attack", "Charge", "EntityHitInteraction", "BlockHitInteraction", "SpawnInteraction", "ApplyEntityEffect"))
                    if (containsType(tree, offense)) return false;
                return playerAlert(tree) && stateBranch(tree, "Alerted", "Flee", false)
                        && stateBranch(tree, "Flee", "Flee", true);
            } catch (RuntimeException failure) { return false; }
        }

        private static JsonElement expand(JsonElement element, Map<String, JsonElement> env, Map<String, String> states,
                                          Function<String, JsonObject> assets, int depth, int[] budget) {
            if (element == null) return JsonNull.INSTANCE;
            if (depth > 64 || --budget[0] < 0) throw new IllegalArgumentException("inspection budget");
            if (element.isJsonArray()) {
                JsonArray out = new JsonArray();
                for (JsonElement child : element.getAsJsonArray()) out.add(expand(child, env, states, assets, depth + 1, budget));
                return out;
            }
            if (!element.isJsonObject()) return element.deepCopy();
            JsonObject node = element.getAsJsonObject();
            if (node.has("Enabled") && new JsonPrimitive(false).equals(resolve(node.get("Enabled"), env))) return JsonNull.INSTANCE;
            if (node.has("Reference")) {
                for (String key : List.of("Instructions", "Actions", "Action", "Sensor", "Sensors", "BodyMotion", "HeadMotion", "Content"))
                    if (node.has(key)) throw new IllegalArgumentException("executable reference override");
                JsonElement reference = resolve(node.get("Reference"), env);
                if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("unresolved component reference");
                String name = reference.getAsString();
                if (name.isEmpty() && node.has("Nullable") && node.get("Nullable").getAsBoolean()) return JsonNull.INSTANCE;
                JsonObject definition = assets.apply(name);
                if (definition == null || !definition.has("Content")) throw new IllegalArgumentException("missing component");
                Map<String, JsonElement> childEnv = new HashMap<>(env);
                JsonObject params = object(definition.get("Parameters"));
                if (params != null) params.entrySet().forEach(entry -> {
                    JsonObject parameter = object(entry.getValue());
                    if (parameter != null && parameter.has("Value")) childEnv.put(entry.getKey(), resolve(parameter.get("Value"), env));
                });
                JsonObject modify = object(node.get("Modify"));
                if (modify != null) for (String key : List.of("Instructions", "Actions", "Action", "Sensor", "Sensors", "BodyMotion", "HeadMotion", "Content"))
                    if (modify.has(key)) throw new IllegalArgumentException("executable component replacement");
                if (modify != null) modify.entrySet().forEach(entry -> childEnv.put(entry.getKey(), resolve(entry.getValue(), env)));
                Map<String, String> childStates = new HashMap<>();
                JsonElement imports = params == null ? null : params.get("_ImportStates");
                JsonElement exports = childEnv.get("_ExportStates");
                if (imports != null && imports.isJsonArray()) {
                    if (exports == null || !exports.isJsonArray() || imports.getAsJsonArray().size() != exports.getAsJsonArray().size())
                        throw new IllegalArgumentException("unresolved state exports");
                    for (int i = 0; i < imports.getAsJsonArray().size(); i++) {
                        String imported = imports.getAsJsonArray().get(i).getAsString();
                        String exported = exports.getAsJsonArray().get(i).getAsString();
                        childStates.put(imported, states.getOrDefault(exported, exported));
                    }
                }
                return expand(definition.get("Content"), childEnv, childStates, assets, depth + 1, budget);
            }
            JsonObject out = new JsonObject();
            for (var entry : node.entrySet()) {
                if (entry.getKey().equals("$Comment") || entry.getKey().equals("Parameters")) continue;
                out.add(entry.getKey(), expand(resolve(entry.getValue(), env), env, states, assets, depth + 1, budget));
            }
            if ("ParentState".equals(string(out.get("Type")))) {
                String state = string(out.get("State"));
                if (state == null || !states.containsKey(state)) throw new IllegalArgumentException("unresolved parent state");
                out.addProperty("Type", "State");
                out.addProperty("State", states.get(state));
            }
            return out;
        }

        private static JsonElement resolve(JsonElement value, Map<String, JsonElement> env) {
            Set<String> seen = new HashSet<>();
            while (value != null && value.isJsonObject()) {
                String key = string(value.getAsJsonObject().get("Compute"));
                if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]*") || !seen.add(key) || !env.containsKey(key)) break;
                value = env.get(key);
            }
            return value == null ? JsonNull.INSTANCE : value;
        }
        private static boolean playerAlert(JsonElement tree) {
            return any(tree, node -> positivePlayer(node.get("Sensor")) && containsStateAction(node.get("Actions"), "Alerted"));
        }
        private static boolean stateBranch(JsonElement tree, String state, String goal, boolean motion) {
            return any(tree, node -> {
                JsonObject sensor = object(node.get("Sensor"));
                if (sensor == null || !"State".equals(string(sensor.get("Type"))) || !state.equals(string(sensor.get("State")))) return false;
                return motion ? containsType(node.get("Instructions"), goal)
                        : containsStateAction(node.get("Actions"), goal)
                        || any(node.get("Instructions"), child -> containsStateAction(child.get("Actions"), goal));
            });
        }
        private static boolean positivePlayer(JsonElement element) {
            if (element == null) return false;
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) if (positivePlayer(child)) return true;
            } else if (element.isJsonObject()) {
                JsonObject node = element.getAsJsonObject();
                if ("Not".equals(string(node.get("Type")))) return false;
                if ("Player".equals(string(node.get("Type")))) return true;
                for (var entry : node.entrySet()) if (positivePlayer(entry.getValue())) return true;
            }
            return false;
        }
        private static boolean containsStateAction(JsonElement tree, String state) {
            return any(tree, node -> "State".equals(string(node.get("Type"))) && state.equals(string(node.get("State"))));
        }
        private static boolean containsType(JsonElement tree, String type) {
            return any(tree, node -> type.equals(string(node.get("Type"))));
        }
        private static boolean any(JsonElement element, java.util.function.Predicate<JsonObject> predicate) {
            if (element == null || element.isJsonNull()) return false;
            if (element.isJsonArray()) { for (JsonElement child : element.getAsJsonArray()) if (any(child, predicate)) return true; }
            else if (element.isJsonObject()) {
                JsonObject node = element.getAsJsonObject();
                if (predicate.test(node)) return true;
                for (var entry : node.entrySet()) if (any(entry.getValue(), predicate)) return true;
            }
            return false;
        }
        private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
        private static String string(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null; }
    }
}

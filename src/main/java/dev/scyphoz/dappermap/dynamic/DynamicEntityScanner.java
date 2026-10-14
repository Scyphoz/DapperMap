package dev.scyphoz.dappermap.dynamic;

import com.google.gson.*;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.joml.Vector3d;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapColours.Key.*;
import static dev.scyphoz.dappermap.util.DapperMapUtil.jsonDisabled;
import static dev.scyphoz.dappermap.util.DapperMapUtil.jsonObject;
import static dev.scyphoz.dappermap.util.DapperMapUtil.jsonString;


/** Converts nearby NPC and player entities into coloured minimap markers (or creature icons). */
public final class DynamicEntityScanner {

    private DynamicEntityScanner() {
    }

    /**
     * @param threats per-viewer colour cache (may be null to classify every creature afresh).
     * @param icons   per-viewer creature icon keys, used when Creature Markers is set to Icons.
     * @param playerIcons per-viewer player head icon keys, used when Player Markers is set to Icons.
     */
    public static List<DynamicMinimapMarker> scan(Store<EntityStore> store, Ref<EntityStore> playerRef, Vector3d playerPosition,
                                                  double radius, DynamicMinimapSetting settings, ThreatCache threats,
                                                  DynamicCreatureIcons.KeyCache icons,
                                                  DynamicCreatureIcons.PlayerKeyCache playerIcons) {
        ArrayList<DynamicMinimapMarker> markers = new ArrayList<>();
        double radiusSq = radius * radius;
        double playerX = playerPosition.x();
        double playerY = playerPosition.y();
        double playerZ = playerPosition.z();
        // Hostile-only keeps a larger nearest-first pool so passive creatures nearby cannot
        // use up the marker limit before more distant hostiles are reached.
        boolean hostileOnly = settings.hostileCreaturesOnly;
        NearestNpcs creatures = settings.showEntities
                ? new NearestNpcs(settings.creatureMarkerLimit, hostileOnly, playerX, playerZ) : null;
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
                        creatures.add(npcRef, npc, position.x(), position.z());
                    } catch (RuntimeException ignored) { }
                }
            } else {
                try {
                    store.forEachChunk(NPCEntity.getComponentType(), (chunk, commandBuffer) -> {
                        for(int i = 0; i < chunk.size(); ++i) {
                            try {
                                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                                if (transform != null) {
                                    Vector3d position = transform.getPosition();
                                    double dx = position.x() - playerX;
                                    double dz = position.z() - playerZ;
                                    if (!(dx * dx + dz * dz > radiusSq) && (!(verticalRange > (double)0.0F) || !(Math.abs(position.y() - playerY) > verticalRange))) {
                                        if (!creatures.wouldAccept(position.x(), position.z())) continue;
                                        NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                                        if (npc != null) {
                                            Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                                            creatures.add(npcRef, npc, position.x(), position.z());
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                                // One unreadable entity must not end the chunk scan.
                            }
                        }

                    });
                } catch (Exception ignored) {
                    // Store not readable this tick: no creature markers this scan.
                }
            }
        }

        // Only classify the final displayed set. Intermediate candidates may be
        // replaced by closer creatures, and need no attitude or intent reads.
        if (creatures != null) {
            long now = System.currentTimeMillis();
            boolean useIcons = settings.creatureIcons && icons != null;
            int iconSize = DynamicCreatureIcons.markerSize(settings); // Grows with map size (20-32 units).
            int shown = 0;
            for (NearestNpcs.Candidate candidate : creatures.sorted()) {
                if (shown >= creatures.shown) break; // Marker limit reached (nearest first).
                try {
                    if (!candidate.ref().isValid()) continue;
                    NPCEntity npc = candidate.npc(); // Read during selection; no second lookup.
                    if (npc == null) continue;
                    DynamicMinimapColours.Key key = threats == null
                            ? colourFor(candidate.ref(), npc, playerRef, store)
                            : threats.classify(candidate.ref(), npc, playerRef, store, now,
                            hostileOnly ? ThreatCache.HIDDEN_TTL_MS : ThreatCache.TTL_MS);
                    // Hostile-only: skip neutral and friendly creatures (attacking ones always show).
                    if (hostileOnly && key != HOSTILE && key != HOSTILE_ACTIVE) continue;
                    Color colour = settings.colours.awt(key, 255);
                    // Hostile and attacking creatures are drawn above other markers.
                    boolean threat = key == HOSTILE || key == HOSTILE_ACTIVE;
                    // Icons mode: the same threat colour edges the creature's face icon
                    // (the icon key itself is a cached map read per creature).
                    markers.add(useIcons
                            ? DynamicMinimapMarker.creature(candidate.x(), candidate.z(),
                            icons.key(candidate.ref(), npc, store), colour, iconSize, threat)
                            : DynamicMinimapMarker.creatureDot(candidate.x(), candidate.z(), colour, 7, threat));
                    shown++;
                } catch (RuntimeException ignored) { }
            }
            // Both caches keep only the creatures of this scan (the unused one empties itself).
            if (threats != null) threats.endScan();
            if (icons != null) icons.endScan();
        } else {
            // Creatures hidden: don't keep entity references (and old models) alive.
            if (threats != null) threats.clear();
            if (icons != null) icons.clear();
        }

        PlayerVisibility visibility = settings.showOtherPlayers ? PlayerVisibility.forViewer(store, playerRef) : null;
        if (visibility != null) {
            // Nearby player entities: spatial index first, chunk scan as the fallback.
            List<Ref<EntityStore>> others = nearby(store, playerPosition, radius, 0, false);
            if (others == null) {
                List<Ref<EntityStore>> found = new ArrayList<>();
                try {
                    store.forEachChunk(Player.getComponentType(), (chunk, commandBuffer) -> {
                        for (int i = 0; i < chunk.size(); ++i) {
                            try {
                                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                                if (transform == null) continue;
                                Vector3d position = transform.getPosition();
                                double dx = position.x() - playerX, dz = position.z() - playerZ;
                                if (dx * dx + dz * dz <= radiusSq) found.add(chunk.getReferenceTo(i));
                            } catch (Exception ignored) {
                                // One unreadable entity must not end the chunk scan.
                            }
                        }
                    });
                } catch (Exception ignored) {
                    // Store not readable this tick: no player markers this scan.
                }
                others = found;
            }
            addPlayers(markers, others, store, settings, visibility, playerIcons, playerX, playerZ, radiusSq);
        } else if (playerIcons != null) {
            playerIcons.clear(); // Players hidden: don't keep their entity references alive.
        }

        return markers;
    }

    /**
     * Adds a marker for each visible player: a head icon (or dot) edged in the player colour, or
     * in the attacking colour while that player has hit the viewer in the last 15 s (PvP).
     * Dead players are left off until they respawn. World thread.
     */
    private static void addPlayers(List<DynamicMinimapMarker> markers, List<Ref<EntityStore>> others,
                                   Store<EntityStore> store, DynamicMinimapSetting settings, PlayerVisibility visibility,
                                   DynamicCreatureIcons.PlayerKeyCache playerIcons,
                                   double playerX, double playerZ, double radiusSq) {
        Color playerColour = settings.colours.awt(PLAYERS, 255);
        Color attackingColour = settings.colours.awt(HOSTILE_ACTIVE, 255);
        boolean useIcons = settings.playerIcons && playerIcons != null;
        // Players stand out a little from creature icons of the same map size.
        int iconSize = DynamicCreatureIcons.markerSize(settings) + 2;
        // Most of the time nobody has hit the viewer: skip the per-player lookups entirely.
        boolean attacked = PlayerAttacks.anyAttackers(visibility.viewer);
        long now = attacked ? System.currentTimeMillis() : 0L;
        for (Ref<EntityStore> other : others) {
            try {
                if (!other.isValid()) continue;
                TransformComponent transform = store.getComponent(other, TransformComponent.getComponentType());
                if (transform == null || store.getComponent(other, Player.getComponentType()) == null) continue;
                Vector3d position = transform.getPosition();
                double dx = position.x() - playerX, dz = position.z() - playerZ;
                double distance = dx * dx + dz * dz;
                // Cheap distance test first; visibility lookups only for candidates.
                if (distance < .01 || distance > radiusSq) continue;
                PlayerRef ref = store.getComponent(other, PlayerRef.getComponentType());
                if (!visibility.visible(ref, store.getComponent(other, Spectating.getComponentType()) != null)) continue;
                // Dead (death screen): no marker until they respawn.
                if (store.getComponent(other, com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent.getComponentType()) != null) continue;
                boolean attacking = attacked && PlayerAttacks.isAttacking(visibility.viewer, ref.getUuid(), now);
                Color colour = attacking ? attackingColour : playerColour;
                markers.add(useIcons
                        ? DynamicMinimapMarker.player(position.x(), position.z(),
                        playerIcons.key(other, ref.getUsername(), store, settings.playerIconHelmets), colour, iconSize)
                        : DynamicMinimapMarker.playerDot(position.x(), position.z(), colour, 11));
            } catch (RuntimeException ignored) { }
        }
        // Keep only the players of this scan (dots mode empties it).
        if (playerIcons != null) playerIcons.endScan();
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

    /**
     * Which players have recently hit which (PvP). A viewer's minimap shows a player who hit
     * them in the attacking colour until that player has not hit them for {@link #HOLD_MS}, or
     * dies. Filled by an ECS damage system that runs after Hytale's own filters, so cancelled
     * hits (PvP off in the world, spawn protection, invulnerable players) never count.
     * Hits land on world threads and are read on others: concurrent maps, a few entries each.
     */
    public static final class PlayerAttacks {
        /** A player stays "attacking" this long after their last hit on the viewer. */
        static final long HOLD_MS = 15_000L;
        /** Victim -> (attacker -> time of the attacker's last hit on them). */
        private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Long>> HITS = new ConcurrentHashMap<>();

        private PlayerAttacks() { }

        /** Registers the damage and death systems; called once from plugin setup. */
        public static void register(com.hypixel.hytale.component.ComponentRegistryProxy<EntityStore> registry) {
            registry.registerSystem(new HitSystem());
            registry.registerSystem(new DeathSystem());
        }

        static void recordHit(UUID victim, UUID attacker, long now) {
            var attackers = HITS.computeIfAbsent(victim, ignored -> new ConcurrentHashMap<>());
            attackers.values().removeIf(at -> now - at >= HOLD_MS); // Small map: drop stale hits here.
            attackers.put(attacker, now);
        }

        /** True while {@code attacker} has hit {@code victim} within the last HOLD_MS. */
        static boolean isAttacking(UUID victim, UUID attacker, long now) {
            var attackers = victim == null || attacker == null ? null : HITS.get(victim);
            Long at = attackers == null ? null : attackers.get(attacker);
            if (at == null) return false;
            if (now - at < HOLD_MS) return true;
            attackers.remove(attacker, at); // Expired: forget it.
            return false;
        }

        /** True when anyone has hit {@code victim} recently (skips per-player lookups otherwise). */
        static boolean anyAttackers(UUID victim) {
            var attackers = victim == null ? null : HITS.get(victim);
            return attackers != null && !attackers.isEmpty();
        }

        /** A dying player stops counting as anyone's attacker. */
        static void attackerDied(UUID attacker) {
            for (var attackers : HITS.values()) attackers.remove(attacker);
        }

        /** The player left: forget hits on them and by them. */
        public static void forget(UUID player) {
            if (player == null) return;
            HITS.remove(player);
            attackerDied(player);
        }

        /** Records player-on-player hits (projectiles count for their shooter). World thread. */
        static final class HitSystem extends com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem {
            @Override public com.hypixel.hytale.component.query.Query<EntityStore> getQuery() {
                return PlayerRef.getComponentType();
            }

            /** Inspect group: runs after the filters, so only damage that really lands is seen. */
            @Override public com.hypixel.hytale.component.SystemGroup<EntityStore> getGroup() {
                return com.hypixel.hytale.server.core.modules.entity.damage.DamageModule.get().getInspectDamageGroup();
            }

            @Override public void handle(int index, com.hypixel.hytale.component.ArchetypeChunk<EntityStore> chunk,
                                         Store<EntityStore> store, com.hypixel.hytale.component.CommandBuffer<EntityStore> buffer,
                                         com.hypixel.hytale.server.core.modules.entity.damage.Damage damage) {
                try {
                    // Cheapest test first: most damage is from creatures, falls or the world.
                    if (!(damage.getSource() instanceof com.hypixel.hytale.server.core.modules.entity.damage.Damage.EntitySource source)) return;
                    Ref<EntityStore> attackerRef = source.getRef();
                    if (attackerRef == null || !attackerRef.isValid()) return;
                    PlayerRef attacker = buffer.getComponent(attackerRef, PlayerRef.getComponentType());
                    if (attacker == null) return; // Not a player.
                    PlayerRef victim = chunk.getComponent(index, PlayerRef.getComponentType());
                    if (victim == null || victim.getUuid() == null || attacker.getUuid() == null
                            || victim.getUuid().equals(attacker.getUuid())) return;
                    var world = store.getExternalData().getWorld();
                    if (world != null && !world.getWorldConfig().isPvpEnabled()) return; // Belt and braces: PvP off.
                    recordHit(victim.getUuid(), attacker.getUuid(), System.currentTimeMillis());
                } catch (RuntimeException ignored) {
                    // Never disturb the damage pipeline.
                }
            }
        }

        /** Clears a player's attacks on everyone when they die. World thread. */
        static final class DeathSystem extends com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems.OnDeathSystem {
            @Override public com.hypixel.hytale.component.query.Query<EntityStore> getQuery() {
                return PlayerRef.getComponentType();
            }

            @Override public void onComponentAdded(Ref<EntityStore> ref, com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent death,
                                                   Store<EntityStore> store, com.hypixel.hytale.component.CommandBuffer<EntityStore> buffer) {
                try {
                    if (HITS.isEmpty()) return;
                    PlayerRef player = buffer.getComponent(ref, PlayerRef.getComponentType());
                    if (player != null && player.getUuid() != null) attackerDied(player.getUuid());
                } catch (RuntimeException ignored) { }
            }
        }
    }

    /** Native spatial indices are rebuilt by Hytale every tick. Query a slightly padded cylinder first,
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

    /** Hytale rebuilds the spatial indices every world tick, so indexed positions are at most one tick old. */
    private static final double INDEX_SLACK = 8.0;

    static <T> List<T> collectNearby(com.hypixel.hytale.component.spatial.SpatialStructure<T> structure,
                                     Vector3d position, double radius, double height) {
        // Cylinder: horizontal circle plus a vertical band. collectCylinder takes the FULL
        // height (it tests |dy| <= height / 2); no vertical filter means every elevation.
        double fullHeight = height > 0 ? 2.0 * (height + INDEX_SLACK) : Double.MAX_VALUE;
        List<T> result = new ArrayList<>(64);
        structure.collectCylinder(position, radius + INDEX_SLACK, fullHeight, result);
        return result;
    }

    /** A role parameter name usable in {"Compute": name}; compiled once (role JSON is read on the render pool). */
    private static final java.util.regex.Pattern PARAMETER_NAME = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Retains positions/references only; expensive AI inspection follows selection. */
    static final class NearestNpcs {
        record Candidate(Ref<EntityStore> ref, NPCEntity npc, double x, double z, double distanceSquared) { }
        /** Hostile-only pool: 4x the marker limit, capped so classification work stays bounded. */
        private static final int HOSTILE_POOL_FACTOR = 4, HOSTILE_POOL_MAX = 128;
        /** Markers to show (16/32/64); {@code limit} is how many nearest candidates are kept. */
        final int shown;
        private final int limit;
        private final double x, z;
        private static final Comparator<Candidate> NEAREST = Comparator.comparingDouble(Candidate::distanceSquared);
        private final PriorityQueue<Candidate> candidates;
        NearestNpcs(int limit, boolean hostileOnly, double x, double z) {
            this.shown = limit == 32 || limit == 64 ? limit : 16;
            this.limit = hostileOnly ? Math.min(this.shown * HOSTILE_POOL_FACTOR, HOSTILE_POOL_MAX) : this.shown;
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
        void add(Ref<EntityStore> ref, NPCEntity npc, double markerX, double markerZ) {
            double distance = distance(markerX, markerZ);
            if (candidates.size() >= limit) {
                if (distance >= candidates.peek().distanceSquared()) return;
                candidates.poll();
            }
            candidates.add(new Candidate(ref, npc, markerX, markerZ, distance));
        }
        List<Candidate> sorted() {
            var result = new ArrayList<>(candidates);
            result.sort(NEAREST);
            return result;
        }
    }

    /**
     * Per-viewer cache of creature threat colours. Full classification (attitude cache,
     * AI state, combat state, role profile and behaviour-tree inspection) is the most
     * expensive part of a marker scan, so a result is reused for up to {@link #TTL_MS}.
     *
     * <p>Attack responsiveness is preserved by a cheap "engagement" fingerprint read on
     * every scan: whether the creature's locked target is this player, whether it is
     * executing an attack, and whether its current movement is aimed at this player. Any
     * change to that fingerprint (a creature starting or stopping an attack on the player)
     * forces an immediate reclassification on that same scan.
     *
     * <p>Only creatures classified in the latest scan are kept, so the cache is bounded by the
     * candidate pool (the marker limit, or up to 128 in hostile-only mode) and never retains
     * removed entities. World thread only.
     */
    public static final class ThreatCache {
        static final long TTL_MS = 750L;
        /**
         * Hostile-only mode: neutral/friendly results (hidden markers) are re-checked less often.
         * A creature turning on the player still changes its engagement and shows at once.
         */
        static final long HIDDEN_TTL_MS = 2000L;
        private record Entry(DynamicMinimapColours.Key key, int engagement, long expiresAt) { }
        private java.util.Map<Ref<EntityStore>, Entry> current = new java.util.HashMap<>();
        private java.util.Map<Ref<EntityStore>, Entry> next = new java.util.HashMap<>();

        DynamicMinimapColours.Key classify(Ref<EntityStore> npcRef, NPCEntity npc, Ref<EntityStore> playerRef,
                                           ComponentAccessor<EntityStore> accessor, long now, long passiveTtl) {
            // Read once and shared with a full reclassification (previously read twice each).
            Ref<EntityStore> lockedTarget = getLockedTarget(npcRef, accessor);
            boolean executingAttack = isExecutingAttack(npcRef, accessor);
            int engagement = engagement(npc, playerRef, lockedTarget, executingAttack);
            Entry cached = this.current.get(npcRef);
            if (cached == null || cached.engagement() != engagement || now >= cached.expiresAt()) {
                NpcThreatProfile profile;
                try { profile = NpcThreatClassifier.profileFor(npc.getRoleIndex()); }
                catch (RuntimeException | LinkageError ignored) { profile = NpcThreatProfile.UNKNOWN; }
                DynamicMinimapColours.Key key = colourFor(npcRef, npc, playerRef, accessor, lockedTarget,
                        executingAttack, profile);
                // Provisional result: a creature's first check (its attitude towards the player
                // may not be set up yet) or one made while its species profile is still loading.
                // It is checked again on the next scan instead of being kept for the full TTL,
                // so a hostile creature shows as hostile almost as soon as it appears.
                boolean provisional = cached == null || profile == NpcThreatProfile.LOADING;
                long ttl = provisional ? 0L : key == HOSTILE || key == HOSTILE_ACTIVE ? TTL_MS : passiveTtl;
                cached = new Entry(key, engagement, now + ttl);
            }
            this.next.put(npcRef, cached);
            return cached.key();
        }

        /** Keeps only creatures seen in the scan just finished. */
        void endScan() {
            var swap = this.current;
            this.current = this.next;
            this.next = swap;
            this.next.clear();
        }

        /** Forgets every creature (creatures hidden, or the player changed world). */
        void clear() {
            this.current.clear();
            this.next.clear();
        }

        /** Bit 0: locked onto player; bit 1: executing an attack; bit 2: moving at player. */
        private static int engagement(NPCEntity npc, Ref<EntityStore> playerRef,
                                      Ref<EntityStore> lockedTarget, boolean executingAttack) {
            int bits = sameEntity(lockedTarget, playerRef) ? 1 : 0;
            if (executingAttack) bits |= 2;
            try {
                Role role = npc.getRole();
                BodyMotion motion = role == null ? null : role.getLastBodySteeringMotion();
                if (motion != null && sameEntity(motion.getDesiredTargetEntity(), playerRef)) bits |= 4;
            } catch (RuntimeException | LinkageError ignored) { }
            return bits;
        }
    }

    /** Full threat classification, reading the creature's locked target and attack state itself. */
    private static DynamicMinimapColours.Key colourFor(Ref<EntityStore> npcRef, NPCEntity npc,
                                                       Ref<EntityStore> playerRef, ComponentAccessor<EntityStore> commandBuffer) {
        NpcThreatProfile profile;
        try { profile = NpcThreatClassifier.profileFor(npc.getRoleIndex()); }
        catch (RuntimeException | LinkageError ignored) { profile = NpcThreatProfile.UNKNOWN; }
        return colourFor(npcRef, npc, playerRef, commandBuffer,
                getLockedTarget(npcRef, commandBuffer), isExecutingAttack(npcRef, commandBuffer), profile);
    }

    /** True while the creature is executing an attack (false when unknown). */
    private static boolean isExecutingAttack(Ref<EntityStore> npcRef, ComponentAccessor<EntityStore> accessor) {
        try {
            CombatSupport support = CombatSupport.get(npcRef, accessor);
            return support != null && support.isExecutingAttack();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Full threat classification with the locked target, attack state and role profile already read. */
    private static DynamicMinimapColours.Key colourFor(Ref<EntityStore> npcRef, NPCEntity npc,
                                                       Ref<EntityStore> playerRef, ComponentAccessor<EntityStore> commandBuffer,
                                                       Ref<EntityStore> lockedTarget, boolean executingAttack,
                                                       NpcThreatProfile profile) {
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
            boolean targetingPlayer = sameEntity(lockedTarget, playerRef);
            String state = null;
            try {
                StateSupport support = StateSupport.get(npcRef, commandBuffer);
                if (support != null) state = support.getStateName();
            } catch (RuntimeException ignored) { }
            var signals = NpcBehaviourSignals.capture(npc.getRole(), npc.getRoleIndex(), playerRef, lockedTarget, npcRef, commandBuffer);
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
        } catch (Exception ignored) {
            return null;
        }
    }

    /** True when both references are live and point at the same entity. */
    private static boolean sameEntity(Ref<EntityStore> first, Ref<EntityStore> second) {
        return first != null && second != null && first.isValid() && second.isValid() && first.equals(second);
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

        static Signals capture(Role role, int roleIndex, Ref<EntityStore> player, Ref<EntityStore> lockedTarget,
                               Ref<EntityStore> npcRef, ComponentAccessor<EntityStore> accessor) {
            if (role == null) return Signals.UNKNOWN;
            final Tree tree;
            try { tree = tree(role, roleIndex); }
            catch (RuntimeException | LinkageError ignored) { return Signals.UNKNOWN; }
            boolean activeAttackPlayer = false;
            try {
                for (WeakReference<ActionAttack> reference : tree.attacks) {
                    ActionAttack action = reference.get();
                    // This is only positive evidence while an action remains activated.
                    // Blocking lists deactivate completed actions in the same cycle; false
                    // does not mean no attack. isTriggered() is remembered, not live intent.
                    if (action != null && action.isActivated() && sameEntity(targetFromParents(action), player)) {
                        activeAttackPlayer = true;
                        break;
                    }
                }
                BodyMotion motion = role.getLastBodySteeringMotion();
                if (motion == null) return new Signals(tree.capability, Intent.OTHER, activeAttackPlayer, "none");
                Ref<EntityStore> target = motion.getDesiredTargetEntity();
                if (target == null) target = targetFromParents(motion);
                Intent intent = Intent.OTHER;
                if (motion instanceof BodyMotionFind && sameEntity(target, player)) intent = Intent.TOWARDS_PLAYER;
                if (motion instanceof BodyMotionCharge charge && sameEntity(target, player) && chargeEngaged(charge))
                    intent = Intent.CHARGE_PLAYER;
                if (motion instanceof BodyMotionMaintainDistance && sameEntity(target, player)) {
                    TransformComponent npcTransform = accessor.getComponent(npcRef, TransformComponent.getComponentType());
                    TransformComponent playerTransform = accessor.getComponent(player, TransformComponent.getComponentType());
                    if (npcTransform != null && playerTransform != null
                            && approaching(role.getBodySteering(), npcTransform.getPosition(), playerTransform.getPosition()))
                        intent = Intent.TOWARDS_PLAYER;
                }
                if (motion instanceof BodyMotionMoveAway
                        && sameEntity(target == null ? lockedTarget : target, player)) intent = Intent.AWAY_FROM_PLAYER;
                Capability capability = motion instanceof BodyMotionCharge ? Capability.ATTACK_CAPABLE : tree.capability;
                return new Signals(capability, intent, activeAttackPlayer, motion.getClass().getSimpleName());
            } catch (RuntimeException | LinkageError ignored) {
                return new Signals(tree.capability, Intent.OTHER, activeAttackPlayer, "unavailable");
            }
        }

        /**
         * A charge counts as an attack while it is lining up, winding up or running at the
         * player; one that has finished, lost its target or been blocked does not (the NPC then
         * falls back to its other signals). Matched by name: 0.7.0 adds InStopRange, so the
         * enum's ordinals differ between server versions. Unknown states keep the old behaviour.
         */
        static boolean chargeEngaged(BodyMotionCharge charge) {
            Enum<?> state = charge.getState();
            if (state == null) return true;
            return switch (state.name()) {
                case "Finished", "LostTarget", "Obstructed", "Knockback" -> false;
                default -> true;
            };
        }

        static boolean approaching(Steering steering, org.joml.Vector3dc npc, org.joml.Vector3dc player) {
            if (steering == null || !steering.hasTranslation()) return false;
            double dx = player.x() - npc.x(), dz = player.z() - npc.z();
            double sx = steering.getX(), sz = steering.getZ();
            double product = (dx * dx + dz * dz) * (sx * sx + sz * sz);
            return product > 1e-12 && dx * sx + dz * sz > 0.05 * Math.sqrt(product);
        }

        /**
         * Role indexes whose behaviour tree has no offensive node, with the classifier generation
         * they were read in. Every NPC instance builds its own Role, so without this each newly
         * spawned passive creature had its whole tree walked (up to 8192 nodes) on the world
         * thread. Only NO_OFFENSE is shared: attack-capable roles still walk, since they need
         * their own instance's attack actions.
         */
        private static final ConcurrentHashMap<Integer, Long> PASSIVE_ROLES = new ConcurrentHashMap<>();

        private static Tree tree(Role role, int roleIndex) {
            Instruction root = role.getRootInstruction();
            Tree cached = TREES.get(role);
            if (cached != null && cached.root.get() == root) return cached;
            long generation = NpcThreatClassifier.GENERATION.get();
            Long passive = roleIndex < 0 ? null : PASSIVE_ROLES.get(roleIndex);
            Tree tree;
            if (passive != null && passive == generation) {
                tree = new Tree(new WeakReference<>(root), Capability.NO_OFFENSE, List.of());
            } else {
                List<WeakReference<ActionAttack>> attacks = new ArrayList<>();
                Capability capability = inspect(root, attacks);
                tree = new Tree(new WeakReference<>(root), capability, List.copyOf(attacks));
                if (roleIndex >= 0) {
                    if (capability == Capability.NO_OFFENSE) {
                        if (PASSIVE_ROLES.size() >= 4096) PASSIVE_ROLES.clear(); // Bounded.
                        PASSIVE_ROLES.put(roleIndex, generation);
                    } else PASSIVE_ROLES.remove(roleIndex);
                }
            }
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
    }

    /** Cached, read-only inspection of loaded role assets. No AI or attitude is modified. */
    static final class NpcThreatClassifier {
        private record Cached(BuilderInfo info, long generation, NpcThreatProfile profile) { }
        private static final ConcurrentHashMap<Integer, Cached> CACHE = new ConcurrentHashMap<>();
        private static final AtomicLong GENERATION = new AtomicLong();
        /** Role indexes whose profile is being read on the render pool. */
        private static final java.util.Set<Integer> PENDING = ConcurrentHashMap.newKeySet();
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
                // Reading and parsing the role's JSON assets is file I/O: do it on the render
                // pool, never on the world thread. Until it lands, use the previous profile for
                // this role (after an asset reload) or UNKNOWN, which classifies conservatively
                // from live attitude/AI state. The threat cache re-checks within 750 ms.
                if (PENDING.add(roleIndex) && !DynamicMinimapSession.renderAsync(() -> {
                    try {
                        // Components can be referenced repeatedly by one resolved role. Read
                        // each asset once per resolution, including missing/unreadable assets.
                        var assets = new java.util.HashMap<String, JsonObject>();
                        NpcThreatProfile profile = NpcThreatProfile.resolve(info.getKeyName(), name -> {
                            if (!assets.containsKey(name)) assets.put(name, read(manager, name));
                            return assets.get(name);
                        });
                        // Drop the result if assets were reloaded while it was being read.
                        if (GENERATION.get() == generation) CACHE.put(roleIndex, new Cached(info, generation, profile));
                    } catch (RuntimeException | LinkageError ignored) {
                        // Stays UNKNOWN; retried on a later scan.
                    } finally {
                        PENDING.remove(roleIndex);
                    }
                })) PENDING.remove(roleIndex); // Render pool busy: retry on a later scan.
                // LOADING classifies exactly like UNKNOWN; it also tells the threat cache to re-check soon.
                return cached != null ? cached.profile : NpcThreatProfile.LOADING;
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

        static DynamicMinimapColours.Key classify(NpcThreatProfile profile, Attitude attitude,
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
        /** Same as UNKNOWN, returned (by identity) while the role's profile is still being read. */
        static final NpcThreatProfile LOADING = new NpcThreatProfile(Kind.UNKNOWN, false);

        static NpcThreatProfile resolve(String roleName, Function<String, JsonObject> assets) {
            List<JsonObject> variants = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            String name = roleName;
            JsonObject root = null;
            while (name != null && visited.add(name) && visited.size() <= 64) {
                JsonObject json = assets.apply(name);
                if (json == null) return UNKNOWN;
                if (!"Variant".equals(jsonString(json.get("Type")))) {
                    root = json;
                    break;
                }
                variants.add(json);
                name = jsonString(json.get("Reference"));
            }
            if (root == null) return UNKNOWN;
            // Replacing instructions invalidates our knowledge of the template's behaviour.
            for (JsonObject variant : variants) {
                JsonObject modify = jsonObject(variant.get("Modify"));
                if (modify != null && (modify.has("Instructions") || modify.has("StateTransitions")
                        || modify.has("StartState"))) return UNKNOWN;
            }
            Map<String, JsonElement> parameters = new HashMap<>();
            collectParameters(root, parameters);
            for (int i = variants.size() - 1; i >= 0; i--) collectParameters(variants.get(i), parameters);
            Map<String, JsonElement> values = new HashMap<>();
            root.entrySet().forEach(e -> values.put(e.getKey(), e.getValue()));
            for (int i = variants.size() - 1; i >= 0; i--) {
                JsonObject modify = jsonObject(variants.get(i).get("Modify"));
                if (modify != null) modify.entrySet().forEach(e -> values.put(e.getKey(), e.getValue()));
            }
            return switch (name) {
                case "Template_Beasts_Passive_Critter", "Template_Birds_Passive",
                     "Template_Swimming_Passive", "Template_Edible_Critter" ->
                        new NpcThreatProfile(Kind.FLEE_ONLY, false);
                case "Template_Predator" -> {
                    // Most predator variants (zombies, ghoul, werewolf, raptor, ...) don't set the
                    // flag themselves: fall back to the template's parameter default (false in 0.7.1).
                    JsonElement raw = values.get("FleeIfNotThreatened");
                    if (raw == null) raw = parameters.get("FleeIfNotThreatened");
                    JsonElement flee = evaluate(raw, parameters, new HashSet<>());
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

        /** States that never count as offensive, even when an attack is nested inside them. */
        private static final Set<String> PASSIVE_STATES =
                Set.of("Idle", "Alerted", "Flee", "Panic", "Sleep", "ReturnHome", "Search");

        boolean offensiveState(String state) {
            if (!combatStates || state == null) return false;
            // Combat.Maybe decides whether to accept a flock request; it is not engagement yet.
            if (state.equals("Combat.Maybe")) return false;
            // Plain loop: no stream or string concatenation on this per-classification path.
            for (String root : offensiveRoots) {
                if (state.startsWith(root) && (state.length() == root.length()
                        || state.charAt(root.length()) == '.')) return true;
            }
            return false;
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
            String dispositionName = jsonString(disposition);
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
                if (jsonDisabled(node)) return;
                JsonObject sensor = jsonObject(node.get("Sensor"));
                String state = sensor != null && "State".equals(jsonString(sensor.get("Type"))) ? jsonString(sensor.get("State")) : null;
                String current = state == null ? enclosing : state.startsWith(".") ? enclosing + state : state;
                if (state != null && !current.isEmpty()
                        && ((!PASSIVE_STATES.contains(current)
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
                if (jsonDisabled(node)) return false;
                JsonObject enabled = jsonObject(node.get("Enabled"));
                if (enabled != null && "FleeIfNotThreatened".equals(jsonString(enabled.get("Compute")))
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
                if (jsonDisabled(node)) return false;
                JsonObject enabled = jsonObject(node.get("Enabled"));
                if (enabled != null && "!FleeIfNotThreatened".equals(jsonString(enabled.get("Compute")))
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
                if ("NPCGroup".equals(jsonString(node.get("Type"))) && node.has("IncludeGroups") && node.get("IncludeGroups").isJsonArray())
                    for (JsonElement group : node.getAsJsonArray("IncludeGroups")) if ("Player".equals(jsonString(group))) return true;
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
                if ("State".equals(jsonString(node.get("Type"))) && state.equals(jsonString(node.get("State")))) return true;
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
                if (jsonDisabled(node)) return false;
                if (type.equals(jsonString(node.get("Type")))) return true;
                for (var entry : node.entrySet()) if (hasType(entry.getValue(), type, depth + 1)) return true;
            }
            return false;
        }

        boolean disengagingState(String state) {
            if (!combatStates || state == null || state.equals("Flee.Attack")) return false;
            return state.equals("Flee") || state.startsWith("Flee.")
                    || state.equals("Panic") || state.startsWith("Panic.")
                    || state.equals("ReturnHome") || state.startsWith("ReturnHome.")
                    || state.equals("Combat.Maybe");
        }

        private static void collectParameters(JsonObject json, Map<String, JsonElement> result) {
            JsonObject parameters = jsonObject(json.get("Parameters"));
            if (parameters == null) return;
            parameters.entrySet().forEach(e -> {
                JsonObject definition = jsonObject(e.getValue());
                if (definition != null && definition.has("Value")) result.put(e.getKey(), definition.get("Value"));
            });
        }

        private static JsonElement evaluate(JsonElement value, Map<String, JsonElement> parameters, Set<String> seen) {
            if (value == null || value.isJsonNull()) return null;
            if (value.isJsonPrimitive()) return value;
            JsonObject expression = jsonObject(value);
            String parameter = expression == null ? null : jsonString(expression.get("Compute"));
            if (parameter == null || !PARAMETER_NAME.matcher(parameter).matches() || !seen.add(parameter)) return null;
            return evaluate(parameters.get(parameter), parameters, seen);
        }

    }

    /** Positive avoidance policy from resolved instruction components, not a proof that
     * arbitrary external mod code cannot damage. Unknown references fail conservatively. */
    static final class NpcAssetEvasion {
        /** Keys whose replacement would change what a referenced component does. */
        private static final List<String> EXECUTABLE_KEYS =
                List.of("Instructions", "Actions", "Action", "Sensor", "Sensors", "BodyMotion", "HeadMotion", "Content");
        /** Native offensive component types; any of them rules out an "avoids player" profile. */
        private static final List<String> OFFENSIVE_TYPES =
                List.of("Attack", "Charge", "EntityHitInteraction", "BlockHitInteraction", "SpawnInteraction", "ApplyEntityEffect");
        static boolean avoidsPlayer(JsonElement instructions, Map<String, JsonElement> parameters,
                                    Map<String, JsonElement> values, Function<String, JsonObject> assets) {
            Map<String, JsonElement> environment = new HashMap<>(parameters);
            values.forEach((key, value) -> environment.put(key, resolve(value, parameters)));
            try {
                int[] budget = {32768};
                JsonElement tree = expand(instructions, environment, Map.of(), assets, 0, budget);
                // Never suppress a native attack/hit/charge route, including one hidden
                // behind a component reference. It needs an offensive profile instead.
                for (String offense : OFFENSIVE_TYPES)
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
                for (String key : EXECUTABLE_KEYS)
                    if (node.has(key)) throw new IllegalArgumentException("executable reference override");
                JsonElement reference = resolve(node.get("Reference"), env);
                if (!reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("unresolved component reference");
                String name = reference.getAsString();
                if (name.isEmpty() && node.has("Nullable") && node.get("Nullable").getAsBoolean()) return JsonNull.INSTANCE;
                JsonObject definition = assets.apply(name);
                if (definition == null || !definition.has("Content")) throw new IllegalArgumentException("missing component");
                Map<String, JsonElement> childEnv = new HashMap<>(env);
                JsonObject params = jsonObject(definition.get("Parameters"));
                if (params != null) params.entrySet().forEach(entry -> {
                    JsonObject parameter = jsonObject(entry.getValue());
                    if (parameter != null && parameter.has("Value")) childEnv.put(entry.getKey(), resolve(parameter.get("Value"), env));
                });
                JsonObject modify = jsonObject(node.get("Modify"));
                if (modify != null) for (String key : EXECUTABLE_KEYS)
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
            if ("ParentState".equals(jsonString(out.get("Type")))) {
                String state = jsonString(out.get("State"));
                if (state == null || !states.containsKey(state)) throw new IllegalArgumentException("unresolved parent state");
                out.addProperty("Type", "State");
                out.addProperty("State", states.get(state));
            }
            return out;
        }

        private static JsonElement resolve(JsonElement value, Map<String, JsonElement> env) {
            Set<String> seen = new HashSet<>();
            while (value != null && value.isJsonObject()) {
                String key = jsonString(value.getAsJsonObject().get("Compute"));
                if (key == null || !PARAMETER_NAME.matcher(key).matches() || !seen.add(key) || !env.containsKey(key)) break;
                value = env.get(key);
            }
            return value == null ? JsonNull.INSTANCE : value;
        }
        private static boolean playerAlert(JsonElement tree) {
            return any(tree, node -> positivePlayer(node.get("Sensor")) && containsStateAction(node.get("Actions"), "Alerted"));
        }
        private static boolean stateBranch(JsonElement tree, String state, String goal, boolean motion) {
            return any(tree, node -> {
                JsonObject sensor = jsonObject(node.get("Sensor"));
                if (sensor == null || !"State".equals(jsonString(sensor.get("Type"))) || !state.equals(jsonString(sensor.get("State")))) return false;
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
                if ("Not".equals(jsonString(node.get("Type")))) return false;
                if ("Player".equals(jsonString(node.get("Type")))) return true;
                for (var entry : node.entrySet()) if (positivePlayer(entry.getValue())) return true;
            }
            return false;
        }
        private static boolean containsStateAction(JsonElement tree, String state) {
            return any(tree, node -> "State".equals(jsonString(node.get("Type"))) && state.equals(jsonString(node.get("State"))));
        }
        private static boolean containsType(JsonElement tree, String type) {
            return any(tree, node -> type.equals(jsonString(node.get("Type"))));
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
    }
}

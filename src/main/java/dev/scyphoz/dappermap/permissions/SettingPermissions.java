package dev.scyphoz.dappermap.permissions;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapColours;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The one table of DapperMap's player permission nodes (3.2.0+), and the permission-aware copying
 * of player settings built from it. The settings page, the {@code /dappermap} commands and
 * {@link DapperMapPermissions} all read this table, so a node can't drift between them.
 *
 * <p>Every player-setting node is {@code dappermap.command.<tab>.<setting>}, named exactly
 * like its command ({@code /dappermap <tab> <setting>}), so the command tree is the permission
 * tree and {@code dappermap.command.<tab>.*} covers a whole settings tab. Commands that
 * sit directly under {@code /dappermap} have a node directly under {@code command}:
 * {@code toggle} (the bare command, {@code on} and {@code off}), {@code settings}, {@code status},
 * {@code help}, {@code reset} and {@code position}. Each colour has {@code colours.<target>}.
 *
 * <p>To add a setting: add its row to {@link #SETTING_NODES} (path = group + "." + command name,
 * the settings-page row id, and the DynamicMinimapSetting.Field(s) it copies). DapperMapCommand checks at start-up that
 * every setting command has a node here.
 */
public final class SettingPermissions {

    /** Commands with no setting of their own (the page and help checks use them as well). */
    public static final String TOGGLE = "toggle";
    public static final String SETTINGS = "settings";
    public static final String STATUS = "status";
    public static final String HELP = "help";
    public static final String RESET = "reset";
    public static final String POSITION = "position";
    /** Prefix of the per-colour nodes, e.g. {@code colours.map-edge}. */
    public static final String COLOURS = "colours";

    /** Copies one setting's value(s) from {@code source} into {@code target}. */
    @FunctionalInterface
    private interface Copier {
        void copy(DynamicMinimapSetting target, DynamicMinimapSetting source);
    }

    /** One setting node: its path below {@code dappermap.command}, page row id (or null) and fields. */
    private record Node(String path, String rowId, Copier copier) { }

    /** Every node that guards a setting field, in settings-page order. */
    private static final List<Node> SETTING_NODES = new ArrayList<>();
    /** Settings-page row id -> node path. */
    private static final Map<String, String> ROW_NODES = new HashMap<>();
    /** Every player node path (settings plus the utility commands), for registration. */
    private static final Set<String> ALL_PATHS;
    /** Colour key -> its node path, built once (avoids string building per check). */
    private static final Map<DynamicMinimapColours.Key, String> COLOUR_NODES =
            new java.util.EnumMap<>(DynamicMinimapColours.Key.class);

    static {
        // Root-level: the Minimap switch (/dappermap, on, off).
        node(TOGGLE, "enabled", Field.ENABLED);
        // Display tab.
        node("display.zoom", "zoom", Field.ZOOM_BLOCKS);
        node("display.size", "size", Field.SIZE_PIXELS);
        node("display.scale", "interface_scale", Field.INTERFACE_SCALE);
        node("display.shape", "shape", Field.SHAPE);
        node("display.rotation", "rotation", Field.ROTATION_MODE);
        node("display.compass-letters", "compass_letters", Field.SHOW_COMPASS_LETTERS);
        node("display.compass-dashes", "compass_dashes", Field.SHOW_COMPASS_DASHES);
        node("display.cardinal-dashes", "cardinal_dashes", Field.SHOW_CARDINAL_DASHES);
        node("display.speed", "speed", Field.UPDATE_SPEED);
        node("display.resolution", "texture_resolution", Field.TEXTURE_RESOLUTION);
        // Markers tab.
        node("markers.players", "players", Field.SHOW_OTHER_PLAYERS);
        node("markers.player-style", "player_style", Field.PLAYER_ICONS);
        node("markers.player-helmets", "player_helmets", Field.PLAYER_ICON_HELMETS);
        node("markers.creatures", "entities", Field.SHOW_ENTITIES);
        node("markers.creature-style", "creature_style", Field.CREATURE_ICONS);
        node("markers.creature-filter", "creature_filter", Field.HOSTILE_CREATURES_ONLY);
        node("markers.creature-limit", "creature_limit", Field.CREATURE_MARKER_LIMIT);
        node("markers.height-filter", "height", Field.LIMIT_MOB_HEIGHT);
        node("markers.height-range", "vrange", Field.MOB_VERTICAL_RANGE);
        node("markers.death", "death", Field.SHOW_DEATH_MARKER);
        // Info Panel tab.
        node("info.panel", "info_panel", Field.INFO_PANEL_ENABLED);
        node("info.position", "panel_position", Field.INFO_PANEL_POSITION);
        node("info.alignment", "panel_alignment", Field.INFO_PANEL_TEXT_ALIGNMENT);
        node("info.coordinates", "coordinates", Field.SHOW_COORDINATES);
        node("info.bearing", "compass", Field.SHOW_COMPASS);
        node("info.world-time", "clock", Field.SHOW_CLOCK);
        node("info.clock-format", "clock_format", Field.CLOCK_24_HOUR);
        node("info.region", "biome", Field.SHOW_BIOME);
        // Integrations tab.
        node("integration.worldmap", "worldmap", Field.SHOW_WORLD_MAP_MARKERS);
        node("integration.bettermap", "bettermap", Field.BETTERMAP_ENABLED);
        node("integration.bettermap-markers", "bettermap_markers", Field.BETTERMAP_MARKERS);
        node("integration.bettermap-caves", "bettermap_caves", Field.BETTERMAP_CAVES);
        // Position tab (one setting: both axes).
        node(POSITION, "move_minimap", Field.HUD_POSITION_X, Field.HUD_POSITION_Y);
        // Colours tab: one node per colour, copying only that colour.
        for (DynamicMinimapColours.Key key : DynamicMinimapColours.Key.values()) {
            String path = COLOURS + "." + key.id;
            COLOUR_NODES.put(key, path);
            SETTING_NODES.add(new Node(path, null, (t, s) -> t.colours = t.colours.with(key, s.colours.rgb(key))));
        }
        Set<String> all = new LinkedHashSet<>();
        for (Node node : SETTING_NODES) all.add(node.path());
        Collections.addAll(all, SETTINGS, STATUS, HELP, RESET);
        ALL_PATHS = Collections.unmodifiableSet(all);
    }

    /** A setting node that copies the given settings fields (one, or both position axes). */
    private static void node(String path, String rowId, Field... fields) {
        Copier copier = fields.length == 1 ? fields[0]::copy : (t, s) -> { for (Field field : fields) field.copy(t, s); };
        SETTING_NODES.add(new Node(path, rowId, copier));
        if (rowId != null) ROW_NODES.put(rowId, path);
    }

    private SettingPermissions() {
    }

    /** Every player node path, setting and utility nodes alike (registered once at start-up). */
    public static Set<String> allPaths() {
        return ALL_PATHS;
    }

    /** True when {@code path} is one of DapperMap's player nodes. */
    public static boolean isNode(String path) {
        return ALL_PATHS.contains(path);
    }

    /** The node guarding a settings-page row, or null for rows that have none (headers, limits). */
    public static String forRow(String rowId) {
        return rowId == null ? null : ROW_NODES.get(rowId);
    }

    /** The node guarding one colour, e.g. {@code colours.map-edge}. */
    public static String forColour(DynamicMinimapColours.Key key) {
        return COLOUR_NODES.get(key);
    }

    /** True when at least one colour may be changed (opens the Colours tab and editor). */
    public static boolean anyColour(Predicate<String> allowed) {
        for (String path : COLOUR_NODES.values()) {
            if (allowed.test(path)) return true;
        }
        return false;
    }

    /** Permission check for a real player against the player command tree. */
    public static Predicate<String> forPlayer(PlayerRef playerRef) {
        return permission -> permission == null
                || playerRef.hasPermission(DapperMapPermissions.player(permission));
    }

    /** True when every setting may be edited, i.e. a full reset may drop the player override. */
    public static boolean hasAll(Predicate<String> allowed) {
        for (Node node : SETTING_NODES) {
            if (!allowed.test(node.path())) return false;
        }
        return true;
    }

    /**
     * Copies into {@code target} only the values from {@code source} that {@code allowed}
     * permits, then normalises. Denied settings keep the target's current value.
     */
    public static DynamicMinimapSetting merge(Predicate<String> allowed,
                                              DynamicMinimapSetting target,
                                              DynamicMinimapSetting source) {
        for (Node node : SETTING_NODES) {
            if (allowed.test(node.path())) node.copier().copy(target, source);
        }
        target.normalize();
        return target;
    }
}

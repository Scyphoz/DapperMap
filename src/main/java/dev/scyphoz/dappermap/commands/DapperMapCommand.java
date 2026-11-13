package dev.scyphoz.dappermap.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.PermissionQuery;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.scyphoz.dappermap.DapperMap;
import dev.scyphoz.dappermap.config.DapperMapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapColours;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapColours.Key;
import dev.scyphoz.dappermap.integration.BetterMapIntegration;
import dev.scyphoz.dappermap.pages.DapperMapSettingsPage;
import dev.scyphoz.dappermap.permissions.DapperMapPermissions;
import dev.scyphoz.dappermap.permissions.SettingPermissions;
import dev.scyphoz.dappermap.service.DapperMapSessionManager;
import dev.scyphoz.dappermap.service.DapperMapServerLoad;
import dev.scyphoz.dappermap.util.DapperMapUtil;
import dev.scyphoz.dappermap.util.DapperMapUtil.Lang;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting.*;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the player and administrator command trees, grouped like the settings page tabs:
 * {@code /mm display|markers|info|integration <setting> <value>}, plus {@code position},
 * {@code colours} and the general commands. Every setting is one row of {@link #SETTINGS}, shared
 * by both trees, so each change has exactly one command and one set of accepted values.
 *
 * <p>When a setting is added to the settings page, add its row here too (and to the README).
 */
public class DapperMapCommand extends AbstractPlayerCommand {
    private final DapperMapSessionManager sessions;

    public DapperMapCommand(String name, String description, DapperMapSessionManager sessions) {
        super(name, description);
        this.sessions = sessions;
        this.addAliases("dm", "minimap", "mm"); // Root forms: /dappermap, /dm, /minimap, /mm.
        // The bare command toggles the minimap (node command.toggle). hasPermission below also lets
        // players without it in, when they may use any sub-command; execute() checks toggle itself.
        this.requirePermission(DapperMapPermissions.player(SettingPermissions.TOGGLE));

        // The player-facing tree.
        registerCommands(this, Scope.PLAYER);

        // The admin tree is the same set of sub-commands, rooted under "admin" and
        // routed at the server defaults instead of the caller's personal settings.
        AdminDispatchCommand admin = new AdminDispatchCommand(this);
        registerCommands(admin, Scope.ADMIN);
        // Admin-only server controls (no player-tree twin): limits, stats and load shedding.
        // Descriptions are dappermap.lang keys: each player's game shows them in their language.
        admin.addSubCommand(new AdminToolCommand("limit", key("command.admin.limit.desc"), this));
        admin.addSubCommand(new AdminToolCommand("stats", key("command.admin.stats.desc"), this));
        admin.addSubCommand(new AdminToolCommand("load-shedding", key("command.admin.load-shedding.desc"), this));
        this.addSubCommand(admin);
    }

    // ---------------------------------------------------------------------------------
    // The setting table
    // ---------------------------------------------------------------------------------

    /** A change's confirmation text (after "DapperMap ", in the player's language) and whether anything was changed. */
    private record Result(boolean changed, String message) { }

    /** Applies one value; returns null when the value isn't one the setting accepts. {@code l}: reply language. */
    @FunctionalInterface
    private interface Applier {
        Result apply(DynamicMinimapSetting settings, String value, String extra, Player player, Lang l);
    }

    /** A full dappermap.lang key, as Hytale resolves command and argument descriptions on the client. */
    private static String key(String key) {
        return Lang.PREFIX + key;
    }

    /** Permission path below dappermap.command for a group and name ("display.zoom", "position"). */
    private static String nodeOf(String group, String name) {
        return group.isEmpty() ? name : group + "." + name;
    }

    /** A setting's name for confirmations, in the reply language (key "command.<node>"). */
    private static String settingName(Lang l, String group, String name, String description) {
        return l.get("command." + nodeOf(group, name), label(description));
    }

    /** "on"/"off" in the reply language. */
    private static String onOff(boolean value, Lang l) {
        return value ? l.get("reply.on", "on") : l.get("reply.off", "off");
    }

    /**
     * One command setting. {@code group} is its settings-tab command ("" for a root command) and
     * {@code values} the accepted values shown in usage and help. Its player permission is derived
     * from the command itself: {@code dappermap.command.<group>.<name>} (see {@link #node()}),
     * the same node as its settings-page control.
     */
    private record Setting(String group, String name, Arg arg, String values, String description,
                           Applier apply) {
        String path() { return group.isEmpty() ? name : group + " " + name; }
        /** Permission path below dappermap.command, e.g. "display.zoom" or "position". */
        String node() { return nodeOf(group, name); }
    }

    /** Argument shape of a setting command. */
    private enum Arg { WORD, NUMBER, TWO_NUMBERS }

    /** Setting groups in help order, with the settings tab they match. */
    private static final Map<String, String> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("display", "Display: map appearance, compass ring, font size and performance");
        GROUPS.put("markers", "Markers: players, creatures, death markers and marker size");
        GROUPS.put("info", "Info Panel: the panel and what it shows");
        GROUPS.put("integration", "Integrations: Hytale world map and BetterMap");
    }

    private static final List<Setting> SETTINGS = new ArrayList<>();
    static {
        // Display
        add("display", "zoom", Arg.WORD, "close|medium|far|<" + ZOOM_MIN + ".." + ZOOM_MAX + ">", "Zoom Radius (presets 48, 96, 160 blocks)", (s, v, e, p, l) -> {
            int zoom = switch (v) { case "close" -> 48; case "medium" -> 96; case "far" -> 160; default -> parseRanged(v, ZOOM_MIN, ZOOM_MAX); };
            if (zoom < 0) return null;
            s.zoomBlocks = zoom;
            return done(value(l, "display", "zoom", "Zoom Radius", l.format("unit.blocks", zoom + " blocks", "value", zoom)));
        });
        number("display", "size", SIZE_MIN, SIZE_MAX, "Map Size", (s, n) -> s.sizePixels = n, " px");
        number("display", "scale", SCALE_MIN, SCALE_MAX, "Interface Scale", (s, n) -> s.interfaceScalePercent = n, "%");
        named("display", "shape", "circle|square", SHAPE_NAMES, "Map Shape", "shape", (s, i) -> s.shape = i);
        named("display", "rotation", "north|player", ROTATION_NAMES, "Map Rotation: North Up or Player Facing", "rotation",
                (s, i) -> s.rotationMode = i);
        toggle("display", "compass-letters", "Compass Letters (N, E, S, W)", (s, on) -> s.showCompassLetters = on);
        toggle("display", "compass-dashes", "Compass Dashes (small dashes around the map edge)", (s, on) -> s.showCompassDashes = on);
        toggle("display", "cardinal-dashes", "Cardinal Dashes (large N, E, S, W dashes)", (s, on) -> s.showCardinalDashes = on);
        add("display", "speed", Arg.WORD, "very-fast|fast|normal|slow", "Update Speed", (s, v, e, p, l) -> {
            int speed = DapperMapUtil.indexOf(DapperMapConfig.SPEED_NAMES, v);
            if (speed < 0) return null;
            s.updateSpeed = speed;
            return done(value(l, "display", "speed", "Update Speed",
                    l.get("choice.speed." + speed, DapperMapConfig.SPEED_LABELS[speed])));
        });
        number("display", "resolution", RESOLUTION_MIN, RESOLUTION_MAX, "Texture Resolution", (s, n) -> s.textureResolution = n, " px");
        // Font Size (percent; 100 is the standard size)
        percent("display", "font-title", FONT_SCALE_MIN, FONT_SCALE_MAX, FONT_SCALE_STEP, "Font Size: Panel Title", (s, n) -> s.fontTitle = n);
        percent("display", "font-headings", FONT_SCALE_MIN, FONT_SCALE_MAX, FONT_SCALE_STEP, "Font Size: Panel Headings", (s, n) -> s.fontHeadings = n);
        percent("display", "font-values", FONT_SCALE_MIN, FONT_SCALE_MAX, FONT_SCALE_STEP, "Font Size: Panel Values", (s, n) -> s.fontValues = n);
        percent("display", "font-compass", FONT_SCALE_MIN, FONT_SCALE_MAX, FONT_SCALE_STEP, "Font Size: Compass Letters", (s, n) -> s.fontCompass = n);
        // Markers
        toggle("markers", "players", "Player Markers", (s, on) -> s.showOtherPlayers = on);
        choice("markers", "player-style", "icons", "dots", "Player Marker Style", "style", (s, first) -> s.playerIcons = first);
        toggle("markers", "player-helmets", "Player Icon Helmets", (s, on) -> s.playerIconHelmets = on);
        toggle("markers", "creatures", "Creature Markers", (s, on) -> s.showEntities = on);
        choice("markers", "creature-style", "icons", "dots", "Creature Marker Style", "style", (s, first) -> s.creatureIcons = first);
        choice("markers", "creature-filter", "all", "hostile", "Creature Filter: all creatures, or only hostile and attacking ones",
                "creature_filter", (s, first) -> s.hostileCreaturesOnly = !first);
        add("markers", "creature-limit", Arg.NUMBER, "16|32|64", "Creature Marker Limit", (s, v, e, p, l) -> {
            int limit = parseRanged(v, 16, 64);
            if (!isCreatureLimit(limit)) return null;
            s.creatureMarkerLimit = limit;
            return done(value(l, "markers", "creature-limit", "Creature Marker Limit", Integer.toString(limit)));
        });
        toggle("markers", "height-filter", "Creature Height Filter", (s, on) -> s.limitMobHeight = on);
        number("markers", "height-range", HEIGHT_RANGE_MIN, HEIGHT_RANGE_MAX, "Height Filter Range (blocks above and below you)",
                (s, n) -> s.mobVerticalRange = n, " blocks");
        named("markers", "critters", "all|ground|flying|both", CRITTER_NAMES,
                "Critter Markers (hide harmless ground critters, flying critters, or both)", "critters",
                (s, i) -> s.critterFilter = i);
        toggle("markers", "memories", "Uncollected Memories", (s, on) -> s.memoryHints = on);
        toggle("markers", "death", "Death Markers", (s, on) -> s.showDeathMarker = on);
        // Marker Size (percent; 100 is the standard size)
        percent("markers", "size-creatures", MARKER_SCALE_MIN, MARKER_SCALE_MAX, MARKER_SCALE_STEP, "Marker Size: Creatures", (s, n) -> s.markerCreatures = n);
        percent("markers", "size-players", MARKER_SCALE_MIN, MARKER_SCALE_MAX, MARKER_SCALE_STEP, "Marker Size: Players", (s, n) -> s.markerPlayers = n);
        percent("markers", "size-map", MARKER_SCALE_MIN, MARKER_SCALE_MAX, MARKER_SCALE_STEP, "Marker Size: Map Markers (Hytale and BetterMap)", (s, n) -> s.markerMap = n);
        percent("markers", "size-arrow", MARKER_SCALE_MIN, MARKER_SCALE_MAX, MARKER_SCALE_STEP, "Marker Size: Your Arrow", (s, n) -> s.markerArrow = n);
        // Info Panel
        toggle("info", "panel", "Info Panel", (s, on) -> s.infoPanelEnabled = on);
        named("info", "position", "below|above|left|right", PANEL_POSITION_NAMES, "Info Panel Position",
                "panel_position", (s, i) -> s.infoPanelPosition = i);
        named("info", "alignment", "left|centre|right", PANEL_ALIGNMENT_NAMES, "Info Panel Alignment",
                "panel_alignment", (s, i) -> s.infoPanelTextAlignment = i);
        toggle("info", "coordinates", "Coordinates", (s, on) -> s.showCoordinates = on);
        toggle("info", "bearing", "Bearing", (s, on) -> s.showCompass = on);
        toggle("info", "world-time", "World Time", (s, on) -> s.showClock = on);
        // Its own command (and permission) since 3.2.0; world-time no longer takes 12|24.
        add("info", "clock-format", Arg.NUMBER, "12|24", "Clock Format: 12-hour or 24-hour", (s, v, e, p, l) -> {
            if (!v.equals("12") && !v.equals("24")) return null;
            s.clock24Hour = v.equals("24");
            return done(value(l, "info", "clock-format", "Clock Format",
                    l.get("choice.clock_format." + s.clock24Hour, v + "-hour")));
        });
        toggle("info", "region", "Region", (s, on) -> s.showBiome = on);
        // Integrations
        toggle("integration", "worldmap", "Hytale World Map Markers", (s, on) -> s.showWorldMapMarkers = on);
        add("integration", "bettermap", Arg.WORD, "on|off", "BetterMap Integration", (s, v, e, p, l) -> {
            Boolean on = parseOnOff(v);
            if (on == null) return null;
            if (on && !BetterMapIntegration.isAvailable())
                return new Result(false, l.get("reply.bettermap.missing", "BetterMap integration unchanged: BetterMap was not detected."));
            s.betterMapEnabled = on;
            return done(toggled(l, "integration", "bettermap", "BetterMap Integration", on));
        });
        add("integration", "bettermap-markers", Arg.WORD, "on|off", "BetterMap Markers", (s, v, e, p, l) -> {
            Boolean on = parseOnOff(v);
            if (on == null) return null;
            if (!s.betterMapEnabled || !BetterMapIntegration.isAvailable())
                return new Result(false, l.get("reply.bettermap.markers", "BetterMap markers unchanged: turn the BetterMap integration on first."));
            s.betterMapMarkersEnabled = on;
            return done(toggled(l, "integration", "bettermap-markers", "BetterMap Markers", on));
        });
        add("integration", "bettermap-caves", Arg.WORD, "on|off", "BetterMap Cave Mode (BetterMap cave mode must be active)", (s, v, e, p, l) -> {
            Boolean on = parseOnOff(v);
            if (on == null) return null;
            if (!s.betterMapEnabled || !BetterMapIntegration.isAvailable())
                return new Result(false, l.get("reply.bettermap.caves", "BetterMap caves unchanged: turn the BetterMap integration on first."));
            if (!BetterMapIntegration.isCaveModeEnabled(p))
                return new Result(false, l.get("reply.bettermap.cave_mode", "BetterMap caves unchanged: turn on BetterMap cave mode first."));
            s.betterMapCaveModeEnabled = on;
            return done(toggled(l, "integration", "bettermap-caves", "BetterMap Cave Mode", on));
        });
        // Position (its own tab, a root command)
        add("", "position", Arg.TWO_NUMBERS, "<x 0..100> <y 0..100>", "Place the minimap on screen", (s, v, e, p, l) -> {
            int x = parseRanged(v, POSITION_MIN, POSITION_MAX), y = parseRanged(e, POSITION_MIN, POSITION_MAX);
            if (x < 0 || y < 0) return null;
            s.hudPositionX = x;
            s.hudPositionY = y;
            return done(l.format("reply.position", "Position: x={x}, y={y}.", "x", x, "y", y));
        });
        // Every command must have a node in the permission table (fails at start-up, not in play).
        for (Setting setting : SETTINGS) {
            if (!SettingPermissions.isNode(setting.node()))
                throw new IllegalStateException("DapperMap command '" + setting.path() + "' has no permission node");
        }
    }

    private static void add(String group, String name, Arg arg, String values, String description, Applier apply) {
        SETTINGS.add(new Setting(group, name, arg, values, description, apply));
    }

    /** An on|off setting. */
    private static void toggle(String group, String name, String description,
                               java.util.function.BiConsumer<DynamicMinimapSetting, Boolean> set) {
        add(group, name, Arg.WORD, "on|off", description, (s, v, e, p, l) -> {
            Boolean on = parseOnOff(v);
            if (on == null) return null;
            set.accept(s, on);
            return done(toggled(l, group, name, description, on));
        });
    }

    /**
     * A setting with two named values; {@code set} receives true for the first. The confirmation
     * names the value as the settings page does (key "choice.<choiceSet>.<value>").
     */
    private static void choice(String group, String name, String first, String second, String description,
                               String choiceSet, java.util.function.BiConsumer<DynamicMinimapSetting, Boolean> set) {
        add(group, name, Arg.WORD, first + "|" + second, description, (s, v, e, p, l) -> {
            if (!v.equals(first) && !v.equals(second)) return null;
            set.accept(s, v.equals(first));
            return done(value(l, group, name, description, l.get("choice." + choiceSet + "." + v, v)));
        });
    }

    /**
     * A setting chosen by name; {@code names} are indexed by the setting's value, and
     * {@code values} is the usage text. Confirms with "Setting Name: Value.", the value named as on
     * the settings page (key "choice.<choiceSet>.<index>": the page uses the same index order).
     */
    private static void named(String group, String name, String values, String[] names, String description,
                              String choiceSet, java.util.function.ObjIntConsumer<DynamicMinimapSetting> set) {
        add(group, name, Arg.WORD, values, description, (s, v, e, p, l) -> {
            int index = DapperMapUtil.indexOf(names, v);
            if (index < 0) return null;
            set.accept(s, index);
            return done(value(l, group, name, description, l.get("choice." + choiceSet + "." + index, v)));
        });
    }

    /** A whole-number setting within [min, max]. */
    private static void number(String group, String name, int min, int max, String description,
                               java.util.function.ObjIntConsumer<DynamicMinimapSetting> set, String unit) {
        // Unit keys shared with the settings page: unit.px, unit.percent, unit.blocks.
        String unitKey = switch (unit) { case " px" -> "unit.px"; case "%" -> "unit.percent"; default -> "unit.blocks"; };
        add(group, name, Arg.NUMBER, "<" + min + ".." + max + ">", description, (s, v, e, p, l) -> {
            int n = parseRanged(v, min, max);
            if (n < 0) return null;
            set.accept(s, n);
            return done(value(l, group, name, description, l.format(unitKey, n + unit, "value", n)));
        });
    }

    /**
     * A size percentage within [min, max]; values between steps are rounded to the nearest
     * step (e.g. 112 -> 110 for fonts). Confirms with "Font Size: Panel Title: 110%.".
     */
    private static void percent(String group, String name, int min, int max, int step, String description,
                                java.util.function.ObjIntConsumer<DynamicMinimapSetting> set) {
        add(group, name, Arg.NUMBER, "<" + min + ".." + max + ">", description, (s, v, e, p, l) -> {
            int n = parseRanged(v, min, max);
            if (n < 0) return null;
            int snapped = DynamicMinimapSetting.snapScale(n, min, max, step);
            set.accept(s, snapped);
            // The full name ("Font Size: Panel Title"): the area is part of what changed.
            return done(l.format("reply.value", "{setting}: {value}.", "setting", l.get("command." + nodeOf(group, name), description),
                    "value", l.format("unit.percent", snapped + "%", "value", snapped)));
        });
    }

    /** The setting's page name from its description, e.g. "Compass Letters" from "Compass Letters (N, E, S, W)". */
    private static String label(String description) {
        return description.split("[(:;]")[0].trim();
    }

    private static Result done(String message) {
        return new Result(true, message);
    }

    /** "Zoom Radius: 96 blocks." in the reply language. */
    private static String value(Lang l, String group, String name, String description, String value) {
        return l.format("reply.value", "{setting}: {value}.", "setting", settingName(l, group, name, description), "value", value);
    }

    /** "Compass Letters on." in the reply language. */
    private static String toggled(Lang l, String group, String name, String description, boolean on) {
        return l.format("reply.toggle", "{setting} {state}.", "setting", settingName(l, group, name, description),
                "state", onOff(on, l));
    }


    // ---------------------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------------------

    /**
     * Registers the whole tree onto {@code parent}: once for players, once under "admin", so the
     * two can never drift apart. Grouped commands sit under a container per settings tab.
     */
    private void registerCommands(AbstractCommand parent, Scope scope) {
        for (String simple : new String[]{"settings", "on", "off", "status", "help", "reset"})
            parent.addSubCommand(new SimpleCommand(simple, key("command." + simple + ".desc"), this, scope));
        parent.addSubCommand(new ColoursCommand(this, scope));
        Map<String, GroupCommand> groups = new LinkedHashMap<>();
        for (Map.Entry<String, String> group : GROUPS.entrySet())
            groups.put(group.getKey(), new GroupCommand(group.getKey(), key("command.group." + group.getKey() + ".desc"), this, scope));
        for (Setting setting : SETTINGS) {
            SettingCommand command = new SettingCommand(setting, this, scope);
            if (setting.group().isEmpty()) parent.addSubCommand(command);
            else groups.get(setting.group()).addSubCommand(command);
        }
        groups.values().forEach(parent::addSubCommand);
    }

    /**
     * Player node for a non-setting command: on/off share "toggle" (as the settings page's
     * Minimap switch); settings, status, help and reset use their own name.
     */
    private static String commandNode(String command) {
        return command.equals("on") || command.equals("off") ? SettingPermissions.TOGGLE : command;
    }

    /** Admin scope requires the admin node; player scope the given player node. */
    private static PermissionQuery gateFor(Scope scope, String playerNode) {
        // Admin: the "admin" container already gates this subtree, but requiring it again on
        // every leaf means each admin sub-command is safe to reason about on its own.
        return scope == Scope.ADMIN ? DapperMapPermissions.ADMIN : DapperMapPermissions.player(playerNode);
    }

    /**
     * True when the sender may run at least one of {@code command}'s sub-commands. Containers
     * (the root and each settings group) use it so they stay usable, and listed in the client's
     * command list, for a player granted only some settings. Stops at the first allowed one.
     */
    private static boolean anySubCommandAllowed(AbstractCommand command, CommandSender sender) {
        for (AbstractCommand sub : command.getSubCommands().values()) {
            if (sub.hasPermission(sender)) return true;
        }
        return false;
    }

    /** Which settings store a command writes to. */
    private enum Scope { PLAYER, ADMIN }

    // ---------------------------------------------------------------------------------
    // Running commands
    // ---------------------------------------------------------------------------------

    /**
     * Allowed for the toggle node, or for any sub-command. Hytale's default check would also
     * require nothing more here (this is the root), but sub-commands no longer inherit this check:
     * see {@link GatedCommand#hasPermission}.
     */
    @Override
    public boolean hasPermission(@Nonnull CommandSender sender) {
        return sender.hasPermission(DapperMapPermissions.player(SettingPermissions.TOGGLE))
                || anySubCommandAllowed(this, sender);
    }

    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        // hasPermission lets in players with any sub-command, so the bare toggle checks its own node.
        Lang l = Lang.of(playerRef);
        if (!playerRef.hasPermission(DapperMapPermissions.player(SettingPermissions.TOGGLE))) {
            context.sendMessage(Message.raw(l.get("reply.toggle_denied",
                    "You do not have permission to show or hide DapperMap. Use /dappermap help for your commands.")));
            return;
        }
        boolean enabled = this.sessions.toggle(playerRef, DapperMap.config());
        context.sendMessage(Message.raw(shownHidden(l, enabled)));
    }

    /** One setting command, for a player's own settings or (admin) the server defaults. */
    private void runSetting(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef,
                            Scope scope, Setting setting, String value, String extra) {
        DapperMapConfig config = DapperMap.config();
        Player player = store.getComponent(ref, Player.getComponentType());
        Lang l = Lang.of(playerRef); // Replies in the player's language; command words stay English.
        value = value.toLowerCase(java.util.Locale.ROOT);
        extra = extra.toLowerCase(java.util.Locale.ROOT);
        String prefix = scope == Scope.ADMIN ? "/dappermap admin " : "/dappermap ";
        if (scope == Scope.ADMIN) {
            if (!requireAdmin(context, playerRef, l)) return;
            DynamicMinimapSetting defaults = this.sessions.getAdminDefaults(config).copy();
            Result result = setting.apply().apply(defaults, value, extra, player, l);
            if (result == null) { context.sendMessage(Message.raw(usage(l, prefix + setting.path() + " " + setting.values()))); return; }
            if (result.changed()) {
                defaults.normalize();
                this.sessions.setAdminDefaults(defaults);
            }
            context.sendMessage(Message.raw(l.format("reply.prefix.admin", "DapperMap admin default {message}", "message", result.message())));
            return;
        }
        DynamicMinimapSetting settings = this.sessions.getSettings(playerRef.getUuid(), config);
        Result result = setting.apply().apply(settings, value, extra, player, l);
        if (result == null) { context.sendMessage(Message.raw(usage(l, prefix + setting.path() + " " + setting.values()))); return; }
        if (result.changed()) this.sessions.applySettings(playerRef, settings);
        context.sendMessage(Message.raw(l.format("reply.prefix", "DapperMap {message}", "message", result.message())
                + (DapperMapConfig.Limit.bySettingCommand(setting.name()) != null ? limitNote(settings, l) : "")));
    }

    /** General commands (settings, on, off, status, help, reset) for either scope. */
    private void runSimple(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef,
                           Scope scope, String command) {
        DapperMapConfig config = DapperMap.config();
        Player player = store.getComponent(ref, Player.getComponentType());
        Lang l = Lang.of(playerRef);
        if (scope == Scope.ADMIN) {
            if (!requireAdmin(context, playerRef, l)) return;
            switch (command) {
                case "settings" -> openAdminPage(store, ref, playerRef, player, config);
                case "help" -> {
                    showHelp(context, "/dappermap admin ", node -> true, l);
                    context.sendMessage(Message.raw(l.get("reply.admin.help", "Admin defaults take the same settings and values as the player commands.")));
                    context.sendMessage(Message.raw(l.format("reply.admin.server", "Server: {usage}", "usage",
                            "/dappermap admin limit [speed|resolution|zoom|creatures <value|none> | colours <off|32..256>] | /dappermap admin stats | /dappermap admin load-shedding on|off")));
                }
                case "status" -> {
                    for (String line : status(this.sessions.getAdminDefaults(config)))
                        context.sendMessage(Message.raw(l.format("reply.admin.status", "Admin defaults {line}", "line", line)));
                    context.sendMessage(Message.raw(l.format("reply.admin.server_status",
                            "Server limits: {limits}; terrain colours {colours}; load shedding {shedding}.",
                            "limits", config.describeLimits(l),
                            "colours", DapperMapConfig.terrainColoursText(config.getTerrainColours(), l),
                            "shedding", onOff(config.isLoadShedding(), l))));
                }
                case "reset" -> {
                    this.sessions.resetAdminDefaults(config);
                    context.sendMessage(Message.raw(l.get("reply.admin.reset", "DapperMap admin defaults reset to mod defaults.")));
                }
                default -> { // on, off
                    DynamicMinimapSetting defaults = this.sessions.getAdminDefaults(config).copy();
                    defaults.enabled = command.equals("on");
                    this.sessions.setAdminDefaults(defaults);
                    context.sendMessage(Message.raw(l.format("reply.admin.minimap", "DapperMap admin default: minimap {state}.",
                            "state", onOff(defaults.enabled, l))));
                }
            }
            return;
        }
        DynamicMinimapSetting settings = this.sessions.getSettings(playerRef.getUuid(), config);
        switch (command) {
            case "settings" -> player.getPageManager().openCustomPage(ref, store, new DapperMapSettingsPage(playerRef, this.sessions, config));
            case "help" -> showHelp(context, "/dappermap ", SettingPermissions.forPlayer(playerRef), l);
            case "status" -> {
                for (String line : status(settings)) context.sendMessage(Message.raw(line));
                String note = limitNote(settings, l);
                if (!note.isEmpty()) context.sendMessage(Message.raw(note.trim()));
            }
            case "reset" -> {
                // Same behaviour as the settings page's Reset: return to the current server
                // defaults. With every setting permission the personal override is removed,
                // so future admin-default changes apply again; otherwise only permitted
                // settings are reset and the rest are kept.
                var allowed = SettingPermissions.forPlayer(playerRef);
                if (SettingPermissions.hasAll(allowed)) {
                    this.sessions.resetSettings(playerRef, config);
                } else {
                    DynamicMinimapSetting live = settings.copy();
                    this.sessions.applySettings(playerRef,
                            SettingPermissions.merge(allowed, live, this.sessions.getAdminDefaults(config)));
                }
                context.sendMessage(Message.raw(l.get("reply.reset", "DapperMap settings reset to the server defaults.")));
            }
            default -> { // on, off
                settings.enabled = command.equals("on");
                this.sessions.applySettings(playerRef, settings);
                context.sendMessage(Message.raw(shownHidden(l, settings.enabled)));
            }
        }
    }

    /** True when the player holds the admin node; otherwise tells them and returns false. */
    private static boolean requireAdmin(CommandContext context, PlayerRef playerRef, Lang l) {
        if (playerRef.hasPermission(DapperMapPermissions.ADMIN)) return true;
        context.sendMessage(Message.raw(l.get("reply.admin.denied", "You do not have permission to manage DapperMap server defaults.")));
        return false;
    }

    /** "DapperMap shown." / "DapperMap hidden." in the reply language. */
    private static String shownHidden(Lang l, boolean shown) {
        return shown ? l.get("reply.shown", "DapperMap shown.") : l.get("reply.hidden", "DapperMap hidden.");
    }

    /** "Usage: /dappermap ..." (the command syntax itself stays English). */
    private static String usage(Lang l, String syntax) {
        return l.format("reply.usage", "Usage: {usage}", "usage", syntax);
    }

    /** Opens the admin settings page; "Save Changes" then closes it (opened by command). */
    private void openAdminPage(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, Player player,
                               DapperMapConfig config) {
        this.sessions.setAdminPageOrigin(playerRef.getUuid(), false);
        if (player != null) player.getPageManager().openCustomPage(ref, store,
                new DapperMapSettingsPage(playerRef, this.sessions, config, true));
    }

    /** {@code colours [list | <target> <#RRGGBB|R G B> | reset <target|all>]} for either scope. */
    private void runColours(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef,
                            Scope scope, String arguments) {
        DapperMapConfig config = DapperMap.config();
        Lang l = Lang.of(playerRef);
        String args = arguments.trim().toLowerCase(java.util.Locale.ROOT);
        if (scope == Scope.ADMIN) {
            if (!requireAdmin(context, playerRef, l)) return;
            if (args.isEmpty()) { context.sendMessage(Message.raw(coloursUsage(l, "/dappermap admin "))); return; }
            DynamicMinimapSetting defaults = this.sessions.getAdminDefaults(config).copy();
            var result = executeColourCommand(defaults, args, node -> true, l); // Admin: every colour.
            if (result.changed()) this.sessions.setAdminDefaults(defaults);
            context.sendMessage(Message.raw(result.message()));
            return;
        }
        DynamicMinimapSetting settings = this.sessions.getSettings(playerRef.getUuid(), config);
        if (args.isEmpty()) {
            if (!settings.enabled) { context.sendMessage(Message.raw(l.get("reply.colours.enable", "Enable the minimap before opening Colours."))); return; }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) player.getPageManager().openCustomPage(ref, store, DapperMapSettingsPage.colours(playerRef, this.sessions, config));
            return;
        }
        // Each colour has its own node (colours.<target>); list and the editor need any one of them.
        var result = executeColourCommand(settings, args, SettingPermissions.forPlayer(playerRef), l);
        if (result.changed()) this.sessions.applySettings(playerRef, settings);
        context.sendMessage(Message.raw(result.message()));
    }

    /** Admin-only server tools: limit, stats and load-shedding. */
    private void runAdminTool(CommandContext context, PlayerRef playerRef, String tool, String arguments) {
        Lang l = Lang.of(playerRef);
        if (!requireAdmin(context, playerRef, l)) return;
        DapperMapConfig config = DapperMap.config();
        switch (tool) {
            case "limit" -> context.sendMessage(Message.raw(this.adminLimit(arguments.trim(), l)));
            // Stats stay English: a diagnostic report of figures and internal names.
            case "stats" -> { for (String line : DapperMapServerLoad.report(this.sessions, config)) context.sendMessage(Message.raw(line)); }
            default -> context.sendMessage(Message.raw(this.adminLoadShedding(arguments.trim().toLowerCase(java.util.Locale.ROOT), l)));
        }
    }

    // ---------------------------------------------------------------------------------
    // Help, status and helpers
    // ---------------------------------------------------------------------------------

    /** "Colours: /dappermap colours | ..." for {@code prefix} ("/dappermap " or "/dappermap admin "). */
    private static String coloursUsage(Lang l, String prefix) {
        return l.get("command.group.colours", "Colours") + ": " + (prefix + "colours | " + prefix + "colours list | "
                + prefix + "colours <target> <#RRGGBB|R G B> | " + prefix + "colours reset <target|all>");
    }

    /**
     * Every command the caller may use, grouped like the settings tabs. {@code prefix} is
     * "/dappermap " or "/dappermap admin "; {@code allowed} tests a player node path (always true
     * for admin help, whose commands are all covered by the admin node).
     */
    private static void showHelp(CommandContext context, String prefix, java.util.function.Predicate<String> allowed, Lang l) {
        context.sendMessage(Message.raw(l.get("reply.help.title", "DapperMap commands (/dappermap, /dm, /minimap or /mm):")));
        if (prefix.equals("/dappermap ")) {
            // General commands, each shown only when its node is granted.
            List<String> general = new ArrayList<>(6);
            if (allowed.test(SettingPermissions.TOGGLE)) general.add("/dappermap | /dappermap on|off");
            for (String command : new String[]{SettingPermissions.STATUS, SettingPermissions.SETTINGS,
                    SettingPermissions.RESET, SettingPermissions.HELP}) {
                if (allowed.test(command)) general.add("/dappermap " + command);
            }
            if (!general.isEmpty()) context.sendMessage(Message.raw(String.join(" | ", general)));
        } else {
            context.sendMessage(Message.raw("/dappermap admin | /dappermap admin on|off | /dappermap admin status | /dappermap admin reset | /dappermap admin help"));
        }
        for (String group : GROUPS.keySet()) {
            String line = groupHelp(prefix, group, allowed, l);
            if (line != null) context.sendMessage(Message.raw(line));
        }
        if (allowed.test(SettingPermissions.POSITION))
            context.sendMessage(Message.raw(l.get("command.group.position", "Position") + ": " + prefix + "position <x 0..100> <y 0..100>"));
        if (SettingPermissions.anyColour(allowed))
            context.sendMessage(Message.raw(coloursUsage(l, prefix)));
    }

    /**
     * "Display: /mm display zoom close|... | size <120..360> | ..." for one group, listing only
     * the settings {@code allowed} permits; null when it permits none of them.
     */
    private static String groupHelp(String prefix, String group, java.util.function.Predicate<String> allowed, Lang l) {
        String title = GROUPS.get(group);
        // The group's title ("Display") in the reply language; the command syntax stays English.
        StringBuilder out = new StringBuilder(l.get("command.group." + group, title.substring(0, title.indexOf(':'))))
                .append(": ").append(prefix).append(group).append(' ');
        boolean first = true;
        for (Setting setting : SETTINGS) {
            if (!setting.group().equals(group) || !allowed.test(setting.node())) continue;
            if (!first) out.append(" | ");
            out.append(setting.name()).append(' ').append(setting.values());
            first = false;
        }
        return first ? null : out.toString();
    }

    /** Every setting, grouped and named exactly as its command takes it. */
    private static List<String> status(DynamicMinimapSetting s) {
        return List.of(
                "DapperMap: " + onOff(s.enabled) + ", position " + s.hudPositionX + " " + s.hudPositionY,
                "display: zoom " + s.zoomBlocks + ", size " + s.sizePixels + ", scale " + s.interfaceScalePercent
                        + ", shape " + DapperMapUtil.nameAt(SHAPE_NAMES, s.shape)
                        + ", rotation " + DapperMapUtil.nameAt(ROTATION_NAMES, s.rotationMode)
                        + ", compass-letters " + onOff(s.showCompassLetters) + ", compass-dashes " + onOff(s.showCompassDashes)
                        + ", cardinal-dashes " + onOff(s.showCardinalDashes)
                        + ", speed " + s.updateSpeedName() + ", resolution " + s.textureResolution
                        + ", font-title " + s.fontTitle + ", font-headings " + s.fontHeadings
                        + ", font-values " + s.fontValues + ", font-compass " + s.fontCompass,
                "markers: players " + onOff(s.showOtherPlayers) + ", player-style " + (s.playerIcons ? "icons" : "dots")
                        + ", player-helmets " + onOff(s.playerIconHelmets) + ", creatures " + onOff(s.showEntities)
                        + ", creature-style " + (s.creatureIcons ? "icons" : "dots")
                        + ", creature-filter " + (s.hostileCreaturesOnly ? "hostile" : "all")
                        + ", creature-limit " + s.creatureMarkerLimit + ", height-filter " + onOff(s.limitMobHeight)
                        + ", height-range " + s.mobVerticalRange + ", critters " + DapperMapUtil.nameAt(CRITTER_NAMES, s.critterFilter)
                        + ", memories " + onOff(s.memoryHints)
                        + ", death " + onOff(s.showDeathMarker)
                        + ", size-creatures " + s.markerCreatures + ", size-players " + s.markerPlayers
                        + ", size-map " + s.markerMap
                        + ", size-arrow " + s.markerArrow,
                "info: panel " + onOff(s.infoPanelEnabled) + ", position " + DapperMapUtil.nameAt(PANEL_POSITION_NAMES, s.infoPanelPosition)
                        + ", alignment " + DapperMapUtil.nameAt(PANEL_ALIGNMENT_NAMES, s.infoPanelTextAlignment)
                        + ", coordinates " + onOff(s.showCoordinates) + ", bearing " + onOff(s.showCompass)
                        + ", world-time " + onOff(s.showClock) + ", clock-format " + (s.clock24Hour ? "24" : "12")
                        + ", region " + onOff(s.showBiome),
                "integration: worldmap " + onOff(s.showWorldMapMarkers) + ", bettermap " + onOff(s.betterMapEnabled)
                        + ", bettermap-markers " + onOff(s.betterMapMarkersEnabled)
                        + ", bettermap-caves " + onOff(s.betterMapCaveModeEnabled));
    }

    /** " (server limit applies: ...)" when an admin limit holds this player below their own choice. */
    private static String limitNote(DynamicMinimapSetting settings, Lang l) {
        String applied = DapperMap.config().describeLimitsApplied(settings, l);
        return applied.isEmpty() ? "" : " " + l.format("reply.limit_note", "(server limit applies: {limits})", "limits", applied);
    }

    /**
     * {@code /mm admin limit [speed|resolution|zoom|creatures <value|none> | colours <off|32..256>]}. Saves the config and
     * re-applies the limits to every live minimap; players' saved settings are kept.
     */
    private String adminLimit(String arguments, Lang l) {
        DapperMapConfig config = DapperMap.config();
        String usage = usage(l, "/dappermap admin limit speed very-fast|fast|normal|slow|none"
                + " | resolution <256..1024>|none | zoom <16..256>|none | creatures 16|32|64|none"
                + " | colours off|" + terrainColourValues());
        if (arguments.isBlank()) return limitsSummary(config, l) + ".";
        String[] parts = arguments.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        if (parts.length != 2) return usage;
        if (parts[0].equals("colours")) return this.adminTerrainColours(parts[1], usage, l);
        DapperMapConfig.Limit limit = DapperMapConfig.Limit.byCommand(parts[0]);
        if (limit == null) return usage;
        // "none" lifts the limit (its widest value); anything else must be a value the limit accepts.
        int parsed = parts[1].equals("none") ? limit.noLimit : limit.parse(parts[1]);
        if (parsed < 0) return usage;
        config.setLimit(limit, parsed);
        DapperMap.saveConfig();
        this.sessions.refreshLiveSettings(); // Applies on each minimap's next tick.
        return limitsSummary(config, l) + " " + l.format("reply.limits.players", "({count} player(s) currently limited).",
                "count", this.sessions.limitedPlayerCount());
    }

    /** "DapperMap server limits: ...; terrain colours off" for the limit command's replies. */
    private static String limitsSummary(DapperMapConfig config, Lang l) {
        return l.format("reply.limits.summary", "DapperMap server limits: {limits}; terrain colours {colours}",
                "limits", config.describeLimits(l), "colours", DapperMapConfig.terrainColoursText(config.getTerrainColours(), l));
    }

    /** "32|64|96|128|192|256": the colour counts the command and the admin page offer. */
    private static String terrainColourValues() {
        StringBuilder out = new StringBuilder();
        for (int value : DapperMapConfig.TERRAIN_COLOUR_CHOICES) {
            if (value == 0) continue; // "off" is listed by name.
            if (!out.isEmpty()) out.append('|');
            out.append(value);
        }
        return out.toString();
    }

    /**
     * {@code /mm admin limit colours off|32..256}: the server-wide terrain colour reduction. Saves
     * the config and redraws every live minimap's terrain with the new setting.
     */
    private String adminTerrainColours(String value, String usage, Lang l) {
        int colours = -1;
        if (value.equals("off")) colours = 0;
        else for (int choice : DapperMapConfig.TERRAIN_COLOUR_CHOICES) if (choice > 0 && Integer.toString(choice).equals(value)) colours = choice;
        if (colours < 0) return usage;
        DapperMapConfig config = DapperMap.config();
        if (config.getTerrainColours() != colours) {
            DapperMap.applyTerrainColours(colours);
            this.sessions.redrawAllTerrain();
        }
        return limitsSummary(config, l) + ".";
    }

    /** {@code /mm admin load-shedding on|off}; no value shows the current state. */
    private String adminLoadShedding(String value, Lang l) {
        DapperMapConfig config = DapperMap.config();
        Boolean enabled = parseOnOff(value);
        if (enabled == null) {
            if (!value.isEmpty()) return usage(l, "/dappermap admin load-shedding on|off");
            String state = onOff(config.isLoadShedding(), l);
            return (DapperMapServerLoad.sheddingLevel() > 0
                    ? l.format("reply.shedding.active", "DapperMap load shedding is {state} and currently active.", "state", state)
                    : l.format("reply.shedding.state", "DapperMap load shedding is {state}.", "state", state))
                    + " " + l.get("reply.shedding.thresholds",
                    "Thresholds are set in dappermap_minimap.json (LoadSheddingRejectionsPerMinute, LoadSheddingPlayers).");
        }
        config.setLoadShedding(enabled);
        DapperMap.saveConfig();
        return enabled
                ? l.get("reply.shedding.on", "DapperMap load shedding on (live minimaps step down one update speed while the server is busy).")
                : l.get("reply.shedding.off", "DapperMap load shedding off.");
    }

    /**
     * {@code list | <target> <#RRGGBB|R G B> | reset <target|all>} against {@code settings}.
     * {@code allowed} tests a colour's node ({@code colours.<target>}): a denied colour is never
     * changed, and {@code reset all} resets only the colours that may be changed.
     */
    private static Result executeColourCommand(DynamicMinimapSetting settings, String arguments,
                                               java.util.function.Predicate<String> allowed, Lang l) {
        String[] parts = arguments.trim().split("\\s+", 2);
        String target = parts[0];

        if (target.equals("list")) {
            // Locked colours are still listed (read-only), marked so the player knows why a change fails.
            String locked = " " + l.get("reply.colours.locked_tag", "[locked]");
            return new Result(false, l.get("reply.colours.list", "DapperMap colours:") + "\n" + Arrays.stream(Key.values())
                    .map(key -> key.id + " " + settings.colours.hex(key) + " (" + colourEntry(l, key) + ")"
                            + (allowed.test(SettingPermissions.forColour(key)) ? "" : locked))
                    .collect(Collectors.joining("\n")));
        }

        if (target.equals("reset")) {
            String name = parts.length > 1 ? parts[1].trim() : "";
            if (name.equals("all")) {
                int kept = 0;
                DynamicMinimapColours colours = settings.colours;
                for (Key key : Key.values()) {
                    if (allowed.test(SettingPermissions.forColour(key))) colours = colours.reset(key);
                    else kept++;
                }
                settings.colours = colours;
                return new Result(true, kept == 0 ? l.get("reply.colours.reset_all", "DapperMap colours reset to defaults.")
                        : l.format("reply.colours.reset_some",
                        "DapperMap colours you may change were reset to defaults ({count} locked colour(s) kept).", "count", kept));
            }
            Key key = Key.find(name);
            if (key == null) {
                return new Result(false, usage(l, "/dappermap colours reset <target|all>.") + " "
                        + l.get("reply.colours.targets", "Use /dappermap colours list for targets."));
            }
            if (!allowed.test(SettingPermissions.forColour(key))) return lockedColour(key, l);
            settings.colours = settings.colours.reset(key);
            return new Result(true, colourSet(l, key, settings));
        }

        Key key = Key.find(target);
        if (key == null || parts.length != 2) {
            return new Result(false, usage(l, "/dappermap colours <target> <#RRGGBB|R G B>.") + " "
                    + l.get("reply.colours.targets", "Use /dappermap colours list for targets."));
        }
        if (!allowed.test(SettingPermissions.forColour(key))) return lockedColour(key, l);
        try {
            int rgb = DynamicMinimapColours.parse(parts[1]);
            settings.colours = settings.colours.with(key, rgb);
            return new Result(true, colourSet(l, key, settings));
        } catch (IllegalArgumentException e) {
            return new Result(false, colourError(l, e.getMessage()));
        }
    }

    /** The reply for a colour the player has no permission to change. */
    private static Result lockedColour(Key key, Lang l) {
        return new Result(false, l.format("reply.colours.locked", "You do not have permission to change the {name} colour.",
                "name", colourName(l, key)));
    }

    /** "Map Edge: #FF0000." in the reply language. */
    private static String colourSet(Lang l, Key key, DynamicMinimapSetting settings) {
        return l.format("reply.value", "{setting}: {value}.", "setting", colourName(l, key), "value", settings.colours.hex(key));
    }

    /** A colour's name in the reply language (the settings page's key "colour.<id>"). */
    private static String colourName(Lang l, Key key) {
        return l.get("colour." + key.id, key.label);
    }

    /** "Frame / Map Edge", as the settings page's colour dropdown shows it. */
    private static String colourEntry(Lang l, Key key) {
        String group = key.group.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        return l.format("colours.entry", "{group} / {name}", "group", l.get("colours.group." + group, key.group),
                "name", colourName(l, key));
    }

    /** DynamicMinimapColours.parse's English error, in the reply language. */
    private static String colourError(Lang l, String english) {
        String key = switch (english == null ? "" : english) {
            case "Missing colour" -> "reply.colour.missing";
            case "Use #RRGGBB or R G B (0..255)" -> "reply.colour.format";
            case "RGB channels must be 0..255" -> "reply.colour.range";
            case "Invalid RGB colour" -> "reply.colour.invalid";
            default -> null;
        };
        return key == null ? english : l.get(key, english);
    }

    /** An integer in [min, max], or -1 for anything else. */
    private static int parseRanged(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed < min || parsed > max ? -1 : parsed;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** "on" or "off" only; null for anything else (one spelling per value). */
    private static Boolean parseOnOff(String value) {
        return switch (value) {
            case "on" -> Boolean.TRUE;
            case "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** "on"/"off" as command words (status lines list settings exactly as their commands take them). */
    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    /**
     * Everything typed after {@code command}'s own name, as " value value" (or "" when nothing
     * was typed). Used by commands that take free text: they allow extra arguments instead of
     * declaring an optional argument, because Hytale only accepts optional arguments in the
     * named {@code --name=value} form. The leading tokens (root command, any parents and this
     * command) are skipped by count.
     */
    private static String trailingArguments(CommandContext context, AbstractCommand command) {
        String input = context.getInputString();
        if (input == null) return "";
        input = input.trim();
        if (input.startsWith("/")) input = input.substring(1);
        String[] tokens = input.split("\\s+");
        int skip = command.countParents() + 1;
        if (tokens.length <= skip) return "";
        return " " + String.join(" ", Arrays.copyOfRange(tokens, skip, tokens.length));
    }

    // ---------------------------------------------------------------------------------
    // Command classes
    // ---------------------------------------------------------------------------------

    /** Player node checks for a command sender (the page and commands share SettingPermissions' table). */
    private static java.util.function.Predicate<String> allowedFor(CommandSender sender) {
        return path -> path == null || sender.hasPermission(DapperMapPermissions.player(path));
    }

    /**
     * A command checked against exactly one node. Hytale's default {@code hasPermission} also
     * requires every parent command's node (it recurses up the tree), which in 3.1.x silently
     * made each setting depend on the root's {@code toggle} and the group's {@code help} nodes.
     * Here only the command's own node counts, so a deny or grant on one setting affects only it.
     */
    private abstract static class GatedCommand extends AbstractPlayerCommand {
        private final PermissionQuery gate;

        GatedCommand(String name, String description, PermissionQuery gate) {
            super(name, description);
            this.gate = gate;
            this.requirePermission(gate); // Also what Hytale's help and permission tools show.
        }

        @Override
        public boolean hasPermission(@Nonnull CommandSender sender) {
            return sender.hasPermission(this.gate);
        }
    }

    /** One setting from {@link #SETTINGS}, with typed arguments and value suggestions. */
    private static final class SettingCommand extends GatedCommand {
        private final DapperMapCommand root;
        private final Scope scope;
        private final Setting setting;
        private final RequiredArg<String> word;
        private final RequiredArg<Integer> first;
        private final RequiredArg<Integer> second;

        SettingCommand(Setting setting, DapperMapCommand root, Scope scope) {
            // Player node: dappermap.command.<group>.<name>, e.g. command.display.zoom.
            // Description: key "command.<node>.desc", translated by each player's game.
            super(setting.name(), key("command." + setting.node() + ".desc"), gateFor(scope, setting.node()));
            this.root = root;
            this.scope = scope;
            this.setting = setting;
            this.word = setting.arg() == Arg.WORD ? this.withRequiredArg("value", setting.values(), ArgTypes.STRING) : null;
            this.first = setting.arg() != Arg.WORD ? this.withRequiredArg("value", setting.values(), ArgTypes.INTEGER) : null;
            this.second = setting.arg() == Arg.TWO_NUMBERS ? this.withRequiredArg("y", key("command.position.y"), ArgTypes.INTEGER) : null;
            if (this.word != null) {
                // Suggest the named values (not number ranges such as <16..256>).
                String[] suggestions = Arrays.stream(setting.values().split("\\|"))
                        .filter(value -> !value.startsWith("<")).toArray(String[]::new);
                this.word.suggest((sender, entered, count, result) -> {
                    String prefix = entered.toLowerCase(java.util.Locale.ROOT);
                    for (String value : suggestions) if (value.startsWith(prefix)) result.suggest(value);
                });
            }
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String value = this.word != null ? this.word.get(context) : String.valueOf(this.first.get(context));
            String extra = this.second != null ? String.valueOf(this.second.get(context)) : "";
            root.runSetting(context, store, ref, playerRef, scope, setting, value, extra);
        }
    }

    /**
     * A settings-tab group (display, markers, info, integration). It has no node of its own: it
     * is usable (and listed for the client) when any of its settings is, and bare it lists them.
     */
    private static final class GroupCommand extends AbstractPlayerCommand {
        private final String group;
        private final Scope scope;

        GroupCommand(String group, String description, DapperMapCommand root, Scope scope) {
            super(group, description);
            this.group = group;
            this.scope = scope;
            // No generated node (it would be a grantable name that does nothing): see hasPermission.
            this.requireNoPermission();
        }

        @Override
        public boolean hasPermission(@Nonnull CommandSender sender) {
            return anySubCommandAllowed(this, sender);
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            boolean admin = this.scope == Scope.ADMIN;
            String line = groupHelp(admin ? "/dappermap admin " : "/dappermap ", this.group,
                    admin ? node -> true : SettingPermissions.forPlayer(playerRef), Lang.of(playerRef));
            if (line != null) context.sendMessage(Message.raw(line));
        }
    }

    /** settings, on, off, status, help and reset. */
    private static final class SimpleCommand extends GatedCommand {
        private final DapperMapCommand root;
        private final Scope scope;

        SimpleCommand(String name, String description, DapperMapCommand root, Scope scope) {
            super(name, description, gateFor(scope, commandNode(name)));
            this.root = root;
            this.scope = scope;
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            root.runSimple(context, store, ref, playerRef, scope, this.getName());
        }
    }

    /**
     * {@code colours} with free text: list, a target and colour, or reset. Bare, it opens the editor.
     * Each colour has its own node ({@code colours.<target>}), so the player command is usable
     * with any one of them; runColours checks the target's own node.
     */
    private static final class ColoursCommand extends AbstractPlayerCommand {
        private final DapperMapCommand root;
        private final Scope scope;

        ColoursCommand(DapperMapCommand root, Scope scope) {
            super("colours", key("command.colours.desc"));
            this.root = root;
            this.scope = scope;
            if (scope == Scope.ADMIN) this.requirePermission(DapperMapPermissions.ADMIN);
            else this.requireNoPermission(); // Per-colour nodes: see hasPermission.
            this.setAllowsExtraArguments(true); // Plain values: /dappermap colours compass-dashes #FFAA00
        }

        @Override
        public boolean hasPermission(@Nonnull CommandSender sender) {
            return this.scope == Scope.ADMIN ? sender.hasPermission(DapperMapPermissions.ADMIN)
                    : SettingPermissions.anyColour(allowedFor(sender));
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            root.runColours(context, store, ref, playerRef, scope, trailingArguments(context, this));
        }
    }

    /**
     * Container for the admin tree. Running it bare opens the admin settings page;
     * the commands are registered onto it by {@link #registerCommands}.
     *
     * <p>Gated on {@link DapperMapPermissions#ADMIN}, a node deliberately kept outside the
     * player command tree (see that class's javadoc). Not setPermissionGroups(GROUP_ADMIN): that
     * is a coarse "anyone in this group" path that skips the deny-aware node check. ADMIN_NODE
     * is registered against GROUP_ADMIN the deny-respecting way in DapperMapPermissions.register().
     */
    private static final class AdminDispatchCommand extends GatedCommand {
        private final DapperMapCommand root;

        AdminDispatchCommand(DapperMapCommand root) {
            super("admin", key("command.admin.desc"), DapperMapPermissions.ADMIN);
            this.root = root;
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            root.runSimple(context, store, ref, playerRef, Scope.ADMIN, "settings");
        }
    }

    /** Admin-only server tools (limit, stats, load-shedding). */
    private static final class AdminToolCommand extends GatedCommand {
        private final DapperMapCommand root;

        AdminToolCommand(String name, String description, DapperMapCommand root) {
            super(name, description, DapperMapPermissions.ADMIN);
            this.root = root;
            this.setAllowsExtraArguments(true); // Plain values: /dappermap admin limit speed fast
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            root.runAdminTool(context, playerRef, this.getName(), trailingArguments(context, this));
        }
    }
}

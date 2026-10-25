package dev.scyphoz.dappermap.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
        // The bare command toggles the minimap. Give it its own short node rather than letting the
        // framework auto-generate one from this command's own name, for the same reason every
        // sub-command below does: scyphoz.dappermap.command.* should cover it without relying on
        // what this top-level command happens to be called.
        this.requirePermission(DapperMapPermissions.player("toggle"));

        // The player-facing tree.
        registerCommands(this, Scope.PLAYER);

        // The admin tree is the same set of sub-commands, rooted under "admin" and
        // routed at the server defaults instead of the caller's personal settings.
        AdminDispatchCommand admin = new AdminDispatchCommand(this);
        registerCommands(admin, Scope.ADMIN);
        // Admin-only server controls (no player-tree twin): limits, stats and load shedding.
        admin.addSubCommand(new AdminToolCommand("limit",
                "Server limits: limit [speed|resolution|zoom|creatures <value|none>]", this));
        admin.addSubCommand(new AdminToolCommand("stats", "Show DapperMap server load figures", this));
        admin.addSubCommand(new AdminToolCommand("load-shedding", "Turn adaptive load shedding on|off", this));
        this.addSubCommand(admin);
    }

    // ---------------------------------------------------------------------------------
    // The setting table
    // ---------------------------------------------------------------------------------

    /** A change's confirmation text (after "DapperMap ") and whether anything was changed. */
    private record Result(boolean changed, String message) { }

    /** Applies one value; returns null when the value isn't one the setting accepts. */
    @FunctionalInterface
    private interface Applier {
        Result apply(DynamicMinimapSetting settings, String value, String extra, Player player);
    }

    /**
     * One command setting. {@code group} is its settings-tab command ("" for a root command),
     * {@code node} its player permission (scyphoz.dappermap.command.&lt;node&gt;, the same node as
     * its settings-page control), {@code values} the accepted values shown in usage and help.
     */
    private record Setting(String group, String name, String node, Arg arg, String values, String description,
                           Applier apply) {
        String path() { return group.isEmpty() ? name : group + " " + name; }
    }

    /** Argument shape of a setting command. */
    private enum Arg { WORD, NUMBER, TWO_NUMBERS }

    /** Setting groups in help order, with the settings tab they match. */
    private static final Map<String, String> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("display", "Display: map appearance, compass ring and performance");
        GROUPS.put("markers", "Markers: players, creatures and death markers");
        GROUPS.put("info", "Info Panel: the panel and what it shows");
        GROUPS.put("integration", "Integrations: Hytale world map and BetterMap");
    }

    private static final List<Setting> SETTINGS = new ArrayList<>();
    static {
        // Display
        add("display", "zoom", "zoom", Arg.WORD, "close|medium|far|<16..256>", "Zoom Radius (presets 48, 96, 160 blocks)", (s, v, e, p) -> {
            int zoom = switch (v) { case "close" -> 48; case "medium" -> 96; case "far" -> 160; default -> parseRanged(v, 16, 256); };
            if (zoom < 0) return null;
            s.zoomBlocks = zoom;
            return done("zoom radius: " + zoom + " blocks.");
        });
        number("display", "size", "size", 120, 360, "Map Size", (s, n) -> s.sizePixels = n, " px");
        number("display", "scale", "scale", 75, 160, "Interface Scale", (s, n) -> s.interfaceScalePercent = n, "%");
        add("display", "shape", "shape", Arg.WORD, "circle|square", "Map Shape", (s, v, e, p) -> {
            if (!v.equals("circle") && !v.equals("square")) return null;
            s.shape = v.equals("circle") ? DynamicMinimapSetting.SHAPE_CIRCLE : DynamicMinimapSetting.SHAPE_SQUARE;
            return done("map shape: " + v + ".");
        });
        add("display", "rotation", "rotation", Arg.WORD, "north|player", "Map Rotation: North Up or Player Facing", (s, v, e, p) -> {
            if (!v.equals("north") && !v.equals("player")) return null;
            s.rotationMode = v.equals("player") ? DynamicMinimapSetting.ROTATION_PLAYER_UP : DynamicMinimapSetting.ROTATION_NORTH_UP;
            return done("map rotation: " + v + ".");
        });
        toggle("display", "compass-letters", "compass", "Compass Letters (N, E, S, W)", (s, on) -> s.showCompassLetters = on);
        toggle("display", "compass-dashes", "compass", "Compass Dashes (small dashes around the map edge)", (s, on) -> s.showCompassDashes = on);
        toggle("display", "cardinal-dashes", "compass", "Cardinal Dashes (large N, E, S, W dashes)", (s, on) -> s.showCardinalDashes = on);
        add("display", "speed", "speed", Arg.WORD, "very-fast|fast|normal|slow", "Update Speed", (s, v, e, p) -> {
            int speed = Arrays.asList(DapperMapConfig.SPEED_NAMES).indexOf(v);
            if (speed < 0) return null;
            s.updateSpeed = speed;
            return done("update speed: " + s.updateSpeedName() + ".");
        });
        number("display", "resolution", "resolution", 256, 1024, "Texture Resolution", (s, n) -> s.textureResolution = n, " px");
        // Markers
        toggle("markers", "players", "markers", "Player Markers", (s, on) -> s.showOtherPlayers = on);
        choice("markers", "player-style", "markers", "icons", "dots", "Player Marker Style", (s, first) -> s.playerIcons = first);
        toggle("markers", "player-helmets", "markers", "Player Icon Helmets", (s, on) -> s.playerIconHelmets = on);
        toggle("markers", "creatures", "markers", "Creature Markers", (s, on) -> s.showEntities = on);
        choice("markers", "creature-style", "markers", "icons", "dots", "Creature Marker Style", (s, first) -> s.creatureIcons = first);
        choice("markers", "creature-filter", "markers", "all", "hostile", "Creature Filter: all creatures, or only hostile and attacking ones",
                (s, first) -> s.hostileCreaturesOnly = !first);
        add("markers", "creature-limit", "markers", Arg.NUMBER, "16|32|64", "Creature Marker Limit", (s, v, e, p) -> {
            int limit = parseRanged(v, 16, 64);
            if (limit != 16 && limit != 32 && limit != 64) return null;
            s.creatureMarkerLimit = limit;
            return done("creature marker limit: " + limit + ".");
        });
        toggle("markers", "height-filter", "height", "Creature Height Filter", (s, on) -> s.limitMobHeight = on);
        number("markers", "height-range", "height-range", 4, 256, "Height Filter Range (blocks above and below you)",
                (s, n) -> s.mobVerticalRange = n, " blocks");
        toggle("markers", "death", "markers", "Death Markers", (s, on) -> s.showDeathMarker = on);
        // Info Panel
        toggle("info", "panel", "info", "Info Panel", (s, on) -> s.infoPanelEnabled = on);
        add("info", "position", "panel", Arg.WORD, "below|above|left|right", "Info Panel Position", (s, v, e, p) -> {
            int position = switch (v) {
                case "below" -> DynamicMinimapSetting.INFO_BELOW;
                case "above" -> DynamicMinimapSetting.INFO_ABOVE;
                case "left" -> DynamicMinimapSetting.INFO_LEFT;
                case "right" -> DynamicMinimapSetting.INFO_RIGHT;
                default -> -1;
            };
            if (position < 0) return null;
            s.infoPanelPosition = position;
            return done("Info Panel position: " + v + ".");
        });
        add("info", "alignment", "panel", Arg.WORD, "left|centre|right", "Info Panel Alignment", (s, v, e, p) -> {
            int alignment = switch (v) {
                case "left" -> DynamicMinimapSetting.INFO_ALIGN_LEFT;
                case "centre" -> DynamicMinimapSetting.INFO_ALIGN_CENTER;
                case "right" -> DynamicMinimapSetting.INFO_ALIGN_RIGHT;
                default -> -1;
            };
            if (alignment < 0) return null;
            s.infoPanelTextAlignment = alignment;
            return done("Info Panel alignment: " + v + ".");
        });
        toggle("info", "coordinates", "coordinates", "Coordinates", (s, on) -> s.showCoordinates = on);
        toggle("info", "bearing", "compass", "Bearing", (s, on) -> s.showCompass = on);
        add("info", "world-time", "clock", Arg.WORD, "on|off|12|24", "World Time; 12 or 24 also sets the Clock Format", (s, v, e, p) -> {
            switch (v) {
                case "on", "off" -> s.showClock = v.equals("on");
                case "12", "24" -> { s.showClock = true; s.clock24Hour = v.equals("24"); }
                default -> { return null; }
            }
            return done("world time: " + (s.showClock ? (s.clock24Hour ? "on, 24-hour" : "on, 12-hour") : "off") + ".");
        });
        toggle("info", "region", "biome", "Region", (s, on) -> s.showBiome = on);
        // Integrations
        toggle("integration", "worldmap", "integration", "Hytale World Map Markers", (s, on) -> s.showWorldMapMarkers = on);
        add("integration", "bettermap", "integration", Arg.WORD, "on|off", "The BetterMap integration", (s, v, e, p) -> {
            Boolean on = parseOnOff(v);
            if (on == null) return null;
            if (on && !BetterMapIntegration.isAvailable()) return new Result(false, "BetterMap integration unchanged: BetterMap was not detected.");
            s.betterMapEnabled = on;
            return done("BetterMap integration " + onOff(on) + ".");
        });
        add("integration", "bettermap-markers", "integration", Arg.WORD, "on|off", "BetterMap Markers", (s, v, e, p) -> {
            Boolean on = parseOnOff(v);
            if (on == null) return null;
            if (!s.betterMapEnabled || !BetterMapIntegration.isAvailable())
                return new Result(false, "BetterMap markers unchanged: turn the BetterMap integration on first.");
            s.betterMapMarkersEnabled = on;
            return done("BetterMap markers " + onOff(on) + ".");
        });
        add("integration", "bettermap-caves", "integration", Arg.WORD, "on|off", "BetterMap Cave Mode (BetterMap cave mode must be active)", (s, v, e, p) -> {
            Boolean on = parseOnOff(v);
            if (on == null) return null;
            if (!s.betterMapEnabled || !BetterMapIntegration.isAvailable())
                return new Result(false, "BetterMap caves unchanged: turn the BetterMap integration on first.");
            if (!BetterMapIntegration.isCaveModeEnabled(p))
                return new Result(false, "BetterMap caves unchanged: turn on BetterMap cave mode first.");
            s.betterMapCaveModeEnabled = on;
            return done("BetterMap caves " + onOff(on) + ".");
        });
        // Position (its own tab, a root command)
        add("", "position", "position", Arg.TWO_NUMBERS, "<x 0..100> <y 0..100>", "Place the minimap on screen", (s, v, e, p) -> {
            int x = parseRanged(v, 0, 100), y = parseRanged(e, 0, 100);
            if (x < 0 || y < 0) return null;
            s.hudPositionX = x;
            s.hudPositionY = y;
            return done("position: x=" + x + ", y=" + y + ".");
        });
    }

    private static void add(String group, String name, String node, Arg arg, String values, String description, Applier apply) {
        SETTINGS.add(new Setting(group, name, node, arg, values, description, apply));
    }

    /** An on|off setting. */
    private static void toggle(String group, String name, String node, String description,
                               java.util.function.BiConsumer<DynamicMinimapSetting, Boolean> set) {
        add(group, name, node, Arg.WORD, "on|off", description, (s, v, e, p) -> {
            Boolean on = parseOnOff(v);
            if (on == null) return null;
            set.accept(s, on);
            return done(label(description) + " " + onOff(on) + ".");
        });
    }

    /** A setting with two named values; {@code set} receives true for the first. */
    private static void choice(String group, String name, String node, String first, String second, String description,
                               java.util.function.BiConsumer<DynamicMinimapSetting, Boolean> set) {
        add(group, name, node, Arg.WORD, first + "|" + second, description, (s, v, e, p) -> {
            if (!v.equals(first) && !v.equals(second)) return null;
            set.accept(s, v.equals(first));
            return done(label(description) + ": " + v + ".");
        });
    }

    /** A whole-number setting within [min, max]. */
    private static void number(String group, String name, String node, int min, int max, String description,
                               java.util.function.ObjIntConsumer<DynamicMinimapSetting> set, String unit) {
        add(group, name, node, Arg.NUMBER, "<" + min + ".." + max + ">", description, (s, v, e, p) -> {
            int n = parseRanged(v, min, max);
            if (n < 0) return null;
            set.accept(s, n);
            return done(label(description) + ": " + n + unit + ".");
        });
    }

    /** The setting's page name from its description, e.g. "Compass Letters" from "Compass Letters (N, E, S, W)". */
    private static String label(String description) {
        return description.split("[(:;]")[0].trim();
    }

    private static Result done(String message) {
        return new Result(true, message);
    }

    /** Settings that server limits can hold below the player's own choice. */
    private static boolean limitable(Setting setting) {
        return switch (setting.name()) {
            case "zoom", "speed", "resolution", "creature-limit" -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------------------

    /**
     * Registers the whole tree onto {@code parent}: once for players, once under "admin", so the
     * two can never drift apart. Grouped commands sit under a container per settings tab.
     */
    private void registerCommands(AbstractCommand parent, Scope scope) {
        parent.addSubCommand(new SimpleCommand("settings", "Open the settings page", this, scope));
        parent.addSubCommand(new SimpleCommand("on", "Show DapperMap", this, scope));
        parent.addSubCommand(new SimpleCommand("off", "Hide DapperMap", this, scope));
        parent.addSubCommand(new SimpleCommand("status", "Show current settings", this, scope));
        parent.addSubCommand(new SimpleCommand("help", "List commands and accepted values", this, scope));
        parent.addSubCommand(new SimpleCommand("reset", "Reset settings to the defaults", this, scope));
        parent.addSubCommand(new ColoursCommand(this, scope));
        Map<String, GroupCommand> groups = new LinkedHashMap<>();
        for (Map.Entry<String, String> group : GROUPS.entrySet())
            groups.put(group.getKey(), new GroupCommand(group.getKey(), group.getValue(), this, scope));
        for (Setting setting : SETTINGS) {
            SettingCommand command = new SettingCommand(setting, this, scope);
            if (setting.group().isEmpty()) parent.addSubCommand(command);
            else groups.get(setting.group()).addSubCommand(command);
        }
        groups.values().forEach(parent::addSubCommand);
    }

    /**
     * Player node for a non-setting command: on/off share "toggle" (as the settings page's
     * Minimap switch); settings, status, help, reset and colours use their own name.
     */
    private static String commandNode(String command) {
        return command.equals("on") || command.equals("off") ? "toggle" : command;
    }

    /** Admin scope requires the admin node; player scope the given player node. */
    private static void gate(AbstractCommand command, Scope scope, String playerNode) {
        // Admin: the "admin" container already gates this subtree, but requiring it again on
        // every leaf means each admin sub-command is safe to reason about on its own.
        // Player: explicit short node (scyphoz.dappermap.command.<node>), registered against
        // the default player group via PermissionsModule (see DapperMapPermissions.player).
        command.requirePermission(scope == Scope.ADMIN ? DapperMapPermissions.ADMIN
                : DapperMapPermissions.player(playerNode));
    }

    /** Which settings store a command writes to. */
    private enum Scope { PLAYER, ADMIN }

    // ---------------------------------------------------------------------------------
    // Running commands
    // ---------------------------------------------------------------------------------

    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        boolean enabled = this.sessions.toggle(store.getComponent(ref, Player.getComponentType()), playerRef,
                DapperMap.getConfig().get());
        context.sendMessage(Message.raw(enabled ? "DapperMap shown." : "DapperMap hidden."));
    }

    /** One setting command, for a player's own settings or (admin) the server defaults. */
    private void runSetting(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef,
                            Scope scope, Setting setting, String value, String extra) {
        DapperMapConfig config = DapperMap.getConfig().get();
        Player player = store.getComponent(ref, Player.getComponentType());
        value = value.toLowerCase(java.util.Locale.ROOT);
        extra = extra.toLowerCase(java.util.Locale.ROOT);
        String prefix = scope == Scope.ADMIN ? "/dappermap admin " : "/dappermap ";
        if (scope == Scope.ADMIN) {
            if (!playerRef.hasPermission(DapperMapPermissions.ADMIN)) {
                context.sendMessage(Message.raw("You do not have permission to manage DapperMap server defaults."));
                return;
            }
            DynamicMinimapSetting defaults = this.sessions.getAdminDefaults(config).copy();
            Result result = setting.apply().apply(defaults, value, extra, player);
            if (result == null) { context.sendMessage(Message.raw("Usage: " + prefix + setting.path() + " " + setting.values())); return; }
            if (result.changed()) {
                defaults.normalize();
                this.sessions.setAdminDefaults(defaults);
            }
            context.sendMessage(Message.raw("DapperMap admin default " + result.message()));
            return;
        }
        DynamicMinimapSetting settings = this.sessions.getSettings(playerRef.getUuid(), config);
        Result result = setting.apply().apply(settings, value, extra, player);
        if (result == null) { context.sendMessage(Message.raw("Usage: " + prefix + setting.path() + " " + setting.values())); return; }
        if (result.changed()) this.sessions.applySettings(playerRef, settings);
        context.sendMessage(Message.raw("DapperMap " + result.message() + (limitable(setting) ? limitNote(settings) : "")));
    }

    /** General commands (settings, on, off, status, help, reset) for either scope. */
    private void runSimple(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef,
                           Scope scope, String command) {
        DapperMapConfig config = DapperMap.getConfig().get();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (scope == Scope.ADMIN) {
            if (!playerRef.hasPermission(DapperMapPermissions.ADMIN)) {
                context.sendMessage(Message.raw("You do not have permission to manage DapperMap server defaults."));
                return;
            }
            switch (command) {
                case "settings" -> openAdminPage(store, ref, playerRef, player, config);
                case "help" -> {
                    showHelp(context, "/dappermap admin ");
                    context.sendMessage(Message.raw("Admin defaults take the same settings and values as the player commands."));
                    context.sendMessage(Message.raw("Server: /dappermap admin limit [speed|resolution|zoom|creatures <value|none>] | /dappermap admin stats | /dappermap admin load-shedding on|off"));
                }
                case "status" -> {
                    for (String line : status(this.sessions.getAdminDefaults(config))) context.sendMessage(Message.raw("Admin defaults " + line));
                    context.sendMessage(Message.raw("Server limits: " + (config.hasLimits() ? config.describeLimits() : "none")
                            + "; load shedding " + (config.isLoadShedding() ? "on" : "off") + "."));
                }
                case "reset" -> {
                    this.sessions.resetAdminDefaults(config);
                    context.sendMessage(Message.raw("DapperMap admin defaults reset to mod defaults."));
                }
                default -> { // on, off
                    DynamicMinimapSetting defaults = this.sessions.getAdminDefaults(config).copy();
                    defaults.enabled = command.equals("on");
                    this.sessions.setAdminDefaults(defaults);
                    context.sendMessage(Message.raw("DapperMap admin default: minimap " + command + "."));
                }
            }
            return;
        }
        DynamicMinimapSetting settings = this.sessions.getSettings(playerRef.getUuid(), config);
        switch (command) {
            case "settings" -> player.getPageManager().openCustomPage(ref, store, new DapperMapSettingsPage(playerRef, this.sessions, config));
            case "help" -> showHelp(context, "/dappermap ");
            case "status" -> {
                for (String line : status(settings)) context.sendMessage(Message.raw(line));
                String note = limitNote(settings);
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
                context.sendMessage(Message.raw("DapperMap settings reset to the server defaults."));
            }
            default -> { // on, off
                settings.enabled = command.equals("on");
                this.sessions.applySettings(playerRef, settings);
                context.sendMessage(Message.raw(settings.enabled ? "DapperMap shown." : "DapperMap hidden."));
            }
        }
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
        DapperMapConfig config = DapperMap.getConfig().get();
        String args = arguments.trim().toLowerCase(java.util.Locale.ROOT);
        if (scope == Scope.ADMIN) {
            if (!playerRef.hasPermission(DapperMapPermissions.ADMIN)) {
                context.sendMessage(Message.raw("You do not have permission to manage DapperMap server defaults."));
                return;
            }
            if (args.isEmpty()) { context.sendMessage(Message.raw(COLOURS_USAGE.replace("/dappermap ", "/dappermap admin "))); return; }
            DynamicMinimapSetting defaults = this.sessions.getAdminDefaults(config).copy();
            var result = executeColourCommand(defaults, args);
            if (result.changed()) this.sessions.setAdminDefaults(defaults);
            context.sendMessage(Message.raw(result.message()));
            return;
        }
        DynamicMinimapSetting settings = this.sessions.getSettings(playerRef.getUuid(), config);
        if (args.isEmpty()) {
            if (!settings.enabled) { context.sendMessage(Message.raw("Enable the minimap before opening Colours.")); return; }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) player.getPageManager().openCustomPage(ref, store, DapperMapSettingsPage.colours(playerRef, this.sessions, config));
            return;
        }
        var result = executeColourCommand(settings, args);
        if (result.changed()) this.sessions.applySettings(playerRef, settings);
        context.sendMessage(Message.raw(result.message()));
    }

    /** Admin-only server tools: limit, stats and load-shedding. */
    private void runAdminTool(CommandContext context, PlayerRef playerRef, String tool, String arguments) {
        if (!playerRef.hasPermission(DapperMapPermissions.ADMIN)) {
            context.sendMessage(Message.raw("You do not have permission to manage DapperMap server defaults."));
            return;
        }
        DapperMapConfig config = DapperMap.getConfig().get();
        switch (tool) {
            case "limit" -> context.sendMessage(Message.raw(this.adminLimit(arguments.trim())));
            case "stats" -> { for (String line : DapperMapServerLoad.report(this.sessions, config)) context.sendMessage(Message.raw(line)); }
            default -> context.sendMessage(Message.raw(this.adminLoadShedding(arguments.trim().toLowerCase(java.util.Locale.ROOT))));
        }
    }

    // ---------------------------------------------------------------------------------
    // Help, status and helpers
    // ---------------------------------------------------------------------------------

    private static final String COLOURS_USAGE =
            "Colours: /dappermap colours | /dappermap colours list | /dappermap colours <target> <#RRGGBB|R G B> | /dappermap colours reset <target|all>";

    /** Every command, grouped like the settings tabs. {@code prefix} is "/dappermap " or "/dappermap admin ". */
    private static void showHelp(CommandContext context, String prefix) {
        context.sendMessage(Message.raw("DapperMap commands (/dappermap, /dm, /minimap or /mm):"));
        context.sendMessage(Message.raw(prefix.equals("/dappermap ")
                ? "/dappermap | /dappermap on|off | /dappermap status | /dappermap settings | /dappermap reset | /dappermap help"
                : "/dappermap admin | /dappermap admin on|off | /dappermap admin status | /dappermap admin reset | /dappermap admin help"));
        for (String group : GROUPS.keySet()) context.sendMessage(Message.raw(groupHelp(prefix, group)));
        context.sendMessage(Message.raw("Position: " + prefix + "position <x 0..100> <y 0..100>"));
        context.sendMessage(Message.raw(COLOURS_USAGE.replace("/dappermap ", prefix)));
    }

    /** "Display: /mm display zoom close|... | size <120..360> | ..." for one group. */
    private static String groupHelp(String prefix, String group) {
        String title = GROUPS.get(group);
        StringBuilder out = new StringBuilder(title.substring(0, title.indexOf(':') + 1)).append(' ')
                .append(prefix).append(group).append(' ');
        boolean first = true;
        for (Setting setting : SETTINGS) {
            if (!setting.group().equals(group)) continue;
            if (!first) out.append(" | ");
            out.append(setting.name()).append(' ').append(setting.values());
            first = false;
        }
        return out.toString();
    }

    /** Every setting, grouped and named exactly as its command takes it. */
    private static List<String> status(DynamicMinimapSetting s) {
        return List.of(
                "DapperMap: " + onOff(s.enabled) + ", position " + s.hudPositionX + " " + s.hudPositionY,
                "display: zoom " + s.zoomBlocks + ", size " + s.sizePixels + ", scale " + s.interfaceScalePercent
                        + ", shape " + (s.isCircle() ? "circle" : "square")
                        + ", rotation " + (s.rotationMode == DynamicMinimapSetting.ROTATION_PLAYER_UP ? "player" : "north")
                        + ", compass-letters " + onOff(s.showCompassLetters) + ", compass-dashes " + onOff(s.showCompassDashes)
                        + ", cardinal-dashes " + onOff(s.showCardinalDashes)
                        + ", speed " + s.updateSpeedName() + ", resolution " + s.textureResolution,
                "markers: players " + onOff(s.showOtherPlayers) + ", player-style " + (s.playerIcons ? "icons" : "dots")
                        + ", player-helmets " + onOff(s.playerIconHelmets) + ", creatures " + onOff(s.showEntities)
                        + ", creature-style " + (s.creatureIcons ? "icons" : "dots")
                        + ", creature-filter " + (s.hostileCreaturesOnly ? "hostile" : "all")
                        + ", creature-limit " + s.creatureMarkerLimit + ", height-filter " + onOff(s.limitMobHeight)
                        + ", height-range " + s.mobVerticalRange + ", death " + onOff(s.showDeathMarker),
                "info: panel " + onOff(s.infoPanelEnabled) + ", position " + panelPositionName(s.infoPanelPosition)
                        + ", alignment " + panelAlignmentName(s.infoPanelTextAlignment)
                        + ", coordinates " + onOff(s.showCoordinates) + ", bearing " + onOff(s.showCompass)
                        + ", world-time " + (s.showClock ? (s.clock24Hour ? "24" : "12") : "off")
                        + ", region " + onOff(s.showBiome),
                "integration: worldmap " + onOff(s.showWorldMapMarkers) + ", bettermap " + onOff(s.betterMapEnabled)
                        + ", bettermap-markers " + onOff(s.betterMapMarkersEnabled)
                        + ", bettermap-caves " + onOff(s.betterMapCaveModeEnabled));
    }

    /** " (server limit applies: ...)" when an admin limit holds this player below their own choice. */
    private static String limitNote(DynamicMinimapSetting settings) {
        String applied = DapperMap.getConfig().get().describeLimitsApplied(settings);
        return applied.isEmpty() ? "" : " (server limit applies: " + applied + ")";
    }

    /**
     * {@code /mm admin limit [speed|resolution|zoom|creatures <value|none>]}. Saves the config and
     * re-applies the limits to every live minimap; players' saved settings are kept.
     */
    private String adminLimit(String arguments) {
        DapperMapConfig config = DapperMap.getConfig().get();
        String usage = "Usage: /dappermap admin limit speed very-fast|fast|normal|slow|none"
                + " | resolution <256..1024>|none | zoom <16..256>|none | creatures 16|32|64|none";
        if (arguments.isBlank()) return "DapperMap server limits: " + (config.hasLimits() ? config.describeLimits() : "none") + ".";
        String[] parts = arguments.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        if (parts.length != 2) return usage;
        boolean none = parts[1].equals("none");
        int speed = config.getFastestUpdateSpeed(), resolution = config.getMaxTextureResolution();
        int zoom = config.getMaxZoomRadius(), creatures = config.getMaxCreatureLimit();
        int parsed;
        switch (parts[0]) {
            case "speed" -> {
                parsed = none ? 0 : Arrays.asList(DapperMapConfig.SPEED_NAMES).indexOf(parts[1]);
                if (parsed < 0) return usage;
                speed = parsed;
            }
            case "resolution" -> {
                parsed = none ? 1024 : parseRanged(parts[1], 256, 1024);
                if (parsed < 0) return usage;
                resolution = parsed;
            }
            case "zoom" -> {
                parsed = none ? 256 : parseRanged(parts[1], 16, 256);
                if (parsed < 0) return usage;
                zoom = parsed;
            }
            case "creatures" -> {
                parsed = none ? 64 : parseRanged(parts[1], 16, 64);
                if (parsed != 16 && parsed != 32 && parsed != 64) return usage;
                creatures = parsed;
            }
            default -> { return usage; }
        }
        config.setLimits(speed, resolution, zoom, creatures);
        DapperMap.saveConfig();
        this.sessions.refreshLiveSettings(); // Applies on each minimap's next tick.
        return "DapperMap server limits: " + (config.hasLimits() ? config.describeLimits() : "none")
                + " (" + this.sessions.limitedPlayerCount() + " player(s) currently limited).";
    }

    /** {@code /mm admin load-shedding on|off}; no value shows the current state. */
    private String adminLoadShedding(String value) {
        DapperMapConfig config = DapperMap.getConfig().get();
        Boolean enabled = parseOnOff(value);
        if (enabled == null) {
            return value.isEmpty()
                    ? "DapperMap load shedding is " + (config.isLoadShedding() ? "on" : "off")
                    + (DapperMapServerLoad.sheddingLevel() > 0 ? " and currently active." : ".")
                    + " Thresholds are set in dappermap_minimap.json (LoadSheddingRejectionsPerMinute, LoadSheddingPlayers)."
                    : "Usage: /dappermap admin load-shedding on|off";
        }
        config.setLoadShedding(enabled);
        DapperMap.saveConfig();
        return "DapperMap load shedding " + (enabled ? "on" : "off")
                + (enabled ? " (live minimaps step down one update speed while the server is busy)." : ".");
    }

    private record ColourCommandResult(boolean changed, String message) { }

    /** {@code list | <target> <#RRGGBB|R G B> | reset <target|all>} against {@code settings}. */
    private static ColourCommandResult executeColourCommand(DynamicMinimapSetting settings, String arguments) {
        String[] parts = arguments.trim().split("\\s+", 2);
        String target = parts[0];

        if (target.equals("list")) {
            return new ColourCommandResult(false, "DapperMap colours:\n" + Arrays.stream(Key.values())
                    .map(key -> key.id + " " + settings.colours.hex(key) + " (" + key.group + " / " + key.label + ")")
                    .collect(Collectors.joining("\n")));
        }

        if (target.equals("reset")) {
            String name = parts.length > 1 ? parts[1].trim() : "";
            if (name.equals("all")) {
                settings.colours = new DynamicMinimapColours();
                return new ColourCommandResult(true, "DapperMap colours reset to defaults.");
            }
            Key key = Key.find(name);
            if (key == null) {
                return new ColourCommandResult(false,
                        "Usage: /dappermap colours reset <target|all>. Use /dappermap colours list for targets.");
            }
            settings.colours = settings.colours.reset(key);
            return new ColourCommandResult(true, key.label + ": " + settings.colours.hex(key));
        }

        Key key = Key.find(target);
        if (key == null || parts.length != 2) {
            return new ColourCommandResult(false,
                    "Usage: /dappermap colours <target> <#RRGGBB|R G B>. Use /dappermap colours list for targets.");
        }
        try {
            int rgb = DynamicMinimapColours.parse(parts[1]);
            settings.colours = settings.colours.with(key, rgb);
            return new ColourCommandResult(true, key.label + ": " + settings.colours.hex(key));
        } catch (IllegalArgumentException e) {
            return new ColourCommandResult(false, e.getMessage());
        }
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

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static String panelPositionName(int value) {
        return switch (value) {
            case DynamicMinimapSetting.INFO_ABOVE -> "above";
            case DynamicMinimapSetting.INFO_LEFT -> "left";
            case DynamicMinimapSetting.INFO_RIGHT -> "right";
            default -> "below";
        };
    }

    private static String panelAlignmentName(int value) {
        return switch (value) {
            case DynamicMinimapSetting.INFO_ALIGN_CENTER -> "centre";
            case DynamicMinimapSetting.INFO_ALIGN_RIGHT -> "right";
            default -> "left";
        };
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

    /** One setting from {@link #SETTINGS}, with typed arguments and value suggestions. */
    private static final class SettingCommand extends AbstractPlayerCommand {
        private final DapperMapCommand root;
        private final Scope scope;
        private final Setting setting;
        private final RequiredArg<String> word;
        private final RequiredArg<Integer> first;
        private final RequiredArg<Integer> second;

        SettingCommand(Setting setting, DapperMapCommand root, Scope scope) {
            super(setting.name(), setting.description());
            this.root = root;
            this.scope = scope;
            this.setting = setting;
            gate(this, scope, setting.node());
            this.word = setting.arg() == Arg.WORD ? this.withRequiredArg("value", setting.values(), ArgTypes.STRING) : null;
            this.first = setting.arg() != Arg.WORD ? this.withRequiredArg("value", setting.values(), ArgTypes.INTEGER) : null;
            this.second = setting.arg() == Arg.TWO_NUMBERS ? this.withRequiredArg("y", "Vertical position", ArgTypes.INTEGER) : null;
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

    /** A settings-tab group (display, markers, info, integration). Bare, it lists its settings. */
    private static final class GroupCommand extends AbstractPlayerCommand {
        private final String group;

        GroupCommand(String group, String description, DapperMapCommand root, Scope scope) {
            super(group, description);
            this.group = group;
            // Each setting below checks its own node; the bare group only shows help.
            gate(this, scope, "help");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String prefix = this.countParents() > 1 ? "/dappermap admin " : "/dappermap ";
            context.sendMessage(Message.raw(groupHelp(prefix, this.group)));
        }
    }

    /** settings, on, off, status, help and reset. */
    private static final class SimpleCommand extends AbstractPlayerCommand {
        private final DapperMapCommand root;
        private final Scope scope;

        SimpleCommand(String name, String description, DapperMapCommand root, Scope scope) {
            super(name, description);
            this.root = root;
            this.scope = scope;
            gate(this, scope, commandNode(name));
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            root.runSimple(context, store, ref, playerRef, scope, this.getName());
        }
    }

    /** {@code colours} with free text: list, a target and colour, or reset. Bare, it opens the editor. */
    private static final class ColoursCommand extends AbstractPlayerCommand {
        private final DapperMapCommand root;
        private final Scope scope;

        ColoursCommand(DapperMapCommand root, Scope scope) {
            super("colours", "Configure colours");
            this.root = root;
            this.scope = scope;
            gate(this, scope, "colours");
            this.setAllowsExtraArguments(true); // Plain values: /dappermap colours compass-dashes #FFAA00
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
     * player command tree (see that class's javadoc). Requiring it explicitly matters:
     * generated command nodes are permissive unless something requires them, so without
     * this any player could reach the admin tree.
     */
    private static final class AdminDispatchCommand extends AbstractPlayerCommand {
        private final DapperMapCommand root;

        AdminDispatchCommand(DapperMapCommand root) {
            super("admin", "Configure server defaults for new DapperMap players");
            this.root = root;
            this.requirePermission(DapperMapPermissions.ADMIN);
            // Not setPermissionGroups(GROUP_ADMIN) here: that call is a coarse "anyone in this
            // group" fast path that bypasses the node-specific deny-aware check (see
            // DapperMapPermissions.DEFAULT_PLAYER_GROUP javadoc). ADMIN_NODE is already
            // registered against GROUP_ADMIN the deny-respecting way in DapperMapPermissions.register().
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            root.runSimple(context, store, ref, playerRef, Scope.ADMIN, "settings");
        }
    }

    /** Admin-only server tools (limit, stats, load-shedding). */
    private static final class AdminToolCommand extends AbstractPlayerCommand {
        private final DapperMapCommand root;

        AdminToolCommand(String name, String description, DapperMapCommand root) {
            super(name, description);
            this.root = root;
            this.requirePermission(DapperMapPermissions.ADMIN);
            this.setAllowsExtraArguments(true); // Plain values: /dappermap admin limit speed fast
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            root.runAdminTool(context, playerRef, this.getName(), trailingArguments(context, this));
        }
    }
}

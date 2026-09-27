package dev.scyphoz.dappermap.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.scyphoz.dappermap.DapperMap;
import dev.scyphoz.dappermap.config.MinimapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.dynamic.MinimapColours;
import dev.scyphoz.dappermap.dynamic.MinimapColours.Key;
import dev.scyphoz.dappermap.integration.BetterMapIntegration;
import dev.scyphoz.dappermap.pages.DapperMapSettingsPage;
import dev.scyphoz.dappermap.permissions.DapperMapPermissions;
import dev.scyphoz.dappermap.pages.DapperMapColoursPage;
import dev.scyphoz.dappermap.service.MinimapSessionManager;
import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Builds the player and administrator command trees and routes both trees through the
 * same setting parsers while keeping their permission scopes and persistence targets separate.
 */
public class MinimapCommand extends AbstractPlayerCommand {
    private final MinimapSessionManager sessions;

    public MinimapCommand(String name, String description, MinimapSessionManager sessions) {
        super(name, description);
        this.sessions = sessions;
        this.addAliases("minimap", "mm", "dm");
        // The bare command (and its aliases) toggles the minimap. Give it its own short node
        // rather than letting the framework auto-generate one from this command's own name,
        // for the same reason every sub-command below does: scyphoz.dappermap.command.* should
        // cover it without relying on what this top-level command happens to be called.
        this.requirePermission(DapperMapPermissions.player("toggle"));

        // The player-facing tree.
        registerSettingCommands(this, Scope.PLAYER);

        // The admin tree is the same set of sub-commands, rooted under "admin" and
        // routed at the server defaults instead of the caller's personal settings.
        AdminDispatchCommand admin = new AdminDispatchCommand(this);
        registerSettingCommands(admin, Scope.ADMIN);
        this.addSubCommand(admin);
    }

    /**
     * Registers the full setting table onto {@code parent}. Called once for the player
     * tree and once for the admin tree so the two can never drift apart: adding a setting
     * here gives you both {@code /dappermap <setting>} and {@code /dappermap admin <setting>},
     * with identical argument types and tab completion.
     */
    private void registerSettingCommands(AbstractCommand parent, Scope scope) {
        addNoArg(parent, scope, "settings", "Open settings", "menu", "gui");
        addNoArg(parent, scope, "on", "Show DapperMap");
        addNoArg(parent, scope, "off", "Hide DapperMap");
        addNoArg(parent, scope, "status", "Show current settings");
        addNoArg(parent, scope, "reset", "Reset settings", "defaults");
        addString(parent, scope, "zoom", "Set zoom", new String[]{"close", "medium", "far"});
        addString(parent, scope, "speed", "Set update speed", new String[]{"very-fast", "fast", "normal", "slow"}, "update");
        addString(parent, scope, "shape", "Set map shape", new String[]{"circle", "square"});
        addString(parent, scope, "rotation", "Set rotation", new String[]{"north", "player"}, "rotate");
        addString(parent, scope, "info", "Enable or disable the Info Panel", ON_OFF, "info-panel", "infopanel");
        addString(parent, scope, "panel", "Set Info Panel position", new String[]{"below", "above", "left", "right"});
        addString(parent, scope, "height", "Toggle creature height detection", ON_OFF, "height-detection");
        addString(parent, scope, "clock", "Configure clock", new String[]{"on", "off", "12", "24"});
        addString(parent, scope, "coordinates", "Toggle coordinates", ON_OFF, "coords");
        addString(parent, scope, "compass", "Toggle compass", ON_OFF);
        addString(parent, scope, "biome", "Toggle biome", ON_OFF);
        addInt(parent, scope, "size", "Set map size");
        addInt(parent, scope, "scale", "Set interface scale", "interface-scale", "interfacescale");
        addInt(parent, scope, "height-range", "Set creature height range", "heightrange", "vrange");
        addInt(parent, scope, "resolution", "Set texture resolution", "texture-resolution");
        addTwoInt(parent, scope, "position", "Set map position", "pos");
        addTwoString(parent, scope, "markers", "Configure markers", "blips");
        parent.addSubCommand(new IntegrationDispatchCommand("integration", "Configure integrations", this, scope, "integrations"));
        parent.addSubCommand(new ColourDispatchCommand("colours", "Configure colours", this, scope, "colour", "color", "colors"));
    }

    private static final String[] ON_OFF = {"on", "off"};

    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        boolean enabled = this.sessions.toggle((Player)store.getComponent(ref, Player.getComponentType()), playerRef,
                (MinimapConfig)DapperMap.getConfig().get());
        context.sendMessage(Message.raw(enabled ? "DapperMap shown." : "DapperMap hidden."));
    }

    private void dispatch(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                          @Nonnull PlayerRef playerRef, @Nonnull World world, String args) {
        MinimapConfig config = (MinimapConfig)DapperMap.getConfig().get();
        Player player = (Player)store.getComponent(ref, Player.getComponentType());
        args = args.trim();
        if (!args.isEmpty() && !args.equalsIgnoreCase("toggle")) {
            String[] parts = args.split("\\s+", 3);
            String subcommand = parts[0].toLowerCase();
            String value = parts.length > 1 ? parts[1].toLowerCase() : "";
            String extra = parts.length > 2 ? parts[2].toLowerCase() : "";
            DynamicMinimapSetting settings = this.sessions.getSettings(playerRef.getUuid(), config);
            switch (subcommand) {
                case "colour":
                case "colours":
                case "color":
                case "colors":
                    if (value.isEmpty()) {
                        if (!settings.enabled) { context.sendMessage(Message.raw("Enable Minimap before opening Colours.")); return; }
                        player.getPageManager().openCustomPage(ref, store, new DapperMapColoursPage(playerRef, this.sessions, config));
                    } else {
                        var result = executeColourCommand(settings, value + (extra.isEmpty() ? "" : " " + extra));
                        if (result.changed()) this.sessions.applySettings(playerRef, settings);
                        context.sendMessage(Message.raw(result.message()));
                    }
                    break;
                case "settings":
                case "menu":
                case "gui":
                    player.getPageManager().openCustomPage(ref, store, new DapperMapSettingsPage(playerRef, this.sessions, config));
                    break;
                case "on":
                    settings.enabled = true;
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap shown."));
                    break;
                case "off":
                    settings.enabled = false;
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap hidden."));
                    break;
                case "status":
                    context.sendMessage(Message.raw(status(settings)));
                    break;
                case "zoom":
                    if (!setZoom(settings, value)) {
                        context.sendMessage(Message.raw("Usage: /mm zoom close|medium|far|<16..256>"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap zoom: " + settings.zoomBlocks + " blocks."));
                    break;
                case "speed":
                case "update":
                    if (!setSpeed(settings, value)) {
                        context.sendMessage(Message.raw("Usage: /mm speed very-fast|fast|normal|slow"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap update speed: " + settings.updateSpeedName() + "."));
                    break;
                case "shape":
                    if (!setShape(settings, value)) {
                        context.sendMessage(Message.raw("Usage: /mm shape circle|square"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap shape: " + (settings.isCircle() ? "circle" : "square") + "."));
                    break;
                case "position":
                case "pos":
                    if (!setPosition(settings, value, extra)) {
                        context.sendMessage(Message.raw("Usage: /mm position <x 0..100> <y 0..100>"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap position: x=" + settings.hudPositionX + ", y=" + settings.hudPositionY + "."));
                    break;
                case "size":
                    if (!setRangedInt(value, 120, 360, v -> settings.sizePixels = v)) {
                        context.sendMessage(Message.raw("Usage: /mm size <120..360>"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap size: " + settings.sizePixels + " px."));
                    break;
                case "scale":
                case "interface-scale":
                case "interfacescale":
                    if (!setRangedInt(value, 75, 160, v -> settings.interfaceScalePercent = v)) {
                        context.sendMessage(Message.raw("Usage: /mm scale <75..160>"));
                        return;
                    }
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap interface scale: " + settings.interfaceScalePercent + "%."));
                    break;
                case "rotation":
                case "rotate":
                    if (!setRotation(settings, value)) {
                        context.sendMessage(Message.raw("Usage: /mm rotation north|player"));
                        return;
                    }
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap rotation: " + (settings.rotationMode == DynamicMinimapSetting.ROTATION_NORTH_UP ? "locked north" : "follow player") + "."));
                    break;
                case "info":
                case "info-panel":
                case "infopanel":
                    Boolean infoEnabled = parseOnOff(value);
                    if (infoEnabled == null) {
                        context.sendMessage(Message.raw("Usage: /mm info on|off"));
                        return;
                    }
                    settings.infoPanelEnabled = infoEnabled;
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap Info Panel " + (infoEnabled ? "enabled." : "disabled.")));
                    break;
                case "panel":
                    if (!setPanelPosition(settings, value)) {
                        context.sendMessage(Message.raw("Usage: /mm panel below|above|left|right"));
                        return;
                    }
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap information panel: " + panelPositionName(settings.infoPanelPosition) + "."));
                    break;
                case "height":
                case "height-detection":
                    Boolean heightEnabled = parseOnOff(value);
                    if (heightEnabled == null) {
                        context.sendMessage(Message.raw("Usage: /mm height on|off"));
                        return;
                    }
                    settings.limitMobHeight = heightEnabled;
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap creature height detection " + (heightEnabled ? "on." : "off.")));
                    break;
                case "height-range":
                case "heightrange":
                case "vrange":
                    if (!setRangedInt(value, 4, 256, v -> settings.mobVerticalRange = v)) {
                        context.sendMessage(Message.raw("Usage: /mm height-range <4..256>"));
                        return;
                    }
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap creature height range: " + settings.mobVerticalRange + " blocks above/below."));
                    break;
                case "resolution":
                case "texture-resolution":
                    if (!setRangedInt(value, 256, 1024, v -> settings.textureResolution = v)) {
                        context.sendMessage(Message.raw("Usage: /mm resolution <256..1024>"));
                        return;
                    }
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap texture resolution: " + settings.textureResolution + " px."));
                    break;
                case "reset":
                case "defaults":
                    DynamicMinimapSetting defaults = DynamicMinimapSetting.fromConfig(config);
                    this.sessions.applySettings(playerRef, defaults);
                    context.sendMessage(Message.raw("DapperMap settings reset to defaults."));
                    break;
                case "markers":
                case "blips":
                    if (!setMarkerToggle(settings, value, extra)) {
                        context.sendMessage(Message.raw("Usage: /mm markers players|entities|death|worldmap on|off"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap marker setting changed."));
                    break;
                case "integration":
                case "integrations":
                    if (!setIntegration(settings, value, extra, context, player)) {
                        context.sendMessage(Message.raw("Usage: /mm integration native on|off | bettermap enable|disable|markers on|off|caves on|off"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    break;
                case "clock":
                    if (!setClock(settings, value)) {
                        context.sendMessage(Message.raw("Usage: /mm clock on|off|12|24"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap clock setting changed."));
                    break;
                case "coordinates":
                case "coords":
                    Boolean coordinatesEnabled = parseOnOff(value);
                    if (coordinatesEnabled == null) {
                        context.sendMessage(Message.raw("Usage: /mm coordinates on|off"));
                        return;
                    }

                    settings.showCoordinates = coordinatesEnabled;
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap coordinate readout " + (coordinatesEnabled ? "on." : "off.")));
                    break;
                case "compass":
                    Boolean compassEnabled = parseOnOff(value);
                    if (compassEnabled == null) {
                        context.sendMessage(Message.raw("Usage: /mm compass on|off"));
                        return;
                    }

                    settings.showCompass = compassEnabled;
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap compass " + (compassEnabled ? "on." : "off.")));
                    break;
                case "biome":
                    Boolean biomeEnabled = parseOnOff(value);
                    if (biomeEnabled == null) {
                        context.sendMessage(Message.raw("Usage: /mm biome on|off"));
                        return;
                    }

                    settings.showBiome = biomeEnabled;
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap biome readout " + (biomeEnabled ? "on." : "off.")));
                    break;
                default:
                    showHelp(context);
            }
        } else {
            boolean enabled = this.sessions.toggle(player, playerRef, config);
            context.sendMessage(Message.raw(enabled ? "DapperMap shown." : "DapperMap hidden."));
        }

    }

    private record ColourCommandResult(boolean changed, String message) { }

    /** Parses /mm colour(s) arguments without requiring a separate command class. */
    private void dispatchAdmin(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef, String arguments) {
        if (!playerRef.hasPermission(DapperMapPermissions.ADMIN)) {
            context.sendMessage(Message.raw("You do not have permission to manage DapperMap server defaults."));
            return;
        }
        MinimapConfig config = (MinimapConfig)DapperMap.getConfig().get();
        Player player = store.getComponent(ref, Player.getComponentType());
        String args = arguments == null ? "" : arguments.trim();
        if (args.isEmpty() || args.equalsIgnoreCase("settings") || args.equalsIgnoreCase("menu") || args.equalsIgnoreCase("gui")) {
            // Opened from the console, so "Save Changes" should close the UI rather than
            // drop the admin into the player settings page.
            this.sessions.setAdminPageOrigin(playerRef.getUuid(), false);
            if (player != null) player.getPageManager().openCustomPage(ref, store,
                    new DapperMapSettingsPage(playerRef, this.sessions, config, true));
            return;
        }
        String[] parts = args.split("\\s+", 3);
        String key = parts[0].toLowerCase();
        String value = parts.length > 1 ? parts[1].toLowerCase() : "";
        String extra = parts.length > 2 ? parts[2].toLowerCase() : "";
        DynamicMinimapSetting settings = this.sessions.getAdminDefaults(config).copy();
        boolean changed = false;
        String usage = null;
        switch (key) {
            case "status" -> { context.sendMessage(Message.raw("DapperMap admin defaults: " + status(settings))); return; }
            case "reset", "defaults" -> { this.sessions.resetAdminDefaults(config); context.sendMessage(Message.raw("DapperMap admin defaults reset to mod defaults.")); return; }
            case "on" -> { settings.enabled = true; changed = true; }
            case "off" -> { settings.enabled = false; changed = true; }
            case "zoom" -> { changed = setZoom(settings, value); usage = "admin zoom close|medium|far|<16..256>"; }
            case "speed", "update" -> { changed = setSpeed(settings, value); usage = "admin speed very-fast|fast|normal|slow"; }
            case "shape" -> { changed = setShape(settings, value); usage = "admin shape circle|square"; }
            case "rotation", "rotate" -> { changed = setRotation(settings, value); usage = "admin rotation north|player"; }
            case "info", "info-panel", "infopanel" -> { Boolean b=parseOnOff(value); if (b != null) { settings.infoPanelEnabled=b; changed=true; } usage="admin info on|off"; }
            case "panel" -> { changed = setPanelPosition(settings, value); usage = "admin panel below|above|left|right"; }
            case "position", "pos" -> { changed = setPosition(settings, value, extra); usage = "admin position <x 0..100> <y 0..100>"; }
            case "size" -> { changed = setRangedInt(value,120,360,v->settings.sizePixels=v); usage = "admin size <120..360>"; }
            case "scale", "interface-scale", "interfacescale" -> { changed = setRangedInt(value,75,160,v->settings.interfaceScalePercent=v); usage = "admin scale <75..160>"; }
            case "resolution", "texture-resolution" -> { changed = setRangedInt(value,256,1024,v->settings.textureResolution=v); usage = "admin resolution <256..1024>"; }
            case "height-range", "heightrange", "vrange" -> { changed = setRangedInt(value,4,256,v->settings.mobVerticalRange=v); usage = "admin height-range <4..256>"; }
            case "markers", "blips" -> { changed = setMarkerToggle(settings, value, extra); usage = "admin markers players|entities|death|worldmap on|off"; }
            case "height", "height-detection" -> { if ("on".equals(value)||"off".equals(value)) { settings.limitMobHeight="on".equals(value); changed=true; } usage="admin height on|off"; }
            case "coordinates", "coords" -> { if ("on".equals(value)||"off".equals(value)) { settings.showCoordinates="on".equals(value); changed=true; } usage="admin coordinates on|off"; }
            case "compass" -> { if ("on".equals(value)||"off".equals(value)) { settings.showCompass="on".equals(value); changed=true; } usage="admin compass on|off"; }
            case "biome" -> { if ("on".equals(value)||"off".equals(value)) { settings.showBiome="on".equals(value); changed=true; } usage="admin biome on|off"; }
            case "clock" -> { changed=setClock(settings,value); usage="admin clock on|off|12|24"; }
            case "integration", "integrations" -> { changed=setIntegration(settings,value,extra,context,
                    store.getComponent(ref, Player.getComponentType())); usage="admin integration native on|off | bettermap enable|disable|markers on|off|caves on|off"; }
            case "colour", "colours", "color", "colors" -> {
                String colourArgs = args.substring(key.length()).trim();
                var result = executeColourCommand(settings, colourArgs);
                if (result.changed()) { this.sessions.setAdminDefaults(settings); }
                context.sendMessage(Message.raw(result.message())); return;
            }
            default -> { context.sendMessage(Message.raw("Usage: /dappermap admin settings|status|reset|<setting> <value>")); return; }
        }
        if (!changed) { context.sendMessage(Message.raw("Usage: /dappermap " + usage)); return; }
        settings.normalize(); this.sessions.setAdminDefaults(settings);
        context.sendMessage(Message.raw("DapperMap admin default updated: " + key + "."));
    }

    private static ColourCommandResult executeColourCommand(DynamicMinimapSetting settings, String arguments) {
        String[] parts = arguments.trim().split("\\s+", 2);
        String target = parts[0];

        if (target.equalsIgnoreCase("list") || target.equalsIgnoreCase("status")) {
            return new ColourCommandResult(false, "DapperMap colours:\n" + Arrays.stream(Key.values())
                    .map(key -> key.id + " " + settings.colours.hex(key))
                    .collect(Collectors.joining("\n")));
        }

        if (target.equalsIgnoreCase("reset")) {
            String name = parts.length > 1 ? parts[1].trim() : "";
            if (name.equalsIgnoreCase("all")) {
                settings.colours = new MinimapColours();
                return new ColourCommandResult(true, "DapperMap colours reset to defaults.");
            }

            Key key = Key.find(name);
            if (key == null) {
                return new ColourCommandResult(false,
                        "Usage: /mm colour reset <target|all>. Use /mm colours list for targets.");
            }

            settings.colours = settings.colours.reset(key);
            return new ColourCommandResult(true, key.label + ": " + settings.colours.hex(key));
        }

        Key key = Key.find(target);
        if (key == null || parts.length != 2) {
            return new ColourCommandResult(false,
                    "Usage: /mm colour <target> <#RRGGBB|R G B>. Use /mm colours list for targets.");
        }

        try {
            int rgb = MinimapColours.parse(parts[1]);
            settings.colours = settings.colours.with(key, rgb);
            return new ColourCommandResult(true, key.label + ": " + settings.colours.hex(key));
        } catch (IllegalArgumentException e) {
            return new ColourCommandResult(false, e.getMessage());
        }
    }

    private static boolean setZoom(DynamicMinimapSetting settings, String value) {
        switch (value) {
            case "close":
                settings.zoomBlocks = 48;
                break;
            case "medium":
            case "normal":
                settings.zoomBlocks = 96;
                break;
            case "far":
                settings.zoomBlocks = 160;
                break;
            default:
                try {
                    settings.zoomBlocks = Integer.parseInt(value);
                } catch (NumberFormatException var5) {
                    return false;
                }
        }

        return settings.zoomBlocks >= 16 && settings.zoomBlocks <= 256;
    }

    private static boolean setSpeed(DynamicMinimapSetting settings, String value) {
        switch (value) {
            case "very-fast":
            case "veryfast":
            case "vf":
                settings.updateSpeed = 0;
                break;
            case "fast":
                settings.updateSpeed = 1;
                break;
            case "normal":
            case "medium":
                settings.updateSpeed = 2;
                break;
            case "slow":
                settings.updateSpeed = 3;
                break;
            default:
                return false;
        }

        return true;
    }

    private static boolean setShape(DynamicMinimapSetting settings, String value) {
        switch (value) {
            case "circle":
            case "round":
                settings.shape = 1;
                break;
            case "square":
                settings.shape = 0;
                break;
            default:
                return false;
        }

        return true;
    }

    private static boolean setPosition(DynamicMinimapSetting settings, String value, String extra) {
        try {
            int x = Integer.parseInt(value);
            int y = Integer.parseInt(extra);
            if (x < 0 || x > 100 || y < 0 || y > 100) {
                return false;
            }
            settings.hudPositionX = x;
            settings.hudPositionY = y;
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean setRotation(DynamicMinimapSetting settings, String value) {
        switch (value) {
            case "north":
            case "north-up":
            case "locked":
            case "locked-north":
                settings.rotationMode = DynamicMinimapSetting.ROTATION_NORTH_UP;
                return true;
            case "player":
            case "player-up":
            case "follow":
            case "follow-player":
                settings.rotationMode = DynamicMinimapSetting.ROTATION_PLAYER_UP;
                return true;
            default:
                return false;
        }
    }

    private static boolean setPanelPosition(DynamicMinimapSetting settings, String value) {
        switch (value) {
            case "below": settings.infoPanelPosition = DynamicMinimapSetting.INFO_BELOW; return true;
            case "above": settings.infoPanelPosition = DynamicMinimapSetting.INFO_ABOVE; return true;
            case "left": settings.infoPanelPosition = DynamicMinimapSetting.INFO_LEFT; return true;
            case "right": settings.infoPanelPosition = DynamicMinimapSetting.INFO_RIGHT; return true;
            default: return false;
        }
    }

    private static String panelPositionName(int value) {
        return switch (value) {
            case DynamicMinimapSetting.INFO_ABOVE -> "above";
            case DynamicMinimapSetting.INFO_LEFT -> "left";
            case DynamicMinimapSetting.INFO_RIGHT -> "right";
            default -> "below";
        };
    }

    @FunctionalInterface
    private interface IntSetter {
        void set(int value);
    }

    private static boolean setRangedInt(String value, int min, int max, IntSetter setter) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) return false;
            setter.set(parsed);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean setMarkerToggle(DynamicMinimapSetting settings, String type, String value) {
        Boolean enabled = parseOnOff(value);
        if (enabled == null) {
            return false;
        } else {
            switch (type) {
                case "players":
                case "player":
                    settings.showOtherPlayers = enabled;
                    break;
                case "entities":
                case "entity":
                case "npcs":
                case "npc":
                case "mobs":
                case "mob":
                    settings.showEntities = enabled;
                    break;
                case "death":
                    settings.showDeathMarker = enabled;
                    break;
                case "worldmap":
                case "world-map":
                case "native":
                    settings.showWorldMapMarkers = enabled;
                    break;
                default:
                    return false;
            }

            return true;
        }
    }

    private static boolean setIntegration(DynamicMinimapSetting settings, String integration,
                                          String arguments, CommandContext context, Player player) {
        String[] parts = arguments.trim().split("\\s+");
        if (integration.equals("native") || integration.equals("worldmap")) {
            Boolean enabled = parts.length == 1 ? parseEnabledDisabled(parts[0]) : null;
            if (enabled == null) return false;
            settings.showWorldMapMarkers = enabled;
            context.sendMessage(Message.raw("Hytale world map markers " + (enabled ? "enabled." : "disabled.")));
            return true;
        }
        if (!integration.equals("bettermap")) return false;

        if (parts.length == 1) {
            Boolean enabled = parseEnabledDisabled(parts[0]);
            if (enabled == null) return false;
            if (enabled && !BetterMapIntegration.isAvailable()) {
                context.sendMessage(Message.raw("BetterMap was not detected."));
                return false;
            }
            settings.betterMapEnabled = enabled;
            context.sendMessage(Message.raw("BetterMap integration " + (enabled ? "enabled." : "disabled.")));
            return true;
        }
        if (parts.length != 2) return false;
        Boolean enabled = parseEnabledDisabled(parts[1]);
        if (enabled == null) return false;
        if (!settings.betterMapEnabled || !BetterMapIntegration.isAvailable()) {
            context.sendMessage(Message.raw("Enable the BetterMap integration first."));
            return false;
        }
        switch (parts[0]) {
            case "markers" -> settings.betterMapMarkersEnabled = enabled;
            case "caves", "cave" -> {
                if (!BetterMapIntegration.isCaveModeEnabled(player)) {
                    context.sendMessage(Message.raw("Enable BetterMap cave mode first."));
                    return false;
                }
                settings.betterMapCaveModeEnabled = enabled;
            }
            default -> { return false; }
        }
        context.sendMessage(Message.raw("BetterMap " + parts[0] + " " + (enabled ? "enabled." : "disabled.")));
        return true;
    }

    private static Boolean parseEnabledDisabled(String value) {
        switch (value) {
            case "enable":
            case "enabled":
            case "on":
            case "true":
            case "yes":
            case "1":
                return true;
            case "disable":
            case "disabled":
            case "off":
            case "false":
            case "no":
            case "0":
                return false;
            default:
                return null;
        }
    }

    private static boolean setClock(DynamicMinimapSetting settings, String value) {
        switch (value) {
            case "on":
                settings.showClock = true;
                break;
            case "off":
                settings.showClock = false;
                break;
            case "12":
                settings.showClock = true;
                settings.clock24Hour = false;
                break;
            case "24":
                settings.showClock = true;
                settings.clock24Hour = true;
                break;
            default:
                return false;
        }

        return true;
    }

    private static Boolean parseOnOff(String value) {
        Boolean var10000;
        switch (value) {
            case "on":
            case "true":
            case "yes":
            case "1":
                var10000 = true;
                break;
            case "off":
            case "false":
            case "no":
            case "0":
                var10000 = false;
                break;
            default:
                var10000 = null;
        }

        return var10000;
    }

    private static String status(DynamicMinimapSetting settings) {
        String enabled = settings.enabled ? "on" : "off";
        return "DapperMap: " + enabled
                + ", zoom=" + settings.zoomBlocks
                + ", size=" + settings.sizePixels
                + ", scale=" + settings.interfaceScalePercent + "%"
                + ", resolution=" + settings.textureResolution
                + ", speed=" + settings.updateSpeedName()
                + ", shape=" + (settings.isCircle() ? "circle" : "square")
                + ", rotation=" + (settings.rotationMode == DynamicMinimapSetting.ROTATION_NORTH_UP ? "north" : "player")
                + ", position=" + settings.hudPositionX + "," + settings.hudPositionY
                + ", info-panel=" + (settings.infoPanelEnabled ? "on" : "off")
                + ", panel=" + panelPositionName(settings.infoPanelPosition)
                + ", markers[players=" + settings.showOtherPlayers
                + ", entities=" + settings.showEntities
                + ", death=" + settings.showDeathMarker
                + ", worldmap=" + settings.showWorldMapMarkers
                + ", height=" + settings.limitMobHeight
                + ", range=" + settings.mobVerticalRange + "]"
                + ", clock=" + (settings.showClock ? (settings.clock24Hour ? "24h" : "12h") : "off")
                + ", coordinates=" + settings.showCoordinates
                + ", compass=" + settings.showCompass
                + ", biome=" + settings.showBiome
                + ", integrations[native=" + settings.showWorldMapMarkers
                + ", bettermap=" + settings.betterMapEnabled
                + ", markers=" + settings.betterMapMarkersEnabled
                + ", caves=" + settings.betterMapCaveModeEnabled + "]";
    }

    private static void showHelp(CommandContext context) {
        context.sendMessage(Message.raw("DapperMap commands:"));
        context.sendMessage(Message.raw("/mm settings | /mm colours | /mm reset"));
        context.sendMessage(Message.raw("/mm toggle|on|off|status"));
        context.sendMessage(Message.raw("/mm zoom close|medium|far|<16..256>"));
        context.sendMessage(Message.raw("/mm size <120..360> | /mm scale <75..160>"));
        context.sendMessage(Message.raw("/mm shape circle|square | /mm rotation north|player"));
        context.sendMessage(Message.raw("/mm speed very-fast|fast|normal|slow"));
        context.sendMessage(Message.raw("/mm position <x 0..100> <y 0..100>"));
        context.sendMessage(Message.raw("/mm panel below|above|left|right"));
        context.sendMessage(Message.raw("/mm markers players|entities|death|worldmap on|off"));
        context.sendMessage(Message.raw("/mm height on|off | /mm height-range <4..256>"));
        context.sendMessage(Message.raw("/mm coordinates on|off | /mm clock on|off|12|24"));
        context.sendMessage(Message.raw("/mm compass on|off | /mm biome on|off"));
        context.sendMessage(Message.raw("/mm integration native on|off | bettermap enable|disable"));
        context.sendMessage(Message.raw("/mm integration bettermap markers|caves on|off"));
        context.sendMessage(Message.raw("/mm colours list | /mm colour <target> <#RRGGBB|R G B>"));
        context.sendMessage(Message.raw("/mm colour reset <target|all>"));
        context.sendMessage(Message.raw("Advanced: /mm resolution <256..1024>"));
    }

    private void addNoArg(AbstractCommand parent, Scope scope, String name, String description, String... aliases) {
        parent.addSubCommand(new DispatchCommand(name, description, this, scope, Kind.NONE, null, aliases));
    }

    private void addString(AbstractCommand parent, Scope scope, String name, String description, String[] suggestions, String... aliases) {
        parent.addSubCommand(new DispatchCommand(name, description, this, scope, Kind.STRING, suggestions, aliases));
    }

    private void addInt(AbstractCommand parent, Scope scope, String name, String description, String... aliases) {
        parent.addSubCommand(new DispatchCommand(name, description, this, scope, Kind.INTEGER, null, aliases));
    }

    private void addTwoInt(AbstractCommand parent, Scope scope, String name, String description, String... aliases) {
        parent.addSubCommand(new DispatchCommand(name, description, this, scope, Kind.TWO_INTEGERS, null, aliases));
    }

    private void addTwoString(AbstractCommand parent, Scope scope, String name, String description, String... aliases) {
        parent.addSubCommand(new DispatchCommand(name, description, this, scope, Kind.TWO_STRINGS, null, aliases));
    }

    /** Which settings store a sub-command writes to. */
    private enum Scope { PLAYER, ADMIN }

    private enum Kind { NONE, STRING, INTEGER, TWO_INTEGERS, TWO_STRINGS }

    private static class DispatchCommand extends AbstractPlayerCommand {
        private final MinimapCommand root;
        private final Scope scope;
        private final Kind kind;
        private final RequiredArg<String> firstString;
        private final RequiredArg<String> secondString;
        private final RequiredArg<Integer> firstInt;
        private final RequiredArg<Integer> secondInt;

        DispatchCommand(String name, String description, MinimapCommand root, Scope scope, Kind kind,
                        String[] suggestions, String... aliases) {
            super(name, description);
            this.root = root;
            this.scope = scope;
            this.kind = kind;
            if (aliases.length > 0) this.addAliases(aliases);
            if (scope == Scope.ADMIN) {
                // Belt-and-braces: the "admin" container already gates this whole subtree,
                // but requiring it again on every leaf means each admin sub-command is safe
                // to reason about on its own, and none of them depend on the framework's
                // per-node ancestor-permission check being the only thing standing guard.
                this.requirePermission(DapperMapPermissions.ADMIN);
            } else {
                // Explicit short node (scyphoz.dappermap.command.<name>) instead of the
                // framework's auto-generated scyphoz.dappermap.command.dappermap.<name>.
                // DapperMapPermissions.player(...) registers it against the default player
                // group via PermissionsModule, not via setPermissionGroups — see that
                // method's javadoc for why setPermissionGroups was dropped here.
                this.requirePermission(DapperMapPermissions.player(name.toLowerCase(java.util.Locale.ROOT)));
            }
            this.firstString = kind == Kind.STRING || kind == Kind.TWO_STRINGS
                    ? this.withRequiredArg("value", "Setting value", ArgTypes.STRING) : null;
            this.secondString = kind == Kind.TWO_STRINGS
                    ? this.withRequiredArg("state", "Setting state", ArgTypes.STRING) : null;
            this.firstInt = kind == Kind.INTEGER || kind == Kind.TWO_INTEGERS
                    ? this.withRequiredArg("value", "Numeric value", ArgTypes.INTEGER) : null;
            this.secondInt = kind == Kind.TWO_INTEGERS
                    ? this.withRequiredArg("y", "Vertical position", ArgTypes.INTEGER) : null;
            if (this.firstString != null && suggestions != null) {
                this.firstString.suggest((sender, entered, count, result) -> {
                    String prefix = entered.toLowerCase();
                    for (String value : suggestions) if (value.startsWith(prefix)) result.suggest(value);
                });
            }
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String args = this.getName();
            if (kind == Kind.STRING) args += " " + firstString.get(context);
            else if (kind == Kind.INTEGER) args += " " + firstInt.get(context);
            else if (kind == Kind.TWO_INTEGERS) args += " " + firstInt.get(context) + " " + secondInt.get(context);
            else if (kind == Kind.TWO_STRINGS) args += " " + firstString.get(context) + " " + secondString.get(context);
            if (scope == Scope.ADMIN) {
                root.dispatchAdmin(context, store, ref, playerRef, args);
            } else {
                root.dispatch(context, store, ref, playerRef, world, args);
            }
        }
    }

    /**
     * Container for the admin tree. Running it bare opens the admin settings page;
     * the setting sub-commands are registered onto it by {@link #registerSettingCommands}.
     *
     * <p>Gated on {@link DapperMapPermissions#ADMIN}, a node deliberately kept outside the
     * player command tree (see that class's javadoc). Requiring it explicitly matters:
     * generated command nodes are permissive unless something requires them, so without
     * this any player could reach the admin tree.
     */
    private static final class AdminDispatchCommand extends AbstractPlayerCommand {
        private final MinimapCommand root;

        AdminDispatchCommand(MinimapCommand root) {
            super("admin", "Configure server defaults for new DapperMap players");
            this.root = root;
            this.requirePermission(DapperMapPermissions.ADMIN);
            // Not setPermissionGroups(OP_GROUP) here: that call is a coarse "anyone in this
            // group" fast path that bypasses the node-specific deny-aware check (see
            // DapperMapPermissions.DEFAULT_PLAYER_GROUP javadoc). ADMIN_NODE is already
            // registered against OP_GROUP the deny-respecting way in DapperMapPermissions.register().
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            root.dispatchAdmin(context, store, ref, playerRef, "");
        }
    }

    /** Accepts integration, feature, and state in one command for both scopes. */
    private static final class IntegrationDispatchCommand extends AbstractPlayerCommand {
        private final MinimapCommand root;
        private final Scope scope;
        private final OptionalArg<String> arguments;

        IntegrationDispatchCommand(String name, String description, MinimapCommand root, Scope scope, String... aliases) {
            super(name, description);
            this.root = root;
            this.scope = scope;
            this.addAliases(aliases);
            this.requirePermission(scope == Scope.ADMIN ? DapperMapPermissions.ADMIN
                    : DapperMapPermissions.player("integration"));
            this.arguments = this.withOptionalArg("arguments", "Integration and setting", ArgTypes.GREEDY_STRING);
            this.arguments.suggest((sender, entered, count, result) -> {
                String prefix = entered.toLowerCase();
                for (String value : new String[]{"native on", "native off", "bettermap enable",
                        "bettermap disable", "bettermap markers on", "bettermap markers off",
                        "bettermap caves on", "bettermap caves off"}) {
                    if (value.startsWith(prefix)) result.suggest(value);
                }
            });
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String suffix = this.arguments.provided(context) ? " " + this.arguments.get(context) : "";
            if (scope == Scope.ADMIN) root.dispatchAdmin(context, store, ref, playerRef, this.getName() + suffix);
            else root.dispatch(context, store, ref, playerRef, world, this.getName() + suffix);
        }
    }

    private static final class ColourDispatchCommand extends AbstractPlayerCommand {
        private final MinimapCommand root;
        private final Scope scope;
        private final OptionalArg<String> arguments;

        ColourDispatchCommand(String name, String description, MinimapCommand root, Scope scope, String... aliases) {
            super(name, description);
            this.root = root;
            this.scope = scope;
            if (scope == Scope.ADMIN) {
                this.requirePermission(DapperMapPermissions.ADMIN);
            } else {
                this.requirePermission(DapperMapPermissions.player(name.toLowerCase(java.util.Locale.ROOT)));
            }
            this.addAliases(aliases);
            this.arguments = this.withOptionalArg("arguments", "Colour target and value", ArgTypes.GREEDY_STRING);
            this.arguments.suggest((sender, entered, count, result) -> {
                String prefix = entered.toLowerCase();
                for (String value : java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("list", "reset all"),
                        Arrays.stream(Key.values()).map(key -> key.id)).toList()) {
                    if (value.startsWith(prefix)) result.suggest(value);
                }
            });
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String suffix = this.arguments.provided(context) ? " " + this.arguments.get(context) : "";
            if (scope == Scope.ADMIN) {
                root.dispatchAdmin(context, store, ref, playerRef, this.getName() + suffix);
            } else {
                root.dispatch(context, store, ref, playerRef, world, this.getName() + suffix);
            }
        }
    }

}

package dev.scyphoz.dappermap.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
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
import dev.scyphoz.dappermap.pages.DapperMapColoursPage;
import dev.scyphoz.dappermap.service.MinimapSessionManager;
import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.stream.Collectors;

public class MinimapCommand extends AbstractPlayerCommand {
    private final MinimapSessionManager sessions;
    private final RequiredArg<String> argsArg;

    public MinimapCommand(String name, String description, MinimapSessionManager sessions) {
        super(name, description);
        this.sessions = sessions;
        this.argsArg = this.withRequiredArg("args", "subcommand + value", ArgTypes.GREEDY_STRING);
    }

    public boolean canGeneratePermission() {
        return false;
    }

    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        MinimapConfig config = (MinimapConfig)DapperMap.getConfig().get();
        Player player = (Player)store.getComponent(ref, Player.getComponentType());
        String args = ((String)this.argsArg.get(context)).trim();
        this.sessions.noteCommandUsed();
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
                case "panel":
                case "info-panel":
                case "infopanel":
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
                case "border":
                    Boolean borderEnabled = parseOnOff(value);
                    if (borderEnabled == null) {
                        context.sendMessage(Message.raw("Usage: /mm border on|off"));
                        return;
                    }
                    settings.showBorder = borderEnabled;
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap border " + (borderEnabled ? "on." : "off.")));
                    break;
                case "resolution":
                case "texture-resolution":
                    if (!setRangedInt(value, 256, 512, v -> settings.textureResolution = v)) {
                        context.sendMessage(Message.raw("Usage: /mm resolution <256..512>"));
                        return;
                    }
                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap texture resolution: " + settings.textureResolution + " px."));
                    break;
                case "reset":
                case "defaults":
                    DynamicMinimapSetting defaults = DynamicMinimapSetting.fromConfig(config);
                    defaults.betterMapEnabled = settings.betterMapEnabled;
                    this.sessions.applySettings(playerRef, defaults);
                    context.sendMessage(Message.raw("DapperMap settings reset to defaults (integration preference preserved)."));
                    break;
                case "markers":
                case "blips":
                    if (!setMarkerToggle(settings, value, extra)) {
                        context.sendMessage(Message.raw("Usage: /mm markers players|entities|death on|off"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap marker setting changed."));
                    break;
                case "integration":
                case "integrations":
                    if (!setIntegration(settings, value, extra, context)) {
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
                default:
                    return false;
            }

            return true;
        }
    }

    private static boolean setIntegration(DynamicMinimapSetting settings, String integration, String value, CommandContext context) {
        if (!integration.equals("bettermap")) {
            context.sendMessage(Message.raw("Usage: /mm integration bettermap enable|disable"));
            return false;
        }

        Boolean enabled = parseEnabledDisabled(value);
        if (enabled == null) {
            context.sendMessage(Message.raw("Usage: /mm integration bettermap enable|disable"));
            return false;
        }

        if (enabled && !BetterMapIntegration.isAvailable()) {
            context.sendMessage(Message.raw("BetterMap integration cannot be enabled because BetterMap was not detected."));
            return false;
        }

        settings.betterMapEnabled = enabled;
        context.sendMessage(Message.raw("BetterMap integration " + (enabled ? "enabled." : "disabled.")));
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
                + ", panel=" + panelPositionName(settings.infoPanelPosition)
                + ", border=" + settings.showBorder
                + ", markers[players=" + settings.showOtherPlayers
                + ", entities=" + settings.showEntities
                + ", death=" + settings.showDeathMarker
                + ", height=" + settings.limitMobHeight
                + ", range=" + settings.mobVerticalRange + "]"
                + ", clock=" + (settings.showClock ? (settings.clock24Hour ? "24h" : "12h") : "off")
                + ", compass=" + settings.showCompass
                + ", biome=" + settings.showBiome
                + ", integrations[bettermap=" + settings.betterMapEnabled + "]";
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
        context.sendMessage(Message.raw("/mm markers players|entities|death on|off"));
        context.sendMessage(Message.raw("/mm height on|off | /mm height-range <4..256>"));
        context.sendMessage(Message.raw("/mm clock on|off|12|24 | /mm compass on|off | /mm biome on|off"));
        context.sendMessage(Message.raw("/mm integration bettermap enable|disable"));
        context.sendMessage(Message.raw("/mm colours list | /mm colour <target> <#RRGGBB|R G B>"));
        context.sendMessage(Message.raw("/mm colour reset <target|all>"));
        context.sendMessage(Message.raw("Advanced: /mm border on|off | /mm resolution <256..512>"));
    }

}

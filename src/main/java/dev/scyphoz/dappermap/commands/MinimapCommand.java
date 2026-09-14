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
import dev.scyphoz.dappermap.pages.DapperMapSettingsPage;
import dev.scyphoz.dappermap.pages.DapperMapColoursPage;
import dev.scyphoz.dappermap.service.MinimapSessionManager;
import javax.annotation.Nonnull;

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
                        var result = MinimapColourCommand.execute(settings, value + (extra.isEmpty() ? "" : " " + extra));
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
                    if (!setPosition(settings, value)) {
                        context.sendMessage(Message.raw("Usage: /mm position top-left|top-right|bottom-left|bottom-right"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap position changed."));
                    break;
                case "size":
                    try {
                        settings.sizePixels = Integer.parseInt(value);
                    } catch (NumberFormatException var18) {
                        context.sendMessage(Message.raw("Usage: /mm size <120..360>"));
                        return;
                    }

                    this.sessions.applySettings(playerRef, settings);
                    context.sendMessage(Message.raw("DapperMap size: " + settings.sizePixels + " px."));
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

        return true;
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

    private static boolean setPosition(DynamicMinimapSetting settings, String value) {
        switch (value) {
            case "top-left":
            case "tl":
                settings.position = 0;
                break;
            case "top-right":
            case "tr":
                settings.position = 1;
                break;
            case "bottom-left":
            case "bl":
                settings.position = 2;
                break;
            case "bottom-right":
            case "br":
                settings.position = 3;
                break;
            default:
                return false;
        }

        return true;
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
        String var10000 = settings.enabled ? "on" : "off";
        return "DapperMap: " + var10000 + ", zoom=" + settings.zoomBlocks + ", size=" + settings.sizePixels + ", speed=" + settings.updateSpeedName() + ", shape=" + (settings.isCircle() ? "circle" : "square") + ", markers[players=" + settings.showOtherPlayers + ", entities=" + settings.showEntities + ", death=" + settings.showDeathMarker + "], clock=" + (settings.showClock ? (settings.clock24Hour ? "24h" : "12h") : "off") + ", compass=" + settings.showCompass + ", biome=" + settings.showBiome;
    }

    private static void showHelp(CommandContext context) {
        context.sendMessage(Message.raw("DapperMap commands:"));
        context.sendMessage(Message.raw("/mm settings | /mm colours"));
        context.sendMessage(Message.raw("/mm colours list | /mm colour <target> <#RRGGBB|R G B>"));
        context.sendMessage(Message.raw("/mm colour reset <target|all>"));
        context.sendMessage(Message.raw("/mm toggle|on|off|status"));
        context.sendMessage(Message.raw("/mm zoom close|medium|far|<16..256>"));
        context.sendMessage(Message.raw("/mm speed very-fast|fast|normal|slow"));
        context.sendMessage(Message.raw("/mm shape circle|square"));
        context.sendMessage(Message.raw("/mm position top-left|top-right|bottom-left|bottom-right"));
        context.sendMessage(Message.raw("/mm markers players|entities|death on|off"));
        context.sendMessage(Message.raw("/mm clock on|off|12|24, /mm compass on|off"));
        context.sendMessage(Message.raw("/mm biome on|off"));
    }
}

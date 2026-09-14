package dev.scyphoz.dappermap.commands;

import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.dynamic.MinimapColours;
import dev.scyphoz.dappermap.dynamic.MinimapColours.Key;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Shared command parser; rejects invalid input without changing any colours. */
public final class MinimapColourCommand {
    private MinimapColourCommand() { }
    public record Result(boolean changed, String message) { }
    public static Result execute(DynamicMinimapSetting settings, String arguments) {
        String[] parts = arguments.trim().split("\\s+", 2);
        String target = parts[0];
        if (target.equalsIgnoreCase("list") || target.equalsIgnoreCase("status")) {
            return new Result(false, "DapperMap colours:\n" + Arrays.stream(Key.values())
                    .map(key -> key.id + " " + settings.colours.hex(key)).collect(Collectors.joining("\n")));
        }
        if (target.equalsIgnoreCase("reset")) {
            String name = parts.length > 1 ? parts[1].trim() : "";
            if (name.equalsIgnoreCase("all")) {
                settings.colours = new MinimapColours();
                return new Result(true, "DapperMap colours reset to defaults.");
            }
            Key key = Key.find(name);
            if (key == null) return new Result(false, "Usage: /mm colour reset <target|all>. Use /mm colours list for targets.");
            settings.colours = settings.colours.reset(key);
            return new Result(true, key.label + ": " + settings.colours.hex(key));
        }
        Key key = Key.find(target);
        if (key == null || parts.length != 2) return new Result(false,
                "Usage: /mm colour <target> <#RRGGBB|R G B>. Use /mm colours list for targets.");
        try {
            int rgb = MinimapColours.parse(parts[1]);
            settings.colours = settings.colours.with(key, rgb);
            return new Result(true, key.label + ": " + settings.colours.hex(key));
        } catch (IllegalArgumentException e) { return new Result(false, e.getMessage()); }
    }
}

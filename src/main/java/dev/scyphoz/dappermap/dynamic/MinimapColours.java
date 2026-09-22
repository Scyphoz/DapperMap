package dev.scyphoz.dappermap.dynamic;

import java.awt.Color;
import java.util.Arrays;
import java.util.Locale;

/** Immutable per-player RGB palette. Existing opacity and terrain colours are retained. */
public final class MinimapColours {
    public enum Key {
        EDGE("edge", "Frame", "Aperture Edge", "The main edge of the DapperMap aperture.", 0x7B6CFF),
        TICKS("ticks", "Frame", "Survey Marks", "The fine marks around the map aperture.", 0x57D8D0),
        CARDINAL_TEXT("cardinal-text", "Text", "Compass Directions", "The N, E, S and W labels on the minimap.", 0x7B6CFF),
        PANEL("panel", "Panel", "Telemetry Rail", "The translucent rail holding map telemetry.", 0x111426),
        BRAND_TEXT("brand-text", "Text", "DapperMap Label", "The DapperMap instrument label.", 0xB8AEFF),
        INFO_HEADERS("info-headers", "Text", "Information Headings", "The heading above each information-panel value.", 0x57D8D0),
        COORDS_TEXT("coords-text", "Text", "Coordinates", "The world-coordinate text.", 0xF4F2FF),
        COMPASS_TEXT("compass-text", "Text", "Bearing", "The facing-direction text.", 0x6FE8DF),
        CLOCK_TEXT("clock-text", "Text", "World Time", "The Hytale world-time text.", 0x8FCBFF),
        BIOME_TEXT("biome-text", "Text", "Region", "The current-biome text.", 0xC7D3E2),
        SELF("self", "Markers", "Your Locator", "The locator marking your position.", 0xF4C76B),
        PLAYERS("players", "Markers", "Other Players", "Nearby player markers.", 0x74A7FF),
        DEATH("death", "Markers", "Death Marker", "Your last-death marker.", 0xF2F0FF),
        HOSTILE_ACTIVE("hostile-active", "Markers", "Attacking Creatures", "Hostiles currently targeting you.", 0xFF526B),
        HOSTILE("hostile", "Markers", "Hostile Creatures", "Nearby hostile creatures.", 0xFF9566),
        NEUTRAL("neutral", "Markers", "Neutral Creatures", "Neutral or retreating creatures.", 0xE9CA68),
        FRIENDLY("friendly", "Markers", "Friendly Creatures", "Nearby friendly creatures.", 0x69D69A);
        public final String id, group, label, description;
        public final int defaultRgb;
        Key(String id, String group, String label, String description, int defaultRgb) {
            this.id = id; this.group = group; this.label = label;
            this.description = description; this.defaultRgb = defaultRgb;
        }
        public static Key find(String id) {
            if (id == null) return null;
            for (Key key : values()) if (key.id.equalsIgnoreCase(id)) return key;
            return null;
        }
    }
    private final int[] values;
    public MinimapColours() {
        values = Arrays.stream(Key.values()).mapToInt(key -> key.defaultRgb).toArray();
    }
    private MinimapColours(int[] values) { this.values = values; }
    public int rgb(Key key) { return values[key.ordinal()]; }
    public String hex(Key key) { return String.format(Locale.ROOT, "#%06X", rgb(key)); }
    public Color awt(Key key, int alpha) { return new Color((alpha << 24) | rgb(key), true); }
    public MinimapColours with(Key key, int rgb) {
        if (key == null || rgb < 0 || rgb > 0xffffff) throw new IllegalArgumentException("Invalid RGB colour");
        if (rgb(key) == rgb) return this;
        int[] next = values.clone(); next[key.ordinal()] = rgb; return new MinimapColours(next);
    }
    public MinimapColours reset(Key key) { return with(key, key.defaultRgb); }
    public String frameKey() {
        StringBuilder out = new StringBuilder();
        for (Key key : new Key[]{Key.EDGE, Key.TICKS, Key.CARDINAL_TEXT})
            out.append(hex(key).substring(1));
        return out.toString();
    }
    public String arrowKey() { return hex(Key.SELF).substring(1); }
    /** Accept #RRGGBB, RRGGBB, or three decimal channels separated by spaces/commas. */
    public static int parse(String input) {
        if (input == null) throw new IllegalArgumentException("Missing colour");
        String text = input.trim();
        if (text.matches("#?[0-9a-fA-F]{6}")) return Integer.parseInt(text.replace("#", ""), 16);
        String[] parts = text.split("[\\s,]+");
        if (parts.length != 3) throw new IllegalArgumentException("Use #RRGGBB or R G B (0..255)");
        int rgb = 0;
        try {
            for (String part : parts) {
                if (!part.matches("[0-9]{1,3}")) throw new IllegalArgumentException("RGB channels must be 0..255");
                int channel = Integer.parseInt(part);
                if (channel > 255) throw new IllegalArgumentException("RGB channels must be 0..255");
                rgb = (rgb << 8) | channel;
            }
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid RGB colour"); }
        return rgb;
    }
    @Override public boolean equals(Object other) { return other instanceof MinimapColours p && Arrays.equals(values, p.values); }
    @Override public int hashCode() { return Arrays.hashCode(values); }
}

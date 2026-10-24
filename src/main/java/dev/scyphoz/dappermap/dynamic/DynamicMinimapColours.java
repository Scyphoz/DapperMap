package dev.scyphoz.dappermap.dynamic;

import java.awt.Color;
import java.util.Arrays;

/** Immutable per-player RGB palette. Existing opacity and terrain colours are retained. */
public final class DynamicMinimapColours {
    public enum Key {
        EDGE("edge", "Frame", "Aperture Edge", "The main edge of the DapperMap aperture.", 0x7B6CFF),
        TICKS("ticks", "Frame", "Cardinal Marks", "The dashes and cardinal marks around the map edge.", 0x57D8D0),
        CARDINAL_TEXT("cardinal-text", "Text", "Compass Directions", "The N, E, S and W labels on the minimap.", 0x57D8D0),
        PANEL("panel", "Panel", "Information Panel", "The translucent panel holding map information.", 0x111426),
        BRAND_TEXT("brand-text", "Text", "DapperMap Label", "The DapperMap instrument label.", 0xB8AEFF),
        INFO_HEADERS("info-headers", "Text", "Information Headings", "The heading above each information-panel value.", 0x57D8D0),
        COORDS_TEXT("coords-text", "Text", "Coordinates", "The world-coordinate text.", 0xF4F2FF),
        COMPASS_TEXT("compass-text", "Text", "Bearing", "The facing-direction text.", 0xF4F2FF),
        CLOCK_TEXT("clock-text", "Text", "World Time", "The Hytale world-time text.", 0xF4F2FF),
        BIOME_TEXT("biome-text", "Text", "Region", "The current-biome text.", 0xF4F2FF),
        SELF("self", "Markers", "Your Locator", "The locator marking your position.", 0xF4C76B),
        PLAYERS("players", "Markers", "Other Players", "Nearby player markers.", 0x74A7FF),
        DEATH("death", "Markers", "Death Markers", "Tint for Hytale's death markers (white keeps the original look).", 0xF2F0FF),
        HOSTILE_ACTIVE("hostile-active", "Markers", "Attacking Creatures", "Hostiles currently targeting you.", 0xFF9566),
        HOSTILE("hostile", "Markers", "Hostile Creatures", "Nearby hostile creatures.", 0xFD3E5A),
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
    private static final DynamicMinimapColours DEFAULT = new DynamicMinimapColours();
    public static DynamicMinimapColours defaults() { return DEFAULT; }

    private final int[] values;
    private final String[] hexValues;
    private final Color[] opaqueColours;
    private final String frameKey;
    private final String arrowKey;

    public DynamicMinimapColours() {
        this(Arrays.stream(Key.values()).mapToInt(key -> key.defaultRgb).toArray());
    }

    private DynamicMinimapColours(int[] values) {
        this.values = values;
        this.hexValues = new String[values.length];
        this.opaqueColours = new Color[values.length];
        for (int i = 0; i < values.length; i++) {
            this.hexValues[i] = "#" + dev.scyphoz.dappermap.util.DapperMapUtil.hex6(values[i]);
            this.opaqueColours[i] = new Color(0xFF000000 | values[i], true);
        }
        this.frameKey = hexValues[Key.EDGE.ordinal()].substring(1)
                + hexValues[Key.TICKS.ordinal()].substring(1)
                + hexValues[Key.CARDINAL_TEXT.ordinal()].substring(1);
        this.arrowKey = hexValues[Key.SELF.ordinal()].substring(1);
    }

    public int rgb(Key key) { return values[key.ordinal()]; }
    public String hex(Key key) { return hexValues[key.ordinal()]; }
    public Color awt(Key key, int alpha) {
        return alpha == 255 ? opaqueColours[key.ordinal()] : new Color((alpha << 24) | rgb(key), true);
    }
    public DynamicMinimapColours with(Key key, int rgb) {
        if (key == null || rgb < 0 || rgb > 0xffffff) throw new IllegalArgumentException("Invalid RGB colour");
        if (rgb(key) == rgb) return this;
        int[] next = values.clone(); next[key.ordinal()] = rgb; return new DynamicMinimapColours(next);
    }
    public DynamicMinimapColours reset(Key key) { return with(key, key.defaultRgb); }
    public String frameKey() { return frameKey; }
    public String arrowKey() { return arrowKey; }
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
    @Override public boolean equals(Object other) { return other instanceof DynamicMinimapColours p && Arrays.equals(values, p.values); }
    @Override public int hashCode() { return Arrays.hashCode(values); }
}

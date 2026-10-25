package dev.scyphoz.dappermap.dynamic;

import java.awt.Color;
import java.util.Arrays;

/** Immutable per-player RGB palette. Existing opacity and terrain colours are retained. */
public final class DynamicMinimapColours {
    public enum Key {
        // id: the name used by /mm colours and the settings page; storeId: the key saved in settings
        // files (unchanged since earlier versions, so players' saved colours keep loading).
        EDGE("map-edge", "edge", "Frame", "Map Edge", "The bright edge around the map.", 0x7B6CFF),
        TICKS("cardinal-dashes", "ticks", "Frame", "Cardinal Dashes", "The large N, E, S and W dashes, and the outline of the letter badges.", 0x57D8D0),
        COMPASS_DASHES("compass-dashes", "compass-dashes", "Frame", "Compass Dashes", "The small dashes around the map edge.", 0x57D8D0),
        CARDINAL_TEXT("compass-letters", "cardinal-text", "Frame", "Compass Letters", "The N, E, S and W letters on the map edge.", 0x57D8D0),
        PANEL("panel-background", "panel", "Info Panel", "Background", "The translucent Info Panel background.", 0x111426),
        BRAND_TEXT("panel-title", "brand-text", "Info Panel", "Title", "The DapperMap title at the top of the Info Panel.", 0xB8AEFF),
        INFO_HEADERS("panel-headings", "info-headers", "Info Panel", "Headings", "The heading above each Info Panel value.", 0x57D8D0),
        COORDS_TEXT("coordinates", "coords-text", "Info Panel", "Coordinates", "The world coordinates text.", 0xF4F2FF),
        COMPASS_TEXT("bearing", "compass-text", "Info Panel", "Bearing", "The bearing (facing direction) text.", 0xF4F2FF),
        CLOCK_TEXT("world-time", "clock-text", "Info Panel", "World Time", "The Hytale world time text.", 0xF4F2FF),
        BIOME_TEXT("region", "biome-text", "Info Panel", "Region", "The current region (biome) text.", 0xF4F2FF),
        SELF("locator", "self", "Markers", "Your Locator", "The locator marking your position.", 0xF4C76B),
        PLAYERS("players", "players", "Markers", "Other Players", "Nearby player markers.", 0x74A7FF),
        DEATH("death", "death", "Markers", "Death Markers", "Tint for Hytale's death markers (white keeps the original look).", 0xF2F0FF),
        HOSTILE_ACTIVE("attacking", "hostile-active", "Markers", "Attacking", "Creatures targeting you, and players attacking you in PvP.", 0xFF9566),
        HOSTILE("hostile", "hostile", "Markers", "Hostile Creatures", "Nearby hostile creatures.", 0xFD3E5A),
        NEUTRAL("neutral", "neutral", "Markers", "Neutral Creatures", "Neutral or retreating creatures.", 0xE9CA68),
        FRIENDLY("friendly", "friendly", "Markers", "Friendly Creatures", "Nearby friendly creatures.", 0x69D69A);
        public final String id, storeId, group, label, description;
        public final int defaultRgb;
        Key(String id, String storeId, String group, String label, String description, int defaultRgb) {
            this.id = id; this.storeId = storeId; this.group = group; this.label = label;
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
        // Frame images hold the edge and marks only (the letters are native labels), so a
        // letter colour change re-colours the labels without redrawing every heading's frame.
        this.frameKey = hexValues[Key.EDGE.ordinal()].substring(1)
                + hexValues[Key.TICKS.ordinal()].substring(1)
                + hexValues[Key.COMPASS_DASHES.ordinal()].substring(1);
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

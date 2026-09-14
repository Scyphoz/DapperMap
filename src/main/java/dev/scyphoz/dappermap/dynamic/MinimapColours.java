package dev.scyphoz.dappermap.dynamic;

import java.awt.Color;
import java.util.Arrays;
import java.util.Locale;

/** Immutable per-player RGB palette. Existing opacity and terrain colours are retained. */
public final class MinimapColours {
    public enum Key {
        EDGE("edge", "Frame", "Outside Edge", "The outer rim and large frame ticks.", 0xe0ae52),
        TICKS("ticks", "Frame", "Small Frame Ticks", "The small marks around the frame.", 0xced6e2),
        CARDINALS("cardinals", "Frame", "Compass Letters", "The N, E, S and W letters and north pointer.", 0xffdc50),
        PANEL("panel", "Panel", "Panel Background", "The background around the information rows.", 0x071016),
        COORDS_BACKGROUND("coords-background", "Panel", "Coordinates Background", "The background behind your coordinates.", 0x102232),
        COMPASS_BACKGROUND("compass-background", "Panel", "Direction Background", "The background behind your facing direction.", 0x132733),
        CLOCK_BACKGROUND("clock-background", "Panel", "Clock Background", "The background behind the world clock.", 0x183040),
        BIOME_BACKGROUND("biome-background", "Panel", "Biome Background", "The background behind the biome name.", 0x0f1f29),
        BRAND_TEXT("brand-text", "Text", "Mod Name Text", "The DapperMap name above the information.", 0xffd66b),
        COORDS_TEXT("coords-text", "Text", "Coordinates Text", "The text showing your world coordinates.", 0xf5f7fb),
        COMPASS_TEXT("compass-text", "Text", "Direction Text", "The text showing where you are facing.", 0xffd66b),
        CLOCK_TEXT("clock-text", "Text", "Clock Text", "The text showing the Hytale world time.", 0x9ddcff),
        BIOME_TEXT("biome-text", "Text", "Biome Text", "The text showing your current biome.", 0xcfe0ef),
        SELF("self", "Markers", "Your Arrow", "The arrow marking your position.", 0x28ff64),
        PLAYERS("players", "Markers", "Other Players", "The dots marking nearby players.", 0x508cff),
        DEATH("death", "Markers", "Death Marker", "The cross marking your last death.", 0xffffff),
        HOSTILE_ACTIVE("hostile-active", "Markers", "Attacking Creatures", "Hostile creatures targeting you.", 0xdc2828),
        HOSTILE("hostile", "Markers", "Hostile Creatures", "Hostile creatures not targeting you.", 0xff8c00),
        NEUTRAL("neutral", "Markers", "Neutral Creatures", "Neutral or retreating creatures.", 0xe6c828),
        FRIENDLY("friendly", "Markers", "Friendly Creatures", "Creatures friendly towards you.", 0x3cd25a);
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
        for (Key key : new Key[]{Key.EDGE, Key.TICKS, Key.CARDINALS})
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

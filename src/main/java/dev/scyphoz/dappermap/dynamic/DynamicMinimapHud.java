package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Objects;
import java.util.UUID;

public final class DynamicMinimapHud extends CustomUIHud {
    public static final String KEY = "DapperMapDynamicMinimapHud";
    public static final int MARKER_SLOTS = 48;
    private final PlayerRef ownerRef;
    private final UUID uuid;
    private final DynamicMinimapSetting settings;
    /** Logical Hytale UI size. This is deliberately separate from raster texture resolution. */
    private final int uiSize;
    private final int bufferPixels;
    private final int selfSize;
    private final String slug;
    private final String maskFile;
    private final String blankPath;
    private final String framePath;
    private String selfPath;
    private boolean built = false;
    private boolean terrainShown = false;
    private int position;
    private int panLeft = 0;
    private int panTop = 0;
    private String coordsText = "X: ?  Y: ?  Z: ?";
    private String compassText = "N 0 deg";
    private String clockText = "Sun 06:00";
    private String biomeText = "Biome: unknown";
    private final String[] slotPath = new String[48];
    private final boolean[] slotVisible = new boolean[48];
    private final int[] slotLeft = new int[48];
    private final int[] slotTop = new int[48];
    private final int[] slotSize = new int[48];

    public DynamicMinimapHud(PlayerRef playerRef, DynamicMinimapSetting settings) {
        super(playerRef, "DapperMapDynamicMinimapHud");
        this.ownerRef = playerRef;
        this.uuid = playerRef.getUuid();
        this.settings = settings.copy();
        this.settings.normalize();
        this.position = this.settings.position;
        this.uiSize = this.settings.sizePixels;
        this.bufferPixels = DynamicMinimapRenderer.bufferPixels(this.settings);
        this.selfSize = Math.max(18, this.uiSize / 10);
        this.slug = this.uuid != null ? this.uuid.toString().replace("-", "") : "anon";
        String var10000 = this.settings.isCircle() ? "circle" : "square";
        String shapeId = var10000 + "-" + this.uiSize + "-r" + this.settings.textureResolution + "-nautical" + (this.settings.showBorder ? "-b" : "");
        this.maskFile = "DapperMap-" + this.slug + "-" + shapeId + "-mask.png";
        this.blankPath = "UI/DapperMap/blank-" + this.slug + ".png";
        this.framePath = "UI/DapperMap/frame-" + this.slug + "-" + shapeId + ".png";
        this.selfPath = "UI/DapperMap/self-" + this.slug + "-0.png";
    }

    public int bufferPixels() {
        return this.bufferPixels;
    }

    public boolean isShown() {
        return this.built;
    }

    public void deliverStaticAssets() {
        byte[] blank = DynamicMinimapRenderer.renderBlank();
        if (blank != null) {
            DynamicAssetPublisher.deliver(this.ownerRef, this.blankPath, blank);
        }

        byte[] frame = DynamicMinimapRenderer.renderFrame(this.settings);
        if (frame != null) {
            DynamicAssetPublisher.deliver(this.ownerRef, this.framePath, frame);
        }

        byte[] self = DynamicMinimapRenderer.renderSelfArrow((double)0.0F);
        if (self != null) {
            DynamicAssetPublisher.deliver(this.ownerRef, this.selfPath, self);
        }

        byte[] mask = DynamicMinimapRenderer.renderShapeMask(this.settings);
        if (mask != null) {
            DynamicAssetPublisher.deliverWithRebuild(this.ownerRef, "UI/Custom/" + this.maskFile, mask);
        }

    }

    public void show() {
        UICommandBuilder builder = new UICommandBuilder();
        builder.appendInline((String)null, this.buildMarkup());
        this.writeStatus(builder);
        this.update(true, builder);
        this.built = true;
    }

    protected void build(UICommandBuilder builder) {
        builder.appendInline((String)null, this.buildMarkup());
        this.writeStatus(builder);
    }

    public void setTerrain(String assetPath, int left, int top) {
        if (this.built && assetPath != null) {
            this.panLeft = left;
            this.panTop = top;
            UICommandBuilder builder = new UICommandBuilder();
            builder.set("#WayfinderMmRoot #WayfinderMmClip #WayfinderMmTerrain.AssetPath", assetPath);
            builder.setObject("#WayfinderMmRoot #WayfinderMmClip #WayfinderMmTerrain.Anchor", this.fullAnchor(left, top));
            builder.setObject("#WayfinderMmRoot #WayfinderMmClip #WayfinderMmMarkers.Anchor", this.fullAnchor(left, top));
            builder.set("#WayfinderMmRoot #WayfinderMmClip #WayfinderMmTerrain.Visible", true);
            this.terrainShown = true;
            this.update(false, builder);
        }

    }

    public void pan(int left, int top) {
        if (this.built && this.terrainShown && (left != this.panLeft || top != this.panTop)) {
            this.panLeft = left;
            this.panTop = top;
            UICommandBuilder builder = new UICommandBuilder();
            builder.setObject("#WayfinderMmRoot #WayfinderMmClip #WayfinderMmTerrain.Anchor", this.fullAnchor(left, top));
            builder.setObject("#WayfinderMmRoot #WayfinderMmClip #WayfinderMmMarkers.Anchor", this.fullAnchor(left, top));
            this.update(false, builder);
        }

    }

    public void setSelf(String assetPath) {
        if (this.built && assetPath != null && !assetPath.equals(this.selfPath)) {
            this.selfPath = assetPath;
            UICommandBuilder builder = new UICommandBuilder();
            builder.set("#WayfinderMmRoot #WayfinderMmSelf.AssetPath", assetPath);
            this.update(false, builder);
        }

    }

    public void setMarkers(Slot[] slots) {
        if (this.built) {
            UICommandBuilder builder = new UICommandBuilder();
            boolean changed = false;

            for(int i = 0; i < 48; ++i) {
                Slot slot = slots != null && i < slots.length ? slots[i] : null;
                String path = slot != null ? slot.path : null;
                boolean visible = path != null;
                int left = visible ? slot.left : 0;
                int top = visible ? slot.top : 0;
                int size = visible ? slot.size : 0;
                if (!Objects.equals(this.slotPath[i], path) || this.slotVisible[i] != visible || this.slotLeft[i] != left || this.slotTop[i] != top || this.slotSize[i] != size) {
                    this.slotPath[i] = path;
                    this.slotVisible[i] = visible;
                    this.slotLeft[i] = left;
                    this.slotTop[i] = top;
                    this.slotSize[i] = size;
                    String selector = "#WayfinderMmRoot #WayfinderMmClip #WayfinderMmMarkers #WayfinderMmMk" + i;
                    builder.setObject(selector + ".Anchor", anchor(left, top, size, size));
                    builder.set(selector + ".AssetPath", visible ? path : this.blankPath);
                    builder.set(selector + ".Visible", visible);
                    changed = true;
                }
            }

            if (changed) {
                this.update(false, builder);
            }
        }

    }

    public void setCoords(String coords) {
        if (coords != null && !coords.equals(this.coordsText)) {
            this.coordsText = coords;
            if (this.built) {
                UICommandBuilder builder = new UICommandBuilder();
                builder.set("#WayfinderMmRoot #WayfinderMmReadout #WayfinderMmCoords.TextSpans", Message.raw(this.coordsText));
                this.update(false, builder);
            }
        }

    }

    public void setStatus(String coords, String compass, String clock, String biome) {
        boolean changed = false;
        if (coords != null && !coords.equals(this.coordsText)) {
            this.coordsText = coords;
            changed = true;
        }

        if (compass != null && !compass.equals(this.compassText)) {
            this.compassText = compass;
            changed = true;
        }

        if (clock != null && !clock.equals(this.clockText)) {
            this.clockText = clock;
            changed = true;
        }

        if (biome != null && !biome.equals(this.biomeText)) {
            this.biomeText = biome;
            changed = true;
        }

        if (this.built && changed) {
            UICommandBuilder builder = new UICommandBuilder();
            this.writeStatus(builder);
            this.update(false, builder);
        }

    }

    public void removeFromClient() {
        if (this.built) {
            UICommandBuilder builder = new UICommandBuilder();
            builder.remove("#WayfinderMmRoot");
            this.update(false, builder);
            this.built = false;
            this.terrainShown = false;

            for(int i = 0; i < 48; ++i) {
                this.slotPath[i] = null;
                this.slotVisible[i] = false;
            }
        }

    }

    public void setPosition(int position) {
        this.position = position;
        if (this.built) {
            Anchor anchor = new Anchor();
            if (this.anchorRight()) {
                anchor.setRight(Value.of(10));
            } else {
                anchor.setLeft(Value.of(10));
            }

            if (this.anchorBottom()) {
                anchor.setBottom(Value.of(10));
            } else {
                anchor.setTop(Value.of(10));
            }

            anchor.setWidth(Value.of(this.uiSize));
            anchor.setHeight(Value.of(this.rootHeight()));
            UICommandBuilder builder = new UICommandBuilder();
            builder.setObject("#WayfinderMmRoot.Anchor", anchor);
            this.update(false, builder);
        }

    }

    private String buildMarkup() {
        int selfInset = (this.uiSize - this.selfSize) / 2;
        StringBuilder out = new StringBuilder(4096);
        out.append("Group #WayfinderMmRoot { ").append("Anchor: (").append(this.rootAnchorEdges()).append(", Width: ").append(this.uiSize).append(", Height: ").append(this.rootHeight()).append("); ");
        out.append("Group #WayfinderMmClip { ").append("Anchor: (Left: 0, Top: 0, Width: ").append(this.uiSize).append(", Height: ").append(this.uiSize).append("); ").append("MaskTexturePath: \"").append(this.maskFile).append("\"; ").append("Background: #0a1218(0.92); ");
        out.append("AssetImage #WayfinderMmTerrain { ").append("Anchor: (Left: ").append(this.panLeft).append(", Top: ").append(this.panTop).append(", Width: ").append(this.bufferPixels).append(", Height: ").append(this.bufferPixels).append("); AssetPath: \"").append(this.blankPath).append("\"; Visible: false; } ");
        out.append("Group #WayfinderMmMarkers { ").append("Anchor: (Left: ").append(this.panLeft).append(", Top: ").append(this.panTop).append(", Width: ").append(this.bufferPixels).append(", Height: ").append(this.bufferPixels).append("); ");

        for(int i = 0; i < 48; ++i) {
            out.append("AssetImage #WayfinderMmMk").append(i).append(" { Anchor: (Left: 0, Top: 0, Width: 0, Height: 0); AssetPath: \"").append(this.blankPath).append("\"; Visible: false; } ");
        }

        out.append("} } ");
        out.append("AssetImage #WayfinderMmFrame { Anchor: (Left: 0, Top: 0, Width: ").append(this.uiSize).append(", Height: ").append(this.uiSize).append("); AssetPath: \"").append(this.framePath).append("\"; Visible: true; } ");
        out.append("AssetImage #WayfinderMmSelf { Anchor: (Left: ").append(selfInset).append(", Top: ").append(selfInset).append(", Width: ").append(this.selfSize).append(", Height: ").append(this.selfSize).append("); AssetPath: \"").append(this.selfPath).append("\"; Visible: true; } ");
        out.append("Group #WayfinderMmReadout { Anchor: (Left: 0, Top: ").append(this.uiSize + 4).append(", Width: ").append(this.uiSize).append(", Height: 88); Background: #071016(0.96); LayoutMode: Top; Padding: (Horizontal: 8, Vertical: 5); ").append("Label #WayfinderMmBrand { Anchor: (Height: 12); Text: \"DapperMap\"; Style: LabelStyle(FontSize: 9, RenderBold: true, TextColor: #ffd66b, HorizontalAlignment: Center, VerticalAlignment: Center); } ").append("Group #WayfinderMmCoordsBand { Anchor: (Height: 22); Background: #102232(0.98); LayoutMode: CenterMiddle; ").append("Label #WayfinderMmCoords { Style: LabelStyle(FontSize: 12, RenderBold: true, TextColor: #f5f7fb, HorizontalAlignment: Center, VerticalAlignment: Center); } } ").append("Group #WayfinderMmMetaBand { Anchor: (Height: 26); LayoutMode: Left; Padding: (Top: 4); ").append("Group #WayfinderMmCompassBox { FlexWeight: 1; Background: #132733(0.9); LayoutMode: CenterMiddle; Padding: (Right: 3); ").append("Label #WayfinderMmCompass { Style: LabelStyle(FontSize: 10, RenderBold: true, TextColor: #ffd66b, HorizontalAlignment: Center, VerticalAlignment: Center); } } ").append("Group #WayfinderMmClockBox { FlexWeight: 1; Background: #183040(0.9); LayoutMode: CenterMiddle; Padding: (Left: 3); ").append("Label #WayfinderMmClock { Style: LabelStyle(FontSize: 10, RenderBold: true, TextColor: #9ddcff, HorizontalAlignment: Center, VerticalAlignment: Center); } } ").append("} ").append("Group #WayfinderMmBiomeBand { Anchor: (Height: 18); Background: #0f1f29(0.92); LayoutMode: CenterMiddle; ").append("Label #WayfinderMmBiome { Style: LabelStyle(FontSize: 9, RenderBold: true, TextColor: #cfe0ef, HorizontalAlignment: Center, VerticalAlignment: Center); } } ").append("} ");
        out.append("}");
        return out.toString();
    }

    private int rootHeight() {
        return this.uiSize + 4 + 88;
    }

    private void writeStatus(UICommandBuilder builder) {
        builder.set("#WayfinderMmRoot #WayfinderMmReadout #WayfinderMmCoords.TextSpans", Message.raw(this.coordsText));
        builder.set("#WayfinderMmRoot #WayfinderMmReadout #WayfinderMmCompass.TextSpans", Message.raw(this.compassText));
        builder.set("#WayfinderMmRoot #WayfinderMmReadout #WayfinderMmClock.TextSpans", Message.raw(this.clockText));
        builder.set("#WayfinderMmRoot #WayfinderMmReadout #WayfinderMmBiome.TextSpans", Message.raw(this.biomeText));
    }

    private boolean anchorRight() {
        return this.position == 1 || this.position == 3;
    }

    private boolean anchorBottom() {
        return this.position == 2 || this.position == 3;
    }

    private String rootAnchorEdges() {
        String horizontal = this.anchorRight() ? "Right: 10" : "Left: 10";
        String vertical = this.anchorBottom() ? "Bottom: 10" : "Top: 10";
        return horizontal + ", " + vertical;
    }

    private Anchor fullAnchor(int left, int top) {
        return anchor(left, top, this.bufferPixels, this.bufferPixels);
    }

    private static Anchor anchor(int left, int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(left));
        anchor.setTop(Value.of(top));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    public static final class Slot {
        public final String path;
        public final int left;
        public final int top;
        public final int size;

        public Slot(String path, int left, int top, int size) {
            this.path = path;
            this.left = left;
            this.top = top;
            this.size = size;
        }
    }
}

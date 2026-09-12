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

    // All constants below are logical HUD units. They are converted to display units
    // only when markup/Anchor values are produced.
    private static final int HUD_MARGIN = 10;
    private static final int READOUT_GAP = 4;
    private static final int READOUT_HEIGHT = 88;
    private static final int SIDE_READOUT_WIDTH = 140;
    private static final int READOUT_PADDING_H = 8;
    private static final int READOUT_PADDING_V = 5;
    private static final int BRAND_HEIGHT = 12;
    private static final int COORDS_HEIGHT = 22;
    private static final int META_HEIGHT = 26;
    private static final int BIOME_HEIGHT = 18;
    private static final int META_TOP_PADDING = 4;
    private static final int META_INNER_PADDING = 3;
    private static final int BRAND_FONT_SIZE = 9;
    private static final int COORDS_FONT_SIZE = 12;
    private static final int META_FONT_SIZE = 10;
    private static final int BIOME_FONT_SIZE = 9;

    private final PlayerRef ownerRef;
    private final UUID uuid;
    private final DynamicMinimapSetting settings;
    /** Base logical UI size. World/pan/marker math remains in this coordinate space. */
    private int logicalUiSize;
    /** Logical terrain-buffer size. Kept compatible with DynamicMinimapSession. */
    private int bufferPixels;
    /** Base logical size of the self arrow. */
    private int selfSize;
    /**
     * Mutable presentation-only scale. Kept separate from the copied settings so
     * scale changes can be applied to the existing HUD without recreating it.
     */
    private int interfaceScalePercent;
    private final String slug;
    private String maskFile;
    private final String blankPath;
    private String framePath;
    private String selfPath;
    private String terrainPath;
    private boolean built = false;
    private boolean terrainShown = false;
    private int hudPositionX;
    private int hudPositionY;
    private int infoPanelPosition;
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
        this.hudPositionX = this.settings.hudPositionX;
        this.hudPositionY = this.settings.hudPositionY;
        this.infoPanelPosition = this.settings.infoPanelPosition;
        this.logicalUiSize = this.settings.sizePixels;
        this.bufferPixels = DynamicMinimapRenderer.bufferPixels(this.settings);
        this.selfSize = Math.max(18, this.logicalUiSize / 10);
        this.interfaceScalePercent = this.settings.interfaceScalePercent;
        this.slug = this.uuid != null ? this.uuid.toString().replace("-", "") : "anon";
        this.blankPath = "UI/DapperMap/blank-" + this.slug + ".png";
        this.selfPath = "UI/DapperMap/self-" + this.slug + "-0.png";
        this.terrainPath = this.blankPath;
        this.updateStaticAssetPaths();
    }

    /** Logical terrain-buffer size expected by the existing session/world calculations. */
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

        byte[] self = DynamicMinimapRenderer.renderSelfArrow(0.0D);
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

    /** left/top are logical minimap coordinates supplied by DynamicMinimapSession. */
    public void setTerrain(String assetPath, int left, int top) {
        if (this.built && assetPath != null) {
            this.terrainPath = assetPath;
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

    /** left/top stay logical; conversion happens only when the Anchor is emitted. */
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

    /**
     * Swaps only the compass/frame asset. Used by player-up rotation mode so the
     * compass markings track the rotated terrain without rebuilding the HUD.
     */
    public void setFrame(String assetPath) {
        if (this.built && assetPath != null && !assetPath.equals(this.framePath)) {
            this.framePath = assetPath;
            UICommandBuilder builder = new UICommandBuilder();
            builder.set("#WayfinderMmRoot #WayfinderMmFrame.AssetPath", assetPath);
            this.update(false, builder);
        }
    }

    /** Slot coordinates and sizes are logical minimap values, converted here for display. */
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
                if (!Objects.equals(this.slotPath[i], path)
                        || this.slotVisible[i] != visible
                        || this.slotLeft[i] != left
                        || this.slotTop[i] != top
                        || this.slotSize[i] != size) {
                    this.slotPath[i] = path;
                    this.slotVisible[i] = visible;
                    this.slotLeft[i] = left;
                    this.slotTop[i] = top;
                    this.slotSize[i] = size;
                    String selector = "#WayfinderMmRoot #WayfinderMmClip #WayfinderMmMarkers #WayfinderMmMk" + i;
                    builder.setObject(selector + ".Anchor", anchor(
                            this.displayCoordinate(left),
                            this.displayCoordinate(top),
                            this.displaySize(size),
                            this.displaySize(size)));
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

    /**
     * Backward-compatible four-corner positioning entry point.
     */
    public void setPosition(int position) {
        int x;
        int y;

        switch (position) {
            case DynamicMinimapSetting.POS_TOP_LEFT -> {
                x = 0;
                y = 0;
            }
            case DynamicMinimapSetting.POS_BOTTOM_LEFT -> {
                x = 0;
                y = 100;
            }
            case DynamicMinimapSetting.POS_BOTTOM_RIGHT -> {
                x = 100;
                y = 100;
            }
            case DynamicMinimapSetting.POS_TOP_RIGHT -> {
                x = 100;
                y = 0;
            }
            default -> {
                x = 100;
                y = 0;
            }
        }

        this.setLayoutPosition(x, y, this.infoPanelPosition);
    }

    /**
     * Moves the complete minimap widget and/or relocates the information panel.
     * The Custom HUD root remains mounted; only the positioning subtree is
     * replaced, matching the flicker-free strategy used elsewhere.
     */
    public void setLayoutPosition(
            int xPercent,
            int yPercent,
            int panelPosition
    ) {
        int x = Math.max(0, Math.min(100, xPercent));
        int y = Math.max(0, Math.min(100, yPercent));
        int panel = Math.max(
                DynamicMinimapSetting.INFO_BELOW,
                Math.min(DynamicMinimapSetting.INFO_RIGHT, panelPosition)
        );

        if (x == this.hudPositionX
                && y == this.hudPositionY
                && panel == this.infoPanelPosition) {
            return;
        }

        this.hudPositionX = x;
        this.hudPositionY = y;
        this.infoPanelPosition = panel;

        if (this.built) {
            this.rebuildPositionLayout();
        }
    }

    /**
     * Changes the logical minimap size without recreating the CustomUIHud root.
     *
     * Size affects map/world coordinate space and buffer dimensions, so the
     * map subtree is replaced atomically while the root HUD remains mounted.
     * Static mask/frame assets are reused at every size. Terrain is intentionally
     * reset to blank until
     * the session delivers a freshly rendered buffer for the new logical size.
     */
    public void setMapSize(int newSize) {
        int normalized = Math.max(120, Math.min(360, newSize));
        if (normalized == this.logicalUiSize) {
            return;
        }

        this.settings.sizePixels = normalized;
        this.settings.normalize();

        this.logicalUiSize = this.settings.sizePixels;
        this.bufferPixels = DynamicMinimapRenderer.bufferPixels(this.settings);
        this.selfSize = Math.max(18, this.logicalUiSize / 10);

        // Frame and mask are reused across logical map sizes. Only terrain and
        // layout geometry depend on size at runtime.
        this.panLeft = 0;
        this.panTop = 0;
        this.terrainPath = this.blankPath;
        this.terrainShown = false;

        if (!this.built) {
            return;
        }

        this.rebuildPositionLayout();
    }

    /**
     * Applies a presentation-scale change to the existing HUD tree.
     *
     * This intentionally avoids removeFromClient(), appendInline(), update(true),
     * and static-asset rebuilds. World/map coordinates remain logical and are
     * converted only when Anchor values are emitted.
     */
    public void setInterfaceScale(int percent) {
        int normalized = Math.max(75, Math.min(160, percent));
        if (normalized == this.interfaceScalePercent) {
            return;
        }

        this.interfaceScalePercent = normalized;

        if (this.built) {
            this.rebuildPositionLayout();
        }
    }

    private String buildMarkup() {
        StringBuilder out = new StringBuilder(6200);
        out.append("Group #WayfinderMmRoot { ")
                .append("Anchor: (Full: 0); ")
                .append("Padding: (Full: ").append(this.displaySize(HUD_MARGIN)).append("); ")
                .append("LayoutMode: Full; ");

        out.append(this.buildPositionLayoutMarkup());
        out.append("}");
        return out.toString();
    }

    /**
     * Creates a resolution-independent positioning layout.
     *
     * The fixed-size widget consumes its own width/height. Flex spacers divide
     * only the remaining screen space, so X/Y percentages naturally describe
     * the widget's position while keeping it fully on screen.
     */
    private String buildPositionLayoutMarkup() {
        int widgetWidth = this.widgetWidth();
        int widgetHeight = this.widgetHeight();

        StringBuilder out = new StringBuilder(5600);
        out.append("Group #WayfinderMmPositionLayout { Anchor: (Full: 0); LayoutMode: Top; ");

        if (this.hudPositionY > 0) {
            out.append("Group #WayfinderMmTopSpacer { FlexWeight: ")
                    .append(this.hudPositionY)
                    .append("; } ");
        }

        out.append("Group #WayfinderMmPositionRow { Anchor: (Height: ")
                .append(widgetHeight)
                .append("); LayoutMode: Left; ");

        if (this.hudPositionX > 0) {
            out.append("Group #WayfinderMmLeftSpacer { FlexWeight: ")
                    .append(this.hudPositionX)
                    .append("; } ");
        }

        out.append("Group #WayfinderMmWidget { Anchor: (Width: ")
                .append(widgetWidth)
                .append(", Height: ").append(widgetHeight)
                .append("); LayoutMode: Full; ");

        out.append(this.buildMapMarkup());
        out.append(this.buildReadoutMarkup());
        out.append("} ");

        if (this.hudPositionX < 100) {
            out.append("Group #WayfinderMmRightSpacer { FlexWeight: ")
                    .append(100 - this.hudPositionX)
                    .append("; } ");
        }

        out.append("} ");

        if (this.hudPositionY < 100) {
            out.append("Group #WayfinderMmBottomSpacer { FlexWeight: ")
                    .append(100 - this.hudPositionY)
                    .append("; } ");
        }

        out.append("} ");
        return out.toString();
    }

    /**
     * Builds the map subtree at the origin determined by the selected
     * information-panel side.
     */
    private String buildMapMarkup() {
        int displayUiSize = this.displaySize(this.logicalUiSize);
        int displayBuffer = this.displaySize(this.bufferPixels);
        int mapLeft = this.mapOriginX();
        int mapTop = this.mapOriginY();
        int selfInset = this.displayCoordinate((this.logicalUiSize - this.selfSize) / 2);
        int displaySelfSize = this.displaySize(this.selfSize);

        StringBuilder out = new StringBuilder(3200);
        out.append("Group #WayfinderMmClip { ")
                .append("Anchor: (Left: ").append(mapLeft)
                .append(", Top: ").append(mapTop)
                .append(", Width: ").append(displayUiSize)
                .append(", Height: ").append(displayUiSize).append("); ")
                .append("MaskTexturePath: \"").append(this.maskFile).append("\"; ")
                .append("Background: #0a1218(0.92); ");

        out.append("AssetImage #WayfinderMmTerrain { ")
                .append("Anchor: (Left: ").append(this.displayCoordinate(this.panLeft))
                .append(", Top: ").append(this.displayCoordinate(this.panTop))
                .append(", Width: ").append(displayBuffer)
                .append(", Height: ").append(displayBuffer)
                .append("); AssetPath: \"").append(this.terrainPath)
                .append("\"; Visible: ").append(this.terrainShown).append("; } ");

        out.append("Group #WayfinderMmMarkers { ")
                .append("Anchor: (Left: ").append(this.displayCoordinate(this.panLeft))
                .append(", Top: ").append(this.displayCoordinate(this.panTop))
                .append(", Width: ").append(displayBuffer)
                .append(", Height: ").append(displayBuffer).append("); ");

        for (int i = 0; i < MARKER_SLOTS; ++i) {
            boolean visible = this.slotVisible[i] && this.slotPath[i] != null;
            out.append("AssetImage #WayfinderMmMk").append(i).append(" { Anchor: (Left: ")
                    .append(visible ? this.displayCoordinate(this.slotLeft[i]) : 0)
                    .append(", Top: ")
                    .append(visible ? this.displayCoordinate(this.slotTop[i]) : 0)
                    .append(", Width: ")
                    .append(visible ? this.displaySize(this.slotSize[i]) : 0)
                    .append(", Height: ")
                    .append(visible ? this.displaySize(this.slotSize[i]) : 0)
                    .append("); AssetPath: \"")
                    .append(visible ? this.slotPath[i] : this.blankPath)
                    .append("\"; Visible: ").append(visible).append("; } ");
        }

        out.append("} } ");

        out.append("AssetImage #WayfinderMmFrame { Anchor: (Left: ")
                .append(mapLeft).append(", Top: ").append(mapTop)
                .append(", Width: ").append(displayUiSize)
                .append(", Height: ").append(displayUiSize)
                .append("); AssetPath: \"").append(this.framePath)
                .append("\"; Visible: true; } ");

        out.append("AssetImage #WayfinderMmSelf { Anchor: (Left: ")
                .append(mapLeft + selfInset)
                .append(", Top: ").append(mapTop + selfInset)
                .append(", Width: ").append(displaySelfSize)
                .append(", Height: ").append(displaySelfSize)
                .append("); AssetPath: \"").append(this.selfPath)
                .append("\"; Visible: true; } ");

        return out.toString();
    }

    /**
     * Static visual assets are intentionally independent of logical map size.
     *
     * Hytale stretches the mask/frame AssetImage to the current UI Anchor, so a
     * circle/square mask and nautical frame can be reused at every map size.
     * This avoids introducing a new mask resource during live map-size changes.
     */
    private void updateStaticAssetPaths() {
        String shape = this.settings.isCircle() ? "circle" : "square";
        String shapeId = shape + "-r" + this.settings.textureResolution
                + "-nautical" + (this.settings.showBorder ? "-b" : "");

        this.maskFile = "DapperMap-" + this.slug + "-" + shapeId + "-mask.png";
        this.framePath = "UI/DapperMap/frame-" + this.slug + "-" + shapeId + ".png";
    }

    /**
     * Builds just the readout subtree using the current presentation scale.
     *
     * Keeping this as normal UI markup means FontSize and Padding are parsed by
     * the same client code used during the initial HUD build. On a scale change
     * we can replace only this child group instead of recreating the Custom HUD.
     */
    private String buildReadoutMarkup() {
        int readoutLeft = this.readoutOriginX();
        int readoutTop = this.readoutOriginY();
        int readoutWidth = this.readoutWidth();
        int readoutHeight = this.displaySize(READOUT_HEIGHT);

        StringBuilder out = new StringBuilder(2300);
        out.append("Group #WayfinderMmReadout { Anchor: (Left: ").append(readoutLeft)
                .append(", Top: ").append(readoutTop)
                .append(", Width: ").append(readoutWidth)
                .append(", Height: ").append(readoutHeight).append("); ")
                .append("Background: #071016(0.96); LayoutMode: Top; Padding: (Horizontal: ")
                .append(this.displaySize(READOUT_PADDING_H)).append(", Vertical: ")
                .append(this.displaySize(READOUT_PADDING_V)).append("); ")

                .append("Label #WayfinderMmBrand { Anchor: (Height: ")
                .append(this.displaySize(BRAND_HEIGHT)).append("); Text: \"DapperMap\"; ")
                .append("Style: LabelStyle(FontSize: ").append(this.displaySize(BRAND_FONT_SIZE))
                .append(", RenderBold: true, TextColor: #ffd66b, HorizontalAlignment: Center, VerticalAlignment: Center); } ")

                .append("Group #WayfinderMmCoordsBand { Anchor: (Height: ")
                .append(this.displaySize(COORDS_HEIGHT)).append("); Background: #102232(0.98); LayoutMode: CenterMiddle; ")
                .append("Label #WayfinderMmCoords { Style: LabelStyle(FontSize: ")
                .append(this.displaySize(COORDS_FONT_SIZE))
                .append(", RenderBold: true, TextColor: #f5f7fb, HorizontalAlignment: Center, VerticalAlignment: Center); } } ")

                .append("Group #WayfinderMmMetaBand { Anchor: (Height: ")
                .append(this.displaySize(META_HEIGHT)).append("); LayoutMode: Left; Padding: (Top: ")
                .append(this.displaySize(META_TOP_PADDING)).append("); ")
                .append("Group #WayfinderMmCompassBox { FlexWeight: 1; Background: #132733(0.9); LayoutMode: CenterMiddle; Padding: (Right: ")
                .append(this.displaySize(META_INNER_PADDING)).append("); ")
                .append("Label #WayfinderMmCompass { Style: LabelStyle(FontSize: ")
                .append(this.displaySize(META_FONT_SIZE))
                .append(", RenderBold: true, TextColor: #ffd66b, HorizontalAlignment: Center, VerticalAlignment: Center); } } ")
                .append("Group #WayfinderMmClockBox { FlexWeight: 1; Background: #183040(0.9); LayoutMode: CenterMiddle; Padding: (Left: ")
                .append(this.displaySize(META_INNER_PADDING)).append("); ")
                .append("Label #WayfinderMmClock { Style: LabelStyle(FontSize: ")
                .append(this.displaySize(META_FONT_SIZE))
                .append(", RenderBold: true, TextColor: #9ddcff, HorizontalAlignment: Center, VerticalAlignment: Center); } } ")
                .append("} ")

                .append("Group #WayfinderMmBiomeBand { Anchor: (Height: ")
                .append(this.displaySize(BIOME_HEIGHT)).append("); Background: #0f1f29(0.92); LayoutMode: CenterMiddle; ")
                .append("Label #WayfinderMmBiome { Style: LabelStyle(FontSize: ")
                .append(this.displaySize(BIOME_FONT_SIZE))
                .append(", RenderBold: true, TextColor: #cfe0ef, HorizontalAlignment: Center, VerticalAlignment: Center); } } ")
                .append("} ");

        return out.toString();
    }

    private void rebuildPositionLayout() {
        if (!this.built) {
            return;
        }

        UICommandBuilder builder = new UICommandBuilder();
        builder.remove("#WayfinderMmRoot #WayfinderMmPositionLayout");
        builder.appendInline("#WayfinderMmRoot", this.buildPositionLayoutMarkup());
        this.writeStatus(builder);
        this.update(false, builder);
    }

    private int widgetWidth() {
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || this.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT) {
            return this.displaySize(
                    this.logicalUiSize + READOUT_GAP + SIDE_READOUT_WIDTH
            );
        }

        return this.displaySize(this.logicalUiSize);
    }

    private int widgetHeight() {
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || this.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT) {
            return this.displaySize(this.logicalUiSize);
        }

        return this.displaySize(
                this.logicalUiSize + READOUT_GAP + READOUT_HEIGHT
        );
    }

    private int mapOriginX() {
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT) {
            return this.displaySize(SIDE_READOUT_WIDTH + READOUT_GAP);
        }

        return 0;
    }

    private int mapOriginY() {
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_ABOVE) {
            return this.displaySize(READOUT_HEIGHT + READOUT_GAP);
        }

        return 0;
    }

    private int readoutOriginX() {
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT) {
            return this.displaySize(this.logicalUiSize + READOUT_GAP);
        }

        return 0;
    }

    private int readoutOriginY() {
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_BELOW) {
            return this.displaySize(this.logicalUiSize + READOUT_GAP);
        }

        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || this.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT) {
            int logicalTop = Math.max(
                    0,
                    (this.logicalUiSize - READOUT_HEIGHT) / 2
            );
            return this.displayCoordinate(logicalTop);
        }

        return 0;
    }

    private int readoutWidth() {
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || this.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT) {
            return this.displaySize(SIDE_READOUT_WIDTH);
        }

        return this.displaySize(this.logicalUiSize);
    }

    private void writeStatus(UICommandBuilder builder) {
        builder.set("#WayfinderMmRoot #WayfinderMmReadout #WayfinderMmCoords.TextSpans", Message.raw(this.coordsText));
        builder.set("#WayfinderMmRoot #WayfinderMmReadout #WayfinderMmCompass.TextSpans", Message.raw(this.compassText));
        builder.set("#WayfinderMmRoot #WayfinderMmReadout #WayfinderMmClock.TextSpans", Message.raw(this.clockText));
        builder.set("#WayfinderMmRoot #WayfinderMmReadout #WayfinderMmBiome.TextSpans", Message.raw(this.biomeText));
    }




    private Anchor fullAnchor(int logicalLeft, int logicalTop) {
        return anchor(
                this.displayCoordinate(logicalLeft),
                this.displayCoordinate(logicalTop),
                this.displaySize(this.bufferPixels),
                this.displaySize(this.bufferPixels));
    }

    private int displayCoordinate(int logicalValue) {
        return Math.round(logicalValue * this.interfaceScalePercent / 100.0F);
    }

    private int displaySize(int logicalValue) {
        if (logicalValue <= 0) {
            return 0;
        }

        return Math.max(
                1,
                Math.round(logicalValue * this.interfaceScalePercent / 100.0F)
        );
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

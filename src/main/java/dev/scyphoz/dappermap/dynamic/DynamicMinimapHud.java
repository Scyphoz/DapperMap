package dev.scyphoz.dappermap.dynamic;

import static dev.scyphoz.dappermap.dynamic.MinimapColours.Key.*;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Owns the client-side HUD tree and applies small UI updates without rebuilding the
 * complete minimap whenever terrain, markers, readouts or layout values change.
 */
public final class DynamicMinimapHud extends CustomUIHud {
    public static final String KEY = "DapperMapDynamicMinimapHud";
    public static final int MARKER_SLOTS = 128;

    // All constants below are logical HUD units. They are converted to display units
    // only when markup/Anchor values are produced.
    private static final int HUD_MARGIN = 10;
    private static final int SQUARE_READOUT_GAP = 16;
    private static final int CIRCLE_READOUT_GAP = 8;
    /** Compact concept-style information panel used in every panel orientation. */
    private static final int READOUT_WIDTH = 148;
    private static final int READOUT_PADDING_V = 6;
    private static final int BRAND_HEIGHT = 24;
    private static final int COORDS_HEIGHT = 43;
    private static final int META_HEIGHT = 43;
    private static final int BIOME_HEIGHT = 43;

    private final PlayerRef ownerRef;
    private final Consumer<Runnable> worldExecute;
    private boolean overlayRenderQueued;
    private boolean overlayDirty;
    private long overlayGeneration;
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
    private String overlayPath;
    private int overlayRevision = 0;
    private double frameAngle = 0.0D;
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
    private final String[] slotPath = new String[MARKER_SLOTS];
    private final boolean[] slotVisible = new boolean[MARKER_SLOTS];
    private final int[] slotLeft = new int[MARKER_SLOTS];
    private final int[] slotTop = new int[MARKER_SLOTS];
    private final int[] slotSize = new int[MARKER_SLOTS];
    private int activeMarkerSlots = 0;

    public DynamicMinimapHud(PlayerRef playerRef, DynamicMinimapSetting settings,
                             Consumer<Runnable> worldExecute) {
        super(playerRef, KEY);
        this.ownerRef = playerRef;
        this.worldExecute = worldExecute;
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
        this.selfPath = "UI/DapperMap/self-" + this.slug + "-" + this.settings.colours.arrowKey() + "-0.png";
        this.terrainPath = this.blankPath;
        this.updateStaticAssetPaths();
    }

    /** Logical terrain-buffer size expected by the existing session/world calculations. */
    public int bufferPixels() {
        return this.bufferPixels;
    }

    /** Copy the mounted HUD state for a frozen settings preview. */
    public DynamicMinimapRenderer.LiveSnapshot snapshot(byte[] terrain, double selfAngle) {
        if (!this.built || !this.terrainShown || terrain == null) return null;
        java.util.List<DynamicMinimapRenderer.PreviewMarker> markers = new java.util.ArrayList<>();
        for (int i = 0; i < this.slotPath.length; i++) {
            if (!this.slotVisible[i] || this.slotPath[i] == null) continue;
            byte[] icon = DynamicAssetPublisher.markerBytes(this.uuid, this.slotPath[i]);
            if (icon != null) markers.add(new DynamicMinimapRenderer.PreviewMarker(
                    icon, this.panLeft + this.slotLeft[i], this.panTop + this.slotTop[i],
                    this.slotSize[i], this.slotPath[i].contains("/mk-") && this.slotSize[i] <= 9));
        }
        return new DynamicMinimapRenderer.LiveSnapshot(terrain.clone(), this.logicalUiSize,
                this.bufferPixels, this.panLeft, this.panTop, selfAngle, this.frameAngle,
                this.coordsText, this.compassText, this.clockText, this.biomeText,
                java.util.List.copyOf(markers));
    }

    public boolean isShown() {
        return this.built;
    }

    /** Populate the renderer's shared mask cache before the first world-thread HUD tick. */
    public static void prepareStaticAssets(DynamicMinimapSetting settings) {
        for (int resolution : new int[]{256, 320, 384, 448, 512, 1024}) {
            for (int shape : new int[]{DynamicMinimapSetting.SHAPE_SQUARE,
                    DynamicMinimapSetting.SHAPE_CIRCLE}) {
                DynamicMinimapSetting candidate = settings.copy();
                candidate.textureResolution = resolution;
                candidate.shape = shape;
                candidate.normalize();
                DynamicMinimapRenderer.renderShapeMask(candidate);
            }
        }
        DynamicMinimapRenderer.renderBlank();
    }

    public void deliverStaticAssets() {
        byte[] blank = DynamicMinimapRenderer.renderBlank();
        if (blank != null) {
            DynamicAssetPublisher.deliver(this.ownerRef, this.blankPath, blank);
        }

        this.publishOverlayAsset(false);

        byte[] self = DynamicMinimapRenderer.renderSelfArrow(0.0D, this.settings.colours);
        if (self != null) {
            DynamicAssetPublisher.deliver(this.ownerRef, this.selfPath, self);
        }

        // MaskTexturePath is resolved from Hytale's common UI asset set, unlike
        // ordinary AssetImage paths.  Publishing a previously unseen mask therefore
        // requires RequestCommonAssetsRebuild.  Doing that while an InteractiveCustomUIPage
        // (such as DapperMap's settings page) is open can cause the client to rebuild that
        // page mid-update, producing malformed/discoloured controls.
        //
        // Register every selectable shape/resolution combination before the one
        // initial common-assets rebuild. Future shape and texture-resolution
        // changes can then swap paths without rebuilding an open settings page.
        for (int resolution : new int[]{256, 320, 384, 448, 512, 1024}) {
            for (int shape : new int[]{
                    DynamicMinimapSetting.SHAPE_SQUARE,
                    DynamicMinimapSetting.SHAPE_CIRCLE}) {
                if (resolution == this.settings.textureResolution
                        && shape == this.settings.shape) {
                    continue;
                }

                DynamicMinimapSetting maskSettings = this.settings.copy();
                maskSettings.textureResolution = resolution;
                maskSettings.shape = shape;
                maskSettings.normalize();
                byte[] candidate = DynamicMinimapRenderer.renderShapeMask(maskSettings);
                if (candidate != null) {
                    DynamicAssetPublisher.deliver(
                            this.ownerRef,
                            "UI/Custom/" + maskFileFor(maskSettings),
                            candidate
                    );
                }
            }
        }

        byte[] mask = DynamicMinimapRenderer.renderShapeMask(this.settings);
        if (mask != null) {
            DynamicAssetPublisher.deliverWithRebuild(
                    this.ownerRef,
                    "UI/Custom/" + this.maskFile,
                    mask
            );
        }
    }

    public void show() {
        UICommandBuilder builder = new UICommandBuilder();
        builder.appendInline((String)null, this.buildMarkup());
        this.update(true, builder);
        this.built = true;
    }

    protected void build(UICommandBuilder builder) {
        builder.appendInline((String)null, this.buildMarkup());
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
    public int panLeft() {
        return this.panLeft;
    }

    public int panTop() {
        return this.panTop;
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

    /**
     * Swaps only the compass/frame asset. Used by player-up rotation mode so the
     * compass markings track the rotated terrain without rebuilding the HUD.
     */
    public void setFrameAngle(double angle) {
        if (this.built && Double.compare(angle, this.frameAngle) != 0) {
            this.frameAngle = angle;
            this.publishOverlayAsset(true);
        }
    }

    /**
     * Switches between the circular and square minimap presentation in-place.
     *
     * Do not tear down/recreate the CustomUIHud here: doing so while an
     * InteractiveCustomUIPage is open can corrupt that page's client-side UI tree.
     * Both mask variants are pre-published by deliverStaticAssets(), so changing
     * shape only needs to point the existing clip at the other mask and replace
     * the frame image.
     */
    public void setShape(int shape) {
        int normalized = shape == DynamicMinimapSetting.SHAPE_CIRCLE
                ? DynamicMinimapSetting.SHAPE_CIRCLE
                : DynamicMinimapSetting.SHAPE_SQUARE;

        if (this.settings.shape == normalized) {
            return;
        }

        this.settings.shape = normalized;
        this.settings.normalize();
        this.updateStaticAssetPaths();

        // The frame is a normal dynamic AssetImage, so it can be delivered and
        // swapped without rebuilding the common UI asset registry.
        this.publishOverlayAsset(false);

        if (!this.built) {
            return;
        }

        this.rebuildPositionLayout();
    }

    /**
     * Changes raster resolution without recreating the Custom HUD or rebuilding
     * common assets. All supported mask paths are registered during initial load.
     */
    public void setTextureResolution(int resolution) {
        int normalized = Math.max(256, Math.min(1024, resolution));
        if (this.settings.textureResolution == normalized) {
            return;
        }

        this.settings.textureResolution = normalized;
        this.settings.normalize();
        this.updateStaticAssetPaths();

        if (!this.built) {
            return;
        }

        this.publishOverlayAsset(true);
        UICommandBuilder builder = new UICommandBuilder();
        builder.set("#WayfinderMmRoot #WayfinderMmClip.MaskTexturePath", this.maskFile);
        this.update(false, builder);
    }

    /** Force the next marker update to rebind client asset paths after teleportation. */
    public void invalidateMarkerSlots() {
        java.util.Arrays.fill(this.slotPath, null);
        java.util.Arrays.fill(this.slotVisible, false);
    }

    /** Slot coordinates and sizes are logical minimap values, converted here for display. */
    public void setMarkers(Slot[] slots) {
        if (this.built) {
            UICommandBuilder builder = new UICommandBuilder();
            boolean changed = false;
            int occupied = 0;
            if (slots != null) {
                for (int i = Math.min(slots.length, MARKER_SLOTS) - 1; i >= 0; --i) {
                    if (slots[i] != null && slots[i].path != null) {
                        occupied = i + 1;
                        break;
                    }
                }
            }

            for (int i = 0; i < Math.max(occupied, this.activeMarkerSlots); ++i) {
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
            this.activeMarkerSlots = occupied;
        }
    }

    public void setColours(MinimapColours colours) {
        this.settings.colours = colours;
        if (this.built) {
            this.overlayRevision++;
            this.publishOverlayAsset(false);
            this.rebuildPositionLayout();
            java.util.Arrays.fill(this.slotPath, null);
            java.util.Arrays.fill(this.slotVisible, false);
        }
    }

    /**
     * Updates which optional information components are present in the readout.
     * The readout subtree is rebuilt only when visibility actually changes, so
     * disabled rows disappear and the remaining rows reclaim their space.
     */
    public void setInfoPanelEnabled(boolean enabled) {
        if (this.settings.infoPanelEnabled == enabled) return;
        this.settings.infoPanelEnabled = enabled;
        if (this.built) {
            this.publishOverlayAsset(false);
            this.rebuildPositionLayout();
        }
    }

    public void setInfoVisibility(boolean showCoordinates, boolean showCompass, boolean showClock, boolean showBiome) {
        if (this.settings.showCoordinates == showCoordinates
                && this.settings.showCompass == showCompass
                && this.settings.showClock == showClock
                && this.settings.showBiome == showBiome) {
            return;
        }

        this.settings.showCoordinates = showCoordinates;
        this.settings.showCompass = showCompass;
        this.settings.showClock = showClock;
        this.settings.showBiome = showBiome;

        if (this.built) {
            this.publishOverlayAsset(false);
            this.rebuildPositionLayout();
        }
    }

    public void setInfoTextAlignment(int alignment) {
        int normalized = Math.max(DynamicMinimapSetting.INFO_ALIGN_LEFT,
                Math.min(DynamicMinimapSetting.INFO_ALIGN_RIGHT, alignment));

        // DynamicMinimapSession and this HUD intentionally share the same settings
        // instance. By the time the session detects an alignment change, the value in
        // this.settings has therefore already been updated. Do not short-circuit by
        // comparing against this.settings here: doing so prevents the live HUD overlay
        // from being republished even though the Settings preview is correct.
        this.settings.infoPanelTextAlignment = normalized;
        if (this.built) {
            this.publishOverlayAsset(true);
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
            this.publishOverlayAsset(true);
        }
    }

    public void removeFromClient() {
        if (this.built) {
            UICommandBuilder builder = new UICommandBuilder();
            builder.remove("#WayfinderMmRoot");
            this.update(false, builder);
            this.built = false;
            this.overlayGeneration++;
            this.overlayDirty = false;
            this.terrainShown = false;

            for(int i = 0; i < MARKER_SLOTS; ++i) {
                this.slotPath[i] = null;
                this.slotVisible[i] = false;
            }
            this.activeMarkerSlots = 0;
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
        this.settings.infoPanelPosition = panel;

        if (this.built) {
            this.publishOverlayAsset(false);
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
        this.updateStaticAssetPaths();

        if (!this.built) {
            return;
        }

        this.publishOverlayAsset(false);
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
        out.append("AssetImage #WayfinderMmOverlay { Anchor: (Full: 0); AssetPath: \"")
                .append(this.overlayPath).append("\"; Visible: true; } ");
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
                .append("Background: ").append("#0B0D18").append("(0.94); ");

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
        // The clip mask depends only on shape and texture resolution.
        this.maskFile = maskFileFor(this.settings);
        this.overlayPath = "UI/DapperMap/overlay-" + this.slug + "-" + shape
                + "-r" + this.settings.textureResolution
                + "-p" + this.settings.infoPanelPosition + "-a" + this.settings.infoPanelTextAlignment
                + "-s" + this.logicalUiSize
                + "-h" + this.readoutHeight()
                + "-" + this.settings.colours.frameKey()
                + this.settings.colours.hex(PANEL).substring(1)
                + "-v" + (this.overlayRevision & 15) + ".png";
    }

    private void publishOverlayAsset(boolean updateClient) {
        if (updateClient && this.built) {
            this.overlayDirty = true;
            this.queueOverlayRender();
            return;
        }

        // Structural changes need a valid asset before the HUD subtree is rebuilt.
        // Invalidate any older in-flight status image before publishing the layout.
        this.overlayGeneration++;
        this.overlayDirty = false;
        this.updateStaticAssetPaths();
        byte[] overlay = DynamicMinimapRenderer.renderHudOverlay(
                this.settings, this.logicalUiSize, READOUT_WIDTH,
                this.readoutHeight(), this.readoutGap(), this.frameAngle,
                this.coordsText, this.compassText, this.clockText, this.biomeText);
        if (overlay != null) DynamicAssetPublisher.deliver(this.ownerRef, this.overlayPath, overlay);
    }

    /** Render frequent compass and readout changes outside the world tick. */
    private void queueOverlayRender() {
        if (!this.overlayDirty || this.overlayRenderQueued || !this.built) return;
        this.overlayDirty = false;
        this.overlayRenderQueued = true;
        this.overlayRevision++;
        this.updateStaticAssetPaths();
        String path = this.overlayPath;
        long generation = ++this.overlayGeneration;
        DynamicMinimapSetting snapshot = this.settings.copy();
        int mapSize = this.logicalUiSize;
        int panelHeight = this.readoutHeight();
        int gap = this.readoutGap();
        double angle = this.frameAngle;
        String coords = this.coordsText;
        String compass = this.compassText;
        String clock = this.clockText;
        String biome = this.biomeText;
        DynamicMinimapSession.renderAsync(() -> {
            byte[] image = DynamicMinimapRenderer.renderHudOverlay(
                    snapshot, mapSize, READOUT_WIDTH, panelHeight, gap, angle,
                    coords, compass, clock, biome);
            this.worldExecute.accept(() -> {
                this.overlayRenderQueued = false;
                if (this.built && generation == this.overlayGeneration && image != null
                        && DynamicAssetPublisher.deliver(this.ownerRef, path, image)) {
                    UICommandBuilder builder = new UICommandBuilder();
                    builder.set("#WayfinderMmRoot #WayfinderMmOverlay.AssetPath", path);
                    this.update(false, builder);
                }
                this.queueOverlayRender();
            });
        });
    }

    private String maskFileFor(DynamicMinimapSetting maskSettings) {
        String shape = maskSettings.isCircle() ? "circle" : "square";
        return "DapperMap-" + this.slug + "-" + shape
                + "-r" + maskSettings.textureResolution + "-mask.png";
    }

    private int readoutHeight() {
        if (!this.settings.infoPanelEnabled) return 0;
        int height = (READOUT_PADDING_V * 2) + BRAND_HEIGHT;

        if (this.settings.showCoordinates) {
            height += COORDS_HEIGHT;
        }

        if (this.settings.showCompass) {
            height += META_HEIGHT;
        }

        if (this.settings.showClock) {
            height += META_HEIGHT;
        }

        if (this.settings.showBiome) {
            height += BIOME_HEIGHT;
        }

        return height;
    }

    private void rebuildPositionLayout() {
        if (!this.built) {
            return;
        }

        UICommandBuilder builder = new UICommandBuilder();
        builder.remove("#WayfinderMmRoot #WayfinderMmPositionLayout");
        builder.appendInline("#WayfinderMmRoot", this.buildPositionLayoutMarkup());
        this.update(false, builder);
    }

    private int widgetWidth() {
        if (!this.settings.infoPanelEnabled) return this.displaySize(this.logicalUiSize);
        if (this.isSideRail()) {
            return this.displaySize(this.logicalUiSize + this.readoutGap() + READOUT_WIDTH);
        }
        return this.displaySize(Math.max(this.logicalUiSize, this.readoutLogicalWidth()));
    }

    private int widgetHeight() {
        if (!this.settings.infoPanelEnabled) return this.displaySize(this.logicalUiSize);
        if (this.isSideRail()) {
            return this.displaySize(Math.max(this.logicalUiSize, this.readoutHeight()));
        }
        return this.displaySize(this.logicalUiSize + this.readoutGap() + this.readoutHeight());
    }

    private int mapOriginX() {
        if (!this.settings.infoPanelEnabled) return 0;
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT) {
            return this.displaySize(READOUT_WIDTH + this.readoutGap());
        }
        return this.isSideRail() ? 0 : this.displayCoordinate(
                Math.max(0, (this.readoutLogicalWidth() - this.logicalUiSize) / 2));
    }

    private int mapOriginY() {
        if (!this.settings.infoPanelEnabled) return 0;
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_ABOVE) {
            return this.displaySize(this.readoutHeight() + this.readoutGap());
        }
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_BELOW) {
            return 0;
        }
        return this.displayCoordinate(Math.max(0, (this.readoutHeight() - this.logicalUiSize) / 2));
    }

    private int readoutLogicalWidth() {
        return READOUT_WIDTH;
    }

    private boolean isSideRail() {
        return this.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || this.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT;
    }

    private int readoutGap() {
        if (!this.settings.infoPanelEnabled) return 0;
        return this.settings.isCircle() ? CIRCLE_READOUT_GAP : SQUARE_READOUT_GAP;
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

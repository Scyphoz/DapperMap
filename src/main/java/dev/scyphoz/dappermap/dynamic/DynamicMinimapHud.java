package dev.scyphoz.dappermap.dynamic;

import static dev.scyphoz.dappermap.dynamic.DynamicMinimapColours.Key.*;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.scyphoz.dappermap.util.DapperMapUtil;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Owns the client-side HUD tree and applies small UI updates without rebuilding the
 * complete minimap whenever terrain, markers, readouts or layout values change.
 */
public final class DynamicMinimapHud extends CustomUIHud {
    public static final String KEY = "DapperMapDynamicMinimapHud";
    /** In-map markers (creature dots/icons and players): up to 64 creatures plus 32 players. */
    public static final int MAP_MARKER_SLOTS = 96;
    /** Edge-tracked icons (world-map, BetterMap waypoint and death markers). */
    public static final int EDGE_MARKER_SLOTS = 64;
    /**
     * Hostile and attacking creatures, drawn above the other in-map markers so they stay visible
     * in crowds. Any beyond this many fall back to the normal in-map layer (which has room for
     * every in-map marker), so none are lost.
     */
    public static final int THREAT_MARKER_SLOTS = 32;
    /**
     * Other players (head icons or dots), drawn above every creature marker. Any beyond this
     * many fall back to the threat, then the normal in-map layer, so none are lost.
     */
    public static final int PLAYER_MARKER_SLOTS = 16;
    /** Markers one projection can place (in-map + edge); the threat layer takes some in-map ones. */
    public static final int MARKER_SLOTS = MAP_MARKER_SLOTS + EDGE_MARKER_SLOTS;
    /** Largest in-map marker movement (logical px) that does not trigger an Anchor update. */
    private static final int MARKER_DEADBAND = 1;

    /**
     * Client-side state of one marker layer. Each layer has its own compact slot indices,
     * so dots and edge icons no longer reserve an element in each other's layer.
     */
    private static final class MarkerLayer {
        final boolean fixed;
        final String elementPrefix;
        final String[] selectors;
        /** Property selectors built once, so marker updates don't concatenate strings. */
        final String[] anchorSel, assetSel, visibleSel;
        final String[] path;
        final boolean[] visible;
        final int[] left, top, size;
        int active;

        MarkerLayer(boolean fixed, int capacity, String elementPrefix, String selectorPrefix) {
            this.fixed = fixed;
            this.elementPrefix = elementPrefix;
            this.selectors = new String[capacity];
            this.anchorSel = new String[capacity];
            this.assetSel = new String[capacity];
            this.visibleSel = new String[capacity];
            for (int i = 0; i < capacity; i++) {
                this.selectors[i] = selectorPrefix + elementPrefix + i;
                this.anchorSel[i] = this.selectors[i] + ".Anchor";
                this.assetSel[i] = this.selectors[i] + ".AssetPath";
                this.visibleSel[i] = this.selectors[i] + ".Visible";
            }
            this.path = new String[capacity];
            this.visible = new boolean[capacity];
            this.left = new int[capacity];
            this.top = new int[capacity];
            this.size = new int[capacity];
        }

        /** Forget what the client shows so the next update rebinds every used slot. */
        void forget() {
            java.util.Arrays.fill(this.path, null);
            java.util.Arrays.fill(this.visible, false);
        }
    }

    private final MarkerLayer mapLayer = new MarkerLayer(false, MAP_MARKER_SLOTS,
            "WayfinderMmMk", "#WayfinderMmRoot #WayfinderMmClip #WayfinderMmMarkers #");
    private final MarkerLayer edgeLayer = new MarkerLayer(true, EDGE_MARKER_SLOTS,
            "WayfinderMmEdge", "#WayfinderMmRoot #WayfinderMmEdges #");
    /** Emitted after mapLayer in the same group, so its markers draw on top. */
    private final MarkerLayer threatLayer = new MarkerLayer(false, THREAT_MARKER_SLOTS,
            "WayfinderMmThr", "#WayfinderMmRoot #WayfinderMmClip #WayfinderMmMarkers #");
    /** Emitted after threatLayer, so other players draw above every creature. */
    private final MarkerLayer playerLayer = new MarkerLayer(false, PLAYER_MARKER_SLOTS,
            "WayfinderMmPly", "#WayfinderMmRoot #WayfinderMmClip #WayfinderMmMarkers #");

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

    private record InitialOverlayKey(int size, int shape, int resolution, boolean panel, int position,
                                     int alignment, boolean coords, boolean compass, boolean clock,
                                     boolean biome, DynamicMinimapColours colours, int markings) {}
    /** First overlay per HUD set-up; least recently used dropped beyond 32 (was cleared wholesale at 16). */
    private static final java.util.Map<InitialOverlayKey, DynamicMinimapRenderer.NativeHudParts> INITIAL_OVERLAYS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75F, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<InitialOverlayKey, DynamicMinimapRenderer.NativeHudParts> eldest) {
                    return size() > 32;
                }
            });

    private static InitialOverlayKey initialOverlayKey(DynamicMinimapSetting settings) {
        return new InitialOverlayKey(settings.sizePixels, settings.shape,
                // Alignment 0: the panel image is text-free, so alignment never changes it.
                settings.textureResolution, settings.infoPanelShown(), settings.infoPanelPosition,
                0, settings.showCoordinates, settings.showCompass,
                settings.showClock, settings.showBiome, settings.colours, settings.compassMarkings());
    }

    /** The first overlay for these settings, rendered on a miss. Render pool (or a rare fallback). */
    private static DynamicMinimapRenderer.NativeHudParts initialOverlay(DynamicMinimapSetting settings) {
        InitialOverlayKey key = initialOverlayKey(settings);
        var ready = INITIAL_OVERLAYS.get(key);
        if (ready != null) return ready;
        var png = DynamicMinimapRenderer.renderNativeHudParts(settings, settings.sizePixels,
                READOUT_WIDTH, readoutHeight(settings), readoutGap(settings), 0.0D);
        if (png != null) INITIAL_OVERLAYS.put(key, png);
        return png;
    }

    /** True when a new HUD with these settings can be shown without drawing on the world thread. */
    public static boolean initialOverlayReady(DynamicMinimapSetting settings) {
        DynamicMinimapSetting normalized = settings.copy();
        normalized.normalize(); // Same key as prepareInitialOverlay and the HUD constructor use.
        return INITIAL_OVERLAYS.containsKey(initialOverlayKey(normalized));
    }

    /** Renders the first overlay for these settings into the cache. Render pool only. */
    public static void prepareInitialOverlay(DynamicMinimapSetting settings) {
        DynamicMinimapSetting normalized = settings.copy();
        normalized.normalize();
        var overlay = initialOverlay(normalized);
        if (overlay != null) { rememberHash(overlay.readout()); rememberHash(overlay.frame()); rememberHash(overlay.bezel()); }
    }

    private final PlayerRef ownerRef;
    private final Consumer<Runnable> worldExecute;
    private final java.util.function.BooleanSupplier renderingAllowed;
    private volatile boolean overlayRenderQueued;
    private volatile boolean overlayDirty;
    private long overlayGeneration;
    private long lastOverlaySubmitMs;
    private String displayedOverlayPath;
    private String displayedFramePath;
    private String framePath;
    /** Heading of the compass frame the client shows; the N/E/S/W labels are placed for it. */
    private double displayedFrameAngle = 0.0D;
    /** True when the client's markup has the N/E/S/W labels (letters on at the last rebuild). */
    private boolean cardinalLabelsInMarkup;
    /** Heading bucket the N/E/S/W labels were last placed at (-1: none). */
    private int cardinalBucketShown = -1;
    /** Fixed bezel under the compass ring: sent once per shape/size/resolution/colours. */
    private String bezelPath;
    private String displayedBezelPath;


    // Unslotted: player-up compass frame paths end in their heading bucket, not a slot number.
    private final DynamicAssetPublisher.ImagePublicationCache framePublications = new DynamicAssetPublisher.ImagePublicationCache(false);
    private final DynamicAssetPublisher.ImagePublicationCache bezelPublications = new DynamicAssetPublisher.ImagePublicationCache();
    private final DynamicAssetPublisher.ImagePublicationCache overlayPublications = new DynamicAssetPublisher.ImagePublicationCache();

    private final HudCommandBatch pendingCommands = new HudCommandBatch();

    /** Coalesce normal property updates, while tree changes keep their exact ordering. */
    @Override public void update(boolean clear, UICommandBuilder builder) {
        var commands = builder.getCommands();
        boolean structural = clear;
        for (var command : commands)
            structural |= command.type != com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType.Set;
        if (structural) {
            if (clear) pendingCommands.clear(); else flushPendingCommands();
            super.update(clear, builder);
        } else {
            pendingCommands.add(commands);
            if (pendingCommands.size() >= 2048) flushPendingCommands();
        }
    }

    void flushPendingCommands() {
        if (pendingCommands.size() == 0) return;
        var commands = pendingCommands.drain();
        if (commands.length > 0) this.getPlayerRef().getPacketHandler().writeNoCache(
                new com.hypixel.hytale.protocol.packets.interface_.CustomHud(
                        this.getKey(), this.getZOrder(), false, commands));
    }

    static final class HudCommandBatch {
        private final java.util.LinkedHashMap<String, com.hypixel.hytale.protocol.packets.interface_.CustomUICommand> latest
                = new java.util.LinkedHashMap<>();
        void add(com.hypixel.hytale.protocol.packets.interface_.CustomUICommand[] commands) {
            for (var command : commands) latest.put(command.selector, command);
        }
        int size() { return latest.size(); }
        void clear() { latest.clear(); }
        com.hypixel.hytale.protocol.packets.interface_.CustomUICommand[] drain() {
            var result = latest.values().toArray(com.hypixel.hytale.protocol.packets.interface_.CustomUICommand[]::new);
            clear();
            return result;
        }
    }

    /** Called on normal world ticks so throttled changes eventually publish the latest state. */
    public void flushPendingVisuals(int updateSpeed) {
        this.settings.updateSpeed = updateSpeed;
        this.applyPendingLayout();
        this.queueOverlayRender();
        this.flushPendingCommands();
    }

    public void invalidateVisualAssets() {
        ++this.overlayGeneration;
        this.displayedOverlayPath = null;
        this.displayedFramePath = null;
        this.displayedBezelPath = null;
        this.terrainShown = false;
        this.overlayPublications.clear();
        this.framePublications.clear();
        this.bezelPublications.clear();
        this.lastOverlaySubmitMs = 0L;
        this.overlayDirty = true;
    }

    private long overlayIntervalMs() {
        return switch (this.settings.updateSpeed) {
            case 0 -> 125L;
            case 1 -> 150L;
            case 3 -> 500L;
            default -> 250L;
        };
    }
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
    /** Logical side of the terrain image (the buffer, or a cropped player-up frame). */
    private int terrainPixels;
    private volatile boolean built = false;
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

    public DynamicMinimapHud(PlayerRef playerRef, DynamicMinimapSetting settings,
                             Consumer<Runnable> worldExecute, java.util.function.BooleanSupplier renderingAllowed) {
        super(playerRef, KEY);
        this.ownerRef = playerRef;
        this.worldExecute = worldExecute;
        this.renderingAllowed = renderingAllowed;
        this.uuid = playerRef.getUuid();
        this.settings = settings.copy();
        this.settings.normalize();
        this.hudPositionX = this.settings.hudPositionX;
        this.hudPositionY = this.settings.hudPositionY;
        this.infoPanelPosition = this.settings.infoPanelPosition;
        this.logicalUiSize = this.settings.sizePixels;
        this.bufferPixels = DynamicMinimapRenderer.bufferPixels(this.settings);
        this.terrainPixels = this.bufferPixels;
        this.selfSize = Math.max(18, this.logicalUiSize / 10);
        this.interfaceScalePercent = this.settings.interfaceScalePercent;
        this.slug = DapperMapUtil.playerSlug(this.uuid);
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
        for (MarkerLayer layer : new MarkerLayer[]{this.mapLayer, this.threatLayer, this.playerLayer, this.edgeLayer}) {
            for (int i = 0; i < layer.active; i++) {
                if (!layer.visible[i] || layer.path[i] == null) continue;
                byte[] icon = DynamicAssetPublisher.markerBytes(this.uuid, layer.path[i]);
                if (icon == null) continue;
                // Creature icons and creature dots (player dots are larger) are marked as creatures:
                // the preview replaces them with a few colour samples instead of drawing them all.
                String iconKey = DynamicCreatureIcons.keyFromPath(layer.path[i]);
                boolean creature = iconKey != null || (layer.path[i].contains("/mk-") && layer.size[i] <= 9);
                // Edge icons are positioned in viewport space; map dots move with the pan.
                markers.add(new DynamicMinimapRenderer.PreviewMarker(
                        icon, (layer.fixed ? 0 : this.panLeft) + layer.left[i],
                        (layer.fixed ? 0 : this.panTop) + layer.top[i],
                        layer.size[i], creature, iconKey));
            }
        }
        // The preview draws the terrain image into its own box (cropped player-up frames are inset).
        int inset = this.terrainInset();
        // The displayed terrain PNG is shared and never modified, so it needs no copy.
        return new DynamicMinimapRenderer.LiveSnapshot(terrain, this.logicalUiSize,
                this.terrainPixels, this.panLeft + inset, this.panTop + inset, selfAngle, this.frameAngle,
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
                rememberHash(DynamicMinimapRenderer.renderShapeMask(candidate));
            }
        }
        rememberHash(DynamicMinimapRenderer.renderBlank());
        DynamicMinimapSetting normalized = settings.copy();
        normalized.normalize();
        // Pre-render every locator heading so the first turn needs no render round trip.
        byte[][] arrows = DynamicMinimapRenderer.selfArrows(normalized.colours);
        rememberArrowHashes(arrows); // Every heading, so locator deliveries never hash on the world thread.
        var overlay = initialOverlay(normalized); // Includes frame/font setup and PNG encoding off the world thread.
        if (overlay != null) { rememberHash(overlay.readout()); rememberHash(overlay.frame()); rememberHash(overlay.bezel()); }
    }

    /** Hashes a cached image here (render pool) so the world thread's delivery finds its hash ready. */
    private static void rememberHash(byte[] png) {
        if (png != null && png.length > 0) DynamicAssetPublisher.rememberedHash(png);
    }

    /** Hashes all locator headings of one colour set (render pool; already-hashed arrays are a map hit). */
    static void rememberArrowHashes(byte[][] arrows) {
        if (arrows != null) for (byte[] png : arrows) rememberHash(png);
    }

    public void deliverStaticAssets() {
        byte[] blank = DynamicMinimapRenderer.renderBlank();
        if (blank != null) {
            DynamicAssetPublisher.deliverShared(this.ownerRef, this.blankPath, blank);
        }

        // Before the HUD is shown its markup must name a valid overlay, so a (rare) cache miss
        // is drawn here; the session prepares the initial overlay off-thread beforehand.
        this.publishOverlayAsset(false, true);

        // Heading 0 of the pre-rendered set (same image as angle 0.0): drawing it again here put
        // a render, encode and hash on the world thread, as the single-arrow cache had dropped it.
        byte[][] arrows = DynamicMinimapRenderer.cachedSelfArrows(this.settings.colours);
        byte[] self = arrows != null ? arrows[0] : DynamicMinimapRenderer.renderSelfArrow(0.0D, this.settings.colours);
        if (self != null) {
            DynamicAssetPublisher.deliverShared(this.ownerRef, this.selfPath, self);
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
                    DynamicAssetPublisher.deliverShared( // Cached mask bytes: remembered hash.
                            this.ownerRef,
                            "UI/Custom/" + maskFileFor(maskSettings),
                            candidate
                    );
                }
            }
        }

        byte[] mask = DynamicMinimapRenderer.renderShapeMask(this.settings);
        if (mask != null) {
            DynamicAssetPublisher.deliverSharedWithRebuild(
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

    /**
     * left/top are logical minimap coordinates supplied by DynamicMinimapSession; imagePixels is
     * the logical side of the terrain image, centred in the pan group (smaller than the buffer
     * for cropped player-up frames). Markers keep using buffer coordinates either way.
     */
    public void setTerrain(String assetPath, int left, int top, int imagePixels) {
        if (this.built && assetPath != null) {
            int size = Math.max(1, Math.min(this.bufferPixels, imagePixels));
            if (this.terrainShown && assetPath.equals(this.terrainPath) && size == this.terrainPixels) {
                this.pan(left, top);
                return;
            }
            this.terrainPath = assetPath;
            // Only send the pan and Visible flag when they change (a new frame usually keeps both).
            boolean panChanged = !this.terrainShown || left != this.panLeft || top != this.panTop;
            this.panLeft = left;
            this.panTop = top;
            UICommandBuilder builder = new AnchorCommandBuilder();
            builder.set("#WayfinderMmRoot #WayfinderMmClip #WayfinderMmTerrain.AssetPath", assetPath);
            if (size != this.terrainPixels) {
                // Only when switching between full and cropped images (mode or size change).
                this.terrainPixels = size;
                int inset = this.terrainInset();
                builder.setObject("#WayfinderMmRoot #WayfinderMmClip #WayfinderMmTerrain.Anchor", anchor(
                        this.displayCoordinate(inset), this.displayCoordinate(inset),
                        this.displaySize(size), this.displaySize(size)));
            }
            // Terrain and markers share one pan group: one Anchor update moves both.
            if (panChanged) this.setFullAnchor(builder, left, top);
            if (!this.terrainShown) builder.set("#WayfinderMmRoot #WayfinderMmClip #WayfinderMmTerrain.Visible", true);
            this.terrainShown = true;
            this.update(false, builder);
        }
    }

    public void pan(int left, int top) {
        if (this.built && this.terrainShown && (left != this.panLeft || top != this.panTop)) {
            this.panLeft = left;
            this.panTop = top;
            UICommandBuilder builder = new AnchorCommandBuilder();
            // Terrain and markers share one pan group: one Anchor update moves both.
            this.setFullAnchor(builder, left, top);
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
    /** Publish a pre-rendered frame in the same command batch as its accepted terrain. */
    public boolean acceptTerrainFrame(double angle, DynamicAssetPublisher.PreparedAsset prepared) {
        if (!this.overlayWorkAllowed() || prepared == null) return false;
        String path = this.publishPart(this.framePublications, prepared);
        if (path == null) return false;
        this.frameAngle = angle;
        if (this.overlayRenderQueued) this.overlayDirty = true;
        ++this.overlayGeneration; // Reject a delayed independent overlay at an older heading.
        this.framePath = path;
        UICommandBuilder builder = new AnchorCommandBuilder();
        boolean changed = !path.equals(this.displayedFramePath);
        if (changed) builder.set("#WayfinderMmRoot #WayfinderMmFrame.AssetPath", path);
        // N/E/S/W labels move with the ring, in the same batch as its new image.
        changed |= this.cardinalFrameShown(builder, angle);
        if (changed) this.update(false, builder);
        this.displayedFramePath = path;
        return true;
    }

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

        if (!this.built) {
            return;
        }

        // The frame is a normal dynamic AssetImage, so it can be delivered and swapped without
        // rebuilding the common UI asset registry. Published with the layout by
        // applyPendingLayout(), drawn off the world thread when not cached.
        this.overlayPublishPending = true;
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
        this.mapLayer.forget();
        this.threatLayer.forget();
        this.playerLayer.forget();
        this.edgeLayer.forget();
    }

    /**
     * Slot coordinates and sizes are logical minimap values, converted here for display.
     * Edge icons fill their layer from index 0. In-map markers keep the element that already
     * shows the same image (see arrangeMapSlots), so only changed elements receive commands.
     */
    public void setMarkers(Slot[] slots) {
        if (!this.built) return;
        // Scratch arrays reused on every update (world thread only), cleared at the end.
        Slot[] map = this.scratchMap;
        Slot[] threat = this.scratchThreat;
        Slot[] players = this.scratchPlayers;
        Slot[] edge = this.scratchEdge;
        int mapCount = 0, threatCount = 0, playerCount = 0, edgeCount = 0;
        if (slots != null) {
            for (Slot slot : slots) {
                if (slot == null || slot.path == null) continue;
                if (slot.fixed) { if (edgeCount < edge.length) edge[edgeCount++] = slot; }
                // Other players use the topmost layer while it has room.
                else if (slot.player && playerCount < players.length) players[playerCount++] = slot;
                    // Hostile/attacking creatures (and any overflow players) use the threat layer.
                else if ((slot.threat || slot.player) && threatCount < threat.length) threat[threatCount++] = slot;
                else if (mapCount < map.length) map[mapCount++] = slot;
            }
        }
        UICommandBuilder builder = new AnchorCommandBuilder();
        boolean changed = this.applyArranged(builder, this.mapLayer, map, mapCount);
        changed |= this.applyArranged(builder, this.threatLayer, threat, threatCount);
        changed |= this.applyArranged(builder, this.playerLayer, players, playerCount);
        changed |= this.applyLayer(builder, this.edgeLayer, edge, edgeCount);
        if (changed) this.update(false, builder);
        java.util.Arrays.fill(map, null);
        java.util.Arrays.fill(threat, null);
        java.util.Arrays.fill(players, null);
        java.util.Arrays.fill(edge, null);
    }

    /** Arranges an in-map layer's markers onto its existing elements, then emits the changes. */
    private boolean applyArranged(UICommandBuilder builder, MarkerLayer layer, Slot[] slots, int count) {
        Slot[] arranged = arrangeMapSlots(layer, slots, count, this.scratchPlaced, this.scratchTaken, this.scratchHashes);
        int arrangedCount = 0;
        int capacity = layer.path.length;
        for (int i = 0; i < capacity; i++) if (arranged[i] != null) arrangedCount = i + 1;
        boolean changed = this.applyLayer(builder, layer, arranged, arrangedCount);
        java.util.Arrays.fill(arranged, null);
        return changed;
    }

    private final Slot[] scratchMap = new Slot[MAP_MARKER_SLOTS];
    private final Slot[] scratchThreat = new Slot[THREAT_MARKER_SLOTS];
    private final Slot[] scratchPlayers = new Slot[PLAYER_MARKER_SLOTS];
    private final Slot[] scratchEdge = new Slot[EDGE_MARKER_SLOTS];
    private final Slot[] scratchPlaced = new Slot[MAP_MARKER_SLOTS];
    private final boolean[] scratchTaken = new boolean[MAP_MARKER_SLOTS];
    /** Path hash of each new slot (per HUD, world thread only: HUDs live on different world threads). */
    private final int[] scratchHashes = new int[MAP_MARKER_SLOTS];

    /**
     * Places in-map markers so each one reuses the HUD element already showing the same
     * image nearest to it. Creatures are listed nearest-first, so their order changes as
     * they move; without this, swapped neighbours would each need a new AssetPath (most
     * noticeable with creature icons, where every creature type has its own image).
     * Unmatched markers take the lowest free elements. At most a few 96 x 96 cheap comparisons.
     */
    private static Slot[] arrangeMapSlots(MarkerLayer layer, Slot[] slots, int count,
                                          Slot[] placed, boolean[] taken, int[] hashes) {
        java.util.Arrays.fill(placed, null);
        java.util.Arrays.fill(taken, 0, count, false);
        // String caches its hash, so this is one field read per slot.
        for (int j = 0; j < count; j++) hashes[j] = slots[j].path.hashCode();
        for (int i = 0; i < layer.active; i++) {
            String path = layer.path[i];
            if (!layer.visible[i] || path == null) continue;
            int hash = path.hashCode();
            int best = -1, bestDistance = Integer.MAX_VALUE;
            for (int j = 0; j < count; j++) {
                // An int compare rejects other images; equals only runs for a probable match.
                if (taken[j] || hashes[j] != hash || !path.equals(slots[j].path)) continue;
                int distance = Math.abs(slots[j].left - layer.left[i]) + Math.abs(slots[j].top - layer.top[i]);
                if (distance < bestDistance) { best = j; bestDistance = distance; }
            }
            if (best >= 0) { placed[i] = slots[best]; taken[best] = true; }
        }
        // Markers whose image changed (e.g. a creature's threat colour or attack pulse) take the
        // nearest element left over from the last update, so only its AssetPath changes.
        for (int j = 0; j < count; j++) {
            if (taken[j]) continue;
            int best = -1, bestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < layer.active; i++) {
                if (placed[i] != null || !layer.visible[i] || layer.path[i] == null) continue;
                int distance = Math.abs(slots[j].left - layer.left[i]) + Math.abs(slots[j].top - layer.top[i]);
                if (distance < bestDistance) { best = i; bestDistance = distance; }
            }
            if (best >= 0 && bestDistance <= 2 * slots[j].size) { placed[best] = slots[j]; taken[j] = true; }
        }
        int free = 0, capacity = layer.path.length; // placed[] is sized for the largest layer.
        for (int j = 0; j < count; j++) {
            if (taken[j]) continue;
            while (free < capacity && placed[free] != null) free++;
            if (free == capacity) break;
            placed[free++] = slots[j];
        }
        return placed;
    }

    /** Emits commands for one layer's changed slots; returns true when any were added. */
    private boolean applyLayer(UICommandBuilder builder, MarkerLayer layer, Slot[] slots, int count) {
        boolean changed = false;
        for (int i = 0; i < Math.max(count, layer.active); ++i) {
            Slot slot = i < count ? slots[i] : null;
            String path = slot != null ? slot.path : null;
            boolean visible = path != null;
            int left = visible ? slot.left : 0;
            int top = visible ? slot.top : 0;
            int size = visible ? slot.size : 0;
            // Dead-band: an in-map icon that moved by only 1 logical px keeps its last sent
            // position. Error stays within 1 px while moving creatures stop generating an
            // Anchor update on almost every scan. Edge icons have their own EdgeTracker.
            if (!layer.fixed && visible && layer.visible[i]
                    && size == layer.size[i] && Objects.equals(path, layer.path[i])
                    && Math.abs(left - layer.left[i]) <= MARKER_DEADBAND
                    && Math.abs(top - layer.top[i]) <= MARKER_DEADBAND) continue;
            if (Objects.equals(path, layer.path[i]) && visible == layer.visible[i] && (!visible
                    || (left == layer.left[i] && top == layer.top[i] && size == layer.size[i]))) continue;
            if (appendMarkerDelta(builder, layer, i, this.settings, this.blankPath,
                    layer.path[i], layer.visible[i], layer.left[i], layer.top[i], layer.size[i],
                    path, visible, left, top, size)) {
                layer.path[i] = path;
                layer.visible[i] = visible;
                layer.left[i] = left;
                layer.top[i] = top;
                layer.size[i] = size;
                changed = true;
            }
        }
        layer.active = count;
        return changed;
    }

    /** Moving an existing icon must not rebind its texture or visibility. */
    static boolean appendMarkerDelta(UICommandBuilder builder, MarkerLayer layer, int slot,
                                     DynamicMinimapSetting settings, String blankPath,
                                     String oldPath, boolean oldVisible, int oldLeft, int oldTop, int oldSize,
                                     String path, boolean visible, int left, int top, int size) {
        boolean imageChanged = !Objects.equals(oldPath, path);
        boolean visibilityChanged = oldVisible != visible;
        boolean anchorChanged = visible && (!oldVisible || oldLeft != left || oldTop != top || oldSize != size);
        if (anchorChanged) setAnchor(builder, layer.anchorSel[slot],
                settings.displayCoordinate(left), settings.displayCoordinate(top),
                settings.displaySize(size), settings.displaySize(size));
        if (imageChanged) builder.set(layer.assetSel[slot], visible ? path : blankPath);
        if (visibilityChanged) builder.set(layer.visibleSel[slot], visible);
        return anchorChanged || imageChanged || visibilityChanged;
    }

    public void setColours(DynamicMinimapColours colours) {
        this.settings.colours = colours;
        if (this.built) {
            this.overlayRevision++;
            this.overlayPublishPending = true;
            this.rebuildPositionLayout();
            this.invalidateMarkerSlots();
        }
    }

    /**
     * Shows or hides the compass ring's N/E/S/W letters, small edge dashes and large cardinal
     * dashes. The ring image is redrawn and the layout rebuilt once (the letters are labels).
     */
    public void setCompassMarkings(boolean letters, boolean edgeDashes, boolean cardinalDashes) {
        if (this.settings.showCompassLetters == letters && this.settings.showCompassDashes == edgeDashes
                && this.settings.showCardinalDashes == cardinalDashes) return;
        this.settings.showCompassLetters = letters;
        this.settings.showCompassDashes = edgeDashes;
        this.settings.showCardinalDashes = cardinalDashes;
        if (this.built) {
            this.overlayRevision++;
            this.overlayPublishPending = true;
            this.rebuildPositionLayout();
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
            this.overlayPublishPending = true;
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
            this.overlayPublishPending = true;
            this.rebuildPositionLayout();
        }
    }

    public void setInfoTextAlignment(int alignment) {
        int normalized = Math.max(DynamicMinimapSetting.INFO_ALIGN_LEFT,
                Math.min(DynamicMinimapSetting.INFO_ALIGN_RIGHT, alignment));

        if (this.settings.infoPanelTextAlignment == normalized) return;
        this.settings.infoPanelTextAlignment = normalized;
        if (this.built) {
            // Brand, headings and values are all native labels; the panel image is
            // unaffected by alignment, so only the layout markup is rebuilt.
            this.rebuildPositionLayout();
        }
    }

    public void setStatus(String coords, String compass, String clock, String biome) {
        UICommandBuilder builder = null;
        if (coords != null && !coords.equals(this.coordsText)) {
            this.coordsText = coords;
            if (this.built && (this.shownReadouts & 1) != 0) {
                if (builder == null) builder = new UICommandBuilder();
                builder.set("#WayfinderMmRoot #WayfinderMmReadout0.Text", this.coordsText);
            }
        }

        if (compass != null && !compass.equals(this.compassText)) {
            this.compassText = compass;
            if (this.built && (this.shownReadouts & 2) != 0) {
                if (builder == null) builder = new UICommandBuilder();
                builder.set("#WayfinderMmRoot #WayfinderMmReadout1.Text", this.compassText);
            }
        }

        if (clock != null && !clock.equals(this.clockText)) {
            this.clockText = clock;
            if (this.built && (this.shownReadouts & 4) != 0) {
                if (builder == null) builder = new UICommandBuilder();
                builder.set("#WayfinderMmRoot #WayfinderMmReadout2.Text", this.clockText);
            }
        }

        if (biome != null && !biome.equals(this.biomeText)) {
            this.biomeText = biome;
            if (this.built && (this.shownReadouts & 8) != 0) {
                if (builder == null) builder = new UICommandBuilder();
                builder.set("#WayfinderMmRoot #WayfinderMmReadout3.Text", this.biomeText);
            }
        }

        if (builder != null) this.update(false, builder);
    }

    /** Invalidate detached HUD work without sending updates to a previous world. */
    public void detach() {
        this.pendingCommands.clear();
        this.built = false;
        this.overlayGeneration++;
        this.overlayDirty = false;
    }

    public void removeFromClient() {
        if (this.built) {
            UICommandBuilder builder = new UICommandBuilder();
            builder.remove("#WayfinderMmRoot");
            this.update(false, builder);
            this.built = false;
            this.cardinalLabelsInMarkup = false; // Removed with the root.
            this.overlayGeneration++;
            this.overlayDirty = false;
            this.terrainShown = false;

            this.invalidateMarkerSlots();
            this.mapLayer.active = 0;
            this.threatLayer.active = 0;
            this.playerLayer.active = 0;
            this.edgeLayer.active = 0;
        }
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

        boolean panelMoved = panel != this.infoPanelPosition;
        this.hudPositionX = x;
        this.hudPositionY = y;
        this.infoPanelPosition = panel;
        this.settings.infoPanelPosition = panel;

        if (this.built) {
            if (panelMoved) this.overlayPublishPending = true; // The overlay depends on the panel side, not X/Y.
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
        this.terrainPixels = this.bufferPixels; // Blank until the next terrain sets its size.
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

        this.overlayPublishPending = true;
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
        // Marker deltas and rebuilt markup must use the same presentation scale.
        this.settings.interfaceScalePercent = normalized;

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
        // Bezel first, then the compass ring over it (UI elements draw in markup order).
        out.append("AssetImage #WayfinderMmBezel { Anchor: (Left: ").append(this.mapOriginX())
                .append(", Top: ").append(this.mapOriginY()).append(", Width: ").append(this.displaySize(this.logicalUiSize))
                .append(", Height: ").append(this.displaySize(this.logicalUiSize)).append("); AssetPath: \"")
                .append(this.displayedBezelPath != null ? this.displayedBezelPath : this.bezelPath)
                .append("\"; Visible: true; } ");
        out.append("AssetImage #WayfinderMmFrame { Anchor: (Left: ").append(this.mapOriginX())
                .append(", Top: ").append(this.mapOriginY()).append(", Width: ").append(this.displaySize(this.logicalUiSize))
                .append(", Height: ").append(this.displaySize(this.logicalUiSize)).append("); AssetPath: \"")
                .append(this.displayedFramePath != null ? this.displayedFramePath : this.framePath)
                .append("\"; Visible: true; } ");
        this.appendCardinalLabels(out); // Over the ring's badges, under edge markers and the locator.
        this.appendEdgeLayer(out);
        // Locator goes after the frame and edge-marker layer: UI elements draw in markup
        // order, so this keeps it above any marker at the player's position.
        this.appendSelf(out);
        // Rebuilds use the image the client already has. overlayPath/framePath may already name
        // a newer image that is still being rendered; pointing at it showed the missing-image
        // cross until something else re-sent the overlay (e.g. reset to defaults, then a teleport).
        out.append("AssetImage #WayfinderMmOverlay { Anchor: (Full: 0); AssetPath: \"")
                .append(this.displayedOverlayPath != null ? this.displayedOverlayPath : this.overlayPath)
                .append("\"; Visible: true; } ");
        this.appendReadoutLabels(out);
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
    private boolean readoutEnabled(int row) {
        return DynamicMinimapRenderer.readoutShown(this.settings, row);
    }

    /**
     * Bit i set: label #WayfinderMmReadout{i} is in the markup the client has. setStatus only
     * updates these, since a settings toggle can change before its layout rebuild is sent.
     */
    private int shownReadouts;

    private void appendReadoutLabels(StringBuilder out) {
        this.shownReadouts = 0;
        if (!this.settings.infoPanelShown()) return;
        int position = this.settings.infoPanelPosition;
        boolean side = position == DynamicMinimapSetting.INFO_LEFT || position == DynamicMinimapSetting.INFO_RIGHT;
        int panelX = position == DynamicMinimapSetting.INFO_RIGHT ? this.logicalUiSize + this.readoutGap()
                : side ? 0 : Math.max(0, (this.logicalUiSize - READOUT_WIDTH) / 2);
        int panelY = position == DynamicMinimapSetting.INFO_BELOW ? this.logicalUiSize + this.readoutGap()
                : position == DynamicMinimapSetting.INFO_ABOVE ? 0 : Math.max(0, (this.logicalUiSize - this.readoutHeight()) / 2);
        String[] values = {this.coordsText, this.compassText, this.clockText, this.biomeText};
        DynamicMinimapColours.Key[] colours = {COORDS_TEXT, COMPASS_TEXT, CLOCK_TEXT, BIOME_TEXT};
        String alignment = switch (this.settings.infoPanelTextAlignment) { case 1 -> "Center"; case 2 -> "End"; default -> "Start"; };
        // Brand label: native text (previously painted into the panel PNG, which the client
        // stretched and blurred). Same insets/position as the old raster version.
        int brandCurve = this.settings.isCircle() && side ? 6 : 0;
        int brandLeft = 8 + (position == DynamicMinimapSetting.INFO_RIGHT ? brandCurve : 0);
        int brandRight = 8 + (position == DynamicMinimapSetting.INFO_LEFT ? brandCurve : 0);
        this.appendPanelLabel(out, "WayfinderMmBrand", "DapperMap", panelX + brandLeft,
                READOUT_WIDTH - brandLeft - brandRight, panelY + 12, panelY + 27, 10, 0.35F, true,
                this.settings.colours.hex(BRAND_TEXT), alignment);
        int cursor = panelY + 30;
        for (int i = 0; i < 4; i++) {
            if (!this.readoutEnabled(i)) continue;
            this.shownReadouts |= 1 << i;
            int curve = this.settings.isCircle() && side ? (i == 0 || i == 3 ? 12 : 22) : 0;
            int left = 8 + (position == DynamicMinimapSetting.INFO_RIGHT ? curve : 0);
            int right = 8 + (position == DynamicMinimapSetting.INFO_LEFT ? curve : 0);
            // Heading above the value: static text, only sent with layout rebuilds.
            this.appendPanelLabel(out, "WayfinderMmHeading" + i, DynamicMinimapRenderer.READOUT_HEADINGS[i], panelX + left,
                    READOUT_WIDTH - left - right, cursor + 3, cursor + 18, 9, 0.95F, false,
                    this.settings.colours.hex(INFO_HEADERS), alignment);
            out.append("Label #WayfinderMmReadout").append(i).append(" { Anchor: (Left: ")
                    .append(this.displayCoordinate(panelX + left)).append(", Top: ")
                    .append(this.displayCoordinate(cursor + 19)).append(", Width: ")
                    .append(this.displaySize(READOUT_WIDTH - left - right)).append(", Height: ")
                    .append(this.displayCoordinate(cursor + 38) - this.displayCoordinate(cursor + 19))
                    .append("); Text: \"").append(DapperMapUtil.escapeUiText(values[i]))
                    .append("\"; Style: (FontSize: ").append(this.displaySize(11)).append(", TextColor: ")
                    .append(this.settings.colours.hex(colours[i])).append(", HorizontalAlignment: ")
                    .append(alignment).append(", VerticalAlignment: Center); } ");
            cursor += 43;
        }
    }

    /**
     * N/E/S/W as native labels (Hytale's font) over the compass ring's badges, placed for the
     * heading of the frame the client shows. Player-up turns move them with Anchor sets only.
     */
    private void appendCardinalLabels(StringBuilder out) {
        this.cardinalLabelsInMarkup = this.settings.showCompassLetters;
        this.cardinalBucketShown = -1;
        if (!this.cardinalLabelsInMarkup) return;
        double angle = this.displayedFrameAngle;
        this.cardinalBucketShown = DynamicMinimapRenderer.rotationBucket(angle);
        boolean dashes = this.settings.showCardinalDashes;
        int font = this.displaySize((int)Math.round(DynamicMinimapRenderer.cardinalFontSize(dashes)
                * this.logicalUiSize / (double)DynamicMinimapRenderer.STATIC_ASSET_LOGICAL_SIZE));
        String colour = this.settings.colours.hex(CARDINAL_TEXT);
        for (int i = 0; i < 4; i++) {
            int[] box = this.cardinalBox(i, angle);
            out.append("Label #WayfinderMmCardinal").append(i).append(" { Anchor: (Left: ").append(box[0])
                    .append(", Top: ").append(box[1]).append(", Width: ").append(box[2])
                    .append(", Height: ").append(box[2]).append("); Text: \"")
                    .append(DynamicMinimapRenderer.CARDINAL_LETTERS[i])
                    .append("\"; Style: (FontSize: ").append(font).append(", TextColor: ").append(colour)
                    .append(", RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center); } ");
        }
    }

    /** Display-space {left, top, side} of cardinal label {@code index} at a frame heading. */
    private int[] cardinalBox(int index, double angle) {
        boolean dashes = this.settings.showCardinalDashes;
        double[] centre = DynamicMinimapRenderer.cardinalLetterCentre(index, angle, this.settings.isCircle(), dashes);
        int side = (int)Math.round(DynamicMinimapRenderer.cardinalBadgeSize(dashes) * this.logicalUiSize / (double)DynamicMinimapRenderer.STATIC_ASSET_LOGICAL_SIZE);
        int left = (int)Math.round(centre[0] * this.logicalUiSize - side / 2.0D);
        int top = (int)Math.round(centre[1] * this.logicalUiSize - side / 2.0D);
        return new int[]{this.mapOriginX() + this.displayCoordinate(left),
                this.mapOriginY() + this.displayCoordinate(top), this.displaySize(side)};
    }

    /**
     * Records the heading of the compass frame now shown and, when the labels are on the client
     * and the heading bucket changed, adds their four Anchor sets to {@code builder}. Returns
     * true when anything was added. North-up maps never change bucket, so they never send any.
     */
    private boolean cardinalFrameShown(UICommandBuilder builder, double angle) {
        this.displayedFrameAngle = angle;
        if (builder == null || !this.built || !this.cardinalLabelsInMarkup) return false;
        int bucket = DynamicMinimapRenderer.rotationBucket(angle);
        if (bucket == this.cardinalBucketShown) return false;
        this.cardinalBucketShown = bucket;
        for (int i = 0; i < 4; i++) {
            int[] box = this.cardinalBox(i, angle);
            setAnchor(builder, "#WayfinderMmRoot #WayfinderMmCardinal" + i + ".Anchor", box[0], box[1], box[2], box[2]);
        }
        return true;
    }

    /**
     * Appends one static native label to the info panel (brand or heading). Logical
     * coordinates are converted to display space; letter spacing scales with the UI.
     */
    private void appendPanelLabel(StringBuilder out, String id, String text, int left, int width,
                                  int top, int bottom, int fontSize, float letterSpacing, boolean bold,
                                  String colourHex, String alignment) {
        float spacing = Math.round(letterSpacing * this.interfaceScalePercent / 10.0F) / 10.0F;
        out.append("Label #").append(id).append(" { Anchor: (Left: ")
                .append(this.displayCoordinate(left)).append(", Top: ")
                .append(this.displayCoordinate(top)).append(", Width: ")
                .append(this.displaySize(width)).append(", Height: ")
                .append(this.displayCoordinate(bottom) - this.displayCoordinate(top))
                .append("); Text: \"").append(text)
                .append("\"; Style: (FontSize: ").append(this.displaySize(fontSize))
                .append(", TextColor: ").append(colourHex);
        if (bold) out.append(", RenderBold: true");
        if (spacing > 0.0F) out.append(", LetterSpacing: ").append(spacing);
        out.append(", HorizontalAlignment: ").append(alignment).append(", VerticalAlignment: Center); } ");
    }

    private String buildMapMarkup() {
        int displayUiSize = this.displaySize(this.logicalUiSize);
        int displayBuffer = this.displaySize(this.bufferPixels);
        int mapLeft = this.mapOriginX();
        int mapTop = this.mapOriginY();
        StringBuilder out = new StringBuilder(3200);
        out.append("Group #WayfinderMmClip { ")
                .append("Anchor: (Left: ").append(mapLeft)
                .append(", Top: ").append(mapTop)
                .append(", Width: ").append(displayUiSize)
                .append(", Height: ").append(displayUiSize).append("); ")
                .append("MaskTexturePath: \"").append(this.maskFile).append("\"; ")
                .append("Background: ").append("#0B0D18").append("(0.94); ");

        // Pan group: terrain and in-map markers move together with a single Anchor update.
        out.append("Group #WayfinderMmPan { ")
                .append("Anchor: (Left: ").append(this.displayCoordinate(this.panLeft))
                .append(", Top: ").append(this.displayCoordinate(this.panTop))
                .append(", Width: ").append(displayBuffer)
                .append(", Height: ").append(displayBuffer).append("); ");

        int terrainInset = this.displayCoordinate(this.terrainInset());
        int displayTerrain = this.displaySize(this.terrainPixels);
        out.append("AssetImage #WayfinderMmTerrain { ")
                .append("Anchor: (Left: ").append(terrainInset).append(", Top: ").append(terrainInset)
                .append(", Width: ").append(displayTerrain)
                .append(", Height: ").append(displayTerrain)
                .append("); AssetPath: \"").append(this.terrainPath)
                .append("\"; Visible: ").append(this.terrainShown).append("; } ");

        out.append("Group #WayfinderMmMarkers { ")
                .append("Anchor: (Left: 0, Top: 0, Width: ").append(displayBuffer)
                .append(", Height: ").append(displayBuffer).append("); ");

        appendMarkerLayer(out, this.mapLayer);
        appendMarkerLayer(out, this.threatLayer); // After the map layer: hostiles draw on top.
        appendMarkerLayer(out, this.playerLayer); // Last: other players draw above every creature.
        out.append("} "); // Markers
        out.append("} "); // Pan
        out.append("} "); // Clip

        return out.toString();
    }

    /** Player locator, centred on the map; emitted last among map layers so it draws on top. */
    private void appendSelf(StringBuilder out) {
        int selfInset = this.displayCoordinate((this.logicalUiSize - this.selfSize) / 2);
        int displaySelfSize = this.displaySize(this.selfSize);
        out.append("AssetImage #WayfinderMmSelf { Anchor: (Left: ")
                .append(this.mapOriginX() + selfInset)
                .append(", Top: ").append(this.mapOriginY() + selfInset)
                .append(", Width: ").append(displaySelfSize)
                .append(", Height: ").append(displaySelfSize)
                .append("); AssetPath: \"").append(this.selfPath)
                .append("\"; Visible: true; } ");
    }

    /**
     * Static visual assets are intentionally independent of logical map size.
     *
     * Hytale stretches the mask/frame AssetImage to the current UI Anchor, so a
     * circle/square mask and nautical frame can be reused at every map size.
     * This avoids introducing a new mask resource during live map-size changes.
     */
    /** Edge icons sit above the bezel and outside the terrain aperture mask. */
    private void appendEdgeLayer(StringBuilder out) {
        int mapLeft = this.mapOriginX(), mapTop = this.mapOriginY();
        int displayUiSize = this.displaySize(this.logicalUiSize);
        out.append("Group #WayfinderMmEdges { Anchor: (Left: ").append(mapLeft)
                .append(", Top: ").append(mapTop).append(", Width: ").append(displayUiSize)
                .append(", Height: ").append(displayUiSize).append("); ");
        appendMarkerLayer(out, this.edgeLayer);
        out.append("} ");
    }

    /** Emits every element of a layer, restoring the currently shown icons on rebuilds. */
    private void appendMarkerLayer(StringBuilder out, MarkerLayer layer) {
        for (int i = 0; i < layer.path.length; ++i) {
            boolean visible = layer.visible[i] && layer.path[i] != null;
            out.append("AssetImage #").append(layer.elementPrefix).append(i)
                    .append(" { Anchor: (Left: ").append(visible ? this.displayCoordinate(layer.left[i]) : 0)
                    .append(", Top: ").append(visible ? this.displayCoordinate(layer.top[i]) : 0)
                    .append(", Width: ").append(visible ? this.displaySize(layer.size[i]) : 0)
                    .append(", Height: ").append(visible ? this.displaySize(layer.size[i]) : 0)
                    .append("); AssetPath: ").append('"').append(visible ? layer.path[i] : this.blankPath)
                    .append('"').append("; Visible: ").append(visible).append("; } ");
        }
    }

    private void updateStaticAssetPaths() {
        String shape = this.settings.isCircle() ? "circle" : "square";
        // The clip mask depends only on shape and texture resolution.
        this.maskFile = maskFileFor(this.settings);
        this.overlayPath = "UI/DapperMap/overlay-" + this.slug + "-" + shape
                + "-r" + this.settings.textureResolution
                + "-p" + this.settings.infoPanelPosition
                + "-s" + this.logicalUiSize
                + "-h" + this.readoutHeight()
                + "-" + this.settings.colours.frameKey()
                + this.settings.colours.hex(PANEL).substring(1)
                + "-m" + this.settings.compassMarkings()
                + "-v" + (this.overlayRevision & 15) + ".png";
        this.framePath = this.overlayPath.replace("-v", "-frame-v");
        this.bezelPath = this.overlayPath.replace("-v", "-bezel-v");
    }

    private void publishOverlayAsset(boolean updateClient) {
        this.publishOverlayAsset(updateClient, false);
    }

    /** An off-thread render of overlay parts that weren't cached is queued or running. */
    private volatile boolean overlayPrepareQueued;
    /** The last off-thread overlay render produced nothing: draw inline once rather than loop. */
    private volatile boolean overlayPrepareFailed;
    /** Which layout a set of overlay parts was drawn for (the settings part matches INITIAL_OVERLAYS). */
    private record OverlayLayoutKey(InitialOverlayKey settings, boolean initial, int mapSize,
                                    int panelHeight, int gap, double angle) { }
    private record PreparedOverlay(OverlayLayoutKey key, DynamicMinimapRenderer.NativeHudParts parts) { }
    /**
     * Parts the render pool drew for a pending layout, handed straight to the next publish so a
     * shared-cache eviction in between can't cause another miss. Used once, then cleared.
     */
    private volatile PreparedOverlay preparedOverlay;
    /** When the pending layout first missed (0: none). Past 2.5 s it is drawn inline so it always lands. */
    private long overlayPendingSinceMs;
    private static final long OVERLAY_INLINE_AFTER_MS = 2500L;

    /**
     * Publishes the overlay, compass ring and bezel for the current layout. Returns false when
     * their images weren't cached: they are then drawn on the render pool and the caller retries
     * on a later tick (applyPendingLayout keeps the layout change pending until then).
     * {@code allowInlineRender} draws a miss immediately instead (only before the HUD is shown).
     */
    private boolean publishOverlayAsset(boolean updateClient, boolean allowInlineRender) {
        if (updateClient && this.built) {
            this.overlayDirty = true;
            this.queueOverlayRender();
            return true;
        }

        // Structural changes need a valid asset before the HUD subtree is rebuilt.
        // Invalidate any older in-flight status image before publishing the layout.
        this.overlayGeneration++;
        this.overlayDirty = false;
        this.updateStaticAssetPaths();
        boolean initial = this.frameAngle == 0.0D
                && "X: ?  Y: ?  Z: ?".equals(this.coordsText) && "N 0 deg".equals(this.compassText)
                && "Sun 06:00".equals(this.clockText) && "Biome: unknown".equals(this.biomeText);
        long now = System.currentTimeMillis();
        boolean inline = allowInlineRender || this.overlayPrepareFailed
                || (this.overlayPendingSinceMs != 0L && now - this.overlayPendingSinceMs > OVERLAY_INLINE_AFTER_MS);
        var key = new OverlayLayoutKey(initialOverlayKey(this.settings), initial, this.logicalUiSize,
                this.readoutHeight(), this.readoutGap(), this.frameAngle);
        var handed = this.preparedOverlay;
        this.preparedOverlay = null;
        var overlay = handed != null && handed.key().equals(key) ? handed.parts()
                : initial
                ? (inline ? initialOverlay(this.settings) : INITIAL_OVERLAYS.get(key.settings()))
                : inline ? DynamicMinimapRenderer.renderNativeHudParts(this.settings, this.logicalUiSize, READOUT_WIDTH,
                this.readoutHeight(), this.readoutGap(), this.frameAngle)
                : DynamicMinimapRenderer.cachedNativeHudParts(this.settings, this.logicalUiSize, READOUT_WIDTH,
                this.readoutHeight(), this.readoutGap(), this.frameAngle);
        this.overlayPrepareFailed = false;
        if (overlay == null) {
            if (!inline) {
                if (this.overlayPendingSinceMs == 0L) this.overlayPendingSinceMs = now;
                this.queueOverlayPrepare(key); // A busy pool is simply retried next tick.
            } else this.overlayPendingSinceMs = 0L;
            return inline; // An inline failure is not retried (as before); a miss is.
        }
        this.overlayPendingSinceMs = 0L;
        var readout = DynamicAssetPublisher.prepareShared(this.overlayPath, overlay.readout());
        var frame = DynamicAssetPublisher.prepareShared(this.framePath, overlay.frame());
        var bezel = DynamicAssetPublisher.prepareShared(this.bezelPath, overlay.bezel());
        String readoutPath = publishPart(this.overlayPublications, readout);
        String framePath = publishPart(this.framePublications, frame);
        // The same bezel content is never re-sent (publishPart matches it by hash).
        String bezelPath = publishPart(this.bezelPublications, bezel);
        if (readoutPath != null) this.displayedOverlayPath = this.overlayPath = readoutPath;
        if (framePath != null) {
            this.displayedFramePath = this.framePath = framePath;
            // Labels follow this frame: placed by the layout rebuild that normally follows, or
            // moved here when none is due (the sets are flushed before any later rebuild).
            UICommandBuilder labels = new AnchorCommandBuilder();
            if (this.cardinalFrameShown(this.layoutRebuildPending ? null : labels, this.frameAngle))
                this.update(false, labels);
        }
        if (bezelPath != null) this.displayedBezelPath = this.bezelPath = bezelPath;
        return true;
    }

    /** Draws this layout's overlay parts on the render pool (one job at a time) for the next publish. */
    private void queueOverlayPrepare(OverlayLayoutKey key) {
        if (this.overlayPrepareQueued) return;
        this.overlayPrepareQueued = true;
        DynamicMinimapSetting snapshot = this.settings.copy();
        if (!DynamicMinimapSession.renderAsync(() -> {
            try {
                var parts = key.initial() ? initialOverlay(snapshot)
                        : DynamicMinimapRenderer.renderNativeHudParts(snapshot, key.mapSize(), READOUT_WIDTH,
                        key.panelHeight(), key.gap(), key.angle());
                if (parts == null) this.overlayPrepareFailed = true;
                else {
                    // Hash the parts here, so publishOverlayAsset's prepareShared finds them remembered
                    // instead of running SHA-256 on the world thread.
                    rememberHash(parts.readout()); rememberHash(parts.frame()); rememberHash(parts.bezel());
                    this.preparedOverlay = new PreparedOverlay(key, parts);
                }
            } finally {
                this.overlayPrepareQueued = false; // The next tick's applyPendingLayout publishes it.
            }
        })) this.overlayPrepareQueued = false; // Render pool busy: retried on a later tick.
    }

    private String publishPart(DynamicAssetPublisher.ImagePublicationCache publications,
                               DynamicAssetPublisher.PreparedAsset prepared) {
        if (prepared == null) return null;
        String existing = publications.pathFor(prepared.hash());
        if (existing == null) {
            var publication = prepared.withPath(publications.nonActivePath(prepared.path()));
            if (!DynamicAssetPublisher.deliver(this.ownerRef, publication)) return null;
            publications.remember(publication.path(), publication.hash());
            existing = publication.path();
        }
        // Every caller shows the returned path in the same world task.
        publications.shown(existing);
        return existing;
    }

    /** Render frame changes outside the world tick; native readouts use text deltas. */
    private void queueOverlayRender() {
        if (!this.overlayDirty || this.overlayRenderQueued || !this.built || !this.renderingAllowed.getAsBoolean()) return;
        long now = System.currentTimeMillis();
        if (now - this.lastOverlaySubmitMs < this.overlayIntervalMs()) return;
        this.overlayDirty = false;
        this.overlayRenderQueued = true;
        this.overlayRevision++;
        this.updateStaticAssetPaths();
        String path = this.overlayPath;
        String framePath = this.framePath;
        String bezelPath = this.bezelPath;
        long generation = ++this.overlayGeneration;
        DynamicMinimapSetting snapshot = this.settings.copy();
        int mapSize = this.logicalUiSize;
        int panelHeight = this.readoutHeight();
        int gap = this.readoutGap();
        double angle = this.frameAngle;
        if (!DynamicMinimapSession.renderAsync(() -> {
            try {
                if (!this.overlayWorkAllowed()) return;
                var image = DynamicMinimapRenderer.renderNativeHudParts(
                        snapshot, mapSize, READOUT_WIDTH, panelHeight, gap, angle);
                var prepared = image == null ? null : DynamicAssetPublisher.prepareShared(path, image.readout());
                var preparedFrame = image == null ? null : DynamicAssetPublisher.prepareShared(framePath, image.frame());
                var preparedBezel = image == null ? null : DynamicAssetPublisher.prepareShared(bezelPath, image.bezel());
                if (!this.overlayWorkAllowed()) return;
                this.worldExecute.accept(() -> {
                    this.overlayRenderQueued = false;
                    if (!this.overlayWorkAllowed()) return;
                    // A newer change superseded this render: render again rather than stay stale.
                    if (generation != this.overlayGeneration) this.overlayDirty = true;
                    if (generation == this.overlayGeneration && prepared != null) {
                        String publishedPath = this.publishPart(this.overlayPublications, prepared);
                        String publishedFrame = this.publishPart(this.framePublications, preparedFrame);
                        String publishedBezel = this.publishPart(this.bezelPublications, preparedBezel);
                        UICommandBuilder builder = new AnchorCommandBuilder();
                        boolean changed = false;
                        if (publishedBezel != null) {
                            this.bezelPath = publishedBezel;
                            if (!publishedBezel.equals(this.displayedBezelPath)) {
                                builder.set("#WayfinderMmRoot #WayfinderMmBezel.AssetPath", publishedBezel);
                                changed = true;
                            }
                            this.displayedBezelPath = publishedBezel;
                        }
                        if (publishedPath != null) {
                            this.overlayPath = publishedPath;
                            if (!publishedPath.equals(this.displayedOverlayPath)) {
                                builder.set("#WayfinderMmRoot #WayfinderMmOverlay.AssetPath", publishedPath);
                                changed = true;
                            }
                            this.displayedOverlayPath = publishedPath;
                        }
                        if (publishedFrame != null) {
                            this.framePath = publishedFrame;
                            if (!publishedFrame.equals(this.displayedFramePath)) {
                                builder.set("#WayfinderMmRoot #WayfinderMmFrame.AssetPath", publishedFrame);
                                changed = true;
                            }
                            this.displayedFramePath = publishedFrame;
                            changed |= this.cardinalFrameShown(builder, angle); // Labels follow the ring.
                        }
                        if (changed) this.update(false, builder);
                    }
                    // The normal tick admits the next dirty overlay after this callback exits.
                });
            } catch (RuntimeException failure) {
                this.overlayDirty = true;
                this.overlayRenderQueued = false;
                if (!DynamicMinimapSession.isStoppedWorldException(failure)) throw failure;
            }
        })) {
            this.overlayRenderQueued = false;
            this.overlayDirty = true;
        } else {
            this.lastOverlaySubmitMs = now;
        }
    }

    private boolean overlayWorkAllowed() {
        if (this.built && this.renderingAllowed.getAsBoolean()) return true;
        this.overlayDirty = this.built;
        this.overlayRenderQueued = false;
        return false;
    }

    private String maskFileFor(DynamicMinimapSetting maskSettings) {
        String shape = maskSettings.isCircle() ? "circle" : "square";
        return "DapperMap-" + this.slug + "-" + shape
                + "-r" + maskSettings.textureResolution + "-mask.png";
    }

    /** Logical offset of the terrain image inside the pan group (0 for a full buffer). */
    private int terrainInset() {
        return (this.bufferPixels - this.terrainPixels) / 2;
    }

    private int readoutHeight() { return readoutHeight(this.settings); }

    private static int readoutHeight(DynamicMinimapSetting settings) {
        if (!settings.infoPanelShown()) return 0;
        int height = (READOUT_PADDING_V * 2) + BRAND_HEIGHT;

        if (settings.showCoordinates) {
            height += COORDS_HEIGHT;
        }

        if (settings.showCompass) {
            height += META_HEIGHT;
        }

        if (settings.showClock) {
            height += META_HEIGHT;
        }

        if (settings.showBiome) {
            height += BIOME_HEIGHT;
        }

        return height;
    }

    /** Overlay publish requested by a layout setter; applied once by applyPendingLayout(). */
    private boolean overlayPublishPending;
    /** Layout rebuild requested; applied once by applyPendingLayout() (world thread only). */
    private boolean layoutRebuildPending;

    /** Marks the position layout for one rebuild; several setting changes in one tick share it. */
    private void rebuildPositionLayout() {
        if (this.built) this.layoutRebuildPending = true;
    }

    /**
     * Applies queued layout work once: the overlay is published first, then the layout that
     * references it is rebuilt. Called right after a tick's settings changes, and from
     * flushPendingVisuals as a safety net.
     */
    public void applyPendingLayout() {
        if (this.overlayPublishPending) {
            // Not cached yet: it is being drawn off the world thread. Keep the publish and the
            // layout rebuild pending so they still land together, on a later tick.
            if (!this.publishOverlayAsset(false, false)) return;
            this.overlayPublishPending = false;
        }
        if (this.layoutRebuildPending) {
            this.layoutRebuildPending = false;
            this.rebuildPositionLayoutNow();
        }
    }

    private void rebuildPositionLayoutNow() {
        if (!this.built) {
            return;
        }

        // The rebuilt markup shows the last delivered overlay; if a newer one is pending,
        // make sure it is (re)rendered and swapped in afterwards.
        if (this.overlayRenderQueued || !java.util.Objects.equals(this.overlayPath, this.displayedOverlayPath)) {
            this.overlayDirty = true;
        }
        UICommandBuilder builder = new UICommandBuilder();
        builder.remove("#WayfinderMmRoot #WayfinderMmPositionLayout");
        builder.appendInline("#WayfinderMmRoot", this.buildPositionLayoutMarkup());
        this.update(false, builder);
    }

    private int widgetWidth() {
        if (!this.settings.infoPanelShown()) return this.displaySize(this.logicalUiSize);
        if (this.isSideRail()) {
            return this.displaySize(this.logicalUiSize + this.readoutGap() + READOUT_WIDTH);
        }
        return this.displaySize(Math.max(this.logicalUiSize, this.readoutLogicalWidth()));
    }

    private int widgetHeight() {
        if (!this.settings.infoPanelShown()) return this.displaySize(this.logicalUiSize);
        if (this.isSideRail()) {
            return this.displaySize(Math.max(this.logicalUiSize, this.readoutHeight()));
        }
        return this.displaySize(this.logicalUiSize + this.readoutGap() + this.readoutHeight());
    }

    private int mapOriginX() {
        if (!this.settings.infoPanelShown()) return 0;
        if (this.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT) {
            return this.displaySize(READOUT_WIDTH + this.readoutGap());
        }
        return this.isSideRail() ? 0 : this.displayCoordinate(
                Math.max(0, (this.readoutLogicalWidth() - this.logicalUiSize) / 2));
    }

    private int mapOriginY() {
        if (!this.settings.infoPanelShown()) return 0;
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

    private int readoutGap() { return readoutGap(this.settings); }

    private static int readoutGap(DynamicMinimapSetting settings) {
        if (!settings.infoPanelShown()) return 0;
        return settings.isCircle() ? CIRCLE_READOUT_GAP : SQUARE_READOUT_GAP;
    }

    /** Logical HUD value in display units (same rounding as DynamicMinimapSetting, used for marker updates). */
    private int displayCoordinate(int logicalValue) {
        return DapperMapUtil.scaled(logicalValue, this.interfaceScalePercent);
    }

    /** Positive logical size in display units (0 stays 0, anything else at least 1). */
    private int displaySize(int logicalValue) {
        return logicalValue <= 0 ? 0 : Math.max(1, this.displayCoordinate(logicalValue));
    }

    private static final String PAN_ANCHOR = "#WayfinderMmRoot #WayfinderMmClip #WayfinderMmPan.Anchor";

    /** Sets the pan group's Anchor (terrain and markers move together). */
    private void setFullAnchor(UICommandBuilder builder, int logicalLeft, int logicalTop) {
        int size = this.displaySize(this.bufferPixels);
        setAnchor(builder, PAN_ANCHOR, this.displayCoordinate(logicalLeft), this.displayCoordinate(logicalTop), size, size);
    }

    /** Anchor Set via the hand-built fast path when the builder supports it, else the codec. */
    static void setAnchor(UICommandBuilder builder, String selector, int left, int top, int width, int height) {
        if (builder instanceof AnchorCommandBuilder fast && AnchorCommandBuilder.ENABLED) {
            fast.setAnchor(selector, left, top, width, height);
        } else {
            builder.setObject(selector, anchor(left, top, width, height));
        }
    }

    /**
     * UICommandBuilder that adds Anchor Sets as hand-built JSON (about 15x cheaper than the
     * Codec -> BSON -> JSON path; the output is byte-identical, checked once at class load).
     * Only for builders holding Set commands: the extra commands are appended after the
     * builder's own, which is order-safe because Sets on different selectors are independent.
     */
    static final class AnchorCommandBuilder extends UICommandBuilder {
        static final boolean ENABLED = selfCheck();
        private java.util.ArrayList<com.hypixel.hytale.protocol.packets.interface_.CustomUICommand> anchors;
        private final StringBuilder json = new StringBuilder(64);

        void setAnchor(String selector, int left, int top, int width, int height) {
            if (this.anchors == null) this.anchors = new java.util.ArrayList<>(8);
            this.anchors.add(new com.hypixel.hytale.protocol.packets.interface_.CustomUICommand(
                    com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType.Set,
                    selector, anchorJson(this.json, left, top, width, height), null));
        }

        @Override public com.hypixel.hytale.protocol.packets.interface_.CustomUICommand[] getCommands() {
            var own = super.getCommands();
            if (this.anchors == null || this.anchors.isEmpty()) return own;
            var all = java.util.Arrays.copyOf(own, own.length + this.anchors.size());
            for (int i = 0; i < this.anchors.size(); i++) all[own.length + i] = this.anchors.get(i);
            return all;
        }

        /** Same text UICommandBuilder.setObject produces for an Anchor (field order Left, Top, Height, Width). */
        static String anchorJson(StringBuilder out, int left, int top, int width, int height) {
            out.setLength(0);
            return out.append("{\"0\": {\"Left\": ").append(left).append(", \"Top\": ").append(top)
                    .append(", \"Height\": ").append(height).append(", \"Width\": ").append(width)
                    .append("}}").toString();
        }

        /** Falls back to the codec for good if a future Hytale changes the Anchor JSON format. */
        private static boolean selfCheck() {
            try {
                UICommandBuilder reference = new UICommandBuilder();
                reference.setObject("#Check.Anchor", anchor(12, -34, 300, 301));
                return anchorJson(new StringBuilder(), 12, -34, 300, 301).equals(reference.getCommands()[0].data);
            } catch (RuntimeException failed) {
                return false;
            }
        }
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
        public final boolean fixed;
        /** Hostile or attacking creature: placed in the threat marker layer. */
        public final boolean threat;
        /** Another player: placed in the player layer, above every creature. */
        public final boolean player;

        public Slot(String path, int left, int top, int size) {
            this(path, left, top, size, false, false);
        }

        public Slot(String path, int left, int top, int size, boolean fixed) {
            this(path, left, top, size, fixed, false);
        }

        public Slot(String path, int left, int top, int size, boolean fixed, boolean threat) {
            this(path, left, top, size, fixed, threat, false);
        }

        public Slot(String path, int left, int top, int size, boolean fixed, boolean threat, boolean player) {
            this.path = path;
            this.left = left;
            this.top = top;
            this.size = size;
            this.fixed = fixed;
            this.threat = threat;
            this.player = player;
        }
    }
}

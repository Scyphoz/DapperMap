package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import java.awt.BasicStroke;
import java.awt.Color;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapColours.Key.*;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.Shape;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import javax.imageio.ImageIO;

import dev.scyphoz.dappermap.service.DapperMapServerLoad;
import dev.scyphoz.dappermap.util.DapperMapUtil;

/** Pure rendering utilities for terrain, HUD chrome, previews and marker PNG assets. */
public final class DynamicMinimapRenderer {
    // Fixed visual colours that are intentionally not part of the player palette.
    private static final Color FIXED_MAP_BACKGROUND = new Color(0x0A1218);
    private static final Color FIXED_FRAME_SHADOW = new Color(0x03080C);
    private static final Color FIXED_MARKER_OUTLINE = new Color(0x000000);
    /** Map background behind terrain chunks (also fills rotated corners). */
    private static final Color MAP_BACKGROUND_FILL = withAlpha(FIXED_MAP_BACKGROUND, 235);

    private static final int CHUNK_SIZE = 32;
    /**
     * Static frame artwork is authored in this logical coordinate space and
     * stretched by Hytale to the selected HUD map size.
     */
    private static final int STATIC_ASSET_LOGICAL_SIZE = 220;
    public static final int ROTATION_BUCKETS = 360;
    private static final Font HUD_BRAND_FONT = trackedFont(10, true, 0.035F);
    private static final Font HUD_CAPTION_FONT = trackedFont(9, false, 0.105F);
    private static final Font HUD_VALUE_FONT = trackedFont(11, false, 0.015F);
    private static Font trackedFont(int size, boolean bold, float tracking) {
        return new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size)
                .deriveFont(Map.of(TextAttribute.TRACKING, tracking));
    }
    private static Font hudFont(int size, boolean bold, float tracking) {
        if (size == 10 && bold && tracking == 0.035F) return HUD_BRAND_FONT;
        if (size == 9 && !bold && tracking == 0.105F) return HUD_CAPTION_FONT;
        if (size == 11 && !bold && tracking == 0.015F) return HUD_VALUE_FONT;
        return trackedFont(size, bold, tracking);
    }
    private static final double[][] SQUARE_COMPASS_POINTS = {{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1}};
    private static final Font CARDINAL_FONT = new Font("SansSerif", 1, 15);
    private static final Map<String, byte[]> MASK_CACHE = new ConcurrentHashMap<>();
    private record ArrowKey(long angle, int rgb) {}
    /**
     * Single locator images by exact angle. The 72 heading images live in ARROW_SETS, so this only
     * needs room for the HUD's initial arrow and the settings previews' arbitrary angles.
     */
    private static final PngCache ARROW_CACHE = new PngCache(32, 256 * 1024);
    private static final byte[] BLANK_PNG = toPng(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));

    private DynamicMinimapRenderer() {
    }

    /**
     * Logical minimap pixels per world block. Session pan and marker math deliberately
     * stay in this coordinate space so both raster resolution and interfaceScalePercent
     * remain presentation-only and never alter zoom/world coverage.
     */
    public static double pixelsPerBlock(DynamicMinimapSetting settings) {
        return (double)settings.sizePixels / ((double)2.0F * (double)Math.max(16, settings.zoomBlocks));
    }

    public static int panMarginPixels(DynamicMinimapSetting settings) {
        return Math.max(32, settings.sizePixels / 2);
    }

    /** Logical UI size of the terrain buffer, not its PNG pixel dimensions. */
    public static int bufferPixels(DynamicMinimapSetting settings) {
        int buffer = settings.sizePixels + 2 * panMarginPixels(settings);
        return (buffer & 1) == 0 ? buffer : buffer + 1;
    }

    /** Raster pixels per logical UI pixel. */
    public static double textureScale(DynamicMinimapSetting settings) {
        return (double)Math.max(1, settings.textureResolution) / (double)Math.max(1, settings.sizePixels);
    }

    /** Actual PNG width/height used for the terrain buffer. */
    public static int textureBufferPixels(DynamicMinimapSetting settings) {
        int pixels = (int)Math.round((double)bufferPixels(settings) * textureScale(settings));
        // Rotation already delivers at most 512 pixels. Stitch directly at that
        // resolution instead of creating and downsampling up to a 2048px PNG.
        if (settings.rotationMode == 1) pixels = Math.min(512, pixels);
        return Math.max(2, (pixels & 1) == 0 ? pixels : pixels + 1);
    }

    public static double bufferOriginWorld(double center, DynamicMinimapSetting settings) {
        return center - (double)bufferPixels(settings) / (double)2.0F / pixelsPerBlock(settings);
    }

    /**
     * Stitches the world-map chunk images around (centerX, centerZ) into one terrain buffer.
     * The change signature of exactly the images drawn is hashed in the same pass (it used to be
     * a second pass over every chunk after rendering). {@code encodePng} false skips the PNG for
     * player-up maps, whose rotation source works from the image and encodes north-up lazily.
     */
    public static Terrain renderTerrain(WorldMapManager worldMapManager, double centerX, double centerZ,
                                        DynamicMinimapSetting settings, BiFunction<Integer, Integer, MapImage> caveImages,
                                        boolean encodePng) {
        int logicalBuffer = bufferPixels(settings);
        int textureBuffer = textureBufferPixels(settings);
        double logicalPixelsPerBlock = pixelsPerBlock(settings);
        double rasterScale = (double)textureBuffer / (double)logicalBuffer;
        double texturePixelsPerBlock = logicalPixelsPerBlock * rasterScale;
        double originX = bufferOriginWorld(centerX, settings);
        double originZ = bufferOriginWorld(centerZ, settings);
        BufferedImage terrain = new BufferedImage(textureBuffer, textureBuffer, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = terrain.createGraphics();
        graphics.setColor(MAP_BACKGROUND_FILL);
        graphics.fillRect(0, 0, textureBuffer, textureBuffer);
        int present = 0;
        int total = 0;
        long signature = 0L;
        if (worldMapManager != null || caveImages != null) {
            ChunkRange range = ChunkRange.of(centerX, centerZ, settings);
            int chunkPixels = (int)Math.ceil((double)CHUNK_SIZE * texturePixelsPerBlock);
            // Per-chunk hashes in grid order, folded after the loop exactly as terrainSignature does.
            long[] chunkHashes = new long[range.width() * range.height()];
            java.util.Arrays.fill(chunkHashes, 1L);

            java.util.List<int[]> chunks = new java.util.ArrayList<>(chunkHashes.length);
            for (int z = range.minZ(); z <= range.maxZ(); ++z)
                for (int x = range.minX(); x <= range.maxX(); ++x) chunks.add(new int[]{x, z});
            // Nearest chunks first, so the async request budget goes to what the player sees.
            chunks.sort(java.util.Comparator.comparingDouble(chunk -> {
                double dx = (chunk[0] + 0.5) * CHUNK_SIZE - centerX;
                double dz = (chunk[1] + 0.5) * CHUNK_SIZE - centerZ;
                return dx * dx + dz * dz;
            }));
            int requests = 0;
            // One scratch image for chunk pixels, decoded straight into its backing array and
            // recreated only when a chunk has another size (BetterMap cave chunks differ).
            BufferedImage chunkImage = null;
            int[] chunkArgb = null;
            Object interpolation = null; // Current interpolation hint, set only when it changes.
            for (int[] coordinate : chunks) {
                int chunkX = coordinate[0], chunkZ = coordinate[1];
                ++total;

                try {
                    MapImage image;
                    if (caveImages instanceof dev.scyphoz.dappermap.integration.BetterMapIntegration.CaveView view) {
                        image = view.peek(chunkX, chunkZ);
                        if (image == null && requests < 16) {
                            ++requests;
                            image = view.apply(chunkX, chunkZ);
                        }
                    } else if (caveImages != null) {
                        image = caveImages.apply(chunkX, chunkZ);
                    } else {
                        image = worldMapManager.getImageIfInMemory(chunkX, chunkZ);
                        if (image == null && requests < 16) {
                            ++requests;
                            image = worldMapManager.getImageAsync(chunkX, chunkZ).getNow(null);
                        }
                    }
                    // Hash before drawing, so an image that fails to decode still counts as seen.
                    if (image != null) chunkHashes[range.indexOf(chunkX, chunkZ)] = chunkHash(image);
                    if (image != null && image.width > 0 && image.height > 0
                            && (long)image.width * image.height <= Integer.MAX_VALUE) {
                        if (chunkImage == null || chunkImage.getWidth() != image.width || chunkImage.getHeight() != image.height) {
                            chunkImage = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB);
                            chunkArgb = backingArgb(chunkImage);
                        }
                        if (decodePixels(image, chunkArgb, chunkImage)) {
                            int drawX = (int)Math.round(((double)chunkX * (double)CHUNK_SIZE - originX) * texturePixelsPerBlock);
                            int drawZ = (int)Math.round(((double)chunkZ * (double)CHUNK_SIZE - originZ) * texturePixelsPerBlock);
                            // Preserve detail when BetterMap supplies more samples per chunk.
                            Object wanted = image.width > chunkPixels || image.height > chunkPixels
                                    ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                                    : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
                            if (wanted != interpolation) {
                                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, wanted);
                                interpolation = wanted;
                            }
                            graphics.drawImage(chunkImage, drawX, drawZ, chunkPixels, chunkPixels, (ImageObserver)null);
                            ++present;
                        }
                    }
                } catch (Throwable ignored) {
                    // A chunk that cannot be read is left as background (and hashed as missing).
                }
            }
            signature = foldSignature(chunkHashes);
        }

        graphics.dispose();
        if (present == 0) return new Terrain(null, null, 0, total, signature);
        return new Terrain(encodePng ? terrainPng(terrain) : null, terrain, present, total, signature);
    }

    /**
     * Cheap change signature of the chunk images currently in memory for a buffer (no chunk
     * loads are started). Used by the periodic probe; renderTerrain computes the same value for
     * the images it drew.
     */
    public static long terrainSignature(WorldMapManager worldMapManager, double centerX, double centerZ, DynamicMinimapSetting settings, BiFunction<Integer, Integer, MapImage> caveImages) {
        if (worldMapManager == null && caveImages == null) return 0L;
        ChunkRange range = ChunkRange.of(centerX, centerZ, settings);
        long[] chunkHashes = new long[range.width() * range.height()];
        for (int chunkZ = range.minZ(); chunkZ <= range.maxZ(); ++chunkZ) {
            for (int chunkX = range.minX(); chunkX <= range.maxX(); ++chunkX) {
                long hash = 1L;
                try {
                    MapImage image = caveImages instanceof dev.scyphoz.dappermap.integration.BetterMapIntegration.CaveView view
                            ? view.peek(chunkX, chunkZ)
                            : caveImages != null ? caveImages.apply(chunkX, chunkZ)
                            : worldMapManager.getImageIfInMemory(chunkX, chunkZ);
                    if (image != null) hash = chunkHash(image);
                } catch (Throwable ignored) {
                    // Unreadable chunk: hashed as missing.
                }
                chunkHashes[range.indexOf(chunkX, chunkZ)] = hash;
            }
        }
        return foldSignature(chunkHashes);
    }

    /** World-map chunks covered by a terrain buffer centred on (centerX, centerZ). */
    private record ChunkRange(int minX, int minZ, int maxX, int maxZ) {
        static ChunkRange of(double centerX, double centerZ, DynamicMinimapSetting settings) {
            double originX = bufferOriginWorld(centerX, settings);
            double originZ = bufferOriginWorld(centerZ, settings);
            double worldSize = (double)bufferPixels(settings) / pixelsPerBlock(settings);
            return new ChunkRange(
                    ChunkUtil.chunkCoordinate((int)Math.floor(originX)),
                    ChunkUtil.chunkCoordinate((int)Math.floor(originZ)),
                    ChunkUtil.chunkCoordinate((int)Math.ceil(originX + worldSize) - 1),
                    ChunkUtil.chunkCoordinate((int)Math.ceil(originZ + worldSize) - 1));
        }
        int width() { return this.maxX - this.minX + 1; }
        int height() { return this.maxZ - this.minZ + 1; }
        /** Row-major index (z rows, x columns), the order the signature is folded in. */
        int indexOf(int chunkX, int chunkZ) { return (chunkZ - this.minZ) * width() + (chunkX - this.minX); }
    }

    /** Sampled hash of one chunk image (dimensions, bit depth, packed indices and palette). */
    private static long chunkHash(MapImage image) {
        long chunkHash = 1L;
        chunkHash = 31L * chunkHash + (long)image.width;
        chunkHash = 31L * chunkHash + (long)image.height;
        chunkHash = 31L * chunkHash + (long)Byte.toUnsignedInt(image.bitsPerIndex);
        chunkHash = 31L * chunkHash + sampleHash(image.packedIndices);
        chunkHash = 31L * chunkHash + sampleHash(image.palette);
        return chunkHash;
    }

    /** FNV-1a style fold of per-chunk hashes in row-major order. */
    private static long foldSignature(long[] chunkHashes) {
        long hash = 1469598103934665603L;
        for (long chunkHash : chunkHashes) hash = (hash ^ chunkHash) * 1099511628211L;
        return hash;
    }

    public static byte[] renderShapeMask(DynamicMinimapSetting settings) {
        String shape = settings.isCircle() ? "c" : "r";
        String key = shape + "@" + settings.textureResolution;
        byte[] cached = MASK_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        int textureSize = Math.max(1, settings.textureResolution);
        BufferedImage image = new BufferedImage(textureSize, textureSize, 2);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );
        graphics.setColor(Color.WHITE);

        double inset = textureSize * 11.0D / STATIC_ASSET_LOGICAL_SIZE;
        double aperture = textureSize - 2 * inset;
        if (settings.isCircle()) {
            graphics.fill(new Ellipse2D.Double(inset, inset, aperture, aperture));
        } else {
            double corner = textureSize * 10.0D / STATIC_ASSET_LOGICAL_SIZE;
            graphics.fill(new RoundRectangle2D.Double(inset, inset, aperture, aperture, corner, corner));
        }

        graphics.dispose();
        byte[] png = toPng(image);
        if (png != null) {
            MASK_CACHE.put(key, png);
        }

        return png;
    }

    /** The frame artwork at texture resolution (bezel fixed, compass markings at mapAngle's bucket). */
    private static BufferedImage renderFrameImage(DynamicMinimapSetting settings, double mapAngle) {
        return renderFrameImage(settings, mapAngle, true, true);
    }

    /**
     * The frame artwork with either layer optional. The live HUD sends the fixed bezel once and
     * the compass ring per heading (about 9 KB instead of 33 KB per player-up heading); the
     * settings preview draws both together.
     */
    private static BufferedImage renderFrameImage(DynamicMinimapSetting settings, double mapAngle,
                                                  boolean bezel, boolean compass) {
        DynamicMinimapColours colours = settings.colours;
        int bucket = rotationBucket(mapAngle);
        int logicalSize = STATIC_ASSET_LOGICAL_SIZE;
        int textureSize = Math.max(1, settings.textureResolution);
        double scale = (double)textureSize / (double)logicalSize;

        BufferedImage image = new BufferedImage(textureSize, textureSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );
        graphics.scale(scale, scale);

        if (bezel) {
            if (settings.isCircle()) {
                drawApertureCircleFrame(graphics, logicalSize, colours);
            } else {
                drawApertureSquareFrame(graphics, logicalSize, colours);
            }
        }

        if (compass) drawTexturedCompass(graphics, logicalSize, settings.isCircle(), bucketAngle(bucket), colours);
        graphics.dispose();
        return image;
    }

    private static BufferedImage renderHudChromeImage(DynamicMinimapSetting settings, int mapSize,
                                                      int sidePanelWidth, int panelHeight, int gap) {
        boolean side = settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT;
        int panelWidth = sidePanelWidth;
        int width = side ? mapSize + gap + panelWidth : Math.max(mapSize, panelWidth);
        int height = side ? Math.max(mapSize, panelHeight) : mapSize + gap + panelHeight;

        int mapX = settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                ? panelWidth + gap : (side ? 0 : Math.max(0, (width - mapSize) / 2));
        int mapY = settings.infoPanelPosition == DynamicMinimapSetting.INFO_ABOVE
                ? panelHeight + gap : Math.max(0, (height - mapSize) / 2);
        if (settings.infoPanelPosition == DynamicMinimapSetting.INFO_BELOW) mapY = 0;

        int panelX = settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT
                ? mapSize + gap : (side ? 0 : Math.max(0, (width - panelWidth) / 2));
        int panelY = settings.infoPanelPosition == DynamicMinimapSetting.INFO_BELOW
                ? mapSize + gap : (settings.infoPanelPosition == DynamicMinimapSetting.INFO_ABOVE
                ? 0 : Math.max(0, (height - panelHeight) / 2));

        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color panel = settings.colours.awt(PANEL, 168);
        Color edge = settings.colours.awt(EDGE, 235);
        Color survey = settings.colours.awt(TICKS, 245);

        Path2D.Double shell = panelShellPath(settings, panelX, panelY, panelWidth, panelHeight);
        // Keep the information panel as a restrained pane of tinted glass.  The
        // concept does not use a coloured outline around the whole panel; only
        // a very faint transparency break defines its outer silhouette.
        graphics.setColor(panel);
        graphics.fill(shell);
        graphics.setColor(new Color(255, 255, 255, 10));
        graphics.fill(shell);
        graphics.setStroke(new BasicStroke(0.75F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(205, 225, 242, 46));
        graphics.draw(shell);

        // Give only the panel edge facing the minimap a restrained cyan accent.
        // The remaining perimeter stays as the faint glass boundary above.
        drawMapFacingPanelEdge(graphics, settings, panelX, panelY, panelWidth, panelHeight);
        graphics.dispose();
        return image;
    }

    private record OverlayLayout(int mapSize, int width, int height, int panelHeight, int gap,
                                 int shape, int position, int alignment, int flags, DynamicMinimapColours colours) {}
    private record RowLayerKey(OverlayLayout layout, int row, String value) {}
    private record FrameLayerKey(int mapSize, int width, int height, int mapX, int mapY,
                                 int resolution, int shape, boolean panel, int angle, String colours) {}
    /** Room for every player-up compass frame (72 headings) of a couple of HUD set-ups, plus panel layers. */
    private static final PngCache OVERLAY_LAYERS = new PngCache(192, 6 * 1024 * 1024);
    /** Bound encoded-byte retention, with gradual eviction instead of cache-wide clearing. */
    static final class PngCache {
        private final java.util.LinkedHashMap<Object, byte[]> entries = new java.util.LinkedHashMap<>(16, .75F, true);
        private final int capacity, byteLimit;
        private int bytes;
        PngCache(int capacity, int byteLimit) { this.capacity = capacity; this.byteLimit = byteLimit; }
        synchronized byte[] get(Object key) { return entries.get(key); }
        synchronized void put(Object key, byte[] value) {
            if (value == null || value.length > byteLimit) return;
            byte[] old = entries.put(key, value);
            bytes += value.length - (old == null ? 0 : old.length);
            var iterator = entries.entrySet().iterator();
            while (entries.size() > capacity || bytes > byteLimit) {
                bytes -= iterator.next().getValue().length; iterator.remove();
            }
        }
        synchronized int bytes() { return bytes; }
        synchronized int size() { return entries.size(); }
    }


    /** HUD layers: compass ring (per heading), info panel, and the fixed bezel under the ring. */
    public record NativeHudParts(byte[] frame, byte[] readout, byte[] bezel) {}

    /** Native value labels make the panel independent of changing telemetry. */
    public static NativeHudParts renderNativeHudParts(DynamicMinimapSetting settings, int mapSize,
                                                      int sidePanelWidth, int panelHeight, int gap, double angle) {
        return nativeHudParts(settings, mapSize, sidePanelWidth, panelHeight, gap, angle, false);
    }

    /**
     * The HUD parts only if every one is already cached, else null; never renders. Lets the
     * world thread publish a layout change at once when possible and hand a cache miss
     * (25-80 ms of drawing) to the render pool instead of stalling the world tick.
     */
    public static NativeHudParts cachedNativeHudParts(DynamicMinimapSetting settings, int mapSize,
                                                      int sidePanelWidth, int panelHeight, int gap, double angle) {
        return nativeHudParts(settings, mapSize, sidePanelWidth, panelHeight, gap, angle, true);
    }

    private static NativeHudParts nativeHudParts(DynamicMinimapSetting settings, int mapSize, int sidePanelWidth,
                                                 int panelHeight, int gap, double angle, boolean cachedOnly) {
        boolean side = settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT;
        int width = !settings.infoPanelEnabled ? mapSize : side ? mapSize + gap + sidePanelWidth : Math.max(mapSize, sidePanelWidth);
        int height = !settings.infoPanelEnabled ? mapSize : side ? Math.max(mapSize, panelHeight) : mapSize + gap + panelHeight;
        int flags = (settings.infoPanelEnabled ? 16 : 0) | (settings.showCoordinates ? 1 : 0)
                | (settings.showCompass ? 2 : 0) | (settings.showClock ? 4 : 0) | (settings.showBiome ? 8 : 0);
        var key = new RowLayerKey(new OverlayLayout(mapSize, width, height, panelHeight, gap,
                settings.shape, settings.infoPanelPosition, settings.infoPanelTextAlignment, flags, settings.colours), -2, "native");
        byte[] panel = OVERLAY_LAYERS.get(key);
        if (panel == null) {
            if (cachedOnly) return null;
            BufferedImage image = renderHudOverlayImage(settings, mapSize, sidePanelWidth, panelHeight, gap,
                    0, "", "", "", "", false);
            if (image == null) return null;
            panel = toPng(image);
            OVERLAY_LAYERS.put(key, panel);
        }
        byte[] frame = frameLayerPng(settings, mapSize, angle, false, cachedOnly);
        byte[] bezel = frameLayerPng(settings, mapSize, 0.0D, true, cachedOnly);
        if (panel == null || frame == null || bezel == null) return null;
        return new NativeHudParts(frame, panel, bezel);
    }

    /** The compass ring for one heading (no bezel), cropped to the map. Cached per heading. */
    public static byte[] renderNativeHudFrame(DynamicMinimapSetting settings, int size, double angle) {
        return frameLayerPng(settings, size, angle, false, false);
    }

    /** The fixed bezel (no compass ring), cropped to the map. One per shape/size/resolution/colours. */
    public static byte[] renderNativeHudBezel(DynamicMinimapSetting settings, int size) {
        return frameLayerPng(settings, size, 0.0D, true, false);
    }

    /** Crop to the map and retain the previous logical-size frame pixels. */
    private static byte[] frameLayerPng(DynamicMinimapSetting settings, int size, double angle, boolean bezel,
                                        boolean cachedOnly) {
        // Heading -1 marks the bezel, which is the same at every heading.
        var key = new FrameLayerKey(size, size, size, 0, 0, settings.textureResolution,
                settings.shape, settings.infoPanelEnabled, bezel ? -1 : rotationBucket(angle), settings.colours.frameKey());
        byte[] cached = OVERLAY_LAYERS.get(key);
        if (cached != null || cachedOnly) return cached;
        try {
            // Draw from the frame image directly: encoding it as a PNG at texture resolution and
            // decoding it again (identical pixels) was the most expensive part of a new heading.
            BufferedImage decoded = renderFrameImage(settings, angle, bezel, !bezel);
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            if (!settings.infoPanelEnabled)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            else {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            }
            graphics.drawImage(decoded, 0, 0, size, size, null);
            graphics.dispose();
            byte[] png = toPng(image);
            OVERLAY_LAYERS.put(key, png);
            return png;
        } catch (RuntimeException ignored) { return null; }
    }

    /**
     * Every foreground HUD element (frame, panel, telemetry) in one coordinate space. The live
     * terrain, markers and player arrow are independent layers underneath this overlay.
     */
    private static BufferedImage renderHudOverlayImage(DynamicMinimapSetting settings, int mapSize,
                                                       int sidePanelWidth, int panelHeight, int gap,
                                                       double frameAngle, String coords, String compass,
                                                       String clock, String biome) {
        return renderHudOverlayImage(settings, mapSize, sidePanelWidth, panelHeight, gap,
                frameAngle, coords, compass, clock, biome, true);
    }

    private static BufferedImage renderHudOverlayImage(DynamicMinimapSetting settings, int mapSize,
                                                       int sidePanelWidth, int panelHeight, int gap,
                                                       double frameAngle, String coords, String compass,
                                                       String clock, String biome, boolean includeFrame) {
        if (!settings.infoPanelEnabled) {
            try {
                BufferedImage overlay = new BufferedImage(mapSize, mapSize, BufferedImage.TYPE_INT_ARGB);
                // Frame artwork drawn directly (no PNG encode and decode of the texture-size frame).
                BufferedImage frame = includeFrame ? renderFrameImage(settings, frameAngle) : null;
                if (frame != null) {
                    Graphics2D g = overlay.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(frame, 0, 0, mapSize, mapSize, null);
                    g.dispose();
                }
                return overlay;
            } catch (Exception ignored) { return null; }
        }
        try {
            // Chrome is an intermediate raster, not a transfer asset. Paint onto
            // it directly instead of encoding, decoding and expanding a PNG.
            BufferedImage overlay = renderHudChromeImage(settings, mapSize, sidePanelWidth, panelHeight, gap);
            Graphics2D graphics = overlay.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            boolean side = settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                    || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT;
            int panelWidth = sidePanelWidth;
            int mapX = settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                    ? panelWidth + gap : (side ? 0
                    : Math.max(0, (overlay.getWidth() - mapSize) / 2));
            int mapY = settings.infoPanelPosition == DynamicMinimapSetting.INFO_ABOVE
                    ? panelHeight + gap : Math.max(0, (overlay.getHeight() - mapSize) / 2);
            if (settings.infoPanelPosition == DynamicMinimapSetting.INFO_BELOW) mapY = 0;
            int panelX = settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT
                    ? mapSize + gap : (side ? 0 : Math.max(0, (overlay.getWidth() - panelWidth) / 2));
            int panelY = settings.infoPanelPosition == DynamicMinimapSetting.INFO_BELOW
                    ? mapSize + gap : (settings.infoPanelPosition == DynamicMinimapSetting.INFO_ABOVE
                    ? 0 : Math.max(0, (overlay.getHeight() - panelHeight) / 2));

            BufferedImage frame = includeFrame ? renderFrameImage(settings, frameAngle) : null;
            if (frame != null) {
                graphics.drawImage(frame, mapX, mapY, mapSize, mapSize, null);
            }

            drawTelemetry(graphics, settings, panelX, panelY, panelWidth,
                    coords, compass, clock, biome);
            graphics.dispose();
            return overlay;
        } catch (Exception ignored) {
            return null;
        }
    }


    /** The player HUD's terrain, telemetry and visible marker assets frozen when settings open. */
    /** {@code creature}: a creature icon or dot; {@code iconKey}: its creature type (icons only). */
    public record PreviewMarker(byte[] image, int x, int y, int size, boolean creature, String iconKey) { }
    public record LiveSnapshot(byte[] terrain, int mapSize, int bufferSize, int panX, int panY,
                               double selfAngle, double frameAngle, String coords,
                               String compass, String clock, String biome,
                               java.util.List<PreviewMarker> markers) { }

    public static byte[] renderSettingsPreview(DynamicMinimapSetting settings, LiveSnapshot snapshot) {
        BufferedImage stage = settingsPreviewImage(settings, snapshot);
        return stage == null ? null : toPng(stage);
    }

    /** The settings preview stage (360x286, HUD centred), or null. Render pool only. */
    private static BufferedImage settingsPreviewImage(DynamicMinimapSetting settings, LiveSnapshot snapshot) {
        if (snapshot == null || snapshot.terrain() == null) return null;
        DynamicMinimapSetting preview = settings.copy();
        preview.normalize();
        int mapSize = Math.max(120, Math.min(360, preview.sizePixels));
        int panelWidth = 148;
        int gap = preview.isCircle() ? 8 : 16;
        int panelHeight = 24 + 12;
        if (preview.showCoordinates) panelHeight += 43;
        if (preview.showCompass) panelHeight += 43;
        if (preview.showClock) panelHeight += 43;
        if (preview.showBiome) panelHeight += 43;

        // The overlay is composited directly (it used to be PNG encoded and decoded first).
        BufferedImage overlay = renderHudOverlayImage(preview, mapSize, panelWidth, panelHeight, gap, snapshot.frameAngle(),
                snapshot.coords(), snapshot.compass(), snapshot.clock(), snapshot.biome());
        if (overlay == null) return null;
        try {
            BufferedImage hud = new BufferedImage(overlay.getWidth(), overlay.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = hud.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean side = preview.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                    || preview.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT;
            int mapX = !preview.infoPanelEnabled ? 0 : (preview.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT ? panelWidth + gap
                    : (side ? 0 : Math.max(0, (hud.getWidth() - mapSize) / 2)));
            int mapY = !preview.infoPanelEnabled ? 0 : (preview.infoPanelPosition == DynamicMinimapSetting.INFO_ABOVE ? panelHeight + gap
                    : (preview.infoPanelPosition == DynamicMinimapSetting.INFO_BELOW ? 0
                    : Math.max(0, (hud.getHeight() - mapSize) / 2)));

            Shape oldClip = g.getClip();
            double apertureInset = mapSize * 11.0D / STATIC_ASSET_LOGICAL_SIZE;
            double apertureSize = mapSize - 2 * apertureInset;
            if (preview.isCircle()) g.setClip(new Ellipse2D.Double(mapX + apertureInset, mapY + apertureInset, apertureSize, apertureSize));
            else g.setClip(new RoundRectangle2D.Double(mapX + apertureInset, mapY + apertureInset, apertureSize, apertureSize, mapSize * 10.0D / STATIC_ASSET_LOGICAL_SIZE, mapSize * 10.0D / STATIC_ASSET_LOGICAL_SIZE));
            BufferedImage terrain = ImageIO.read(new ByteArrayInputStream(snapshot.terrain()));
            if (terrain != null) {
                double ratio = (double)mapSize / snapshot.mapSize();
                int panX = (int)Math.round(snapshot.panX() * ratio);
                int panY = (int)Math.round(snapshot.panY() * ratio);
                int bufferSize = (int)Math.round(snapshot.bufferSize() * ratio);
                g.drawImage(terrain, mapX + panX, mapY + panY,
                        mapX + panX + bufferSize, mapY + panY + bufferSize,
                        0, 0, terrain.getWidth(), terrain.getHeight(), null);
                java.util.List<double[]> creatureCandidates = new java.util.ArrayList<>();
                java.util.List<double[]> occupied = new java.util.ArrayList<>();
                // Creature types seen around the player (Icons mode): the colour samples use their faces.
                java.util.LinkedHashSet<String> creatureKeys = new java.util.LinkedHashSet<>();
                for (PreviewMarker marker : snapshot.markers()) {
                    int x = mapX + (int)Math.round(marker.x() * ratio);
                    int y = mapY + (int)Math.round(marker.y() * ratio);
                    int size = Math.max(1, (int)Math.round(marker.size() * ratio));
                    if (marker.creature()) {
                        // Live creatures are not drawn; their spots become sample positions.
                        if (marker.iconKey() != null && creatureKeys.size() < 8) creatureKeys.add(marker.iconKey());
                        double centerX = (marker.x() + marker.size() / 2.0) * ratio;
                        double centerY = (marker.y() + marker.size() / 2.0) * ratio;
                        if (centerX >= 10 && centerX <= mapSize - 10
                                && centerY >= 10 && centerY <= mapSize - 10) {
                            creatureCandidates.add(new double[]{centerX, centerY});
                        }
                    } else {
                        drawPreviewAsset(g, marker.image(), x, y, size);
                        occupied.add(new double[]{(marker.x() + marker.size() / 2.0) * ratio,
                                (marker.y() + marker.size() / 2.0) * ratio});
                    }
                }
                // Keep only a few spaced creature examples, one per threat colour, so colour edits
                // are easy to follow; the rest of the live creature pool is left out of the preview.
                // Icons mode shows real creature faces from around the player (dots if none).
                if (preview.showEntities) {
                    // Hostile Only hides neutral and friendly creatures, so the preview does too.
                    DynamicMinimapColours.Key[] kinds = preview.hostileCreaturesOnly
                            ? new DynamicMinimapColours.Key[]{HOSTILE_ACTIVE, HOSTILE}
                            : new DynamicMinimapColours.Key[]{HOSTILE_ACTIVE, HOSTILE, NEUTRAL, FRIENDLY};
                    double[][] positions = {
                            {0.28, 0.32}, {0.68, 0.34}, {0.30, 0.68}, {0.70, 0.68}
                    };
                    java.util.Collections.shuffle(creatureCandidates,
                            new java.util.Random(snapshot.coords().hashCode()));
                    java.util.List<double[]> samples = new java.util.ArrayList<>();
                    double separation = mapSize * 0.18;
                    for (double[] candidate : creatureCandidates) {
                        if (samples.size() == kinds.length) break;
                        if (preview.isCircle() && Math.hypot(candidate[0] - mapSize / 2.0,
                                candidate[1] - mapSize / 2.0) > mapSize * 0.43) continue;
                        if (Math.hypot(candidate[0] - mapSize / 2.0,
                                candidate[1] - mapSize / 2.0) < mapSize * 0.20) continue;
                        if (previewPositionClear(candidate, occupied, separation * 0.55)
                                && previewPositionClear(candidate, samples, separation)) {
                            samples.add(candidate);
                        }
                    }
                    for (double[] fallback : positions) {
                        if (samples.size() == kinds.length) break;
                        double[] candidate = {mapSize * fallback[0], mapSize * fallback[1]};
                        if (previewPositionClear(candidate, occupied, separation * 0.55)
                                && previewPositionClear(candidate, samples, separation)) samples.add(candidate);
                    }
                    for (double[] fallback : positions) {
                        if (samples.size() == kinds.length) break;
                        double[] candidate = {mapSize * fallback[0], mapSize * fallback[1]};
                        if (previewPositionClear(candidate, samples, separation * 0.55)) samples.add(candidate);
                    }
                    int dotSize = Math.max(9, (int)Math.round(10 * mapSize / 220.0));
                    int iconSize = DynamicCreatureIcons.markerSize(preview);
                    java.util.List<String> keys = preview.creatureIcons
                            ? new java.util.ArrayList<>(creatureKeys) : java.util.List.of();
                    for (int i = 0; i < samples.size(); i++) {
                        int rgb = preview.colours.awt(kinds[i], 255).getRGB() & 0xFFFFFF;
                        // A different creature for each sample where possible, edged in that colour.
                        byte[] image = keys.isEmpty() ? null
                                : DynamicCreatureIcons.previewIcon(keys.get(i % keys.size()), rgb);
                        int size = image != null ? iconSize : dotSize;
                        if (image == null) image = renderDot(rgb, preview.colours);
                        drawPreviewAsset(g, image,
                                mapX + samples.get(i)[0] - size / 2.0,
                                mapY + samples.get(i)[1] - size / 2.0, size);
                    }
                }
            }
            g.setClip(oldClip);
            int arrowSize = Math.max(18, mapSize / 10);
            drawPreviewAsset(g, renderSelfArrow(snapshot.selfAngle(), preview.colours),
                    mapX + (mapSize - arrowSize) / 2, mapY + (mapSize - arrowSize) / 2,
                    arrowSize);
            g.drawImage(overlay, 0, 0, null);
            g.dispose();

            int stageW = 360, stageH = 286;
            BufferedImage stage = new BufferedImage(stageW, stageH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = stage.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            // Keep the minimap at the same preview scale when the Info Panel is disabled.
            // The live HUD correctly collapses to the map alone, but using that smaller image
            // as the preview's scale reference made the map expand to fill the preview stage.
            // For preview scaling only, reserve the footprint the enabled panel would have used.
            int scaleReferenceWidth = hud.getWidth();
            int scaleReferenceHeight = hud.getHeight();
            if (!preview.infoPanelEnabled) {
                boolean referenceSide = preview.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                        || preview.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT;
                scaleReferenceWidth = referenceSide
                        ? mapSize + gap + panelWidth
                        : Math.max(mapSize, panelWidth);
                scaleReferenceHeight = referenceSide
                        ? Math.max(mapSize, panelHeight)
                        : mapSize + gap + panelHeight;
            }
            double scale = Math.min((stageW - 8.0) / scaleReferenceWidth,
                    (stageH - 8.0) / scaleReferenceHeight);
            int w = Math.max(1, (int)Math.round(hud.getWidth() * scale));
            int h = Math.max(1, (int)Math.round(hud.getHeight() * scale));
            sg.drawImage(hud, (stageW - w) / 2, (stageH - h) / 2, w, h, null);
            sg.dispose();
            return stage;
        } catch (Exception ignored) { return null; }
    }

    /** True when the candidate point is at least minimumDistance from every other point. */
    private static boolean previewPositionClear(double[] candidate, java.util.List<double[]> others,
                                                double minimumDistance) {
        for (double[] other : others) {
            if (Math.hypot(candidate[0] - other[0], candidate[1] - other[1]) < minimumDistance) {
                return false;
            }
        }
        return true;
    }

    /**
     * The Position-tab preview's HUD image: the settings preview trimmed to its visible pixels,
     * plus the extent its scale is based on. Independent of the HUD X/Y position.
     */
    private record PositionPreviewBase(BufferedImage hud, int scaleReference) {}

    /** A cached base, valid for one snapshot and one draft (compared without its X/Y position). */
    private record CachedPositionBase(LiveSnapshot snapshot, DynamicMinimapSetting settings, PositionPreviewBase base) {}

    /** A few recent bases (one per open settings page, typically), so slider moves skip the HUD render. */
    private static final java.util.ArrayDeque<CachedPositionBase> POSITION_BASES = new java.util.ArrayDeque<>();
    private static final int POSITION_BASE_LIMIT = 4;

    /** Every public instance field of the settings, except the HUD position (the base does not depend on it). */
    private static final java.lang.reflect.Field[] PREVIEW_FIELDS = java.util.Arrays.stream(
                    DynamicMinimapSetting.class.getFields())
            .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
            .filter(field -> !field.getName().equals("hudPositionX") && !field.getName().equals("hudPositionY"))
            .toArray(java.lang.reflect.Field[]::new);

    /** True when two drafts give the same preview HUD (every field equal except the X/Y position). */
    private static boolean samePreviewHud(DynamicMinimapSetting first, DynamicMinimapSetting second) {
        try {
            for (java.lang.reflect.Field field : PREVIEW_FIELDS)
                if (!java.util.Objects.equals(field.get(first), field.get(second))) return false;
            return true;
        } catch (IllegalAccessException unexpected) {
            return false;
        }
    }

    /**
     * Renders the Position-tab preview as a complete screen rather than a close-up of the HUD.
     * The HUD image is the same settings preview used by the other tabs, scaled and placed from
     * the draft X/Y percentages. Position-only edits reuse the HUD image of the previous call.
     */
    public static byte[] renderPositionPreview(DynamicMinimapSetting settings, LiveSnapshot snapshot) {
        if (snapshot == null) return null;
        DynamicMinimapSetting draft = settings.copy();
        PositionPreviewBase base = null;
        synchronized (POSITION_BASES) {
            for (CachedPositionBase cached : POSITION_BASES)
                if (cached.snapshot() == snapshot && samePreviewHud(cached.settings(), draft)) { base = cached.base(); break; }
        }
        if (base == null) {
            base = positionPreviewBase(draft, snapshot);
            if (base == null) return null;
            synchronized (POSITION_BASES) {
                POSITION_BASES.removeIf(cached -> cached.snapshot() == snapshot);
                POSITION_BASES.addFirst(new CachedPositionBase(snapshot, draft, base));
                while (POSITION_BASES.size() > POSITION_BASE_LIMIT) POSITION_BASES.removeLast();
            }
        }
        return composePositionPreview(base, draft);
    }

    /** Visible bounds {minX, minY, maxX, maxY} of an image (alpha above 0), or null when empty. */
    private static int[] visibleBounds(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = argbPixels(image);
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                if ((pixels[rowStart + x] >>> 24) == 0) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                maxY = y;
            }
        }
        return maxX >= minX && maxY >= minY ? new int[]{minX, minY, maxX, maxY} : null;
    }

    private static PositionPreviewBase positionPreviewBase(DynamicMinimapSetting settings, LiveSnapshot snapshot) {
        BufferedImage hud = settingsPreviewImage(settings, snapshot);
        if (hud == null) return null;
        // The preview stage is a transparent canvas with the HUD centred inside it. Position the
        // visible HUD bounds, not that canvas; otherwise its transparent margins keep corner
        // selections near the centre.
        int[] bounds = visibleBounds(hud);
        if (bounds != null) hud = hud.getSubimage(bounds[0], bounds[1], bounds[2] - bounds[0] + 1, bounds[3] - bounds[1] + 1);
        int scaleReference = Math.max(hud.getWidth(), hud.getHeight());
        if (!settings.infoPanelEnabled) {
            // The disabled-panel map is deliberately smaller so its scale matches the enabled
            // preview. Do not enlarge it again after trimming: scale by the enabled HUD footprint.
            DynamicMinimapSetting reference = settings.copy();
            reference.infoPanelEnabled = true;
            BufferedImage referenceHud = settingsPreviewImage(reference, snapshot);
            int[] referenceBounds = referenceHud == null ? null : visibleBounds(referenceHud);
            if (referenceBounds != null) {
                scaleReference = Math.max(referenceBounds[2] - referenceBounds[0] + 1,
                        referenceBounds[3] - referenceBounds[1] + 1);
            }
        }
        return new PositionPreviewBase(hud, scaleReference);
    }

    /** The 360x286 screen with the HUD placed at the draft's X/Y percentages, as PNG. */
    private static byte[] composePositionPreview(PositionPreviewBase base, DynamicMinimapSetting settings) {
        try {
            BufferedImage hud = base.hud();
            int width = 360;
            int height = 286;
            int inset = 8;
            int widgetExtent = 154;
            double widgetScale = widgetExtent / (double) base.scaleReference();
            int widgetWidth = Math.max(1, (int) Math.round(hud.getWidth() * widgetScale));
            int widgetHeight = Math.max(1, (int) Math.round(hud.getHeight() * widgetScale));
            int x = inset + (int) Math.round((width - inset * 2 - widgetWidth)
                    * Math.max(0, Math.min(100, settings.hudPositionX)) / 100.0D);
            int y = inset + (int) Math.round((height - inset * 2 - widgetHeight)
                    * Math.max(0, Math.min(100, settings.hudPositionY)) / 100.0D);

            BufferedImage screen = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = screen.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(10, 11, 24, 222));
            g.fillRoundRect(1, 1, width - 2, height - 2, 18, 18);
            g.setColor(new Color(110, 101, 151, 92));
            g.setStroke(new BasicStroke(1.2F));
            g.drawRoundRect(1, 1, width - 3, height - 3, 18, 18);
            g.setColor(new Color(90, 219, 211, 30));
            g.drawLine(width / 2, 12, width / 2, height - 12);
            g.drawLine(12, height / 2, width - 12, height / 2);
            g.drawImage(hud, x, y, widgetWidth, widgetHeight, null);
            g.dispose();
            return toPng(screen);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void drawPreviewAsset(Graphics2D g, byte[] png, double x, double y, int size) throws IOException {
        if (png == null) return;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image != null) g.drawImage(image, (int)Math.round(x), (int)Math.round(y), size, size, null);
    }

    private static void drawTelemetry(Graphics2D graphics, DynamicMinimapSetting settings,
                                      int x, int y, int width, String coords,
                                      String compass, String clock, String biome) {
        int cursor = y + 6;
        int brandHeight = 24;
        int rowHeight = 43;
        int baseInset = 8;
        int brandLeftInset = 8;
        int brandRightInset = 8;
        if (settings.isCircle() && settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT) brandLeftInset += 6;
        if (settings.isCircle() && settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT) brandRightInset += 6;
        drawAlignedHudText(graphics, settings, "DapperMap", x, width, brandLeftInset, brandRightInset,
                cursor + 17, 10, settings.colours.awt(BRAND_TEXT, 255), true, 0.035F);
        cursor += brandHeight;

        if (settings.showCoordinates) {
            drawTelemetryRow(graphics, settings, x, width, cursor, baseInset, 12,
                    "COORDINATES", coords, settings.colours.awt(COORDS_TEXT, 255));
            cursor += rowHeight;
        }
        if (settings.showCompass) {
            drawTelemetryRow(graphics, settings, x, width, cursor, baseInset, 22,
                    "BEARING", compass, settings.colours.awt(COMPASS_TEXT, 255));
            cursor += rowHeight;
        }
        if (settings.showClock) {
            drawTelemetryRow(graphics, settings, x, width, cursor, baseInset, 22,
                    "WORLD TIME", clock, settings.colours.awt(CLOCK_TEXT, 255));
            cursor += rowHeight;
        }
        if (settings.showBiome) {
            drawTelemetryRow(graphics, settings, x, width, cursor, baseInset, 12,
                    "REGION", biome, settings.colours.awt(BIOME_TEXT, 255));
        }
    }

    /** Draws a faint cyan accent across the complete information-panel edge nearest the minimap. */
    private static void drawMapFacingPanelEdge(Graphics2D graphics, DynamicMinimapSetting settings,
                                               int x, int y, int width, int height) {
        graphics.setStroke(new BasicStroke(1.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(settings.colours.awt(TICKS, 105));

        double x0 = x + 1.0D;
        double y0 = y + 1.0D;
        double x1 = x + width - 1.0D;
        double y1 = y + height - 1.0D;
        double cx = (x0 + x1) / 2.0D;
        double cy = (y0 + y1) / 2.0D;
        double corner = 10.0D;
        double inset = Math.min(10.0D, Math.min(width, height) * 0.07D);
        double spread = Math.min(60.0D,
                (settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                        || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT
                        ? height : width) * 0.34D);

        // Non-circular styles keep a straight map-facing edge, now spanning the full side.
        if (!settings.isCircle()) {
            switch (settings.infoPanelPosition) {
                case DynamicMinimapSetting.INFO_LEFT ->
                        graphics.drawLine(x + width - 1, y + 1, x + width - 1, y + height - 1);
                case DynamicMinimapSetting.INFO_RIGHT ->
                        graphics.drawLine(x + 1, y + 1, x + 1, y + height - 1);
                case DynamicMinimapSetting.INFO_ABOVE ->
                        graphics.drawLine(x + 1, y + height - 1, x + width - 1, y + height - 1);
                case DynamicMinimapSetting.INFO_BELOW ->
                        graphics.drawLine(x + 1, y + 1, x + width - 1, y + 1);
                default -> { }
            }
            return;
        }

        // Circular styles trace the complete map-facing side of the glass shell: straight
        // sections at the ends plus the shallow inward curve around the minimap.
        Path2D.Double edge = new Path2D.Double();
        switch (settings.infoPanelPosition) {
            case DynamicMinimapSetting.INFO_RIGHT -> {
                edge.moveTo(x0, y0 + corner);
                edge.lineTo(x0, cy - spread);
                edge.curveTo(x0 + inset * 0.35D, cy - spread * 0.55D,
                        x0 + inset, cy - spread * 0.35D, x0 + inset, cy);
                edge.curveTo(x0 + inset, cy + spread * 0.35D,
                        x0 + inset * 0.35D, cy + spread * 0.55D, x0, cy + spread);
                edge.lineTo(x0, y1 - corner);
            }
            case DynamicMinimapSetting.INFO_LEFT -> {
                edge.moveTo(x1, y0 + corner);
                edge.lineTo(x1, cy - spread);
                edge.curveTo(x1 - inset * 0.35D, cy - spread * 0.55D,
                        x1 - inset, cy - spread * 0.35D, x1 - inset, cy);
                edge.curveTo(x1 - inset, cy + spread * 0.35D,
                        x1 - inset * 0.35D, cy + spread * 0.55D, x1, cy + spread);
                edge.lineTo(x1, y1 - corner);
            }
            case DynamicMinimapSetting.INFO_BELOW -> {
                edge.moveTo(x0 + corner, y0);
                edge.lineTo(cx - spread, y0);
                edge.curveTo(cx - spread * 0.55D, y0 + inset * 0.35D,
                        cx - spread * 0.35D, y0 + inset, cx, y0 + inset);
                edge.curveTo(cx + spread * 0.35D, y0 + inset,
                        cx + spread * 0.55D, y0 + inset * 0.35D, cx + spread, y0);
                edge.lineTo(x1 - corner, y0);
            }
            case DynamicMinimapSetting.INFO_ABOVE -> {
                edge.moveTo(x0 + corner, y1);
                edge.lineTo(cx - spread, y1);
                edge.curveTo(cx - spread * 0.55D, y1 - inset * 0.35D,
                        cx - spread * 0.35D, y1 - inset, cx, y1 - inset);
                edge.curveTo(cx + spread * 0.35D, y1 - inset,
                        cx + spread * 0.55D, y1 - inset * 0.35D, cx + spread, y1);
                edge.lineTo(x1 - corner, y1);
            }
            default -> { return; }
        }
        graphics.draw(edge);
    }

    private static void drawTelemetryRow(Graphics2D graphics, DynamicMinimapSetting settings,
                                         int x, int width, int y, int baseInset,
                                         int curveInset, String caption, String value,
                                         Color valueColor) {
        boolean curvedSide = settings.isCircle()
                && (settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT);
        int leftInset = baseInset;
        int rightInset = baseInset;
        // The extra inset belongs only to the edge facing the circular minimap. Keeping
        // the outer edge compact preserves the rounded glass silhouette for combinations
        // such as LEFT panel + RIGHT-aligned text.
        if (curvedSide && settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT) {
            leftInset += curveInset;
        } else if (curvedSide && settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT) {
            rightInset += curveInset;
        }
        drawAlignedHudText(graphics, settings, caption, x, width, leftInset, rightInset, y + 14, 9,
                settings.colours.awt(INFO_HEADERS, 255), false, 0.105F);
        drawAlignedHudText(graphics, settings, value == null ? "" : value, x, width,
                leftInset, rightInset, y + 32, 11, valueColor, false, 0.015F);
    }

    private static void drawAlignedHudText(Graphics2D graphics, DynamicMinimapSetting settings,
                                           String value, int panelX, int panelWidth,
                                           int leftInset, int rightInset, int baseline,
                                           int size, Color color, boolean bold, float tracking) {
        graphics.setFont(hudFont(size, bold, tracking));
        int textWidth = graphics.getFontMetrics().stringWidth(value);
        int left = panelX + leftInset;
        int right = panelX + panelWidth - rightInset;
        int textX = switch (settings.infoPanelTextAlignment) {
            case DynamicMinimapSetting.INFO_ALIGN_CENTER -> left + Math.max(0, (right - left - textWidth) / 2);
            case DynamicMinimapSetting.INFO_ALIGN_RIGHT -> Math.max(left, right - textWidth);
            default -> left;
        };
        drawHudText(graphics, value, textX, baseline, size, color, bold, tracking);
    }

    private static void drawHudText(Graphics2D graphics, String value, int x, int baseline,
                                    int size, Color color, boolean bold, float tracking) {
        graphics.setFont(hudFont(size, bold, tracking));
        graphics.setColor(new Color(0, 0, 0, 105));
        graphics.drawString(value, x + 1, baseline + 1);
        graphics.setColor(color);
        graphics.drawString(value, x, baseline);
    }

    private static Path2D.Double panelShellPath(DynamicMinimapSetting settings,
                                                int panelX, int panelY,
                                                int panelWidth, int panelHeight) {
        Path2D.Double path = new Path2D.Double();
        if (!settings.isCircle()) {
            path.append(new RoundRectangle2D.Double(panelX + 1, panelY + 1,
                    panelWidth - 2, panelHeight - 2, 12, 12), false);
            return path;
        }

        double x0 = panelX + 1.0D;
        double y0 = panelY + 1.0D;
        double x1 = panelX + panelWidth - 1.0D;
        double y1 = panelY + panelHeight - 1.0D;
        double cx = (x0 + x1) / 2.0D;
        double cy = (y0 + y1) / 2.0D;
        double corner = 10.0D;
        double inset = Math.min(10.0D, Math.min(panelWidth, panelHeight) * 0.07D);
        double spread = Math.min(60.0D, (settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT
                ? panelHeight : panelWidth) * 0.34D);

        if (settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT) {
            path.moveTo(x0 + corner, y0);
            path.lineTo(x1 - corner, y0); path.quadTo(x1, y0, x1, y0 + corner);
            path.lineTo(x1, y1 - corner); path.quadTo(x1, y1, x1 - corner, y1);
            path.lineTo(x0 + corner, y1); path.quadTo(x0, y1, x0, y1 - corner);
            path.lineTo(x0, cy + spread);
            path.curveTo(x0 + inset * 0.35D, cy + spread * 0.55D,
                    x0 + inset, cy + spread * 0.35D, x0 + inset, cy);
            path.curveTo(x0 + inset, cy - spread * 0.35D,
                    x0 + inset * 0.35D, cy - spread * 0.55D, x0, cy - spread);
            path.lineTo(x0, y0 + corner); path.quadTo(x0, y0, x0 + corner, y0);
        } else if (settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT) {
            path.moveTo(x0 + corner, y0);
            path.lineTo(x1 - corner, y0); path.quadTo(x1, y0, x1, y0 + corner);
            path.lineTo(x1, cy - spread);
            path.curveTo(x1 - inset * 0.35D, cy - spread * 0.55D,
                    x1 - inset, cy - spread * 0.35D, x1 - inset, cy);
            path.curveTo(x1 - inset, cy + spread * 0.35D,
                    x1 - inset * 0.35D, cy + spread * 0.55D, x1, cy + spread);
            path.lineTo(x1, y1 - corner); path.quadTo(x1, y1, x1 - corner, y1);
            path.lineTo(x0 + corner, y1); path.quadTo(x0, y1, x0, y1 - corner);
            path.lineTo(x0, y0 + corner); path.quadTo(x0, y0, x0 + corner, y0);
        } else if (settings.infoPanelPosition == DynamicMinimapSetting.INFO_BELOW) {
            path.moveTo(x0 + corner, y0);
            path.lineTo(cx - spread, y0);
            path.curveTo(cx - spread * 0.55D, y0 + inset * 0.35D,
                    cx - spread * 0.35D, y0 + inset, cx, y0 + inset);
            path.curveTo(cx + spread * 0.35D, y0 + inset,
                    cx + spread * 0.55D, y0 + inset * 0.35D, cx + spread, y0);
            path.lineTo(x1 - corner, y0); path.quadTo(x1, y0, x1, y0 + corner);
            path.lineTo(x1, y1 - corner); path.quadTo(x1, y1, x1 - corner, y1);
            path.lineTo(x0 + corner, y1); path.quadTo(x0, y1, x0, y1 - corner);
            path.lineTo(x0, y0 + corner); path.quadTo(x0, y0, x0 + corner, y0);
        } else {
            path.moveTo(x0 + corner, y0);
            path.lineTo(x1 - corner, y0); path.quadTo(x1, y0, x1, y0 + corner);
            path.lineTo(x1, y1 - corner); path.quadTo(x1, y1, x1 - corner, y1);
            path.lineTo(cx + spread, y1);
            path.curveTo(cx + spread * 0.55D, y1 - inset * 0.35D,
                    cx + spread * 0.35D, y1 - inset, cx, y1 - inset);
            path.curveTo(cx - spread * 0.35D, y1 - inset,
                    cx - spread * 0.55D, y1 - inset * 0.35D, cx - spread, y1);
            path.lineTo(x0 + corner, y1); path.quadTo(x0, y1, x0, y1 - corner);
            path.lineTo(x0, y0 + corner); path.quadTo(x0, y0, x0 + corner, y0);
        }
        path.closePath();
        return path;
    }

    /** Dot PNGs by colour: identical for every player, so each colour is drawn and encoded once. */
    private static final PngCache DOT_CACHE = new PngCache(64, 512 * 1024);

    public static byte[] renderDot(int rgb, DynamicMinimapColours colours) {
        byte[] cached = DOT_CACHE.get(rgb);
        if (cached != null) return cached;
        byte[] png = drawDot(rgb);
        DOT_CACHE.put(rgb, png);
        return png;
    }

    private static byte[] drawDot(int rgb) {
        BufferedImage image = new BufferedImage(32, 32, 2);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int center = 16;
        int radius = 12;
        graphics.setColor(withAlpha(FIXED_MARKER_OUTLINE, 200));
        graphics.fillOval(center - radius - 2, center - radius - 2, (radius + 2) * 2, (radius + 2) * 2);
        graphics.setColor(new Color(-16777216 | rgb, true));
        graphics.fillOval(center - radius, center - radius, radius * 2, radius * 2);
        graphics.dispose();
        return toPng(image);
    }

    public static byte[] renderSelfArrow(double angle, DynamicMinimapColours colours) {
        ArrowKey key = new ArrowKey(Double.doubleToLongBits(angle), colours.rgb(DynamicMinimapColours.Key.SELF));
        byte[] cached = ARROW_CACHE.get(key);
        if (cached != null) return cached;
        BufferedImage image = new BufferedImage(40, 40, 2);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double radius = 12.8;
        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo((double)0.0F, -radius);
        arrow.lineTo(radius * 0.72, radius);
        arrow.lineTo((double)0.0F, radius * 0.35);
        arrow.lineTo(-radius * 0.72, radius);
        arrow.closePath();
        AffineTransform previous = graphics.getTransform();
        graphics.translate(20, 20);
        if (!Double.isNaN(angle)) {
            graphics.rotate(angle);
        }

        graphics.setColor(colours.awt(DynamicMinimapColours.Key.SELF, 255));
        graphics.fill(arrow);
        graphics.setColor(withAlpha(FIXED_MARKER_OUTLINE, 220));
        graphics.setStroke(new BasicStroke(1.5F));
        graphics.draw(arrow);
        graphics.setTransform(previous);
        graphics.dispose();
        byte[] png = toPng(image);
        if (png != null) {
            ARROW_CACHE.put(key, png);
        }
        return png;
    }

    /** Locator headings: 5° steps are visually smooth at HUD arrow sizes (18–36 px). */
    public static final int SELF_ARROW_BUCKETS = 72;
    /** Complete locator image sets keyed by locator RGB; shared by every player using that colour. */
    private static final Map<Integer, byte[][]> ARROW_SETS = new ConcurrentHashMap<>();

    /** Non-blocking lookup of a ready locator set, or null if it has not been rendered yet. */
    public static byte[][] cachedSelfArrows(DynamicMinimapColours colours) {
        return ARROW_SETS.get(colours.rgb(DynamicMinimapColours.Key.SELF));
    }

    /** Renders (once per colour) every locator heading. Render pool only. */
    public static byte[][] selfArrows(DynamicMinimapColours colours) {
        int rgb = colours.rgb(DynamicMinimapColours.Key.SELF);
        byte[][] ready = ARROW_SETS.get(rgb);
        if (ready != null) return ready;
        byte[][] set = new byte[SELF_ARROW_BUCKETS][];
        for (int bucket = 0; bucket < SELF_ARROW_BUCKETS; bucket++) {
            set[bucket] = renderSelfArrow(selfArrowAngle(bucket), colours);
            if (set[bucket] == null) return null;
        }
        if (ARROW_SETS.size() >= 16) ARROW_SETS.clear(); // Bounded: ~60 KB per colour.
        byte[][] raced = ARROW_SETS.putIfAbsent(rgb, set);
        return raced != null ? raced : set;
    }

    /** Locator bucket for a player yaw in north-up mode. */
    public static int selfArrowBucket(double yaw) {
        double turns = screenAngleForYaw(yaw) / (Math.PI * 2.0D);
        return Math.floorMod((int)Math.round(turns * SELF_ARROW_BUCKETS), SELF_ARROW_BUCKETS);
    }

    public static double selfArrowAngle(int bucket) {
        return Math.floorMod(bucket, SELF_ARROW_BUCKETS) * (Math.PI * 2.0D) / SELF_ARROW_BUCKETS;
    }

    public static byte[] renderBlank() {
        return BLANK_PNG;
    }

    /**
     * Map rotation that makes the player's current heading screen-up.
     * It deliberately reuses the proven self-arrow yaw convention.
     */
    public static double mapRotationForYaw(double yaw) {
        return Double.isNaN(yaw) ? 0.0D : -screenAngleForYaw(yaw);
    }

    /** Player-up maps turn in steps of this many degrees (72 headings). */
    public static final int PLAYER_UP_STEP_DEGREES = 5;

    /**
     * Player-up map bucket (degrees, 0..359) for a yaw, snapped to {@link #PLAYER_UP_STEP_DEGREES}.
     * Fewer distinct headings mean fewer rotation renders, and repeated headings reuse images
     * the client already has (terrain slots, and one compass frame per heading).
     */
    public static int rotationBucketForYaw(double yaw) {
        int bucket = rotationBucket(mapRotationForYaw(yaw));
        int step = PLAYER_UP_STEP_DEGREES;
        return Math.floorMod(Math.round(bucket / (float)step) * step, ROTATION_BUCKETS);
    }

    public static int rotationBucket(double angle) {
        if (Double.isNaN(angle)) {
            return 0;
        }

        double turns = angle / (Math.PI * 2.0D);
        int bucket = (int)Math.round(turns * ROTATION_BUCKETS);
        return Math.floorMod(bucket, ROTATION_BUCKETS);
    }

    public static double bucketAngle(int bucket) {
        int normalized = Math.floorMod(bucket, ROTATION_BUCKETS);
        return (double)normalized * (Math.PI * 2.0D) / (double)ROTATION_BUCKETS;
    }

    /**
     * Share of the pan margin kept around the viewport in player-up frames. Player-up maps
     * re-centre once the player is 0.35 of the margin away on either axis (up to 0.495 after
     * rotation, diagonally); 0.65 leaves the rest as slack for movement while the new terrain
     * is rendered. The other 0.35 of the margin was sent with every frame but never shown.
     */
    private static final double ROTATION_CROP_MARGIN = 0.65D;

    /**
     * Logical side length of the terrain image in player-up mode: the viewport plus the part of
     * the pan margin that can come into view before the map re-centres (even, at most the
     * full buffer). North-up maps keep the full buffer.
     */
    public static int rotationCropPixels(DynamicMinimapSetting settings) {
        int buffer = bufferPixels(settings);
        int crop = settings.sizePixels + 2 * (int)Math.ceil(panMarginPixels(settings) * ROTATION_CROP_MARGIN);
        crop += crop & 1;
        return Math.min(buffer, crop);
    }

    /** Logical side length of the terrain image the HUD shows for these settings. */
    public static int terrainImagePixels(DynamicMinimapSetting settings) {
        return settings.rotationMode == DynamicMinimapSetting.ROTATION_PLAYER_UP
                ? rotationCropPixels(settings) : bufferPixels(settings);
    }

    /**
     * One immutable decoded source per accepted terrain. It also remembers the most recent
     * rotated frames of this terrain (encoded and hashed), so turning back to a recent
     * heading skips the rotate + PNG encode + hash work. The cache lives and dies with the
     * source, so a new terrain render can never reuse a stale frame.
     *
     * <p>Frames are cropped to {@link #rotationCropPixels} around the buffer centre. On circular
     * maps, pixels the round aperture can never show (whatever the pan offset before the map
     * re-centres) are filled with the flat background, which compresses to almost nothing.
     */
    public static final class RotationSource {
        private static final int FRAME_LIMIT = 12;
        private static final int FRAME_BYTE_LIMIT = 3 * 1024 * 1024;
        private final BufferedImage image;
        /** Side of the cropped frame in texels (even). */
        private final int cropTexels;
        /** Circular maps: aperture radius and largest pan offset covered, in texels; radius 0 = no fill. */
        private final double visibleRadius, panReach;
        /** North-up frame (bucket 0), encoded on first use. */
        private byte[] png;
        /** Colour reduction shared by every frame of this terrain (built on first use; null = none). */
        private ColourReduction reduction;
        private boolean reductionBuilt;
        private final java.util.LinkedHashMap<Integer, DynamicAssetPublisher.PreparedAsset> frames =
                new java.util.LinkedHashMap<>(16, 0.75F, true);
        private int frameBytes;

        private RotationSource(BufferedImage image, int cropTexels, double visibleRadius, double panReach) {
            this.image = image;
            this.cropTexels = cropTexels;
            this.visibleRadius = visibleRadius;
            this.panReach = panReach;
        }

        /**
         * One median-cut reduction of the whole source, reused by every rotated frame (bucket 0
         * included) instead of a new histogram per frame; colours also stay the same while turning.
         * Frames are nearest-pixel samples of the source, so every opaque frame colour is in it.
         * Translucent slots: the source's own plus one for the background drawn behind them.
         */
        private synchronized ColourReduction reduction() {
            if (!this.reductionBuilt) {
                this.reductionBuilt = true;
                int colours = terrainColours;
                this.reduction = colours > 0 ? buildReduction(argbPixels(this.image), colours, 1) : null;
            }
            return this.reduction;
        }

        /** North-up frame of this terrain. Render pool only. */
        synchronized byte[] png() {
            if (this.png == null) this.png = this.frame(0, false);
            return this.png;
        }

        /**
         * Cropped frame rotated to a heading bucket, PNG encoded (null on failure). A reduced
         * frame (sent mid-turn) is drawn at {@link #TURNING_SCALE} of the texels;
         * the HUD stretches it to the same logical size, so only its sharpness changes.
         */
        private byte[] frame(int bucket, boolean reduced) {
            int width = this.image.getWidth(), height = this.image.getHeight(), crop = this.cropTexels;
            int size = reduced ? Math.max(64, evenRound(crop * TURNING_SCALE)) : crop;
            BufferedImage rotated = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rotated.createGraphics();
            try {
                graphics.setColor(MAP_BACKGROUND_FILL);
                graphics.fillRect(0, 0, size, size);
                if (size != crop) graphics.scale(size / (double)crop, size / (double)crop);
                // Nearest-pixel sampling at either size: it keeps the map's own colours, so the
                // PNG stays palette-encoded (smoothing adds colours and made frames bigger).
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                // Shift by the (integer) crop offset, then rotate about the source centre as the
                // full-buffer frames did: the result is exactly the centre of that frame.
                graphics.translate((crop - width) / 2, (crop - height) / 2);
                if (bucket != 0) graphics.rotate(bucketAngle(bucket), width / 2.0D, height / 2.0D);
                graphics.drawImage(this.image, 0, 0, (ImageObserver)null);
            } finally {
                graphics.dispose();
            }
            if (this.visibleRadius > 0) flattenHiddenCorners(rotated, size / (double)crop);
            return terrainPng(rotated, this.reduction());
        }

        /**
         * Circular maps: fills every pixel no aperture position can reach with the background.
         * A pixel is reachable when it is within the aperture radius of some viewport centre in
         * the square of pan offsets (+-panReach) around the frame centre.
         */
        private void flattenHiddenCorners(BufferedImage frame, double scale) {
            int size = frame.getWidth();
            double radius = this.visibleRadius * scale, panReach = this.panReach * scale;
            double centre = size / 2.0D, radiusSquared = radius * radius;
            int fill = MAP_BACKGROUND_FILL.getRGB();
            int[] pixels = backingArgb(frame); // Written in place when available (always, for frames made here).
            int[] row = pixels != null ? null : new int[size];
            // Furthest any pixel in a row can be (horizontally) outside the pan square.
            double rowEdge = Math.max(0, centre - panReach);
            for (int y = 0; y < size; y++) {
                double outsideY = Math.max(0, Math.abs(y + 0.5D - centre) - panReach);
                if (rowEdge * rowEdge + outsideY * outsideY <= radiusSquared) continue; // Whole row reachable.
                if (pixels != null) {
                    int rowStart = y * size;
                    for (int x = 0; x < size; x++) {
                        double outsideX = Math.max(0, Math.abs(x + 0.5D - centre) - panReach);
                        if (outsideX * outsideX + outsideY * outsideY > radiusSquared) pixels[rowStart + x] = fill;
                    }
                    continue;
                }
                boolean changed = false;
                frame.getRGB(0, y, size, 1, row, 0, size);
                for (int x = 0; x < size; x++) {
                    double outsideX = Math.max(0, Math.abs(x + 0.5D - centre) - panReach);
                    if (outsideX * outsideX + outsideY * outsideY > radiusSquared && row[x] != fill) {
                        row[x] = fill;
                        changed = true;
                    }
                }
                if (changed) frame.setRGB(0, y, size, 1, row, 0, size);
            }
        }

        /** Previously rotated frame for a heading bucket, or null. Thread-safe. */
        public synchronized DynamicAssetPublisher.PreparedAsset cachedFrame(int bucket) {
            return this.frames.get(bucket);
        }

        /** Remembers a rotated frame; least recently used frames are evicted by count and bytes. */
        public synchronized void rememberFrame(int bucket, DynamicAssetPublisher.PreparedAsset frame) {
            if (frame == null) return;
            var old = this.frames.put(bucket, frame);
            this.frameBytes += frame.data().length - (old == null ? 0 : old.data().length);
            var iterator = this.frames.values().iterator();
            while (iterator.hasNext() && (this.frames.size() > FRAME_LIMIT || this.frameBytes > FRAME_BYTE_LIMIT)) {
                this.frameBytes -= iterator.next().data().length;
                iterator.remove();
            }
        }
    }

    /**
     * Rotation source straight from a stitched terrain image (no PNG encode/decode round trip).
     * Rotating uploads are capped at 512 square pixels without changing logical map coverage;
     * frames are cropped to {@link #rotationCropPixels} for these settings.
     */
    public static RotationSource prepareRotationSource(BufferedImage image, DynamicMinimapSetting settings) {
        if (image == null) return null;
        try {
            int largest = Math.max(image.getWidth(), image.getHeight());
            if (largest > 512) {
                double scale = 512.0 / largest;
                BufferedImage reduced = new BufferedImage(
                        Math.max(1, (int)Math.round(image.getWidth() * scale)),
                        Math.max(1, (int)Math.round(image.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = reduced.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    graphics.drawImage(image, 0, 0, reduced.getWidth(), reduced.getHeight(), null);
                } finally {
                    graphics.dispose();
                }
                image = reduced;
            }
            // Texels per logical pixel of this (square) terrain image.
            double texels = image.getWidth() / (double)bufferPixels(settings);
            int crop = (int)Math.round(rotationCropPixels(settings) * texels);
            crop = Math.max(2, Math.min(image.getWidth() - (image.getWidth() & 1), crop + (crop & 1)));
            double radius = 0, reach = 0;
            if (settings.isCircle()) {
                radius = settings.sizePixels / 2.0D * texels + 2.0D; // Aperture (inside the frame) plus a safety texel.
                reach = Math.max(0, (crop - settings.sizePixels * texels) / 2.0D);
            }
            return new RotationSource(image, crop, radius, reach);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Share of the texels used for reduced (mid-turn) player-up frames: about a third of the bytes. */
    public static final double TURNING_SCALE = 0.5D;

    /** Nearest even whole number (frames stay centred on whole texels). */
    private static int evenRound(double value) {
        int rounded = (int)Math.round(value);
        return rounded + (rounded & 1);
    }

    /** Cropped full-sharpness player-up frame for a map angle (bucket 0 is the cached north-up frame). */
    public static byte[] rotateTerrain(RotationSource base, double mapAngle) {
        return rotateTerrain(base, mapAngle, false);
    }

    /** Cropped player-up frame; {@code reduced} draws the lighter mid-turn version. */
    public static byte[] rotateTerrain(RotationSource base, double mapAngle, boolean reduced) {
        if (base == null || base.image == null) return null;
        int bucket = rotationBucket(mapAngle);
        if (bucket == 0 && !reduced) return base.png();
        try {
            return base.frame(bucket, reduced);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Rotates a logical world/map delta using the exact same transform as the
     * terrain bitmap.
     */
    public static double[] rotateMapDelta(double x, double z, double mapAngle) {
        int bucket = rotationBucket(mapAngle);
        if (bucket == 0) {
            return new double[]{x, z};
        }

        double angle = bucketAngle(bucket);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new double[]{
                x * cos - z * sin,
                x * sin + z * cos
        };
    }

    public static double screenAngleForYaw(double yaw) {
        if (Double.isNaN(yaw)) {
            return (double)0.0F;
        } else {
            double screenX = -Math.sin(yaw);
            double screenY = -Math.cos(yaw);
            return Math.atan2(screenY, screenX) + (Math.PI / 2D);
        }
    }

    /** Logo-style bevel and emissive trim; fixed bezel, independently moving compass. */
    private static void drawApertureCircleFrame(Graphics2D graphics, int size, DynamicMinimapColours colours) {
        drawTexturedBezel(graphics, size, true, colours);
    }

    private static void drawApertureSquareFrame(Graphics2D graphics, int size, DynamicMinimapColours colours) {
        drawTexturedBezel(graphics, size, false, colours);
    }

    private static void drawTexturedBezel(Graphics2D g, int size, boolean circle, DynamicMinimapColours colours) {
        Shape rim = circle ? new Ellipse2D.Double(11, 11, size - 22, size - 22)
                : new RoundRectangle2D.Double(11, 11, size - 22, size - 22, 15, 15);
        g.setStroke(new BasicStroke(18, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(5, 9, 20, 50));g.draw(rim);
        g.setStroke(new BasicStroke(14));g.setColor(new Color(17, 25, 43, 45));g.draw(rim);
        g.setStroke(new BasicStroke(10));g.setColor(new Color(8, 13, 28, 50));g.draw(rim);
        // Restore the full bright band's dark backing without making the inner bevel opaque.
        g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(5, 9, 20, 230));g.draw(rim);
        for (int width : new int[]{7, 4, 2}) {
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(colours.awt(EDGE, width == 2 ? 255 : width == 4 ? 110 : 30));g.draw(rim);
        }
    }

    private static void drawTexturedCompass(Graphics2D g, int size, boolean circle, double angle, DynamicMinimapColours colours) {
        double center = size / 2.0D;
        // Minor divisions sit on the inner rim; major bars project beyond the outer hairline.
        int divisions = circle ? 32 : 24;
        int cardinalStep = divisions / 4, diagonalStep = divisions / 8;
        for (int i = 0; i < divisions; i++) {
            if(i % cardinalStep == 0)continue;
            boolean diagonal = i % diagonalStep == 0;
            double bearing;
            if (circle) bearing = -Math.PI / 2 + i * Math.PI / 16 + angle;
            else {
                // Match the artwork's two evenly spaced small ticks per half-side.
                double[][] points = SQUARE_COMPASS_POINTS;
                int segment = i / 3;
                double fraction = (i % 3) / 3.0D;
                double x = points[segment][0] + (points[segment+1][0]-points[segment][0])*fraction;
                double y = points[segment][1] + (points[segment+1][1]-points[segment][1])*fraction;
                bearing = Math.atan2(y,x) + angle;
            }
            double divisor = circle ? 1 : Math.max(Math.abs(Math.cos(bearing)), Math.abs(Math.sin(bearing)));
            double outer = (center - (diagonal ? 13 : 18)) / divisor;
            double inner = (center - (diagonal ? 24 : 22)) / divisor;
            float width = diagonal ? 2.8F : 1.8F;
            drawRadialTick(g, (int)center, (int)center, outer, inner, bearing, width + 2, colours.awt(TICKS, 40));
            drawRadialTick(g, (int)center, (int)center, outer, inner, bearing, width, colours.awt(TICKS, 245));
        }
        g.setFont(CARDINAL_FONT);
        String[] labels = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            double bearing = -Math.PI / 2 + i * Math.PI / 2 + angle;
            double dx = Math.cos(bearing), dy = Math.sin(bearing);
            double divisor = circle ? 1 : Math.max(Math.abs(dx), Math.abs(dy));
            double radius = (center - 27) / divisor;
            int x = (int)Math.round(center + dx * radius), y = (int)Math.round(center + dy * radius);
            // Badge joins the bezel and cuts an inward recess around an upright letter.
            Shape badge = new RoundRectangle2D.Double(x - 11, y - 11, 22, 22, 6, 6);
            double join = (center - 10) / divisor;
            g.setStroke(new BasicStroke(20, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(5, 10, 23));
            g.draw(new java.awt.geom.Line2D.Double(x, y, center + dx * join, center + dy * join));
            g.fill(badge);
            g.setStroke(new BasicStroke(.6F));g.setColor(colours.awt(TICKS, 65));g.draw(badge);
            drawCardinal(g, labels[i], x, y, colours);
            drawRadialTick(g, (int)center, (int)center, (center - 1.8) / divisor,
                    (center - 12) / divisor, bearing, 6.5F, new Color(3, 9, 20));
            drawRadialTick(g, (int)center, (int)center, (center - 2.5) / divisor,
                    (center - 11.5) / divisor, bearing, 3.6F, colours.awt(TICKS, 255));
            drawRadialTick(g, (int)center, (int)center, (center - 3) / divisor,
                    (center - 11) / divisor, bearing, 1.0F, new Color(235, 255, 255, 180));
        }
    }

    private static void drawRadialTick(Graphics2D graphics, int centerX, int centerY, double outerRadius, double innerRadius, double angle, float width, Color color) {
        int x1 = (int)Math.round((double)centerX + Math.cos(angle) * outerRadius);
        int y1 = (int)Math.round((double)centerY + Math.sin(angle) * outerRadius);
        int x2 = (int)Math.round((double)centerX + Math.cos(angle) * innerRadius);
        int y2 = (int)Math.round((double)centerY + Math.sin(angle) * innerRadius);
        graphics.setStroke(new BasicStroke(width, 1, 1));
        graphics.setColor(color);
        graphics.drawLine(x1, y1, x2, y2);
    }

    private static void drawCardinal(Graphics2D graphics, String label, int centerX, int centerY, DynamicMinimapColours colours) {
        FontMetrics metrics = graphics.getFontMetrics();
        int left = centerX - metrics.stringWidth(label) / 2;
        int baseline = centerY + metrics.getAscent() / 2 - 1;
        graphics.setColor(new Color(0, 0, 0, 170));
        graphics.drawString(label, left + 1, baseline + 1);
        graphics.setColor(colours.awt(CARDINAL_TEXT, 255));
        graphics.drawString(label, left, baseline);
    }

    /** {@link #toPng} for terrain images, also counted for /dappermap admin stats (palette or true colour). */
    private static byte[] terrainPng(BufferedImage image) {
        return terrainPng(image, null);
    }

    /**
     * Terrain PNG: exact palette when the image has at most 256 colours, otherwise colour-reduced
     * (through {@code shared} when given, else a reduction of this image alone), otherwise full colour.
     */
    private static byte[] terrainPng(BufferedImage image, ColourReduction shared) {
        // 0 = exact palette, 1 = colour-reduced, 2 = full colour (for /dappermap admin stats).
        int kind = 0;
        int[] pixels = argbPixels(image);
        BufferedImage encoded = paletteImage(image, pixels);
        if (encoded == image) {
            BufferedImage reduced = null;
            if (terrainColours > 0) {
                if (shared != null) reduced = shared.apply(pixels, image.getWidth(), image.getHeight());
                if (reduced == null) reduced = reduceColours(image, pixels, terrainColours);
            }
            kind = reduced != null ? 1 : 2;
            if (reduced != null) encoded = reduced;
        }
        byte[] png = DapperMapUtil.encodePng(encoded, Math.min(32768, image.getWidth() * image.getHeight()));
        if (png != null) DapperMapServerLoad.recordTerrainImage(png.length, kind);
        return png;
    }

    /** Colour limit for full-colour terrain images (0 = off); set from the config. */
    private static volatile int terrainColours = 256;

    /** Sets the terrain colour limit: 0 keeps full colour, otherwise 32..256 colours. */
    public static void setTerrainColours(int colours) {
        terrainColours = colours <= 0 ? 0 : Math.max(32, Math.min(256, colours));
    }

    /** Most translucent colours kept exact (the map background and its rotated corners). */
    private static final int MAX_TRANSLUCENT = 16;

    /**
     * Backing pixel array of a plain TYPE_INT_ARGB image (one bank, no offsets, not a sub-image),
     * else null. Its values are exactly what getRGB returns (non-premultiplied ARGB).
     */
    private static int[] backingArgb(BufferedImage image) {
        if (image.getType() != BufferedImage.TYPE_INT_ARGB) return null;
        java.awt.image.WritableRaster raster = image.getRaster();
        if (raster.getMinX() != 0 || raster.getMinY() != 0
                || raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0) return null;
        if (!(raster.getDataBuffer() instanceof java.awt.image.DataBufferInt buffer)
                || buffer.getNumBanks() != 1 || buffer.getOffset() != 0) return null;
        if (!(raster.getSampleModel() instanceof java.awt.image.SinglePixelPackedSampleModel model)
                || model.getScanlineStride() != image.getWidth()) return null;
        int[] data = buffer.getData();
        return data.length >= image.getWidth() * image.getHeight() ? data : null;
    }

    /** All pixels as ARGB in row order: the backing array when possible (treat as read-only), else a copy. */
    private static int[] argbPixels(BufferedImage image) {
        int[] backing = backingArgb(image);
        if (backing != null) return backing;
        int width = image.getWidth(), height = image.getHeight();
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    /** 5-bit-per-channel histogram bin of an opaque colour. */
    private static int colourBin(int argb) {
        return ((((argb >>> 16) & 0xFF) >>> 3) << 10) | ((((argb >>> 8) & 0xFF) >>> 3) << 5) | ((argb & 0xFF) >>> 3);
    }

    /**
     * A median-cut colour reduction: one colour per box of 5-bit histogram bins, plus room for
     * up to {@code translucentSlots} exact translucent colours. Immutable once built, so one
     * reduction can be shared by every rotated frame of a player-up terrain.
     */
    static final class ColourReduction {
        private final int[] boxColours;
        /** Box index per histogram bin; -1 for bins the reduction never saw. */
        private final short[] binToBox;
        private final int translucentSlots;

        private ColourReduction(int[] boxColours, short[] binToBox, int translucentSlots) {
            this.boxColours = boxColours;
            this.binToBox = binToBox;
            this.translucentSlots = translucentSlots;
        }

        /**
         * Indexed image of {@code argb}: opaque pixels by bin, translucent colours exact (in order of
         * appearance, after the box colours). Null when a pixel's bin is unknown to this reduction or
         * there are more translucent colours than slots; callers then fall back.
         */
        BufferedImage apply(int[] argb, int width, int height) {
            int boxes = this.boxColours.length;
            int[] translucent = new int[Math.max(1, this.translucentSlots)];
            int translucentCount = 0;
            byte[] pixels = new byte[width * height];
            for (int i = 0; i < pixels.length; i++) {
                int colour = argb[i];
                if ((colour >>> 24) == 255) {
                    short box = this.binToBox[colourBin(colour)];
                    if (box < 0) return null; // Colour the shared reduction never saw.
                    pixels[i] = (byte)box;
                } else {
                    int slot = indexOf(translucent, translucentCount, colour);
                    if (slot < 0) {
                        if (translucentCount == this.translucentSlots) return null;
                        slot = translucentCount;
                        translucent[translucentCount++] = colour;
                    }
                    pixels[i] = (byte)(boxes + slot);
                }
            }
            int[] palette = new int[boxes + translucentCount];
            System.arraycopy(this.boxColours, 0, palette, 0, boxes);
            System.arraycopy(translucent, 0, palette, boxes, translucentCount);
            var model = new java.awt.image.IndexColorModel(8, palette.length, palette, 0, true, -1,
                    java.awt.image.DataBuffer.TYPE_BYTE);
            // Wrap the index array directly (same layout as TYPE_BYTE_INDEXED, no copy).
            var raster = java.awt.image.Raster.createInterleavedRaster(
                    new java.awt.image.DataBufferByte(pixels, pixels.length), width, height, width, 1, new int[]{0}, null);
            return new BufferedImage(model, raster, false, null);
        }
    }

    /**
     * Median cut over the opaque pixels of {@code argb}: each split goes across the widest channel
     * of the most populated box at the pixel-weighted median; each box becomes its pixel-weighted
     * average colour. {@code reservedTranslucent} palette entries are left for exact translucent
     * colours. Null when the pixels hold more than {@link #MAX_TRANSLUCENT} translucent colours.
     */
    static ColourReduction buildReduction(int[] argb, int colours, int extraTranslucent) {
        int[] count = new int[32768], sumR = new int[32768], sumG = new int[32768], sumB = new int[32768];
        int[] translucent = new int[MAX_TRANSLUCENT];
        int translucentCount = 0;
        // Histogram of opaque colours; translucent colours counted exactly.
        for (int colour : argb) {
            if ((colour >>> 24) == 255) {
                int bin = colourBin(colour);
                count[bin]++;
                sumR[bin] += (colour >>> 16) & 0xFF;
                sumG[bin] += (colour >>> 8) & 0xFF;
                sumB[bin] += colour & 0xFF;
            } else if (indexOf(translucent, translucentCount, colour) < 0) {
                if (translucentCount == MAX_TRANSLUCENT) return null;
                translucent[translucentCount++] = colour;
            }
        }
        int translucentSlots = Math.min(MAX_TRANSLUCENT, translucentCount + extraTranslucent);
        int occupied = 0;
        for (int c : count) if (c > 0) occupied++;
        int[] bins = new int[occupied];
        for (int bin = 0, i = 0; bin < count.length; bin++) if (count[bin] > 0) bins[i++] = bin;
        long opaquePixels = (long)argb.length;

        // Median cut: boxes are ranges of the bins array; split the most populated box until the budget is used.
        int budget = Math.max(1, colours - translucentSlots);
        int[] boxStart = new int[budget], boxEnd = new int[budget];
        long[] boxPixels = new long[budget];
        int boxes = 1;
        boxStart[0] = 0;
        boxEnd[0] = occupied;
        boxPixels[0] = opaquePixels;
        long[] sortKeys = new long[occupied];
        while (boxes < budget) {
            int pick = -1;
            for (int i = 0; i < boxes; i++)
                if (boxEnd[i] - boxStart[i] > 1 && (pick < 0 || boxPixels[i] > boxPixels[pick])) pick = i;
            if (pick < 0) break; // Every box is a single bin: the image fits the budget already.
            int start = boxStart[pick], end = boxEnd[pick];
            // Widest channel of this box (5-bit components of the bin index).
            int axis = widestChannel(bins, start, end);
            int shift = axis == 0 ? 10 : axis == 1 ? 5 : 0;
            for (int i = start; i < end; i++) sortKeys[i] = ((long)((bins[i] >>> shift) & 31) << 32) | bins[i];
            java.util.Arrays.sort(sortKeys, start, end);
            for (int i = start; i < end; i++) bins[i] = (int)sortKeys[i];
            // Split at the pixel-weighted median, keeping at least one bin on each side.
            long half = boxPixels[pick] / 2, running = 0;
            int split = start + 1;
            for (int i = start; i < end - 1; i++) {
                running += count[bins[i]];
                split = i + 1;
                if (running >= half) break;
            }
            long left = 0;
            for (int i = start; i < split; i++) left += count[bins[i]];
            boxEnd[pick] = split;
            boxPixels[pick] = left;
            boxStart[boxes] = split;
            boxEnd[boxes] = end;
            long right = 0;
            for (int i = split; i < end; i++) right += count[bins[i]];
            boxPixels[boxes] = right;
            boxes++;
        }

        // Each box's pixel-weighted average colour; bins map to their box (unseen bins stay -1).
        int[] boxColours = new int[boxes];
        short[] binToBox = new short[32768];
        java.util.Arrays.fill(binToBox, (short)-1);
        for (int box = 0; box < boxes; box++) {
            long r = 0, g = 0, b = 0, pixels = 0;
            for (int i = boxStart[box]; i < boxEnd[box]; i++) {
                int bin = bins[i];
                r += sumR[bin];
                g += sumG[bin];
                b += sumB[bin];
                pixels += count[bin];
                binToBox[bin] = (short)box;
            }
            pixels = Math.max(1, pixels);
            boxColours[box] = 0xFF000000 | (int)(r / pixels) << 16 | (int)(g / pixels) << 8 | (int)(b / pixels);
        }
        return new ColourReduction(boxColours, binToBox, translucentSlots);
    }

    /**
     * Reduces a full-colour terrain image to at most {@code colours} colours, or null when it
     * cannot (too many translucent colours). Opaque pixels are grouped on a 5-bit-per-channel
     * histogram and split by median cut; translucent colours are kept exact. No dithering: flat
     * areas stay flat, which keeps the PNG small. Render pool only.
     */
    static BufferedImage reduceColours(BufferedImage source, int colours) {
        return reduceColours(source, argbPixels(source), colours);
    }

    private static BufferedImage reduceColours(BufferedImage source, int[] argb, int colours) {
        ColourReduction reduction = buildReduction(argb, colours, 0);
        return reduction == null ? null : reduction.apply(argb, source.getWidth(), source.getHeight());
    }

    private static int indexOf(int[] values, int length, int value) {
        for (int i = 0; i < length; i++) if (values[i] == value) return i;
        return -1;
    }

    /** 0 = red, 1 = green, 2 = blue: the channel with the widest range among bins[start..end). */
    private static int widestChannel(int[] bins, int start, int end) {
        int minR = 31, maxR = 0, minG = 31, maxG = 0, minB = 31, maxB = 0;
        for (int i = start; i < end; i++) {
            int r = (bins[i] >>> 10) & 31, g = (bins[i] >>> 5) & 31, b = bins[i] & 31;
            minR = Math.min(minR, r); maxR = Math.max(maxR, r);
            minG = Math.min(minG, g); maxG = Math.max(maxG, g);
            minB = Math.min(minB, b); maxB = Math.max(maxB, b);
        }
        int red = maxR - minR, green = maxG - minG, blue = maxB - minB;
        return green >= red && green >= blue ? 1 : red >= blue ? 0 : 2;
    }

    /** Palette-optimised PNG (exact palette when the image has at most 256 colours). */
    private static byte[] toPng(BufferedImage image) {
        // Most map PNGs are much smaller than the raster: don't allocate width*height bytes up front.
        return DapperMapUtil.encodePng(paletteImage(image), Math.min(32768, image.getWidth() * image.getHeight()));
    }

    /** Exact palette encoding; images with more than 256 ARGB values stay true-colour. */
    private static BufferedImage paletteImage(BufferedImage source) {
        if ((long)source.getWidth() * source.getHeight() < 4096L) return source;
        return paletteImage(source, argbPixels(source));
    }

    /** {@link #paletteImage(BufferedImage)} with the source's ARGB pixels already in hand. */
    private static BufferedImage paletteImage(BufferedImage source, int[] argb) {
        if ((long)source.getWidth() * source.getHeight() < 4096L) return source;
        int[] keys = new int[512], values = new int[512], colours = new int[256];
        java.util.Arrays.fill(values, -1);
        int count = 0;
        for (int colour : argb) {
            int slot = (colour * 0x9E3779B9) >>> 23;
            while (values[slot] >= 0 && keys[slot] != colour) slot = (slot + 1) & 511;
            if (values[slot] < 0) {
                if (count == 256) return source;
                keys[slot] = colour;
                values[slot] = count;
                colours[count++] = colour;
            }
        }
        int bits = count <= 2 ? 1 : count <= 4 ? 2 : count <= 16 ? 4 : 8;
        var model = new java.awt.image.IndexColorModel(bits, count, colours, 0, true, -1,
                java.awt.image.DataBuffer.TYPE_BYTE);
        int width = source.getWidth();
        var indexed = new BufferedImage(width, source.getHeight(),
                bits == 8 ? BufferedImage.TYPE_BYTE_INDEXED : BufferedImage.TYPE_BYTE_BINARY, model);
        byte[] pixels = ((java.awt.image.DataBufferByte) indexed.getRaster().getDataBuffer()).getData();
        int stride = (width * bits + 7) / 8;
        for (int y = 0, offset = 0; y < source.getHeight(); ++y) {
            for (int x = 0; x < width; x++) {
                int colour = argb[y * width + x];
                int slot = (colour * 0x9E3779B9) >>> 23;
                while (keys[slot] != colour || values[slot] < 0) slot = (slot + 1) & 511;
                if (bits == 8) pixels[offset++] = (byte) values[slot];
                else pixels[y * stride + x * bits / 8] |= (byte) (values[slot] << (8 - bits - x * bits % 8));
            }
        }
        return indexed;
    }

    /**
     * Decodes a world-map chunk image into {@code target}, the backing array of {@code chunkImage}
     * (exactly width*height pixels, ARGB). Falls back to setRGB when no backing array is available.
     * False when the image cannot be decoded (the chunk is then left as background).
     */
    private static boolean decodePixels(MapImage image, int[] target, BufferedImage chunkImage) {
        int width = image.width;
        int height = image.height;
        int count = width * height;
        int[] palette = image.palette;
        if (palette == null || palette.length == 0) return false;
        byte[] packedIndices = image.packedIndices;
        int bitsPerIndex = Byte.toUnsignedInt(image.bitsPerIndex);
        boolean packed = packedIndices != null && packedIndices.length != 0 && bitsPerIndex > 0;
        if (!packed && palette.length != count) return false;
        int[] pixels = target != null ? target : new int[count];
        if (packed) {
            BitFieldArr bitField = new BitFieldArr(bitsPerIndex, count);
            bitField.set(packedIndices);
            for (int i = 0; i < count; ++i) {
                int paletteIndex = bitField.get(i);
                // Map images store RGBA; Java wants ARGB.
                pixels[i] = Integer.rotateRight(palette[paletteIndex >= 0 && paletteIndex < palette.length ? paletteIndex : 0], 8);
            }
        } else {
            for (int i = 0; i < count; ++i) pixels[i] = Integer.rotateRight(palette[i], 8);
        }
        if (target == null) chunkImage.setRGB(0, 0, width, height, pixels, 0, width);
        return true;
    }

    private static long sampleHash(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return 0L;
        int length = bytes.length;
        long hash = length;
        hash = 31L * hash + (long)Byte.toUnsignedInt(bytes[0]);
        hash = 31L * hash + (long)Byte.toUnsignedInt(bytes[length / 4]);
        hash = 31L * hash + (long)Byte.toUnsignedInt(bytes[length / 2]);
        hash = 31L * hash + (long)Byte.toUnsignedInt(bytes[length * 3 / 4]);
        hash = 31L * hash + (long)Byte.toUnsignedInt(bytes[length - 1]);
        return hash;
    }

    private static long sampleHash(int[] ints) {
        if (ints == null || ints.length == 0) return 0L;
        int length = ints.length;
        long hash = length;
        hash = 31L * hash + (long)ints[0];
        hash = 31L * hash + (long)ints[length / 4];
        hash = 31L * hash + (long)ints[length / 2];
        hash = 31L * hash + (long)ints[length * 3 / 4];
        hash = 31L * hash + (long)ints[length - 1];
        return hash;
    }

    public static final class Terrain {
        /** North-up PNG (null for player-up renders, which use {@link #image}). */
        public final byte[] png;
        /** The stitched raster, or null when no chunk was drawn. */
        public final BufferedImage image;
        public final int present;
        public final int total;
        /** Change signature of the chunk images drawn (same value terrainSignature gives). */
        public final long signature;

        Terrain(byte[] png, BufferedImage image, int present, int total, long signature) {
            this.png = png;
            this.image = image;
            this.present = present;
            this.total = total;
            this.signature = signature;
        }

        public boolean complete() {
            return this.total > 0 && this.present >= this.total;
        }
    }
    private static Color withAlpha(Color colour, int alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
    }

}

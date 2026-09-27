package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import java.awt.BasicStroke;
import java.awt.Color;
import static dev.scyphoz.dappermap.dynamic.MinimapColours.Key.*;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.Shape;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import javax.imageio.ImageIO;

/** Pure rendering utilities for terrain, HUD chrome, previews and marker PNG assets. */
public final class DynamicMinimapRenderer {
    // Fixed visual colours that are intentionally not part of the player palette.
    private static final Color FIXED_MAP_BACKGROUND = new Color(0x0A1218);
    private static final Color FIXED_FRAME_SHADOW = new Color(0x03080C);
    private static final Color FIXED_MARKER_OUTLINE = new Color(0x000000);

    private static final int CHUNK_SIZE = 32;
    /**
     * Static frame artwork is authored in this logical coordinate space and
     * stretched by Hytale to the selected HUD map size.
     */
    private static final int STATIC_ASSET_LOGICAL_SIZE = 220;
    public static final int ROTATION_BUCKETS = 360;
    private static final Font CARDINAL_FONT = new Font("SansSerif", 1, 15);
    private static final Map<String, byte[]> MASK_CACHE = new ConcurrentHashMap();
    private static final Map<String, byte[]> FRAME_CACHE = new ConcurrentHashMap();

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
        return Math.max(2, (pixels & 1) == 0 ? pixels : pixels + 1);
    }

    public static double bufferOriginWorld(double center, DynamicMinimapSetting settings) {
        return center - (double)bufferPixels(settings) / (double)2.0F / pixelsPerBlock(settings);
    }

    public static Terrain renderTerrain(WorldMapManager worldMapManager, double centerX, double centerZ, DynamicMinimapSetting settings) {
        return renderTerrain(worldMapManager, centerX, centerZ, settings, null);
    }

    public static Terrain renderTerrain(WorldMapManager worldMapManager, double centerX, double centerZ, DynamicMinimapSetting settings, BiFunction<Integer, Integer, MapImage> caveImages) {
        int logicalBuffer = bufferPixels(settings);
        int textureBuffer = textureBufferPixels(settings);
        double logicalPixelsPerBlock = pixelsPerBlock(settings);
        double rasterScale = (double)textureBuffer / (double)logicalBuffer;
        double texturePixelsPerBlock = logicalPixelsPerBlock * rasterScale;
        double originX = bufferOriginWorld(centerX, settings);
        double originZ = bufferOriginWorld(centerZ, settings);
        BufferedImage terrain = new BufferedImage(textureBuffer, textureBuffer, 2);
        Graphics2D graphics = terrain.createGraphics();
        graphics.setColor(withAlpha(FIXED_MAP_BACKGROUND, 235));
        graphics.fillRect(0, 0, textureBuffer, textureBuffer);
        int present = 0;
        int total = 0;
        if (worldMapManager != null || caveImages != null) {
            double worldSize = (double)logicalBuffer / logicalPixelsPerBlock;
            int minChunkX = ChunkUtil.chunkCoordinate((int)Math.floor(originX));
            int minChunkZ = ChunkUtil.chunkCoordinate((int)Math.floor(originZ));
            int maxChunkX = ChunkUtil.chunkCoordinate((int)Math.ceil(originX + worldSize));
            int maxChunkZ = ChunkUtil.chunkCoordinate((int)Math.ceil(originZ + worldSize));
            int chunkPixels = (int)Math.ceil((double)CHUNK_SIZE * texturePixelsPerBlock);

            for(int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                for(int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
                    ++total;

                    try {
                        MapImage image = caveImages != null ? caveImages.apply(chunkX, chunkZ)
                                : worldMapManager.getImageAsync(chunkX, chunkZ).getNow(null);
                        if (image != null && image.width > 0 && image.height > 0) {
                            int[] pixels = decodePixels(image);
                            if (pixels != null) {
                                BufferedImage chunk = new BufferedImage(image.width, image.height, 2);
                                chunk.setRGB(0, 0, image.width, image.height, pixels, 0, image.width);
                                int drawX = (int)Math.round(((double)chunkX * (double)CHUNK_SIZE - originX) * texturePixelsPerBlock);
                                int drawZ = (int)Math.round(((double)chunkZ * (double)CHUNK_SIZE - originZ) * texturePixelsPerBlock);
                                // Preserve detail when BetterMap supplies more samples per chunk.
                                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                        image.width > chunkPixels || image.height > chunkPixels
                                                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                                                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                                graphics.drawImage(chunk, drawX, drawZ, chunkPixels, chunkPixels, (ImageObserver)null);
                                ++present;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        graphics.dispose();
        return present == 0 ? new Terrain((byte[])null, 0, total) : new Terrain(toPng(terrain), present, total);
    }

    public static long terrainSignature(WorldMapManager worldMapManager, double centerX, double centerZ, DynamicMinimapSetting settings) {
        return terrainSignature(worldMapManager, centerX, centerZ, settings, null);
    }

    public static long terrainSignature(WorldMapManager worldMapManager, double centerX, double centerZ, DynamicMinimapSetting settings, BiFunction<Integer, Integer, MapImage> caveImages) {
        if (worldMapManager == null && caveImages == null) {
            return 0L;
        } else {
            double pixelsPerBlock = pixelsPerBlock(settings);
            int buffer = bufferPixels(settings);
            double originX = bufferOriginWorld(centerX, settings);
            double originZ = bufferOriginWorld(centerZ, settings);
            double worldSize = (double)buffer / pixelsPerBlock;
            int minChunkX = ChunkUtil.chunkCoordinate((int)Math.floor(originX));
            int minChunkZ = ChunkUtil.chunkCoordinate((int)Math.floor(originZ));
            int maxChunkX = ChunkUtil.chunkCoordinate((int)Math.ceil(originX + worldSize));
            int maxChunkZ = ChunkUtil.chunkCoordinate((int)Math.ceil(originZ + worldSize));
            long hash = 1469598103934665603L;

            for(int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                for(int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
                    long chunkHash = 1L;

                    try {
                        MapImage image = caveImages != null ? caveImages.apply(chunkX, chunkZ)
                                : worldMapManager.getImageIfInMemory(chunkX, chunkZ);
                        if (image != null) {
                            chunkHash = 31L * chunkHash + (long)image.width;
                            chunkHash = 31L * chunkHash + (long)image.height;
                            chunkHash = 31L * chunkHash + (long)Byte.toUnsignedInt(image.bitsPerIndex);
                            chunkHash = 31L * chunkHash + sampleHash(image.packedIndices);
                            chunkHash = 31L * chunkHash + sampleHash(image.palette);
                        }
                    } catch (Throwable var26) {
                    }

                    hash = (hash ^ chunkHash) * 1099511628211L;
                }
            }

            return hash;
        }
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

        if (settings.isCircle()) {
            graphics.fill(new Ellipse2D.Double(
                    0.0,
                    0.0,
                    textureSize,
                    textureSize
            ));
        } else {
            graphics.fillRect(0, 0, textureSize, textureSize);
        }

        graphics.dispose();
        byte[] png = toPng(image);
        if (png != null) {
            MASK_CACHE.put(key, png);
        }

        return png;
    }

    /**
     * Renders the frame with compass/cardinal markings rotated by mapAngle.
     * The geometric bezel remains fixed (especially important for square maps),
     * while the directional markings rotate with the terrain.
     */
    public static byte[] renderFrame(DynamicMinimapSetting settings, double mapAngle) {
        MinimapColours colours = settings.colours;
        int bucket = rotationBucket(mapAngle);
        String shape = settings.isCircle() ? "c" : "r";
        String key = shape + "@" + settings.textureResolution
                + "-rot" + bucket + "-" + colours.frameKey();
        byte[] cached = FRAME_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        int logicalSize = STATIC_ASSET_LOGICAL_SIZE;
        int textureSize = Math.max(1, settings.textureResolution);
        double scale = (double)textureSize / (double)logicalSize;

        BufferedImage image = new BufferedImage(textureSize, textureSize, 2);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );
        graphics.scale(scale, scale);

        if (settings.isCircle()) {
            drawApertureCircleFrame(graphics, logicalSize, colours);
        } else {
            drawApertureSquareFrame(graphics, logicalSize, colours);
        }

        if (settings.isCircle()) {
            AffineTransform beforeCompass = graphics.getTransform();
            double center = (double)logicalSize / 2.0D;
            graphics.rotate(bucketAngle(bucket), center, center);
            drawCardinals(graphics, logicalSize, true, colours);
            graphics.setTransform(beforeCompass);
        } else {
            drawSquareCardinalsRotated(
                    graphics,
                    logicalSize,
                    bucketAngle(bucket)
                    , colours);
        }
        graphics.dispose();

        byte[] png = toPng(image);
        if (png != null) {
            if (FRAME_CACHE.size() >= 256) FRAME_CACHE.clear();
            FRAME_CACHE.put(key, png);
        }

        return png;
    }

    /**
     * Renders the shared instrument body behind the map and telemetry content.
     * Terrain and marker assets remain separate; this image supplies only the
     * contoured telemetry shell and the independent shape-aware divider.
     */
    public static byte[] renderHudChrome(DynamicMinimapSetting settings, int mapSize,
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
        return toPng(image);
    }

    /**
     * Renders every foreground HUD element into one coordinate space. The live
     * terrain, markers, and player arrow remain independent layers underneath
     * this overlay, while the frame, divider, panel, and telemetry can no longer
     * drift apart through separate UI layout/scaling rules.
     */
    public static byte[] renderHudOverlay(DynamicMinimapSetting settings, int mapSize,
                                          int sidePanelWidth, int panelHeight, int gap,
                                          double frameAngle, String coords, String compass,
                                          String clock, String biome) {
        if (!settings.infoPanelEnabled) {
            try {
                BufferedImage overlay = new BufferedImage(mapSize, mapSize, BufferedImage.TYPE_INT_ARGB);
                byte[] framePng = renderFrame(settings, frameAngle);
                if (framePng != null) {
                    BufferedImage frame = ImageIO.read(new ByteArrayInputStream(framePng));
                    Graphics2D g = overlay.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(frame, 0, 0, mapSize, mapSize, null);
                    g.dispose();
                }
                return toPng(overlay);
            } catch (Exception ignored) { return null; }
        }
        byte[] chromePng = renderHudChrome(settings, mapSize, sidePanelWidth, panelHeight, gap);
        if (chromePng == null) return null;

        try {
            BufferedImage overlay = ImageIO.read(new ByteArrayInputStream(chromePng));
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

            byte[] framePng = renderFrame(settings, frameAngle);
            BufferedImage frame = framePng == null ? null
                    : ImageIO.read(new ByteArrayInputStream(framePng));
            if (frame != null) {
                graphics.drawImage(frame, mapX, mapY, mapSize, mapSize, null);
            }

            drawTelemetry(graphics, settings, panelX, panelY, panelWidth,
                    coords, compass, clock, biome);
            graphics.dispose();
            return toPng(overlay);
        } catch (Exception ignored) {
            return null;
        }
    }


    /**
     * Renders the settings-page preview from the same frame/chrome/telemetry renderer used by
     * the live HUD. Only the world data is synthetic and frozen; the HUD itself is not rebuilt
     * in Custom UI markup, preventing the settings preview from drifting from the in-game HUD.
     */
    public static byte[] renderSettingsPreview(DynamicMinimapSetting settings) {
        DynamicMinimapSetting preview = settings.copy();
        preview.normalize();
        int mapSize = Math.max(180, Math.min(256, preview.sizePixels));
        int panelWidth = 148;
        int gap = preview.isCircle() ? 8 : 16;
        int panelHeight = 24 + 12;
        if (preview.showCoordinates) panelHeight += 43;
        if (preview.showCompass) panelHeight += 43;
        if (preview.showClock) panelHeight += 43;
        if (preview.showBiome) panelHeight += 43;

        byte[] overlayPng = renderHudOverlay(preview, mapSize, panelWidth, panelHeight, gap, 0.0D,
                "1,119, 298", "268° W", preview.clock24Hour ? "NIGHT 23:01" : "NIGHT 11:01 PM", "Plains Spawn");
        if (overlayPng == null) return null;
        try {
            BufferedImage overlay = ImageIO.read(new ByteArrayInputStream(overlayPng));
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
            if (preview.isCircle()) g.setClip(new Ellipse2D.Double(mapX + 3, mapY + 3, mapSize - 6, mapSize - 6));
            else g.setClip(new RoundRectangle2D.Double(mapX + 3, mapY + 3, mapSize - 6, mapSize - 6, 8, 8));
            // Frozen representative terrain. This is deliberately data-only: all bezel, compass,
            // panel geometry and typography come from the live HUD renderer below.
            GradientPaint land = new GradientPaint(mapX, mapY, new Color(38, 83, 48),
                    mapX + mapSize, mapY + mapSize, new Color(76, 108, 54));
            g.setPaint(land); g.fillRect(mapX, mapY, mapSize, mapSize);
            g.setColor(new Color(45, 91, 108));
            Path2D river = new Path2D.Double();
            river.moveTo(mapX + mapSize * .08, mapY + mapSize * .78);
            river.curveTo(mapX + mapSize * .32, mapY + mapSize * .55, mapX + mapSize * .43, mapY + mapSize * .86, mapX + mapSize * .61, mapY + mapSize * .52);
            river.curveTo(mapX + mapSize * .76, mapY + mapSize * .26, mapX + mapSize * .65, mapY + mapSize * .12, mapX + mapSize * .83, mapY - 4);
            g.setStroke(new BasicStroke(Math.max(12f, mapSize * .085f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(river);
            g.setClip(oldClip);

            // Use the same generated marker assets as the live HUD.
            if (preview.showOtherPlayers)
                drawPreviewAsset(g, renderDot(preview.colours.rgb(PLAYERS), preview.colours), mapX + mapSize * 35 / 100, mapY + mapSize * 30 / 100, 11);
            if (preview.showEntities) {
                drawPreviewAsset(g, renderDot(preview.colours.rgb(HOSTILE), preview.colours), mapX + mapSize * 73 / 100, mapY + mapSize * 35 / 100, 11);
                drawPreviewAsset(g, renderDot(preview.colours.rgb(FRIENDLY), preview.colours), mapX + mapSize * 69 / 100, mapY + mapSize * 72 / 100, 11);
            }
            if (preview.showDeathMarker) drawPreviewAsset(g, renderDeath(preview.colours), mapX + mapSize * 18 / 100, mapY + mapSize * 54 / 100, 16);
            drawPreviewAsset(g, renderSelfArrow(0.0D, preview.colours), mapX + mapSize / 2 - 12, mapY + mapSize / 2 - 12, 24);
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
            return toPng(stage);
        } catch (Exception ignored) { return null; }
    }

    /**
     * Renders the Position-tab preview as a complete screen rather than a
     * close-up of the HUD. The HUD image is the same settings preview used by
     * the other tabs, scaled and placed from the draft X/Y percentages.
     */
    public static byte[] renderPositionPreview(DynamicMinimapSetting settings) {
        byte[] hudPng = renderSettingsPreview(settings);
        if (hudPng == null) return null;
        try {
            BufferedImage hud = ImageIO.read(new ByteArrayInputStream(hudPng));
            if (hud == null) return null;

            // renderSettingsPreview returns a transparent preview-stage canvas with the
            // HUD centred inside it. Position the visible HUD bounds, not that canvas;
            // otherwise its transparent margins keep corner selections near the centre.
            int minX = hud.getWidth();
            int minY = hud.getHeight();
            int maxX = -1;
            int maxY = -1;
            for (int py = 0; py < hud.getHeight(); py++) {
                for (int px = 0; px < hud.getWidth(); px++) {
                    if ((hud.getRGB(px, py) >>> 24) == 0) continue;
                    minX = Math.min(minX, px);
                    minY = Math.min(minY, py);
                    maxX = Math.max(maxX, px);
                    maxY = Math.max(maxY, py);
                }
            }
            if (maxX >= minX && maxY >= minY) {
                hud = hud.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
            }

            int width = 360;
            int height = 286;
            int inset = 8;
            int widgetExtent = 154;
            int widgetScaleReference = Math.max(hud.getWidth(), hud.getHeight());
            if (!settings.infoPanelEnabled) {
                // renderSettingsPreview intentionally leaves the disabled-panel map smaller so
                // its map scale matches the enabled preview. Do not enlarge it again here after
                // trimming transparent pixels: use the enabled HUD footprint as the scale basis.
                DynamicMinimapSetting reference = settings.copy();
                reference.infoPanelEnabled = true;
                byte[] referencePng = renderSettingsPreview(reference);
                if (referencePng != null) {
                    BufferedImage referenceHud = ImageIO.read(new ByteArrayInputStream(referencePng));
                    if (referenceHud != null) {
                        int rMinX = referenceHud.getWidth(), rMinY = referenceHud.getHeight();
                        int rMaxX = -1, rMaxY = -1;
                        for (int py = 0; py < referenceHud.getHeight(); py++) {
                            for (int px = 0; px < referenceHud.getWidth(); px++) {
                                if ((referenceHud.getRGB(px, py) >>> 24) == 0) continue;
                                rMinX = Math.min(rMinX, px); rMinY = Math.min(rMinY, py);
                                rMaxX = Math.max(rMaxX, px); rMaxY = Math.max(rMaxY, py);
                            }
                        }
                        if (rMaxX >= rMinX && rMaxY >= rMinY) {
                            widgetScaleReference = Math.max(rMaxX - rMinX + 1, rMaxY - rMinY + 1);
                        }
                    }
                }
            }
            double widgetScale = widgetExtent / (double) widgetScaleReference;
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
        Font font = new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size);
        graphics.setFont(font.deriveFont(Map.of(TextAttribute.TRACKING, tracking)));
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
        Font font = new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size);
        graphics.setFont(font.deriveFont(Map.of(TextAttribute.TRACKING, tracking)));
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

    public static byte[] renderDot(int rgb, MinimapColours colours) {
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

    public static byte[] renderDeath(MinimapColours colours) {
        BufferedImage image = new BufferedImage(32, 32, 2);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int inset = 6;
        graphics.setStroke(new BasicStroke(6.0F, 1, 1));
        graphics.setColor(withAlpha(FIXED_MARKER_OUTLINE, 220));
        graphics.drawLine(inset, inset, 32 - inset, 32 - inset);
        graphics.drawLine(inset, 32 - inset, 32 - inset, inset);
        graphics.setStroke(new BasicStroke(3.0F, 1, 1));
        graphics.setColor(colours.awt(DEATH, 235));
        graphics.drawLine(inset, inset, 32 - inset, 32 - inset);
        graphics.drawLine(inset, 32 - inset, 32 - inset, inset);
        graphics.dispose();
        return toPng(image);
    }

    public static byte[] renderSelfArrow(double angle, MinimapColours colours) {
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

        graphics.setColor(colours.awt(MinimapColours.Key.SELF, 255));
        graphics.fill(arrow);
        graphics.setColor(withAlpha(FIXED_MARKER_OUTLINE, 220));
        graphics.setStroke(new BasicStroke(1.5F));
        graphics.draw(arrow);
        graphics.setTransform(previous);
        graphics.dispose();
        return toPng(image);
    }

    public static byte[] renderBlank() {
        return toPng(new BufferedImage(1, 1, 2));
    }

    /**
     * Map rotation that makes the player's current heading screen-up.
     * It deliberately reuses the proven self-arrow yaw convention.
     */
    public static double mapRotationForYaw(double yaw) {
        return Double.isNaN(yaw) ? 0.0D : -screenAngleForYaw(yaw);
    }

    public static int rotationBucketForYaw(double yaw) {
        return rotationBucket(mapRotationForYaw(yaw));
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
     * Rotates a previously rendered north-up terrain PNG around its center.
     * The terrain buffer is deliberately larger than the visible viewport, so
     * the viewport remains covered while the square buffer rotates.
     */
    public static byte[] rotateTerrain(byte[] northUpPng, double mapAngle) {
        return rotateTerrain(northUpPng, mapAngle, new MinimapColours());
    }

    public static byte[] rotateTerrain(byte[] northUpPng, double mapAngle, MinimapColours colours) {
        if (northUpPng == null || northUpPng.length == 0) {
            return null;
        }

        int bucket = rotationBucket(mapAngle);
        if (bucket == 0) {
            return northUpPng;
        }

        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(northUpPng));
            if (source == null) {
                return null;
            }

            int width = source.getWidth();
            int height = source.getHeight();
            BufferedImage rotated = new BufferedImage(width, height, 2);
            Graphics2D graphics = rotated.createGraphics();
            graphics.setColor(withAlpha(FIXED_MAP_BACKGROUND, 235));
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            );
            graphics.rotate(
                    bucketAngle(bucket),
                    (double)width / 2.0D,
                    (double)height / 2.0D
            );
            graphics.drawImage(source, 0, 0, (ImageObserver)null);
            graphics.dispose();
            return toPng(rotated);
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

    private static void drawApertureCircleFrame(Graphics2D graphics, int size, MinimapColours colours) {
        int center = size / 2;
        int outer = size - 5;
        graphics.setStroke(new BasicStroke(9.0F));
        graphics.setColor(withAlpha(FIXED_FRAME_SHADOW, 220));
        graphics.draw(new Ellipse2D.Double((double)2.0F, (double)2.0F, (double)outer, (double)outer));
        graphics.setStroke(new BasicStroke(2.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(colours.awt(EDGE, 245));
        // Four 30-degree gaps are centred exactly on E/N/W/S.
        for (int start : new int[]{15, 105, 195, 285}) {
            graphics.draw(new Arc2D.Double(4, 4, size - 9, size - 9, start, 60, Arc2D.OPEN));
        }

        // Keep the four stronger cardinal survey bars, then add the smaller
        // sub-compass dashes used by the square frame so the circular frame
        // has the same intermediate bearing references.
        graphics.setStroke(new BasicStroke(4.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(colours.awt(TICKS, 255));
        for (int i = 0; i < 4; i++) {
            double angle = -Math.PI / 2D + i * Math.PI / 2D;
            drawRadialTick(graphics, center, center, size / 2.0D - 5.0D,
                    size / 2.0D - 17.0D, angle, 4.0F, colours.awt(TICKS, 255));
        }

        // Three short dashes between each pair of cardinal points (22.5 degree
        // spacing). This gives the circular aperture the same readable minor
        // compass graduation as the square style without crowding the rim.
        for (int i = 0; i < 16; i++) {
            if (i % 4 == 0) {
                continue; // Cardinal positions already use the stronger bars above.
            }
            double angle = -Math.PI / 2D + i * Math.PI / 8D;
            drawRadialTick(graphics, center, center, size / 2.0D - 7.0D,
                    size / 2.0D - 13.0D, angle, 1.4F, colours.awt(TICKS, 225));
        }
    }

    private static void drawApertureSquareFrame(Graphics2D graphics, int size, MinimapColours colours) {
        graphics.setStroke(new BasicStroke(7.0F));
        graphics.setColor(withAlpha(FIXED_FRAME_SHADOW, 190));
        graphics.drawRect(2, 2, size - 5, size - 5);
        graphics.setStroke(new BasicStroke(1.5F));
        graphics.setColor(colours.awt(EDGE, 245));
        graphics.drawRect(4, 4, size - 9, size - 9);
        int start = 34;
        int end = size - 34;

        for(int i = 0; i <= 8; ++i) {
            int pos = start + (end - start) * i / 8;
            boolean major = i % 4 == 0;
            int tick = major ? 16 : 6;
            graphics.setStroke(new BasicStroke(major ? 1.8F : 1.0F));
            graphics.setColor(major ? colours.awt(TICKS, 255) : colours.awt(TICKS, 225));
            graphics.drawLine(pos, 8, pos, 8 + tick);
            graphics.drawLine(pos, size - 9, pos, size - 9 - tick);
            graphics.drawLine(8, pos, 8 + tick, pos);
            graphics.drawLine(size - 9, pos, size - 9 - tick, pos);
        }

        drawCornerBracket(graphics, 8, 8, 1, 1, colours);
        drawCornerBracket(graphics, size - 9, 8, -1, 1, colours);
        drawCornerBracket(graphics, 8, size - 9, 1, -1, colours);
        drawCornerBracket(graphics, size - 9, size - 9, -1, -1, colours);
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

    private static void drawCornerBracket(Graphics2D graphics, int x, int y, int xDir, int yDir, MinimapColours colours) {
        graphics.setStroke(new BasicStroke(2.0F, 1, 1));
        graphics.setColor(colours.awt(EDGE, 245));
        graphics.drawLine(x, y, x + xDir * 28, y);
        graphics.drawLine(x, y, x, y + yDir * 28);
        graphics.setStroke(new BasicStroke(1.0F, 1, 1));
        graphics.setColor(colours.awt(TICKS, 225));
        graphics.drawLine(x + xDir * 7, y + yDir * 7, x + xDir * 18, y + yDir * 7);
        graphics.drawLine(x + xDir * 7, y + yDir * 7, x + xDir * 7, y + yDir * 18);
    }

    /**
     * Rotates square-map cardinal directions while constraining each label to
     * the square frame perimeter. Rotating the Graphics2D context directly
     * makes the labels orbit on a circle, which is correct for round maps but
     * visually wrong for square maps.
     */
    private static void drawSquareCardinalsRotated(
            Graphics2D graphics,
            int size,
            double angle
            , MinimapColours colours) {
        graphics.setFont(CARDINAL_FONT);
        double center = (double)size / 2.0D;
        double radius = center - 15.0D;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        drawSquareBorderCardinal(graphics, "N", 0.0D, -1.0D, cos, sin, center, radius, colours);
        drawSquareBorderCardinal(graphics, "E", 1.0D, 0.0D, cos, sin, center, radius, colours);
        drawSquareBorderCardinal(graphics, "S", 0.0D, 1.0D, cos, sin, center, radius, colours);
        drawSquareBorderCardinal(graphics, "W", -1.0D, 0.0D, cos, sin, center, radius, colours);
    }

    private static void drawSquareBorderCardinal(
            Graphics2D graphics,
            String label,
            double baseX,
            double baseY,
            double cos,
            double sin,
            double center,
            double radius
            , MinimapColours colours) {
        double x = baseX * cos - baseY * sin;
        double y = baseX * sin + baseY * cos;
        double max = Math.max(Math.abs(x), Math.abs(y));
        if (max < 1.0E-9D) {
            return;
        }

        double scale = radius / max;
        int px = (int)Math.round(center + x * scale);
        int py = (int)Math.round(center + y * scale);
        drawCardinal(graphics, label, px, py, colours);
    }

    private static void drawCardinals(Graphics2D graphics, int size, boolean circle, MinimapColours colours) {
        graphics.setFont(CARDINAL_FONT);
        int margin = circle ? 27 : 15;
        drawCardinal(graphics, "N", size / 2, margin, colours);
        drawCardinal(graphics, "S", size / 2, size - margin, colours);
        drawCardinal(graphics, "E", size - margin, size / 2, colours);
        drawCardinal(graphics, "W", margin, size / 2, colours);
    }

    private static void drawCardinal(Graphics2D graphics, String label, int centerX, int centerY, MinimapColours colours) {
        FontMetrics metrics = graphics.getFontMetrics();
        int left = centerX - metrics.stringWidth(label) / 2;
        int baseline = centerY + metrics.getAscent() / 2 - 1;
        graphics.setColor(new Color(0, 0, 0, 170));
        graphics.drawString(label, left + 1, baseline + 1);
        graphics.setColor(colours.awt(CARDINAL_TEXT, 255));
        graphics.drawString(label, left, baseline);
    }

    private static byte[] toPng(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, image.getWidth() * image.getHeight()));
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (Exception var2) {
            return null;
        }
    }

    private static int[] decodePixels(MapImage image) {
        int width = image.width;
        int height = image.height;
        long countLong = (long)width * (long)height;
        if (countLong > 0L && countLong <= 2147483647L) {
            int count = (int)countLong;
            int[] palette = image.palette;
            if (palette != null && palette.length != 0) {
                byte[] packedIndices = image.packedIndices;
                int bitsPerIndex = Byte.toUnsignedInt(image.bitsPerIndex);
                int[] pixels;
                if (packedIndices != null && packedIndices.length != 0 && bitsPerIndex > 0) {
                    BitFieldArr bitField = new BitFieldArr(bitsPerIndex, count);
                    bitField.set(packedIndices);
                    pixels = new int[count];

                    for(int i = 0; i < count; ++i) {
                        int paletteIndex = bitField.get(i);
                        pixels[i] = palette[paletteIndex >= 0 && paletteIndex < palette.length ? paletteIndex : 0];
                    }
                } else {
                    if (palette.length != count) {
                        return null;
                    }

                    pixels = Arrays.copyOf(palette, count);
                }

                for(int i = 0; i < pixels.length; ++i) {
                    pixels[i] = Integer.rotateRight(pixels[i], 8);
                }

                return pixels;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    private static long sampleHash(byte[] bytes) {
        if (bytes != null && bytes.length != 0) {
            long hash = (long)bytes.length;
            int[] samples = new int[]{0, bytes.length / 4, bytes.length / 2, bytes.length * 3 / 4, bytes.length - 1};

            for(int sample : samples) {
                hash = 31L * hash + (long)Byte.toUnsignedInt(bytes[sample]);
            }

            return hash;
        } else {
            return 0L;
        }
    }

    private static long sampleHash(int[] ints) {
        if (ints != null && ints.length != 0) {
            long hash = (long)ints.length;
            int[] samples = new int[]{0, ints.length / 4, ints.length / 2, ints.length * 3 / 4, ints.length - 1};

            for(int sample : samples) {
                hash = 31L * hash + (long)ints[sample];
            }

            return hash;
        } else {
            return 0L;
        }
    }

    public static final class Terrain {
        public final byte[] png;
        public final int present;
        public final int total;

        Terrain(byte[] png, int present, int total) {
            this.png = png;
            this.present = present;
            this.total = total;
        }

        public boolean complete() {
            return this.total > 0 && this.present >= this.total;
        }
    }
    private static Color withAlpha(Color colour, int alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
    }

}

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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

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
        if (worldMapManager != null) {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
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
                        MapImage image = worldMapManager.getImageAsync(chunkX, chunkZ).getNow(null);
                        if (image != null && image.width > 0 && image.height > 0) {
                            int[] pixels = decodePixels(image);
                            if (pixels != null) {
                                BufferedImage chunk = new BufferedImage(image.width, image.height, 2);
                                chunk.setRGB(0, 0, image.width, image.height, pixels, 0, image.width);
                                int drawX = (int)Math.round(((double)chunkX * (double)CHUNK_SIZE - originX) * texturePixelsPerBlock);
                                int drawZ = (int)Math.round(((double)chunkZ * (double)CHUNK_SIZE - originZ) * texturePixelsPerBlock);
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
        if (worldMapManager == null) {
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
                        MapImage image = worldMapManager.getImageIfInMemory(chunkX, chunkZ);
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

    public static byte[] renderFrame(DynamicMinimapSetting settings) {
        return renderFrame(settings, 0.0D);
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
            drawNorthNeedle(graphics, logicalSize / 2, 3, colours);
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
        int panelWidth = side ? sidePanelWidth : mapSize;
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
        graphics.setStroke(new BasicStroke(3.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(withAlpha(FIXED_FRAME_SHADOW, 105));
        graphics.draw(shell);
        graphics.setColor(panel);
        graphics.fill(shell);

        // A faint second tint and inner hairline give the panel a translucent,
        // glass-like edge without making the telemetry background opaque.
        graphics.setColor(new Color(255, 255, 255, 12));
        graphics.fill(shell);

        graphics.setStroke(new BasicStroke(1.35F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(settings.colours.awt(EDGE, 195));
        graphics.draw(shell);
        graphics.setStroke(new BasicStroke(0.65F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(settings.colours.awt(TICKS, 82));
        graphics.draw(shell);

        drawConnectorDivider(graphics, settings, mapX, mapY, mapSize, gap, survey, edge);
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
        byte[] chromePng = renderHudChrome(settings, mapSize, sidePanelWidth, panelHeight, gap);
        if (chromePng == null) return null;

        try {
            BufferedImage overlay = ImageIO.read(new ByteArrayInputStream(chromePng));
            Graphics2D graphics = overlay.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            boolean side = settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                    || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT;
            int panelWidth = side ? sidePanelWidth : mapSize;
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

    private static void drawTelemetry(Graphics2D graphics, DynamicMinimapSetting settings,
                                      int x, int y, int width, String coords,
                                      String compass, String clock, String biome) {
        int cursor = y + 6;
        int brandHeight = 24;
        int rowHeight = 43;
        int baseInset = 8;
        Color caption = settings.colours.awt(TICKS, 255);

        graphics.setColor(caption);
        int barX = settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                ? x + width - 9 : x + 6;
        graphics.fillRoundRect(barX, cursor + 3, 3, brandHeight - 6, 2, 2);
        drawHudText(graphics, "DapperMap", x + telemetryInset(settings, width, 14),
                cursor + 16, 9, settings.colours.awt(BRAND_TEXT, 255), true);
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

    private static void drawTelemetryRow(Graphics2D graphics, DynamicMinimapSetting settings,
                                         int x, int width, int y, int baseInset,
                                         int curveInset, String caption, String value,
                                         Color valueColor) {
        boolean curvedSide = settings.isCircle()
                && (settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT);
        int textX = x + telemetryInset(settings, width,
                baseInset + (curvedSide ? curveInset : 0));
        drawHudText(graphics, caption, textX, y + 14, 7,
                settings.colours.awt(TICKS, 255), true);
        drawHudText(graphics, value == null ? "" : value, textX, y + 31, 10,
                valueColor, true);
    }

    private static int telemetryInset(DynamicMinimapSetting settings, int width, int inset) {
        if (settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT) {
            return 8;
        }
        return inset;
    }

    private static void drawHudText(Graphics2D graphics, String value, int x, int baseline,
                                    int size, Color color, boolean bold) {
        graphics.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size));
        graphics.setColor(new Color(0, 0, 0, 150));
        graphics.drawString(value, x + 1, baseline + 1);
        graphics.setColor(color);
        graphics.drawString(value, x, baseline);
    }

    private static Path2D.Double panelShellPath(DynamicMinimapSetting settings,
                                                int panelX, int panelY,
                                                int panelWidth, int panelHeight) {
        Path2D.Double path = new Path2D.Double();
        boolean side = settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT;
        if (!settings.isCircle() || !side) {
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

    private static void drawConnectorDivider(Graphics2D graphics, DynamicMinimapSetting settings,
                                             int mapX, int mapY, int mapSize, int gap,
                                             Color survey, Color edge) {
        graphics.setStroke(new BasicStroke(2.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(edge);
        int position = settings.infoPanelPosition;
        if (settings.isCircle()) {
            double start = switch (position) {
                case DynamicMinimapSetting.INFO_LEFT -> 125.0D;
                case DynamicMinimapSetting.INFO_BELOW -> 215.0D;
                case DynamicMinimapSetting.INFO_ABOVE -> 35.0D;
                default -> -55.0D;
            };
            // The frame arc itself is inset four pixels from the map bounds.
            // Compensate for that inset before moving it into the transparent
            // gap, otherwise an eight-pixel circle gap hides the arc in-frame.
            double dividerOffset = gap / 2.0D + 4.0D;
            double offsetX = position == DynamicMinimapSetting.INFO_LEFT ? -dividerOffset
                    : position == DynamicMinimapSetting.INFO_RIGHT ? dividerOffset : 0.0D;
            double offsetY = position == DynamicMinimapSetting.INFO_ABOVE ? -dividerOffset
                    : position == DynamicMinimapSetting.INFO_BELOW ? dividerOffset : 0.0D;
            graphics.draw(new Arc2D.Double(mapX + 3 + offsetX, mapY + 3 + offsetY,
                    mapSize - 7, mapSize - 7,
                    start, 110.0D, Arc2D.OPEN));
        } else {
            int inset = Math.max(24, mapSize / 5);
            if (position == DynamicMinimapSetting.INFO_LEFT || position == DynamicMinimapSetting.INFO_RIGHT) {
                int x = position == DynamicMinimapSetting.INFO_LEFT
                        ? mapX - gap / 2 : mapX + mapSize + gap / 2;
                graphics.drawLine(x, mapY + inset, x, mapY + mapSize - inset);
            } else {
                int y = position == DynamicMinimapSetting.INFO_ABOVE
                        ? mapY - gap / 2 : mapY + mapSize + gap / 2;
                graphics.drawLine(mapX + inset, y, mapX + mapSize - inset, y);
            }
        }

        graphics.setColor(survey);
        int node = 5;
        if (position == DynamicMinimapSetting.INFO_LEFT || position == DynamicMinimapSetting.INFO_RIGHT) {
            int x = position == DynamicMinimapSetting.INFO_LEFT
                    ? mapX - gap / 2 - node / 2 : mapX + mapSize + gap / 2 - node / 2;
            graphics.fillOval(x, mapY + mapSize / 2 - node / 2, node, node);
        } else {
            int y = position == DynamicMinimapSetting.INFO_ABOVE
                    ? mapY - gap / 2 - node / 2 : mapY + mapSize + gap / 2 - node / 2;
            graphics.fillOval(mapX + mapSize / 2 - node / 2, y, node, node);
        }
    }

    public static byte[] renderDot(int rgb) { return renderDot(rgb, new MinimapColours()); }

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

    public static byte[] renderDeath() { return renderDeath(new MinimapColours()); }

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

    public static byte[] renderSelfArrow(double angle) { return renderSelfArrow(angle, new MinimapColours()); }

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

        // Cyan side highlights echo the concept artwork without obscuring the
        // E/W cardinal labels.
        graphics.setStroke(new BasicStroke(3.1F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(colours.awt(TICKS, 245));
        graphics.draw(new Arc2D.Double(7, 7, size - 15, size - 15, -27, 18, Arc2D.OPEN));
        graphics.draw(new Arc2D.Double(7, 7, size - 15, size - 15, 153, 18, Arc2D.OPEN));

        // Four short survey bars replace the dense nautical ruler used by
        // Cartographer. Their asymmetry is the signature of the aperture style.
        graphics.setStroke(new BasicStroke(4.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(colours.awt(TICKS, 255));
        for (int i = 0; i < 4; i++) {
            double angle = -Math.PI / 2D + i * Math.PI / 2D;
            drawRadialTick(graphics, center, center, size / 2.0D - 5.0D,
                    size / 2.0D - 17.0D, angle, 4.0F, colours.awt(TICKS, 255));
        }
    }

    private static void drawApertureSquareFrame(Graphics2D graphics, int size, MinimapColours colours) {
        graphics.setStroke(new BasicStroke(7.0F));
        graphics.setColor(withAlpha(FIXED_FRAME_SHADOW, 190));
        graphics.drawRect(2, 2, size - 5, size - 5);
        graphics.setStroke(new BasicStroke(1.5F));
        graphics.setColor(colours.awt(EDGE, 235));
        graphics.drawRect(4, 4, size - 9, size - 9);
        int start = 34;
        int end = size - 34;

        for(int i = 0; i <= 8; ++i) {
            int pos = start + (end - start) * i / 8;
            boolean major = i % 4 == 0;
            int tick = major ? 16 : 6;
            graphics.setStroke(new BasicStroke(major ? 1.8F : 1.0F));
            graphics.setColor(major ? colours.awt(EDGE, 235) : colours.awt(TICKS, 225));
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

    private static void drawNorthNeedle(Graphics2D graphics, int center, int top, MinimapColours colours) {
        Path2D.Double needle = new Path2D.Double();
        needle.moveTo((double)center, (double)top);
        needle.lineTo((double)(center + 5), (double)(top + 11));
        needle.lineTo((double)center, (double)(top + 8));
        needle.lineTo((double)(center - 5), (double)(top + 11));
        needle.closePath();
        graphics.setColor(withAlpha(FIXED_FRAME_SHADOW, 190));
        graphics.translate(1, 1);
        graphics.fill(needle);
        graphics.translate(-1, -1);
        graphics.setColor(colours.awt(EDGE, 255));
        graphics.fill(needle);
    }

    private static void drawCornerBracket(Graphics2D graphics, int x, int y, int xDir, int yDir, MinimapColours colours) {
        graphics.setStroke(new BasicStroke(2.0F, 1, 1));
        graphics.setColor(colours.awt(EDGE, 235));
        graphics.drawLine(x, y, x + xDir * 28, y);
        graphics.drawLine(x, y, x, y + yDir * 28);
        graphics.setStroke(new BasicStroke(1.0F, 1, 1));
        graphics.setColor(colours.awt(TICKS, 210));
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
        int margin = circle ? 17 : 15;
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
        graphics.setColor(colours.awt(TICKS, 255));
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

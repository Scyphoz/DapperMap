package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

public final class DynamicMinimapRenderer {
    private static final int CHUNK_SIZE = 32;
    private static final Color BACKGROUND = new Color(10, 18, 24, 235);
    private static final Color BORDER = new Color(206, 214, 226, 225);
    private static final Color CARDINAL = new Color(255, 220, 80, 255);
    private static final Color BRASS = new Color(224, 174, 82, 235);
    private static final Color AGED_BRASS = new Color(126, 93, 47, 220);
    private static final Color INK_SHADOW = new Color(3, 8, 12, 190);
    private static final Color GLASS_EDGE = new Color(151, 215, 235, 105);
    private static final Color SELF = new Color(40, 255, 100);
    private static final Font CARDINAL_FONT = new Font("SansSerif", 1, 15);
    private static final Font SMALL_CARDINAL_FONT = new Font("SansSerif", 1, 10);
    private static final Map<String, byte[]> MASK_CACHE = new ConcurrentHashMap();
    private static final Map<String, byte[]> FRAME_CACHE = new ConcurrentHashMap();

    private DynamicMinimapRenderer() {
    }

    public static double pixelsPerBlock(DynamicMinimapSetting settings) {
        return (double)settings.sizePixels / ((double)2.0F * (double)Math.max(16, settings.zoomBlocks));
    }

    public static int panMarginPixels(DynamicMinimapSetting settings) {
        return Math.max(32, settings.sizePixels / 2);
    }

    public static int bufferPixels(DynamicMinimapSetting settings) {
        int buffer = settings.sizePixels + 2 * panMarginPixels(settings);
        return (buffer & 1) == 0 ? buffer : buffer + 1;
    }

    public static double bufferOriginWorld(double center, DynamicMinimapSetting settings) {
        return center - (double)bufferPixels(settings) / (double)2.0F / pixelsPerBlock(settings);
    }

    public static Terrain renderTerrain(WorldMapManager worldMapManager, double centerX, double centerZ, DynamicMinimapSetting settings) {
        int buffer = bufferPixels(settings);
        double pixelsPerBlock = pixelsPerBlock(settings);
        double originX = bufferOriginWorld(centerX, settings);
        double originZ = bufferOriginWorld(centerZ, settings);
        BufferedImage terrain = new BufferedImage(buffer, buffer, 2);
        Graphics2D graphics = terrain.createGraphics();
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, buffer, buffer);
        int present = 0;
        int total = 0;
        if (worldMapManager != null) {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double worldSize = (double)buffer / pixelsPerBlock;
            int minChunkX = ChunkUtil.chunkCoordinate((int)Math.floor(originX));
            int minChunkZ = ChunkUtil.chunkCoordinate((int)Math.floor(originZ));
            int maxChunkX = ChunkUtil.chunkCoordinate((int)Math.ceil(originX + worldSize));
            int maxChunkZ = ChunkUtil.chunkCoordinate((int)Math.ceil(originZ + worldSize));
            int chunkPixels = (int)Math.ceil((double)32.0F * pixelsPerBlock);

            for(int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                for(int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
                    ++total;

                    try {
                        MapImage image = (MapImage)worldMapManager.getImageAsync(chunkX, chunkZ).getNow(null);
                        if (image != null && image.width > 0 && image.height > 0) {
                            int[] pixels = decodePixels(image);
                            if (pixels != null) {
                                BufferedImage chunk = new BufferedImage(image.width, image.height, 2);
                                chunk.setRGB(0, 0, image.width, image.height, pixels, 0, image.width);
                                int drawX = (int)Math.round(((double)chunkX * (double)32.0F - originX) * pixelsPerBlock);
                                int drawZ = (int)Math.round(((double)chunkZ * (double)32.0F - originZ) * pixelsPerBlock);
                                graphics.drawImage(chunk, drawX, drawZ, chunkPixels, chunkPixels, (ImageObserver)null);
                                ++present;
                            }
                        }
                    } catch (Throwable var31) {
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
        String var10000 = settings.isCircle() ? "c" : "r";
        String key = var10000 + settings.sizePixels;
        byte[] cached = (byte[])MASK_CACHE.get(key);
        if (cached != null) {
            return cached;
        } else {
            int size = settings.sizePixels;
            BufferedImage image = new BufferedImage(size, size, 2);
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            if (settings.isCircle()) {
                graphics.fill(new Ellipse2D.Double((double)0.0F, (double)0.0F, (double)size, (double)size));
            } else {
                graphics.fillRect(0, 0, size, size);
            }

            graphics.dispose();
            byte[] png = toPng(image);
            if (png != null) {
                MASK_CACHE.put(key, png);
            }

            return png;
        }
    }

    public static byte[] renderFrame(DynamicMinimapSetting settings) {
        String var10000 = settings.isCircle() ? "c" : "r";
        String key = var10000 + settings.sizePixels + (settings.showBorder ? "b" : "");
        byte[] cached = (byte[])FRAME_CACHE.get(key);
        if (cached != null) {
            return cached;
        } else {
            int size = settings.sizePixels;
            BufferedImage image = new BufferedImage(size, size, 2);
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (settings.showBorder) {
                if (settings.isCircle()) {
                    drawNauticalCircleFrame(graphics, size);
                } else {
                    drawNauticalSquareFrame(graphics, size);
                }
            }

            drawCardinals(graphics, size, settings.isCircle());
            graphics.dispose();
            byte[] png = toPng(image);
            if (png != null) {
                FRAME_CACHE.put(key, png);
            }

            return png;
        }
    }

    public static byte[] renderDot(int rgb) {
        BufferedImage image = new BufferedImage(32, 32, 2);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int center = 16;
        int radius = 12;
        graphics.setColor(new Color(0, 0, 0, 200));
        graphics.fillOval(center - radius - 2, center - radius - 2, (radius + 2) * 2, (radius + 2) * 2);
        graphics.setColor(new Color(-16777216 | rgb, true));
        graphics.fillOval(center - radius, center - radius, radius * 2, radius * 2);
        graphics.dispose();
        return toPng(image);
    }

    public static byte[] renderDeath() {
        BufferedImage image = new BufferedImage(32, 32, 2);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int inset = 6;
        graphics.setStroke(new BasicStroke(6.0F, 1, 1));
        graphics.setColor(new Color(0, 0, 0, 220));
        graphics.drawLine(inset, inset, 32 - inset, 32 - inset);
        graphics.drawLine(inset, 32 - inset, 32 - inset, inset);
        graphics.setStroke(new BasicStroke(3.0F, 1, 1));
        graphics.setColor(DynamicMinimapMarker.DEATH_COLOR);
        graphics.drawLine(inset, inset, 32 - inset, 32 - inset);
        graphics.drawLine(inset, 32 - inset, 32 - inset, inset);
        graphics.dispose();
        return toPng(image);
    }

    public static byte[] renderSelfArrow(double angle) {
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

        graphics.setColor(SELF);
        graphics.fill(arrow);
        graphics.setColor(new Color(0, 0, 0, 220));
        graphics.setStroke(new BasicStroke(1.5F));
        graphics.draw(arrow);
        graphics.setTransform(previous);
        graphics.dispose();
        return toPng(image);
    }

    public static byte[] renderBlank() {
        return toPng(new BufferedImage(1, 1, 2));
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

    private static void drawNauticalCircleFrame(Graphics2D graphics, int size) {
        int center = size / 2;
        int outer = size - 5;
        int middle = size - 17;
        int inner = size - 29;
        graphics.setStroke(new BasicStroke(5.0F));
        graphics.setColor(INK_SHADOW);
        graphics.draw(new Ellipse2D.Double((double)2.0F, (double)2.0F, (double)outer, (double)outer));
        graphics.setStroke(new BasicStroke(2.4F));
        graphics.setColor(BRASS);
        graphics.draw(new Ellipse2D.Double((double)3.0F, (double)3.0F, (double)(size - 7), (double)(size - 7)));
        graphics.setStroke(new BasicStroke(1.2F));
        graphics.setColor(AGED_BRASS);
        graphics.draw(new Ellipse2D.Double((double)10.0F, (double)10.0F, (double)middle, (double)middle));
        graphics.setColor(GLASS_EDGE);
        graphics.draw(new Ellipse2D.Double((double)16.0F, (double)16.0F, (double)inner, (double)inner));

        for(int i = 0; i < 64; ++i) {
            boolean cardinal = i % 16 == 0;
            boolean ordinal = i % 8 == 0;
            boolean longTick = i % 4 == 0;
            double angle = (-Math.PI / 2D) + (double)i * Math.PI * (double)2.0F / (double)64.0F;
            int tick = cardinal ? 15 : (ordinal ? 12 : (longTick ? 9 : 5));
            float width = cardinal ? 2.2F : (ordinal ? 1.7F : 1.0F);
            Color color = !cardinal && !ordinal ? BORDER : BRASS;
            drawRadialTick(graphics, center, center, (double)size / (double)2.0F - (double)8.0F, (double)size / (double)2.0F - (double)8.0F - (double)tick, angle, width, color);
        }

        drawNorthNeedle(graphics, center, 18);
    }

    private static void drawNauticalSquareFrame(Graphics2D graphics, int size) {
        graphics.setStroke(new BasicStroke(4.0F));
        graphics.setColor(INK_SHADOW);
        graphics.drawRect(2, 2, size - 5, size - 5);
        graphics.setStroke(new BasicStroke(2.0F));
        graphics.setColor(BRASS);
        graphics.drawRect(4, 4, size - 9, size - 9);
        graphics.setStroke(new BasicStroke(1.0F));
        graphics.setColor(AGED_BRASS);
        graphics.drawRect(11, 11, size - 23, size - 23);
        graphics.setColor(GLASS_EDGE);
        graphics.drawRect(17, 17, size - 35, size - 35);
        int start = 24;
        int end = size - 24;

        for(int i = 0; i <= 16; ++i) {
            int pos = start + (end - start) * i / 16;
            boolean major = i % 4 == 0;
            int tick = major ? 13 : 7;
            graphics.setStroke(new BasicStroke(major ? 1.8F : 1.0F));
            graphics.setColor(major ? BRASS : BORDER);
            graphics.drawLine(pos, 8, pos, 8 + tick);
            graphics.drawLine(pos, size - 9, pos, size - 9 - tick);
            graphics.drawLine(8, pos, 8 + tick, pos);
            graphics.drawLine(size - 9, pos, size - 9 - tick, pos);
        }

        drawCornerBracket(graphics, 8, 8, 1, 1);
        drawCornerBracket(graphics, size - 9, 8, -1, 1);
        drawCornerBracket(graphics, 8, size - 9, 1, -1);
        drawCornerBracket(graphics, size - 9, size - 9, -1, -1);
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

    private static void drawNorthNeedle(Graphics2D graphics, int center, int top) {
        Path2D.Double needle = new Path2D.Double();
        needle.moveTo((double)center, (double)top);
        needle.lineTo((double)(center + 6), (double)(top + 16));
        needle.lineTo((double)center, (double)(top + 11));
        needle.lineTo((double)(center - 6), (double)(top + 16));
        needle.closePath();
        graphics.setColor(INK_SHADOW);
        graphics.translate(1, 1);
        graphics.fill(needle);
        graphics.translate(-1, -1);
        graphics.setColor(CARDINAL);
        graphics.fill(needle);
    }

    private static void drawCornerBracket(Graphics2D graphics, int x, int y, int xDir, int yDir) {
        graphics.setStroke(new BasicStroke(2.0F, 1, 1));
        graphics.setColor(BRASS);
        graphics.drawLine(x, y, x + xDir * 20, y);
        graphics.drawLine(x, y, x, y + yDir * 20);
        graphics.setStroke(new BasicStroke(1.0F, 1, 1));
        graphics.setColor(GLASS_EDGE);
        graphics.drawLine(x + xDir * 5, y + yDir * 5, x + xDir * 15, y + yDir * 5);
        graphics.drawLine(x + xDir * 5, y + yDir * 5, x + xDir * 5, y + yDir * 15);
    }

    private static void drawCardinals(Graphics2D graphics, int size, boolean circle) {
        graphics.setFont(CARDINAL_FONT);
        int margin = circle ? 17 : 15;
        drawCardinal(graphics, "N", size / 2, margin);
        drawCardinal(graphics, "S", size / 2, size - margin);
        drawCardinal(graphics, "E", size - margin, size / 2);
        drawCardinal(graphics, "W", margin, size / 2);
        if (circle && size >= 180) {
            graphics.setFont(SMALL_CARDINAL_FONT);
            int inset = Math.max(30, size / 7);
            drawCardinal(graphics, "NE", size - inset, inset);
            drawCardinal(graphics, "SE", size - inset, size - inset);
            drawCardinal(graphics, "SW", inset, size - inset);
            drawCardinal(graphics, "NW", inset, inset);
        }

    }

    private static void drawCardinal(Graphics2D graphics, String label, int centerX, int centerY) {
        FontMetrics metrics = graphics.getFontMetrics();
        int left = centerX - metrics.stringWidth(label) / 2;
        int baseline = centerY + metrics.getAscent() / 2 - 1;
        graphics.setColor(new Color(0, 0, 0, 170));
        graphics.drawString(label, left + 1, baseline + 1);
        graphics.setColor(CARDINAL);
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
}

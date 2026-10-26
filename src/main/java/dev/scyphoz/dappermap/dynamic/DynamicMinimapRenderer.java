package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import java.awt.BasicStroke;
import java.awt.Color;
import static dev.scyphoz.dappermap.dynamic.DynamicMinimapColours.Key.*;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.Shape;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongPredicate;

import dev.scyphoz.dappermap.service.DapperMapServerLoad;
import dev.scyphoz.dappermap.util.DapperMapUtil;
import static dev.scyphoz.dappermap.util.DapperMapUtil.argbPixels;
import static dev.scyphoz.dappermap.util.DapperMapUtil.backingArgb;

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
    static final int STATIC_ASSET_LOGICAL_SIZE = 220;
    /** Map aperture inside the frame, in frame units: inset from the edge and square-map corner radius. */
    static final double APERTURE_INSET = 11.0D, APERTURE_CORNER = 10.0D;
    public static final int ROTATION_BUCKETS = 360;
    // No text is rasterised: every letter the player reads (info panel, N/E/S/W) is a native
    // game label in Hytale's own font, drawn by the client over these images.
    private static final double[][] SQUARE_COMPASS_POINTS = {{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1}};
    /** Cardinal letters, indexed like {@link #cardinalLetterCentre}. */
    public static final String[] CARDINAL_LETTERS = {"N", "E", "S", "W"};
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
        return (double)settings.sizePixels / ((double)2.0F * (double)Math.max(DynamicMinimapSetting.ZOOM_MIN, settings.zoomBlocks));
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
     * The per-chunk hashes of exactly the images drawn are taken in the same pass (it used to be
     * a second pass over every chunk after rendering). {@code encodePng} false skips the PNG for
     * player-up maps, whose rotation source works from the image and encodes north-up lazily.
     * {@code chunkLoaded} (see {@link #loadedChunkFilter}; null = no gating) keeps image requests
     * to loaded world chunks: an unloaded chunk only uses an image already in memory, so a render
     * never makes the server load or generate terrain. A skipped chunk counts as missing.
     */
    public static Terrain renderTerrain(WorldMapManager worldMapManager, double centerX, double centerZ,
                                        DynamicMinimapSetting settings, BiFunction<Integer, Integer, MapImage> caveImages,
                                        boolean encodePng, LongPredicate chunkLoaded) {
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
        int unloaded = 0;
        long[] chunkHashes = null;
        // The always-true filter needs no index work at all.
        boolean gated = chunkLoaded != null && chunkLoaded != ALWAYS_LOADED;
        if (worldMapManager != null || caveImages != null) {
            ChunkRange range = ChunkRange.of(centerX, centerZ, settings);
            int chunkPixels = (int)Math.ceil((double)CHUNK_SIZE * texturePixelsPerBlock);
            // Per-chunk hashes in grid order (0 = missing), the layout terrainChunkHashes returns.
            chunkHashes = new long[range.width() * range.height()];

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
            // World-map image requests this render may start. Gated renders only ask for loaded
            // chunks, whose images are cheap to build (no disk loads), so every missing one is
            // requested at once: with BetterMap, Hytale's own map tracker treats chunks it already
            // sent as done for good (view radius 999), so after Hytale drops an area's images
            // (about 60 s unseen) nothing else rebuilds them. At 16 per render a full HUD took
            // a dozen partial redraws (about 12 s) to fill. Requests for a chunk already queued
            // reuse Hytale's pending future, so repeats cost nothing. Ungated renders keep 16.
            int surfaceBudget = gated ? chunkHashes.length : 16;
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
                        if (image == null) {
                            // World-map chunk index == ChunkStore column index (both 32 blocks,
                            // ChunkUtil.indexChunk): no conversion. Unloaded: no request slot used.
                            if (gated && !chunkLoaded.test(ChunkUtil.indexChunk(chunkX, chunkZ))) ++unloaded;
                            else if (requests < surfaceBudget) {
                                ++requests;
                                image = worldMapManager.getImageAsync(chunkX, chunkZ).getNow(null);
                            }
                        }
                    }
                    // Hash before drawing, so an image that fails to decode still counts as seen.
                    if (image != null) chunkHashes[range.indexOf(chunkX, chunkZ)] = presentHash(image);
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
        }

        graphics.dispose();
        if (present == 0) return new Terrain(null, null, 0, total, unloaded, chunkHashes);
        return new Terrain(encodePng ? terrainPng(terrain) : null, terrain, present, total, unloaded, chunkHashes);
    }

    /**
     * Per-chunk hashes of the images currently in memory for a buffer, in renderTerrain's grid
     * order (0 = missing; null when there is no image source). Used by the periodic probe and
     * compared with {@link #chunksChanged}. No world-map loads are started. A BetterMap cave view
     * quietly re-requests up to {@link #PROBE_CAVE_REFRESHES} expired chunks per probe (its images
     * time out after 30 s): a rebuild that differs shows up as a changed hash on a later probe,
     * so mined-out caves still refresh, while removals themselves never force a redraw.
     */
    public static long[] terrainChunkHashes(WorldMapManager worldMapManager, double centerX, double centerZ,
                                            DynamicMinimapSetting settings, BiFunction<Integer, Integer, MapImage> caveImages) {
        if (worldMapManager == null && caveImages == null) return null;
        ChunkRange range = ChunkRange.of(centerX, centerZ, settings);
        long[] chunkHashes = new long[range.width() * range.height()];
        dev.scyphoz.dappermap.integration.BetterMapIntegration.CaveView view =
                caveImages instanceof dev.scyphoz.dappermap.integration.BetterMapIntegration.CaveView cave ? cave : null;
        // Cave chunks found missing on this pass (x, z pairs); refreshed nearest-first below.
        int[] missing = view != null ? new int[chunkHashes.length * 2] : null;
        int missingCount = 0;
        for (int chunkZ = range.minZ(); chunkZ <= range.maxZ(); ++chunkZ) {
            for (int chunkX = range.minX(); chunkX <= range.maxX(); ++chunkX) {
                try {
                    MapImage image;
                    if (view != null) {
                        image = view.peek(chunkX, chunkZ);
                        if (image == null) { missing[missingCount * 2] = chunkX; missing[missingCount * 2 + 1] = chunkZ; ++missingCount; }
                    } else {
                        image = caveImages != null ? caveImages.apply(chunkX, chunkZ)
                                : worldMapManager.getImageIfInMemory(chunkX, chunkZ);
                    }
                    if (image != null) chunkHashes[range.indexOf(chunkX, chunkZ)] = presentHash(image);
                } catch (Throwable ignored) {
                    // Unreadable chunk: left as missing (0).
                }
            }
        }
        if (missingCount > 0) refreshCaveChunks(view, chunkHashes, range, missing, missingCount, centerX, centerZ);
        return chunkHashes;
    }

    /** Cave chunks the probe may re-request per pass (BetterMap admits 8 starts per 100 ms overall). */
    private static final int PROBE_CAVE_REFRESHES = 4;
    /** A cave chunk the probe re-requested is not re-requested again for this long (pending or empty builds). */
    private static final long PROBE_CAVE_RETRY_MS = 10_000L;
    /** Last probe re-request per (cave view, chunk); pruned when large. Shared by all players' probes. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> PROBE_CAVE_TRIED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Re-requests up to {@link #PROBE_CAVE_REFRESHES} missing cave chunks, nearest to the player
     * first, skipping chunks re-requested in the last {@link #PROBE_CAVE_RETRY_MS} (still building,
     * failed or empty). Nearest-first keeps the area around the player fresh after mining.
     */
    private static void refreshCaveChunks(dev.scyphoz.dappermap.integration.BetterMapIntegration.CaveView view,
                                          long[] chunkHashes, ChunkRange range, int[] missing, int count,
                                          double centerX, double centerZ) {
        long now = System.currentTimeMillis();
        if (PROBE_CAVE_TRIED.size() > 8192) PROBE_CAVE_TRIED.values().removeIf(at -> now - at >= PROBE_CAVE_RETRY_MS);
        int centerChunkX = Math.floorDiv((int)Math.floor(centerX), CHUNK_SIZE);
        int centerChunkZ = Math.floorDiv((int)Math.floor(centerZ), CHUNK_SIZE);
        long viewKey = (long)view.hashCode() * 0x9E3779B97F4A7C15L;
        // Order the missing chunks by distance from the player (small list: one sort per probe).
        Integer[] order = new Integer[count];
        int[] distance = new int[count];
        for (int i = 0; i < count; ++i) {
            int dx = missing[i * 2] - centerChunkX, dz = missing[i * 2 + 1] - centerChunkZ;
            distance[i] = dx * dx + dz * dz;
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(distance[a], distance[b]));
        int refreshes = 0;
        for (int n = 0; n < count && refreshes < PROBE_CAVE_REFRESHES; ++n) {
            int chunkX = missing[order[n] * 2], chunkZ = missing[order[n] * 2 + 1];
            long key = viewKey ^ ((long)chunkX << 32 | (chunkZ & 0xFFFFFFFFL));
            Long tried = PROBE_CAVE_TRIED.get(key);
            if (tried != null && now - tried < PROBE_CAVE_RETRY_MS) continue; // Pending, failed or empty recently.
            PROBE_CAVE_TRIED.put(key, now);
            ++refreshes;
            try {
                MapImage image = view.apply(chunkX, chunkZ);
                if (image != null) chunkHashes[range.indexOf(chunkX, chunkZ)] = presentHash(image);
            } catch (Throwable ignored) {
                // Build failure: retried after the cooldown (BetterMapIntegration backs off on repeats).
            }
        }
    }

    /**
     * True when the probe found a chunk image that is new or different from the one shown.
     * A chunk that disappeared (probe 0: unloaded, cave TTL, cap eviction) is never a change: the
     * HUD still shows it correctly, and redrawing would only punch a hole. Nothing shown yet
     * ({@code shown} null) is a change only once some image exists; a different grid size always is.
     */
    public static boolean chunksChanged(long[] shown, long[] probe) {
        if (probe == null) return false; // No image source: nothing to compare.
        if (shown == null) {
            for (long hash : probe) if (hash != 0L) return true;
            return false;
        }
        if (shown.length != probe.length) return true;
        for (int i = 0; i < probe.length; ++i) {
            long hash = probe[i];
            if (hash != 0L && hash != shown[i]) return true;
        }
        return false;
    }

    /** Filter that never gates (no world, or the chunk store cannot be asked). */
    private static final LongPredicate ALWAYS_LOADED = index -> true;
    /** Set if the chunk-store lookup is missing on this server build; gating is then off. */
    private static volatile boolean chunkGateUnavailable;

    /**
     * Maps a world-map chunk index to whether its world chunk is loaded. Map chunks and ChunkStore
     * chunks are both 32 blocks and share ChunkUtil.indexChunk, so the index is used unchanged.
     * {@code ChunkStore.getChunkReference(long)} is a concurrent-map get plus a StampedLock
     * optimistic read with no thread assert, so the filter is safe on the render pool. Any doubt
     * (no world, no store, a lookup failure) answers true, i.e. today's ungated behaviour.
     */
    public static LongPredicate loadedChunkFilter(World world) {
        if (world == null || chunkGateUnavailable) return ALWAYS_LOADED;
        ChunkStore store;
        try {
            store = world.getChunkStore();
        } catch (RuntimeException | LinkageError unavailable) {
            return ALWAYS_LOADED;
        }
        if (store == null) return ALWAYS_LOADED;
        return index -> {
            try {
                return store.getChunkReference(index) != null;
            } catch (LinkageError missing) {
                chunkGateUnavailable = true; // Older/newer server without this lookup: stop gating.
                return true;
            } catch (RuntimeException unsure) {
                return true;
            }
        };
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
        /** Row-major index (z rows, x columns): the order of the per-chunk hash arrays. */
        int indexOf(int chunkX, int chunkZ) { return (chunkZ - this.minZ) * width() + (chunkX - this.minX); }
    }

    /**
     * Hash of what a chunk image looks like: its size and the colour of every pixel, so it does
     * not depend on palette order. Hytale builds the palette from a pooled hash set whose order
     * depends on which builder made the image, so a rebuild of an unchanged chunk often has the
     * same colours in a different palette order (and different packed indices). Hashing the raw
     * palette and indices made those rebuilds look like terrain changes and redrew the HUD each
     * time, which BetterMap's heavy image churn turned into repeated redraws after a teleport.
     * Each image is decoded once: the result is cached by image object (see {@link #PIXEL_HASHES}).
     */
    private static long chunkHash(MapImage image) {
        Long key = ((long) image.width << 48 | (long) image.height << 32) ^ (System.identityHashCode(image) & 0xFFFFFFFFL);
        PixelHash cached = PIXEL_HASHES.get(key);
        if (cached != null && cached.image.get() == image) return cached.hash; // Same image object: no decode.
        long hash = pixelHash(image);
        if (PIXEL_HASHES.size() >= PIXEL_HASH_CACHE_MAX) PIXEL_HASHES.clear(); // Bounded; refills from probes.
        PIXEL_HASHES.put(key, new PixelHash(new java.lang.ref.WeakReference<>(image), hash));
        return hash;
    }

    /** Cached pixel hash for one image object; weak, so cached entries never keep old images alive. */
    private record PixelHash(java.lang.ref.WeakReference<MapImage> image, long hash) {}
    /** Pixel hashes by image identity (identity hash mixed with size). Shared by all players and worlds. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, PixelHash> PIXEL_HASHES = new java.util.concurrent.ConcurrentHashMap<>();
    /** About 3x a full-size HUD buffer for several players; clearing just costs one decode per image. */
    private static final int PIXEL_HASH_CACHE_MAX = 16_384;

    /** Size plus every pixel's colour in order (palette order does not matter). 1 decode per image. */
    private static long pixelHash(MapImage image) {
        long hash = 31L * (31L + image.width) + image.height;
        int[] palette = image.palette;
        if (palette == null || palette.length == 0) return 31L * hash + 7L;
        int count = image.width * image.height;
        byte[] packed = image.packedIndices;
        int bits = Byte.toUnsignedInt(image.bitsPerIndex);
        if (packed != null && packed.length != 0 && bits > 0 && count > 0) {
            BitFieldArr indices = new BitFieldArr(bits, count);
            indices.set(packed);
            for (int i = 0; i < count; ++i) {
                int index = indices.get(i);
                hash = 31L * hash + (index >= 0 && index < palette.length ? palette[index] : palette[0]);
            }
        } else {
            for (int colour : palette) hash = 31L * hash + colour; // Unpacked: palette holds the pixels.
        }
        return hash;
    }

    /** chunkHash for a present image, never 0 (0 marks a missing chunk in the hash arrays). */
    private static long presentHash(MapImage image) {
        long hash = chunkHash(image);
        return hash == 0L ? 1L : hash;
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
        Graphics2D graphics = DapperMapUtil.antialiased(image);
        graphics.setColor(Color.WHITE);

        double inset = textureSize * APERTURE_INSET / STATIC_ASSET_LOGICAL_SIZE;
        double aperture = textureSize - 2 * inset;
        if (settings.isCircle()) {
            graphics.fill(new Ellipse2D.Double(inset, inset, aperture, aperture));
        } else {
            double corner = textureSize * APERTURE_CORNER / STATIC_ASSET_LOGICAL_SIZE;
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
        Graphics2D graphics = DapperMapUtil.antialiased(image);
        graphics.scale(scale, scale);

        if (bezel) {
            drawTexturedBezel(graphics, logicalSize, settings.isCircle(), colours);
        }

        if (compass) drawTexturedCompass(graphics, logicalSize, settings.isCircle(), bucketAngle(bucket), colours,
                settings.showCompassLetters, settings.showCompassDashes, settings.showCardinalDashes);
        graphics.dispose();
        return image;
    }

    /**
     * Logical layout of the HUD widget: the map and the info panel beside, above or below it
     * (logical HUD units). The one source for the live HUD markup and labels, the panel image and
     * the settings preview, so the three always line up. With the panel hidden the widget is the
     * map alone (panelHeight and gap 0).
     */
    public record HudLayout(int mapSize, int panelHeight, int gap, int width, int height,
                            int mapX, int mapY, int panelX, int panelY, boolean side) {
        /** Info panel width in every orientation. */
        public static final int PANEL_WIDTH = 148;
        private static final int PANEL_PADDING_V = 6, BRAND_HEIGHT = 24, ROW_HEIGHT = 43;
        private static final int CIRCLE_GAP = 8, SQUARE_GAP = 16;

        /** Layout for these settings and map size. */
        public static HudLayout of(DynamicMinimapSetting settings, int mapSize) {
            return settings.infoPanelShown()
                    ? withPanel(settings, mapSize)
                    : new HudLayout(mapSize, 0, 0, mapSize, mapSize, 0, 0, 0, 0, isSide(settings));
        }

        /**
         * Layout as if the panel were shown (even when it is switched off): the settings preview
         * keeps this footprint as its scale reference, so the map doesn't grow when the panel goes.
         */
        public static HudLayout withPanel(DynamicMinimapSetting settings, int mapSize) {
            int position = settings.infoPanelPosition;
            boolean side = isSide(settings);
            // Padding, the brand line, then one 43-unit row per readout shown.
            int panelHeight = PANEL_PADDING_V * 2 + BRAND_HEIGHT;
            for (int row = 0; row < 4; row++) if (readoutShown(settings, row)) panelHeight += ROW_HEIGHT;
            int gap = settings.isCircle() ? CIRCLE_GAP : SQUARE_GAP;
            int width = side ? mapSize + gap + PANEL_WIDTH : Math.max(mapSize, PANEL_WIDTH);
            int height = side ? Math.max(mapSize, panelHeight) : mapSize + gap + panelHeight;
            int mapX = position == DynamicMinimapSetting.INFO_LEFT ? PANEL_WIDTH + gap : side ? 0 : Math.max(0, (width - mapSize) / 2);
            int mapY = position == DynamicMinimapSetting.INFO_ABOVE ? panelHeight + gap
                    : position == DynamicMinimapSetting.INFO_BELOW ? 0 : Math.max(0, (height - mapSize) / 2);
            int panelX = position == DynamicMinimapSetting.INFO_RIGHT ? mapSize + gap : side ? 0 : Math.max(0, (width - PANEL_WIDTH) / 2);
            int panelY = position == DynamicMinimapSetting.INFO_BELOW ? mapSize + gap
                    : position == DynamicMinimapSetting.INFO_ABOVE ? 0 : Math.max(0, (height - panelHeight) / 2);
            return new HudLayout(mapSize, panelHeight, gap, width, height, mapX, mapY, panelX, panelY, side);
        }

        private static boolean isSide(DynamicMinimapSetting settings) {
            return settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT
                    || settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT;
        }

        /**
         * The panel's native text boxes: the brand (row -1), then a heading and a value per shown
         * readout row. Insets clear the curved panel edge of a round map beside it. Empty when the
         * panel is hidden.
         */
        public java.util.List<PanelLabel> panelLabels(DynamicMinimapSetting settings) {
            java.util.List<PanelLabel> labels = new java.util.ArrayList<>(9);
            if (!settings.infoPanelShown()) return labels;
            int position = settings.infoPanelPosition;
            boolean curved = settings.isCircle() && this.side;
            int brandCurve = curved ? 6 : 0;
            int brandLeft = 8 + (position == DynamicMinimapSetting.INFO_RIGHT ? brandCurve : 0);
            int brandRight = 8 + (position == DynamicMinimapSetting.INFO_LEFT ? brandCurve : 0);
            labels.add(new PanelLabel(-1, false, this.panelX + brandLeft, this.panelY + 12,
                    PANEL_WIDTH - brandLeft - brandRight, 15, 10, 0.35D, true));
            int cursor = this.panelY + 30;
            for (int row = 0; row < 4; row++) {
                if (!readoutShown(settings, row)) continue;
                int curve = curved ? (row == 0 || row == 3 ? 12 : 22) : 0;
                int left = 8 + (position == DynamicMinimapSetting.INFO_RIGHT ? curve : 0);
                int right = 8 + (position == DynamicMinimapSetting.INFO_LEFT ? curve : 0);
                int x = this.panelX + left, width = PANEL_WIDTH - left - right;
                labels.add(new PanelLabel(row, false, x, cursor + 3, width, 15, 9, 0.95D, false)); // Heading.
                labels.add(new PanelLabel(row, true, x, cursor + 19, width, 19, 11, 0.0D, false)); // Value.
                cursor += ROW_HEIGHT;
            }
            return labels;
        }
    }

    /**
     * One info-panel text box in logical HUD units. {@code row} -1 is the brand; otherwise the
     * readout row's heading ({@code value} false) or its value.
     */
    public record PanelLabel(int row, boolean value, int x, int y, int width, int height,
                             int fontSize, double letterSpacing, boolean bold) { }

    /** The info panel's glass on a widget-sized canvas (panel shown only). */
    private static BufferedImage renderHudChromeImage(DynamicMinimapSetting settings, HudLayout layout) {
        int panelX = layout.panelX(), panelY = layout.panelY();
        int panelWidth = HudLayout.PANEL_WIDTH, panelHeight = layout.panelHeight();
        BufferedImage image = new BufferedImage(Math.max(1, layout.width()), Math.max(1, layout.height()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = DapperMapUtil.antialiased(image);

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
                                 int resolution, int shape, boolean panel, int angle, String colours, int markings) {}
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

    /** Native value labels make the panel independent of changing telemetry. Layout: {@link HudLayout#of}. */
    public static NativeHudParts renderNativeHudParts(DynamicMinimapSetting settings, int mapSize, double angle) {
        return nativeHudParts(settings, mapSize, angle, false);
    }

    /**
     * The HUD parts only if every one is already cached, else null; never renders. Lets the
     * world thread publish a layout change at once when possible and hand a cache miss
     * (25-80 ms of drawing) to the render pool instead of stalling the world tick.
     */
    public static NativeHudParts cachedNativeHudParts(DynamicMinimapSetting settings, int mapSize, double angle) {
        return nativeHudParts(settings, mapSize, angle, true);
    }

    private static NativeHudParts nativeHudParts(DynamicMinimapSetting settings, int mapSize, double angle, boolean cachedOnly) {
        HudLayout layout = HudLayout.of(settings, mapSize);
        int flags = (settings.infoPanelShown() ? 16 : 0) | (settings.showCoordinates ? 1 : 0)
                | (settings.showCompass ? 2 : 0) | (settings.showClock ? 4 : 0) | (settings.showBiome ? 8 : 0);
        // The live panel is text-free glass (headings and values are native client labels), so text
        // alignment no longer changes its pixels: key it as 0 to share one cached panel per layout.
        var key = new RowLayerKey(new OverlayLayout(mapSize, layout.width(), layout.height(), layout.panelHeight(), layout.gap(),
                settings.shape, settings.infoPanelPosition, 0, flags, settings.colours), -2, "native");
        byte[] panel = OVERLAY_LAYERS.get(key);
        if (panel == null) {
            if (cachedOnly) return null;
            BufferedImage image = renderHudOverlayImage(settings, layout, 0, false);
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


    /** Crop to the map and retain the previous logical-size frame pixels. */
    private static byte[] frameLayerPng(DynamicMinimapSetting settings, int size, double angle, boolean bezel,
                                        boolean cachedOnly) {
        // Heading -1 marks the bezel, which is the same at every heading.
        var key = new FrameLayerKey(size, size, size, 0, 0, settings.textureResolution,
                settings.shape, settings.infoPanelShown(), bezel ? -1 : rotationBucket(angle), settings.colours.frameKey(),
                bezel ? 0 : settings.compassMarkings()); // The bezel never shows compass markings.
        byte[] cached = OVERLAY_LAYERS.get(key);
        if (cached != null || cachedOnly) return cached;
        try {
            // Draw from the frame image directly: encoding it as a PNG at texture resolution and
            // decoding it again (identical pixels) was the most expensive part of a new heading.
            BufferedImage decoded = renderFrameImage(settings, angle, bezel, !bezel);
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            if (!settings.infoPanelShown())
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            else {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
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
     * Every foreground HUD image element (frame and panel glass) in one coordinate space. The live
     * terrain, markers and player arrow are independent layers underneath this overlay. It holds no
     * text: brand, headings, values and N/E/S/W are native labels (on the HUD and in the preview).
     */
    private static BufferedImage renderHudOverlayImage(DynamicMinimapSetting settings, HudLayout layout,
                                                       double frameAngle, boolean includeFrame) {
        int mapSize = layout.mapSize();
        if (!settings.infoPanelShown()) {
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
            BufferedImage overlay = renderHudChromeImage(settings, layout);
            Graphics2D graphics = DapperMapUtil.antialiased(overlay);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            BufferedImage frame = includeFrame ? renderFrameImage(settings, frameAngle) : null;
            if (frame != null) {
                graphics.drawImage(frame, layout.mapX(), layout.mapY(), mapSize, mapSize, null);
            }
            graphics.dispose();
            return overlay;
        } catch (Exception ignored) {
            return null;
        }
    }


    /** One preview marker. {@code creature}: a creature icon or dot; {@code iconKey}: its creature type (icons only). */
    public record PreviewMarker(byte[] image, int x, int y, int size, boolean creature, String iconKey) { }
    /** The player HUD's terrain, telemetry and visible marker assets frozen when settings open. */
    public record LiveSnapshot(byte[] terrain, int mapSize, int bufferSize, int panX, int panY,
                               double selfAngle, double frameAngle, String coords,
                               String compass, String clock, String biome,
                               java.util.List<PreviewMarker> markers) { }

    /** Settings preview stage size (the page shows it at 272x216). */
    public static final int PREVIEW_STAGE_WIDTH = 360, PREVIEW_STAGE_HEIGHT = 286;

    /**
     * One native text label over the settings preview, in stage pixels (360x286). The page turns
     * these into Labels, so the preview's text uses Hytale's font just like the live HUD.
     * {@code alignment}: 0 start, 1 centre, 2 end.
     */
    public record PreviewText(String id, String text, double x, double y, double width, double height,
                              double fontSize, String colour, boolean bold, double letterSpacing, int alignment) { }

    /** The preview image plus the native labels drawn over it. */
    public record SettingsPreview(byte[] png, java.util.List<PreviewText> texts) { }

    public static SettingsPreview renderSettingsPreview(DynamicMinimapSetting settings, LiveSnapshot snapshot) {
        java.util.List<PreviewText> texts = new java.util.ArrayList<>(14);
        BufferedImage stage = settingsPreviewImage(settings, snapshot, texts);
        byte[] png = stage == null ? null : toPng(stage);
        return png == null ? null : new SettingsPreview(png, java.util.List.copyOf(texts));
    }

    /**
     * The settings preview stage (360x286, HUD centred), or null. Render pool only. When
     * {@code texts} is given it receives the panel and N/E/S/W labels, placed on the stage.
     */
    private static BufferedImage settingsPreviewImage(DynamicMinimapSetting settings, LiveSnapshot snapshot,
                                                      java.util.List<PreviewText> texts) {
        if (snapshot == null || snapshot.terrain() == null) return null;
        DynamicMinimapSetting preview = settings.copy();
        preview.normalize();
        int mapSize = DapperMapUtil.clamp(preview.sizePixels, DynamicMinimapSetting.SIZE_MIN, DynamicMinimapSetting.SIZE_MAX);
        // The live HUD's own layout, so the preview's map, panel and labels sit exactly as in game.
        HudLayout layout = HudLayout.of(preview, mapSize);

        // The overlay is composited directly (it used to be PNG encoded and decoded first).
        BufferedImage overlay = renderHudOverlayImage(preview, layout, snapshot.frameAngle(), true);
        if (overlay == null) return null;
        // Decoded terrain and icons for this snapshot, shared by every preview render of the page.
        DecodedPreview decoded = decodedPreview(snapshot);
        try {
            BufferedImage hud = new BufferedImage(overlay.getWidth(), overlay.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = DapperMapUtil.antialiased(hud);
            int mapX = layout.mapX(), mapY = layout.mapY();

            Shape oldClip = g.getClip();
            double apertureInset = mapSize * APERTURE_INSET / STATIC_ASSET_LOGICAL_SIZE;
            double apertureSize = mapSize - 2 * apertureInset;
            if (preview.isCircle()) g.setClip(new Ellipse2D.Double(mapX + apertureInset, mapY + apertureInset, apertureSize, apertureSize));
            else g.setClip(new RoundRectangle2D.Double(mapX + apertureInset, mapY + apertureInset, apertureSize, apertureSize, mapSize * APERTURE_CORNER / STATIC_ASSET_LOGICAL_SIZE, mapSize * APERTURE_CORNER / STATIC_ASSET_LOGICAL_SIZE));
            BufferedImage terrain = decoded.terrain(snapshot.terrain());
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
                        drawPreviewAsset(g, decoded, marker.image(), x, y, size);
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
                        if (image == null) image = renderDot(rgb);
                        drawPreviewAsset(g, decoded, image,
                                mapX + samples.get(i)[0] - size / 2.0,
                                mapY + samples.get(i)[1] - size / 2.0, size);
                    }
                }
            }
            g.setClip(oldClip);
            int arrowSize = Math.max(18, mapSize / 10);
            drawPreviewAsset(g, decoded, renderSelfArrow(snapshot.selfAngle(), preview.colours),
                    mapX + (mapSize - arrowSize) / 2, mapY + (mapSize - arrowSize) / 2,
                    arrowSize);
            g.drawImage(overlay, 0, 0, null);
            g.dispose();

            int stageW = PREVIEW_STAGE_WIDTH, stageH = PREVIEW_STAGE_HEIGHT;
            BufferedImage stage = new BufferedImage(stageW, stageH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = stage.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            // Keep the minimap at the same preview scale when the Info Panel is disabled.
            // The live HUD correctly collapses to the map alone, but using that smaller image
            // as the preview's scale reference made the map expand to fill the preview stage.
            // For preview scaling only, reserve the footprint the enabled panel would have used.
            int scaleReferenceWidth = hud.getWidth();
            int scaleReferenceHeight = hud.getHeight();
            if (!preview.infoPanelShown()) {
                HudLayout reference = HudLayout.withPanel(preview, mapSize);
                scaleReferenceWidth = reference.width();
                scaleReferenceHeight = reference.height();
            }
            double scale = Math.min((stageW - 8.0) / scaleReferenceWidth,
                    (stageH - 8.0) / scaleReferenceHeight);
            int w = Math.max(1, (int)Math.round(hud.getWidth() * scale));
            int h = Math.max(1, (int)Math.round(hud.getHeight() * scale));
            sg.drawImage(hud, (stageW - w) / 2, (stageH - h) / 2, w, h, null);
            sg.dispose();
            if (texts != null) {
                // Same stage transform as the HUD image above (hud pixels -> stage pixels).
                double scaleX = (double)w / hud.getWidth(), scaleY = (double)h / hud.getHeight();
                previewTexts(texts, preview, snapshot, layout, (stageW - w) / 2, (stageH - h) / 2, scaleX, scaleY);
            }
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
     * {@code texts}: the HUD's native labels, in the trimmed {@code hud} image's pixels.
     */
    private record PositionPreviewBase(BufferedImage hud, int scaleReference, java.util.List<PreviewText> texts) {}

    /** A cached base, valid for one snapshot and one draft (compared without its X/Y position). */
    private record CachedPositionBase(LiveSnapshot snapshot, DynamicMinimapSetting settings, PositionPreviewBase base) {}

    /** A few recent bases (one per open settings page, typically), so slider moves skip the HUD render. */
    private static final java.util.ArrayDeque<CachedPositionBase> POSITION_BASES = new java.util.ArrayDeque<>();
    private static final int POSITION_BASE_LIMIT = 4;

    /** Drops this (closed) page's cached preview bases; other players' open pages keep theirs. */
    public static void forgetPreview(LiveSnapshot snapshot) {
        if (snapshot == null) return;
        synchronized (POSITION_BASES) {
            POSITION_BASES.removeIf(cached -> cached.snapshot() == snapshot);
        }
        synchronized (DECODED_PREVIEWS) {
            DECODED_PREVIEWS.removeIf(cached -> cached.snapshot == snapshot);
        }
    }

    /**
     * Decoded images for one preview snapshot: its terrain PNG and the icon PNGs drawn over it,
     * keyed by array identity. Snapshots and the cached icon arrays are never mutated, so a decode
     * stays valid for the snapshot's life and previews no longer re-decode them on every render.
     * The images are only read (drawImage). Render pool only.
     */
    private static final class DecodedPreview {
        /** Icon arrays remembered per snapshot; new arrays beyond this just decode as before. */
        private static final int ICON_LIMIT = 64;
        final LiveSnapshot snapshot;
        private volatile BufferedImage terrain;
        private final java.util.IdentityHashMap<byte[], BufferedImage> icons = new java.util.IdentityHashMap<>();

        DecodedPreview(LiveSnapshot snapshot) { this.snapshot = snapshot; }

        /** The snapshot terrain, decoded once (a racing first decode is harmless). */
        BufferedImage terrain(byte[] png) {
            BufferedImage image = this.terrain;
            if (image == null) {
                image = DapperMapUtil.decodeImage(png);
                this.terrain = image;
                if (image != null) trimDecodedPreviews(); // Its size is only known now.
            }
            return image;
        }

        BufferedImage icon(byte[] png) {
            synchronized (this.icons) {
                BufferedImage image = this.icons.get(png);
                if (image != null) return image;
            }
            BufferedImage image = DapperMapUtil.decodeImage(png); // Decoded outside the lock.
            if (image != null) {
                synchronized (this.icons) {
                    if (this.icons.size() < ICON_LIMIT) this.icons.put(png, image);
                }
            }
            return image;
        }

        long pixels() {
            BufferedImage image = this.terrain;
            return image == null ? 0L : (long)image.getWidth() * image.getHeight();
        }
    }

    /** Recent snapshots' decodes (one per open settings page, typically), most recent first. */
    private static final java.util.ArrayDeque<DecodedPreview> DECODED_PREVIEWS = new java.util.ArrayDeque<>();
    private static final int DECODED_PREVIEW_LIMIT = 4;
    /** Terrain pixels kept decoded across snapshots (8M ARGB pixels = 32 MB; the default size is ~0.6M each). */
    private static final long DECODED_PREVIEW_PIXELS = 8L * 1024L * 1024L;

    private static DecodedPreview decodedPreview(LiveSnapshot snapshot) {
        synchronized (DECODED_PREVIEWS) {
            DecodedPreview found = null;
            for (DecodedPreview cached : DECODED_PREVIEWS) if (cached.snapshot == snapshot) { found = cached; break; }
            if (found != null) {
                if (DECODED_PREVIEWS.peekFirst() != found) { DECODED_PREVIEWS.remove(found); DECODED_PREVIEWS.addFirst(found); }
                return found;
            }
            found = new DecodedPreview(snapshot);
            DECODED_PREVIEWS.addFirst(found);
            trimDecodedPreviews();
            return found;
        }
    }

    /**
     * Drops the least recent decodes beyond the count or terrain-pixel budget (a 2048px buffer
     * decodes to 16 MB). The most recent entry always stays.
     */
    private static void trimDecodedPreviews() {
        synchronized (DECODED_PREVIEWS) {
            long pixels = 0L;
            for (DecodedPreview cached : DECODED_PREVIEWS) pixels += cached.pixels();
            while (DECODED_PREVIEWS.size() > 1 && (DECODED_PREVIEWS.size() > DECODED_PREVIEW_LIMIT
                    || pixels > DECODED_PREVIEW_PIXELS)) pixels -= DECODED_PREVIEWS.removeLast().pixels();
        }
    }

    /** Every public instance field of the settings, except the HUD position (the base does not depend on it). */
    /**
     * Renders the Position-tab preview as a complete screen rather than a close-up of the HUD.
     * The HUD image is the same settings preview used by the other tabs, scaled and placed from
     * the draft X/Y percentages. Position-only edits reuse the HUD image of the previous call.
     */
    public static SettingsPreview renderPositionPreview(DynamicMinimapSetting settings, LiveSnapshot snapshot) {
        if (snapshot == null) return null;
        DynamicMinimapSetting draft = settings.copy();
        PositionPreviewBase base = null;
        synchronized (POSITION_BASES) {
            for (CachedPositionBase cached : POSITION_BASES)
                if (cached.snapshot() == snapshot && cached.settings().sameExceptPosition(draft)) { base = cached.base(); break; }
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

    private static PositionPreviewBase positionPreviewBase(DynamicMinimapSetting settings, LiveSnapshot snapshot) {
        java.util.List<PreviewText> stageTexts = new java.util.ArrayList<>(14);
        BufferedImage hud = settingsPreviewImage(settings, snapshot, stageTexts);
        if (hud == null) return null;
        // The preview stage is a transparent canvas with the HUD centred inside it. Position the
        // visible HUD bounds, not that canvas; otherwise its transparent margins keep corner
        // selections near the centre.
        // Visible bounds {minX, minY, maxX + 1, maxY + 1} (any alpha above 0).
        int[] bounds = DapperMapUtil.opaqueBounds(hud, 0);
        if (bounds != null) hud = hud.getSubimage(bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1]);
        // Labels move with the trim: stage pixels -> trimmed HUD pixels (composePositionPreview places them).
        java.util.List<PreviewText> texts = bounds == null ? stageTexts
                : moveTexts(stageTexts, -bounds[0], -bounds[1], 1.0D, 1.0D);
        int scaleReference = Math.max(hud.getWidth(), hud.getHeight());
        if (!settings.infoPanelShown()) {
            // The disabled-panel map is deliberately smaller so its scale matches the enabled
            // preview. Do not enlarge it again after trimming: scale by the enabled HUD footprint.
            DynamicMinimapSetting reference = settings.copy();
            reference.infoPanelEnabled = true;
            if (!reference.infoPanelShown()) reference.showCoordinates = true; // A panel to measure.
            BufferedImage referenceHud = settingsPreviewImage(reference, snapshot, null);
            int[] referenceBounds = referenceHud == null ? null : DapperMapUtil.opaqueBounds(referenceHud, 0);
            if (referenceBounds != null) {
                scaleReference = Math.max(referenceBounds[2] - referenceBounds[0],
                        referenceBounds[3] - referenceBounds[1]);
            }
        }
        return new PositionPreviewBase(hud, scaleReference, texts);
    }

    /** Labels shifted by (dx, dy) and then scaled about the origin (x' = (x + dx) * scale). */
    private static java.util.List<PreviewText> moveTexts(java.util.List<PreviewText> texts, double dx, double dy,
                                                         double scaleX, double scaleY) {
        java.util.List<PreviewText> moved = new java.util.ArrayList<>(texts.size());
        for (PreviewText t : texts) {
            moved.add(new PreviewText(t.id(), t.text(), (t.x() + dx) * scaleX, (t.y() + dy) * scaleY,
                    t.width() * scaleX, t.height() * scaleY, t.fontSize() * scaleY, t.colour(), t.bold(),
                    t.letterSpacing() * scaleX, t.alignment()));
        }
        return moved;
    }

    /** The 360x286 screen with the HUD placed at the draft's X/Y percentages, as PNG plus its labels. */
    private static SettingsPreview composePositionPreview(PositionPreviewBase base, DynamicMinimapSetting settings) {
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
                    * DapperMapUtil.clamp(settings.hudPositionX, DynamicMinimapSetting.POSITION_MIN, DynamicMinimapSetting.POSITION_MAX) / 100.0D);
            int y = inset + (int) Math.round((height - inset * 2 - widgetHeight)
                    * DapperMapUtil.clamp(settings.hudPositionY, DynamicMinimapSetting.POSITION_MIN, DynamicMinimapSetting.POSITION_MAX) / 100.0D);

            BufferedImage screen = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = DapperMapUtil.antialiased(screen);
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
            byte[] png = toPng(screen);
            if (png == null) return null;
            // The HUD's labels, scaled and placed exactly like its image on the small screen.
            double scaleX = (double)widgetWidth / hud.getWidth(), scaleY = (double)widgetHeight / hud.getHeight();
            return new SettingsPreview(png, java.util.List.copyOf(
                    moveTexts(base.texts(), x / scaleX, y / scaleY, scaleX, scaleY)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void drawPreviewAsset(Graphics2D g, DecodedPreview decoded, byte[] png,
                                         double x, double y, int size) {
        if (png == null) return;
        BufferedImage image = decoded.icon(png);
        if (image != null) g.drawImage(image, (int)Math.round(x), (int)Math.round(y), size, size, null);
    }

    /** Info-panel headings, indexed like the readout rows (coordinates, bearing, world time, region). */
    public static final String[] READOUT_HEADINGS = {"COORDINATES", "BEARING", "WORLD TIME", "REGION"};
    /** Value text colour per readout row. */
    public static final DynamicMinimapColours.Key[] READOUT_COLOURS = {COORDS_TEXT, COMPASS_TEXT, CLOCK_TEXT, BIOME_TEXT};

    /** Whether readout row {@code i} (coordinates, bearing, world time, region) is shown. */
    public static boolean readoutShown(DynamicMinimapSetting settings, int row) {
        return switch (row) {
            case 0 -> settings.showCoordinates;
            case 1 -> settings.showCompass;
            case 2 -> settings.showClock;
            default -> settings.showBiome;
        };
    }

    /**
     * Side of a cardinal letter's badge in frame units (220 = the whole frame): 18 units, both
     * inside the ring beside the large dashes and out on the rim when those dashes are hidden.
     * The edge-marker clamp keeps clear of badges of this size.
     */
    public static final double CARDINAL_BADGE_SIZE = 18.0D;

    /** Letter size in frame units for the badge. */
    public static final double CARDINAL_FONT_SIZE = 13.0D;

    /**
     * Centre of cardinal letter {@code index} (0 N, 1 E, 2 S, 3 W) as a fraction (x, y) of the
     * frame, at the heading bucket the frame image is drawn for. Shared by the frame artwork (its
     * badges), the live HUD's native labels and the settings preview, so all three line up.
     */
    public static double[] cardinalLetterCentre(int index, double mapAngle, boolean circle, boolean cardinalDashes) {
        double center = STATIC_ASSET_LOGICAL_SIZE / 2.0D;
        double bearing = -Math.PI / 2 + index * Math.PI / 2 + bucketAngle(rotationBucket(mapAngle));
        double dx = Math.cos(bearing), dy = Math.sin(bearing);
        double divisor = circle ? 1 : Math.max(Math.abs(dx), Math.abs(dy));
        // Inside the ring beside the large dash, or (dashes hidden) out on the rim where the dash was.
        double inset = cardinalDashes ? 25.0D : CARDINAL_BADGE_SIZE / 2.0D + 0.5D;
        double radius = (center - inset) / divisor;
        return new double[]{(center + dx * radius) / STATIC_ASSET_LOGICAL_SIZE,
                (center + dy * radius) / STATIC_ASSET_LOGICAL_SIZE};
    }

    /**
     * Native labels for the settings preview: the info panel (same layout as the live HUD's labels)
     * and the N/E/S/W letters, converted from HUD pixels to stage pixels.
     */
    private static void previewTexts(java.util.List<PreviewText> out, DynamicMinimapSetting settings,
                                     LiveSnapshot snapshot, HudLayout layout,
                                     int offsetX, int offsetY, double scaleX, double scaleY) {
        int mapX = layout.mapX(), mapY = layout.mapY(), mapSize = layout.mapSize();
        java.util.function.DoubleUnaryOperator sx = value -> offsetX + value * scaleX;
        java.util.function.DoubleUnaryOperator sy = value -> offsetY + value * scaleY;
        if (settings.showCompassLetters) {
            boolean dashes = settings.showCardinalDashes;
            double box = CARDINAL_BADGE_SIZE * mapSize / STATIC_ASSET_LOGICAL_SIZE;
            double font = CARDINAL_FONT_SIZE * mapSize / STATIC_ASSET_LOGICAL_SIZE;
            String colour = settings.colours.hex(CARDINAL_TEXT);
            for (int i = 0; i < 4; i++) {
                double[] centre = cardinalLetterCentre(i, snapshot.frameAngle(), settings.isCircle(), dashes);
                double x = mapX + centre[0] * mapSize - box / 2, y = mapY + centre[1] * mapSize - box / 2;
                out.add(new PreviewText("PreviewCardinal" + i, CARDINAL_LETTERS[i], sx.applyAsDouble(x),
                        sy.applyAsDouble(y), box * scaleX, box * scaleY, font * scaleY, colour, true, 0, 1));
            }
        }
        // Brand, then heading + value per shown row: the same boxes as the live HUD's labels.
        String[] values = {snapshot.coords(), snapshot.compass(), snapshot.clock(), snapshot.biome()};
        int alignment = settings.infoPanelTextAlignment;
        for (PanelLabel label : layout.panelLabels(settings)) {
            String id, text;
            DynamicMinimapColours.Key colour;
            if (label.row() < 0) { id = "PreviewBrand"; text = "DapperMap"; colour = BRAND_TEXT; }
            else if (!label.value()) { id = "PreviewHeading" + label.row(); text = READOUT_HEADINGS[label.row()]; colour = INFO_HEADERS; }
            else {
                id = "PreviewValue" + label.row();
                text = values[label.row()] == null ? "" : values[label.row()];
                colour = READOUT_COLOURS[label.row()];
            }
            out.add(new PreviewText(id, text, sx.applyAsDouble(label.x()), sy.applyAsDouble(label.y()),
                    label.width() * scaleX, label.height() * scaleY, label.fontSize() * scaleY,
                    settings.colours.hex(colour), label.bold(), label.letterSpacing() * scaleX, alignment));
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
                appendMapCurve(edge, true, x0, cy, inset, spread, -1);
                edge.lineTo(x0, y1 - corner);
            }
            case DynamicMinimapSetting.INFO_LEFT -> {
                edge.moveTo(x1, y0 + corner);
                edge.lineTo(x1, cy - spread);
                appendMapCurve(edge, true, x1, cy, -inset, spread, -1);
                edge.lineTo(x1, y1 - corner);
            }
            case DynamicMinimapSetting.INFO_BELOW -> {
                edge.moveTo(x0 + corner, y0);
                edge.lineTo(cx - spread, y0);
                appendMapCurve(edge, false, y0, cx, inset, spread, -1);
                edge.lineTo(x1 - corner, y0);
            }
            case DynamicMinimapSetting.INFO_ABOVE -> {
                edge.moveTo(x0 + corner, y1);
                edge.lineTo(cx - spread, y1);
                appendMapCurve(edge, false, y1, cx, -inset, spread, -1);
                edge.lineTo(x1 - corner, y1);
            }
            default -> { return; }
        }
        graphics.draw(edge);
    }

    /**
     * The shallow inward curve where a circular map meets its info panel, shared by the panel
     * outline and its map-facing accent. {@code edge}: the panel side facing the map (x for a
     * side panel, y for one above or below; {@code vertical} true for a side panel). The curve
     * bulges by {@code inset} (signed, into the panel) about {@code centre} and runs from
     * centre + from*spread to centre - from*spread ({@code from} is 1 or -1).
     */
    private static void appendMapCurve(Path2D.Double path, boolean vertical, double edge, double centre,
                                       double inset, double spread, int from) {
        double reach = from * spread; // Signed half-length of the curve.
        if (vertical) {
            path.curveTo(edge + inset * 0.35D, centre + reach * 0.55D,
                    edge + inset, centre + reach * 0.35D, edge + inset, centre);
            path.curveTo(edge + inset, centre - reach * 0.35D,
                    edge + inset * 0.35D, centre - reach * 0.55D, edge, centre - reach);
        } else {
            path.curveTo(centre + reach * 0.55D, edge + inset * 0.35D,
                    centre + reach * 0.35D, edge + inset, centre, edge + inset);
            path.curveTo(centre - reach * 0.35D, edge + inset,
                    centre - reach * 0.55D, edge + inset * 0.35D, centre - reach, edge);
        }
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

        // The rounded panel outline, with the inward curve on the side facing the map.
        if (settings.infoPanelPosition == DynamicMinimapSetting.INFO_RIGHT) {
            path.moveTo(x0 + corner, y0);
            path.lineTo(x1 - corner, y0); path.quadTo(x1, y0, x1, y0 + corner);
            path.lineTo(x1, y1 - corner); path.quadTo(x1, y1, x1 - corner, y1);
            path.lineTo(x0 + corner, y1); path.quadTo(x0, y1, x0, y1 - corner);
            path.lineTo(x0, cy + spread);
            appendMapCurve(path, true, x0, cy, inset, spread, 1);
            path.lineTo(x0, y0 + corner); path.quadTo(x0, y0, x0 + corner, y0);
        } else if (settings.infoPanelPosition == DynamicMinimapSetting.INFO_LEFT) {
            path.moveTo(x0 + corner, y0);
            path.lineTo(x1 - corner, y0); path.quadTo(x1, y0, x1, y0 + corner);
            path.lineTo(x1, cy - spread);
            appendMapCurve(path, true, x1, cy, -inset, spread, -1);
            path.lineTo(x1, y1 - corner); path.quadTo(x1, y1, x1 - corner, y1);
            path.lineTo(x0 + corner, y1); path.quadTo(x0, y1, x0, y1 - corner);
            path.lineTo(x0, y0 + corner); path.quadTo(x0, y0, x0 + corner, y0);
        } else if (settings.infoPanelPosition == DynamicMinimapSetting.INFO_BELOW) {
            path.moveTo(x0 + corner, y0);
            path.lineTo(cx - spread, y0);
            appendMapCurve(path, false, y0, cx, inset, spread, -1);
            path.lineTo(x1 - corner, y0); path.quadTo(x1, y0, x1, y0 + corner);
            path.lineTo(x1, y1 - corner); path.quadTo(x1, y1, x1 - corner, y1);
            path.lineTo(x0 + corner, y1); path.quadTo(x0, y1, x0, y1 - corner);
            path.lineTo(x0, y0 + corner); path.quadTo(x0, y0, x0 + corner, y0);
        } else {
            path.moveTo(x0 + corner, y0);
            path.lineTo(x1 - corner, y0); path.quadTo(x1, y0, x1, y0 + corner);
            path.lineTo(x1, y1 - corner); path.quadTo(x1, y1, x1 - corner, y1);
            path.lineTo(cx + spread, y1);
            appendMapCurve(path, false, y1, cx, -inset, spread, 1);
            path.lineTo(x0 + corner, y1); path.quadTo(x0, y1, x0, y1 - corner);
            path.lineTo(x0, y0 + corner); path.quadTo(x0, y0, x0 + corner, y0);
        }
        path.closePath();
        return path;
    }

    /** Dot PNGs by colour: identical for every player, so each colour is drawn and encoded once. */
    private static final PngCache DOT_CACHE = new PngCache(64, 512 * 1024);

    public static byte[] renderDot(int rgb) {
        byte[] cached = DOT_CACHE.get(rgb);
        if (cached != null) return cached;
        byte[] png = drawDot(rgb);
        DOT_CACHE.put(rgb, png);
        return png;
    }

    private static byte[] drawDot(int rgb) {
        BufferedImage image = new BufferedImage(32, 32, 2);
        Graphics2D graphics = DapperMapUtil.antialiased(image);
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
        Graphics2D graphics = DapperMapUtil.antialiased(image);
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

    /**
     * The compass ring: small edge dashes, the large cardinal dashes and the dark badges behind
     * N/E/S/W, each optional. The letters themselves are native labels over this image (Hytale's
     * font). With the cardinal dashes hidden, the badges move out onto the rim in their place.
     */
    private static void drawTexturedCompass(Graphics2D g, int size, boolean circle, double angle,
                                            DynamicMinimapColours colours, boolean letters,
                                            boolean edgeDashes, boolean cardinalDashes) {
        double center = size / 2.0D;
        // Minor divisions sit on the inner rim; major bars project beyond the outer hairline.
        int divisions = circle ? 32 : 24;
        int cardinalStep = divisions / 4, diagonalStep = divisions / 8;
        for (int i = 0; edgeDashes && i < divisions; i++) {
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
            // Small dashes have their own colour (Compass Dashes); the large ones use Cardinal Dashes.
            drawRadialTick(g, (int)center, (int)center, outer, inner, bearing, width + 2, colours.awt(COMPASS_DASHES, 40));
            drawRadialTick(g, (int)center, (int)center, outer, inner, bearing, width, colours.awt(COMPASS_DASHES, 245));
        }
        if (!letters && !cardinalDashes) return;
        double badgeSize = CARDINAL_BADGE_SIZE;
        for (int i = 0; i < 4; i++) {
            double bearing = -Math.PI / 2 + i * Math.PI / 2 + angle;
            double dx = Math.cos(bearing), dy = Math.sin(bearing);
            double divisor = circle ? 1 : Math.max(Math.abs(dx), Math.abs(dy));
            if (letters) {
                // Same centre the native letter label uses (fraction of the frame -> frame units).
                double[] centre = cardinalLetterCentre(i, angle, circle, cardinalDashes);
                double x = centre[0] * size, y = centre[1] * size;
                Shape badge = new RoundRectangle2D.Double(x - badgeSize / 2, y - badgeSize / 2,
                        badgeSize, badgeSize, 6, 6);
                g.setColor(new Color(5, 10, 23));
                if (cardinalDashes) {
                    // Inside the ring: the badge joins the bezel and cuts an inward recess.
                    double join = (center - 10) / divisor;
                    g.setStroke(new BasicStroke(20, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new java.awt.geom.Line2D.Double(x, y, center + dx * join, center + dy * join));
                }
                g.fill(badge);
                g.setStroke(new BasicStroke(.6F));g.setColor(colours.awt(TICKS, 65));g.draw(badge);
            }
            if (cardinalDashes) {
                // Beside the letters the dash crosses the bezel; with the letters hidden it moves
                // inside the map like the other dashes (longer, so N/E/S/W stay distinct).
                double outer = letters ? 2.5 : 12.0, inner = letters ? 11.5 : 27.0;
                drawRadialTick(g, (int)center, (int)center, (center - outer + 0.7) / divisor,
                        (center - inner - 0.5) / divisor, bearing, 6.5F, new Color(3, 9, 20));
                drawRadialTick(g, (int)center, (int)center, (center - outer) / divisor,
                        (center - inner) / divisor, bearing, 3.6F, colours.awt(TICKS, 255));
                drawRadialTick(g, (int)center, (int)center, (center - outer - 0.5) / divisor,
                        (center - inner + 0.5) / divisor, bearing, 1.0F, new Color(235, 255, 255, 180));
            }
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

    /** Colour limit for full-colour terrain images (0 = off, the default); set from the config. */
    private static volatile int terrainColours = 0;

    /** Sets the terrain colour limit: 0 keeps full colour, otherwise 32..256 colours. */
    public static void setTerrainColours(int colours) {
        terrainColours = colours <= 0 ? 0 : Math.max(32, Math.min(256, colours));
    }

    /** Most translucent colours kept exact (the map background and its rotated corners). */
    private static final int MAX_TRANSLUCENT = 16;

    /** 5-bit-per-channel histogram bin of an opaque colour. */
    private static int colourBin(int argb) {
        return ((((argb >>> 16) & 0xFF) >>> 3) << 10) | ((((argb >>> 8) & 0xFF) >>> 3) << 5) | ((argb & 0xFF) >>> 3);
    }

    /**
     * A colour reduction ({@link #buildReduction}): one colour per box of 5-bit histogram bins, plus room for
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

    /*
     * Channel weights for colour error (roughly how strongly the eye separates red, green and
     * blue). Weighted squared RGB distance is used for every split and nearest-colour choice.
     */
    private static final int WEIGHT_R = 3, WEIGHT_G = 4, WEIGHT_B = 2;

    /**
     * Error-driven median cut over the opaque pixels of {@code argb}, then one refinement pass.
     * <ul>
     * <li>Each split goes to the box with the most colour error (pixels x spread), not the most
     * pixels. Splitting by pixel count spent the whole budget on large areas (ocean and grass
     * shades) and left small, distinct colours (roofs, sand, coloured leaves, farmland) sharing a
     * box with a dominant neighbour. They then showed as that box's average: purple roofs went
     * grey-blue, teal leaves blue, brown farmland green, so the HUD didn't match the world map.</li>
     * <li>The split point along the box's widest (weighted-variance) channel is the one that
     * leaves the least error on both sides.</li>
     * <li>Every bin is then moved to its nearest palette colour and the colours re-averaged once
     * (one k-means pass), so no bin keeps a far-off box average.</li>
     * </ul>
     * {@code extraTranslucent} palette entries are reserved for exact translucent colours. Null
     * when the pixels hold more than {@link #MAX_TRANSLUCENT} translucent colours.
     * Costs about the same as the old pixel-count cut (a few ms per 768px terrain), and PNGs come
     * out slightly smaller because neighbouring pixels share colours more often.
     */
    static ColourReduction buildReduction(int[] argb, int colours, int extraTranslucent) {
        int[] count = new int[32768];
        long[] sumR = new long[32768], sumG = new long[32768], sumB = new long[32768];
        int[] translucent = new int[MAX_TRANSLUCENT];
        int translucentCount = 0;
        // Histogram of opaque colours on 5-bit bins; translucent colours counted exactly.
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
        // Occupied bins as parallel arrays: bin index, pixel count and mean colour.
        int[] bins = new int[occupied], pixels = new int[occupied];
        float[] meanR = new float[occupied], meanG = new float[occupied], meanB = new float[occupied];
        for (int bin = 0, i = 0; bin < count.length; bin++) {
            int c = count[bin];
            if (c == 0) continue;
            bins[i] = bin;
            pixels[i] = c;
            meanR[i] = (float)sumR[bin] / c;
            meanG[i] = (float)sumG[bin] / c;
            meanB[i] = (float)sumB[bin] / c;
            i++;
        }

        // Boxes are ranges of `order` (a permutation of the occupied bins).
        int budget = Math.max(1, colours - translucentSlots);
        int[] order = new int[occupied];
        for (int i = 0; i < occupied; i++) order[i] = i;
        int[] boxStart = new int[budget], boxEnd = new int[budget];
        double[] boxError = new double[budget];
        int boxes = 1;
        boxEnd[0] = occupied;
        boxError[0] = Double.MAX_VALUE; // Always split first (unless it is a single bin).
        long[] sortKeys = new long[occupied];
        // Prefix sums over a sorted box: pixels, weighted channel sums, weighted sum of squares.
        double[] prefixN = new double[occupied + 1], prefixR = new double[occupied + 1],
                prefixG = new double[occupied + 1], prefixB = new double[occupied + 1], prefixQ = new double[occupied + 1];
        while (boxes < budget) {
            int pick = -1;
            for (int i = 0; i < boxes; i++)
                if (boxEnd[i] - boxStart[i] > 1 && boxError[i] > 0 && (pick < 0 || boxError[i] > boxError[pick])) pick = i;
            if (pick < 0) break; // Every box is exact (one bin or no spread): the image fits already.
            int start = boxStart[pick], end = boxEnd[pick], length = end - start;
            // Sort the box along its widest channel (means are >= 0, so float bits sort numerically).
            float[] axis = widestChannel(order, start, end, pixels, meanR, meanG, meanB);
            for (int i = start; i < end; i++) sortKeys[i] = ((long)Float.floatToIntBits(axis[order[i]]) << 32) | order[i];
            java.util.Arrays.sort(sortKeys, start, end);
            for (int i = start; i < end; i++) order[i] = (int)sortKeys[i];
            for (int i = start, k = 1; i < end; i++, k++) {
                int b = order[i];
                double n = pixels[b], r = meanR[b], g = meanG[b], bl = meanB[b];
                prefixN[k] = prefixN[k - 1] + n;
                prefixR[k] = prefixR[k - 1] + n * r;
                prefixG[k] = prefixG[k - 1] + n * g;
                prefixB[k] = prefixB[k - 1] + n * bl;
                prefixQ[k] = prefixQ[k - 1] + n * (WEIGHT_R * r * r + WEIGHT_G * g * g + WEIGHT_B * bl * bl);
            }
            // Error of a range = weighted sum of squares - |weighted sum|^2 / pixels.
            int split = 1;
            double best = Double.MAX_VALUE, leftError = 0, rightError = 0;
            for (int k = 1; k < length; k++) {
                double left = prefixQ[k] - weightedSquare(prefixR[k], prefixG[k], prefixB[k]) / prefixN[k];
                double right = (prefixQ[length] - prefixQ[k]) - weightedSquare(prefixR[length] - prefixR[k],
                        prefixG[length] - prefixG[k], prefixB[length] - prefixB[k]) / (prefixN[length] - prefixN[k]);
                if (left + right < best) { best = left + right; split = k; leftError = left; rightError = right; }
            }
            boxEnd[pick] = start + split;
            boxError[pick] = Math.max(0, leftError);
            boxStart[boxes] = start + split;
            boxEnd[boxes] = end;
            boxError[boxes] = Math.max(0, rightError);
            boxes++;
        }

        // Box of each bin, the boxes' mean colours, then one nearest-colour refinement pass.
        int[] binBox = new int[occupied];
        for (int box = 0; box < boxes; box++)
            for (int i = boxStart[box]; i < boxEnd[box]; i++) binBox[order[i]] = box;
        double[] boxR = new double[boxes], boxG = new double[boxes], boxB = new double[boxes];
        boxMeans(binBox, pixels, meanR, meanG, meanB, boxR, boxG, boxB);
        nearestBoxes(binBox, meanR, meanG, meanB, boxR, boxG, boxB);
        boxMeans(binBox, pixels, meanR, meanG, meanB, boxR, boxG, boxB);
        nearestBoxes(binBox, meanR, meanG, meanB, boxR, boxG, boxB);

        int[] boxColours = new int[boxes];
        for (int box = 0; box < boxes; box++)
            boxColours[box] = 0xFF000000 | channel(boxR[box]) << 16 | channel(boxG[box]) << 8 | channel(boxB[box]);
        // Bins map to their box (unseen bins stay -1, so apply() can detect them).
        short[] binToBox = new short[32768];
        java.util.Arrays.fill(binToBox, (short)-1);
        for (int i = 0; i < occupied; i++) binToBox[bins[i]] = (short)binBox[i];
        return new ColourReduction(boxColours, binToBox, translucentSlots);
    }

    /**
     * Reduces a full-colour terrain image to at most {@code colours} colours, or null when it
     * cannot (too many translucent colours). Opaque pixels are reduced by {@link #buildReduction};
     * translucent colours are kept exact. No dithering: flat areas stay flat, which keeps the
     * PNG small. Render pool only.
     */
    private static BufferedImage reduceColours(BufferedImage source, int[] argb, int colours) {
        ColourReduction reduction = buildReduction(argb, colours, 0);
        return reduction == null ? null : reduction.apply(argb, source.getWidth(), source.getHeight());
    }

    private static int indexOf(int[] values, int length, int value) {
        for (int i = 0; i < length; i++) if (values[i] == value) return i;
        return -1;
    }

    /** Weighted squared length of a channel-sum vector (the |sum|^2 term of a box's error). */
    private static double weightedSquare(double r, double g, double b) {
        return WEIGHT_R * r * r + WEIGHT_G * g * g + WEIGHT_B * b * b;
    }

    /** 0..255 channel from a mean. */
    private static int channel(double value) {
        return (int)Math.max(0, Math.min(255, Math.round(value)));
    }

    /** Mean array of the channel with the largest weighted pixel variance among order[start..end). */
    private static float[] widestChannel(int[] order, int start, int end, int[] pixels,
                                         float[] meanR, float[] meanG, float[] meanB) {
        double n = 0, r = 0, g = 0, b = 0, rr = 0, gg = 0, bb = 0;
        for (int i = start; i < end; i++) {
            int k = order[i];
            double w = pixels[k];
            n += w;
            r += w * meanR[k]; g += w * meanG[k]; b += w * meanB[k];
            rr += w * meanR[k] * meanR[k]; gg += w * meanG[k] * meanG[k]; bb += w * meanB[k] * meanB[k];
        }
        double red = WEIGHT_R * (rr - r * r / n), green = WEIGHT_G * (gg - g * g / n), blue = WEIGHT_B * (bb - b * b / n);
        return green >= red && green >= blue ? meanG : red >= blue ? meanR : meanB;
    }

    /** Pixel-weighted mean colour of each box (a box left empty by refinement keeps its colour). */
    private static void boxMeans(int[] binBox, int[] pixels, float[] meanR, float[] meanG, float[] meanB,
                                 double[] boxR, double[] boxG, double[] boxB) {
        int boxes = boxR.length;
        double[] n = new double[boxes], r = new double[boxes], g = new double[boxes], b = new double[boxes];
        for (int i = 0; i < binBox.length; i++) {
            int box = binBox[i];
            double w = pixels[i];
            n[box] += w; r[box] += w * meanR[i]; g[box] += w * meanG[i]; b[box] += w * meanB[i];
        }
        for (int box = 0; box < boxes; box++) {
            if (n[box] == 0) continue;
            boxR[box] = r[box] / n[box]; boxG[box] = g[box] / n[box]; boxB[box] = b[box] / n[box];
        }
    }

    /**
     * Moves every bin to its nearest box colour. The palette is sorted by green and searched
     * outward from the bin's green value, stopping once green alone is further than the best
     * match, so each bin checks only a handful of the (up to 256) colours.
     */
    private static void nearestBoxes(int[] binBox, float[] meanR, float[] meanG, float[] meanB,
                                     double[] boxR, double[] boxG, double[] boxB) {
        int boxes = boxR.length;
        if (boxes < 2) return;
        // Sort key: green in 1/65536 steps above the box index (box < 256 fits in 8 bits).
        long[] keys = new long[boxes];
        for (int box = 0; box < boxes; box++) keys[box] = Math.round(boxG[box] * 65536.0D) << 8 | box;
        java.util.Arrays.sort(keys);
        int[] sorted = new int[boxes];
        double[] sortedG = new double[boxes];
        for (int i = 0; i < boxes; i++) { sorted[i] = (int)(keys[i] & 0xFF); sortedG[i] = (keys[i] >> 8) / 65536.0D; }
        for (int i = 0; i < binBox.length; i++) {
            double r = meanR[i], g = meanG[i], b = meanB[i];
            int position = java.util.Arrays.binarySearch(sortedG, g);
            if (position < 0) position = -position - 1;
            double best = Double.MAX_VALUE;
            int bestBox = binBox[i];
            // Walk up and down the green-sorted palette until both directions are out of reach.
            for (int up = position, down = position - 1; up < boxes || down >= 0; ) {
                if (up < boxes) {
                    double dg = sortedG[up] - g, distance = WEIGHT_G * dg * dg;
                    if (distance >= best) up = boxes;
                    else {
                        int box = sorted[up++];
                        double dr = boxR[box] - r, db = boxB[box] - b;
                        distance += WEIGHT_R * dr * dr + WEIGHT_B * db * db;
                        if (distance < best) { best = distance; bestBox = box; }
                    }
                }
                if (down >= 0) {
                    double dg = g - sortedG[down], distance = WEIGHT_G * dg * dg;
                    if (distance >= best) down = -1;
                    else {
                        int box = sorted[down--];
                        double dr = boxR[box] - r, db = boxB[box] - b;
                        distance += WEIGHT_R * dr * dr + WEIGHT_B * db * db;
                        if (distance < best) { best = distance; bestBox = box; }
                    }
                }
            }
            binBox[i] = bestBox;
        }
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

    /**
     * One stitched terrain buffer.
     *
     * @param png         north-up PNG (null for player-up renders, which use {@code image})
     * @param image       the stitched raster, or null when no chunk was drawn
     * @param unloaded    missing chunks skipped because their world chunk was not loaded (no request made)
     * @param chunkHashes hash of each chunk image drawn, in grid order (0 = missing; null without an
     *                    image source). Never mutated after renderTerrain returns, so it can be handed between threads.
     */
    public record Terrain(byte[] png, BufferedImage image, int present, int total, int unloaded, long[] chunkHashes) {
        public boolean complete() {
            return this.total > 0 && this.present >= this.total;
        }
    }
    private static Color withAlpha(Color colour, int alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
    }

}

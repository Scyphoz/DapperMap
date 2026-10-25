package dev.scyphoz.dappermap.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Small helpers shared across DapperMap. Each one previously existed as a private copy in two
 * or more classes; keeping a single version here means a fix or optimisation lands everywhere.
 * Every static method is thread-safe; {@link ScanCache} instances belong to one thread.
 */
public final class DapperMapUtil {
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private DapperMapUtil() {
    }

    // ------------------------------------------------------------------------------------
    // Numbers and text
    // ------------------------------------------------------------------------------------

    /** {@code value} limited to {@code min..max}. */
    public static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    /** A logical HUD value in display units at {@code percent} interface scale (rounded). */
    public static int scaled(int logicalValue, int percent) {
        return (int) Math.round(logicalValue * percent / 100.0D);
    }

    /** Upper-case six digit "RRGGBB" for an RGB colour; same text as {@code %06X}, without String.format. */
    public static String hex6(int rgb) {
        char[] digits = new char[6];
        int value = rgb & 0xFFFFFF;
        for (int i = 5; i >= 0; i--) {
            digits[i] = HEX_DIGITS[value & 0xF];
            value >>>= 4;
        }
        return new String(digits);
    }

    /** Position of {@code value} in {@code names}, or -1 (exact, case-sensitive match). */
    public static int indexOf(String[] names, String value) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(value)) return i;
        return -1;
    }

    /** {@code names[index]}, or the first name when the index is out of range. */
    public static String nameAt(String[] names, int index) {
        return index >= 0 && index < names.length ? names[index] : names[0];
    }

    /** A player's UUID without dashes, used in their per-player asset paths ("anon" when unknown). */
    public static String playerSlug(UUID uuid) {
        return uuid == null ? "anon" : uuid.toString().replace("-", "");
    }

    /** Folds {@code text} into a 64-bit FNV-1a hash (start from {@link #FNV64_OFFSET}). */
    public static long fnv64(long hash, CharSequence text) {
        for (int i = 0; i < text.length(); i++) hash = (hash ^ text.charAt(i)) * 0x100000001b3L;
        return hash;
    }

    /** FNV-1a 64-bit offset basis, the start value for {@link #fnv64}. */
    public static final long FNV64_OFFSET = 0xcbf29ce484222325L;

    /**
     * An asset path with forward slashes and no leading slash, or null when it is blank or
     * contains "..". Only ever used to look assets up, never to build file paths.
     */
    public static String cleanAssetPath(String path) {
        if (path == null || path.isBlank() || path.contains("..")) return null;
        String clean = path.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        return clean;
    }

    // ------------------------------------------------------------------------------------
    // Bounded maps and caches
    // ------------------------------------------------------------------------------------

    /**
     * Access-ordered map that drops its least recently used entry beyond {@code limit}.
     * {@code synchronize}: wrap it for use from several threads (otherwise guard it yourself).
     */
    public static <K, V> Map<K, V> lruMap(int limit, boolean synchronize) {
        Map<K, V> map = new LinkedHashMap<>(Math.min(64, limit), 0.75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > limit; }
        };
        return synchronize ? Collections.synchronizedMap(map) : map;
    }

    /**
     * Puts into a cache that is simply emptied when it reaches {@code limit} entries (cheap
     * bounding for caches whose entries are all easy to rebuild). Replacing a key never empties it.
     */
    public static <K, V> void putBounded(Map<K, V> map, K key, V value, int limit) {
        if (map.size() >= limit && !map.containsKey(key)) map.clear();
        map.put(key, value);
    }

    /**
     * Per-scan cache that keeps only the entries used in the latest scan: look up the previous
     * scan's entry with {@link #previous}, record this scan's with {@link #keep}, then call
     * {@link #endScan}. Not thread-safe: one owner thread (the world thread for marker scans).
     */
    public static final class ScanCache<K, V> {
        private Map<K, V> current = new HashMap<>();
        private Map<K, V> next = new HashMap<>();

        /** The entry kept by the previous scan, or null. */
        public V previous(K key) { return this.current.get(key); }

        /** Keeps an entry for the next scan. */
        public void keep(K key, V value) { this.next.put(key, value); }

        /** Keeps the previous scan's entry (if any) without recomputing it. */
        public void carry(K key) {
            V value = this.current.get(key);
            if (value != null) this.next.put(key, value);
        }

        /** Ends a scan: entries not kept in it are dropped. */
        public void endScan() {
            Map<K, V> swap = this.current;
            this.current = this.next;
            this.next = swap;
            this.next.clear();
        }

        /** Forgets every entry. */
        public void clear() {
            this.current.clear();
            this.next.clear();
        }
    }

    // ------------------------------------------------------------------------------------
    // Threads and files
    // ------------------------------------------------------------------------------------

    /** Daemon threads with a fixed name, so DapperMap's threads never hold up a server stop. */
    public static ThreadFactory daemonThreads(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Writes {@code bytes} to a ".tmp" sibling, then moves it over {@code file} (atomically
     * where the file system supports it), so a crash mid-write never leaves half a file.
     */
    public static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------------------------
    // Images and assets
    // ------------------------------------------------------------------------------------

    /** Default deflate level for DapperMap's PNGs (Java's own default is 4). */
    public static final int DEFAULT_PNG_COMPRESSION = 5;
    /** Deflate level used by {@link #encodePng} (1 fastest .. 9 smallest); set from the config. */
    private static volatile int pngCompression = DEFAULT_PNG_COMPRESSION;

    /**
     * Sets the PNG deflate level (clamped to 1..9). Measured on map frames against Java's default
     * 4: level 5 is about 4-9% smaller for up to 20% more encode time, level 6 about 7-11% smaller
     * for up to twice the time, level 9 about 12% smaller for roughly 20 times the time.
     */
    public static void setPngCompression(int level) {
        pngCompression = clamp(level, 1, 9);
    }

    public static int pngCompression() {
        return pngCompression;
    }

    /**
     * PNG bytes for an image, or null on failure. Encodes in memory (plain
     * {@code ImageIO.write(image, "png", outputStream)} buffers through a temporary file on disk)
     * at the configured deflate level.
     */
    public static byte[] encodePng(BufferedImage image, int initialCapacity) {
        // Palette images: DapperMap's own writer (see IndexedPng). Anything it declines uses ImageIO.
        if (image.getColorModel() instanceof IndexColorModel && IndexedPng.VERIFIED) {
            try {
                byte[] png = IndexedPng.encode(image, initialCapacity, pngCompression);
                if (png != null) return png;
            } catch (RuntimeException ignored) {
                // Fall through to ImageIO.
            }
        }
        return encodePngImageIO(image, initialCapacity);
    }

    /** PNG bytes through ImageIO at the configured deflate level, or null on failure. */
    private static byte[] encodePngImageIO(BufferedImage image, int initialCapacity) {
        javax.imageio.ImageWriter writer = null;
        try {
            var writers = ImageIO.getImageWritersByFormatName("png");
            if (!writers.hasNext()) return null;
            writer = writers.next();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, initialCapacity));
            try (var stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
                // Java's PNG writer uses deflate level 9 - round(quality * 9).
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality((9 - pngCompression) / 9.0F);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (writer != null) writer.dispose();
        }
    }

    /**
     * Minimal PNG writer for palette images (colour type 3, bit depth 1/2/4/8).
     *
     * <p>Why: the JDK's PNG writer runs its adaptive row filters (Sub/Up/Average/Paeth) only on
     * palette images ({@code RowFilter.filterRow} returns filter 0 for every other colour type).
     * Filtering palette indices works against deflate: measured on map frames, filter None
     * gives 25-39% smaller files and encodes about twice as fast. Pixels are identical.
     *
     * <p>Translucent palette entries are written first (as the JDK does), so the tRNS chunk only
     * lists them. Images whose raster is not the plain packed layout return null (ImageIO is
     * used instead). Stateless and thread-safe; the Deflater is per call and always ended.
     */
    static final class IndexedPng {
        private static final byte[] SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        private static final int IDAT_CHUNK = 32768;
        private static final byte[] NO_DATA = new byte[0];
        /** Startup round-trip self-test passed; false keeps every image on ImageIO. */
        static final boolean VERIFIED = selfTest();

        private IndexedPng() {
        }

        /** PNG bytes, or null when the image is not a plainly laid out palette image. */
        static byte[] encode(BufferedImage image, int initialCapacity, int level) {
            if (!(image.getColorModel() instanceof IndexColorModel palette)) return null;
            int bits = palette.getPixelSize();
            if (bits != 1 && bits != 2 && bits != 4 && bits != 8) return null;
            int entries = palette.getMapSize();
            if (entries < 1 || entries > (1 << bits)) return null;
            int width = image.getWidth(), height = image.getHeight();
            int stride = (width * bits + 7) / 8;
            byte[] indices = packedIndices(image, bits, stride);
            if (indices == null) return null;

            // Palette order: translucent entries first (8-bit only; sub-byte palettes are tiny).
            int[] order = new int[entries]; // order[newIndex] = original index
            int translucent = 0;
            byte[] remap = null;
            if (bits == 8) {
                for (int i = 0; i < entries; i++) if (palette.getAlpha(i) < 255) order[translucent++] = i;
                int next = translucent;
                for (int i = 0; i < entries; i++) if (palette.getAlpha(i) == 255) order[next++] = i;
                boolean identity = true;
                for (int i = 0; i < entries; i++) identity &= order[i] == i;
                if (!identity) {
                    remap = new byte[256];
                    for (int i = 0; i < entries; i++) remap[order[i]] = (byte)i;
                }
            } else {
                for (int i = 0; i < entries; i++) {
                    order[i] = i;
                    if (palette.getAlpha(i) < 255) translucent = i + 1; // tRNS runs to the last translucent entry.
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, initialCapacity));
            out.writeBytes(SIGNATURE);
            byte[] header = new byte[13];
            putInt(header, 0, width);
            putInt(header, 4, height);
            header[8] = (byte)bits;
            header[9] = 3; // Colour type 3: palette. Compression, filter and interlace stay 0.
            chunk(out, "IHDR", header, 13);
            byte[] rgb = new byte[entries * 3];
            for (int i = 0; i < entries; i++) {
                int argb = palette.getRGB(order[i]);
                rgb[i * 3] = (byte)(argb >>> 16);
                rgb[i * 3 + 1] = (byte)(argb >>> 8);
                rgb[i * 3 + 2] = (byte)argb;
            }
            chunk(out, "PLTE", rgb, rgb.length);
            if (translucent > 0) {
                byte[] alphas = new byte[translucent];
                for (int i = 0; i < translucent; i++) alphas[i] = (byte)palette.getAlpha(order[i]);
                chunk(out, "tRNS", alphas, translucent);
            }

            // Image data: each scanline is filter byte 0 (None) followed by the packed indices.
            java.util.zip.Deflater deflater = new java.util.zip.Deflater(clamp(level, 1, 9));
            try {
                byte[] scanline = new byte[stride + 1];
                byte[] idat = new byte[IDAT_CHUNK];
                int used = 0;
                for (int y = 0; y < height; y++) {
                    int rowStart = y * stride;
                    if (remap == null) System.arraycopy(indices, rowStart, scanline, 1, stride);
                    else for (int x = 0; x < stride; x++) scanline[x + 1] = remap[indices[rowStart + x] & 0xFF];
                    deflater.setInput(scanline);
                    while (!deflater.needsInput()) {
                        used += deflater.deflate(idat, used, IDAT_CHUNK - used);
                        if (used == IDAT_CHUNK) { chunk(out, "IDAT", idat, used); used = 0; }
                    }
                }
                deflater.finish();
                while (!deflater.finished()) {
                    used += deflater.deflate(idat, used, IDAT_CHUNK - used);
                    if (used == IDAT_CHUNK) { chunk(out, "IDAT", idat, used); used = 0; }
                }
                if (used > 0) chunk(out, "IDAT", idat, used);
            } finally {
                deflater.end();
            }
            chunk(out, "IEND", NO_DATA, 0);
            return out.toByteArray();
        }

        /**
         * The raster's packed index bytes when they are laid out exactly as PNG scanlines
         * (one bank, no offsets, no sub-image translation, MSB-first rows of {@code stride}
         * bytes), else null.
         */
        private static byte[] packedIndices(BufferedImage image, int bits, int stride) {
            java.awt.image.WritableRaster raster = image.getRaster();
            if (raster.getMinX() != 0 || raster.getMinY() != 0
                    || raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0
                    || raster.getNumBands() != 1) return null;
            if (!(raster.getDataBuffer() instanceof java.awt.image.DataBufferByte buffer)
                    || buffer.getNumBanks() != 1 || buffer.getOffset() != 0) return null;
            java.awt.image.SampleModel model = raster.getSampleModel();
            if (model instanceof java.awt.image.MultiPixelPackedSampleModel packed) {
                if (packed.getPixelBitStride() != bits || packed.getScanlineStride() != stride
                        || packed.getDataBitOffset() != 0) return null;
            } else if (model instanceof java.awt.image.PixelInterleavedSampleModel interleaved) {
                if (bits != 8 || interleaved.getPixelStride() != 1 || interleaved.getScanlineStride() != stride
                        || interleaved.getBandOffsets()[0] != 0) return null;
            } else {
                return null;
            }
            byte[] data = buffer.getData();
            return data.length >= (long)stride * image.getHeight() ? data : null;
        }

        private static void putInt(byte[] target, int offset, int value) {
            target[offset] = (byte)(value >>> 24);
            target[offset + 1] = (byte)(value >>> 16);
            target[offset + 2] = (byte)(value >>> 8);
            target[offset + 3] = (byte)value;
        }

        /** One PNG chunk: length, type, data, CRC-32 of type and data. */
        private static void chunk(ByteArrayOutputStream out, String type, byte[] data, int length) {
            byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] number = new byte[4];
            putInt(number, 0, length);
            out.writeBytes(number);
            out.writeBytes(typeBytes);
            out.write(data, 0, length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(typeBytes);
            crc.update(data, 0, length);
            putInt(number, 0, (int)crc.getValue());
            out.writeBytes(number);
        }

        /**
         * Encodes small 1/2/4/8-bit test images (odd widths, translucent entries in the middle
         * of the palette) and checks that ImageIO decodes exactly the same pixels.
         */
        private static boolean selfTest() {
            try {
                int[] colours = {0xFF102030, 0x800A1218, 0xFFE0C040, 0x00000000, 0xEB0A1218, 0xFF4F8A3A,
                        0xFFFFFFFF, 0x40FF0000, 0xFF000000, 0xFF123456, 0xFF654321, 0xFFABCDEF,
                        0xFF00FF00, 0x7F7F7F7F, 0xFF0000FF, 0xFFFF00FF};
                for (int bits : new int[]{1, 2, 4, 8}) {
                    int entries = Math.min(1 << bits, colours.length);
                    var model = new IndexColorModel(bits, entries, colours, 0, true, -1,
                            java.awt.image.DataBuffer.TYPE_BYTE);
                    BufferedImage image = new BufferedImage(37, 5,
                            bits == 8 ? BufferedImage.TYPE_BYTE_INDEXED : BufferedImage.TYPE_BYTE_BINARY, model);
                    var raster = image.getRaster();
                    for (int y = 0; y < 5; y++)
                        for (int x = 0; x < 37; x++) raster.setSample(x, y, 0, (x * 7 + y * 3) % entries);
                    byte[] png = encode(image, 256, DEFAULT_PNG_COMPRESSION);
                    if (png == null) return false;
                    BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(png));
                    if (decoded == null) return false;
                    for (int y = 0; y < 5; y++)
                        for (int x = 0; x < 37; x++)
                            if (decoded.getRGB(x, y) != image.getRGB(x, y)) return false;
                }
                return true;
            } catch (Exception | LinkageError failed) {
                return false;
            }
        }
    }

    /** A decoded PNG (or other ImageIO image), or null when it is missing or unreadable. */
    public static BufferedImage decodeImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Graphics for {@code image} with anti-aliasing on; the caller disposes it. */
    public static java.awt.Graphics2D antialiased(BufferedImage image) {
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        return graphics;
    }

    /**
     * Backing pixel array of a plain TYPE_INT_ARGB image (one bank, no offsets, not a sub-image),
     * else null. Its values are exactly what getRGB returns (non-premultiplied ARGB).
     */
    public static int[] backingArgb(BufferedImage image) {
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
    public static int[] argbPixels(BufferedImage image) {
        int[] backing = backingArgb(image);
        if (backing != null) return backing;
        int width = image.getWidth(), height = image.getHeight();
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    /**
     * Bounds {minX, minY, maxX + 1, maxY + 1} of the pixels whose alpha is above
     * {@code minAlpha}, or null when there are none. Reads the pixels once (not getRGB per pixel).
     */
    public static int[] opaqueBounds(BufferedImage image, int minAlpha) {
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = argbPixels(image);
        int minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                if ((pixels[rowStart + x] >>> 24) <= minAlpha) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                maxY = y;
            }
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX + 1, maxY + 1};
    }

    /**
     * A PNG with every pixel's colour multiplied by {@code rgb} (alpha kept), or null when the
     * source can't be decoded. Used for tinted world-map and BetterMap marker icons; callers cache it.
     */
    public static byte[] tintPng(byte[] source, int rgb) {
        BufferedImage image = decodeImage(source);
        if (image == null) return null;
        try {
            int tintR = (rgb >>> 16) & 0xFF, tintG = (rgb >>> 8) & 0xFF, tintB = rgb & 0xFF;
            int width = image.getWidth(), height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            for (int i = 0; i < pixels.length; i++) {
                int argb = pixels[i];
                int r = ((argb >>> 16) & 0xFF) * tintR / 255;
                int g = ((argb >>> 8) & 0xFF) * tintG / 255;
                int b = (argb & 0xFF) * tintB / 255;
                pixels[i] = (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
            BufferedImage tinted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            tinted.setRGB(0, 0, width, height, pixels, 0, width);
            return encodePng(tinted, 2048);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** RGB (0xRRGGBB) of a protocol colour, as sent by tint components and the colour picker. */
    public static int rgb(com.hypixel.hytale.protocol.Color colour) {
        return (Byte.toUnsignedInt(colour.red) << 16) | (Byte.toUnsignedInt(colour.green) << 8)
                | Byte.toUnsignedInt(colour.blue);
    }

    // ------------------------------------------------------------------------------------
    // Rate-limited failure logging (the per-tick paths catch everything to keep running)
    // ------------------------------------------------------------------------------------

    /** DapperMap's one logger (every class logs through the helpers below). */
    private static final com.hypixel.hytale.logger.HytaleLogger LOGGER = com.hypixel.hytale.logger.HytaleLogger.get("DapperMap");
    /** Keys already logged by {@link #logInfoOnce}; bounded, so a flood of new keys stops logging. */
    private static final Set<String> LOGGED_ONCE = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> FAILURES_LOGGED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong FAILURES = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Logs an unexpected failure caught in a hot path, with its stack, at most once a minute per
     * place and exception type (a bug that throws every tick would otherwise flood the log, or
     * silently freeze the minimap). A stopped world's rejection is expected and not logged.
     */
    public static void logFailure(String where, Throwable failure) {
        if (failure == null || failure instanceof IllegalThreadStateException
                || failure.getCause() instanceof IllegalThreadStateException) return;
        FAILURES.incrementAndGet();
        String key = where + ":" + failure.getClass().getName();
        long now = System.currentTimeMillis();
        Long last = FAILURES_LOGGED.get(key);
        if (last != null && now - last < 60_000L) return;
        if (FAILURES_LOGGED.size() >= 256) FAILURES_LOGGED.clear(); // Bounded; only affects muting.
        FAILURES_LOGGED.put(key, now);
        LOGGER.at(java.util.logging.Level.WARNING).withCause(failure).log(
                "DapperMap: %s failed (repeats of this error are muted for 60 s)", where);
    }

    /** Failures counted by {@link #logFailure} since startup (for /dappermap admin stats). */
    public static long failureCount() { return FAILURES.get(); }

    /** Logs an information line as it is (no formatting). */
    public static void logInfo(String message) {
        LOGGER.at(java.util.logging.Level.INFO).log(message);
    }

    /**
     * Logs a printf-style information line once per {@code key} per server run (at most 512 keys),
     * e.g. a missing marker icon or a creature with no model face.
     */
    public static void logInfoOnce(String key, String format, Object... args) {
        if (LOGGED_ONCE.size() < 512 && LOGGED_ONCE.add(key)) {
            LOGGER.at(java.util.logging.Level.INFO).logVarargs(format, args);
        }
    }

    /**
     * True for the rejection a stopped world gives when work is posted to it (the server wraps
     * it in SkipSentryException). Callers drop that work quietly.
     */
    public static boolean isStoppedWorld(Throwable failure) {
        return failure instanceof IllegalThreadStateException
                || (failure instanceof com.hypixel.hytale.logger.sentry.SkipSentryException
                && failure.getCause() instanceof IllegalThreadStateException);
    }

    /** Content hash of a common asset (kept by the registry; nothing is read), or null. */
    public static String commonAssetHash(String name) {
        if (name == null) return null;
        try {
            CommonAsset asset = CommonAssetRegistry.getByName(name);
            return asset == null ? null : asset.getHash();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Bytes of a Hytale common asset (including mod-pack assets), or null when absent, empty or
     * not loaded in time. Asset blobs are only weakly cached and may reload asynchronously, so
     * this waits up to {@code timeoutMs}: render pool only, never on a world thread.
     */
    public static byte[] commonAssetBytes(String name, long timeoutMs) {
        try {
            CommonAsset asset = CommonAssetRegistry.getByName(name);
            if (asset == null) return null;
            byte[] bytes = asset.getBlob().get(timeoutMs, TimeUnit.MILLISECONDS);
            return bytes == null || bytes.length == 0 ? null : bytes;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------
    // Read-only JSON helpers (asset JSON: roles, models)
    // ------------------------------------------------------------------------------------

    /** The value as an object, or null. */
    public static JsonObject jsonObject(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /** The value as a string, or null when it is not a JSON string. */
    public static String jsonString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    /** The value as a boolean, or null when it is not a JSON boolean. */
    public static Boolean jsonBool(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : null;
    }

    /** The value as a number, or {@code fallback} when it is not a JSON number. */
    public static double jsonNumber(JsonElement value, double fallback) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsDouble() : fallback;
    }

    /** True when the node has {@code "Enabled": false}. */
    public static boolean jsonDisabled(JsonObject node) {
        return Boolean.FALSE.equals(jsonBool(node.get("Enabled")));
    }

    /** Test applied to each object node by {@link #jsonAny}; {@code depth} is the node's depth. */
    @FunctionalInterface
    public interface JsonTest {
        boolean test(JsonObject node, int depth);
    }

    /**
     * True when {@code test} matches any object node in the tree, searched depth first through
     * arrays and object values. Each array element and object value is one level deeper; nodes
     * deeper than {@code maxDepth} are not visited. {@code skipDisabled}: a node with
     * {@code "Enabled": false} (and everything under it) is skipped.
     */
    public static boolean jsonAny(JsonElement element, int depth, int maxDepth, boolean skipDisabled, JsonTest test) {
        if (element == null || depth > maxDepth) return false;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray())
                if (jsonAny(child, depth + 1, maxDepth, skipDisabled, test)) return true;
        } else if (element.isJsonObject()) {
            JsonObject node = element.getAsJsonObject();
            if (skipDisabled && jsonDisabled(node)) return false;
            if (test.test(node, depth)) return true;
            for (var entry : node.entrySet())
                if (jsonAny(entry.getValue(), depth + 1, maxDepth, skipDisabled, test)) return true;
        }
        return false;
    }

    /** Text for a quoted UI markup string: quotes and backslashes escaped, control characters as spaces. */
    public static String escapeUiText(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') escaped.append('\\');
            escaped.append(c < 32 ? ' ' : c);
        }
        return escaped.toString();
    }

    /** CustomUI horizontal alignment for 0 start, 1 centre, 2 end (anything else is start). */
    public static String alignmentName(int alignment) {
        return switch (alignment) { case 1 -> "Center"; case 2 -> "End"; default -> "Start"; };
    }

    /**
     * Appends one native Label (in display units) to UI markup: anchor box, escaped text, font
     * size and colour, optional bold and letter spacing (only when above 0), and alignment.
     * Shared by the HUD's info panel and compass letters and the settings preview.
     */
    public static void appendLabel(StringBuilder out, String id, int left, int top, int width, int height,
                                   String text, int fontSize, String colour, boolean bold,
                                   double letterSpacing, String alignment) {
        out.append("Label #").append(id).append(" { Anchor: (Left: ").append(left)
                .append(", Top: ").append(top).append(", Width: ").append(width)
                .append(", Height: ").append(height).append("); Text: \"").append(escapeUiText(text))
                .append("\"; Style: (FontSize: ").append(fontSize).append(", TextColor: ").append(colour);
        if (bold) out.append(", RenderBold: true");
        if (letterSpacing > 0.0D) out.append(", LetterSpacing: ").append(letterSpacing);
        out.append(", HorizontalAlignment: ").append(alignment).append(", VerticalAlignment: Center); } ");
    }
}

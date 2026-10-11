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
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Small helpers shared across DapperMap. Each one previously existed as a private copy in two
 * or more classes; keeping a single version here means a fix or optimisation lands everywhere.
 * Every method is stateless and thread-safe.
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

    /** A player's UUID without dashes, used in their per-player asset paths ("anon" when unknown). */
    public static String playerSlug(UUID uuid) {
        return uuid == null ? "anon" : uuid.toString().replace("-", "");
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

    // ------------------------------------------------------------------------------------
    // Rate-limited failure logging (the per-tick paths catch everything to keep running)
    // ------------------------------------------------------------------------------------

    private static final com.hypixel.hytale.logger.HytaleLogger LOGGER = com.hypixel.hytale.logger.HytaleLogger.get("DapperMap");
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
}

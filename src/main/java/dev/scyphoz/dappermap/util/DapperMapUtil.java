package dev.scyphoz.dappermap.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import java.awt.image.BufferedImage;
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

    /**
     * PNG bytes for an image, or null on failure. Encodes in memory: plain
     * {@code ImageIO.write(image, "png", outputStream)} buffers through a temporary file on disk.
     */
    public static byte[] encodePng(BufferedImage image, int initialCapacity) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, initialCapacity));
            try (var stream = new MemoryCacheImageOutputStream(out)) {
                if (!ImageIO.write(image, "PNG", stream)) return null;
            }
            return out.toByteArray();
        } catch (Exception ignored) {
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

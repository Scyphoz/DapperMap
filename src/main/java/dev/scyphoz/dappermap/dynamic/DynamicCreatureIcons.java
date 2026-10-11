package dev.scyphoz.dappermap.dynamic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import dev.scyphoz.dappermap.util.DapperMapUtil;
import static dev.scyphoz.dappermap.util.DapperMapUtil.jsonBool;
import static dev.scyphoz.dappermap.util.DapperMapUtil.jsonNumber;
import static dev.scyphoz.dappermap.util.DapperMapUtil.jsonObject;
import static dev.scyphoz.dappermap.util.DapperMapUtil.jsonString;

/**
 * Creature face icons for the optional "Icons" creature-marker style.
 *
 * <p>Each creature type gets one outlined face icon, built once on the render pool and shared
 * by every player; its outer edge takes the creature's threat colour (one variant per colour
 * actually seen). The image comes from, in order:
 * <ol>
 *   <li>a front-on render of the creature model's {@code Head} (or {@code Skull}) node and
 *       its children, plus any head attachments the NPC wears (eyes, hair, hats). Works for
 *       any creature with a model, including those added by mod packs;</li>
 *   <li>a head crop of Hytale's generated portrait ({@code Icons/ModelsGenerated/...}) or
 *       the model's own {@code Icon};</li>
 *   <li>a badge showing the creature's initials.</li>
 * </ol>
 * The world thread only derives a short key per creature (cached per entity until its model
 * changes); all asset loading and drawing happens on the render pool.
 */
public final class DynamicCreatureIcons {
    /** Logical HUD size of a creature icon on the default 220 px map (scaled with map size). */
    public static final int MARKER_SIZE = 20;
    /**
     * Published PNG size. Icons can be shown at up to ~32 UI units x the client's UI scale
     * (about 50-80 screen pixels), so 64 px keeps them sharp instead of being stretched.
     */
    private static final int ICON_PX = 64;
    /** Drawing canvas (2x the published size, downscaled for smooth edges). */
    private static final int CANVAS = ICON_PX * 2;
    /** Face raster before trimming; close to the final face size to keep texels crisp. */
    private static final int FACE_RASTER = 128;
    /**
     * Outline widths on the 128 px canvas: dark inner edge, then the threat-colour outer edge
     * (9 px = about 4.5 px on the published icon, so the colour reads at minimap size).
     */
    private static final int DARK_OUTLINE = 3, COLOUR_OUTLINE = 9;
    /** A face covering less than this share of its raster is treated as missing (e.g. invisible texture). */
    private static final double MIN_FACE_COVERAGE = 0.06;
    private static final long LOAD_TIMEOUT_MS = 2000L;
    private static final int DARK_EDGE = 0xFF14161E;
    private static final Color DISC = new Color(70, 78, 96, 235);
    private static final Color INITIALS = new Color(242, 242, 248, 255);

    /** Icon size for the current map: 20 units at the default 220 px map, up to 32 at 360 px. */
    public static int markerSize(DynamicMinimapSetting settings) {
        return Math.max(MARKER_SIZE, Math.min(32, Math.round(MARKER_SIZE * settings.sizePixels / 220.0F)));
    }

    /** Pale "off" phase of the attacking pulse: the attacking colour blended 60% towards white. */
    public static int pulseColour(int rgb) {
        int r = (rgb >>> 16) & 0xFF, g = (rgb >>> 8) & 0xFF, b = rgb & 0xFF;
        return ((r + (255 - r) * 3 / 5) << 16) | ((g + (255 - g) * 3 / 5) << 8) | (b + (255 - b) * 3 / 5);
    }
    private static final java.util.regex.Pattern ICON_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_\\-]{1,96}");

    /** What a creature looks like; its hash is the icon key. */
    record Attachment(String model, String texture) { }
    record Source(String role, String modelId, String model, String texture, List<Attachment> attachments) { }

    /** Icon key -> source description, filled on the world thread, read on the render pool. */
    private static final ConcurrentHashMap<String, Source> SOURCES = new ConcurrentHashMap<>();
    /** Bumped whenever SOURCES is cleared; per-viewer key caches then register their creatures again. */
    private static volatile int sourcesGeneration;
    /** A creature's uncoloured icon: 2x canvas PNG, and whether it is a face "sticker" or an initials disc. */
    private record Base(byte[] png, boolean sticker) { }
    /** Icon key -> uncoloured base (shared by all players). Bounded: ~10-15 KB each. */
    private static final Map<String, Base> BASES = boundedMap(256);
    /** Icon key + edge colour -> finished, hashed PNG (shared; per-player paths via withPath). ~3-6 KB each. */
    private static final Map<String, DynamicAssetPublisher.PreparedAsset> ICONS = boundedMap(512);
    /** Per-key locks while a base icon is built, so players meeting a new creature type together render it once. */
    private static final ConcurrentHashMap<String, Object> BUILDING = new ConcurrentHashMap<>();

    private static <V> Map<String, V> boundedMap(int limit) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) { return size() > limit; }
        });
    }

    private DynamicCreatureIcons() { }

    /**
     * Per-viewer cache of creature icon keys. A key is re-derived only when the entity's
     * Model object changes; only creatures shown in the latest scan are kept. World thread only.
     */
    public static final class KeyCache {
        /** {@code generation}: the SOURCES generation the key was registered in (see {@link #sourcesGeneration}). */
        private record Entry(Model model, String key, int generation) { }
        private Map<Ref<EntityStore>, Entry> current = new HashMap<>();
        private Map<Ref<EntityStore>, Entry> next = new HashMap<>();

        String key(Ref<EntityStore> ref, NPCEntity npc, ComponentAccessor<EntityStore> accessor) {
            Model model = null;
            try {
                ModelComponent component = accessor.getComponent(ref, ModelComponent.getComponentType());
                model = component == null ? null : component.getModel();
            } catch (RuntimeException ignored) { }
            Entry entry = this.current.get(ref);
            int generation = sourcesGeneration;
            // Re-register after SOURCES was cleared, so a creature that stayed in view keeps a resolvable key.
            if (entry == null || entry.model() != model || entry.generation() != generation)
                entry = new Entry(model, register(npc.getRoleName(), model), generation);
            this.next.put(ref, entry);
            return entry.key();
        }

        /** Keeps only creatures seen in the scan just finished. */
        void endScan() {
            var swap = this.current;
            this.current = this.next;
            this.next = swap;
            this.next.clear();
        }

        /** Forgets every creature (creatures hidden, or the player changed world). */
        void clear() {
            this.current.clear();
            this.next.clear();
        }
    }

    /** Describes a creature's appearance and returns its icon key. World thread; called rarely. */
    static String register(String role, Model model) {
        String modelId = null, modelPath = null, texture = null;
        List<Attachment> attachments = new ArrayList<>();
        if (model != null) {
            modelId = model.getModelAssetId();
            modelPath = model.getModel();
            texture = model.getTexture();
            ModelAttachment[] worn = model.getAttachments();
            if (worn != null) for (ModelAttachment part : worn)
                if (part != null && part.getModel() != null) attachments.add(new Attachment(part.getModel(), part.getTexture()));
        }
        Source source = new Source(role, modelId, modelPath, texture, List.copyOf(attachments));
        // 64-bit FNV-1a of the description: short, stable and safe in an asset path.
        long hash = 0xcbf29ce484222325L;
        for (char c : source.toString().toCharArray()) hash = (hash ^ c) * 0x100000001b3L;
        String key = Long.toHexString(hash);
        if (SOURCES.size() >= 4096 && !SOURCES.containsKey(key)) {
            SOURCES.clear(); // Bounded; keys re-register (KeyCache sees the new generation).
            sourcesGeneration++;
        }
        SOURCES.putIfAbsent(key, source);
        return key;
    }

    /**
     * Per-player asset for an icon key with its threat-colour edge, building the shared base
     * icon on first use and each colour variant only when that colour is needed. Render pool only.
     */
    /**
     * Returned by prepareAsset while another render thread is building this creature's base
     * icon. Not a failure: callers retry later without a back-off. Never sent to a client.
     */
    public static final DynamicAssetPublisher.PreparedAsset BUSY =
            DynamicAssetPublisher.prepare("UI/DapperMap/creature-busy.png", new byte[]{0});

    public static DynamicAssetPublisher.PreparedAsset prepareAsset(String key, String playerSlug, int rgb) {
        if (key == null || playerSlug == null) return null;
        String hex = DapperMapUtil.hex6(rgb);
        String variant = key + "-" + hex;
        DynamicAssetPublisher.PreparedAsset shared = ICONS.get(variant);
        if (shared == null) {
            Base base = BASES.get(key);
            if (base == null) {
                Source source = SOURCES.get(key); // Null when SOURCES was cleared (bounded).
                // One render per creature type. A second request doesn't wait (which would park
                // a shared render thread for the whole model render): it returns BUSY and the
                // caller simply asks again on a later projection.
                Object mine = new Object();
                if (BUILDING.putIfAbsent(key, mine) != null) return BUSY;
                try {
                    base = BASES.get(key);
                    if (base == null) {
                        if (source == null) return null;
                        // Saved from an earlier server run with the same model/texture files?
                        // Skips the 15-60 ms model render. The file name includes the assets'
                        // content hashes, so an updated asset pack redraws the icon.
                        String content = contentHash(source);
                        base = loadCachedBase(key, content);
                        if (base == null) {
                            base = renderBase(source);
                            if (base == null) return null;
                            storeCachedBase(key, content, base);
                        }
                        BASES.put(key, base);
                    }
                } finally {
                    BUILDING.remove(key, mine);
                }
            }
            shared = DynamicAssetPublisher.prepare("UI/DapperMap/creature-" + variant + ".png", colourise(base, rgb));
            if (shared == null) return null;
            ICONS.put(variant, shared);
        }
        return shared.withPath("UI/DapperMap/creature-" + playerSlug + "-" + variant + ".png");
    }

    /** Path prefix of every per-player creature icon ("...creature-<player>-<key>-<RRGGBB>.png"). */
    private static final String PLAYER_ICON_PREFIX = "UI/DapperMap/creature-";

    /**
     * The creature type (icon key) of a per-player creature icon path, or null for any other
     * path. Player slugs, keys and colours never contain '-', so the parts split cleanly.
     */
    public static String keyFromPath(String path) {
        if (path == null || !path.startsWith(PLAYER_ICON_PREFIX) || !path.endsWith(".png")) return null;
        String[] parts = path.substring(PLAYER_ICON_PREFIX.length(), path.length() - 4).split("-");
        return parts.length == 3 && !parts[1].isEmpty() ? parts[1] : null;
    }

    /**
     * PNG of a creature type's icon edged in {@code rgb}, for the settings preview's colour
     * samples. Reuses (and fills) the shared colour-variant cache, so a colour shown here is
     * ready if the live minimap needs it. Null when the icon can't be built. Render pool only.
     */
    public static byte[] previewIcon(String key, int rgb) {
        DynamicAssetPublisher.PreparedAsset asset = prepareAsset(key, "preview", rgb);
        return asset == null || asset == BUSY ? null : asset.data();
    }

    // ---------------------------------------------------------------------------------
    // Disk cache of uncoloured base icons (survives server restarts)
    // ---------------------------------------------------------------------------------

    /**
     * Bump when the icon drawing changes, so icons saved by older versions are replaced.
     * v2: names are "v2-<icon key>-<asset content hash>.png" (v1 files are pruned).
     */
    private static final String CACHE_VERSION = "v2";
    /** Largest file accepted from the cache (a base icon is ~10-15 KB). */
    private static final int CACHE_FILE_LIMIT = 512 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** Cache folder, or null when the disk cache is off. */
    private static volatile java.nio.file.Path cacheDir;
    private static volatile long cacheMaxBytes;
    /** Bytes currently in the cache folder; -1 until it is first scanned. */
    private static final java.util.concurrent.atomic.AtomicLong CACHE_BYTES = new java.util.concurrent.atomic.AtomicLong(-1);
    private static final java.util.concurrent.atomic.AtomicInteger CACHE_HITS = new java.util.concurrent.atomic.AtomicInteger();

    /** Called once from plugin setup. Face icons are saved under {@code dir}, up to {@code maxBytes}. */
    public static void configureDiskCache(java.nio.file.Path dir, boolean enabled, long maxBytes) {
        cacheDir = enabled ? dir : null;
        cacheMaxBytes = Math.max(1024L * 1024L, maxBytes);
        CACHE_BYTES.set(-1);
    }

    /** One-line cache summary for /dappermap admin stats. */
    public static String cacheSummary() {
        long disk = CACHE_BYTES.get();
        return "creature sources " + SOURCES.size() + ", base icons " + BASES.size()
                + ", icon variants " + ICONS.size()
                + (cacheDir == null ? ", disk cache off"
                : ", disk cache " + (disk < 0 ? "not yet used" : (disk / 1024) + " KB") + " (" + CACHE_HITS.get() + " loaded from disk)");
    }

    /**
     * 64-bit FNV-1a of the content hashes of a creature's model, texture and attachment
     * files (precomputed by the asset registry, so no file is read). Changes whenever an
     * asset pack update changes any of them. Render pool only.
     */
    static String contentHash(Source source) {
        long hash = 0xcbf29ce484222325L;
        hash = fnv(hash, DapperMapUtil.commonAssetHash(commonName(source.model())));
        hash = fnv(hash, DapperMapUtil.commonAssetHash(commonName(source.texture())));
        for (Attachment part : source.attachments()) {
            hash = fnv(hash, DapperMapUtil.commonAssetHash(commonName(part.model())));
            hash = fnv(hash, DapperMapUtil.commonAssetHash(commonName(part.texture())));
        }
        return Long.toHexString(hash);
    }

    private static long fnv(long hash, String text) {
        String value = text == null ? "-" : text;
        for (int i = 0; i < value.length(); i++) hash = (hash ^ value.charAt(i)) * 0x100000001b3L;
        return (hash ^ '|') * 0x100000001b3L; // Separator: "ab"+"c" never equals "a"+"bc".
    }

    private static boolean plainHex(String text) {
        if (text == null || text.isEmpty() || text.length() > 16) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    /** File for an icon key and content hash; null when the cache is off or either isn't plain hex. */
    private static java.nio.file.Path cacheFile(String key, String content) {
        java.nio.file.Path dir = cacheDir;
        // Never build paths from other text.
        if (dir == null || !plainHex(key) || !plainHex(content)) return null;
        return dir.resolve(CACHE_VERSION + "-" + key + "-" + content + ".png");
    }

    /** Saved face icon for this creature type and asset content, or null. Render pool only. */
    private static Base loadCachedBase(String key, String content) {
        java.nio.file.Path file = cacheFile(key, content);
        if (file == null) return null;
        try {
            if (!java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) > CACHE_FILE_LIMIT) return null;
            byte[] png = java.nio.file.Files.readAllBytes(file);
            if (png.length < PNG_SIGNATURE.length
                    || !java.util.Arrays.equals(png, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) return null;
            // Recently used icons are kept when the cache is pruned (oldest first).
            java.nio.file.Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            CACHE_HITS.incrementAndGet();
            return new Base(png, true);
        } catch (java.io.IOException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Saves a face icon. Initials badges are not saved: they can be the result of a model that
     * failed to load in time, and should get another chance after a restart. Render pool only.
     */
    private static void storeCachedBase(String key, String content, Base base) {
        java.nio.file.Path file = cacheFile(key, content);
        if (file == null || base == null || !base.sticker() || base.png().length > CACHE_FILE_LIMIT) return;
        try {
            // Atomic where the file system supports it, so a reader never sees half a file.
            DapperMapUtil.writeAtomically(file, base.png());
            // Drop this creature's icons drawn from older asset versions (rare: only on a store).
            try (var older = java.nio.file.Files.newDirectoryStream(file.getParent(), CACHE_VERSION + "-" + key + "-*.png")) {
                for (java.nio.file.Path stale : older) {
                    if (stale.equals(file)) continue;
                    long size = java.nio.file.Files.size(stale);
                    if (java.nio.file.Files.deleteIfExists(stale) && CACHE_BYTES.get() >= 0) CACHE_BYTES.addAndGet(-size);
                }
            }
            if (CACHE_BYTES.get() < 0) pruneCache(); // First write this run: measure the folder.
            else if (CACHE_BYTES.addAndGet(base.png().length) > cacheMaxBytes) pruneCache();
        } catch (java.io.IOException | RuntimeException ignored) {
            // A read-only or full disk only means icons are rendered again next time.
        }
    }

    /**
     * Measures the cache folder, removes icons saved by older versions, and deletes the
     * least recently used icons until it is under 80% of the size limit.
     */
    private static synchronized void pruneCache() {
        java.nio.file.Path dir = cacheDir;
        if (dir == null) return;
        record Entry(java.nio.file.Path path, long size, long modified) { }
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        try (var files = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) files::iterator) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".png") && !name.endsWith(".tmp")) continue;
                var attributes = java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                boolean staleTemp = name.endsWith(".tmp")
                        && System.currentTimeMillis() - attributes.lastModifiedTime().toMillis() > 60_000L;
                if (staleTemp || (!name.endsWith(".tmp") && !name.startsWith(CACHE_VERSION + "-"))) {
                    java.nio.file.Files.deleteIfExists(path); // Interrupted write or older icon style.
                    continue;
                }
                if (name.endsWith(".tmp")) continue; // Another render thread is writing it right now.
                entries.add(new Entry(path, attributes.size(), attributes.lastModifiedTime().toMillis()));
                total += attributes.size();
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            CACHE_BYTES.compareAndSet(-1, 0); // Don't re-list the folder on every store.
            return;
        }
        if (total > cacheMaxBytes) {
            entries.sort(java.util.Comparator.comparingLong(Entry::modified)); // Oldest first.
            long target = cacheMaxBytes * 8 / 10;
            for (Entry entry : entries) {
                if (total <= target) break;
                try {
                    java.nio.file.Files.deleteIfExists(entry.path());
                    total -= entry.size();
                } catch (java.io.IOException ignored) { }
            }
        }
        CACHE_BYTES.set(total);
    }

    /** Model face, then portrait crop, then initials; returned uncoloured. */
    static Base renderBase(Source source) {
        BufferedImage face = null;
        try { face = ModelFace.render(source); } catch (RuntimeException | LinkageError ignored) { }
        if (face == null) {
            try { face = portrait(source); } catch (RuntimeException | LinkageError ignored) { }
            reportFallback(source, face != null);
        }
        return base(face, initials(source));
    }

    /** Finished icon for one edge colour (used by tests and prepareAsset). */
    static byte[] renderIcon(Source source, int rgb) {
        Base base = renderBase(source);
        return base == null ? null : colourise(base, rgb);
    }

    /** Creature types already reported as falling back from a model face (logged once each). */
    private static final java.util.Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final com.hypixel.hytale.logger.HytaleLogger LOGGER =
            com.hypixel.hytale.logger.HytaleLogger.get("DapperMap");

    /** Logs once per creature type when no model face could be drawn (helps check mod packs). */
    private static void reportFallback(Source source, boolean portrait) {
        String name = source.role() != null ? source.role() : String.valueOf(source.modelId());
        if (REPORTED.size() < 256 && REPORTED.add(name)) {
            LOGGER.at(java.util.logging.Level.INFO).log(
                    "DapperMap: no model face for creature '%s' (model '%s'); using its %s icon.",
                    name, source.model(), portrait ? "portrait" : "initials");
        }
    }

    // ---------------------------------------------------------------------------------
    // Icon composition
    // ---------------------------------------------------------------------------------

    /**
     * Face icons are "stickers": the face trimmed to its outline, scaled to fill the icon and
     * edged dark here; the outer edge is added per threat colour by {@link #colourise}.
     * Creatures without a face image get a disc with their initials. Drawn at 2x.
     */
    static Base base(BufferedImage face, String initials) {
        BufferedImage trimmed = face == null ? null : trimToSquare(face);
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (trimmed != null) {
                // Leave room for both outlines around the scaled face.
                int margin = DARK_OUTLINE + COLOUR_OUTLINE + 2, inner = CANVAS - 2 * margin;
                g.drawImage(trimmed, margin, margin, inner, inner, null);
            } else {
                g.setColor(DISC);
                g.fillOval(6, 6, CANVAS - 12, CANVAS - 12);
                // Two letters at most; shrink the font until they fit inside the ring.
                int size = 60;
                Font font;
                FontMetrics metrics;
                do {
                    font = new Font(Font.SANS_SERIF, Font.BOLD, size);
                    metrics = g.getFontMetrics(font);
                } while (metrics.stringWidth(initials) > CANVAS - 44 && --size > 24);
                g.setFont(font);
                int x = (CANVAS - metrics.stringWidth(initials)) / 2;
                int y = (CANVAS - metrics.getHeight()) / 2 + metrics.getAscent();
                g.setColor(new Color(0, 0, 0, 140));
                g.drawString(initials, x + 2, y + 2);
                g.setColor(INITIALS);
                g.drawString(initials, x, y);
            }
        } finally {
            g.dispose();
        }
        if (trimmed != null) outline(canvas, DARK_OUTLINE, DARK_EDGE, null);
        byte[] png = DapperMapUtil.encodePng(canvas, 2048);
        return png == null ? null : new Base(png, trimmed != null);
    }

    /** Adds the threat-colour edge (sticker) or ring (initials disc), then downscales to ICON_PX. */
    static byte[] colourise(Base base, int rgb) {
        BufferedImage canvas;
        try {
            canvas = ImageIO.read(new ByteArrayInputStream(base.png()));
        } catch (Exception ignored) {
            return null;
        }
        if (canvas == null) return null;
        if (canvas.getType() != BufferedImage.TYPE_INT_ARGB) {
            BufferedImage converted = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = converted.createGraphics();
            g.drawImage(canvas, 0, 0, null);
            g.dispose();
            canvas = converted;
        }
        int argb = 0xFF000000 | (rgb & 0xFFFFFF);
        if (base.sticker()) {
            outline(canvas, COLOUR_OUTLINE, argb, exterior(canvas));
        } else {
            Graphics2D g = canvas.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(argb, true));
                g.setStroke(new BasicStroke(14F)); // Matches the thicker sticker edge.
                g.drawOval(9, 9, CANVAS - 19, CANVAS - 19);
            } finally {
                g.dispose();
            }
        }
        return DapperMapUtil.encodePng(scale(canvas, ICON_PX), 2048);
    }

    /** Crops to the visible pixels and centres them on a transparent square. */
    private static BufferedImage trimToSquare(BufferedImage image) {
        int[] box = opaqueBounds(image);
        if (box == null) return null;
        int width = box[2] - box[0], height = box[3] - box[1], side = Math.max(width, height);
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = square.createGraphics();
        try {
            g.drawImage(image.getSubimage(box[0], box[1], width, height), (side - width) / 2, (side - height) / 2, null);
        } finally {
            g.dispose();
        }
        return square;
    }

    /**
     * Transparent pixels connected to the canvas border (the outside of the silhouette), so
     * the threat colour edges the outline only and never fills see-through holes in a face.
     */
    private static boolean[] exterior(BufferedImage canvas) {
        int w = canvas.getWidth(), h = canvas.getHeight();
        int[] pixels = canvas.getRGB(0, 0, w, h, null, 0, w);
        boolean[] outside = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int i = 0; i < w * h; i++) {
            int x = i % w, y = i / w;
            if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && (pixels[i] >>> 24) == 0) { outside[i] = true; queue[tail++] = i; }
        }
        while (head < tail) { // Breadth-first flood fill over fully transparent pixels.
            int i = queue[head++], x = i % w, y = i / w;
            // Left, right, up, down (same order as before; no per-pixel array allocation).
            if (x > 0) tail = floodVisit(i - 1, pixels, outside, queue, tail);
            if (x < w - 1) tail = floodVisit(i + 1, pixels, outside, queue, tail);
            if (y > 0) tail = floodVisit(i - w, pixels, outside, queue, tail);
            if (y < h - 1) tail = floodVisit(i + w, pixels, outside, queue, tail);
        }
        return outside;
    }

    /** Marks a transparent, unvisited pixel as outside and queues it; returns the new queue tail. */
    private static int floodVisit(int pixel, int[] pixels, boolean[] outside, int[] queue, int tail) {
        if (!outside[pixel] && (pixels[pixel] >>> 24) == 0) {
            outside[pixel] = true;
            queue[tail++] = pixel;
        }
        return tail;
    }

    /**
     * Adds a solid edge of {@code width} pixels around everything visible on the canvas
     * (a square max-filter of the alpha channel, done separably: two cheap 1-D passes).
     * When {@code only} is given, only those pixels may receive the edge.
     */
    private static void outline(BufferedImage canvas, int width, int argb, boolean[] only) {
        int w = canvas.getWidth(), h = canvas.getHeight();
        int[] pixels = canvas.getRGB(0, 0, w, h, null, 0, w);
        int[] alpha = new int[w * h], horizontal = new int[w * h];
        for (int i = 0; i < pixels.length; i++) alpha[i] = pixels[i] >>> 24;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int best = 0;
                for (int dx = Math.max(0, x - width); dx <= Math.min(w - 1, x + width); dx++) best = Math.max(best, alpha[y * w + dx]);
                horizontal[y * w + x] = best;
            }
        int colour = argb & 0xFFFFFF;
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++) {
                int best = 0;
                for (int dy = Math.max(0, y - width); dy <= Math.min(h - 1, y + width); dy++) best = Math.max(best, horizontal[dy * w + x]);
                int i = y * w + x, a = pixels[i] >>> 24;
                if (best == 0 || a == 255 || (only != null && !only[i] && a == 0)) continue;
                // Composite the original pixel over the edge colour.
                int r = (((pixels[i] >>> 16) & 0xFF) * a + ((colour >>> 16) & 0xFF) * (255 - a)) / 255;
                int gr = (((pixels[i] >>> 8) & 0xFF) * a + ((colour >>> 8) & 0xFF) * (255 - a)) / 255;
                int b = ((pixels[i] & 0xFF) * a + (colour & 0xFF) * (255 - a)) / 255;
                int outA = Math.max(a, best);
                pixels[i] = (outA << 24) | (r << 16) | (gr << 8) | b;
            }
        canvas.setRGB(0, 0, w, h, pixels, 0, w);
    }

    /** "Wyvern_Fire" -> "WF", "SkeletonArcher" -> "SA", "Yeti" -> "Ye", unknown -> "?". */
    static String initials(Source source) {
        String name = source.role() != null && !source.role().isBlank() ? source.role() : source.modelId();
        if (name == null || name.isBlank()) return "?";
        String spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2");
        List<String> words = new ArrayList<>();
        for (String word : spaced.split("[_\\-\\s.]+")) if (!word.isEmpty() && Character.isLetterOrDigit(word.charAt(0))) words.add(word);
        if (words.isEmpty()) return "?";
        if (words.size() == 1) {
            String word = words.get(0);
            return word.length() == 1 ? word.toUpperCase(Locale.ROOT)
                    : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1, 2).toLowerCase(Locale.ROOT);
        }
        return (words.get(0).substring(0, 1) + words.get(1).substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    /**
     * Head crop of a generated portrait. Portraits are side-on full-body renders facing left:
     * upright creatures keep the top square, long creatures the left (head) square.
     */
    static BufferedImage portrait(Source source) {
        List<String> paths = new ArrayList<>();
        for (String name : new String[]{source.role(), source.modelId()})
            if (name != null && ICON_NAME.matcher(name).matches()) paths.add("Icons/ModelsGenerated/" + name + ".png");
        if (source.modelId() != null) {
            try {
                ModelAsset asset = ModelAsset.getAssetMap().getAsset(source.modelId());
                String icon = asset == null ? null : asset.getIcon();
                if (icon != null && !icon.contains("..")) paths.add(icon.replace('\\', '/'));
            } catch (RuntimeException ignored) { }
        }
        for (String path : paths) {
            BufferedImage image = readImage(path);
            if (image == null) continue;
            int[] box = opaqueBounds(image);
            if (box == null) continue;
            int width = box[2] - box[0], height = box[3] - box[1];
            int side = Math.min(width, height);
            return image.getSubimage(box[0], box[1], side, side);
        }
        return null;
    }

    /** {minX, minY, maxX+1, maxY+1} of visible pixels, or null when fully transparent. */
    private static int[] opaqueBounds(BufferedImage image) {
        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) >>> 24) > 16) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
        return maxX < 0 ? null : new int[]{minX, minY, maxX + 1, maxY + 1};
    }

    // ---------------------------------------------------------------------------------
    // Model face renderer
    // ---------------------------------------------------------------------------------

    /**
     * Orthographic front view of a blockymodel's head subtree. The head node's own pose is
     * ignored so the face always looks straight out; child rotations are honoured. Faces are
     * drawn back to front (painter's algorithm) with alpha cut-out and simple flat shading.
     */
    static final class ModelFace {
        private ModelFace() { }

        /** One textured parallelogram: origin, two edge vectors, and its texture rectangle. */
        private record Face(double[] origin, double[] a, double[] b, JsonObject layout,
                            double texWidth, double texHeight, double shade, Texture texture, boolean doubleSided) {
            double depth() { return this.origin[2] + (this.a[2] + this.b[2]) / 2; }
        }
        /** Decoded texture pixels. */
        private record Texture(int[] argb, int width, int height) { }
        /** A node's frame in head space: rotation matrix (row-major 3x3) and origin. */
        private record Frame(double[] rotation, double[] origin) { }

        // Face key -> origin corner signs, a-axis, b-axis (as seen from outside), texture axes.
        private static final String[] KEYS = {"front", "back", "left", "right", "top", "bottom"};
        private static final double[][] SIGN = {{-1, 1, 1}, {1, 1, -1}, {1, 1, 1}, {-1, 1, -1}, {-1, 1, -1}, {-1, -1, 1}};
        private static final double[][] AXIS_A = {{1, 0, 0}, {-1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {1, 0, 0}};
        private static final double[][] AXIS_B = {{0, -1, 0}, {0, -1, 0}, {0, -1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        private static final int[][] TEX_AXES = {{0, 1}, {0, 1}, {2, 1}, {2, 1}, {0, 2}, {0, 2}};
        private static final double[] SHADE = {1.0, 0.7, 0.82, 0.82, 0.93, 0.6};
        private static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        private static final int FACE_LIMIT = 4096;

        /** Three-quarter head view: turned 30 degrees (facing left, like Hytale's portraits) and seen slightly from above. */
        static final double HEAD_YAW = Math.toRadians(-30), HEAD_PITCH = Math.toRadians(12);
        /** Full side view used for long, thin heads (fish, eels): the whole model facing left. */
        static final double SIDE_YAW = Math.toRadians(-90), SIDE_PITCH = Math.toRadians(8);
        /** A head view thinner than this (short side / long side) is replaced by the side view. */
        static final double THIN_HEAD = 0.5;
        /** A legless creature whose side view is at least this much wider than tall is drawn side-on. */
        static final double LONG_BODY = 1.2;

        static BufferedImage render(Source source) {
            if (source.model() == null || source.texture() == null) return null;
            JsonObject model = readJson(source.model());
            Texture texture = readTexture(source.texture());
            if (model == null || texture == null) return null;
            JsonArray nodes = model.getAsJsonArray("nodes");
            // Fish, eels, whales and snakes have no legs and are known by their outline, not their
            // face (a fish head from the front is a thin block), so long legless bodies go side-on.
            BufferedImage side = null;
            if (!hasLegs(nodes, 0)) {
                side = render(source, null, nodes, texture, SIDE_YAW, SIDE_PITCH);
                int[] box = side == null ? null : opaqueBounds(side);
                if (box != null && box[2] - box[0] >= LONG_BODY * (box[3] - box[1])) return side;
            }
            JsonObject head = findHead(nodes, 0);
            if (head != null) {
                BufferedImage face = render(source, head, null, texture, HEAD_YAW, HEAD_PITCH);
                int[] box = face == null ? null : opaqueBounds(face);
                if (box != null && Math.min(box[2] - box[0], box[3] - box[1])
                        >= THIN_HEAD * Math.max(box[2] - box[0], box[3] - box[1])) return face;
            }
            // No head node, or a head that is a thin sliver: show the whole creature side-on.
            return side != null ? side : render(source, null, nodes, texture, SIDE_YAW, SIDE_PITCH);
        }

        /** True if any drawn node is a leg part (Hytale and most mods name them Thigh/Calf/Leg/Foot). */
        private static boolean hasLegs(JsonArray nodes, int depth) {
            if (nodes == null || depth > 64) return false;
            for (JsonElement element : nodes) {
                if (!element.isJsonObject()) continue;
                JsonObject node = element.getAsJsonObject();
                String name = jsonString(node.get("name"));
                JsonObject shape = jsonObject(node.get("shape"));
                String type = shape == null ? null : jsonString(shape.get("type"));
                if (name != null && ("box".equals(type) || "quad".equals(type))) {
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("leg") || lower.contains("thigh") || lower.contains("calf")
                            || lower.contains("foot") || lower.contains("feet")) return true;
                }
                if (hasLegs(node.getAsJsonArray("children"), depth + 1)) return true;
            }
            return false;
        }

        /** Renders one head subtree, or all root nodes, plus matching attachments, from the given view. */
        static BufferedImage render(Source source, JsonObject head, JsonArray roots, Texture texture, double yaw, double pitch) {
            List<Face> faces = new ArrayList<>();
            Map<String, Frame> frames = new HashMap<>();
            Frame origin = new Frame(IDENTITY, new double[3]);
            if (head != null) {
                // The head's own pose is ignored so it always faces the camera the same way.
                collect(head, origin, texture, faces, frames, 0);
            } else if (roots != null) {
                // Whole model: root nodes keep their own position and rotation.
                for (JsonElement element : roots) {
                    if (!element.isJsonObject()) continue;
                    JsonObject root = element.getAsJsonObject();
                    collect(root, child(origin, root), texture, faces, frames, 0);
                }
            }

            // Attachments hook onto head nodes of the same name. Small parts (eyes, hair, hats)
            // match at their root; full-body overlays (e.g. a mod's coat or eye layer drawn as
            // the whole model again with another texture) match deeper, at their own "Head".
            // Faces are added in attachment order, so later layers draw over earlier ones.
            for (Attachment attachment : source.attachments()) {
                if (faces.size() >= FACE_LIMIT) break;
                JsonObject part = readJson(attachment.model());
                if (part == null || !part.has("nodes")) continue;
                Texture partTexture = attachment.texture() == null ? texture : readTexture(attachment.texture());
                if (partTexture == null) continue;
                attach(part.getAsJsonArray("nodes"), frames, partTexture, faces, 0);
            }
            return rasterise(view(faces, yaw, pitch));
        }

        /** Turns every face by the camera angle: yaw about the vertical axis, then pitch. */
        private static List<Face> view(List<Face> faces, double yaw, double pitch) {
            double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
            double[] r = multiply(new double[]{1, 0, 0, 0, cp, -sp, 0, sp, cp}, new double[]{cy, 0, sy, 0, 1, 0, -sy, 0, cy});
            List<Face> turned = new ArrayList<>(faces.size());
            for (Face f : faces)
                turned.add(new Face(apply(r, f.origin()), apply(r, f.a()), apply(r, f.b()), f.layout(),
                        f.texWidth(), f.texHeight(), f.shade(), f.texture(), f.doubleSided()));
            return turned;
        }

        /** A child node's frame: its parent's frame moved by its position and turned by its rotation. */
        private static Frame child(Frame parent, JsonObject node) {
            double[] position = vector(node.get("position"), 0);
            JsonObject q = jsonObject(node.get("orientation"));
            double[] rotation = q == null ? IDENTITY : quaternion(jsonNumber(q.get("w"), 1), jsonNumber(q.get("x"), 0),
                    jsonNumber(q.get("y"), 0), jsonNumber(q.get("z"), 0));
            return new Frame(multiply(parent.rotation(), rotation), add(parent.origin(), apply(parent.rotation(), position)));
        }

        /** Renders each attachment node whose name matches a head node, searching down the tree. */
        private static void attach(JsonArray nodes, Map<String, Frame> frames, Texture texture, List<Face> faces, int depth) {
            if (nodes == null || depth > 64) return;
            for (JsonElement element : nodes) {
                if (!element.isJsonObject()) continue;
                JsonObject node = element.getAsJsonObject();
                Frame frame = frames.get(jsonString(node.get("name")));
                if (frame != null) collect(node, frame, texture, faces, null, 0); // Its children come with it.
                else attach(node.getAsJsonArray("children"), frames, texture, faces, depth + 1);
            }
        }

        /** Depth-first search for the first Head/Skull node. */
        private static JsonObject findHead(JsonArray nodes, int depth) {
            if (nodes == null || depth > 64) return null;
            for (JsonElement element : nodes) {
                if (!element.isJsonObject()) continue;
                JsonObject node = element.getAsJsonObject();
                String name = jsonString(node.get("name"));
                if ("head".equalsIgnoreCase(name) || "skull".equalsIgnoreCase(name)) return node;
            }
            for (JsonElement element : nodes) {
                if (!element.isJsonObject()) continue;
                JsonObject found = findHead(element.getAsJsonObject().getAsJsonArray("children"), depth + 1);
                if (found != null) return found;
            }
            return null;
        }

        /** Adds a node's faces (in the given frame) and recurses into its children. */
        private static void collect(JsonObject node, Frame frame, Texture texture, List<Face> faces,
                                    Map<String, Frame> frames, int depth) {
            if (depth > 64 || faces.size() >= FACE_LIMIT) return;
            if (frames != null) frames.putIfAbsent(jsonString(node.get("name")), frame);
            JsonObject shape = jsonObject(node.get("shape"));
            if (shape != null && !Boolean.FALSE.equals(jsonBool(shape.get("visible")))) addShape(shape, frame, texture, faces);
            JsonArray children = node.getAsJsonArray("children");
            if (children == null) return;
            for (JsonElement element : children) {
                if (!element.isJsonObject()) continue;
                JsonObject child = element.getAsJsonObject();
                collect(child, child(frame, child), texture, faces, frames, depth + 1);
            }
        }

        /** Converts a box or quad shape into textured faces. */
        private static void addShape(JsonObject shape, Frame frame, Texture texture, List<Face> faces) {
            String type = jsonString(shape.get("type"));
            boolean box = "box".equals(type);
            if (!box && !"quad".equals(type)) return;
            JsonObject settings = jsonObject(shape.get("settings"));
            JsonObject layouts = jsonObject(shape.get("textureLayout"));
            if (settings == null || layouts == null) return;
            double[] size = vector(settings.get("size"), 0);
            double[] stretch = vector(shape.get("stretch"), 1);
            double[] offset = vector(shape.get("offset"), 0);
            boolean doubleSided = Boolean.TRUE.equals(jsonBool(shape.get("doubleSided")));
            if (box) {
                double[] half = {size[0] * stretch[0] / 2, size[1] * stretch[1] / 2, size[2] * stretch[2] / 2};
                for (int k = 0; k < KEYS.length; k++) {
                    JsonObject layout = jsonObject(layouts.get(KEYS[k]));
                    if (layout == null) continue;
                    double[] corner = new double[3], a = new double[3], b = new double[3];
                    for (int i = 0; i < 3; i++) {
                        corner[i] = offset[i] + SIGN[k][i] * half[i];
                        a[i] = AXIS_A[k][i] * 2 * half[i];
                        b[i] = AXIS_B[k][i] * 2 * half[i];
                    }
                    faces.add(face(frame, corner, a, b, layout, size[TEX_AXES[k][0]], size[TEX_AXES[k][1]], SHADE[k], texture, doubleSided));
                }
            } else {
                // A quad is one face in the plane through its offset, facing its normal.
                int k = switch (String.valueOf(jsonString(settings.get("normal")))) {
                    case "-Z" -> 1; case "+X" -> 2; case "-X" -> 3; case "+Y" -> 4; case "-Y" -> 5; default -> 0;
                };
                JsonObject layout = jsonObject(layouts.get("front"));
                if (layout == null) layout = jsonObject(layouts.get(KEYS[k]));
                if (layout == null) return;
                double lengthA = size[0] * stretch[axisOf(AXIS_A[k])], lengthB = size[1] * stretch[axisOf(AXIS_B[k])];
                double[] corner = new double[3], a = new double[3], b = new double[3];
                for (int i = 0; i < 3; i++) {
                    a[i] = AXIS_A[k][i] * lengthA;
                    b[i] = AXIS_B[k][i] * lengthB;
                    corner[i] = offset[i] - a[i] / 2 - b[i] / 2;
                }
                faces.add(face(frame, corner, a, b, layout, size[0], size[1], SHADE[k], texture, doubleSided));
            }
        }

        private static Face face(Frame frame, double[] corner, double[] a, double[] b, JsonObject layout,
                                 double texWidth, double texHeight, double shade, Texture texture, boolean doubleSided) {
            return new Face(add(frame.origin(), apply(frame.rotation(), corner)), apply(frame.rotation(), a),
                    apply(frame.rotation(), b), layout, texWidth, texHeight, shade, texture, doubleSided);
        }

        private static int axisOf(double[] direction) {
            return direction[0] != 0 ? 0 : direction[1] != 0 ? 1 : 2;
        }

        /** Draws camera-facing faces (camera on +Z looking back at the face) into a square raster. */
        private static BufferedImage rasterise(List<Face> faces) {
            List<Face> visible = new ArrayList<>();
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Face face : faces) {
                // Outward normal is b x a; keep faces turned towards the camera.
                double normalZ = face.b()[0] * face.a()[1] - face.b()[1] * face.a()[0];
                if (normalZ <= 1e-6 && !face.doubleSided()) continue;
                visible.add(face);
                for (int corner = 0; corner < 4; corner++) {
                    double x = face.origin()[0] + ((corner & 1) != 0 ? face.a()[0] : 0) + ((corner & 2) != 0 ? face.b()[0] : 0);
                    double y = -(face.origin()[1] + ((corner & 1) != 0 ? face.a()[1] : 0) + ((corner & 2) != 0 ? face.b()[1] : 0));
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
            double span = Math.max(maxX - minX, maxY - minY);
            if (visible.isEmpty() || !(span > 0.5)) return null;
            visible.sort(java.util.Comparator.comparingDouble(Face::depth)); // Far faces first.

            int size = FACE_RASTER;
            double scale = size * 0.96 / span, centreX = (minX + maxX) / 2, centreY = (minY + maxY) / 2;
            int[] pixels = new int[size * size];
            for (Face face : visible) {
                // Screen-space parallelogram: p = origin + s*A + t*B, s and t in [0, 1).
                double ox = face.origin()[0], oy = -face.origin()[1];
                double ax = face.a()[0], ay = -face.a()[1], bx = face.b()[0], by = -face.b()[1];
                double det = ax * by - bx * ay;
                if (Math.abs(det) < 1e-9) continue;
                double[] xs = {ox, ox + ax, ox + bx, ox + ax + bx}, ys = {oy, oy + ay, oy + by, oy + ay + by};
                int x0 = Math.max(0, (int) Math.floor(toPixel(min(xs), centreX, scale, size)));
                int x1 = Math.min(size - 1, (int) Math.ceil(toPixel(max(xs), centreX, scale, size)));
                int y0 = Math.max(0, (int) Math.floor(toPixel(min(ys), centreY, scale, size)));
                int y1 = Math.min(size - 1, (int) Math.ceil(toPixel(max(ys), centreY, scale, size)));
                JsonObject mirror = jsonObject(face.layout().get("mirror"));
                boolean mirrorX = mirror != null && Boolean.TRUE.equals(jsonBool(mirror.get("x")));
                boolean mirrorY = mirror != null && Boolean.TRUE.equals(jsonBool(mirror.get("y")));
                int angle = Math.floorMod((int) Math.round(jsonNumber(face.layout().get("angle"), 0)), 360);
                JsonObject uv = jsonObject(face.layout().get("offset"));
                double u0 = uv == null ? 0 : jsonNumber(uv.get("x"), 0), v0 = uv == null ? 0 : jsonNumber(uv.get("y"), 0);
                Texture texture = face.texture();
                for (int py = y0; py <= y1; py++) {
                    double dy = (py + 0.5 - size / 2.0) / scale + centreY - oy;
                    for (int px = x0; px <= x1; px++) {
                        double dx = (px + 0.5 - size / 2.0) / scale + centreX - ox;
                        double s = (dx * by - bx * dy) / det, t = (ax * dy - dx * ay) / det;
                        if (s < 0 || s >= 1 || t < 0 || t >= 1) continue;
                        if (mirrorX) s = 1 - s;
                        if (mirrorY) t = 1 - t;
                        double w = face.texWidth(), h = face.texHeight();
                        // Rotated layouts: the texture rectangle is turned in 90-degree steps.
                        switch (angle) {
                            case 90 -> { double r = s; s = t; t = 1 - r; w = face.texHeight(); h = face.texWidth(); }
                            case 180 -> { s = 1 - s; t = 1 - t; }
                            case 270 -> { double r = s; s = 1 - t; t = r; w = face.texHeight(); h = face.texWidth(); }
                            default -> { }
                        }
                        int u = (int) Math.floor(u0 + s * w), v = (int) Math.floor(v0 + t * h);
                        if (u < 0 || v < 0 || u >= texture.width() || v >= texture.height()) continue;
                        int argb = texture.argb()[v * texture.width() + u];
                        if ((argb >>> 24) < 128) continue; // Alpha cut-out, as in game.
                        pixels[py * size + px] = 0xFF000000 | shade(argb, face.shade());
                    }
                }
            }
            // A face that is almost entirely see-through (e.g. a transparent base texture whose
            // real look comes from an attachment we could not apply) falls back to the next source.
            int drawn = 0;
            for (int pixel : pixels) if (pixel != 0) drawn++;
            if (drawn < MIN_FACE_COVERAGE * size * size) return null;
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, size, size, pixels, 0, size);
            return image;
        }

        private static double toPixel(double value, double centre, double scale, int size) {
            return (value - centre) * scale + size / 2.0;
        }

        private static int shade(int argb, double factor) {
            int r = (int) (((argb >>> 16) & 0xFF) * factor), g = (int) (((argb >>> 8) & 0xFF) * factor), b = (int) ((argb & 0xFF) * factor);
            return (r << 16) | (g << 8) | b;
        }

        private static Texture readTexture(String path) {
            BufferedImage image = readImage(path);
            if (image == null) return null;
            int width = image.getWidth(), height = image.getHeight();
            return new Texture(image.getRGB(0, 0, width, height, null, 0, width), width, height);
        }

        private static JsonObject readJson(String path) {
            byte[] bytes = commonBytes(path);
            if (bytes == null) return null;
            try {
                JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        // Small vector helpers (row-major 3x3 matrices).
        private static double[] quaternion(double w, double x, double y, double z) {
            double n = Math.sqrt(w * w + x * x + y * y + z * z);
            if (n < 1e-9) return IDENTITY;
            w /= n; x /= n; y /= n; z /= n;
            return new double[]{1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                    2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                    2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
        }
        private static double[] multiply(double[] m, double[] n) {
            double[] r = new double[9];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    r[i * 3 + j] = m[i * 3] * n[j] + m[i * 3 + 1] * n[3 + j] + m[i * 3 + 2] * n[6 + j];
            return r;
        }
        private static double[] apply(double[] m, double[] v) {
            return new double[]{m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                    m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
        }
        private static double[] add(double[] a, double[] b) {
            return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
        }
        private static double min(double[] values) { double r = values[0]; for (double v : values) r = Math.min(r, v); return r; }
        private static double max(double[] values) { double r = values[0]; for (double v : values) r = Math.max(r, v); return r; }

        private static double[] vector(JsonElement element, double fallback) {
            JsonObject o = jsonObject(element);
            return o == null ? new double[]{fallback, fallback, fallback}
                    : new double[]{jsonNumber(o.get("x"), fallback), jsonNumber(o.get("y"), fallback), jsonNumber(o.get("z"), fallback)};
        }
    }

    // ---------------------------------------------------------------------------------
    // Asset and image helpers (JSON helpers: DapperMapUtil)
    // ---------------------------------------------------------------------------------

    /** Bytes of a Hytale common asset (including mod-pack assets). Bounded wait; render pool only. */
    static byte[] commonBytes(String path) {
        String name = commonName(path);
        return name == null ? null : DapperMapUtil.commonAssetBytes(name, LOAD_TIMEOUT_MS);
    }

    /** Common asset name for a model/texture path, or null for none or an unsafe path. */
    static String commonName(String path) {
        if (path == null || path.isBlank() || path.contains("..")) return null;
        // Common asset names are relative to the Common folder; tolerate a leading "Common/".
        String name = path.replace('\\', '/');
        while (name.startsWith("/")) name = name.substring(1);
        if (name.startsWith("Common/")) name = name.substring("Common/".length());
        return name;
    }

    static BufferedImage readImage(String path) {
        byte[] bytes = commonBytes(path);
        if (bytes == null) return null;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() > 4096 || image.getHeight() > 4096) return null;
            return image;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Area-averaged downscale via successive halving, then a final bicubic step. */
    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage current = source;
        while (current.getWidth() / 2 >= size) current = resize(current, current.getWidth() / 2);
        return current.getWidth() == size ? current : resize(current, size);
    }

    private static BufferedImage resize(BufferedImage source, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}

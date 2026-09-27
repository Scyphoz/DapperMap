package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.setup.AssetFinalize;
import com.hypixel.hytale.protocol.packets.setup.AssetInitialize;
import com.hypixel.hytale.protocol.packets.setup.AssetPart;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Sends generated PNG assets to a player's client and avoids retransmitting identical bytes. */
public final class DynamicAssetPublisher {
    private static final ConcurrentMap<UUID, ConcurrentMap<String, byte[]>> MARKER_BYTES = new ConcurrentHashMap<>();
    private static final int PART_SIZE = 2621440;
    private static final ConcurrentMap<UUID, ConcurrentMap<String, String>> DELIVERED = new ConcurrentHashMap();

    private DynamicAssetPublisher() {
    }

    public static void clear(UUID uuid) {
        if (uuid != null) {
            DELIVERED.remove(uuid);
            MARKER_BYTES.remove(uuid);
        }

    }

    public static byte[] markerBytes(UUID uuid, String path) {
        Map<String, byte[]> assets = uuid == null ? null : MARKER_BYTES.get(uuid);
        byte[] bytes = assets == null ? null : assets.get(path);
        return bytes == null ? null : bytes.clone();
    }

    public static boolean deliver(PlayerRef playerRef, String path, byte[] data) {
        return deliverBytes(playerRef, path, data, false);
    }

    public static boolean deliverWithRebuild(PlayerRef playerRef, String path, byte[] data) {
        return deliverBytes(playerRef, path, data, true);
    }

    private static boolean deliverBytes(PlayerRef playerRef, String path, byte[] data, boolean rebuildCommonAssets) {
        if (playerRef != null && path != null && data != null && data.length != 0) {
            PacketHandler packetHandler = playerRef.getPacketHandler();
            if (packetHandler == null) {
                return false;
            } else {
                UUID uuid = playerRef.getUuid();

                DynamicMinimapAsset asset;
                try {
                    asset = new DynamicMinimapAsset(path, data);
                } catch (Exception var141) {
                    return false;
                }

                String hash = asset.getHash();
                if (uuid != null && path.startsWith("UI/DapperMap/")
                        && (path.contains("/mk-") || path.contains("/bettermap-")
                        || path.contains("/worldmap-") || path.contains("/death-"))) {
                    Map<String, byte[]> markers = MARKER_BYTES.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>());
                    if (markers.size() < 256 || markers.containsKey(path)) markers.put(path, data.clone());
                }
                if (uuid != null) {
                    Map<String, String> delivered = (Map)DELIVERED.get(uuid);
                    if (delivered != null && hash.equals(delivered.get(path))) {
                        return true;
                    }
                }

                try {
                    byte[][] parts = splitBytes(data, PART_SIZE);
                    Packet[] packets = new Packet[parts.length + 2];
                    packets[0] = new AssetInitialize(asset.toPacket(), data.length);

                    for(int i = 0; i < parts.length; ++i) {
                        packets[i + 1] = new AssetPart(parts[i]);
                    }

                    packets[packets.length - 1] = new AssetFinalize();

                    for(Packet packet : packets) {
                        packetHandler.write((ToClientPacket)packet);
                    }

                    if (rebuildCommonAssets) {
                        packetHandler.writeNoCache(new RequestCommonAssetsRebuild());
                    }

                    if (uuid != null) {
                        ((ConcurrentMap)DELIVERED.computeIfAbsent(uuid, (ignoredx) -> new ConcurrentHashMap())).put(path, hash);
                    }

                    return true;
                } catch (Exception var15) {
                    return false;
                }
            }
        } else {
            return false;
        }
    }

    private static byte[][] splitBytes(byte[] data, int partSize) {
        int count = (data.length + partSize - 1) / partSize;
        if (count == 0) {
            return new byte[][]{data};
        } else {
            byte[][] parts = new byte[count][];

            for(int i = 0; i < count; ++i) {
                int start = i * partSize;
                int end = Math.min(start + partSize, data.length);
                parts[i] = new byte[end - start];
                System.arraycopy(data, start, parts[i], 0, end - start);
            }

            return parts;
        }
    }
}

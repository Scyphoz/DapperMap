package dev.scyphoz.dappermap.dynamic;

import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import java.util.concurrent.CompletableFuture;

final class DynamicMinimapAsset extends CommonAsset {
    private final byte[] data;

    DynamicMinimapAsset(String path, byte[] data) {
        super(path, CommonAsset.hash(data), data);
        this.data = data;
    }

    protected CompletableFuture<byte[]> getBlob0() {
        return CompletableFuture.completedFuture(this.data);
    }
}

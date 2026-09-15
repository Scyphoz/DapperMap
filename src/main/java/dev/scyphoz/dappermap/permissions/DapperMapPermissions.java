package dev.scyphoz.dappermap.permissions;

import com.hypixel.hytale.server.core.permissions.PermissionQuery;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;

/**
 * Central definition of DapperMap's permission nodes.
 *
 * <p>The node deliberately matches the one the command framework generates for
 * {@code /dappermap admin}: {@code <manifest group>.<manifest name>.command.<root>.<sub>},
 * lowercased. Keeping them identical means a single grant covers both the command
 * and the settings UI, and it stays compatible with grants that were already issued.
 *
 * <p>If the manifest {@code Group} or {@code Name} is ever changed, update
 * {@link #ADMIN_NODE} to match, or the generated command node and this node will
 * drift apart.
 */
public final class DapperMapPermissions {

    /** Manifest Group "Scyphoz" + Name "DapperMap", lowercased by the command framework. */
    private static final String COMMAND_BASE = "scyphoz.dappermap.command.dappermap";

    /** Grants access to the admin settings UI and to {@code /dappermap admin ...}. */
    public static final String ADMIN_NODE = COMMAND_BASE + ".admin";

    /** Query form, which also resolves parent wildcards such as {@code scyphoz.*} and {@code *}. */
    public static final PermissionQuery ADMIN = PermissionQuery.of(ADMIN_NODE);

    private static boolean registered;

    private DapperMapPermissions() {
    }

    /**
     * Registers the admin node and attaches it to the built-in operator group so that
     * operators inherit it without any manual permission setup. Must be called during
     * plugin setup, before the command tree is registered.
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        PermissionsModule.registerPermission(ADMIN_NODE, HytalePermissionsProvider.OP_GROUP);
        registered = true;
    }
}

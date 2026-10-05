package dev.scyphoz.dappermap.permissions;

import com.hypixel.hytale.server.core.permissions.PermissionQuery;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;

/**
 * Central definition of DapperMap's permission nodes.
 *
 * <p><b>Deliberately NOT nested under each other.</b> This server's permission provider
 * matches grants by prefix, so any node that is a descendant of another is silently covered
 * by a wildcard grant of the ancestor. The three branches below share no prefix beyond
 * {@code scyphoz.dappermap}, so a grant aimed at any one of them can never reach another:
 *
 * <ul>
 *   <li>{@code scyphoz.dappermap.command.<setting>} — one player-facing command/UI per node.
 *       {@code scyphoz.dappermap.command.*} grants every player command and nothing else.</li>
 *   <li>{@code scyphoz.dappermap.admin} — the admin UI and every {@code /dappermap admin ...}
 *       sub-command, as one node (see below for why the admin tree isn't split further).</li>
 * </ul>
 *
 * <p>An earlier version mirrored the framework's own auto-generated command nodes instead
 * (e.g. {@code scyphoz.dappermap.command.dappermap.zoom}, with admin nested one level deeper
 * still, at {@code scyphoz.dappermap.command.dappermap.admin}). That put admin inside the
 * player tree, so a "grant every player command" wildcard silently granted admin too. Player
 * commands here are shortened for the same reason admin was moved: {@code command.dappermap.*}
 * only existed because the top-level command happens to be named "dappermap"; dropping that
 * redundant segment gives {@code scyphoz.dappermap.command.*} as a clean, flat "every player
 * command" wildcard, structurally guaranteed not to overlap {@link #ADMIN_NODE}.
 */
public final class DapperMapPermissions {

    /** Root for DapperMap nodes that live outside the auto-generated command tree. */
    private static final String PLUGIN_ROOT = "scyphoz.dappermap";

    /** Root for the player-facing command tree: {@code scyphoz.dappermap.command.<setting>}. */
    private static final String COMMAND_ROOT = PLUGIN_ROOT + ".command";

    /**
     * Grants access to the admin settings UI and to every {@code /dappermap admin ...}
     * sub-command. Required explicitly on the admin command and on each of its
     * sub-commands (see {@code MinimapCommand}) rather than left to the framework's
     * per-node auto-generation, so no player-command wildcard can reach it and no
     * single ungated node is left as a hole.
     */
    public static final String ADMIN_NODE = PLUGIN_ROOT + ".admin";

    /** Query form, which also resolves parent wildcards such as {@code scyphoz.*} and {@code *}. */
    public static final PermissionQuery ADMIN = PermissionQuery.of(ADMIN_NODE);

    /**
     * Default group every player belongs to, or inherits through, out of the box. When
     * {@code GrantPlayerSettingsByDefault} is enabled, player-command nodes are registered
     * against it via {@link PermissionsModule#registerPermission}; in explicit-grant mode
     * the same nodes are registered without a default group.
     *
     * <p>{@code AbstractCommand.setPermissionGroups(...)} looks similar but is not the same
     * mechanism: its constant-pool neighbourhood (a {@code GameMode}-keyed switch table
     * alongside the same group name strings) indicates it is a coarse "anyone in this group
     * may run this command" fast path, evaluated independently of — and in addition to — the
     * node-specific permission. Since almost every player is a member of this default group,
     * calling it here would let it silently override any administrator who explicitly denies
     * a player one of these nodes. Use {@link #player(String)} instead, which never touches
     * {@code setPermissionGroups}.
     */
    public static final String DEFAULT_PLAYER_GROUP = HytalePermissionsProvider.DEFAULT_GROUP;

    private static boolean registered;
    private static boolean grantPlayerSettingsByDefault = true;
    private static final java.util.Set<String> registeredPlayerNodes = new java.util.HashSet<>();

    private DapperMapPermissions() {
    }

    /**
     * Node for a single player-facing command/UI, e.g. {@code player("zoom")} yields
     * {@code scyphoz.dappermap.command.zoom}. One level directly under {@link #COMMAND_ROOT},
     * so {@code scyphoz.dappermap.command.*} covers every one of them without covering
     * {@link #ADMIN_NODE}.
     *
     * <p>Registers the node the first time it is requested. When
     * {@code GrantPlayerSettingsByDefault} is enabled it is attached to the default player
     * group; otherwise it is registered without a group and requires an explicit grant.
     *
     * @param settingName the sub-command's own registered name — must match exactly, since a
     *                     mismatch here would leave that command checking a node nobody
     *                     actually granted.
     */
    public static synchronized PermissionQuery player(String settingName) {
        String node = COMMAND_ROOT + "." + settingName;
        if (registeredPlayerNodes.add(node)) {
            if (grantPlayerSettingsByDefault) {
                PermissionsModule.registerPermission(node, DEFAULT_PLAYER_GROUP);
            } else {
                PermissionsModule.registerPermission(node);
            }
        }
        return PermissionQuery.of(node);
    }

    /**
     * Registers the admin node and attaches it to the built-in operator group so that
     * operators inherit it without any manual permission setup. Must be called during
     * plugin setup, before the command tree is registered.
     */
    public static synchronized void register(boolean grantPlayerSettingsByDefault) {
        if (registered) {
            return;
        }
        DapperMapPermissions.grantPlayerSettingsByDefault = grantPlayerSettingsByDefault;
        // GROUP_ADMIN replaces the deprecated OP_GROUP (same group: "hytale:Admin").
        PermissionsModule.registerPermission(ADMIN_NODE, HytalePermissionsProvider.GROUP_ADMIN);
        registered = true;
    }
}

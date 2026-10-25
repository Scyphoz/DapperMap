package dev.scyphoz.dappermap.permissions;

import com.hypixel.hytale.server.core.permissions.PermissionQuery;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers DapperMap's permission nodes and hands out their queries.
 *
 * <p>Two branches that share no prefix beyond {@code dappermap}, so a grant aimed at one
 * can never reach the other:
 * <ul>
 *   <li>{@code dappermap.command.<path>}: the player nodes listed in
 *       {@link SettingPermissions} (e.g. {@code command.display.zoom}, {@code command.toggle},
 *       {@code command.colours.map-edge}). {@code dappermap.command.*} grants every one.</li>
 *   <li>{@code dappermap.admin}: the admin page and every {@code /dappermap admin ...}
 *       command, as one node.</li>
 * </ul>
 *
 * <p><b>How grants resolve (checked in the 0.7.4 server and LuckPerms 5.5.88).</b> Hytale's
 * built-in provider checks, in each permission set (the player's own, then each group's): a
 * {@code -*} deny, the exact deny ({@code -node}), the exact grant ({@code node}), any deny
 * wildcard ({@code -a.*}, {@code -a.b.*}, ...), {@code *}, then any grant wildcard. The first set
 * with an answer decides. So {@code command.display.*} covers every Display setting, an exact
 * {@code -command.display.zoom} still denies zoom, and a player's own grants beat their groups'.
 * A wildcard needs its {@code .*}: a bare {@code command.display} grants nothing below it.
 * LuckPerms answers each node itself (most specific node wins) and applies DapperMap's default
 * grants after its own nodes, through its Hytale virtual groups.
 *
 * <p>Nodes must be registered during plugin setup: Hytale adds default-group grants to its
 * virtual groups once, in {@code PermissionsModule.start()} (and on {@code /perm reload}).
 */
public final class DapperMapPermissions {

    /** Root for DapperMap nodes that live outside the auto-generated command tree. */
    private static final String PLUGIN_ROOT = "dappermap";

    /** Root for the player nodes: {@code dappermap.command.<path>}. */
    public static final String COMMAND_ROOT = PLUGIN_ROOT + ".command";

    /**
     * Grants access to the admin settings UI and to every {@code /dappermap admin ...}
     * sub-command. Deliberately outside {@link #COMMAND_ROOT}, so no player wildcard reaches it.
     */
    public static final String ADMIN_NODE = PLUGIN_ROOT + ".admin";

    /** Query form, which also resolves parent wildcards such as {@code dappermap.*} and {@code *}. */
    public static final PermissionQuery ADMIN = PermissionQuery.of(ADMIN_NODE);

    /**
     * Default group every player belongs to, or inherits through, out of the box. When
     * {@code GrantPlayerSettingsByDefault} is enabled, player nodes are registered against it via
     * {@link PermissionsModule#registerPermission}; in explicit-grant mode they are registered
     * without a group.
     *
     * <p>Don't use {@code AbstractCommand.setPermissionGroups(...)} for this: it is a coarse
     * "anyone in this group may run this command" path that skips the node check, so it would
     * override an administrator who denies a player one of these nodes.
     */
    public static final String DEFAULT_PLAYER_GROUP = HytalePermissionsProvider.DEFAULT_GROUP;

    private static boolean registered;
    /** Path -> query, built once at registration (PermissionQuery.of parses the node). Read lock-free. */
    private static volatile Map<String, PermissionQuery> playerQueries = Map.of();

    private DapperMapPermissions() {
    }

    /** Full node name for a player path, e.g. {@code display.zoom} -> {@code dappermap.command.display.zoom}. */
    public static String node(String path) {
        return COMMAND_ROOT + "." + path;
    }

    /**
     * Query for one player node, e.g. {@code player("display.zoom")}. Only paths listed in
     * {@link SettingPermissions} exist; anything else is a programming error and throws, so a
     * typo can never leave a command checking a node nobody can be granted.
     */
    public static PermissionQuery player(String path) {
        PermissionQuery query = playerQueries.get(path);
        if (query == null) throw new IllegalArgumentException("Unknown DapperMap permission node: " + path);
        return query;
    }

    /**
     * Registers the admin node (granted to the admin group) and every player node (granted to the
     * default player group when {@code grantPlayerSettingsByDefault}, otherwise ungrouped). Must
     * be called during plugin setup, before the command tree is built.
     */
    public static synchronized void register(boolean grantPlayerSettingsByDefault) {
        if (registered) {
            return;
        }
        // GROUP_ADMIN replaces the deprecated OP_GROUP (same group: "hytale:Admin").
        PermissionsModule.registerPermission(ADMIN_NODE, HytalePermissionsProvider.GROUP_ADMIN);
        Map<String, PermissionQuery> queries = new HashMap<>();
        for (String path : SettingPermissions.allPaths()) {
            String node = node(path);
            if (grantPlayerSettingsByDefault) {
                PermissionsModule.registerPermission(node, DEFAULT_PLAYER_GROUP);
            } else {
                PermissionsModule.registerPermission(node);
            }
            queries.put(path, PermissionQuery.of(node));
        }
        playerQueries = Map.copyOf(queries);
        registered = true;
    }
}

package dev.scyphoz.dappermap.pages;

import com.hypixel.hytale.server.core.permissions.PermissionQuery;
import dev.scyphoz.dappermap.permissions.DapperMapPermissions;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.scyphoz.dappermap.config.MinimapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.integration.BetterMapIntegration;
import dev.scyphoz.dappermap.service.MinimapSessionManager;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;

public final class DapperMapSettingsPage
        extends InteractiveCustomUIPage<DapperMapSettingsPage.Ev> {

    private static final Color GOLD = new Color(16766571);
    private static final Color BLUE = new Color(10345727);
    private static final Color GREEN = new Color(5757323);
    private static final Color GRAY = new Color(8491684);

    private static final Row[] ROWS;

    private final PlayerRef playerRef;
    private final MinimapSessionManager sessions;
    private final MinimapConfig config;
    private final boolean adminMode;
    /**
     * Retained for source compatibility. The authoritative definition now lives in
     * {@link dev.scyphoz.dappermap.permissions.DapperMapPermissions#ADMIN}, which uses the
     * same node the command framework generates for {@code /dappermap admin}.
     */
    public static final PermissionQuery ADMIN_PERMISSION = DapperMapPermissions.ADMIN;

    public DapperMapSettingsPage(
            PlayerRef playerRef,
            MinimapSessionManager sessions,
            MinimapConfig config
    ) {
        super(
                playerRef,
                CustomPageLifetime.CanDismiss,
                DapperMapSettingsPage.Ev.CODEC
        );

        this.playerRef = playerRef;
        this.sessions = sessions;
        this.config = config;
        this.adminMode = false;
    }

    public DapperMapSettingsPage(PlayerRef playerRef, MinimapSessionManager sessions, MinimapConfig config, boolean adminMode) {
        super(playerRef, CustomPageLifetime.CanDismiss, DapperMapSettingsPage.Ev.CODEC);
        this.playerRef = playerRef;
        this.sessions = sessions;
        this.config = config;
        this.adminMode = adminMode;
    }

    public void build(
            Ref<EntityStore> ref,
            UICommandBuilder builder,
            UIEventBuilder events,
            Store<EntityStore> store
    ) {
        if (this.adminMode && !this.playerRef.hasPermission(ADMIN_PERMISSION)) return;
        DynamicMinimapSetting settings = this.adminMode
                ? this.sessions.getAdminDefaults(this.config)
                : this.sessions.getSettings(this.playerRef.getUuid(), this.config);

        builder.append(this.adminMode ? "Pages/DapperMapAdminSettings.ui" : "Pages/DapperMapSettings.ui");
        builder.set("#PageTitle.Text", this.adminMode ? "DapperMap - Admin Settings" : "DapperMap");
        if (this.adminMode) {
            builder.set("#DapperMapTagline.Text", "Server defaults for new DapperMap players");
        }

        this.renderList(builder, events, settings);
        this.bindFooterButtons(builder, events);
    }

    private void bindFooterButtons(
            UICommandBuilder builder,
            UIEventBuilder events
    ) {
        builder.append(
                "#DapperMapResetButtonSlot",
                "Pages/BasicTextButton.ui"
        );

        builder.set(
                "#DapperMapResetButtonSlot[0].TextSpans",
                Message.raw(this.adminMode ? "RESET TO MOD DEFAULTS" : "RESET TO DEFAULTS").color(GOLD)
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#DapperMapResetButtonSlot[0]",
                EventData.of("Action", "reset_defaults"),
                false
        );

        if (!this.adminMode && this.playerRef.hasPermission(ADMIN_PERMISSION)) {
            builder.append("#DapperMapAdminButtonSlot", "Pages/BasicTextButton.ui");
            builder.set("#DapperMapAdminButtonSlot[0].TextSpans", Message.raw("ADMIN SETTINGS").color(GREEN));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#DapperMapAdminButtonSlot[0]",
                    EventData.of("Action", "admin_settings"), false);
        }

        if (this.adminMode) {
            // The admin page leaves the bottom-left slot free, so reuse it rather than
            // changing the shared footer layout.
            builder.append("#DapperMapAdminButtonSlot", "Pages/BasicTextButton.ui");
            builder.set("#DapperMapAdminButtonSlot[0].TextSpans", Message.raw("SAVE CHANGES").color(GREEN));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#DapperMapAdminButtonSlot[0]",
                    EventData.of("Action", "admin_save"), false);
        }
    }

    private void renderList(
            UICommandBuilder builder,
            UIEventBuilder events,
            DynamicMinimapSetting settings
    ) {
        builder.clear("#DapperMapSettingsList");

        for (int index = 0; index < ROWS.length; ++index) {
            Row row = ROWS[index];

            if (row.kind == Kind.HEADER) {
                builder.append("#DapperMapSettingsList", "Pages/DapperMapSectionRow.ui");
                builder.set("#DapperMapSettingsList[" + index + "] #SectionLabel.Text", row.label);
            } else if (row.kind == Kind.TOGGLE) {
                this.toggleRow(builder, events, index, row, settings);
            } else if (row.kind == Kind.DROPDOWN) {
                this.dropdownRow(builder, events, index, row, settings);
            } else if (row.kind == Kind.ACTION) {
                this.actionRow(builder, events, index, row, settings);
            }
        }
    }

    private void actionRow(UICommandBuilder builder, UIEventBuilder events,
                           int index, Row row, DynamicMinimapSetting settings) {
        String selector = "#DapperMapSettingsList[" + index + "]";
        if (!settings.enabled) {
            builder.append("#DapperMapSettingsList", "Pages/DapperMapDisabledDropdownRow.ui");
            builder.set(selector + " #RowLabel.Text", row.label);
            builder.set(selector + " #RowDescription.Text", description(row.id));
            builder.set(selector + " #ValueLabel.Text", row.label);
            return;
        }
        builder.append("#DapperMapSettingsList", "Pages/DapperMapMoveRow.ui");
        builder.set(selector + " #RowLabel.Text", row.label);
        builder.set(selector + " #RowDescription.Text", description(row.id));
        builder.append(selector + " #BtnSlot", "Pages/BasicTextButton.ui");
        builder.set(selector + " #BtnSlot[0].TextSpans", Message.raw(row.id.equals("colours") ? "EDIT COLOURS" : "MOVE MINIMAP").color(BLUE));
        events.addEventBinding(CustomUIEventBindingType.Activating, selector + " #BtnSlot[0]",
                EventData.of("Action", row.id), false);
    }

    private void toggleRow(
            UICommandBuilder builder,
            UIEventBuilder events,
            int index,
            Row row,
            DynamicMinimapSetting settings
    ) {
        String selector = "#DapperMapSettingsList[" + index + "]";

        builder.append(
                "#DapperMapSettingsList",
                "Pages/DapperMapToggleRow.ui"
        );

        builder.set(
                selector + " #RowLabel.Text",
                row.label
        );

        builder.set(
                selector + " #RowDescription.Text",
                description(row.id)
        );

        builder.append(
                selector + " #BtnSlot",
                "Pages/BasicTextButton.ui"
        );

        builder.set(
                selector + " #BtnSlot[0].TextSpans",
                toggleButton(row.id, settings)
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector + " #BtnSlot[0]",
                EventData.of("Action", "toggle_" + row.id),
                false
        );
    }

    private void dropdownRow(
            UICommandBuilder builder,
            UIEventBuilder events,
            int index,
            Row row,
            DynamicMinimapSetting settings
    ) {
        String selector = "#DapperMapSettingsList[" + index + "]";

        if (isDropdownDisabled(row.id, settings)) {
            // Render a non-interactive value while its controlling filter is off.
            builder.append("#DapperMapSettingsList", "Pages/DapperMapDisabledDropdownRow.ui");
            builder.set(selector + " #RowLabel.Text", row.label);
            builder.set(selector + " #RowDescription.Text", description(row.id));
            builder.set(selector + " #ValueLabel.Text", disabledValue(row.id, settings));
            return;
        }

        builder.append(
                "#DapperMapSettingsList",
                "Pages/DapperMapDropdownRow.ui"
        );

        builder.set(
                selector + " #RowLabel.Text",
                row.label
        );

        builder.set(selector + " #RowDescription.Text", description(row.id));

        String dropdownSelector = selector + " #ValueDropdown";

        builder.set(dropdownSelector + ".Entries", dropdownEntries(row.id, settings));

        builder.set(
                dropdownSelector + ".Value",
                dropdownValue(row.id, settings)
        );

        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                dropdownSelector,
                EventData.of("Action", "dropdown_" + row.id)
                        .append("@Value", dropdownSelector + ".Value"),
                false
        );
    }

    public void handleDataEvent(
            Ref<EntityStore> ref,
            Store<EntityStore> store,
            Ev event
    ) {
        String action = event.action;

        if (this.adminMode && !this.playerRef.hasPermission(ADMIN_PERMISSION)) return;
        if ("admin_settings".equals(action)) {
            if (!this.playerRef.hasPermission(ADMIN_PERMISSION)) return;
            this.sessions.setAdminPageOrigin(this.playerRef.getUuid(), true);
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) player.getPageManager().openCustomPage(ref, store,
                    new DapperMapSettingsPage(this.playerRef, this.sessions, this.config, true));
            return;
        }

        if ("admin_save".equals(action)) {
            if (!this.adminMode || !this.playerRef.hasPermission(ADMIN_PERMISSION)) return;
            // Each edit is already persisted as it is made; re-saving here makes the button
            // an explicit confirmation and guarantees the file matches what is on screen.
            this.sessions.setAdminDefaults(this.sessions.getAdminDefaults(this.config));
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;
            boolean fromPlayerPage = this.sessions.adminPageOpenedFromPlayerPage(this.playerRef.getUuid());
            this.sessions.setAdminPageOrigin(this.playerRef.getUuid(), false);
            if (fromPlayerPage) {
                player.getPageManager().openCustomPage(ref, store,
                        new DapperMapSettingsPage(this.playerRef, this.sessions, this.config));
            } else {
                player.getPageManager().setPage(ref, store, Page.None);
            }
            return;
        }

        if (action == null || action.isEmpty()) {
            return;
        }

        if ("colours".equals(action)) {
            if (!(this.adminMode ? this.sessions.getAdminDefaults(this.config) : this.sessions.getSettings(this.playerRef.getUuid(), this.config)).enabled) return;
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) player.getPageManager().openCustomPage(ref, store,
                    new DapperMapColoursPage(this.playerRef, this.sessions, this.config, this.adminMode));
            return;
        }

        if ("move_minimap".equals(action)) {
            if (!(this.adminMode ? this.sessions.getAdminDefaults(this.config) : this.sessions.getSettings(this.playerRef.getUuid(), this.config)).enabled) return;
            Player player =
                    (Player) store.getComponent(
                            ref,
                            Player.getComponentType()
                    );

            if (player != null) {
                player.getPageManager().openCustomPage(
                        ref,
                        store,
                        new MoveMinimapPage(
                                this.playerRef,
                                this.sessions,
                                this.config,
                                this.adminMode
                        )
                );
            }

            return;
        }

        DynamicMinimapSetting settings = this.adminMode
                ? this.sessions.getAdminDefaults(this.config).copy()
                : this.sessions.getSettings(this.playerRef.getUuid(), this.config);

        if ("reset_defaults".equals(action)) {
            settings = this.adminMode
                    ? DynamicMinimapSetting.fromConfig(this.config)
                    : this.sessions.getAdminDefaults(this.config).copy();

        } else if (action.startsWith("toggle_")) {
            applyToggle(
                    settings,
                    action.substring("toggle_".length())
            );

        } else if (action.startsWith("dropdown_")) {
            applyDropdown(
                    settings,
                    action.substring("dropdown_".length()),
                    event.value
            );

        } else {
            return;
        }

        if (this.adminMode) this.sessions.setAdminDefaults(settings);
        else this.sessions.applySettings(this.playerRef, settings);

        UICommandBuilder builder = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        this.renderList(builder, events, settings);

        this.sendUpdate(builder, events, false);
    }

    private static void applyToggle(DynamicMinimapSetting settings, String id) {
        if ("enabled".equals(id)) settings.enabled = !settings.enabled;
    }

    private static String description(String id) {
        return switch (id) {
            case "enabled" -> "Show or hide your minimap.";
            case "colours" -> "Choose colours for your minimap and markers.";
            case "zoom" -> "Choose how far around you the map shows.";
            case "size" -> "Change the size of the map on your screen.";
            case "interface_scale" -> "Resize the map and its information together.";
            case "shape" -> "Choose a round or square map.";
            case "rotation" -> "Keep north fixed or turn the map as you turn.";
            case "speed" -> "Choose how often the minimap refreshes.";
            case "move_minimap" -> "Choose where the minimap sits on your screen.";
            case "panel_position" -> "Choose which side shows the map information.";
            case "players" -> "Show nearby players on your minimap.";
            case "entities" -> "Show nearby creatures on your minimap.";
            case "death" -> "Mark the location of your last death.";
            case "worldmap" -> "Show Hytale personal, shared, POI, spawn and respawn world-map markers.";
            case "height" -> "Hide creatures far above or below you.";
            case "vrange" -> "Set how far above and below to show creatures.";
            case "clock" -> "Show the current time in your Hytale world.";
            case "coordinates" -> "Show your current world coordinates.";
            case "clock_format" -> "Choose a 12-hour or 24-hour clock.";
            case "compass" -> "Show the direction you are facing.";
            case "biome" -> "Show the name of the biome you are in.";
            case "bettermap" -> BetterMapIntegration.isAvailable()
                    ? "Show BetterMap waypoints on the minimap and around its edge."
                    : "BetterMap is not installed or could not be detected.";
            case "texture_resolution" -> "Choose the resolution used to render minimap textures.";
            default -> "";
        };
    }

    private static String disabledValue(String id, DynamicMinimapSetting settings) {
        if ("bettermap".equals(id) && !BetterMapIntegration.isAvailable()) {
            return "Not detected";
        }
        return switch (id) {
            case "zoom" -> settings.zoomBlocks + " blocks";
            case "size" -> settings.sizePixels + " px";
            case "interface_scale" -> settings.interfaceScalePercent + "%";
            case "texture_resolution" -> settings.textureResolution + " px";
            case "speed" -> displaySpeed(settings.updateSpeed);
            case "panel_position" -> displayPanelPosition(settings.infoPanelPosition);
            case "shape" -> settings.isCircle() ? "Circle" : "Square";
            case "rotation" -> settings.rotationMode == 0 ? "Locked North" : "Follow player";
            case "vrange" -> settings.mobVerticalRange + " blocks above/below";
            case "clock_format" -> settings.clock24Hour ? "24-hour" : "12-hour";
            default -> "true".equals(dropdownValue(id, settings)) ? "Enabled" : "Disabled";
        };
    }

    private static boolean isDropdownDisabled(String id, DynamicMinimapSetting settings) {
        return !settings.enabled
                || ("bettermap".equals(id) && !BetterMapIntegration.isAvailable())
                || (("height".equals(id) || "vrange".equals(id)) && !settings.showEntities)
                || ("vrange".equals(id) && !settings.limitMobHeight)
                || ("clock_format".equals(id) && !settings.showClock);
    }

    private static void applyDropdown(DynamicMinimapSetting settings, String id, String value) {
        if (isDropdownDisabled(id, settings)) return;
        if (value == null || value.isBlank()) return;
        // Accept only an offered value, including an existing custom numeric value.
        if (dropdownEntries(id, settings).stream().noneMatch(entry -> entry.value().equals(value))) return;
        switch (id) {
            case "zoom" -> settings.zoomBlocks = Integer.parseInt(value);
            case "size" -> settings.sizePixels = Integer.parseInt(value);
            case "interface_scale" -> settings.interfaceScalePercent = Integer.parseInt(value);
            case "texture_resolution" -> settings.textureResolution = Integer.parseInt(value);
            case "speed" -> settings.updateSpeed = Integer.parseInt(value);
            case "panel_position" -> settings.infoPanelPosition = Integer.parseInt(value);
            case "shape" -> settings.shape = Integer.parseInt(value);
            case "rotation" -> settings.rotationMode = Integer.parseInt(value);
            case "vrange" -> settings.mobVerticalRange = Integer.parseInt(value);
            case "players" -> settings.showOtherPlayers = Boolean.parseBoolean(value);
            case "entities" -> settings.showEntities = Boolean.parseBoolean(value);
            case "death" -> settings.showDeathMarker = Boolean.parseBoolean(value);
            case "worldmap" -> settings.showWorldMapMarkers = Boolean.parseBoolean(value);
            case "height" -> settings.limitMobHeight = Boolean.parseBoolean(value);
            case "clock" -> settings.showClock = Boolean.parseBoolean(value);
            case "clock_format" -> settings.clock24Hour = Boolean.parseBoolean(value);
            case "coordinates" -> settings.showCoordinates = Boolean.parseBoolean(value);
            case "compass" -> settings.showCompass = Boolean.parseBoolean(value);
            case "biome" -> settings.showBiome = Boolean.parseBoolean(value);
            case "bettermap" -> settings.betterMapEnabled = Boolean.parseBoolean(value);
        }
        settings.normalize();
    }

    private static List<DropdownEntryInfo> dropdownEntries(String id, DynamicMinimapSetting settings) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        switch (id) {
            case "zoom" -> addNumericEntries(entries, settings.zoomBlocks, 16, 256, 16, " blocks");
            case "size" -> addNumericEntries(entries, settings.sizePixels, 120, 360, 20, " px");
            case "vrange" -> {
                addNumericEntries(entries, settings.mobVerticalRange, 8, 256, 8, " blocks above/below");
            }
            case "interface_scale" -> {
                TreeSet<Integer> values = new TreeSet<>(List.of(75, 85, 100, 110, 125, 140, 160));
                values.add(settings.interfaceScalePercent);
                for (int value : values) entries.add(entry(value + "%", Integer.toString(value)));
            }
            case "texture_resolution" -> {
                TreeSet<Integer> values = new TreeSet<>(List.of(256, 320, 384, 448, 512, 1024));
                values.add(settings.textureResolution);
                for (int value : values) entries.add(entry(value + " px", Integer.toString(value)));
            }
            case "speed" -> {
                for (int value = 0; value <= 3; value++) entries.add(entry(displaySpeed(value), Integer.toString(value)));
            }
            case "panel_position" -> {
                for (int value = 0; value <= 3; value++) entries.add(entry(displayPanelPosition(value), Integer.toString(value)));
            }
            case "shape" -> {
                entries.add(entry("Circle", "1"));
                entries.add(entry("Square", "0"));
            }
            case "rotation" -> {
                entries.add(entry("Locked North", "0"));
                entries.add(entry("Follow player", "1"));
            }
            case "clock_format" -> {
                entries.add(entry("24-hour", "true"));
                entries.add(entry("12-hour", "false"));
            }
            case "players", "entities", "death", "worldmap", "height", "coordinates", "clock", "compass", "biome", "bettermap" -> {
                entries.add(entry("Enabled", "true"));
                entries.add(entry("Disabled", "false"));
            }
            default -> { }
        }
        return entries;
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private static void addNumericEntries(List<DropdownEntryInfo> entries, int current,
                                          int min, int max, int step, String suffix) {
        TreeSet<Integer> values = new TreeSet<>();
        for (int value = min; value <= max; value += step) values.add(value);
        values.add(current);
        if (" blocks above/below".equals(suffix)) values.add(4);
        for (int value : values) entries.add(entry(value + suffix, Integer.toString(value)));
    }

    private static Message toggleButton(String id, DynamicMinimapSetting settings) {
        return Message.raw(settings.enabled ? "ENABLED" : "DISABLED").color(settings.enabled ? GREEN : GRAY);
    }



    private static String dropdownValue(String id, DynamicMinimapSetting settings) {
        return switch (id) {
            case "zoom" -> Integer.toString(settings.zoomBlocks);
            case "size" -> Integer.toString(settings.sizePixels);
            case "interface_scale" -> Integer.toString(settings.interfaceScalePercent);
            case "texture_resolution" -> Integer.toString(settings.textureResolution);
            case "speed" -> Integer.toString(settings.updateSpeed);
            case "panel_position" -> Integer.toString(settings.infoPanelPosition);
            case "shape" -> Integer.toString(settings.shape);
            case "rotation" -> Integer.toString(settings.rotationMode);
            case "vrange" -> Integer.toString(settings.mobVerticalRange);
            case "players" -> Boolean.toString(settings.showOtherPlayers);
            case "entities" -> Boolean.toString(settings.showEntities);
            case "death" -> Boolean.toString(settings.showDeathMarker);
            case "worldmap" -> Boolean.toString(settings.showWorldMapMarkers);
            case "height" -> Boolean.toString(settings.limitMobHeight);
            case "clock" -> Boolean.toString(settings.showClock);
            case "clock_format" -> Boolean.toString(settings.clock24Hour);
            case "coordinates" -> Boolean.toString(settings.showCoordinates);
            case "compass" -> Boolean.toString(settings.showCompass);
            case "biome" -> Boolean.toString(settings.showBiome);
            case "bettermap" -> Boolean.toString(settings.betterMapEnabled);
            default -> throw new IllegalArgumentException("Unknown dropdown: " + id);
        };
    }

    private static String displayPanelPosition(int panelPosition) {
        return switch (panelPosition) {
            case DynamicMinimapSetting.INFO_ABOVE -> "Above";
            case DynamicMinimapSetting.INFO_LEFT -> "Left";
            case DynamicMinimapSetting.INFO_RIGHT -> "Right";
            case DynamicMinimapSetting.INFO_BELOW -> "Below";
            default -> "Below";
        };
    }

    private static String displaySpeed(int speed) {
        return switch (speed) {
            case 0 -> "Very fast";
            case 1 -> "Fast";
            case 3 -> "Slow";
            case 2 -> "Normal";
            default -> "Normal";
        };
    }

    static {
        ROWS = new Row[]{
                new Row("enabled", "Minimap", Kind.TOGGLE),
                new Row("section_0", "MAP APPEARANCE", Kind.HEADER),
                new Row("zoom", "Zoom Radius", Kind.DROPDOWN),
                new Row("size", "Map Size", Kind.DROPDOWN),
                new Row("interface_scale", "Interface Scale", Kind.DROPDOWN),
                new Row("shape", "Map Shape", Kind.DROPDOWN),
                new Row("rotation", "Map Rotation", Kind.DROPDOWN),
                new Row("speed", "Update Speed", Kind.DROPDOWN),
                new Row("texture_resolution", "Texture Resolution", Kind.DROPDOWN),
                new Row("colours", "Minimap Colours", Kind.ACTION),
                new Row("section_1", "POSITION", Kind.HEADER),
                new Row("move_minimap", "Screen Position", Kind.ACTION),
                new Row("panel_position", "Information Panel", Kind.DROPDOWN),
                new Row("section_2", "MARKERS", Kind.HEADER),
                new Row("players", "Player Markers", Kind.DROPDOWN),
                new Row("death", "Death Marker", Kind.DROPDOWN),
                new Row("entities", "Creature Markers", Kind.DROPDOWN),
                new Row("height", "Creature Height Detection", Kind.DROPDOWN),
                new Row("vrange", "Creature Height Range", Kind.DROPDOWN),
                new Row("section_3", "INFORMATION", Kind.HEADER),
                new Row("coordinates", "Coordinate Readout", Kind.DROPDOWN),
                new Row("clock", "Hytale World Clock", Kind.DROPDOWN),
                new Row("clock_format", "Clock Format", Kind.DROPDOWN),
                new Row("compass", "Compass Readout", Kind.DROPDOWN),
                new Row("biome", "Biome Readout", Kind.DROPDOWN),
                new Row("section_4", "INTEGRATIONS", Kind.HEADER),
                new Row("worldmap", "Hytale World Map Markers", Kind.DROPDOWN),
                new Row("bettermap", "BetterMap", Kind.DROPDOWN)
        };
    }

    public static final class Ev {

        public static final BuilderCodec<Ev> CODEC;

        private String action = "";
        private String value = "";

        public Ev() {
        }

        static {
            CODEC = BuilderCodec.builder(Ev.class, Ev::new)
                    .append(
                            new KeyedCodec<>(
                                    "Action",
                                    Codec.STRING,
                                    true
                            ),
                            (ev, value) ->
                                    ev.action = value,
                            ev ->
                                    ev.action
                    )
                    .add()
                    .append(
                            new KeyedCodec<>(
                                    "@Value",
                                    Codec.STRING,
                                    true
                            ),
                            (ev, value) ->
                                    ev.value = value,
                            ev ->
                                    ev.value
                    )
                    .add()
                    .build();
        }
    }

    private enum Kind {
        TOGGLE,
        HEADER,
        DROPDOWN,
        ACTION
    }

    private static final class Row {

        final String id;
        final String label;
        final Kind kind;

        Row(
                String id,
                String label,
                Kind kind
        ) {
            this.id = id;
            this.label = label;
            this.kind = kind;
        }
    }
}

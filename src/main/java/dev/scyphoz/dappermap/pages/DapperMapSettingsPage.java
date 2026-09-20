package dev.scyphoz.dappermap.pages;

import com.hypixel.hytale.server.core.permissions.PermissionQuery;
import dev.scyphoz.dappermap.permissions.DapperMapPermissions;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.codec.protocol.ColorCodec;
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
import dev.scyphoz.dappermap.dynamic.DynamicMinimapRenderer;
import dev.scyphoz.dappermap.dynamic.DynamicAssetPublisher;
import dev.scyphoz.dappermap.dynamic.MinimapColours;
import dev.scyphoz.dappermap.dynamic.MinimapColours.Key;
import dev.scyphoz.dappermap.integration.BetterMapIntegration;
import dev.scyphoz.dappermap.service.MinimapSessionManager;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final Color VIOLET = new Color(0xB8AEFF);
    private static final Color MUTED = new Color(0x7F89A3);

    private static final Row[] ROWS;

    private final PlayerRef playerRef;
    private final MinimapSessionManager sessions;
    private final MinimapConfig config;
    private final boolean adminMode;
    private Tab activeTab = Tab.DISPLAY;
    private Key selectedColour = Key.EDGE;
    private int previewRevision = 0;
    /** Unsaved working copy used by the settings UI and live preview. */
    private DynamicMinimapSetting draftSettings;
    /** Player reset-to-defaults must remove the override only when Apply is pressed. */
    private boolean draftResetsPlayerOverride = false;
    /**
     * Retained for source compatibility. The authoritative definition now lives in
     * {@link dev.scyphoz.dappermap.permissions.DapperMapPermissions#ADMIN}, a node kept
     * deliberately outside the player command tree so player-command wildcard grants
     * can never reach it.
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
        if (this.draftSettings == null) {
            this.draftSettings = (this.adminMode
                    ? this.sessions.getAdminDefaults(this.config)
                    : this.sessions.getSettings(this.playerRef.getUuid(), this.config)).copy();
        }
        DynamicMinimapSetting settings = this.draftSettings;

        builder.append("Pages/DapperMapSettings.ui");
        builder.set("#PlayerTopAccent.Visible", !this.adminMode);
        builder.set("#AdminTopAccent.Visible", this.adminMode);
        if (this.adminMode) {
            builder.set("#DapperMapTagline.Text", "Server defaults for new DapperMap players");
        }

        this.renderList(builder, events, settings);
        this.bindColourEditor(builder, events, settings);
        this.updatePreview(builder, settings);
        this.updateTabVisibility(builder);
        this.bindNavigation(builder, events);
        this.bindFooterButtons(builder, events);
    }

    private void bindNavigation(UICommandBuilder builder, UIEventBuilder events) {
        for (Tab tab : Tab.values()) {
            String slot = "#DapperMapTab" + tab.label + "Slot";
            builder.append(slot, "Pages/BasicTextButton.ui");
            events.addEventBinding(CustomUIEventBindingType.Activating, slot + "[1]",
                    EventData.of("Action", "tab_" + tab.id), false);
        }
        this.updateNavigationLabels(builder);
    }

    private void updateTabVisibility(UICommandBuilder builder) {
        builder.set("#DapperMapSettingsListContainer.Visible", this.activeTab != Tab.COLOURS);
        builder.set("#DapperMapColourEditor.Visible", this.activeTab == Tab.COLOURS);
        builder.set("#PageSectionTitle.Text", this.activeTab.label.toUpperCase());
        String sectionDescription = switch (this.activeTab) {
            case DISPLAY -> "Customize how DapperMap appears in-game.";
            case MARKERS -> "Choose the navigation markers shown on your instrument.";
            case INTERFACE -> "Tune the information, compass and HUD presentation.";
            case COLOURS -> "Tune the DapperMap palette and preview every change live.";
        };
        builder.set("#PageSectionDescription.Text", sectionDescription);
    }

    private void bindColourEditor(UICommandBuilder builder, UIEventBuilder events, DynamicMinimapSetting settings) {
        builder.set("#ColourTarget.Entries", Arrays.stream(Key.values())
                .map(key -> new DropdownEntryInfo(LocalizableString.fromString(key.group + " / " + key.label), key.id))
                .toList());
        builder.set("#ColourTarget.Value", this.selectedColour.id);
        builder.set("#ColourPicker.Color", settings.colours.hex(this.selectedColour));
        builder.set("#ColourDescription.Text", this.selectedColour.description);
        builder.set("#ColourHex.Text", settings.colours.hex(this.selectedColour));
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ColourTarget",
                EventData.of("Action", "colour_target").append("@Target", "#ColourTarget.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ColourPicker",
                EventData.of("Action", "colour_value").append("@Colour", "#ColourPicker.Color"), false);
        builder.append("#ColourResetSlot", "Pages/BasicTextButton.ui");
        builder.set("#ColourResetSlot[0].TextSpans", Message.raw("RESET THIS COLOUR").color(GOLD));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ColourResetSlot[0]", EventData.of("Action", "colour_reset"), false);
        builder.append("#ColourResetAllSlot", "Pages/BasicTextButton.ui");
        builder.set("#ColourResetAllSlot[0].TextSpans", Message.raw("RESET ALL COLOURS").color(GOLD));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ColourResetAllSlot[0]", EventData.of("Action", "colour_reset_all"), false);
    }

    private void refreshColourEditor(UICommandBuilder builder, DynamicMinimapSetting settings, boolean syncPicker) {
        builder.set("#ColourTarget.Value", this.selectedColour.id);
        builder.set("#ColourDescription.Text", this.selectedColour.description);
        builder.set("#ColourHex.Text", settings.colours.hex(this.selectedColour));
        if (syncPicker) builder.set("#ColourPicker.Color", settings.colours.hex(this.selectedColour));
    }

    private void updatePreview(UICommandBuilder ui, DynamicMinimapSetting settings) {
        if (!settings.enabled) {
            ui.set("#PreviewHudImage.Visible", false);
            ui.set("#PreviewDisabled.Visible", true);
            return;
        }
        byte[] png = DynamicMinimapRenderer.renderSettingsPreview(settings);
        if (png == null) return;
        String slug = this.playerRef.getUuid() == null ? "anon" : this.playerRef.getUuid().toString().replace("-", "");
        String path = "UI/DapperMap/settings-preview-" + slug + "-" + (++this.previewRevision) + ".png";
        if (DynamicAssetPublisher.deliver(this.playerRef, path, png)) {
            ui.set("#PreviewHudImage.AssetPath", path);
            ui.set("#PreviewHudImage.Visible", true);
            ui.set("#PreviewDisabled.Visible", false);
        }
    }

    private void updateNavigationLabels(UICommandBuilder builder) {
        for (Tab tab : Tab.values()) {
            boolean selected = tab == this.activeTab;
            String prefix = selected ? "○  " : "   ";
            builder.set("#DapperMapTab" + tab.label + "Active.Visible", selected);
            builder.set("#DapperMapTab" + tab.label + "Slot[1].TextSpans",
                    Message.raw(prefix + tab.label.toUpperCase()).color(selected ? VIOLET : MUTED));
        }
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

        builder.append("#DapperMapCloseButtonSlot", "Pages/BasicTextButton.ui");
        builder.set("#DapperMapCloseButtonSlot[0].TextSpans", Message.raw("CANCEL").color(VIOLET));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DapperMapCloseButtonSlot[0]",
                EventData.of("Action", "cancel_settings"), false);

        builder.append("#DapperMapApplyButtonSlot", "Pages/BasicTextButton.ui");
        builder.set("#DapperMapApplyButtonSlot[0].TextSpans",
                Message.raw("APPLY").color(this.adminMode ? GREEN : new Color(0xF0EDFF)));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DapperMapApplyButtonSlot[0]",
                EventData.of("Action", "apply_settings"), false);

        if (!this.adminMode && this.playerRef.hasPermission(ADMIN_PERMISSION)) {
            builder.append("#DapperMapAdminButtonSlot", "Pages/BasicTextButton.ui");
            builder.set("#DapperMapAdminButtonSlot[0].TextSpans", Message.raw("ADMIN SETTINGS").color(GREEN));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#DapperMapAdminButtonSlot[0]",
                    EventData.of("Action", "admin_settings"), false);
        }
    }

    private void renderList(
            UICommandBuilder builder,
            UIEventBuilder events,
            DynamicMinimapSetting settings
    ) {
        builder.clear("#DapperMapSettingsList");

        int renderedIndex = 0;
        for (Row row : ROWS) {
            if (tabFor(row.id) != this.activeTab) continue;
            int index = renderedIndex++;

            if (row.kind == Kind.HEADER) {
                builder.append("#DapperMapSettingsList", "Pages/DapperMapSectionRow.ui");
                builder.set("#DapperMapSettingsList[" + index + "] #SectionLabel.Text", row.label);
            } else if (row.kind == Kind.TOGGLE) {
                this.toggleRow(builder, events, index, row, settings);
            } else if (row.kind == Kind.DROPDOWN) {
                if (isBooleanToggle(row.id)) this.toggleRow(builder, events, index, row, settings);
                else this.dropdownRow(builder, events, index, row, settings);
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

        boolean on = toggleValue(row.id, settings);
        builder.set(selector + " #ToggleOn.Visible", on);
        builder.set(selector + " #ToggleOff.Visible", !on);

        // Integration rows keep the control visually quiet; all other binary
        // controls show the active state beside the switch in instrument cyan.
        boolean showState = !isIntegrationToggle(row.id);
        builder.set(selector + " #StateLabel.Visible", showState);
        if (showState) {
            builder.set(selector + " #StateLabel.Text", toggleStateLabel(row.id, settings));
        }

        builder.append(
                selector + " #BtnSlot",
                "Pages/BasicTextButton.ui"
        );
        builder.set(selector + " #BtnSlot[0].TextSpans", Message.raw(""));

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
        if ("cancel_settings".equals(action)) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;
            if (this.adminMode && this.sessions.adminPageOpenedFromPlayerPage(this.playerRef.getUuid())) {
                this.sessions.setAdminPageOrigin(this.playerRef.getUuid(), false);
                player.getPageManager().openCustomPage(ref, store,
                        new DapperMapSettingsPage(this.playerRef, this.sessions, this.config));
            } else {
                if (this.adminMode) this.sessions.setAdminPageOrigin(this.playerRef.getUuid(), false);
                player.getPageManager().setPage(ref, store, Page.None);
            }
            return;
        }

        if ("apply_settings".equals(action)) {
            if (this.draftSettings == null) return;
            this.draftSettings.normalize();
            if (this.adminMode) {
                this.sessions.setAdminDefaults(this.draftSettings.copy());
            } else if (this.draftResetsPlayerOverride) {
                this.sessions.resetSettings(this.playerRef, this.config);
            } else {
                this.sessions.applySettings(this.playerRef, this.draftSettings.copy());
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;
            if (this.adminMode) {
                boolean fromPlayerPage = this.sessions.adminPageOpenedFromPlayerPage(this.playerRef.getUuid());
                this.sessions.setAdminPageOrigin(this.playerRef.getUuid(), false);
                if (fromPlayerPage) {
                    player.getPageManager().openCustomPage(ref, store,
                            new DapperMapSettingsPage(this.playerRef, this.sessions, this.config));
                } else {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
            } else {
                player.getPageManager().setPage(ref, store, Page.None);
            }
            return;
        }

        if ("admin_settings".equals(action)) {
            if (!this.playerRef.hasPermission(ADMIN_PERMISSION)) return;
            this.sessions.setAdminPageOrigin(this.playerRef.getUuid(), true);
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) player.getPageManager().openCustomPage(ref, store,
                    new DapperMapSettingsPage(this.playerRef, this.sessions, this.config, true));
            return;
        }

        if (action == null || action.isEmpty()) {
            return;
        }

        if (action.startsWith("tab_")) {
            Tab requested = Tab.find(action.substring("tab_".length()));
            if (requested == null || requested == this.activeTab) return;
            this.activeTab = requested;
            DynamicMinimapSetting current = this.draftSettings;
            UICommandBuilder navBuilder = new UICommandBuilder();
            UIEventBuilder navEvents = new UIEventBuilder();
            this.renderList(navBuilder, navEvents, current);
            this.refreshColourEditor(navBuilder, current, true);
            this.updatePreview(navBuilder, current);
            this.updateTabVisibility(navBuilder);
            this.updateNavigationLabels(navBuilder);
            this.sendUpdate(navBuilder, navEvents, false);
            return;
        }

        if ("move_minimap".equals(action)) {
            if (this.draftSettings == null || !this.draftSettings.enabled) return;
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

        if ("reset_defaults".equals(action)) {
            DynamicMinimapSetting reset = this.adminMode
                    ? DynamicMinimapSetting.fromConfig(this.config)
                    : this.sessions.getAdminDefaults(this.config).copy();
            this.draftSettings = reset.copy();
            this.draftResetsPlayerOverride = !this.adminMode;
            UICommandBuilder resetBuilder = new UICommandBuilder();
            UIEventBuilder resetEvents = new UIEventBuilder();
            this.renderList(resetBuilder, resetEvents, reset);
            this.refreshColourEditor(resetBuilder, reset, true);
            this.updatePreview(resetBuilder, reset);
            this.sendUpdate(resetBuilder, resetEvents, false);
            return;
        }

        DynamicMinimapSetting settings = this.draftSettings;
        if (settings == null) return;

        if ("colour_target".equals(action)) {
            Key key = Key.find(event.target);
            if (key == null) return;
            this.selectedColour = key;
            UICommandBuilder colourUi = new UICommandBuilder();
            this.refreshColourEditor(colourUi, settings, true);
            this.sendUpdate(colourUi, new UIEventBuilder(), false);
            return;
        }
        if ("colour_value".equals(action)) {
            if (event.colour == null) return;
            int rgb = (Byte.toUnsignedInt(event.colour.red) << 16)
                    | (Byte.toUnsignedInt(event.colour.green) << 8)
                    | Byte.toUnsignedInt(event.colour.blue);
            settings.colours = settings.colours.with(this.selectedColour, rgb);
        } else if ("colour_reset".equals(action)) {
            settings.colours = settings.colours.reset(this.selectedColour);
        } else if ("colour_reset_all".equals(action)) {
            settings.colours = new MinimapColours();
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

        this.draftSettings = settings;
        this.draftResetsPlayerOverride = false;

        UICommandBuilder builder = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        this.renderList(builder, events, settings);
        this.refreshColourEditor(builder, settings, !"colour_value".equals(action));
        this.updatePreview(builder, settings);

        this.sendUpdate(builder, events, false);
    }

    private static void applyToggle(DynamicMinimapSetting settings, String id) {
        switch (id) {
            case "enabled" -> settings.enabled = !settings.enabled;
            case "players" -> settings.showOtherPlayers = !settings.showOtherPlayers;
            case "entities" -> settings.showEntities = !settings.showEntities;
            case "death" -> settings.showDeathMarker = !settings.showDeathMarker;
            case "worldmap" -> settings.showWorldMapMarkers = !settings.showWorldMapMarkers;
            case "height" -> settings.limitMobHeight = !settings.limitMobHeight;
            case "clock" -> settings.showClock = !settings.showClock;
            case "coordinates" -> settings.showCoordinates = !settings.showCoordinates;
            case "compass" -> settings.showCompass = !settings.showCompass;
            case "biome" -> settings.showBiome = !settings.showBiome;
            case "bettermap" -> settings.betterMapEnabled = !settings.betterMapEnabled;
            case "shape" -> settings.shape = settings.shape == 1 ? 0 : 1;
            case "rotation" -> settings.rotationMode = settings.rotationMode == 0 ? 1 : 0;
            case "clock_format" -> settings.clock24Hour = !settings.clock24Hour;
        }
    }


    private static boolean isIntegrationToggle(String id) {
        return "worldmap".equals(id) || "bettermap".equals(id);
    }

    private static String toggleStateLabel(String id, DynamicMinimapSetting settings) {
        return switch (id) {
            case "shape" -> settings.shape == 1 ? "CIRCLE" : "SQUARE";
            case "rotation" -> settings.rotationMode == 1 ? "PLAYER FACING" : "NORTH LOCKED";
            case "clock_format" -> settings.clock24Hour ? "24 HOUR" : "12 HOUR";
            default -> toggleValue(id, settings) ? "ENABLED" : "DISABLED";
        };
    }

    private static boolean isBooleanToggle(String id) {
        return switch (id) {
            case "players", "entities", "death", "worldmap", "height",
                 "clock", "coordinates", "compass", "biome", "bettermap",
                 "shape", "rotation", "clock_format" -> true;
            default -> false;
        };
    }

    private static boolean toggleValue(String id, DynamicMinimapSetting settings) {
        return switch (id) {
            case "enabled" -> settings.enabled;
            case "players" -> settings.showOtherPlayers;
            case "entities" -> settings.showEntities;
            case "death" -> settings.showDeathMarker;
            case "worldmap" -> settings.showWorldMapMarkers;
            case "height" -> settings.limitMobHeight;
            case "clock" -> settings.showClock;
            case "coordinates" -> settings.showCoordinates;
            case "compass" -> settings.showCompass;
            case "biome" -> settings.showBiome;
            case "bettermap" -> settings.betterMapEnabled;
            case "shape" -> settings.shape == 1;
            case "rotation" -> settings.rotationMode == 1;
            case "clock_format" -> settings.clock24Hour;
            default -> false;
        };
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
                entries.add(entry("Telemetry rail — Right", Integer.toString(DynamicMinimapSetting.INFO_RIGHT)));
                entries.add(entry("Telemetry rail — Left", Integer.toString(DynamicMinimapSetting.INFO_LEFT)));
                entries.add(entry("Telemetry rail — Above", Integer.toString(DynamicMinimapSetting.INFO_ABOVE)));
                entries.add(entry("Telemetry rail — Below", Integer.toString(DynamicMinimapSetting.INFO_BELOW)));
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
            case DynamicMinimapSetting.INFO_LEFT -> "Telemetry rail — Left";
            case DynamicMinimapSetting.INFO_RIGHT -> "Telemetry rail — Right";
            case DynamicMinimapSetting.INFO_ABOVE -> "Telemetry rail — Above";
            case DynamicMinimapSetting.INFO_BELOW -> "Telemetry rail — Below";
            default -> "Telemetry rail — Right";
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

    private static Tab tabFor(String id) {
        return switch (id) {
            case "section_2", "players", "death", "entities", "height", "vrange",
                 "section_4", "worldmap", "bettermap" -> Tab.MARKERS;
            case "section_1", "move_minimap", "panel_position", "section_3",
                 "coordinates", "clock", "clock_format", "compass", "biome" -> Tab.INTERFACE;
            default -> Tab.DISPLAY;
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
        private String target = "";
        private com.hypixel.hytale.protocol.Color colour;

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
                    .append(new KeyedCodec<>("@Target", Codec.STRING, true), (ev, value) -> ev.target = value, ev -> ev.target).add()
                    .append(new KeyedCodec<>("@Colour", new ColorCodec(), true), (ev, value) -> ev.colour = value, ev -> ev.colour).add()
                    .build();
        }
    }

    private enum Kind {
        TOGGLE,
        HEADER,
        DROPDOWN,
        ACTION
    }

    private enum Tab {
        DISPLAY("display", "Display"),
        MARKERS("markers", "Markers"),
        INTERFACE("interface", "Interface"),
        COLOURS("colours", "Colours");

        final String id;
        final String label;

        Tab(String id, String label) {
            this.id = id;
            this.label = label;
        }

        static Tab find(String id) {
            for (Tab tab : values()) if (tab.id.equals(id)) return tab;
            return null;
        }
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

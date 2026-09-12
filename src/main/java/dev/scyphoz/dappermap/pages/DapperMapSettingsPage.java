package dev.scyphoz.dappermap.pages;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.scyphoz.dappermap.config.MinimapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.service.MinimapSessionManager;

import java.awt.Color;

public final class DapperMapSettingsPage
        extends InteractiveCustomUIPage<DapperMapSettingsPage.Ev> {

    private static final Color GOLD = new Color(16766571);
    private static final Color BLUE = new Color(10345727);
    private static final Color GREEN = new Color(5757323);
    private static final Color GRAY = new Color(8491684);
    private static final Color WHITE;

    private static final int ZOOM_STEP = 16;
    private static final int SIZE_STEP = 20;
    private static final int VRANGE_STEP = 8;

    // Deliberately discrete so players can quickly return to known-good HUD scales.


    private static final int[] INTERFACE_SCALE_STEPS = {
            75, 85, 100, 110, 125, 140, 160
    };

    private static final Row[] ROWS;

    private final PlayerRef playerRef;
    private final MinimapSessionManager sessions;
    private final MinimapConfig config;

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
    }

    public void build(
            Ref<EntityStore> ref,
            UICommandBuilder builder,
            UIEventBuilder events,
            Store<EntityStore> store
    ) {
        DynamicMinimapSetting settings =
                this.sessions.getSettings(this.playerRef.getUuid(), this.config);

        builder.append("Pages/DapperMapSettings.ui");
        builder.set("#PageTitle.Text", "DapperMap");
        builder.set("#DapperMapSettingsSummary.Text", summary(settings));

        this.renderList(builder, events, settings);
        this.bindResetButton(builder, events);
    }

    private void bindResetButton(
            UICommandBuilder builder,
            UIEventBuilder events
    ) {
        builder.append(
                "#DapperMapResetButtonSlot",
                "Pages/BasicTextButton.ui"
        );

        builder.set(
                "#DapperMapResetButtonSlot[0].TextSpans",
                Message.raw("RESET TO DEFAULTS").color(GOLD)
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#DapperMapResetButtonSlot[0]",
                EventData.of("Action", "reset_defaults"),
                false
        );
    }

    private void renderList(
            UICommandBuilder builder,
            UIEventBuilder events,
            DynamicMinimapSetting settings
    ) {
        builder.clear("#DapperMapSettingsList");

        for (int index = 0; index < ROWS.length; ++index) {
            Row row = ROWS[index];

            if (row.kind == Kind.TOGGLE) {
                this.toggleRow(builder, events, index, row, settings);
            } else if (row.kind == Kind.DROPDOWN) {
                this.dropdownRow(builder, events, index, row, settings);
            } else if (row.kind == Kind.SLIDER) {
                this.sliderRow(builder, events, index, row, settings);
            } else {
                this.stepperRow(builder, events, index, row, settings);
            }
        }
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
                selector + " #RowValue.TextSpans",
                toggleValue(row.id, settings)
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

        builder.append(
                "#DapperMapSettingsList",
                dropdownTemplate(row.id)
        );

        builder.set(
                selector + " #RowLabel.Text",
                row.label
        );

        String dropdownSelector = selector + " #ValueDropdown";

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

    private static String dropdownTemplate(String id) {
        return switch (id) {
            case "zoom" ->
                    "Pages/DapperMapDropdownZoomRow.ui";

            case "size" ->
                    "Pages/DapperMapDropdownSizeRow.ui";

            case "interface_scale" ->
                    "Pages/DapperMapDropdownScaleRow.ui";

            case "speed" ->
                    "Pages/DapperMapDropdownSpeedRow.ui";

            case "panel_position" ->
                    "Pages/DapperMapDropdownPanelRow.ui";

            default ->
                    "Pages/DapperMapStepperRow.ui";
        };
    }

    private void sliderRow(
            UICommandBuilder builder,
            UIEventBuilder events,
            int index,
            Row row,
            DynamicMinimapSetting settings
    ) {
        String selector = "#DapperMapSettingsList[" + index + "]";

        builder.append(
                "#DapperMapSettingsList",
                "Pages/DapperMapSliderRow.ui"
        );

        builder.set(selector + " #RowLabel.Text", row.label);
        builder.set(selector + " #ValueLabel.Text", sliderValueText(row.id, settings));

        String sliderSelector = selector + " #ValueSlider";
        builder.set(sliderSelector + ".Value", sliderValue(row.id, settings));

        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                sliderSelector,
                EventData.of("Action", "slider_" + row.id)
                        .append("@SliderValue", sliderSelector + ".Value"),
                false
        );
    }

    private void stepperRow(
            UICommandBuilder builder,
            UIEventBuilder events,
            int index,
            Row row,
            DynamicMinimapSetting settings
    ) {
        String selector = "#DapperMapSettingsList[" + index + "]";

        builder.append(
                "#DapperMapSettingsList",
                "Pages/DapperMapStepperRow.ui"
        );

        builder.set(
                selector + " #RowLabel.Text",
                row.label
        );

        builder.set(
                selector + " #ValueLabel.Text",
                stepperValue(row.id, settings)
        );

        builder.append(
                selector + " #MinusSlot",
                "Pages/BasicTextButton.ui"
        );

        builder.set(
                selector + " #MinusSlot[0].TextSpans",
                Message.raw("-").color(WHITE)
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector + " #MinusSlot[0]",
                EventData.of("Action", row.id + "_minus"),
                false
        );

        builder.append(
                selector + " #PlusSlot",
                "Pages/BasicTextButton.ui"
        );

        builder.set(
                selector + " #PlusSlot[0].TextSpans",
                Message.raw("+").color(WHITE)
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector + " #PlusSlot[0]",
                EventData.of("Action", row.id + "_plus"),
                false
        );
    }

    public void handleDataEvent(
            Ref<EntityStore> ref,
            Store<EntityStore> store,
            Ev event
    ) {
        String action = event.action;

        if (action == null || action.isEmpty()) {
            return;
        }

        DynamicMinimapSetting settings =
                this.sessions.getSettings(this.playerRef.getUuid(), this.config);

        if ("reset_defaults".equals(action)) {
            settings = DynamicMinimapSetting.fromConfig(this.config);

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

        } else if (action.startsWith("slider_")) {
            applySlider(
                    settings,
                    action.substring("slider_".length()),
                    event.sliderValue
            );

        } else if (action.endsWith("_minus")) {
            applyStep(
                    settings,
                    action.substring(
                            0,
                            action.length() - "_minus".length()
                    ),
                    -1
            );

        } else if (action.endsWith("_plus")) {
            applyStep(
                    settings,
                    action.substring(
                            0,
                            action.length() - "_plus".length()
                    ),
                    1
            );

        } else {
            return;
        }

        this.sessions.applySettings(this.playerRef, settings);

        UICommandBuilder builder = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        builder.set(
                "#DapperMapSettingsSummary.Text",
                summary(settings)
        );

        if (action.startsWith("slider_")) {
            String id = action.substring("slider_".length());
            int rowIndex = rowIndex(id);
            if (rowIndex >= 0) {
                builder.set(
                        "#DapperMapSettingsList[" + rowIndex + "] #ValueLabel.Text",
                        sliderValueText(id, settings)
                );
            }
        } else {
            this.renderList(builder, events, settings);
        }

        this.sendUpdate(builder, events, false);
    }

    private static void applyToggle(
            DynamicMinimapSetting settings,
            String id
    ) {
        switch (id) {
            case "enabled":
                settings.enabled = !settings.enabled;
                break;

            case "shape":
                settings.shape = settings.isCircle() ? 0 : 1;
                break;

            case "players":
                settings.showOtherPlayers = !settings.showOtherPlayers;
                break;

            case "entities":
                settings.showEntities = !settings.showEntities;
                break;

            case "death":
                settings.showDeathMarker = !settings.showDeathMarker;
                break;

            case "height":
                settings.limitMobHeight = !settings.limitMobHeight;
                break;

            case "clock":
                settings.showClock = !settings.showClock;
                break;

            case "clock_format":
                settings.showClock = true;
                settings.clock24Hour = !settings.clock24Hour;
                break;

            case "compass":
                settings.showCompass = !settings.showCompass;
                break;

            case "biome":
                settings.showBiome = !settings.showBiome;
                break;

            case "rotation":
                settings.rotationMode =
                        settings.rotationMode == 0 ? 1 : 0;
                break;
        }
    }

    private static void applyDropdown(
            DynamicMinimapSetting settings,
            String id,
            String value
    ) {
        if (value == null || value.isBlank()) {
            return;
        }

        try {
            switch (id) {
                case "zoom" ->
                        settings.zoomBlocks = Integer.parseInt(value);

                case "size" ->
                        settings.sizePixels = Integer.parseInt(value);

                case "interface_scale" ->
                        settings.interfaceScalePercent = Integer.parseInt(value);

                case "speed" ->
                        settings.updateSpeed = Integer.parseInt(value);

                case "panel_position" ->
                        settings.infoPanelPosition = Integer.parseInt(value);

                default -> {
                    return;
                }
            }

            settings.normalize();
        } catch (NumberFormatException ignored) {
        }
    }

    private static void applySlider(
            DynamicMinimapSetting settings,
            String id,
            int value
    ) {
        switch (id) {
            case "hud_x" ->
                    settings.hudPositionX = value;

            case "hud_y" ->
                    settings.hudPositionY = value;

            default -> {
                return;
            }
        }

        settings.normalize();
    }

    private static void applyStep(
            DynamicMinimapSetting settings,
            String id,
            int direction
    ) {
        switch (id) {
            case "zoom" ->
                    settings.zoomBlocks += direction * ZOOM_STEP;

            case "size" ->
                    settings.sizePixels += direction * SIZE_STEP;

            case "interface_scale" ->
                    settings.interfaceScalePercent =
                            stepInterfaceScale(
                                    settings.interfaceScalePercent,
                                    direction
                            );

            case "speed" ->
                    settings.updateSpeed += direction;

            case "position" ->
                    settings.position =
                            Math.floorMod(
                                    settings.position + direction,
                                    4
                            );

            case "vrange" ->
                    settings.mobVerticalRange +=
                            direction * VRANGE_STEP;
        }
    }

    private static Message toggleButton(
            String id,
            DynamicMinimapSetting settings
    ) {
        switch (id) {
            case "shape":
                return Message.raw(
                        settings.isCircle()
                                ? "CIRCLE"
                                : "SQUARE"
                ).color(BLUE);

            case "clock_format":
                return Message.raw(
                        settings.clock24Hour
                                ? "24H"
                                : "12H"
                ).color(BLUE);

            case "rotation":
                return Message.raw(
                        settings.rotationMode == 0
                                ? "NORTH"
                                : "FOLLOW"
                ).color(BLUE);

            default:
                boolean enabled =
                        toggleState(id, settings);

                return Message.raw(
                        enabled
                                ? "ON"
                                : "OFF"
                ).color(
                        enabled
                                ? GREEN
                                : GRAY
                );
        }
    }

    private static Message toggleValue(
            String id,
            DynamicMinimapSetting settings
    ) {
        switch (id) {
            case "shape":
                return Message.raw(
                        settings.isCircle()
                                ? "Round lens"
                                : "Square lens"
                ).color(BLUE);

            case "clock_format":
                return Message.raw(
                        settings.clock24Hour
                                ? "24-hour"
                                : "12-hour"
                ).color(BLUE);

            case "rotation":
                return Message.raw(
                        settings.rotationMode == 0
                                ? "North-up"
                                : "Follow player"
                ).color(BLUE);

            default:
                boolean enabled =
                        toggleState(id, settings);

                return Message.raw(
                        enabled
                                ? "Enabled"
                                : "Disabled"
                ).color(
                        enabled
                                ? GREEN
                                : GRAY
                );
        }
    }

    private static boolean toggleState(
            String id,
            DynamicMinimapSetting settings
    ) {
        return switch (id) {
            case "enabled" -> settings.enabled;
            case "players" -> settings.showOtherPlayers;
            case "entities" -> settings.showEntities;
            case "death" -> settings.showDeathMarker;
            case "height" -> settings.limitMobHeight;
            case "clock" -> settings.showClock;
            case "compass" -> settings.showCompass;
            case "biome" -> settings.showBiome;
            default -> false;
        };
    }

    private static String stepperValue(
            String id,
            DynamicMinimapSetting settings
    ) {
        return switch (id) {
            case "zoom" ->
                    settings.zoomBlocks + " blocks";

            case "size" ->
                    settings.sizePixels + " px";

            case "interface_scale" ->
                    settings.interfaceScalePercent + "%";

            case "speed" ->
                    displaySpeed(settings.updateSpeed);

            case "position" ->
                    displayPosition(settings.position);

            case "vrange" ->
                    "+/- " + settings.mobVerticalRange;

            default -> "";
        };
    }

    private static String dropdownValue(
            String id,
            DynamicMinimapSetting settings
    ) {
        return switch (id) {
            case "zoom" -> Integer.toString(settings.zoomBlocks);
            case "size" -> Integer.toString(settings.sizePixels);
            case "interface_scale" -> Integer.toString(settings.interfaceScalePercent);
            case "speed" -> Integer.toString(settings.updateSpeed);
            case "panel_position" -> Integer.toString(settings.infoPanelPosition);
            default -> "";
        };
    }

    private static int sliderValue(
            String id,
            DynamicMinimapSetting settings
    ) {
        return switch (id) {
            case "hud_x" -> settings.hudPositionX;
            case "hud_y" -> settings.hudPositionY;
            default -> 0;
        };
    }

    private static String sliderValueText(
            String id,
            DynamicMinimapSetting settings
    ) {
        return sliderValue(id, settings) + "%";
    }

    private static int rowIndex(String id) {
        for (int i = 0; i < ROWS.length; ++i) {
            if (ROWS[i].id.equals(id)) {
                return i;
            }
        }

        return -1;
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

    private static int stepInterfaceScale(
            int current,
            int direction
    ) {
        if (direction == 0) {
            return current;
        }

        if (direction > 0) {
            for (int scale : INTERFACE_SCALE_STEPS) {
                if (scale > current) {
                    return scale;
                }
            }

            return INTERFACE_SCALE_STEPS[INTERFACE_SCALE_STEPS.length - 1];
        }

        for (int index = INTERFACE_SCALE_STEPS.length - 1; index >= 0; --index) {
            if (INTERFACE_SCALE_STEPS[index] < current) {
                return INTERFACE_SCALE_STEPS[index];
            }
        }

        return INTERFACE_SCALE_STEPS[0];
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

    private static String displayPosition(int position) {
        return switch (position) {
            case 0 -> "Top left";
            case 2 -> "Bottom left";
            case 3 -> "Bottom right";
            case 1 -> "Top right";
            default -> "Top right";
        };
    }

    private static String summary(
            DynamicMinimapSetting settings
    ) {
        return "Position "
                + settings.hudPositionX
                + "% / "
                + settings.hudPositionY
                + "%  |  Info "
                + displayPanelPosition(settings.infoPanelPosition).toLowerCase()
                + "  |  "
                + settings.zoomBlocks
                + " block radius  |  "
                + displaySpeed(settings.updateSpeed)
                + " updates  |  UI "
                + settings.interfaceScalePercent
                + "%";
    }

    static {
        WHITE = Color.WHITE;

        ROWS = new Row[]{
                new Row(
                        "enabled",
                        "Minimap",
                        Kind.TOGGLE
                ),
                new Row(
                        "zoom",
                        "Zoom radius",
                        Kind.DROPDOWN
                ),
                new Row(
                        "size",
                        "Map size",
                        Kind.DROPDOWN
                ),
                new Row(
                        "interface_scale",
                        "Interface scale",
                        Kind.DROPDOWN
                ),
                new Row(
                        "speed",
                        "Update speed",
                        Kind.DROPDOWN
                ),
                new Row(
                        "shape",
                        "Map shape",
                        Kind.TOGGLE
                ),
                new Row(
                        "hud_x",
                        "Horizontal position",
                        Kind.SLIDER
                ),
                new Row(
                        "hud_y",
                        "Vertical position",
                        Kind.SLIDER
                ),
                new Row(
                        "panel_position",
                        "Information panel",
                        Kind.DROPDOWN
                ),
                new Row(
                        "players",
                        "Player markers",
                        Kind.TOGGLE
                ),
                new Row(
                        "entities",
                        "Creature markers",
                        Kind.TOGGLE
                ),
                new Row(
                        "death",
                        "Death marker",
                        Kind.TOGGLE
                ),
                new Row(
                        "height",
                        "Height filter",
                        Kind.TOGGLE
                ),
                new Row(
                        "vrange",
                        "Creature height range",
                        Kind.STEPPER
                ),
                new Row(
                        "clock",
                        "In-game clock",
                        Kind.TOGGLE
                ),
                new Row(
                        "clock_format",
                        "Clock format",
                        Kind.TOGGLE
                ),
                new Row(
                        "compass",
                        "Compass readout",
                        Kind.TOGGLE
                ),
                new Row(
                        "biome",
                        "Biome readout",
                        Kind.TOGGLE
                ),
                new Row(
                        "rotation",
                        "Map rotation",
                        Kind.TOGGLE
                )
        };
    }

    public static final class Ev {

        public static final BuilderCodec<Ev> CODEC;

        private String action = "";
        private String value = "";
        private int sliderValue = 0;

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
                    .append(
                            new KeyedCodec<>(
                                    "@SliderValue",
                                    Codec.INTEGER,
                                    true
                            ),
                            (ev, value) ->
                                    ev.sliderValue = value,
                            ev ->
                                    ev.sliderValue
                    )
                    .add()
                    .build();
        }
    }

    private enum Kind {
        TOGGLE,
        STEPPER,
        DROPDOWN,
        SLIDER
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
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

        if (action.startsWith("toggle_")) {
            applyToggle(
                    settings,
                    action.substring("toggle_".length())
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

        this.renderList(builder, events, settings);
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

            case "speed" ->
                    displaySpeed(settings.updateSpeed);

            case "position" ->
                    displayPosition(settings.position);

            case "vrange" ->
                    "+/- " + settings.mobVerticalRange;

            default -> "";
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
        return displayPosition(settings.position)
                + "  |  "
                + settings.zoomBlocks
                + " block radius  |  "
                + displaySpeed(settings.updateSpeed)
                + " updates";
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
                        Kind.STEPPER
                ),
                new Row(
                        "size",
                        "Map size",
                        Kind.STEPPER
                ),
                new Row(
                        "speed",
                        "Update speed",
                        Kind.STEPPER
                ),
                new Row(
                        "shape",
                        "Map shape",
                        Kind.TOGGLE
                ),
                new Row(
                        "position",
                        "Screen corner",
                        Kind.STEPPER
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
                    .build();
        }
    }

    private enum Kind {
        TOGGLE,
        STEPPER
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
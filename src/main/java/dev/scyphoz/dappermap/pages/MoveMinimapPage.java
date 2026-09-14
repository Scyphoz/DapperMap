package dev.scyphoz.dappermap.pages;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
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
import dev.scyphoz.dappermap.service.MinimapSessionManager;

import java.awt.Color;

/**
 * Visual minimap-position chooser.
 *
 * The preview surface is divided into an invisible 9x9 click matrix. Clicking
 * anywhere in the preview selects the nearest placement region, and the sliders below it allow 1% fine tuning.
 * Both controls edit a pending position; only Save writes it to player settings.
 */
public final class MoveMinimapPage
        extends InteractiveCustomUIPage<MoveMinimapPage.Ev> {

    private static final int[] STOPS = {
            0, 13, 25, 38, 50, 63, 75, 88, 100
    };

    private static final Color GOLD = new Color(16766571);
    private static final Color BLUE = new Color(10345727);
    private static final Color WHITE = Color.WHITE;

    private final PlayerRef playerRef;
    private final MinimapSessionManager sessions;
    private final MinimapConfig config;

    private int pendingX;
    private int pendingY;
    private final int previewShape;
    private final int previewPanelPosition;

    public MoveMinimapPage(
            PlayerRef playerRef,
            MinimapSessionManager sessions,
            MinimapConfig config
    ) {
        super(
                playerRef,
                CustomPageLifetime.CanDismiss,
                Ev.CODEC
        );

        this.playerRef = playerRef;
        this.sessions = sessions;
        this.config = config;

        DynamicMinimapSetting settings =
                sessions.getSettings(playerRef.getUuid(), config);

        this.pendingX = settings.hudPositionX;
        this.pendingY = settings.hudPositionY;
        this.previewShape = settings.shape;
        this.previewPanelPosition = settings.infoPanelPosition;
    }

    @Override
    public void build(
            Ref<EntityStore> ref,
            UICommandBuilder builder,
            UIEventBuilder events,
            Store<EntityStore> store
    ) {
        builder.append("Pages/DapperMapMoveMinimap.ui");

        builder.clear("#MovePreviewCanvas");
        builder.appendInline(
                "#MovePreviewCanvas",
                buildPreviewMarkup()
        );

        builder.set(
                "#MoveMinimapPosition.Text",
                positionText()
        );

        builder.set(
                "#MovePreviewDescription.Text",
                previewDescription()
        );

        bindPositionSliders(builder, events);
        bindPlacementGrid(builder, events);
        bindButtons(builder, events);
    }

    private void bindPositionSliders(UICommandBuilder builder, UIEventBuilder events) {
        String[] labels = {"Horizontal Position", "Vertical Position"};
        String[] actions = {"slider_x", "slider_y"};
        String[] descriptions = {
                "Move the minimap left or right on your screen.",
                "Move the minimap up or down on your screen."
        };
        for (int index = 0; index < labels.length; index++) {
            String row = "#MoveFineTuneList[" + index + "]";
            builder.append("#MoveFineTuneList", "Pages/DapperMapSliderRow.ui");
            builder.set(row + " #RowLabel.Text", labels[index]);
            builder.set(row + " #RowDescription.Text", descriptions[index]);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, row + " #ValueSlider",
                    EventData.of("Action", actions[index])
                            .append("@SliderValue", row + " #ValueSlider.Value"), false);
        }
        updateSliderControls(builder, true);
    }

    private void updateSliderControls(UICommandBuilder builder, boolean syncValues) {
        int[] values = {this.pendingX, this.pendingY};
        for (int index = 0; index < values.length; index++) {
            String row = "#MoveFineTuneList[" + index + "]";
            builder.set(row + " #ValueLabel.Text", values[index] + "%");
            // Do not overwrite the dragged thumb with a delayed server update.
            if (syncValues) builder.set(row + " #ValueSlider.Value", values[index]);
        }
    }

    private void bindPlacementGrid(
            UICommandBuilder builder,
            UIEventBuilder events
    ) {
        for (int row = 0; row < STOPS.length; ++row) {
            int y = STOPS[row];

            for (int column = 0; column < STOPS.length; ++column) {
                int x = STOPS[column];
                String slot =
                        "#MovePreviewSlot" + row + column;
                String button =
                        slot + "[0]";

                // Reuse the same BasicTextButton asset already proven by the
                // Settings page instead of declaring raw Button markup here.
                builder.append(
                        slot,
                        "Pages/BasicTextButton.ui"
                );

                boolean selected =
                        x == nearestStop(this.pendingX)
                                && y == nearestStop(this.pendingY);

                // Keep the placement targets visible and ASCII-only so Hytale's
                // font renderer cannot substitute unsupported glyphs with '?'.
                builder.set(
                        button + ".TextSpans",
                        Message.raw(selected ? "X" : "+")
                                .color(selected ? GOLD : BLUE)
                );

                events.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        button,
                        EventData.of(
                                "Action",
                                "place_" + x + "_" + y
                        ),
                        false
                );
            }
        }
    }

    private void bindButtons(
            UICommandBuilder builder,
            UIEventBuilder events
    ) {
        builder.append(
                "#MoveMinimapSaveSlot",
                "Pages/BasicTextButton.ui"
        );

        builder.set(
                "#MoveMinimapSaveSlot[0].TextSpans",
                Message.raw("SAVE POSITION").color(GOLD)
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#MoveMinimapSaveSlot[0]",
                EventData.of("Action", "save"),
                false
        );

        builder.append(
                "#MoveMinimapCancelSlot",
                "Pages/BasicTextButton.ui"
        );

        builder.set(
                "#MoveMinimapCancelSlot[0].TextSpans",
                Message.raw("CANCEL").color(WHITE)
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#MoveMinimapCancelSlot[0]",
                EventData.of("Action", "cancel"),
                false
        );
    }

    @Override
    public void handleDataEvent(
            Ref<EntityStore> ref,
            Store<EntityStore> store,
            Ev event
    ) {
        String action = event.action;
        if (action == null || action.isEmpty()) {
            return;
        }

        if ("slider_x".equals(action) || "slider_y".equals(action)) {
            if ("slider_x".equals(action)) this.pendingX = clamp(event.sliderValue);
            else this.pendingY = clamp(event.sliderValue);
            refreshPreview(false);
            return;
        }

        if (action.startsWith("place_")) {
            String[] parts = action.split("_");
            if (parts.length == 3) {
                try {
                    this.pendingX =
                            clamp(Integer.parseInt(parts[1]));
                    this.pendingY =
                            clamp(Integer.parseInt(parts[2]));
                    refreshPreview(true);
                } catch (NumberFormatException ignored) {
                }
            }

            return;
        }

        if ("save".equals(action)) {
            DynamicMinimapSetting settings =
                    this.sessions.getSettings(
                            this.playerRef.getUuid(),
                            this.config
                    );

            settings.hudPositionX = this.pendingX;
            settings.hudPositionY = this.pendingY;
            settings.normalize();

            this.sessions.applySettings(
                    this.playerRef,
                    settings
            );

            openSettings(ref, store);
            return;
        }

        if ("cancel".equals(action)) {
            openSettings(ref, store);
        }
    }

    private void refreshPreview(boolean syncSliders) {
        UICommandBuilder builder = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        builder.set(
                "#MoveMinimapPosition.Text",
                positionText()
        );
        builder.set(
                "#MovePreviewDescription.Text",
                previewDescription()
        );

        updateSliderControls(builder, syncSliders);

        int selectedX = nearestStop(this.pendingX);
        int selectedY = nearestStop(this.pendingY);

        // Update the visible placement markers without rebuilding/rebinding the
        // buttons. '+' means available target; yellow 'X' is the current choice.
        for (int row = 0; row < STOPS.length; ++row) {
            int y = STOPS[row];

            for (int column = 0; column < STOPS.length; ++column) {
                int x = STOPS[column];
                String button =
                        "#MovePreviewSlot" + row + column + "[0]";
                boolean selected =
                        x == selectedX && y == selectedY;

                builder.set(
                        button + ".TextSpans",
                        Message.raw(selected ? "X" : "+")
                                .color(selected ? GOLD : BLUE)
                );
            }
        }

        // Move the preview widget using the same flex-spacer idea as the HUD.
        builder.clear("#MovePreviewCanvas");
        builder.appendInline(
                "#MovePreviewCanvas",
                buildPreviewMarkup()
        );

        this.sendUpdate(builder, events, false);
    }

    private String buildPreviewMarkup() {
        int x = this.pendingX;
        int y = this.pendingY;

        int mapSize = 86;
        int gap = 5;
        int infoWidth = 62;
        int infoHeight = 34;

        boolean side =
                this.previewPanelPosition == DynamicMinimapSetting.INFO_LEFT
                        || this.previewPanelPosition == DynamicMinimapSetting.INFO_RIGHT;

        int widgetWidth = side
                ? mapSize + gap + infoWidth
                : mapSize;

        int widgetHeight = side
                ? mapSize
                : mapSize + gap + infoHeight;

        StringBuilder out = new StringBuilder(2600);
        out.append("Group #MovePreviewLayout { Anchor: (Full: 0); LayoutMode: Top; ");

        if (y > 0) {
            out.append("Group { FlexWeight: ")
                    .append(y)
                    .append("; } ");
        }

        out.append("Group { Anchor: (Height: ")
                .append(widgetHeight)
                .append("); LayoutMode: Left; ");

        if (x > 0) {
            out.append("Group { FlexWeight: ")
                    .append(x)
                    .append("; } ");
        }

        out.append("Group #MovePreviewWidget { Anchor: (Width: ")
                .append(widgetWidth)
                .append(", Height: ")
                .append(widgetHeight)
                .append("); ");

        out.append(buildPreviewMapMarkup(mapSize));
        out.append(buildPreviewInfoMarkup(mapSize, gap, infoWidth, infoHeight));
        out.append("} ");

        if (x < 100) {
            out.append("Group { FlexWeight: ")
                    .append(100 - x)
                    .append("; } ");
        }

        out.append("} ");

        if (y < 100) {
            out.append("Group { FlexWeight: ")
                    .append(100 - y)
                    .append("; } ");
        }

        out.append("} ");
        return out.toString();
    }

    private String buildPreviewMapMarkup(int mapSize) {
        int left = 0;
        int top = 0;

        if (this.previewPanelPosition == DynamicMinimapSetting.INFO_LEFT) {
            left = 67;
        } else if (this.previewPanelPosition == DynamicMinimapSetting.INFO_ABOVE) {
            top = 39;
        }

        // Keep AppendInline markup deliberately simple. The page document itself
        // carries the textual description of circle/square shape.
        String background = this.previewShape == DynamicMinimapSetting.SHAPE_CIRCLE
                ? "#12384b(0.98)"
                : "#102d3f(0.98)";

        return "Group #MovePreviewMap { Anchor: (Left: "
                + left
                + ", Top: "
                + top
                + ", Width: "
                + mapSize
                + ", Height: "
                + mapSize
                + "); Background: "
                + background
                + "; }";
    }

    private String buildPreviewInfoMarkup(
            int mapSize,
            int gap,
            int infoWidth,
            int infoHeight
    ) {
        int left;
        int top;
        int width;
        int height;

        switch (this.previewPanelPosition) {
            case DynamicMinimapSetting.INFO_ABOVE -> {
                left = 0;
                top = 0;
                width = mapSize;
                height = infoHeight;
            }
            case DynamicMinimapSetting.INFO_LEFT -> {
                left = 0;
                top = (mapSize - infoHeight) / 2;
                width = infoWidth;
                height = infoHeight;
            }
            case DynamicMinimapSetting.INFO_RIGHT -> {
                left = mapSize + gap;
                top = (mapSize - infoHeight) / 2;
                width = infoWidth;
                height = infoHeight;
            }
            case DynamicMinimapSetting.INFO_BELOW -> {
                left = 0;
                top = mapSize + gap;
                width = mapSize;
                height = infoHeight;
            }
            default -> {
                left = 0;
                top = mapSize + gap;
                width = mapSize;
                height = infoHeight;
            }
        }

        return "Group #MovePreviewInfo { Anchor: (Left: "
                + left
                + ", Top: "
                + top
                + ", Width: "
                + width
                + ", Height: "
                + height
                + "); Background: #071016(0.96); }";
    }

    private String previewDescription() {
        String shape = this.previewShape == DynamicMinimapSetting.SHAPE_CIRCLE
                ? "Circle"
                : "Square";

        String panel = switch (this.previewPanelPosition) {
            case DynamicMinimapSetting.INFO_ABOVE -> "panel above";
            case DynamicMinimapSetting.INFO_LEFT -> "panel left";
            case DynamicMinimapSetting.INFO_RIGHT -> "panel right";
            case DynamicMinimapSetting.INFO_BELOW -> "panel below";
            default -> "panel below";
        };

        return shape + " minimap • " + panel;
    }

    private void openSettings(
            Ref<EntityStore> ref,
            Store<EntityStore> store
    ) {
        Player player =
                (Player) store.getComponent(
                        ref,
                        Player.getComponentType()
                );

        if (player != null) {
            player.getPageManager().openCustomPage(
                    ref,
                    store,
                    new DapperMapSettingsPage(
                            this.playerRef,
                            this.sessions,
                            this.config
                    )
            );
        }
    }

    private String positionText() {
        return "Horizontal "
                + this.pendingX
                + "%   •   Vertical "
                + this.pendingY
                + "%";
    }

    private static int nearestStop(int value) {
        int best = STOPS[0];
        int distance = Math.abs(value - best);

        for (int stop : STOPS) {
            int candidate = Math.abs(value - stop);
            if (candidate < distance) {
                best = stop;
                distance = candidate;
            }
        }

        return best;
    }


    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public static final class Ev {
        public static final BuilderCodec<Ev> CODEC;

        private String action = "";
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
                            new KeyedCodec<>("@SliderValue", Codec.INTEGER, true),
                            (ev, value) -> ev.sliderValue = value,
                            ev -> ev.sliderValue
                    )
                    .add()
                    .build();
        }
    }
}

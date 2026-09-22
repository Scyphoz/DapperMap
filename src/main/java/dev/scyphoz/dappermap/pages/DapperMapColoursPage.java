package dev.scyphoz.dappermap.pages;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.codec.protocol.ColorCodec;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.scyphoz.dappermap.config.MinimapConfig;
import dev.scyphoz.dappermap.dynamic.MinimapColours;
import dev.scyphoz.dappermap.dynamic.MinimapColours.Key;
import dev.scyphoz.dappermap.permissions.DapperMapPermissions;
import dev.scyphoz.dappermap.service.MinimapSessionManager;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Native colour-picker editor. Changes stay local until Save; cancellation never writes settings. */
public final class DapperMapColoursPage extends InteractiveCustomUIPage<DapperMapColoursPage.Ev> {
    private static final long PULSE_MILLIS = 700L;
    private static final ScheduledExecutorService PULSE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "DapperMap-colour-preview-pulse");
                thread.setDaemon(true);
                return thread;
            });

    private final PlayerRef owner;
    private final MinimapSessionManager sessions;
    private final MinimapConfig config;
    private final boolean adminMode;
    private MinimapColours draft;
    private Key selected = Key.EDGE;
    private final EnumSet<Key> edited = EnumSet.noneOf(Key.class);
    private volatile boolean pageActive;
    private volatile boolean pulseBright = true;
    private ScheduledFuture<?> pulseTask;

    public DapperMapColoursPage(PlayerRef owner, MinimapSessionManager sessions, MinimapConfig config) {
        super(owner, CustomPageLifetime.CanDismiss, Ev.CODEC);
        this.owner = owner;
        this.sessions = sessions;
        this.config = config;
        this.adminMode = false;
        this.draft = sessions.getSettings(owner.getUuid(), config).colours;
    }

    public DapperMapColoursPage(PlayerRef owner, MinimapSessionManager sessions, MinimapConfig config, boolean adminMode) {
        super(owner, CustomPageLifetime.CanDismiss, Ev.CODEC);
        this.owner = owner; this.sessions = sessions; this.config = config; this.adminMode = adminMode;
        this.draft = (adminMode ? sessions.getAdminDefaults(config) : sessions.getSettings(owner.getUuid(), config)).colours;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder events, Store<EntityStore> store) {
        if (!this.canEdit()) return;
        ui.append(adminMode ? "Pages/DapperMapAdminColours.ui" : "Pages/DapperMapColours.ui");
        ui.set("#ColourTarget.Entries", Arrays.stream(Key.values())
                .filter(DapperMapColoursPage::isEditorVisible)
                .map(key -> new DropdownEntryInfo(
                        LocalizableString.fromString(key.group + " / " + key.label),
                        key.id
                )).toList());
        ui.set("#ColourTarget.Value", selected.id);

        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#ColourTarget",
                EventData.of("Action", "target")
                        .append("@Target", "#ColourTarget.Value"),
                false
        );

        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#ColourPicker",
                EventData.of("Action", "colour")
                        .append("@Colour", "#ColourPicker.Color"),
                false
        );

        button(ui, events, "#ColourResetSlot", "RESET THIS COLOUR", "reset_one");
        button(ui, events, "#ColourResetAllSlot", "RESET ALL COLOURS", "reset_all");
        button(ui, events, "#ColourSaveSlot", "SAVE COLOURS", "save", new java.awt.Color(0xFFD66B));
        button(ui, events, "#ColourCancelSlot", "CANCEL", "cancel", new java.awt.Color(0xFFD66B));

        refresh(ui, true);
        startPulse();
    }

    private static void button(UICommandBuilder ui, UIEventBuilder events, String slot, String text, String action) {
        button(ui, events, slot, text, action, null);
    }

    private static void button(
            UICommandBuilder ui,
            UIEventBuilder events,
            String slot,
            String text,
            String action,
            java.awt.Color textColour
    ) {
        ui.append(slot, "Pages/BasicTextButton.ui");
        Message label = Message.raw(text);
        if (textColour != null) {
            label = label.color(textColour);
        }
        ui.set(slot + "[0].TextSpans", label);
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                slot + "[0]",
                EventData.of("Action", action),
                false
        );
    }

    private void refresh(UICommandBuilder ui, boolean syncPicker) {
        ui.set("#ColourDescription.Text", selected.description);
        ui.set("#ColourHex.Text", draft.hex(selected));
        ui.set(
                "#ColourChanges.Text",
                edited.isEmpty()
                        ? "Choose a HUD element, then pick its colour."
                        : "Unsaved colour changes. Choose Save Colours to apply them."
        );

        if (syncPicker) {
            // ColorPickerDropdownBox uses the Color property, not Value.
            ui.set("#ColourPicker.Color", draft.hex(selected));
        }

        updatePreviewColours(ui);
        applyPulse(ui, pulseBright);
    }

    private void updatePreviewColours(UICommandBuilder ui) {
        // Frame / map.
        ui.set("#PreviewShadow.Background", "#03080C");
        ui.set("#PreviewOuterEdge.Background", draft.hex(Key.EDGE));
        ui.set("#PreviewMap.Background", "#0A1218");

        for (String selector : new String[]{
                "#PreviewTickTop", "#PreviewTickRight", "#PreviewTickBottom", "#PreviewTickLeft"
        }) {
            ui.set(selector + ".Background", draft.hex(Key.TICKS));
        }

        int cardinals = draft.rgb(Key.CARDINAL_TEXT);
        ui.set("#PreviewNorth.TextSpans", Message.raw("N").color(new java.awt.Color(cardinals)));
        ui.set("#PreviewEast.TextSpans", Message.raw("E").color(new java.awt.Color(cardinals)));
        ui.set("#PreviewSouth.TextSpans", Message.raw("S").color(new java.awt.Color(cardinals)));
        ui.set("#PreviewWest.TextSpans", Message.raw("W").color(new java.awt.Color(cardinals)));

        // Information panel.
        ui.set("#PreviewPanel.Background", draft.hex(Key.PANEL));
        ui.set("#PreviewCoords.Background", draft.hex(Key.PANEL));
        ui.set("#PreviewCompass.Background", draft.hex(Key.PANEL));
        ui.set("#PreviewClock.Background", draft.hex(Key.PANEL));
        ui.set("#PreviewBiome.Background", draft.hex(Key.PANEL));
        ui.set("#PreviewBrand.TextSpans", Message.raw("DapperMap").color(new java.awt.Color(draft.rgb(Key.BRAND_TEXT))));
        int headings = draft.rgb(Key.INFO_HEADERS);
        ui.set("#PreviewCoordsHeader.TextSpans", Message.raw("COORDINATES").color(new java.awt.Color(headings)));
        ui.set("#PreviewCompassHeader.TextSpans", Message.raw("BEARING").color(new java.awt.Color(headings)));
        ui.set("#PreviewClockHeader.TextSpans", Message.raw("WORLD TIME").color(new java.awt.Color(headings)));
        ui.set("#PreviewBiomeHeader.TextSpans", Message.raw("REGION").color(new java.awt.Color(headings)));
        ui.set("#PreviewCoordsText.TextSpans", Message.raw("X 124  Y 67  Z -302").color(new java.awt.Color(draft.rgb(Key.COORDS_TEXT))));
        ui.set("#PreviewCompassText.TextSpans", Message.raw("NW 315°").color(new java.awt.Color(draft.rgb(Key.COMPASS_TEXT))));
        ui.set("#PreviewClockText.TextSpans", Message.raw("18:42").color(new java.awt.Color(draft.rgb(Key.CLOCK_TEXT))));
        ui.set("#PreviewBiomeText.TextSpans", Message.raw("Emerald Grove").color(new java.awt.Color(draft.rgb(Key.BIOME_TEXT))));

        // Marker examples. The common outline is represented by the marker backing plates.
        ui.set("#PreviewSelfOutline.Background", "#000000");
        ui.set("#PreviewPlayerOutline.Background", "#000000");
        ui.set("#PreviewDeathOutline.TextSpans", Message.raw("✕").color(new java.awt.Color(0x000000)));
        ui.set("#PreviewHostileActiveOutline.Background", "#000000");
        ui.set("#PreviewHostileOutline.Background", "#000000");
        ui.set("#PreviewNeutralOutline.Background", "#000000");
        ui.set("#PreviewFriendlyOutline.Background", "#000000");

        ui.set("#PreviewSelf.TextSpans", Message.raw("^").color(new java.awt.Color(draft.rgb(Key.SELF))));
        ui.set("#PreviewPlayer.Background", draft.hex(Key.PLAYERS));
        ui.set("#PreviewDeath.TextSpans", Message.raw("✕").color(new java.awt.Color(draft.rgb(Key.DEATH))));
        ui.set("#PreviewHostileActive.Background", draft.hex(Key.HOSTILE_ACTIVE));
        ui.set("#PreviewHostile.Background", draft.hex(Key.HOSTILE));
        ui.set("#PreviewNeutral.Background", draft.hex(Key.NEUTRAL));
        ui.set("#PreviewFriendly.Background", draft.hex(Key.FRIENDLY));
    }

    private void startPulse() {
        stopPulse();
        pageActive = true;
        pulseTask = PULSE_EXECUTOR.scheduleAtFixedRate(() -> {
            if (!pageActive) return;
            pulseBright = !pulseBright;
            try {
                UICommandBuilder ui = new UICommandBuilder();
                applyPulse(ui, pulseBright);
                sendUpdate(ui, new UIEventBuilder(), false);
            } catch (RuntimeException ignored) {
                // The page may have been replaced between the activity check and sendUpdate.
            }
        }, PULSE_MILLIS, PULSE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void stopPulse() {
        pageActive = false;
        ScheduledFuture<?> task = pulseTask;
        pulseTask = null;
        if (task != null) task.cancel(false);
    }

    private void applyPulse(UICommandBuilder ui, boolean bright) {
        // Hytale 0.6.3 custom UI Groups/Labels do not expose an Opacity property
        // through UICommandBuilder.set(). Pulse by temporarily changing only the
        // selected element's existing colour-bearing property instead.
        int rgb = draft.rgb(selected);
        setPreviewColour(ui, selected, bright ? rgb : pulseRgb(rgb));
    }

    private static int pulseRgb(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;

        // Dark colours pulse lighter; already-bright colours pulse darker. This keeps
        // black and white (both valid user choices) visibly animated as well.
        if (luminance < 150) {
            r = r + ((255 - r) * 45 / 100);
            g = g + ((255 - g) * 45 / 100);
            b = b + ((255 - b) * 45 / 100);
        } else {
            r = r * 55 / 100;
            g = g * 55 / 100;
            b = b * 55 / 100;
        }
        return (r << 16) | (g << 8) | b;
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static Message coloured(String text, int rgb) {
        return Message.raw(text).color(new java.awt.Color(rgb));
    }

    private static void setPreviewColour(UICommandBuilder ui, Key key, int rgb) {
        String colour = hex(rgb);
        switch (key) {
            case EDGE -> ui.set("#PreviewOuterEdge.Background", colour);
            case TICKS -> {
                ui.set("#PreviewTickTop.Background", colour);
                ui.set("#PreviewTickRight.Background", colour);
                ui.set("#PreviewTickBottom.Background", colour);
                ui.set("#PreviewTickLeft.Background", colour);
            }
            case CARDINAL_TEXT -> {
                ui.set("#PreviewNorth.TextSpans", coloured("N", rgb));
                ui.set("#PreviewEast.TextSpans", coloured("E", rgb));
                ui.set("#PreviewSouth.TextSpans", coloured("S", rgb));
                ui.set("#PreviewWest.TextSpans", coloured("W", rgb));
            }
            case PANEL -> {
                ui.set("#PreviewPanel.Background", colour);
                ui.set("#PreviewCoords.Background", colour);
                ui.set("#PreviewCompass.Background", colour);
                ui.set("#PreviewClock.Background", colour);
                ui.set("#PreviewBiome.Background", colour);
            }
            case BRAND_TEXT -> ui.set("#PreviewBrand.TextSpans", coloured("DapperMap", rgb));
            case INFO_HEADERS -> {
                ui.set("#PreviewCoordsHeader.TextSpans", coloured("COORDINATES", rgb));
                ui.set("#PreviewCompassHeader.TextSpans", coloured("BEARING", rgb));
                ui.set("#PreviewClockHeader.TextSpans", coloured("WORLD TIME", rgb));
                ui.set("#PreviewBiomeHeader.TextSpans", coloured("REGION", rgb));
            }
            case COORDS_TEXT -> ui.set("#PreviewCoordsText.TextSpans", coloured("X 124  Y 67  Z -302", rgb));
            case COMPASS_TEXT -> ui.set("#PreviewCompassText.TextSpans", coloured("NW 315°", rgb));
            case CLOCK_TEXT -> ui.set("#PreviewClockText.TextSpans", coloured("18:42", rgb));
            case BIOME_TEXT -> ui.set("#PreviewBiomeText.TextSpans", coloured("Emerald Grove", rgb));
            case SELF -> ui.set("#PreviewSelf.TextSpans", coloured("^", rgb));
            case PLAYERS -> ui.set("#PreviewPlayer.Background", colour);
            case DEATH -> ui.set("#PreviewDeath.TextSpans", coloured("✕", rgb));
            case HOSTILE_ACTIVE -> ui.set("#PreviewHostileActive.Background", colour);
            case HOSTILE -> ui.set("#PreviewHostile.Background", colour);
            case NEUTRAL -> ui.set("#PreviewNeutral.Background", colour);
            case FRIENDLY -> ui.set("#PreviewFriendly.Background", colour);
        }
    }
    private static boolean isEditorVisible(Key key) {
        return true;
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Ev event) {
        if (!this.canEdit()) return;
        String action = event.action;
        if (action == null) return;

        if ("cancel".equals(action)) {
            stopPulse();
            openSettings(ref, store);
            return;
        }

        if ("save".equals(action)) {
            var settings = adminMode ? sessions.getAdminDefaults(config).copy() : sessions.getSettings(owner.getUuid(), config);
            if (!settings.enabled) {
                UICommandBuilder ui = new UICommandBuilder();
                ui.set("#ColourChanges.Text", "Minimap is disabled. Cancel, enable it, then edit colours.");
                sendUpdate(ui, new UIEventBuilder(), false);
                return;
            }

            // Only touched keys overwrite live settings (commands may run while this page is open).
            settings.colours = mergeDraft(settings.colours);
            if (adminMode) sessions.setAdminDefaults(settings); else sessions.applySettings(owner, settings);
            stopPulse();
            openSettings(ref, store);
            return;
        }

        boolean syncPicker = true;

        if ("target".equals(action)) {
            Key key = Key.find(event.target);
            if (key == null || !isEditorVisible(key)) return;
            selected = key;
            pulseBright = true;
        } else if ("reset_one".equals(action)) {
            draft = draft.reset(selected);
            edited.add(selected);
        } else if ("reset_all".equals(action)) {
            for (Key key : Key.values()) {
                if (isEditorVisible(key)) {
                    draft = draft.reset(key);
                    edited.add(key);
                }
            }
        } else if ("colour".equals(action)) {
            if (event.colour == null) return;

            int rgb = (Byte.toUnsignedInt(event.colour.red) << 16)
                    | (Byte.toUnsignedInt(event.colour.green) << 8)
                    | Byte.toUnsignedInt(event.colour.blue);

            draft = draft.with(selected, rgb);
            edited.add(selected);

            // Leave the picker itself client-controlled while its popup is open, but
            // immediately update the hex readout and preview from the server-side draft.
            syncPicker = false;
        } else {
            return;
        }

        UICommandBuilder ui = new UICommandBuilder();
        refresh(ui, syncPicker);
        sendUpdate(ui, new UIEventBuilder(), false);
    }

    private MinimapColours mergeDraft(MinimapColours live) {
        MinimapColours result = live;
        for (Key key : edited) result = result.with(key, draft.rgb(key));
        return result;
    }

    private boolean canEdit() {
        return this.adminMode
                ? this.owner.hasPermission(DapperMapSettingsPage.ADMIN_PERMISSION)
                : this.owner.hasPermission(DapperMapPermissions.player("colours"));
    }

    private void openSettings(Ref<EntityStore> ref, Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(
                    ref,
                    store,
                    new DapperMapSettingsPage(owner, sessions, config, adminMode)
            );
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        stopPulse();
        super.onDismiss(ref, store);
    }


    public static final class Ev {
        private String action = "";
        private String target = "";
        private Color colour;

        public static final BuilderCodec<Ev> CODEC = BuilderCodec.builder(Ev.class, Ev::new)
                .append(new KeyedCodec<>("Action", Codec.STRING, true), (e, v) -> e.action = v, e -> e.action).add()
                .append(new KeyedCodec<>("@Target", Codec.STRING, true), (e, v) -> e.target = v, e -> e.target).add()
                .append(new KeyedCodec<>("@Colour", new ColorCodec(), true), (e, v) -> e.colour = v, e -> e.colour).add()
                .build();
    }
}

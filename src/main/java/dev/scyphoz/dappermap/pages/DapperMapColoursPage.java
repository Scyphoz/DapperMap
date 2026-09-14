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
import dev.scyphoz.dappermap.service.MinimapSessionManager;
import java.util.Arrays;
import java.util.EnumSet;

/** RGB editor: changes stay local until Save; cancellation never writes settings. */
public final class DapperMapColoursPage extends InteractiveCustomUIPage<DapperMapColoursPage.Ev> {
    private final PlayerRef owner;
    private final MinimapSessionManager sessions;
    private final MinimapConfig config;
    private MinimapColours draft;
    private Key selected = Key.EDGE;
    private final EnumSet<Key> edited = EnumSet.noneOf(Key.class);

    public DapperMapColoursPage(PlayerRef owner, MinimapSessionManager sessions, MinimapConfig config) {
        super(owner, CustomPageLifetime.CanDismiss, Ev.CODEC);
        this.owner = owner; this.sessions = sessions; this.config = config;
        this.draft = sessions.getSettings(owner.getUuid(), config).colours;
    }

    @Override public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder events, Store<EntityStore> store) {
        ui.append("Pages/DapperMapColours.ui");
        ui.set("#ColourTarget.Entries", Arrays.stream(Key.values())
                .map(key -> new DropdownEntryInfo(LocalizableString.fromString(key.group + " / " + key.label), key.id)).toList());
        ui.set("#ColourTarget.Value", selected.id);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ColourTarget",
                EventData.of("Action", "target").append("@Target", "#ColourTarget.Value"), false);
        String[] channels = {"Red", "Green", "Blue"};
        for (int i = 0; i < channels.length; i++) {
            String row = "#ColourChannels[" + i + "]";
            ui.append("#ColourChannels", "Pages/DapperMapRGBRow.ui");
            ui.set(row + " #RowLabel.Text", channels[i]);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, row + " #ValueSlider",
                    EventData.of("Action", "channel_" + i).append("@Channel", row + " #ValueSlider.Value"), false);
        }
        button(ui, events, "#ColourResetSlot", "RESET THIS COLOUR", "reset_one");
        button(ui, events, "#ColourResetAllSlot", "RESET ALL COLOURS", "reset_all");
        button(ui, events, "#ColourSaveSlot", "SAVE COLOURS", "save");
        button(ui, events, "#ColourCancelSlot", "CANCEL", "cancel");
        refresh(ui, true);
    }

    private static void button(UICommandBuilder ui, UIEventBuilder events, String slot, String text, String action) {
        ui.append(slot, "Pages/BasicTextButton.ui");
        ui.set(slot + "[0].TextSpans", Message.raw(text));
        events.addEventBinding(CustomUIEventBindingType.Activating, slot + "[0]", EventData.of("Action", action), false);
    }

    private void refresh(UICommandBuilder ui, boolean syncSliders) {
        int rgb = draft.rgb(selected);
        int[] channels = {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
        ui.set("#ColourDescription.Text", selected.description);
        ui.set("#ColourHex.Text", draft.hex(selected) + "   |   RGB " + channels[0] + ", " + channels[1] + ", " + channels[2]);
        ui.set("#ColourChanges.Text", edited.isEmpty() ? "Choose a colour to get started." : "Unsaved colour changes. Choose Save Colours to apply them.");
        ui.clear("#ColourPreview");
        // RGB is generated from bounded integers, never raw UI input.
        String textColour = channels[0] * 299 + channels[1] * 587 + channels[2] * 114 > 145000 ? "#101820" : "#ffffff";
        ui.appendInline("#ColourPreview", "Group { Anchor: (Full: 0); Background: " + draft.hex(selected)
                + "; Label { Anchor: (Full: 0); Text: \"Colour Preview\"; Style: (FontSize: 20, RenderBold: true, TextColor: "
                + textColour + ", HorizontalAlignment: Center, VerticalAlignment: Center); } }");
        for (int i = 0; i < 3; i++) {
            String row = "#ColourChannels[" + i + "]";
            ui.set(row + " #ValueLabel.Text", Integer.toString(channels[i]));
            if (syncSliders) ui.set(row + " #ValueSlider.Value", channels[i]);
        }
    }

    @Override public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Ev event) {
        String action = event.action;
        if (action == null) return;
        if ("cancel".equals(action)) { openSettings(ref, store); return; }
        if ("save".equals(action)) {
            var settings = sessions.getSettings(owner.getUuid(), config);
            if (!settings.enabled) {
                UICommandBuilder ui = new UICommandBuilder();
                ui.set("#ColourChanges.Text", "Minimap is disabled. Cancel, enable it, then edit colours.");
                sendUpdate(ui, new UIEventBuilder(), false); return;
            }
            // Only touched keys overwrite live settings (commands may run while this page is open).
            settings.colours = mergeDraft(settings.colours);
            sessions.applySettings(owner, settings);
            openSettings(ref, store); return;
        }
        boolean sync = true;
        if ("target".equals(action)) {
            Key key = Key.find(event.target); if (key == null) return;
            selected = key;
        } else if ("reset_one".equals(action)) {
            draft = draft.reset(selected); edited.add(selected);
        } else if ("reset_all".equals(action)) {
            draft = new MinimapColours(); edited.addAll(EnumSet.allOf(Key.class));
        } else if ("channel_0".equals(action) || "channel_1".equals(action) || "channel_2".equals(action)) {
            int index = action.charAt(action.length() - 1) - '0';
            int channel = Math.max(0, Math.min(255, event.channel));
            int shift = (2 - index) * 8;
            draft = draft.with(selected, (draft.rgb(selected) & ~(255 << shift)) | (channel << shift));
            edited.add(selected); sync = false;
        } else return;
        UICommandBuilder ui = new UICommandBuilder(); refresh(ui, sync);
        sendUpdate(ui, new UIEventBuilder(), false);
    }

    private MinimapColours mergeDraft(MinimapColours live) {
        MinimapColours result = live;
        for (Key key : edited) result = result.with(key, draft.rgb(key));
        return result;
    }

    private void openSettings(Ref<EntityStore> ref, Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) player.getPageManager().openCustomPage(ref, store, new DapperMapSettingsPage(owner, sessions, config));
    }

    public static final class Ev {
        private String action = "", target = "";
        private int channel;
        public static final BuilderCodec<Ev> CODEC = BuilderCodec.builder(Ev.class, Ev::new)
                .append(new KeyedCodec<>("Action", Codec.STRING, true), (e,v) -> e.action=v, e -> e.action).add()
                .append(new KeyedCodec<>("@Target", Codec.STRING, true), (e,v) -> e.target=v, e -> e.target).add()
                .append(new KeyedCodec<>("@Channel", Codec.INTEGER, true), (e,v) -> e.channel=v, e -> e.channel).add()
                .build();
    }
}

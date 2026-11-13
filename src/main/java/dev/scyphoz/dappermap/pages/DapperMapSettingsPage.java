package dev.scyphoz.dappermap.pages;

import com.hypixel.hytale.server.core.permissions.PermissionQuery;
import dev.scyphoz.dappermap.permissions.DapperMapPermissions;
import dev.scyphoz.dappermap.permissions.SettingPermissions;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.scyphoz.dappermap.DapperMap;
import dev.scyphoz.dappermap.config.DapperMapConfig;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSetting;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapRenderer;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapSession;
import dev.scyphoz.dappermap.dynamic.DynamicAssetPublisher;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapColours;
import dev.scyphoz.dappermap.dynamic.DynamicMinimapColours.Key;
import dev.scyphoz.dappermap.integration.BetterMapIntegration;
import dev.scyphoz.dappermap.service.DapperMapSessionManager;
import dev.scyphoz.dappermap.util.DapperMapUtil.Lang;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;

/** Tabbed settings editor used for both player preferences and administrator defaults. */
public final class DapperMapSettingsPage
        extends InteractiveCustomUIPage<DapperMapSettingsPage.Ev> {

    private static final Color GOLD = new Color(16766571);
    private static final Color BLUE = new Color(10345727);
    private static final Color GREEN = new Color(5757323);
    private static final Color VIOLET = new Color(0xB8AEFF);
    private static final Color MUTED = new Color(0x7F89A3);

    private static final int[] POSITION_STOPS = {0, 50, 100};
    /** The colour editor's targets: every colour, in Key order (frame, panel, markers). */
    private static final List<Key> VISIBLE_COLOUR_KEYS = List.of(Key.values());

    private static final Row[] ROWS;
    /** Ids of the player-setting dropdown rows (server-limit rows are handled separately). */
    private static final java.util.Set<String> DROPDOWN_IDS;
    private final PlayerRef playerRef;
    private final DapperMapSessionManager sessions;
    private final DapperMapConfig config;
    private final boolean adminMode;
    /** Page text in the player's language when the page opened (dappermap.lang; English fallback). */
    private final Lang lang;
    private Tab activeTab = Tab.DISPLAY;
    private Key selectedColour = Key.EDGE;
    /** Font Size and Marker Size: the area each section's slider edits (page state, not a setting). */
    private String selectedFontArea = "font_title", selectedMarkerArea = "size_creatures";
    private int previewRevision = 0;
    private volatile int previewRequest = 0;
    private volatile boolean closed = false;
    private final java.util.concurrent.atomic.AtomicBoolean previewQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private String lastPreviewPath;
    /** Preview label markup the client has in #PreviewText (empty: none). World thread. */
    private String lastPreviewTexts = "";
    /** Next of the 16 preview slot paths to overwrite with a new image (world thread). */
    private int nextPreviewSlot = 0;
    /** When the last preview render was admitted; renders are paced to PREVIEW_MIN_INTERVAL_MS. */
    private long lastPreviewStartMs = 0L;
    /** A paced preview is waiting on DapperMap's heartbeat thread. */
    private final java.util.concurrent.atomic.AtomicBoolean previewTimerPending = new java.util.concurrent.atomic.AtomicBoolean();
    private static final long PREVIEW_MIN_INTERVAL_MS = 150L;
    private static final int PREVIEW_SLOTS = 16;
    /** Per-event memo of setting permissions (null outside an event): each node is checked once per click. */
    private java.util.Map<String, Boolean> permissionMemo;
    private World previewWorld;
    private DynamicMinimapRenderer.LiveSnapshot previewSnapshot;
    /** Unsaved working copy used by the settings UI and live preview. */
    private DynamicMinimapSetting draftSettings;
    /** Player reset-to-defaults must remove the override only when Apply is pressed. */
    private boolean draftResetsPlayerOverride = false;
    private boolean betterMapCaveAvailable = false;
    /** Admin page only: unsaved server limits, indexed by DapperMapConfig.Limit ordinal; saved on Apply. */
    private int[] draftLimits;
    /** Admin page only: unsaved terrain colour reduction (0 = off, -1 = not loaded); saved on Apply. */
    private int draftTerrainColours = -1;
    /** Admin Server tab row for the server-wide terrain colour reduction (not a per-player limit). */
    private static final String TERRAIN_COLOURS_ROW = "terrain_colours";
    /** The admin node (outside the player command tree, so player wildcard grants never reach it). */
    private static final PermissionQuery ADMIN_PERMISSION = DapperMapPermissions.ADMIN;

    /** The player's own settings page. */
    public DapperMapSettingsPage(PlayerRef playerRef, DapperMapSessionManager sessions, DapperMapConfig config) {
        this(playerRef, sessions, config, false);
    }

    public DapperMapSettingsPage(PlayerRef playerRef, DapperMapSessionManager sessions, DapperMapConfig config, boolean adminMode) {
        super(playerRef, CustomPageLifetime.CanDismiss, DapperMapSettingsPage.Ev.CODEC);
        this.playerRef = playerRef;
        this.sessions = sessions;
        this.config = config;
        this.adminMode = adminMode;
        this.lang = Lang.of(playerRef);
    }

    /** Opens the shared settings editor directly on the Colours tab. */
    public static DapperMapSettingsPage colours(PlayerRef playerRef,
                                                DapperMapSessionManager sessions, DapperMapConfig config) {
        DapperMapSettingsPage page = new DapperMapSettingsPage(playerRef, sessions, config);
        page.activeTab = Tab.COLOURS;
        return page;
    }

    public void build(
            Ref<EntityStore> ref,
            UICommandBuilder builder,
            UIEventBuilder events,
            Store<EntityStore> store
    ) {
        this.permissionMemo = new java.util.HashMap<>(); // Each node checked once per build.
        try {
            this.buildPage(ref, builder, events, store);
        } finally {
            this.permissionMemo = null;
        }
    }

    private void buildPage(Ref<EntityStore> ref, UICommandBuilder builder, UIEventBuilder events,
                           Store<EntityStore> store) {
        if (this.adminMode && !this.playerRef.hasPermission(ADMIN_PERMISSION)) return;
        this.previewWorld = store.getExternalData().getWorld();
        if (this.draftSettings == null) {
            this.draftSettings = (this.adminMode
                    ? this.sessions.getAdminDefaults(this.config)
                    : this.sessions.getSettings(this.playerRef.getUuid(), this.config)).copy();
        }
        if (this.adminMode && this.draftLimits == null) this.draftLimits = this.config.limits();
        if (this.adminMode && this.draftTerrainColours < 0) this.draftTerrainColours = this.config.getTerrainColours();
        if (this.previewSnapshot == null) this.previewSnapshot = this.sessions.capturePreview(this.playerRef.getUuid());
        DynamicMinimapSetting settings = this.draftSettings;
        this.betterMapCaveAvailable = BetterMapIntegration.isCaveModeEnabled(
                store.getComponent(ref, Player.getComponentType()));

        builder.append("Pages/DapperMapSettings.ui");
        this.lastPreviewTexts = ""; // A fresh page has an empty #PreviewText.
        builder.set("#PlayerTopAccent.Visible", !this.adminMode);
        builder.set("#AdminTopAccent.Visible", this.adminMode);
        // The Server Limits tab exists only on the admin page.
        builder.set("#DapperMapTabServerFrame.Visible", this.adminMode);
        builder.set("#DapperMapAdminButtonFrame.Visible",
                !this.adminMode && this.playerRef.hasPermission(ADMIN_PERMISSION));
        this.setStaticText(builder);

        this.renderList(builder, events, settings);
        this.bindColourEditor(builder, events, settings);
        this.bindPositionEditor(builder, events, settings);
        this.updatePreview(builder, settings);
        this.updateTabVisibility(builder);
        this.updateEditorAvailability(builder, settings);
        this.bindNavigation(builder, events);
        this.bindFooterButtons(builder, events);
    }

    /**
     * Fixed page text (titles, buttons and hints written in the .ui files in English), sent in the
     * player's language once when the page is built. The .ui text stays as the English default.
     */
    private void setStaticText(UICommandBuilder builder) {
        builder.set("#DapperMapTagline.Text", this.adminMode
                ? this.lang.get("settings.tagline.admin", "Server defaults for new DapperMap players")
                : this.lang.get("settings.tagline.player", "A lightweight, sophisticated and fully configurable Hytale minimap."));
        builder.set("#DapperMapAdminButtonLabel.Text", this.lang.get("button.admin", "ADMIN SETTINGS"));
        builder.set("#DapperMapCloseButtonLabel.Text", this.lang.get("button.cancel", "CANCEL"));
        builder.set("#ColourEditorTitle.Text", this.lang.get("colours.title", "HUD COLOURS"));
        builder.set("#ColourEditorHint.Text", this.lang.get("colours.hint", "Select an instrument element, then tune its colour."));
        builder.set("#ColourResetLabel.Text", this.lang.get("colours.reset", "RESET THIS COLOUR"));
        builder.set("#ColourResetAllLabel.Text", this.lang.get("colours.reset_all", "RESET ALL COLOURS"));
        builder.set("#QuickPlacementTitle.Text", this.lang.get("position.quick", "QUICK PLACEMENT"));
        builder.set("#FineAdjustmentTitle.Text", this.lang.get("position.fine", "FINE ADJUSTMENT"));
        builder.set("#PreviewTitle.Text", this.lang.get("preview.title", "P R E V I E W"));
    }

    private void bindNavigation(UICommandBuilder builder, UIEventBuilder events) {
        for (Tab tab : Tab.values()) {
            if (tab == Tab.SERVER && !this.adminMode) continue; // Hidden on the player page.
            String slot = "#DapperMapTab" + tab.uiLabel + "ButtonSlot";
            builder.append(slot, "Pages/DapperMapNavigationButton.ui");
            if (this.canOpenTab(tab)) {
                events.addEventBinding(CustomUIEventBindingType.Activating, slot + "[0]",
                        EventData.of("Action", "tab_" + tab.id), false);
            }
        }
        this.updateNavigationLabels(builder);
    }

    private void updateTabVisibility(UICommandBuilder builder) {
        builder.set("#DapperMapSettingsListContainer.Visible",
                this.activeTab != Tab.COLOURS && this.activeTab != Tab.POSITION);
        builder.set("#DapperMapColourEditor.Visible", this.activeTab == Tab.COLOURS);
        builder.set("#DapperMapPositionEditor.Visible", this.activeTab == Tab.POSITION);
        builder.set("#PageSectionTitle.Text", this.tabTitle(this.activeTab));
        String sectionDescription = this.lang.get("tab." + this.activeTab.id + ".desc", switch (this.activeTab) {
            case DISPLAY -> "Choose how the minimap looks and how hard it works.";
            case MARKERS -> "Choose the navigation markers shown on your minimap.";
            case INTEGRATIONS -> "Connect Hytale and BetterMap map features to your minimap.";
            case INTERFACE -> "Choose what the Info Panel shows and where it sits.";
            case POSITION -> "Place the minimap on-screen and fine-tune its position.";
            case COLOURS -> "Tune the DapperMap palette and preview every change live.";
            case SERVER -> "Cap the most expensive settings for every player and set server-wide image options. Saved player choices are kept.";
        });
        builder.set("#PageSectionDescription.Text", sectionDescription);
    }

    private void updateEditorAvailability(UICommandBuilder builder, DynamicMinimapSetting settings) {
        boolean colours = this.activeTab == Tab.COLOURS;
        boolean position = this.activeTab == Tab.POSITION;
        boolean canEditColours = this.canEditAnyColour();
        boolean canEditPosition = this.hasSettingPermission(SettingPermissions.POSITION);
        builder.set("#DapperMapColourEditor.Visible", colours && settings.enabled && canEditColours);
        builder.set("#ColourDisabledNotice.Visible", colours && (!settings.enabled || !canEditColours));
        builder.set("#ColourDisabledNotice.Text", canEditColours
                ? this.lang.get("notice.colours.enable", "ENABLE THE MINIMAP TO EDIT COLOURS")
                : this.lang.get("notice.colours.permission", "PERMISSION REQUIRED TO EDIT COLOURS"));
        builder.set("#DapperMapPositionEditor.Visible", position && settings.enabled && canEditPosition);
        builder.set("#PositionDisabledNotice.Visible", position && (!settings.enabled || !canEditPosition));
        builder.set("#PositionDisabledNotice.Text", canEditPosition
                ? this.lang.get("notice.position.enable", "ENABLE THE MINIMAP TO EDIT POSITION")
                : this.lang.get("notice.position.permission", "PERMISSION REQUIRED TO EDIT POSITION"));
    }

    private void bindColourEditor(UICommandBuilder builder, UIEventBuilder events, DynamicMinimapSetting settings) {
        // Each colour has its own node: the target list holds only the ones this player may change.
        List<Key> keys = this.colourKeys();
        if (!keys.isEmpty() && !keys.contains(this.selectedColour)) this.selectedColour = keys.get(0);
        builder.set("#ColourTarget.Entries", keys.stream()
                .map(key -> new DropdownEntryInfo(LocalizableString.fromString(this.colourEntry(key)), key.id))
                .toList());
        builder.set("#ColourTarget.Value", this.selectedColour.id);
        builder.set("#ColourPicker.Color", settings.colours.hex(this.selectedColour));
        builder.set("#ColourDescription.Text", this.colourDescription(this.selectedColour));
        builder.set("#ColourHex.Text", settings.colours.hex(this.selectedColour));
        boolean allowed = !keys.isEmpty();
        if (allowed) {
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ColourTarget",
                    EventData.of("Action", "colour_target").append("@Target", "#ColourTarget.Value"), false);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ColourPicker",
                    EventData.of("Action", "colour_value").append("@Colour", "#ColourPicker.Color"), false);
        }
        builder.append("#ColourResetButtonSlot", "Pages/BasicTextButton.ui");
        builder.set("#ColourResetButtonSlot[0].TextSpans", Message.raw(""));
        if (allowed) events.addEventBinding(CustomUIEventBindingType.Activating,
                "#ColourResetButtonSlot[0]", EventData.of("Action", "colour_reset"), false);
        builder.append("#ColourResetAllButtonSlot", "Pages/BasicTextButton.ui");
        builder.set("#ColourResetAllButtonSlot[0].TextSpans", Message.raw(""));
        if (allowed) events.addEventBinding(CustomUIEventBindingType.Activating,
                "#ColourResetAllButtonSlot[0]", EventData.of("Action", "colour_reset_all"), false);
    }

    /** "Frame / Map Edge": a colour's group and name, as the colour target dropdown lists them. */
    private String colourEntry(Key key) {
        String group = key.group.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        return this.lang.format("colours.entry", "{group} / {name}",
                "group", this.lang.get("colours.group." + group, key.group),
                "name", this.lang.get("colour." + key.id, key.label));
    }

    private String colourDescription(Key key) {
        return this.lang.get("colour." + key.id + ".desc", key.description);
    }

    private void refreshColourEditor(UICommandBuilder builder, DynamicMinimapSetting settings, boolean syncPicker) {
        builder.set("#ColourTarget.Value", this.selectedColour.id);
        builder.set("#ColourDescription.Text", this.colourDescription(this.selectedColour));
        builder.set("#ColourHex.Text", settings.colours.hex(this.selectedColour));
        if (syncPicker) builder.set("#ColourPicker.Color", settings.colours.hex(this.selectedColour));
    }

    private void bindPositionEditor(UICommandBuilder builder, UIEventBuilder events,
                                    DynamicMinimapSetting settings) {
        boolean allowed = this.hasSettingPermission(SettingPermissions.POSITION);
        String[] labels = {this.lang.get("position.horizontal", "Horizontal Position"),
                this.lang.get("position.vertical", "Vertical Position")};
        String[] actions = {"position_slider_x", "position_slider_y"};
        for (int index = 0; index < labels.length; index++) {
            String row = "#PositionFineTuneList[" + index + "]";
            builder.append("#PositionFineTuneList", "Pages/DapperMapPositionSliderRow.ui");
            builder.set(row + " #RowLabel.Text", labels[index]);
            builder.set(row + " #RowDescription.Text", index == 0
                    ? this.lang.get("position.horizontal.desc", "Move the minimap left or right.")
                    : this.lang.get("position.vertical.desc", "Move the minimap up or down."));
            if (allowed) {
                events.addEventBinding(CustomUIEventBindingType.ValueChanged, row + " #ValueSlider",
                        EventData.of("Action", actions[index])
                                .append("@SliderValue", row + " #ValueSlider.Value"), false);
            }
        }
        for (int row = 0; row < POSITION_STOPS.length; row++) {
            for (int column = 0; column < POSITION_STOPS.length; column++) {
                String slot = "#PositionSlot" + row + column + "Button";
                builder.append(slot, "Pages/BasicTextButton.ui");
                builder.set(slot + "[0].TextSpans", Message.raw(""));
                if (allowed) {
                    events.addEventBinding(CustomUIEventBindingType.Activating, slot + "[0]",
                            EventData.of("Action", "position_" + POSITION_STOPS[column] + "_" + POSITION_STOPS[row]), false);
                }
            }
        }
        this.updatePositionEditor(builder, settings, true);
    }

    private void updatePositionEditor(UICommandBuilder builder, DynamicMinimapSetting settings,
                                      boolean syncSliders) {
        int[] values = {settings.hudPositionX, settings.hudPositionY};
        for (int index = 0; index < values.length; index++) {
            String row = "#PositionFineTuneList[" + index + "]";
            builder.set(row + " #ValueLabel.Text", this.percent(values[index]));
            if (syncSliders) builder.set(row + " #ValueSlider.Value", values[index]);
        }
        builder.set("#PositionSummary.Text", this.positionSummary(settings));
        this.updatePositionGrid(builder, settings);
    }

    /** The 3x3 quick-position grid: highlight and colour the stop nearest the current position. */
    private void updatePositionGrid(UICommandBuilder builder, DynamicMinimapSetting settings) {
        int selectedX = nearestPositionStop(settings.hudPositionX);
        int selectedY = nearestPositionStop(settings.hudPositionY);
        for (int row = 0; row < POSITION_STOPS.length; row++) {
            for (int column = 0; column < POSITION_STOPS.length; column++) {
                int x = POSITION_STOPS[column];
                int y = POSITION_STOPS[row];
                boolean selected = x == selectedX && y == selectedY;
                builder.set("#PositionSlot" + row + column + "Active.Visible", selected);
                builder.set("#PositionSlot" + row + column + "Label.TextSpans",
                        Message.raw(this.positionButtonName(x, y))
                                .color(selected ? VIOLET : MUTED));
            }
        }
    }

    /**
     * Slider move: re-sends only the moved row's value label and the summary; the 3x3 grid
     * (18 commands) only when its selected stop changes. The slider already shows its value.
     */
    private void updatePositionSlider(UICommandBuilder builder, DynamicMinimapSetting settings, int index,
                                      int previousX, int previousY) {
        int value = index == 0 ? settings.hudPositionX : settings.hudPositionY;
        builder.set("#PositionFineTuneList[" + index + "] #ValueLabel.Text", this.percent(value));
        builder.set("#PositionSummary.Text", this.positionSummary(settings));
        if (nearestPositionStop(previousX) != nearestPositionStop(settings.hudPositionX)
                || nearestPositionStop(previousY) != nearestPositionStop(settings.hudPositionY))
            this.updatePositionGrid(builder, settings);
    }

    /** "TOP RIGHT   •   100% / 0%": the Position tab's summary line. */
    private String positionSummary(DynamicMinimapSetting settings) {
        return this.lang.format("position.summary", "{position}   •   {x} / {y}",
                "position", this.positionName(settings.hudPositionX, settings.hudPositionY),
                "x", this.percent(settings.hudPositionX), "y", this.percent(settings.hudPositionY));
    }

    /** "50%" in the player's language (some languages write "50 %" or "%50"). */
    private String percent(int value) {
        return this.lang.format("unit.percent", "{value}%", "value", value);
    }

    /** Hides the preview image and its labels and shows {@code text} in their place. */
    private void showPreviewNotice(UICommandBuilder ui, String key, String english) {
        String text = this.lang.get(key, english);
        ui.set("#PreviewHudImage.Visible", false);
        ui.set("#PreviewText.Visible", false);
        ui.set("#PreviewDisabled.Text", text);
        ui.set("#PreviewDisabled.Visible", true);
    }

    private void updatePreview(UICommandBuilder ui, DynamicMinimapSetting settings) {
        int request = ++this.previewRequest;
        if (!settings.enabled) {
            this.showPreviewNotice(ui, "preview.disabled", "MINIMAP DISABLED");
            return;
        }
        if (this.previewSnapshot == null) this.previewSnapshot = this.sessions.capturePreview(this.playerRef.getUuid());
        if (this.previewSnapshot == null) {
            this.showPreviewNotice(ui, "preview.unavailable", "LIVE HUD PREVIEW UNAVAILABLE");
            return;
        }
        if (this.previewRevision == 0) this.showPreviewNotice(ui, "preview.preparing", "PREPARING HUD PREVIEW");
        World world = this.previewWorld;
        if (world == null || this.closed) return;
        // Pace renders while sliders or the colour picker are dragged; the newest draft is
        // always rendered when the timer fires (it re-enters updatePreview on the world thread).
        long waitMs = this.lastPreviewStartMs + PREVIEW_MIN_INTERVAL_MS - System.currentTimeMillis();
        if (waitMs > 0) {
            this.schedulePacedPreview(world, waitMs);
            return;
        }
        if (!this.previewQueued.compareAndSet(false, true)) return;
        this.lastPreviewStartMs = System.currentTimeMillis();
        ++this.previewRevision;
        DynamicMinimapSetting draft = settings.copy();
        // Preview what will actually be shown: server limits apply here too (and keep this render cheap).
        this.config.applyLimits(draft);
        DynamicMinimapRenderer.LiveSnapshot snapshot = this.previewSnapshot;
        boolean position = this.activeTab == Tab.POSITION;
        String slug = dev.scyphoz.dappermap.util.DapperMapUtil.playerSlug(this.playerRef.getUuid());
        String path = previewSlotPath(slug, 0); // Final slot chosen on delivery (see showPreview).
        if (!DynamicMinimapSession.renderAsync(() -> {
            if (this.closed) { this.previewQueued.set(false); return; }
            try {
                // Text over the preview is native labels (Hytale's font), built here off the world
                // thread, for both the close-up preview and the Position tab's whole-screen one.
                var rendered = position
                        ? DynamicMinimapRenderer.renderPositionPreview(draft, snapshot)
                        : DynamicMinimapRenderer.renderSettingsPreview(draft, snapshot);
                byte[] png = rendered == null ? null : rendered.png();
                String texts = rendered == null ? "" : previewTextMarkup(rendered.texts());
                var prepared = DynamicAssetPublisher.prepare(path, png);
                world.execute(() -> {
                    this.previewQueued.set(false);
                    if (this.closed || this.previewWorld != world) return;
                    if (request != this.previewRequest) {
                        // One admitted render per page; rapid edits replace the pending
                        // request, and the newest draft is rendered after this one finishes.
                        this.updatePreview(new UICommandBuilder(), this.draftSettings);
                        return;
                    }
                    if (prepared == null) return;
                    String shown = this.publishPreview(slug, prepared);
                    if (shown != null) {
                        this.lastPreviewPath = shown;
                        UICommandBuilder update = new UICommandBuilder();
                        update.set("#PreviewHudImage.AssetPath", this.lastPreviewPath);
                        update.set("#PreviewHudImage.Visible", true);
                        update.set("#PreviewDisabled.Visible", false);
                        // Labels are re-sent only when their text, place or style changed.
                        if (!texts.equals(this.lastPreviewTexts)) {
                            this.lastPreviewTexts = texts;
                            update.clear("#PreviewText");
                            if (!texts.isEmpty()) update.appendInline("#PreviewText", texts);
                        }
                        update.set("#PreviewText.Visible", true);
                        this.sendUpdate(update, new UIEventBuilder(), false);
                    }
                });
            } catch (RuntimeException failure) {
                this.previewQueued.set(false);
                if (!dev.scyphoz.dappermap.util.DapperMapUtil.isStoppedWorld(failure)) throw failure;
            }
        })) this.previewQueued.set(false);
    }

    /**
     * Brings the draft in line with what the page shows when Apply is pressed: the position
     * sliders and the colour picker's current values. Uses the same permission and enabled
     * checks as their own events, so nothing the player couldn't change is touched.
     */
    private void reconcileDraft(Ev event) {
        DynamicMinimapSetting draft = this.draftSettings;
        if (draft == null || !draft.enabled) return;
        boolean changed = false;
        if (this.hasSettingPermission(SettingPermissions.POSITION)) {
            if (event.posX != null && clampPercent(event.posX) != draft.hudPositionX) {
                draft.hudPositionX = clampPercent(event.posX);
                changed = true;
            }
            if (event.posY != null && clampPercent(event.posY) != draft.hudPositionY) {
                draft.hudPositionY = clampPercent(event.posY);
                changed = true;
            }
        }
        if (event.colour != null && VISIBLE_COLOUR_KEYS.contains(this.selectedColour)
                && this.hasSettingPermission(SettingPermissions.forColour(this.selectedColour))) {
            int rgb = dev.scyphoz.dappermap.util.DapperMapUtil.rgb(event.colour);
            if (draft.colours.rgb(this.selectedColour) != rgb) {
                draft.colours = draft.colours.with(this.selectedColour, rgb);
                changed = true;
            }
        }
        // A corrected value is a change, so a pending "reset to defaults" no longer applies as-is.
        if (changed) this.draftResetsPlayerOverride = false;
    }

    /** Stage (360x286) to on-page preview (272x216) scale, as #PreviewHudImage/#PreviewText are sized. */
    private static final double PREVIEW_SCALE_X = 272.0D / DynamicMinimapRenderer.PREVIEW_STAGE_WIDTH;
    private static final double PREVIEW_SCALE_Y = 216.0D / DynamicMinimapRenderer.PREVIEW_STAGE_HEIGHT;

    /**
     * Markup for the preview's native labels (children of #PreviewText, which covers the image).
     * One Label per text, in page units; empty when there are none.
     */
    private static String previewTextMarkup(List<DynamicMinimapRenderer.PreviewText> texts) {
        if (texts == null || texts.isEmpty()) return "";
        StringBuilder out = new StringBuilder(texts.size() * 190);
        for (var text : texts) {
            int left = (int)Math.round(text.x() * PREVIEW_SCALE_X);
            int top = (int)Math.round(text.y() * PREVIEW_SCALE_Y);
            int width = Math.max(1, (int)Math.round(text.width() * PREVIEW_SCALE_X));
            int height = Math.max(1, (int)Math.round(text.height() * PREVIEW_SCALE_Y));
            // At least 4: the Position tab draws the whole HUD small (smaller text is unreadable).
            int font = Math.max(4, (int)Math.round(text.fontSize() * PREVIEW_SCALE_Y));
            double spacing = Math.round(text.letterSpacing() * PREVIEW_SCALE_X * 10.0D) / 10.0D;
            dev.scyphoz.dappermap.util.DapperMapUtil.appendLabel(out, text.id(), left, top, width, height,
                    text.text(), font, text.colour(), text.bold(), spacing,
                    dev.scyphoz.dappermap.util.DapperMapUtil.alignmentName(text.alignment()));
        }
        return out.toString();
    }

    private static String previewSlotPath(String slug, int slot) {
        return "UI/DapperMap/settings-preview-" + slug + "-" + slot + ".png";
    }

    /**
     * Shows a rendered preview: reuses any of the 16 slot paths whose image the client already
     * holds (the publisher's delivered records are the source of truth, so teleports and world
     * changes are handled), otherwise sends it to the next slot. Returns the path, or null. World thread.
     */
    private String publishPreview(String slug, DynamicAssetPublisher.PreparedAsset prepared) {
        for (int slot = 0; slot < PREVIEW_SLOTS; slot++) {
            String candidate = previewSlotPath(slug, slot);
            if (DynamicAssetPublisher.isDelivered(this.playerRef, candidate, prepared.hash())) return candidate;
        }
        var publication = prepared.withPath(previewSlotPath(slug, this.nextPreviewSlot));
        if (!DynamicAssetPublisher.deliver(this.playerRef, publication)) return null;
        this.nextPreviewSlot = (this.nextPreviewSlot + 1) % PREVIEW_SLOTS;
        return publication.path();
    }

    /** Runs updatePreview again once the pacing interval has passed (one timer at a time). */
    private void schedulePacedPreview(World world, long delayMs) {
        if (!this.previewTimerPending.compareAndSet(false, true)) return;
        try {
            DynamicMinimapSession.heartbeat().schedule(() -> {
                try {
                    world.execute(() -> {
                        this.previewTimerPending.set(false);
                        if (this.closed || this.previewWorld != world || this.draftSettings == null) return;
                        this.updatePreview(new UICommandBuilder(), this.draftSettings);
                    });
                } catch (RuntimeException stoppedWorld) {
                    this.previewTimerPending.set(false);
                }
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            this.previewTimerPending.set(false); // Heartbeat stopped (plugin shutting down).
        }
    }

    @Override public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        this.closed = true;
        ++this.previewRequest;
        DynamicMinimapRenderer.forgetPreview(this.previewSnapshot); // Frees its preview images now.
        this.previewSnapshot = null;
        this.previewWorld = null;
        super.onDismiss(ref, store);
    }

    private void updateNavigationLabels(UICommandBuilder builder) {
        for (Tab tab : Tab.values()) {
            if (tab == Tab.SERVER && !this.adminMode) continue; // Hidden on the player page.
            boolean selected = tab == this.activeTab;
            builder.set("#DapperMapTab" + tab.uiLabel + "Active.Visible", selected);
            builder.set("#DapperMapTab" + tab.uiLabel + "Label.TextSpans",
                    Message.raw(this.tabTitle(tab)).color(selected ? VIOLET
                            : this.canOpenTab(tab) ? MUTED : new Color(0x555B6D)));
        }
    }

    /** A tab's upper-case title ("INFO PANEL"); translations give their own capitals (Turkish dotted I). */
    private String tabTitle(Tab tab) {
        return this.lang.get("tab." + tab.id, tab.label.toUpperCase(java.util.Locale.ROOT));
    }

    private void bindFooterButtons(
            UICommandBuilder builder,
            UIEventBuilder events
    ) {
        builder.append(
                "#DapperMapResetButtonSlot",
                "Pages/BasicTextButton.ui"
        );

        boolean canReset = this.hasSettingPermission(SettingPermissions.RESET);
        builder.set(
                "#DapperMapResetButtonLabel.TextSpans",
                Message.raw(this.adminMode ? this.lang.get("button.reset_admin", "RESET TO MOD DEFAULTS")
                                : this.lang.get("button.reset", "RESET TO DEFAULTS"))
                        .color(canReset ? GOLD : new Color(0x666A78))
        );
        builder.set("#DapperMapResetButtonSlot[0].TextSpans", Message.raw(""));

        if (canReset) {
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#DapperMapResetButtonSlot[0]",
                    EventData.of("Action", "reset_defaults"),
                    false
            );
        }

        builder.append("#DapperMapCloseButtonSlot", "Pages/BasicTextButton.ui");
        builder.set("#DapperMapCloseButtonSlot[0].TextSpans", Message.raw(""));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DapperMapCloseButtonSlot[0]",
                EventData.of("Action", "cancel_settings"), false);

        builder.append("#DapperMapApplyButtonSlot", "Pages/BasicTextButton.ui");
        builder.set("#DapperMapApplyButtonLabel.TextSpans",
                Message.raw(this.lang.get("button.apply", "APPLY")).color(this.adminMode ? GREEN : new Color(0xF0EDFF)));
        builder.set("#DapperMapApplyButtonSlot[0].TextSpans", Message.raw(""));
        // Apply also carries the sliders' and colour picker's current values: a ValueChanged event
        // dropped while a page update awaited acknowledgement is then still saved correctly.
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DapperMapApplyButtonSlot[0]",
                EventData.of("Action", "apply_settings")
                        .append("@PosX", "#PositionFineTuneList[0] #ValueSlider.Value")
                        .append("@PosY", "#PositionFineTuneList[1] #ValueSlider.Value")
                        .append("@Colour", "#ColourPicker.Color"), false);

        if (!this.adminMode && this.playerRef.hasPermission(ADMIN_PERMISSION)) {
            builder.append("#DapperMapAdminButtonSlot", "Pages/BasicTextButton.ui");
            builder.set("#DapperMapAdminButtonSlot[0].TextSpans", Message.raw(""));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#DapperMapAdminButtonSlot[0]",
                    EventData.of("Action", "admin_settings"), false);
        }
    }

    /** Returns whether this page may edit the named player-setting permission. */
    private boolean hasSettingPermission(String permission) {
        if (this.adminMode || permission == null) return true;
        java.util.Map<String, Boolean> memo = this.permissionMemo;
        if (memo == null) return this.playerRef.hasPermission(DapperMapPermissions.player(permission));
        return memo.computeIfAbsent(permission,
                node -> this.playerRef.hasPermission(DapperMapPermissions.player(node)));
    }

    private boolean canOpenTab(Tab tab) {
        return switch (tab) {
            case COLOURS -> this.canEditAnyColour();
            case SERVER -> this.adminMode; // Server limits: admin page only.
            case POSITION -> this.hasSettingPermission(SettingPermissions.POSITION);
            default -> true; // Rows this player may not change are shown disabled.
        };
    }

    /** True when at least one colour may be changed (each colour has its own node). */
    private boolean canEditAnyColour() {
        return SettingPermissions.anyColour(this::hasSettingPermission);
    }

    /** The editor's colours this player may change, in editor order (every colour on the admin page). */
    private List<Key> colourKeys() {
        if (this.adminMode) return VISIBLE_COLOUR_KEYS;
        List<Key> keys = new ArrayList<>(VISIBLE_COLOUR_KEYS.size());
        for (Key key : VISIBLE_COLOUR_KEYS) {
            if (this.hasSettingPermission(SettingPermissions.forColour(key))) keys.add(key);
        }
        return keys;
    }

    /**
     * The node guarding a settings-page row (from the shared table in SettingPermissions), or
     * null for rows without one (section headers). Always the same node as the row's command.
     */
    private static String permissionForSetting(String id) {
        return SettingPermissions.forRow(id);
    }

    /**
     * Whether this page may act on {@code action}. Toggle and dropdown events for unknown row ids
     * (a malformed or forged event) are refused rather than treated as unguarded.
     */
    private boolean actionAllowed(String action) {
        if (action.startsWith("position_")) return this.hasSettingPermission(SettingPermissions.POSITION);
        if (action.startsWith("colour_")) {
            return switch (action) {
                // Changing the selected colour needs that colour's own node.
                case "colour_value", "colour_reset" -> this.hasSettingPermission(SettingPermissions.forColour(this.selectedColour));
                default -> this.canEditAnyColour(); // colour_target, colour_reset_all (permitted colours only).
            };
        }
        String row = action.startsWith("toggle_") ? action.substring("toggle_".length())
                : action.startsWith("dropdown_") ? action.substring("dropdown_".length()) : null;
        if (row == null) return true;
        String node = permissionForSetting(row);
        return node != null && this.hasSettingPermission(node);
    }

    private boolean hasAllSettingPermissions() {
        return this.adminMode || SettingPermissions.hasAll(this::hasSettingPermission);
    }

    /**
     * Copies only values that the player may edit. This is the authoritative server-side
     * guard for Apply/Reset; disabled controls and missing event bindings are presentation.
     * Shared with the /dappermap commands through {@link SettingPermissions}.
     */
    private DynamicMinimapSetting mergePermittedSettings(
            DynamicMinimapSetting target,
            DynamicMinimapSetting source
    ) {
        return SettingPermissions.merge(this::hasSettingPermission, target, source);
    }

    private void renderList(
            UICommandBuilder builder,
            UIEventBuilder events,
            DynamicMinimapSetting settings
    ) {
        builder.clear("#DapperMapSettingsList");

        int renderedIndex = 0;
        for (Row row : ROWS) {
            if (row.tab != this.activeTab) continue;
            boolean limitRow = row.tab == Tab.SERVER;
            if (limitRow && !this.adminMode) continue; // Server limits are admin-page only.
            int index = renderedIndex++;

            if (limitRow && row.kind == Kind.DROPDOWN) {
                // Independent of the defaults draft (stays editable when the default minimap is off).
                if (TERRAIN_COLOURS_ROW.equals(row.id)) this.terrainColoursRow(builder, events, index, row);
                else this.limitRow(builder, events, index, row);
            } else if (row.kind == Kind.HEADER) {
                builder.append("#DapperMapSettingsList", "Pages/DapperMapSectionRow.ui");
                builder.set("#DapperMapSettingsList[" + index + "] #SectionLabel.Text", this.rowLabel(row));
            } else if (row.kind == Kind.AREA || row.kind == Kind.SLIDER) {
                // Font/Marker Size: usable when at least one of its areas may be changed.
                SizeGroup group = sizeGroup(row.id);
                if (this.sizeRowDisabled(row.id, settings)) this.disabledRow(builder, index, row);
                else if (row.kind == Kind.AREA) this.areaRow(builder, events, index, row, settings, group);
                else this.sliderRow(builder, events, index, row, settings, group);
            } else if (!this.hasSettingPermission(permissionForSetting(row.id)) || isControlDisabled(row.id, settings)) {
                this.disabledRow(builder, index, row); // Not permitted, or a setting it needs is off.
            } else if (row.kind == Kind.DROPDOWN) {
                this.dropdownRow(builder, events, index, row, settings);
            } else {
                this.toggleRow(builder, events, index, row, settings); // TOGGLE and SWITCH rows.
            }
        }
    }

    /** A row shown greyed out with "Disabled" in place of its control. */
    private void disabledRow(UICommandBuilder builder, int index, Row row) {
        String selector = "#DapperMapSettingsList[" + index + "]";
        builder.append("#DapperMapSettingsList", "Pages/DapperMapDisabledDropdownRow.ui");
        builder.set(selector + " #RowLabel.Text", this.rowLabel(row));
        builder.set(selector + " #RowDescription.Text", this.description(row.id));
        builder.set(selector + " #ValueLabel.Text", this.lang.get("row.disabled", "Disabled"));
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

        builder.set(selector + " #RowLabel.Text", this.rowLabel(row));

        builder.set(selector + " #RowDescription.Text", this.description(row.id));

        boolean on = toggleValue(row.id, settings);
        builder.set(selector + " #ToggleOn.Visible", on);
        builder.set(selector + " #ToggleOff.Visible", !on);

        // Integration rows keep the control visually quiet; all other binary
        // controls show the active state beside the switch in instrument cyan.
        boolean showState = !isIntegrationToggle(row.id);
        builder.set(selector + " #StateLabel.Visible", showState);
        if (showState) {
            builder.set(selector + " #StateLabel.Text", this.toggleStateLabel(row.id, settings));
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
            this.disabledRow(builder, index, row); // Non-interactive while its controlling filter is off.
            return;
        }

        builder.append(
                "#DapperMapSettingsList",
                "Pages/DapperMapDropdownRow.ui"
        );

        builder.set(selector + " #RowLabel.Text", this.rowLabel(row));

        builder.set(selector + " #RowDescription.Text", this.rowDescription(row.id));

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
        // Permissions are still read fresh on every event, but each node only once per event.
        this.permissionMemo = new java.util.HashMap<>();
        try {
            this.handleEvent(ref, store, event);
        } finally {
            this.permissionMemo = null;
        }
    }

    private void handleEvent(Ref<EntityStore> ref, Store<EntityStore> store, Ev event) {
        String action = event.action;
        this.previewWorld = store.getExternalData().getWorld();
        // BetterMap cave availability is checked once in build(): it is a reflective plugin
        // lookup, and cave mode cannot change while this page is open.

        if (this.adminMode && !this.playerRef.hasPermission(ADMIN_PERMISSION)) return;
        if ("cancel_settings".equals(action)) {
            this.closed = true;
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
            this.closed = true;
            this.reconcileDraft(event);
            this.draftSettings.normalize();
            if (this.adminMode) {
                this.sessions.setAdminDefaults(this.draftSettings.copy());
                this.saveLimitsIfChanged();
            } else if (this.draftResetsPlayerOverride && this.hasAllSettingPermissions()) {
                this.sessions.resetSettings(this.playerRef, this.config);
            } else {
                DynamicMinimapSetting live = this.sessions
                        .getSettings(this.playerRef.getUuid(), this.config).copy();
                this.sessions.applySettings(this.playerRef,
                        this.mergePermittedSettings(live, this.draftSettings));
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
            this.closed = true;
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
            if (requested == null || requested == this.activeTab || !this.canOpenTab(requested)) return;
            boolean previewModeChanged = (this.activeTab == Tab.POSITION) != (requested == Tab.POSITION);
            this.activeTab = requested;
            DynamicMinimapSetting current = this.draftSettings;
            UICommandBuilder navBuilder = new UICommandBuilder();
            UIEventBuilder navEvents = new UIEventBuilder();
            this.renderList(navBuilder, navEvents, current);
            // The hidden colour and position editors are already in sync: every action that changes
            // colours or position refreshes them (and build/reset send a full refresh).
            if (previewModeChanged) this.updatePreview(navBuilder, current);
            this.updateTabVisibility(navBuilder);
            this.updateEditorAvailability(navBuilder, current);
            this.updateNavigationLabels(navBuilder);
            this.sendUpdate(navBuilder, navEvents, false);
            return;
        }

        if ("reset_defaults".equals(action)) {
            if (!this.hasSettingPermission(SettingPermissions.RESET)) return;
            DynamicMinimapSetting reset = this.adminMode
                    ? DynamicMinimapSetting.fromConfig(this.config)
                    : this.sessions.getAdminDefaults(this.config).copy();
            DynamicMinimapSetting current = this.draftSettings == null
                    ? this.sessions.getSettings(this.playerRef.getUuid(), this.config).copy()
                    : this.draftSettings.copy();
            this.draftSettings = this.adminMode
                    ? reset.copy() : this.mergePermittedSettings(current, reset);
            this.draftResetsPlayerOverride = !this.adminMode && this.hasAllSettingPermissions();
            reset = this.draftSettings;
            UICommandBuilder resetBuilder = new UICommandBuilder();
            UIEventBuilder resetEvents = new UIEventBuilder();
            this.renderList(resetBuilder, resetEvents, reset);
            this.refreshColourEditor(resetBuilder, reset, true);
            this.updatePositionEditor(resetBuilder, reset, true);
            this.updatePreview(resetBuilder, reset);
            this.updateEditorAvailability(resetBuilder, reset);
            this.sendUpdate(resetBuilder, resetEvents, false);
            return;
        }

        if (("dropdown_" + TERRAIN_COLOURS_ROW).equals(action)) {
            // Admin page only, like the limits: a page draft saved with Apply, dropped on cancel.
            if (!this.adminMode || this.draftTerrainColours < 0 || event.value == null) return;
            try {
                int value = Integer.parseInt(event.value);
                if (this.terrainColourChoices().contains(value)) this.draftTerrainColours = value; // Only offered values.
            } catch (NumberFormatException ignored) {
                // Not a value this dropdown offers: keep the draft.
            }
            return;
        }

        if (action.startsWith("dropdown_limit_")) {
            // Admin page only; changes the page draft, saved with Apply and dropped on cancel.
            if (!this.adminMode || this.draftLimits == null) return;
            // The dropdown already shows the new value and no other row depends on it,
            // so nothing needs sending back to the client.
            this.applyLimitDropdown(action.substring("dropdown_".length()), event.value);
            return;
        }

        DynamicMinimapSetting settings = this.draftSettings;
        if (settings == null) return;

        if (!this.actionAllowed(action)) return;

        // Set for toggle/dropdown changes so the reply can update just that row when possible.
        String rowId = null;
        boolean[] disabledBefore = null;
        List<String> entriesBefore = null;
        // Position before a slider move: only the 3x3 grid's selection may need re-sending.
        int previousX = settings.hudPositionX, previousY = settings.hudPositionY;

        if (!settings.enabled
                && !"toggle_enabled".equals(action)
                && (action.startsWith("position_") || action.startsWith("colour_")
                || action.startsWith("toggle_") || action.startsWith("dropdown_")
                || action.startsWith("area_") || action.startsWith("scale_"))) {
            return;
        }

        if (action.startsWith("area_") || action.startsWith("scale_")) {
            // Font/Marker Size: the area's own permission is checked in handleSizeEvent.
            UICommandBuilder sizeUi = new UICommandBuilder();
            if (this.handleSizeEvent(sizeUi, action, event, settings)) {
                this.draftResetsPlayerOverride = false;
                this.updatePreview(sizeUi, settings);
            }
            if (sizeUi.getCommands().length > 0) this.sendUpdate(sizeUi, new UIEventBuilder(), false);
            return;
        }

        if (action.startsWith("position_") && !action.startsWith("position_slider_")) {
            String[] parts = action.split("_");
            if (parts.length != 3) return;
            try {
                settings.hudPositionX = clampPercent(Integer.parseInt(parts[1]));
                settings.hudPositionY = clampPercent(Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
                return;
            }
        } else if ("position_slider_x".equals(action)) {
            int value = clampPercent(event.sliderValue);
            if (settings.hudPositionX == value) return;
            settings.hudPositionX = value;
        } else if ("position_slider_y".equals(action)) {
            int value = clampPercent(event.sliderValue);
            if (settings.hudPositionY == value) return;
            settings.hudPositionY = value;
        } else if ("colour_target".equals(action)) {
            Key key = Key.find(event.target);
            if (key == null || !this.colourKeys().contains(key)) return;
            this.selectedColour = key;
            UICommandBuilder colourUi = new UICommandBuilder();
            this.refreshColourEditor(colourUi, settings, true);
            this.sendUpdate(colourUi, new UIEventBuilder(), false);
            return;
        } else if ("colour_value".equals(action)) {
            if (event.colour == null) return;
            int rgb = dev.scyphoz.dappermap.util.DapperMapUtil.rgb(event.colour);
            if (settings.colours.rgb(this.selectedColour) == rgb) return;
            settings.colours = settings.colours.with(this.selectedColour, rgb);
        } else if ("colour_reset".equals(action)) {
            settings.colours = settings.colours.reset(this.selectedColour);
        } else if ("colour_reset_all".equals(action)) {
            // Only the colours this player may change; locked ones keep their value.
            if (this.adminMode) settings.colours = new DynamicMinimapColours();
            else {
                DynamicMinimapColours colours = settings.colours;
                for (Key key : Key.values()) {
                    if (this.hasSettingPermission(SettingPermissions.forColour(key))) colours = colours.reset(key);
                }
                settings.colours = colours;
            }
        } else if (action.startsWith("toggle_")) {
            rowId = action.substring("toggle_".length());
            disabledBefore = this.disabledRows(settings);
            applyToggle(
                    settings,
                    action.substring("toggle_".length())
            );

        } else if (action.startsWith("dropdown_")) {
            // Ignore ids that are not dropdown rows (a malformed or forged event must not throw).
            if (!DROPDOWN_IDS.contains(action.substring("dropdown_".length()))) return;
            // Cheap value check first; the entry list is only built when the value is unchanged.
            if (event.value != null && event.value.equals(dropdownValue(action.substring("dropdown_".length()), settings))
                    && dropdownEntries(action.substring("dropdown_".length()), settings).stream()
                    .anyMatch(entry -> entry.value().equals(event.value))) return;
            rowId = action.substring("dropdown_".length());
            disabledBefore = this.disabledRows(settings);
            entriesBefore = this.entryValues(rowId, settings);
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

        if ("colour_value".equals(action)) {
            // The picker already shows the new colour and the target is unchanged: hex only.
            builder.set("#ColourHex.Text", settings.colours.hex(this.selectedColour));
        } else if (action.startsWith("colour_")) {
            this.refreshColourEditor(builder, settings, true);
        } else if (action.startsWith("position_slider_")) {
            this.updatePositionSlider(builder, settings, "position_slider_x".equals(action) ? 0 : 1, previousX, previousY);
        } else if (action.startsWith("position_")) {
            this.updatePositionEditor(builder, settings, true);
        } else if (rowId != null && java.util.Arrays.equals(disabledBefore, this.disabledRows(settings))
                && (entriesBefore == null || entriesBefore.equals(this.entryValues(rowId, settings)))) {
            // No other row changed state: update only this row instead of rebuilding the list.
            // A dropdown already shows its new value; a toggle needs its switch and state label.
            if (action.startsWith("toggle_")) this.updateToggleRow(builder, rowId, settings);
        } else {
            this.renderList(builder, events, settings);
            this.updateEditorAvailability(builder, settings);
        }
        this.updatePreview(builder, settings);

        // Every page update blocks the player's input until the client acknowledges it, so an
        // empty one (e.g. a dropdown change with no knock-on effects) is not sent at all.
        if (builder.getCommands().length > 0 || events.getEvents().length > 0) {
            this.sendUpdate(builder, events, false);
        }
    }

    /** Disabled state of every row on the current tab, matching renderList's rules. */
    private boolean[] disabledRows(DynamicMinimapSetting settings) {
        boolean[] out = new boolean[ROWS.length];
        for (int i = 0; i < ROWS.length; i++) {
            Row row = ROWS[i];
            if (row.kind == Kind.HEADER || row.tab != this.activeTab || row.tab == Tab.SERVER) continue;
            out[i] = row.kind == Kind.AREA || row.kind == Kind.SLIDER ? this.sizeRowDisabled(row.id, settings)
                    : !this.hasSettingPermission(permissionForSetting(row.id)) || isControlDisabled(row.id, settings);
        }
        return out;
    }

    /** Dropdown values offered for a row (some lists include the current custom value). */
    private List<String> entryValues(String id, DynamicMinimapSetting settings) {
        List<String> values = new ArrayList<>();
        for (DropdownEntryInfo entry : this.dropdownEntries(id, settings)) values.add(entry.value());
        return values;
    }

    /** Position of a row in the rendered list for the current tab, or -1. Mirrors renderList. */
    private int renderedIndex(String id) {
        int index = 0;
        for (Row row : ROWS) {
            if (row.tab != this.activeTab) continue;
            if (row.tab == Tab.SERVER && !this.adminMode) continue;
            if (row.id.equals(id)) return index;
            index++;
        }
        return -1;
    }

    /** Re-sends only a toggle row's switch and state label (as set by toggleRow). */
    private void updateToggleRow(UICommandBuilder builder, String id, DynamicMinimapSetting settings) {
        int index = this.renderedIndex(id);
        if (index < 0) return;
        String selector = "#DapperMapSettingsList[" + index + "]";
        boolean on = toggleValue(id, settings);
        builder.set(selector + " #ToggleOn.Visible", on);
        builder.set(selector + " #ToggleOff.Visible", !on);
        if (!isIntegrationToggle(id)) builder.set(selector + " #StateLabel.Text", this.toggleStateLabel(id, settings));
    }

    /** Flips an on/off row (also Map Shape, Map Rotation and Clock Format, which are shown as switches). */
    private void applyToggle(DynamicMinimapSetting settings, String id) {
        Row row = row(id);
        if (row == null || (row.kind != Kind.TOGGLE && row.kind != Kind.SWITCH) || isControlDisabled(id, settings)) return;
        row.field.set(settings, toggleValue(id, settings) ? 0 : 1);
    }

    /** Integration switches keep the control visually quiet (no state label beside it). */
    private static boolean isIntegrationToggle(String id) {
        Row row = row(id);
        return row != null && row.tab == Tab.INTEGRATIONS && row.kind != Kind.HEADER;
    }

    /** "ENABLED"/"DISABLED", or the switch's own state name ("CIRCLE", "PLAYER FACING", "24 HOUR"). */
    private String toggleStateLabel(String id, DynamicMinimapSetting settings) {
        boolean on = toggleValue(id, settings);
        Row row = row(id);
        if (row != null && row.kind == Kind.SWITCH)
            for (Choice choice : row.options.choices(0))
                if (choice.fieldValue() == (on ? 1 : 0)) return this.lang.get(choice.key() + ".state", choice.state());
        return on ? this.lang.get("state.enabled", "ENABLED") : this.lang.get("state.disabled", "DISABLED");
    }

    /** A row's label, or a section header's upper-case title (key "row.<id>"). */
    private String rowLabel(Row row) {
        return this.lang.get("row." + row.id, row.label);
    }

    /** A dropdown entry's text: a fixed choice, a number with its unit, or a bare number. */
    private String choiceLabel(Choice choice) {
        if (choice.key() == null) return choice.label();
        if (choice.key().startsWith("unit.")) return this.lang.format(choice.key(), choice.label(), "value", choice.value());
        return this.lang.get(choice.key(), choice.label());
    }

    /** True for a dropdown setting shown as a switch (it also accepts dropdown events). */
    private static boolean isBooleanToggle(String id) {
        Row row = row(id);
        return row != null && row.kind == Kind.SWITCH;
    }

    /** A switch row's state (field value 1); false for every other row. */
    private static boolean toggleValue(String id, DynamicMinimapSetting settings) {
        Row row = row(id);
        return row != null && (row.kind == Kind.TOGGLE || row.kind == Kind.SWITCH) && row.field.get(settings) == 1;
    }

    /** A row's description (key "row.<id>.desc"). */
    private String description(String id) {
        if ("bettermap".equals(id) && !BetterMapIntegration.isAvailable())
            return this.lang.get("row.bettermap.missing", "BetterMap is not installed or could not be detected.");
        Row row = row(id);
        return row == null ? "" : this.lang.get("row." + id + ".desc", row.description);
    }

    /**
     * True when a row's control is unavailable: minimap off, a setting it depends on off (see each
     * row's {@code requires}), or BetterMap (or its cave mode) missing.
     */
    private boolean isDropdownDisabled(String id, DynamicMinimapSetting settings) {
        if (!settings.enabled) return true;
        Row row = row(id);
        if (row == null) return false;
        for (DynamicMinimapSetting.Field field : row.requires) if (field.get(settings) == 0) return true;
        return (id.startsWith("bettermap") && !BetterMapIntegration.isAvailable())
                || ("bettermap_caves".equals(id) && !this.betterMapCaveAvailable);
    }

    private boolean isControlDisabled(String id, DynamicMinimapSetting settings) {
        return !"enabled".equals(id) && isDropdownDisabled(id, settings);
    }

    /** Stores an offered dropdown value (including an existing custom number) in its setting. */
    private void applyDropdown(DynamicMinimapSetting settings, String id, String value) {
        if (isDropdownDisabled(id, settings)) return;
        if (value == null || value.isBlank()) return;
        Row row = row(id);
        if (row == null || row.options == null) return;
        // Accept only an offered value; the entry says which field value it stands for.
        for (Choice choice : row.options.choices(row.field.get(settings))) {
            if (!choice.value().equals(value)) continue;
            row.field.set(settings, choice.fieldValue());
            settings.normalize();
            return;
        }
    }

    /** A dropdown row's entries, with choices above a server limit tagged (player page). */
    private List<DropdownEntryInfo> dropdownEntries(String id, DynamicMinimapSetting settings) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        Row row = row(id);
        if (row == null || row.options == null) return entries;
        for (Choice choice : row.options.choices(row.field.get(settings))) entries.add(entry(this.choiceLabel(choice), choice.value()));
        this.markOverLimit(entries, id);
        return entries;
    }

    // ---- Server limits (3.1.0) ---------------------------------------------------------

    /** Persists edited limits and re-clamps live minimaps; untouched limits cause no file write. */
    private void saveLimitsIfChanged() {
        // Terrain colour reduction: saved and every live terrain redrawn only when it changed.
        if (this.draftTerrainColours >= 0 && this.draftTerrainColours != this.config.getTerrainColours()) {
            DapperMap.applyTerrainColours(this.draftTerrainColours);
            this.sessions.redrawAllTerrain();
        }
        if (this.draftLimits == null || java.util.Arrays.equals(this.draftLimits, this.config.limits())) return;
        this.config.setLimits(this.draftLimits);
        DapperMap.saveConfig();
        this.sessions.refreshLiveSettings(); // Players' saved settings are not changed.
    }

    /** "Fast", "512 px", "128 blocks" or "32": a limit value as the page shows it. */
    private String limitLabel(DapperMapConfig.Limit limit, int value) {
        return switch (limit) {
            case SPEED -> this.displaySpeed(value);
            case RESOLUTION -> this.lang.format("unit.px", value + limit.unit, "value", value);
            case ZOOM -> this.lang.format("unit.blocks", value + limit.unit, "value", value);
            default -> Integer.toString(value); // Creature counts have no unit.
        };
    }

    /** Admin dropdown for one server limit; the "no limit" value is labelled as such. */
    private void limitRow(UICommandBuilder builder, UIEventBuilder events, int index, Row row) {
        String selector = "#DapperMapSettingsList[" + index + "]";
        builder.append("#DapperMapSettingsList", "Pages/DapperMapDropdownRow.ui");
        builder.set(selector + " #RowLabel.Text", this.rowLabel(row));
        builder.set(selector + " #RowDescription.Text", this.description(row.id));
        String dropdownSelector = selector + " #ValueDropdown";
        builder.set(dropdownSelector + ".Entries", this.limitEntries(row.id));
        builder.set(dropdownSelector + ".Value", Integer.toString(this.draftLimits[DapperMapConfig.Limit.byAdminRow(row.id).ordinal()]));
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, dropdownSelector,
                EventData.of("Action", "dropdown_" + row.id).append("@Value", dropdownSelector + ".Value"), false);
    }

    /** Admin dropdown entries for one limit: the player row's choices, with the widest marked "(no limit)". */
    private List<DropdownEntryInfo> limitEntries(String id) {
        DapperMapConfig.Limit limit = DapperMapConfig.Limit.byAdminRow(id);
        List<DropdownEntryInfo> entries = new ArrayList<>();
        if (limit == null) return entries;
        int current = this.draftLimits[limit.ordinal()];
        TreeSet<Integer> values = new TreeSet<>();
        switch (limit) {
            case SPEED -> { for (int value = limit.min; value <= limit.max; value++) values.add(value); }
            case RESOLUTION -> { values = resolutionChoices(); values.add(current); }
            case ZOOM -> { for (int value = limit.min; value <= limit.max; value += 16) values.add(value); values.add(current); }
            case CREATURES -> { for (int value : DynamicMinimapSetting.CREATURE_LIMITS) values.add(value); }
        }
        for (int value : values)
            entries.add(entry(value == limit.noLimit
                    ? this.lang.format("limit.no_limit", "{value} (no limit)", "value", this.limitLabel(limit, value))
                    : this.limitLabel(limit, value), Integer.toString(value)));
        return entries;
    }

    /** Admin dropdown for the server-wide terrain colour reduction ("Off" sends exact colours). */
    private void terrainColoursRow(UICommandBuilder builder, UIEventBuilder events, int index, Row row) {
        String selector = "#DapperMapSettingsList[" + index + "]";
        builder.append("#DapperMapSettingsList", "Pages/DapperMapDropdownRow.ui");
        builder.set(selector + " #RowLabel.Text", this.rowLabel(row));
        builder.set(selector + " #RowDescription.Text", this.description(row.id));
        String dropdownSelector = selector + " #ValueDropdown";
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (int value : this.terrainColourChoices())
            entries.add(entry(value == 0 ? this.lang.get("terrain.off", "Off (exact colours)")
                    : this.lang.format("terrain.colours", "{value} colours", "value", value), Integer.toString(value)));
        builder.set(dropdownSelector + ".Entries", entries);
        builder.set(dropdownSelector + ".Value", Integer.toString(this.draftTerrainColours));
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, dropdownSelector,
                EventData.of("Action", "dropdown_" + row.id).append("@Value", dropdownSelector + ".Value"), false);
    }

    /** The offered colour counts, plus a hand-edited config value so the dropdown can show it. */
    private TreeSet<Integer> terrainColourChoices() {
        TreeSet<Integer> values = new TreeSet<>();
        for (int value : DapperMapConfig.TERRAIN_COLOUR_CHOICES) values.add(value);
        values.add(this.config.getTerrainColours());
        return values;
    }

    /** Validates an offered value and stores it in the page draft; false when nothing changed. */
    private boolean applyLimitDropdown(String id, String value) {
        DapperMapConfig.Limit limit = DapperMapConfig.Limit.byAdminRow(id);
        if (limit == null || value == null
                || this.limitEntries(id).stream().noneMatch(entry -> entry.value().equals(value))) return false;
        int parsed = Integer.parseInt(value);
        if (this.draftLimits[limit.ordinal()] == parsed) return false;
        this.draftLimits[limit.ordinal()] = parsed;
        return true;
    }

    /** Player page: tags choices above a server limit. They stay selectable and are saved, but play at the limit. */
    private void markOverLimit(List<DropdownEntryInfo> entries, String id) {
        DapperMapConfig.Limit limit = DapperMapConfig.Limit.byPageRow(id);
        if (this.adminMode || limit == null || !this.config.isLimited(limit)) return;
        int max = this.config.limit(limit);
        for (int i = 0; i < entries.size(); i++) {
            DropdownEntryInfo entry = entries.get(i);
            int value = Integer.parseInt(entry.value());
            if (!limit.exceeds(value, max)) continue;
            entries.set(i, new DropdownEntryInfo(LocalizableString.fromString(this.lang.format("limit.over",
                    "{value} (server limit)", "value", this.limitLabel(limit, value))), entry.value(),
                    LocalizableString.fromString(this.lang.format("limit.over.tooltip",
                            "This server limits this setting to {limit}. Your choice is kept and used if the limit is lifted.",
                            "limit", this.limitLabel(limit, max)))));
        }
    }

    /** Row description, with a short server-limit note on the player page when a limit applies. */
    private String rowDescription(String id) {
        DapperMapConfig.Limit limit = this.adminMode ? null : DapperMapConfig.Limit.byPageRow(id);
        boolean limited = limit != null && this.config.isLimited(limit);
        return limited ? this.lang.format("limit.note", "{description} Server limit: {limit}.",
                "description", this.description(id), "limit", this.limitLabel(limit, this.config.limit(limit)))
                : this.description(id);
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    /** The value a dropdown row shows: its matching entry, else the number itself. */
    private static String dropdownValue(String id, DynamicMinimapSetting settings) {
        Row row = row(id);
        if (row == null || row.options == null) throw new IllegalArgumentException("Unknown dropdown: " + id);
        int value = row.field.get(settings);
        for (Choice choice : row.options.choices(value)) if (choice.fieldValue() == value) return choice.value();
        return Integer.toString(value);
    }

    private static int clampPercent(int value) {
        return dev.scyphoz.dappermap.util.DapperMapUtil.clamp(value, DynamicMinimapSetting.POSITION_MIN, DynamicMinimapSetting.POSITION_MAX);
    }

    /** The texture resolutions offered by the page, as a sorted set the current value can join. */
    private static TreeSet<Integer> resolutionChoices() {
        TreeSet<Integer> values = new TreeSet<>();
        for (int value : DynamicMinimapSetting.TEXTURE_RESOLUTIONS) values.add(value);
        return values;
    }

    private static int nearestPositionStop(int value) {
        if (value < 25) return 0;
        if (value > 75) return 100;
        return 50;
    }

    /** Summary name of a position ("TOP RIGHT", "MIDDLE LEFT", "CENTRE"); key "position.name.<v>_<h>". */
    private String positionName(int x, int y) {
        String vertical = y < 25 ? "TOP" : (y > 75 ? "BOTTOM" : "MIDDLE");
        String horizontal = x < 25 ? "LEFT" : (x > 75 ? "RIGHT" : "CENTRE");
        String english = "MIDDLE".equals(vertical) && "CENTRE".equals(horizontal) ? "CENTRE" : vertical + " " + horizontal;
        return this.lang.get("position.name." + (vertical + "_" + horizontal).toLowerCase(java.util.Locale.ROOT), english);
    }

    /** Quick placement button name ("TOP LEFT", "TOP", "CENTRE"); key "position.button.<v>_<h>". */
    private String positionButtonName(int x, int y) {
        String vertical = y < 25 ? "top" : (y > 75 ? "bottom" : "middle");
        String horizontal = x < 25 ? "left" : (x > 75 ? "right" : "centre");
        String english;
        if (y < 25) english = x < 25 ? "TOP LEFT" : (x > 75 ? "TOP RIGHT" : "TOP");
        else if (y > 75) english = x < 25 ? "BOTTOM LEFT" : (x > 75 ? "BOTTOM RIGHT" : "BOTTOM");
        else english = x < 25 ? "LEFT" : (x > 75 ? "RIGHT" : "CENTRE");
        return this.lang.get("position.button." + vertical + "_" + horizontal, english);
    }

    /** "Very fast" .. "Slow"; anything out of range reads as Normal. */
    private String displaySpeed(int speed) {
        int index = speed >= 0 && speed < DapperMapConfig.SPEED_LABELS.length ? speed : 2;
        return this.lang.get("choice.speed." + index, DapperMapConfig.SPEED_LABELS[index]);
    }

    /** The row with this id, or null (headers included; ids are unique). */
    private static Row row(String id) {
        return id == null ? null : ROW_BY_ID.get(id);
    }

    // ---- Font Size and Marker Size -----------------------------------------------------

    /** One sizeable area: its own setting row id (= permission row), page label and field. */
    private record SizeArea(String id, String label, DynamicMinimapSetting.Field field) { }

    /**
     * A Font/Marker Size section: an area dropdown row ({@code areaRow}) and a slider row
     * ({@code sliderRow}) whose 11 stops map to {@code stops} (stop 5, the middle, is 100%).
     */
    private record SizeGroup(String areaRow, String sliderRow, List<SizeArea> areas, int[] stops, String description) {
        /** The area with this id, or null. */
        SizeArea area(String id) {
            for (SizeArea area : this.areas) if (area.id().equals(id)) return area;
            return null;
        }

        /** Slider stop nearest to a percentage (commands can set values between stops). */
        int stopFor(int percent) {
            int best = 0;
            for (int i = 1; i < this.stops.length; i++)
                if (Math.abs(this.stops[i] - percent) < Math.abs(this.stops[best] - percent)) best = i;
            return best;
        }
    }

    private static final SizeGroup FONT_SIZES = new SizeGroup("font_area", "font_size", List.of(
            new SizeArea("font_title", "Panel Title", DynamicMinimapSetting.Field.FONT_TITLE),
            new SizeArea("font_headings", "Panel Headings", DynamicMinimapSetting.Field.FONT_HEADINGS),
            new SizeArea("font_values", "Panel Values", DynamicMinimapSetting.Field.FONT_VALUES),
            new SizeArea("font_compass", "Compass Letters", DynamicMinimapSetting.Field.FONT_COMPASS)),
            DynamicMinimapSetting.FONT_SCALE_STOPS,
            "Left for smaller text, right for larger. The middle is the standard size.");
    private static final SizeGroup MARKER_SIZES = new SizeGroup("marker_area", "marker_size", List.of(
            new SizeArea("size_creatures", "Creatures", DynamicMinimapSetting.Field.MARKER_CREATURES),
            new SizeArea("size_players", "Players", DynamicMinimapSetting.Field.MARKER_PLAYERS),
            new SizeArea("size_map", "Map Markers", DynamicMinimapSetting.Field.MARKER_MAP),
            new SizeArea("size_arrow", "Your Arrow", DynamicMinimapSetting.Field.MARKER_ARROW)),
            DynamicMinimapSetting.MARKER_SCALE_STOPS,
            "Left for smaller markers, right for larger. The middle is the standard size.");

    /** The size section a row belongs to (its area or slider row), or null. */
    private static SizeGroup sizeGroup(String rowId) {
        if (FONT_SIZES.areaRow().equals(rowId) || FONT_SIZES.sliderRow().equals(rowId)) return FONT_SIZES;
        if (MARKER_SIZES.areaRow().equals(rowId) || MARKER_SIZES.sliderRow().equals(rowId)) return MARKER_SIZES;
        return null;
    }

    /** Areas of a section this page may change (each has its own node). */
    private List<SizeArea> permittedAreas(SizeGroup group) {
        List<SizeArea> out = new ArrayList<>(group.areas().size());
        for (SizeArea area : group.areas())
            if (this.hasSettingPermission(permissionForSetting(area.id()))) out.add(area);
        return out;
    }

    /** The area the section's slider edits: the chosen one when still permitted, else the first permitted (or null). */
    private SizeArea selectedArea(SizeGroup group) {
        List<SizeArea> permitted = this.permittedAreas(group);
        String chosen = group == FONT_SIZES ? this.selectedFontArea : this.selectedMarkerArea;
        for (SizeArea area : permitted) if (area.id().equals(chosen)) return area;
        return permitted.isEmpty() ? null : permitted.get(0);
    }

    /** Area dropdown entries: each permitted area (its size shows beside the slider below). */
    private List<DropdownEntryInfo> areaEntries(SizeGroup group) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (SizeArea area : this.permittedAreas(group)) entries.add(entry(this.lang.get("area." + area.id(), area.label()), area.id()));
        return entries;
    }

    /** True when a Font/Marker Size row is greyed out: minimap off, or none of its areas permitted. */
    private boolean sizeRowDisabled(String rowId, DynamicMinimapSetting settings) {
        SizeGroup group = sizeGroup(rowId);
        return group == null || isDropdownDisabled(rowId, settings) || this.permittedAreas(group).isEmpty();
    }

    /** The area dropdown row of a Font/Marker Size section. */
    private void areaRow(UICommandBuilder builder, UIEventBuilder events, int index, Row row,
                         DynamicMinimapSetting settings, SizeGroup group) {
        String selector = "#DapperMapSettingsList[" + index + "]";
        builder.append("#DapperMapSettingsList", "Pages/DapperMapDropdownRow.ui");
        builder.set(selector + " #RowLabel.Text", this.rowLabel(row));
        builder.set(selector + " #RowDescription.Text", this.description(row.id));
        String dropdownSelector = selector + " #ValueDropdown";
        builder.set(dropdownSelector + ".Entries", this.areaEntries(group));
        builder.set(dropdownSelector + ".Value", this.selectedArea(group).id());
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, dropdownSelector,
                EventData.of("Action", "area_" + row.id).append("@Value", dropdownSelector + ".Value"), false);
    }

    /** The size slider row of a Font/Marker Size section, showing the selected area's size. */
    private void sliderRow(UICommandBuilder builder, UIEventBuilder events, int index, Row row,
                           DynamicMinimapSetting settings, SizeGroup group) {
        String selector = "#DapperMapSettingsList[" + index + "]";
        builder.append("#DapperMapSettingsList", "Pages/DapperMapScaleSliderRow.ui");
        builder.set(selector + " #RowLabel.Text", this.rowLabel(row));
        builder.set(selector + " #RowDescription.Text", this.description(row.id)); // = group.description() in English.
        int value = this.selectedArea(group).field().get(settings);
        builder.set(selector + " #ValueSlider.Value", group.stopFor(value));
        builder.set(selector + " #ValueLabel.Text", this.percent(value));
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, selector + " #ValueSlider",
                EventData.of("Action", "scale_" + row.id).append("@SliderValue", selector + " #ValueSlider.Value"), false);
    }

    /**
     * Area dropdown or slider event. Area: the slider now edits that area (its value and label
     * are re-sent). Slider: the stop's percentage is stored in the selected area's setting and
     * only the value label is re-sent. Returns false when nothing changed.
     */
    private boolean handleSizeEvent(UICommandBuilder builder, String action, Ev event, DynamicMinimapSetting settings) {
        boolean area = action.startsWith("area_");
        SizeGroup group = sizeGroup(action.substring(area ? "area_".length() : "scale_".length()));
        if (group == null || isDropdownDisabled(group.areaRow(), settings)) return false;
        int sliderIndex = this.renderedIndex(group.sliderRow());
        if (area) {
            SizeArea chosen = group.area(event.value);
            if (chosen == null || !this.permittedAreas(group).contains(chosen)) return false;
            if (group == FONT_SIZES) this.selectedFontArea = chosen.id(); else this.selectedMarkerArea = chosen.id();
            if (sliderIndex < 0) return false;
            int value = chosen.field().get(settings);
            String selector = "#DapperMapSettingsList[" + sliderIndex + "]";
            builder.set(selector + " #ValueSlider.Value", group.stopFor(value));
            builder.set(selector + " #ValueLabel.Text", this.percent(value));
            return false; // Page state only: the draft (and preview) are unchanged.
        }
        SizeArea selected = this.selectedArea(group);
        if (selected == null) return false;
        int stop = dev.scyphoz.dappermap.util.DapperMapUtil.clamp(event.sliderValue, 0, group.stops().length - 1);
        int value = group.stops()[stop];
        if (selected.field().get(settings) == value) return false;
        selected.field().set(settings, value);
        if (sliderIndex >= 0) builder.set("#DapperMapSettingsList[" + sliderIndex + "] #ValueLabel.Text", this.percent(value));
        return true;
    }

    // ---- Row table ---------------------------------------------------------------------

    /**
     * One dropdown entry: its English label, the value the client sends back, the field value it
     * stands for, its English switch state, and its text key (null for a bare number). A "unit."
     * key is a template with {value} (numbers); others are "choice.<set>.<value>" (+ ".state").
     */
    private record Choice(String label, String value, int fieldValue, String state, String key) { }

    /** A row's dropdown entries, given the setting's current value (number lists include it). */
    private interface Options {
        List<Choice> choices(int current);
    }

    /** A fixed list of entries. */
    private record Fixed(List<Choice> list) implements Options {
        public List<Choice> choices(int current) { return this.list; }
    }

    /** English unit templates by unit key ("unit.<name>" in dappermap.lang). */
    private static final java.util.Map<String, String> UNITS = java.util.Map.of(
            "blocks", "{value} blocks", "px", "{value} px", "percent", "{value}%");

    /** Numbers in ascending order, plus the current value when it is not one of them (e.g. "128 blocks"). */
    private record Numbers(int[] base, String unit) implements Options {
        public List<Choice> choices(int current) {
            TreeSet<Integer> values = new TreeSet<>();
            for (int value : this.base) values.add(value);
            values.add(current);
            String english = UNITS.get(this.unit);
            List<Choice> out = new ArrayList<>(values.size());
            for (int value : values) {
                String text = Integer.toString(value);
                out.add(new Choice(english.replace("{value}", text), text, value, null, "unit." + this.unit));
            }
            return out;
        }
    }

    /** A fixed list of entries; each gets the key "choice.<set>.<value>" (no keys when set is null). */
    private static Options choices(String set, Choice... choices) {
        Choice[] keyed = new Choice[choices.length];
        for (int i = 0; i < choices.length; i++) {
            Choice c = choices[i];
            keyed[i] = new Choice(c.label(), c.value(), c.fieldValue(), c.state(), set == null ? null : "choice." + set + "." + c.value());
        }
        return new Fixed(List.of(keyed));
    }

    /** Two entries for a 0/1 setting: the one shown first, then the other. */
    private static Choice on(String label, String value, String state) { return new Choice(label, value, 1, state, null); }
    private static Choice off(String label, String value, String state) { return new Choice(label, value, 0, state, null); }

    /** Enabled/Disabled for an on/off setting. */
    private static final Options ON_OFF = choices("on_off", on("Enabled", "true", "ENABLED"), off("Disabled", "false", "DISABLED"));
    private static final Options ICONS_DOTS = choices("style", on("Icons", "icons", null), off("Dots", "dots", null));

    /** Every value from {@code min} to {@code max} in {@code step}s. */
    private static int[] steps(int min, int max, int step) {
        int[] out = new int[(max - min) / step + 1];
        for (int i = 0; i < out.length; i++) out[i] = min + i * step;
        return out;
    }

    /** Integer entries labelled by {@code labels}, valued 0, 1, 2... in the order given by {@code values}. */
    private static Options indexed(String set, String[] labels, int... values) {
        Choice[] out = new Choice[values.length];
        for (int i = 0; i < values.length; i++) out[i] = new Choice(labels[i], Integer.toString(values[i]), values[i], null, null);
        return choices(set, out);
    }

    private static final List<Row> ROW_LIST = new ArrayList<>();
    private static final java.util.Map<String, Row> ROW_BY_ID = new java.util.HashMap<>();
    /** Tab of the rows being added (static initialisation only). */
    private static Tab addingTab;

    private static void header(String id, String label) {
        add(new Row(id, label, Kind.HEADER, addingTab, null, null, "", new DynamicMinimapSetting.Field[0]));
    }

    private static void row(String id, String label, Kind kind, DynamicMinimapSetting.Field field, Options options,
                            String description, DynamicMinimapSetting.Field... requires) {
        add(new Row(id, label, kind, addingTab, field, options, description, requires));
    }

    private static void add(Row row) {
        ROW_LIST.add(row);
        ROW_BY_ID.put(row.id, row);
    }

    static {
        DynamicMinimapSetting.Field F_ENTITIES = DynamicMinimapSetting.Field.SHOW_ENTITIES;
        DynamicMinimapSetting.Field F_PLAYERS = DynamicMinimapSetting.Field.SHOW_OTHER_PLAYERS;
        DynamicMinimapSetting.Field F_PANEL = DynamicMinimapSetting.Field.INFO_PANEL_ENABLED;
        // Display tab: appearance, then the compass ring, then the settings that cost server work.
        addingTab = Tab.DISPLAY;
        row("enabled", "Minimap", Kind.TOGGLE, DynamicMinimapSetting.Field.ENABLED, null, "Show or hide your minimap.");
        header("section_0", "MAP APPEARANCE");
        row("zoom", "Zoom Radius", Kind.DROPDOWN, DynamicMinimapSetting.Field.ZOOM_BLOCKS,
                new Numbers(steps(DynamicMinimapSetting.ZOOM_MIN, DynamicMinimapSetting.ZOOM_MAX, 16), "blocks"),
                "Choose how far around you the map shows.");
        row("size", "Map Size", Kind.DROPDOWN, DynamicMinimapSetting.Field.SIZE_PIXELS,
                new Numbers(steps(DynamicMinimapSetting.SIZE_MIN, DynamicMinimapSetting.SIZE_MAX, 20), "px"),
                "Change the size of the map on your screen. Markers grow with larger maps.");
        row("interface_scale", "Interface Scale", Kind.DROPDOWN, DynamicMinimapSetting.Field.INTERFACE_SCALE,
                new Numbers(new int[]{75, 85, 100, 110, 125, 140, 160}, "percent"), "Resize the map and its information together.");
        row("shape", "Map Shape", Kind.SWITCH, DynamicMinimapSetting.Field.SHAPE,
                choices("shape", on("Circle", "1", "CIRCLE"), off("Square", "0", "SQUARE")), "Choose a round or square map.");
        row("rotation", "Map Rotation", Kind.SWITCH, DynamicMinimapSetting.Field.ROTATION_MODE,
                choices("rotation", off("North Up", "0", "NORTH UP"), on("Player Facing", "1", "PLAYER FACING")),
                "Keep north at the top, or turn the map with the direction you face.");
        // Compass ring around the map (part of the map, not the Info Panel).
        header("section_compass", "COMPASS RING");
        row("compass_letters", "Compass Letters", Kind.SWITCH, DynamicMinimapSetting.Field.SHOW_COMPASS_LETTERS, ON_OFF,
                "Show the N, E, S and W letters around the map edge.");
        row("compass_dashes", "Compass Dashes", Kind.SWITCH, DynamicMinimapSetting.Field.SHOW_COMPASS_DASHES, ON_OFF,
                "Show the small dashes around the map edge.");
        row("cardinal_dashes", "Cardinal Dashes", Kind.SWITCH, DynamicMinimapSetting.Field.SHOW_CARDINAL_DASHES, ON_OFF,
                "Show the large N, E, S and W dashes. When hidden, the letters take their place.");
        // Font Size: pick a text area, then size it with the slider (each area is its own setting).
        header("section_font", "FONT SIZE");
        row(FONT_SIZES.areaRow(), "Text", Kind.AREA, null, null, "Choose which text the size slider changes.");
        row(FONT_SIZES.sliderRow(), "Size", Kind.SLIDER, null, null, FONT_SIZES.description());
        header("section_performance", "PERFORMANCE");
        row("speed", "Update Speed", Kind.DROPDOWN, DynamicMinimapSetting.Field.UPDATE_SPEED,
                indexed("speed", DapperMapConfig.SPEED_LABELS, 0, 1, 2, 3), "How often the minimap refreshes. Faster uses more server work.");
        row("texture_resolution", "Texture Resolution", Kind.DROPDOWN, DynamicMinimapSetting.Field.TEXTURE_RESOLUTION,
                new Numbers(DynamicMinimapSetting.TEXTURE_RESOLUTIONS, "px"),
                "Sharper map detail. Higher values use more server work and data.");
        // Admin page only (Server Limits tab): server-wide limits that clamp every live minimap.
        addingTab = Tab.SERVER;
        header("section_limits", "PLAYER SETTING LIMITS");
        row("limit_speed", "Fastest Update Speed", Kind.DROPDOWN, null, null, "Fastest update speed any player's minimap runs at.");
        row("limit_resolution", "Max Texture Resolution", Kind.DROPDOWN, null, null, "Highest texture resolution any player's minimap renders at.");
        row("limit_zoom", "Max Zoom Radius", Kind.DROPDOWN, null, null, "Largest zoom radius any player's minimap can show.");
        row("limit_creatures", "Max Creature Markers", Kind.DROPDOWN, null, null, "Most creature markers any player's minimap shows.");
        header("section_terrain_images", "TERRAIN IMAGES");
        row(TERRAIN_COLOURS_ROW, "Terrain Colour Reduction", Kind.DROPDOWN, null, null,
                "Fewer colours make smaller terrain images (less data sent to players) but can shift map colours. Off sends exact colours.");
        // Info Panel tab: every row but the panel switch needs the panel on.
        addingTab = Tab.INTERFACE;
        row("info_panel", "Info Panel", Kind.TOGGLE, F_PANEL, null, "Show or hide the Info Panel while keeping the minimap visible.");
        row("panel_position", "Info Panel Position", Kind.DROPDOWN, DynamicMinimapSetting.Field.INFO_PANEL_POSITION,
                indexed("panel_position", new String[]{"Right", "Left", "Above", "Below"}, DynamicMinimapSetting.INFO_RIGHT,
                        DynamicMinimapSetting.INFO_LEFT, DynamicMinimapSetting.INFO_ABOVE, DynamicMinimapSetting.INFO_BELOW),
                "Choose which side of the map the Info Panel sits on.", F_PANEL);
        row("panel_alignment", "Info Panel Alignment", Kind.DROPDOWN, DynamicMinimapSetting.Field.INFO_PANEL_TEXT_ALIGNMENT,
                indexed("panel_alignment", new String[]{"Left", "Centre", "Right"}, DynamicMinimapSetting.INFO_ALIGN_LEFT,
                        DynamicMinimapSetting.INFO_ALIGN_CENTER, DynamicMinimapSetting.INFO_ALIGN_RIGHT),
                "Align the Info Panel text left, centre or right.", F_PANEL);
        // Markers tab: player and creature details need their markers on.
        addingTab = Tab.MARKERS;
        header("section_players", "PLAYERS");
        row("players", "Player Markers", Kind.SWITCH, F_PLAYERS, ON_OFF, "Show nearby players on your minimap.");
        row("player_style", "Player Marker Style", Kind.DROPDOWN, DynamicMinimapSetting.Field.PLAYER_ICONS, ICONS_DOTS,
                "Show players as head icons or as dots.", F_PLAYERS);
        row("player_helmets", "Player Icon Helmets", Kind.SWITCH, DynamicMinimapSetting.Field.PLAYER_ICON_HELMETS, ON_OFF,
                "Show the helmet each player wears on their icon.", F_PLAYERS, DynamicMinimapSetting.Field.PLAYER_ICONS);
        header("section_creatures", "CREATURES");
        row("entities", "Creature Markers", Kind.SWITCH, F_ENTITIES, ON_OFF, "Show nearby creatures on your minimap.");
        row("creature_style", "Creature Marker Style", Kind.DROPDOWN, DynamicMinimapSetting.Field.CREATURE_ICONS, ICONS_DOTS,
                "Show creatures as face icons or as threat-coloured dots.", F_ENTITIES);
        row("creature_filter", "Creature Filter", Kind.DROPDOWN, DynamicMinimapSetting.Field.HOSTILE_CREATURES_ONLY,
                choices("creature_filter", off("All", "all", null), on("Hostile Only", "hostile", null)),
                "Show all creatures, or only hostile and attacking ones.", F_ENTITIES);
        row("creature_limit", "Creature Marker Limit", Kind.DROPDOWN, DynamicMinimapSetting.Field.CREATURE_MARKER_LIMIT,
                indexed(null, new String[]{"16", "32", "64"}, DynamicMinimapSetting.CREATURE_LIMITS),
                "Show up to 16, 32 or 64 nearby creatures, prioritising the nearest.", F_ENTITIES);
        row("height", "Creature Height Filter", Kind.SWITCH, DynamicMinimapSetting.Field.LIMIT_MOB_HEIGHT, ON_OFF,
                "Hide creatures far above or below you.", F_ENTITIES);
        int[] heights = steps(8, DynamicMinimapSetting.HEIGHT_RANGE_MAX, 8);
        int[] vrange = new int[heights.length + 1];
        vrange[0] = DynamicMinimapSetting.HEIGHT_RANGE_MIN;
        System.arraycopy(heights, 0, vrange, 1, heights.length);
        row("vrange", "Height Filter Range", Kind.DROPDOWN, DynamicMinimapSetting.Field.MOB_VERTICAL_RANGE,
                new Numbers(vrange, "blocks"), "How far above and below you creatures are shown.",
                F_ENTITIES, DynamicMinimapSetting.Field.LIMIT_MOB_HEIGHT);
        row("critters", "Critter Markers", Kind.DROPDOWN, DynamicMinimapSetting.Field.CRITTER_FILTER,
                indexed("critters", new String[]{"Show All", "Hide Ground", "Hide Flying", "Hide Both"},
                        DynamicMinimapSetting.CRITTERS_SHOW, DynamicMinimapSetting.CRITTERS_HIDE_GROUND,
                        DynamicMinimapSetting.CRITTERS_HIDE_FLYING, DynamicMinimapSetting.CRITTERS_HIDE_BOTH),
                "Hide harmless ground critters (mice, frogs), flying critters (birds, bats), or both.", F_ENTITIES);
        row("memories", "Uncollected Memories", Kind.SWITCH, DynamicMinimapSetting.Field.MEMORY_HINTS, ON_OFF,
                "Slowly flash creatures that still have a memory for you to collect.", F_ENTITIES);
        header("section_other", "OTHER");
        row("death", "Death Markers", Kind.SWITCH, DynamicMinimapSetting.Field.SHOW_DEATH_MARKER, ON_OFF,
                "Show death markers on your minimap.");
        // Marker Size: pick a marker type, then size it with the slider (each type is its own setting).
        header("section_marker_size", "MARKER SIZE");
        row(MARKER_SIZES.areaRow(), "Marker Type", Kind.AREA, null, null, "Choose which markers the size slider changes.");
        row(MARKER_SIZES.sliderRow(), "Size", Kind.SLIDER, null, null, MARKER_SIZES.description());
        // Info Panel tab (continued).
        addingTab = Tab.INTERFACE;
        header("section_3", "INFORMATION");
        row("coordinates", "Coordinates", Kind.SWITCH, DynamicMinimapSetting.Field.SHOW_COORDINATES, ON_OFF,
                "Show your current world coordinates.", F_PANEL);
        row("compass", "Bearing", Kind.SWITCH, DynamicMinimapSetting.Field.SHOW_COMPASS, ON_OFF,
                "Show the direction you are facing.", F_PANEL);
        row("clock", "World Time", Kind.SWITCH, DynamicMinimapSetting.Field.SHOW_CLOCK, ON_OFF,
                "Show the current time in your Hytale world.", F_PANEL);
        row("clock_format", "Clock Format", Kind.SWITCH, DynamicMinimapSetting.Field.CLOCK_24_HOUR,
                choices("clock_format", on("24-hour", "true", "24 HOUR"), off("12-hour", "false", "12 HOUR")),
                "Choose a 12-hour or 24-hour clock.", F_PANEL, DynamicMinimapSetting.Field.SHOW_CLOCK);
        row("biome", "Region", Kind.SWITCH, DynamicMinimapSetting.Field.SHOW_BIOME, ON_OFF,
                "Show the name of the region you are in.", F_PANEL);
        // Integrations tab: BetterMap rows also need BetterMap installed (isDropdownDisabled).
        addingTab = Tab.INTEGRATIONS;
        header("section_4", "HYTALE");
        row("worldmap", "Hytale World Map Markers", Kind.SWITCH, DynamicMinimapSetting.Field.SHOW_WORLD_MAP_MARKERS, ON_OFF,
                "Show Hytale personal, shared, POI, spawn and respawn world-map markers.");
        header("section_5", "BETTERMAP");
        row("bettermap", "BetterMap", Kind.SWITCH, DynamicMinimapSetting.Field.BETTERMAP_ENABLED, ON_OFF,
                "Use BetterMap's waypoints and cave maps on your minimap.");
        row("bettermap_markers", "BetterMap Markers", Kind.SWITCH, DynamicMinimapSetting.Field.BETTERMAP_MARKERS, ON_OFF,
                "Show waypoints with BetterMap icons. When off, they appear with Hytale map markers.",
                DynamicMinimapSetting.Field.BETTERMAP_ENABLED);
        row("bettermap_caves", "BetterMap Cave Mode", Kind.SWITCH, DynamicMinimapSetting.Field.BETTERMAP_CAVES, ON_OFF,
                "Show BetterMap's cave terrain while underground.", DynamicMinimapSetting.Field.BETTERMAP_ENABLED);
        addingTab = null;
        ROWS = ROW_LIST.toArray(new Row[0]);
        java.util.Set<String> dropdowns = new java.util.HashSet<>();
        for (Row row : ROWS) if ((row.kind == Kind.DROPDOWN || row.kind == Kind.SWITCH) && row.tab != Tab.SERVER) dropdowns.add(row.id);
        DROPDOWN_IDS = java.util.Set.copyOf(dropdowns);
    }

    public static final class Ev {

        public static final BuilderCodec<Ev> CODEC;

        private String action = "";
        private String value = "";
        private String target = "";
        private int sliderValue = 0;
        private com.hypixel.hytale.protocol.Color colour;
        /** Position slider values sent with Apply (null when absent). */
        private Integer posX, posY;

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
                    .append(new KeyedCodec<>("@SliderValue", Codec.INTEGER, true),
                            (ev, value) -> ev.sliderValue = value, ev -> ev.sliderValue).add()
                    .append(new KeyedCodec<>("@PosX", Codec.INTEGER, true), (ev, value) -> ev.posX = value, ev -> ev.posX).add()
                    .append(new KeyedCodec<>("@PosY", Codec.INTEGER, true), (ev, value) -> ev.posY = value, ev -> ev.posY).add()
                    .build();
        }
    }

    private enum Kind {
        /** Section heading. */
        HEADER,
        /** Font/Marker Size: dropdown choosing which area the size slider below it edits (page state only). */
        AREA,
        /** Font/Marker Size: the slider for the area chosen above it. */
        SLIDER,
        /** On/off switch that takes toggle events only (Minimap, Info Panel). */
        TOGGLE,
        /** A two-value setting shown as a switch; it also takes dropdown events. */
        SWITCH,
        /** Dropdown (server limits too, on the Server tab). */
        DROPDOWN
    }

    private enum Tab {
        DISPLAY("display", "Display"),
        MARKERS("markers", "Markers"),
        INTEGRATIONS("integrations", "Integrations"),
        INTERFACE("interface", "Interface", "Info Panel"),
        POSITION("position", "Position"),
        COLOURS("colours", "Colours"),
        SERVER("server", "Server", "Server Limits");

        final String id;
        final String uiLabel;
        final String label;

        Tab(String id, String label) { this(id, label, label); }

        Tab(String id, String uiLabel, String label) {
            this.id = id;
            this.uiLabel = uiLabel;
            this.label = label;
        }

        static Tab find(String id) {
            for (Tab tab : values()) if (tab.id.equals(id)) return tab;
            return null;
        }
    }

    /**
     * One settings-page row: what it edits ({@code field}, null for headers and server limits), the
     * entries it offers, and the settings that must be on for it to be editable ({@code requires}).
     * Its permission node comes from SettingPermissions (by id).
     */
    private record Row(String id, String label, Kind kind, Tab tab, DynamicMinimapSetting.Field field,
                       Options options, String description, DynamicMinimapSetting.Field[] requires) { }
}

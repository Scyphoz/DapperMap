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
    /** Only colours consumed by the current live HUD renderer are user-facing. */
    private static final List<Key> VISIBLE_COLOUR_KEYS = List.of(
            Key.EDGE, Key.TICKS, Key.PANEL,
            Key.CARDINAL_TEXT, Key.BRAND_TEXT, Key.INFO_HEADERS,
            Key.COORDS_TEXT, Key.COMPASS_TEXT, Key.CLOCK_TEXT, Key.BIOME_TEXT,
            Key.SELF, Key.PLAYERS, Key.DEATH, Key.HOSTILE_ACTIVE, Key.HOSTILE, Key.NEUTRAL, Key.FRIENDLY
    );

    private static final Row[] ROWS;
    /** Ids of the player-setting dropdown rows (server-limit rows are handled separately). */
    private static final java.util.Set<String> DROPDOWN_IDS;
    private final PlayerRef playerRef;
    private final DapperMapSessionManager sessions;
    private final DapperMapConfig config;
    private final boolean adminMode;
    private Tab activeTab = Tab.DISPLAY;
    private Key selectedColour = Key.EDGE;
    private int previewRevision = 0;
    private volatile int previewRequest = 0;
    private volatile boolean closed = false;
    private final java.util.concurrent.atomic.AtomicBoolean previewQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private String lastPreviewPath;
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
    /** Admin page only: unsaved server limits {speed, resolution, zoom, creatures}; saved on Apply. */
    private int[] draftLimits;
    /**
     * Retained for source compatibility. The authoritative definition now lives in
     * {@link dev.scyphoz.dappermap.permissions.DapperMapPermissions#ADMIN}, a node kept
     * deliberately outside the player command tree so player-command wildcard grants
     * can never reach it.
     */
    public static final PermissionQuery ADMIN_PERMISSION = DapperMapPermissions.ADMIN;

    public DapperMapSettingsPage(
            PlayerRef playerRef,
            DapperMapSessionManager sessions,
            DapperMapConfig config
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

    public DapperMapSettingsPage(PlayerRef playerRef, DapperMapSessionManager sessions, DapperMapConfig config, boolean adminMode) {
        super(playerRef, CustomPageLifetime.CanDismiss, DapperMapSettingsPage.Ev.CODEC);
        this.playerRef = playerRef;
        this.sessions = sessions;
        this.config = config;
        this.adminMode = adminMode;
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
        if (this.adminMode && this.draftLimits == null) this.draftLimits = this.savedLimits();
        if (this.previewSnapshot == null) this.previewSnapshot = this.sessions.capturePreview(this.playerRef.getUuid());
        DynamicMinimapSetting settings = this.draftSettings;
        this.betterMapCaveAvailable = BetterMapIntegration.isCaveModeEnabled(
                store.getComponent(ref, Player.getComponentType()));

        builder.append("Pages/DapperMapSettings.ui");
        builder.set("#PlayerTopAccent.Visible", !this.adminMode);
        builder.set("#AdminTopAccent.Visible", this.adminMode);
        // The Server Limits tab exists only on the admin page.
        builder.set("#DapperMapTabServerFrame.Visible", this.adminMode);
        builder.set("#DapperMapAdminButtonFrame.Visible",
                !this.adminMode && this.playerRef.hasPermission(ADMIN_PERMISSION));
        if (this.adminMode) {
            builder.set("#DapperMapTagline.Text", "Server defaults for new DapperMap players");
        }

        this.renderList(builder, events, settings);
        this.bindColourEditor(builder, events, settings);
        this.bindPositionEditor(builder, events, settings);
        this.updatePreview(builder, settings);
        this.updateTabVisibility(builder);
        this.updateEditorAvailability(builder, settings);
        this.bindNavigation(builder, events);
        this.bindFooterButtons(builder, events);
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
        builder.set("#PageSectionTitle.Text", this.activeTab.label.toUpperCase(java.util.Locale.ROOT));
        String sectionDescription = switch (this.activeTab) {
            case DISPLAY -> "Customize how DapperMap appears in-game.";
            case MARKERS -> "Choose the navigation markers shown on your minimap.";
            case INTEGRATIONS -> "Connect Hytale and BetterMap map features to your minimap.";
            case INTERFACE -> "Tune the information, compass and HUD presentation.";
            case POSITION -> "Place the minimap on-screen and fine-tune its position.";
            case COLOURS -> "Tune the DapperMap palette and preview every change live.";
            case SERVER -> "Cap the most expensive settings for every player. Saved player choices are kept.";
        };
        builder.set("#PageSectionDescription.Text", sectionDescription);
    }

    private void updateEditorAvailability(UICommandBuilder builder, DynamicMinimapSetting settings) {
        boolean colours = this.activeTab == Tab.COLOURS;
        boolean position = this.activeTab == Tab.POSITION;
        boolean canEditColours = this.hasSettingPermission("colours");
        boolean canEditPosition = this.hasSettingPermission("position");
        builder.set("#DapperMapColourEditor.Visible", colours && settings.enabled && canEditColours);
        builder.set("#ColourDisabledNotice.Visible", colours && (!settings.enabled || !canEditColours));
        builder.set("#ColourDisabledNotice.Text", canEditColours
                ? "ENABLE THE MINIMAP TO EDIT COLOURS" : "PERMISSION REQUIRED TO EDIT COLOURS");
        builder.set("#DapperMapPositionEditor.Visible", position && settings.enabled && canEditPosition);
        builder.set("#PositionDisabledNotice.Visible", position && (!settings.enabled || !canEditPosition));
        builder.set("#PositionDisabledNotice.Text", canEditPosition
                ? "ENABLE THE MINIMAP TO EDIT POSITION" : "PERMISSION REQUIRED TO EDIT POSITION");
    }

    private void bindColourEditor(UICommandBuilder builder, UIEventBuilder events, DynamicMinimapSetting settings) {
        builder.set("#ColourTarget.Entries", VISIBLE_COLOUR_KEYS.stream()
                .map(key -> new DropdownEntryInfo(LocalizableString.fromString(key.group + " / " + key.label), key.id))
                .toList());
        builder.set("#ColourTarget.Value", this.selectedColour.id);
        builder.set("#ColourPicker.Color", settings.colours.hex(this.selectedColour));
        builder.set("#ColourDescription.Text", this.selectedColour.description);
        builder.set("#ColourHex.Text", settings.colours.hex(this.selectedColour));
        boolean allowed = this.hasSettingPermission("colours");
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

    private void refreshColourEditor(UICommandBuilder builder, DynamicMinimapSetting settings, boolean syncPicker) {
        builder.set("#ColourTarget.Value", this.selectedColour.id);
        builder.set("#ColourDescription.Text", this.selectedColour.description);
        builder.set("#ColourHex.Text", settings.colours.hex(this.selectedColour));
        if (syncPicker) builder.set("#ColourPicker.Color", settings.colours.hex(this.selectedColour));
    }

    private void bindPositionEditor(UICommandBuilder builder, UIEventBuilder events,
                                    DynamicMinimapSetting settings) {
        boolean allowed = this.hasSettingPermission("position");
        String[] labels = {"Horizontal Position", "Vertical Position"};
        String[] actions = {"position_slider_x", "position_slider_y"};
        for (int index = 0; index < labels.length; index++) {
            String row = "#PositionFineTuneList[" + index + "]";
            builder.append("#PositionFineTuneList", "Pages/DapperMapPositionSliderRow.ui");
            builder.set(row + " #RowLabel.Text", labels[index]);
            builder.set(row + " #RowDescription.Text", index == 0
                    ? "Move the minimap left or right."
                    : "Move the minimap up or down.");
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
            builder.set(row + " #ValueLabel.Text", values[index] + "%");
            if (syncSliders) builder.set(row + " #ValueSlider.Value", values[index]);
        }
        builder.set("#PositionSummary.Text",
                positionName(settings.hudPositionX, settings.hudPositionY)
                        + "   •   " + settings.hudPositionX + "% / " + settings.hudPositionY + "%");
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
                        Message.raw(positionButtonName(x, y))
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
        builder.set("#PositionFineTuneList[" + index + "] #ValueLabel.Text", value + "%");
        builder.set("#PositionSummary.Text",
                positionName(settings.hudPositionX, settings.hudPositionY)
                        + "   •   " + settings.hudPositionX + "% / " + settings.hudPositionY + "%");
        if (nearestPositionStop(previousX) != nearestPositionStop(settings.hudPositionX)
                || nearestPositionStop(previousY) != nearestPositionStop(settings.hudPositionY))
            this.updatePositionGrid(builder, settings);
    }

    private void updatePreview(UICommandBuilder ui, DynamicMinimapSetting settings) {
        int request = ++this.previewRequest;
        if (!settings.enabled) {
            ui.set("#PreviewHudImage.Visible", false);
            ui.set("#PreviewDisabled.Text", "MINIMAP DISABLED");
            ui.set("#PreviewDisabled.Visible", true);
            return;
        }
        if (this.previewSnapshot == null) this.previewSnapshot = this.sessions.capturePreview(this.playerRef.getUuid());
        if (this.previewSnapshot == null) {
            ui.set("#PreviewHudImage.Visible", false);
            ui.set("#PreviewDisabled.Text", "LIVE HUD PREVIEW UNAVAILABLE");
            ui.set("#PreviewDisabled.Visible", true);
            return;
        }
        if (this.previewRevision == 0) {
            ui.set("#PreviewHudImage.Visible", false);
            ui.set("#PreviewDisabled.Text", "PREPARING HUD PREVIEW");
            ui.set("#PreviewDisabled.Visible", true);
        }
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
        // In Icons mode the live creature icons are drawn from the HUD snapshot itself, so the
        // preview's sample threat-colour dots (shown only for showEntities) are skipped.
        if (draft.creatureIcons) draft.showEntities = false;
        // Preview what will actually be shown: server limits apply here too (and keep this render cheap).
        this.config.applyLimits(draft);
        DynamicMinimapRenderer.LiveSnapshot snapshot = this.previewSnapshot;
        boolean position = this.activeTab == Tab.POSITION;
        String slug = dev.scyphoz.dappermap.util.DapperMapUtil.playerSlug(this.playerRef.getUuid());
        String path = previewSlotPath(slug, 0); // Final slot chosen on delivery (see showPreview).
        if (!DynamicMinimapSession.renderAsync(() -> {
            if (this.closed) { this.previewQueued.set(false); return; }
            try {
                byte[] png = position
                        ? DynamicMinimapRenderer.renderPositionPreview(draft, snapshot)
                        : DynamicMinimapRenderer.renderSettingsPreview(draft, snapshot);
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
                        this.sendUpdate(update, new UIEventBuilder(), false);
                    }
                });
            } catch (RuntimeException failure) {
                this.previewQueued.set(false);
                if (!DynamicMinimapSession.isStoppedWorldException(failure)) throw failure;
            }
        })) this.previewQueued.set(false);
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
        java.util.UUID uuid = this.playerRef.getUuid();
        for (int slot = 0; slot < PREVIEW_SLOTS; slot++) {
            String candidate = previewSlotPath(slug, slot);
            if (DynamicAssetPublisher.isDelivered(uuid, candidate, prepared.hash())) return candidate;
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
                    Message.raw(tab.label.toUpperCase(java.util.Locale.ROOT)).color(selected ? VIOLET
                            : this.canOpenTab(tab) ? MUTED : new Color(0x555B6D)));
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

        boolean canReset = this.hasSettingPermission("reset");
        builder.set(
                "#DapperMapResetButtonLabel.TextSpans",
                Message.raw(this.adminMode ? "RESET TO MOD DEFAULTS" : "RESET TO DEFAULTS")
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
                Message.raw("APPLY").color(this.adminMode ? GREEN : new Color(0xF0EDFF)));
        builder.set("#DapperMapApplyButtonSlot[0].TextSpans", Message.raw(""));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DapperMapApplyButtonSlot[0]",
                EventData.of("Action", "apply_settings"), false);

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
            case COLOURS -> this.hasSettingPermission("colours");
            case SERVER -> this.adminMode; // Server limits: admin page only.
            case POSITION -> this.hasSettingPermission("position");
            default -> true;
        };
    }

    /** Maps UI-only row identifiers onto the canonical command permission nodes. */
    private static String permissionForSetting(String id) {
        return switch (id) {
            case "enabled" -> "toggle";
            case "interface_scale" -> "scale";
            case "texture_resolution" -> "resolution";
            case "info_panel" -> "info";
            case "panel_position", "panel_alignment" -> "panel";
            case "players", "entities", "death", "creature_limit", "creature_style", "creature_filter" -> "markers";
            case "vrange" -> "height-range";
            case "clock_format" -> "clock";
            case "worldmap", "bettermap", "bettermap_markers", "bettermap_caves" -> "integration";
            case "move_minimap" -> "position";
            default -> id;
        };
    }

    private static String permissionForAction(String action) {
        if (action.startsWith("position_")) return "position";
        if (action.startsWith("colour_")) return "colours";
        if (action.startsWith("toggle_")) {
            return permissionForSetting(action.substring("toggle_".length()));
        }
        if (action.startsWith("dropdown_")) {
            return permissionForSetting(action.substring("dropdown_".length()));
        }
        return null;
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
            if (tabFor(row.id) != this.activeTab) continue;
            boolean limitRow = isLimitRow(row.id);
            if (limitRow && !this.adminMode) continue; // Server limits are admin-page only.
            int index = renderedIndex++;

            if (limitRow && row.kind == Kind.DROPDOWN) {
                // Independent of the defaults draft (stays editable when the default minimap is off).
                this.limitRow(builder, events, index, row);
            } else if (row.kind == Kind.HEADER) {
                builder.append("#DapperMapSettingsList", "Pages/DapperMapSectionRow.ui");
                builder.set("#DapperMapSettingsList[" + index + "] #SectionLabel.Text", row.label);
            } else if (row.kind == Kind.TOGGLE) {
                if (!this.hasSettingPermission(permissionForSetting(row.id))
                        || isControlDisabled(row.id, settings)) {
                    this.disabledRow(builder, index, row, settings);
                }
                else this.toggleRow(builder, events, index, row, settings);
            } else if (row.kind == Kind.DROPDOWN) {
                if (!this.hasSettingPermission(permissionForSetting(row.id))
                        || isControlDisabled(row.id, settings)) {
                    this.disabledRow(builder, index, row, settings);
                }
                else if (isBooleanToggle(row.id)) this.toggleRow(builder, events, index, row, settings);
                else this.dropdownRow(builder, events, index, row, settings);
            }
        }
    }

    private void disabledRow(UICommandBuilder builder, int index, Row row,
                             DynamicMinimapSetting settings) {
        String selector = "#DapperMapSettingsList[" + index + "]";
        builder.append("#DapperMapSettingsList", "Pages/DapperMapDisabledDropdownRow.ui");
        builder.set(selector + " #RowLabel.Text", row.label);
        builder.set(selector + " #RowDescription.Text", description(row.id));
        builder.set(selector + " #ValueLabel.Text", "Disabled");
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
            builder.set(selector + " #ValueLabel.Text", "Disabled");
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
            if (!this.hasSettingPermission("reset")) return;
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

        String requiredPermission = permissionForAction(action);
        if (requiredPermission != null && !this.hasSettingPermission(requiredPermission)) return;

        // Set for toggle/dropdown changes so the reply can update just that row when possible.
        String rowId = null;
        boolean[] disabledBefore = null;
        List<String> entriesBefore = null;
        // Position before a slider move: only the 3x3 grid's selection may need re-sending.
        int previousX = settings.hudPositionX, previousY = settings.hudPositionY;

        if (!settings.enabled
                && !"toggle_enabled".equals(action)
                && (action.startsWith("position_") || action.startsWith("colour_")
                || action.startsWith("toggle_") || action.startsWith("dropdown_"))) {
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
            if (key == null || !VISIBLE_COLOUR_KEYS.contains(key)) return;
            this.selectedColour = key;
            UICommandBuilder colourUi = new UICommandBuilder();
            this.refreshColourEditor(colourUi, settings, true);
            this.sendUpdate(colourUi, new UIEventBuilder(), false);
            return;
        } else if ("colour_value".equals(action)) {
            if (event.colour == null) return;
            int rgb = (Byte.toUnsignedInt(event.colour.red) << 16)
                    | (Byte.toUnsignedInt(event.colour.green) << 8)
                    | Byte.toUnsignedInt(event.colour.blue);
            if (settings.colours.rgb(this.selectedColour) == rgb) return;
            settings.colours = settings.colours.with(this.selectedColour, rgb);
        } else if ("colour_reset".equals(action)) {
            settings.colours = settings.colours.reset(this.selectedColour);
        } else if ("colour_reset_all".equals(action)) {
            settings.colours = new DynamicMinimapColours();
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

        if (action.startsWith("colour_")) {
            this.refreshColourEditor(builder, settings, !"colour_value".equals(action));
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

        this.sendUpdate(builder, events, false);
    }

    /** Disabled state of every row on the current tab, matching renderList's rules. */
    private boolean[] disabledRows(DynamicMinimapSetting settings) {
        boolean[] out = new boolean[ROWS.length];
        for (int i = 0; i < ROWS.length; i++) {
            Row row = ROWS[i];
            if (row.kind == Kind.HEADER || tabFor(row.id) != this.activeTab || isLimitRow(row.id)) continue;
            out[i] = !this.hasSettingPermission(permissionForSetting(row.id)) || isControlDisabled(row.id, settings);
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
            if (tabFor(row.id) != this.activeTab) continue;
            if (isLimitRow(row.id) && !this.adminMode) continue;
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
        if (!isIntegrationToggle(id)) builder.set(selector + " #StateLabel.Text", toggleStateLabel(id, settings));
    }

    private void applyToggle(DynamicMinimapSetting settings, String id) {
        if (isControlDisabled(id, settings)) return;
        switch (id) {
            case "enabled" -> settings.enabled = !settings.enabled;
            case "players" -> settings.showOtherPlayers = !settings.showOtherPlayers;
            case "entities" -> settings.showEntities = !settings.showEntities;
            case "death" -> settings.showDeathMarker = !settings.showDeathMarker;
            case "worldmap" -> settings.showWorldMapMarkers = !settings.showWorldMapMarkers;
            case "height" -> settings.limitMobHeight = !settings.limitMobHeight;
            case "info_panel" -> settings.infoPanelEnabled = !settings.infoPanelEnabled;
            case "clock" -> settings.showClock = !settings.showClock;
            case "coordinates" -> settings.showCoordinates = !settings.showCoordinates;
            case "compass" -> settings.showCompass = !settings.showCompass;
            case "biome" -> settings.showBiome = !settings.showBiome;
            case "bettermap" -> settings.betterMapEnabled = !settings.betterMapEnabled;
            case "bettermap_markers" -> settings.betterMapMarkersEnabled = !settings.betterMapMarkersEnabled;
            case "bettermap_caves" -> settings.betterMapCaveModeEnabled = !settings.betterMapCaveModeEnabled;
            case "shape" -> settings.shape = settings.shape == 1 ? 0 : 1;
            case "rotation" -> settings.rotationMode = settings.rotationMode == 0 ? 1 : 0;
            case "clock_format" -> settings.clock24Hour = !settings.clock24Hour;
        }
    }


    private static boolean isIntegrationToggle(String id) {
        return "worldmap".equals(id) || "bettermap".equals(id)
                || id.startsWith("bettermap_");
    }

    private static String toggleStateLabel(String id, DynamicMinimapSetting settings) {
        return switch (id) {
            case "shape" -> settings.shape == 1 ? "CIRCLE" : "SQUARE";
            case "rotation" -> settings.rotationMode == 1 ? "ENABLED" : "DISABLED";
            case "clock_format" -> settings.clock24Hour ? "24 HOUR" : "12 HOUR";
            default -> toggleValue(id, settings) ? "ENABLED" : "DISABLED";
        };
    }

    private static boolean isBooleanToggle(String id) {
        return switch (id) {
            case "players", "entities", "death", "worldmap", "height", "info_panel",
                 "clock", "coordinates", "compass", "biome", "bettermap",
                 "bettermap_markers", "bettermap_caves",
                 "shape", "rotation", "clock_format" -> true;
            default -> false;
        };
    }

    private static boolean toggleValue(String id, DynamicMinimapSetting settings) {
        return switch (id) {
            case "enabled" -> settings.enabled;
            case "info_panel" -> settings.infoPanelEnabled;
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
            case "bettermap_markers" -> settings.betterMapMarkersEnabled;
            case "bettermap_caves" -> settings.betterMapCaveModeEnabled;
            case "shape" -> settings.shape == 1;
            case "rotation" -> settings.rotationMode == 1;
            case "clock_format" -> settings.clock24Hour;
            default -> false;
        };
    }

    private static String description(String id) {
        return switch (id) {
            case "enabled" -> "Show or hide your minimap.";
            case "info_panel" -> "Show or hide the Info Panel while keeping the minimap visible.";
            case "colours" -> "Choose colours for your minimap and markers.";
            case "zoom" -> "Choose how far around you the map shows.";
            case "size" -> "Change the size of the map on your screen.";
            case "interface_scale" -> "Resize the map and its information together.";
            case "shape" -> "Choose a round or square map.";
            case "rotation" -> "Enable or disable player-facing map rotation.";
            case "speed" -> "Choose how often the minimap refreshes.";
            case "move_minimap" -> "Choose where the minimap sits on your screen.";
            case "panel_position" -> "Choose which side shows the map information.";
            case "panel_alignment" -> "Align the information panel text left, centre or right.";
            case "players" -> "Show nearby players on your minimap.";
            case "entities" -> "Show nearby creatures on your minimap.";
            case "creature_limit" -> "Show up to 16, 32 or 64 nearby creatures, prioritising the nearest.";
            case "creature_style" -> "Show creatures as face icons or as threat-coloured dots.";
            case "creature_filter" -> "Show all creatures, or only hostile and attacking ones.";
            case "death" -> "Show Hytale's death markers (BetterMap's death settings apply).";
            case "worldmap" -> "Show Hytale personal, shared, POI, spawn and respawn world-map markers.";
            case "height" -> "Hide creatures far above or below you.";
            case "vrange" -> "Set how far above and below to show creatures.";
            case "clock" -> "Show the current time in your Hytale world.";
            case "coordinates" -> "Show your current world coordinates.";
            case "clock_format" -> "Choose a 12-hour or 24-hour clock.";
            case "compass" -> "Show the direction you are facing.";
            case "biome" -> "Show the name of the biome you are in.";
            case "bettermap" -> BetterMapIntegration.isAvailable()
                    ? "Enable BetterMap waypoints and cave terrain. Surface maps use shared Hytale images."
                    : "BetterMap is not installed or could not be detected.";
            case "bettermap_markers" -> "Show waypoints with BetterMap icons. When off, they appear with Hytale map markers.";
            case "bettermap_caves" -> "Show BetterMap's cave terrain while underground.";
            case "texture_resolution" -> "Choose the resolution used to render minimap textures.";
            case "limit_speed" -> "Fastest update speed any player's minimap runs at.";
            case "limit_resolution" -> "Highest texture resolution any player's minimap renders at.";
            case "limit_zoom" -> "Largest zoom radius any player's minimap can show.";
            case "limit_creatures" -> "Most creature markers any player's minimap shows.";
            default -> "";
        };
    }

    /** Keep descriptions within the text column at common UI scales. */
    private boolean isDropdownDisabled(String id, DynamicMinimapSetting settings) {
        return !settings.enabled
                || (isInfoPanelSetting(id) && !"info_panel".equals(id) && !settings.infoPanelEnabled)
                || ("bettermap".equals(id) && !BetterMapIntegration.isAvailable())
                || (id.startsWith("bettermap_") && (!settings.betterMapEnabled
                || !BetterMapIntegration.isAvailable()))
                || ("bettermap_caves".equals(id) && !this.betterMapCaveAvailable)
                || (("height".equals(id) || "vrange".equals(id) || "creature_limit".equals(id)
                || "creature_style".equals(id) || "creature_filter".equals(id)) && !settings.showEntities)
                || ("vrange".equals(id) && !settings.limitMobHeight)
                || ("clock_format".equals(id) && !settings.showClock);
    }

    private static boolean isInfoPanelSetting(String id) {
        return switch (id) {
            case "panel_position", "panel_alignment", "coordinates", "clock",
                 "clock_format", "compass", "biome" -> true;
            default -> false;
        };
    }

    private boolean isControlDisabled(String id, DynamicMinimapSetting settings) {
        return !"enabled".equals(id) && isDropdownDisabled(id, settings);
    }

    private void applyDropdown(DynamicMinimapSetting settings, String id, String value) {
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
            case "panel_alignment" -> settings.infoPanelTextAlignment = Integer.parseInt(value);
            case "shape" -> settings.shape = Integer.parseInt(value);
            case "rotation" -> settings.rotationMode = Integer.parseInt(value);
            case "vrange" -> settings.mobVerticalRange = Integer.parseInt(value);
            case "creature_limit" -> settings.creatureMarkerLimit = Integer.parseInt(value);
            case "creature_style" -> settings.creatureIcons = "icons".equals(value);
            case "creature_filter" -> settings.hostileCreaturesOnly = "hostile".equals(value);
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
            case "bettermap_markers" -> settings.betterMapMarkersEnabled = Boolean.parseBoolean(value);
            case "bettermap_caves" -> settings.betterMapCaveModeEnabled = Boolean.parseBoolean(value);
        }
        settings.normalize();
    }

    private List<DropdownEntryInfo> dropdownEntries(String id, DynamicMinimapSetting settings) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        switch (id) {
            case "zoom" -> {
                addNumericEntries(entries, settings.zoomBlocks, 16, 256, 16, " blocks");
                this.markOverLimit(entries, id);
            }
            case "size" -> addNumericEntries(entries, settings.sizePixels, 120, 360, 20, " px");
            case "creature_limit" -> {
                for (int limit : new int[]{16, 32, 64}) entries.add(entry(Integer.toString(limit), Integer.toString(limit)));
                this.markOverLimit(entries, id);
            }
            case "creature_style" -> {
                entries.add(entry("Icons", "icons"));
                entries.add(entry("Dots", "dots"));
            }
            case "creature_filter" -> {
                entries.add(entry("All", "all"));
                entries.add(entry("Hostile Only", "hostile"));
            }
            case "vrange" -> {
                TreeSet<Integer> values = new TreeSet<>(List.of(4));
                for (int value = 8; value <= 256; value += 8) values.add(value);
                values.add(settings.mobVerticalRange);
                for (int value : values) entries.add(entry(value + " blocks", Integer.toString(value)));
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
                this.markOverLimit(entries, id);
            }
            case "speed" -> {
                for (int value = 0; value <= 3; value++) entries.add(entry(displaySpeed(value), Integer.toString(value)));
                this.markOverLimit(entries, id);
            }
            case "panel_position" -> {
                entries.add(entry("Right", Integer.toString(DynamicMinimapSetting.INFO_RIGHT)));
                entries.add(entry("Left", Integer.toString(DynamicMinimapSetting.INFO_LEFT)));
                entries.add(entry("Above", Integer.toString(DynamicMinimapSetting.INFO_ABOVE)));
                entries.add(entry("Below", Integer.toString(DynamicMinimapSetting.INFO_BELOW)));
            }
            case "panel_alignment" -> {
                entries.add(entry("Left", Integer.toString(DynamicMinimapSetting.INFO_ALIGN_LEFT)));
                entries.add(entry("Centre", Integer.toString(DynamicMinimapSetting.INFO_ALIGN_CENTER)));
                entries.add(entry("Right", Integer.toString(DynamicMinimapSetting.INFO_ALIGN_RIGHT)));
            }
            case "shape" -> {
                entries.add(entry("Circle", "1"));
                entries.add(entry("Square", "0"));
            }
            case "rotation" -> {
                entries.add(entry("Disabled", "0"));
                entries.add(entry("Enabled", "1"));
            }
            case "clock_format" -> {
                entries.add(entry("24-hour", "true"));
                entries.add(entry("12-hour", "false"));
            }
            case "players", "entities", "death", "worldmap", "height", "coordinates", "clock", "compass", "biome", "bettermap", "bettermap_markers", "bettermap_caves" -> {
                entries.add(entry("Enabled", "true"));
                entries.add(entry("Disabled", "false"));
            }
            default -> { }
        }
        return entries;
    }

    // ---- Server limits (3.1.0) ---------------------------------------------------------

    /** Rows shown only on the admin page that edit dappermap_minimap.json limits. */
    private static boolean isLimitRow(String id) {
        return id.startsWith("limit_") || "section_limits".equals(id);
    }

    /** Current saved limits as {speed, resolution, zoom, creatures}. */
    private int[] savedLimits() {
        return new int[]{this.config.getFastestUpdateSpeed(), this.config.getMaxTextureResolution(),
                this.config.getMaxZoomRadius(), this.config.getMaxCreatureLimit()};
    }

    /** Persists edited limits and re-clamps live minimaps; untouched limits cause no file write. */
    private void saveLimitsIfChanged() {
        if (this.draftLimits == null || java.util.Arrays.equals(this.draftLimits, this.savedLimits())) return;
        this.config.setLimits(this.draftLimits[0], this.draftLimits[1], this.draftLimits[2], this.draftLimits[3]);
        DapperMap.saveConfig();
        this.sessions.refreshLiveSettings(); // Players' saved settings are not changed.
    }

    private static int limitSlot(String id) {
        return switch (id) {
            case "limit_speed" -> 0;
            case "limit_resolution" -> 1;
            case "limit_zoom" -> 2;
            case "limit_creatures" -> 3;
            default -> -1;
        };
    }

    /** Admin dropdown for one server limit; the "no limit" value is labelled as such. */
    private void limitRow(UICommandBuilder builder, UIEventBuilder events, int index, Row row) {
        String selector = "#DapperMapSettingsList[" + index + "]";
        builder.append("#DapperMapSettingsList", "Pages/DapperMapDropdownRow.ui");
        builder.set(selector + " #RowLabel.Text", row.label);
        builder.set(selector + " #RowDescription.Text", description(row.id));
        String dropdownSelector = selector + " #ValueDropdown";
        builder.set(dropdownSelector + ".Entries", this.limitEntries(row.id));
        builder.set(dropdownSelector + ".Value", Integer.toString(this.draftLimits[limitSlot(row.id)]));
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, dropdownSelector,
                EventData.of("Action", "dropdown_" + row.id).append("@Value", dropdownSelector + ".Value"), false);
    }

    private List<DropdownEntryInfo> limitEntries(String id) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        int current = this.draftLimits[limitSlot(id)];
        switch (id) {
            case "limit_speed" -> {
                for (int value = 0; value <= 3; value++)
                    entries.add(entry(displaySpeed(value) + (value == 0 ? " (no limit)" : ""), Integer.toString(value)));
            }
            case "limit_resolution" -> {
                TreeSet<Integer> values = new TreeSet<>(List.of(256, 320, 384, 448, 512, 1024));
                values.add(current);
                for (int value : values)
                    entries.add(entry(value + " px" + (value == 1024 ? " (no limit)" : ""), Integer.toString(value)));
            }
            case "limit_zoom" -> {
                TreeSet<Integer> values = new TreeSet<>();
                for (int value = 16; value <= 256; value += 16) values.add(value);
                values.add(current);
                for (int value : values)
                    entries.add(entry(value + " blocks" + (value == 256 ? " (no limit)" : ""), Integer.toString(value)));
            }
            case "limit_creatures" -> {
                for (int value : new int[]{16, 32, 64})
                    entries.add(entry(value + (value == 64 ? " (no limit)" : ""), Integer.toString(value)));
            }
            default -> { }
        }
        return entries;
    }

    /** Validates an offered value and stores it in the page draft; false when nothing changed. */
    private boolean applyLimitDropdown(String id, String value) {
        int slot = limitSlot(id);
        if (slot < 0 || value == null
                || this.limitEntries(id).stream().noneMatch(entry -> entry.value().equals(value))) return false;
        int parsed = Integer.parseInt(value);
        if (this.draftLimits[slot] == parsed) return false;
        this.draftLimits[slot] = parsed;
        return true;
    }

    /** Player page: tags choices above a server limit. They stay selectable and are saved, but play at the limit. */
    private void markOverLimit(List<DropdownEntryInfo> entries, String id) {
        if (this.adminMode || !this.config.hasLimits()) return;
        for (int i = 0; i < entries.size(); i++) {
            DropdownEntryInfo entry = entries.get(i);
            int value = Integer.parseInt(entry.value());
            boolean over = switch (id) {
                case "speed" -> value < this.config.getFastestUpdateSpeed();
                case "texture_resolution" -> value > this.config.getMaxTextureResolution();
                case "zoom" -> value > this.config.getMaxZoomRadius();
                case "creature_limit" -> value > this.config.getMaxCreatureLimit();
                default -> false;
            };
            if (!over) continue;
            String label = switch (id) {
                case "speed" -> displaySpeed(value);
                case "texture_resolution" -> value + " px";
                case "zoom" -> value + " blocks";
                default -> Integer.toString(value);
            };
            entries.set(i, new DropdownEntryInfo(LocalizableString.fromString(label + " (server limit)"), entry.value(),
                    LocalizableString.fromString("This server limits this setting to " + this.limitText(id)
                            + ". Your choice is kept and used if the limit is lifted.")));
        }
    }

    /** Short text for the server limit on a row, e.g. "128 blocks". */
    private String limitText(String id) {
        return switch (id) {
            case "speed" -> displaySpeed(this.config.getFastestUpdateSpeed());
            case "texture_resolution" -> this.config.getMaxTextureResolution() + " px";
            case "zoom" -> this.config.getMaxZoomRadius() + " blocks";
            case "creature_limit" -> Integer.toString(this.config.getMaxCreatureLimit());
            default -> "";
        };
    }

    /** Row description, with a short server-limit note on the player page when a limit applies. */
    private String rowDescription(String id) {
        if (this.adminMode) return description(id);
        boolean limited = switch (id) {
            case "speed" -> this.config.getFastestUpdateSpeed() > 0;
            case "texture_resolution" -> this.config.getMaxTextureResolution() < 1024;
            case "zoom" -> this.config.getMaxZoomRadius() < 256;
            case "creature_limit" -> this.config.getMaxCreatureLimit() < 64;
            default -> false;
        };
        return limited ? description(id) + " Server limit: " + this.limitText(id) + "." : description(id);
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private static void addNumericEntries(List<DropdownEntryInfo> entries, int current,
                                          int min, int max, int step, String suffix) {
        TreeSet<Integer> values = new TreeSet<>();
        for (int value = min; value <= max; value += step) values.add(value);
        values.add(current);
        for (int value : values) entries.add(entry(value + suffix, Integer.toString(value)));
    }

    private static String dropdownValue(String id, DynamicMinimapSetting settings) {
        return switch (id) {
            case "zoom" -> Integer.toString(settings.zoomBlocks);
            case "size" -> Integer.toString(settings.sizePixels);
            case "interface_scale" -> Integer.toString(settings.interfaceScalePercent);
            case "texture_resolution" -> Integer.toString(settings.textureResolution);
            case "speed" -> Integer.toString(settings.updateSpeed);
            case "panel_position" -> Integer.toString(settings.infoPanelPosition);
            case "panel_alignment" -> Integer.toString(settings.infoPanelTextAlignment);
            case "shape" -> Integer.toString(settings.shape);
            case "rotation" -> Integer.toString(settings.rotationMode);
            case "vrange" -> Integer.toString(settings.mobVerticalRange);
            case "creature_limit" -> Integer.toString(settings.creatureMarkerLimit);
            case "creature_style" -> settings.creatureIcons ? "icons" : "dots";
            case "creature_filter" -> settings.hostileCreaturesOnly ? "hostile" : "all";
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
            case "bettermap_markers" -> Boolean.toString(settings.betterMapMarkersEnabled);
            case "bettermap_caves" -> Boolean.toString(settings.betterMapCaveModeEnabled);
            default -> throw new IllegalArgumentException("Unknown dropdown: " + id);
        };
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int nearestPositionStop(int value) {
        if (value < 25) return 0;
        if (value > 75) return 100;
        return 50;
    }

    private static String positionName(int x, int y) {
        String vertical = y < 25 ? "TOP" : (y > 75 ? "BOTTOM" : "MIDDLE");
        String horizontal = x < 25 ? "LEFT" : (x > 75 ? "RIGHT" : "CENTRE");
        if ("MIDDLE".equals(vertical) && "CENTRE".equals(horizontal)) return "CENTRE";
        return vertical + " " + horizontal;
    }

    private static String positionButtonName(int x, int y) {
        if (y < 25) return x < 25 ? "TOP LEFT" : (x > 75 ? "TOP RIGHT" : "TOP");
        if (y > 75) return x < 25 ? "BOTTOM LEFT" : (x > 75 ? "BOTTOM RIGHT" : "BOTTOM");
        return x < 25 ? "LEFT" : (x > 75 ? "RIGHT" : "CENTRE");
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
            case "players", "death", "entities", "height", "vrange", "creature_limit", "creature_style",
                 "creature_filter" -> Tab.MARKERS;
            case "section_4", "section_5", "worldmap", "bettermap", "bettermap_markers",
                 "bettermap_caves" -> Tab.INTEGRATIONS;
            case "info_panel", "panel_position", "panel_alignment", "section_3",
                 "coordinates", "clock", "clock_format", "compass", "biome" -> Tab.INTERFACE;
            case "section_limits", "limit_speed", "limit_resolution", "limit_zoom", "limit_creatures" -> Tab.SERVER;
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
                // Admin page only (Server Limits tab): server-wide limits that clamp every live minimap.
                new Row("section_limits", "PLAYER SETTING LIMITS", Kind.HEADER),
                new Row("limit_speed", "Fastest Update Speed", Kind.DROPDOWN),
                new Row("limit_resolution", "Max Texture Resolution", Kind.DROPDOWN),
                new Row("limit_zoom", "Max Zoom Radius", Kind.DROPDOWN),
                new Row("limit_creatures", "Max Creature Limit", Kind.DROPDOWN),
                new Row("info_panel", "Info Panel", Kind.TOGGLE),
                new Row("panel_position", "Info Panel Position", Kind.DROPDOWN),
                new Row("panel_alignment", "Panel Text Alignment", Kind.DROPDOWN),
                new Row("players", "Player Markers", Kind.DROPDOWN),
                new Row("death", "Death Markers", Kind.DROPDOWN),
                new Row("entities", "Creature Markers", Kind.DROPDOWN),
                new Row("creature_style", "Creature Marker Style", Kind.DROPDOWN),
                new Row("creature_filter", "Creatures Shown", Kind.DROPDOWN),
                new Row("creature_limit", "Creature Marker Limit", Kind.DROPDOWN),
                new Row("height", "Creature Height Detection", Kind.DROPDOWN),
                new Row("vrange", "Creature Height Range", Kind.DROPDOWN),
                new Row("section_3", "INFORMATION", Kind.HEADER),
                new Row("coordinates", "Coordinates", Kind.DROPDOWN),
                new Row("compass", "Bearing", Kind.DROPDOWN),
                new Row("clock", "World Time", Kind.DROPDOWN),
                new Row("clock_format", "Clock Format", Kind.DROPDOWN),
                new Row("biome", "Region", Kind.DROPDOWN),
                new Row("section_4", "HYTALE", Kind.HEADER),
                new Row("worldmap", "Hytale World Map Markers", Kind.DROPDOWN),
                new Row("section_5", "BETTERMAP", Kind.HEADER),
                new Row("bettermap", "BetterMap", Kind.DROPDOWN),
                new Row("bettermap_markers", "BetterMap Markers", Kind.DROPDOWN),
                new Row("bettermap_caves", "BetterMap Cave Mode", Kind.DROPDOWN)
        };
        java.util.Set<String> dropdowns = new java.util.HashSet<>();
        for (Row row : ROWS) if (row.kind == Kind.DROPDOWN && !isLimitRow(row.id)) dropdowns.add(row.id);
        DROPDOWN_IDS = java.util.Set.copyOf(dropdowns);
    }

    public static final class Ev {

        public static final BuilderCodec<Ev> CODEC;

        private String action = "";
        private String value = "";
        private String target = "";
        private int sliderValue = 0;
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
                    .append(new KeyedCodec<>("@SliderValue", Codec.INTEGER, true),
                            (ev, value) -> ev.sliderValue = value, ev -> ev.sliderValue).add()
                    .build();
        }
    }

    private enum Kind {
        TOGGLE,
        HEADER,
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

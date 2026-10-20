package com.minermarmot.client.screen;

import com.minermarmot.client.integration.VersionCheck;

import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.config.ClientConfig;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.mining.MiningConfig;
import com.minermarmot.client.features.mining.MiningFeature;
import com.minermarmot.client.screen.widget.ConfigSectionWidget;
import com.minermarmot.client.features.alerts.BlockAlertSettings;
import com.minermarmot.client.features.alerts.AlertIcon;
import com.minermarmot.client.features.alerts.BlockAlertTarget;
import com.minermarmot.client.features.alerts.SoundSelectionScreen;
import com.minermarmot.client.features.portal.PortalManager;
import com.minermarmot.client.features.portal.PortalPreset;
import com.minermarmot.client.features.autocraft.AutocraftPreset;
import com.minermarmot.client.features.skins.SkinsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

public class ConfigScreen extends ConfigMenuScreen {
    private static final int CUSTOM_BORDER_DISTANCE = -1;
    private final Screen parent;
    private final ClientConfig config = ConfigManager.current().copy();
    private boolean customBorderDistance = !MiningConfig.BORDER_DISTANCES.contains(config.mining.worldBorderDistance);
    private String category = "general";
    private String query = "";
    private boolean miningExpanded = true;
    private boolean blocksExpanded;
    private boolean alertsExpanded;
    private boolean tntExpanded = true;
    private boolean displayExpanded;
    private boolean portalsExpanded;
    private boolean autocrafterExpanded;
    private final Set<String> expandedCraftCategories = new HashSet<>();
    private final Set<String> searchCollapsedCraftCategories = new HashSet<>();
    private boolean saveFailed;
    private final Set<String> collapsedCategories = new HashSet<>(ConfigManager.preset(config.preset).blockAlerts.categories.keySet());
    private final Set<String> searchCollapsedCategories = new HashSet<>();
    private OptionList options;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("minermarmot.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int tabWidth = Math.min(90, (contentWidth() - 46) / 3);
        addTab("general", contentLeft(), tabWidth);
        addTab("features", contentLeft() + tabWidth + 6, tabWidth);
        addTab("skins", contentLeft() + 2 * (tabWidth + 6), tabWidth);
        EditBox search = new EditBox(font, contentLeft(), 58, contentWidth(), 20, text("search"));
        search.setHint(text("search"));
        if (category.equals("skins")) search.setTooltip(Tooltip.create(Component.translatable(
                "minermarmot.skins.hint", ConfigManager.preset(config.preset).skins.itemName)));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            searchCollapsedCategories.clear();
            searchCollapsedCraftCategories.clear();
            refreshOptions();
        });
        addRenderableWidget(search);
        options = addRenderableWidget(new OptionList(minecraft, contentWidth(),
                Math.max(24, height - (category.equals("general") ? 158 : 134)), 84));
        options.setX(contentLeft());
        refreshOptions();
        if (category.equals("general")) {
            int resetWidth = Math.min(300, contentWidth());
            addRenderableWidget(Button.builder(text("resetPreset"), button -> {
                config.resetPreset(ConfigManager.preset(config.preset));
                customBorderDistance = !MiningConfig.BORDER_DISTANCES.contains(config.mining.worldBorderDistance);
                applyChanges();
                refreshOptions();
            }).bounds((width - resetWidth) / 2, height - 66, resetWidth, 20).build());
        }
        int buttonWidth = Math.min(180, width - 40);
        addRenderableWidget(Button.builder(text("quit"), button -> onClose())
                .bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
    }


    @Override
    public void tick() {
        if (!VersionCheck.isCompatible()) minecraft.setScreenAndShow(VersionCheck.configScreen(parent));
    }

    private void addTab(String id, int x, int tabWidth) {
        Button tab = Button.builder(text(id), button -> {
            category = id;
            rebuildWidgets();
        }).bounds(x, 28, tabWidth, 20).build();
        tab.setTooltip(Tooltip.create(text(id)));
        tab.active = !category.equals(id);
        addRenderableWidget(tab);
    }

    private void refreshOptions() {
        if (options == null) {
            return;
        }
        options.clearOptions();
        var preset = ConfigManager.preset(config.preset);
        if (category.equals("skins")) {
            options.addSkins();
            return;
        }
        if (category.equals("general")) {
            options.addLabeledOption(text("preset"), CycleButton.builder(id -> Component.literal(ConfigManager.preset(id).name), config.preset)
                    .withValues(ConfigManager.presets().keySet())
                    .displayOnlyValue()
                    .create(0, 0, 140, 20, text("preset"), (button, id) -> {
                        config.applyPreset(id, ConfigManager.preset(id));
                        customBorderDistance = !MiningConfig.BORDER_DISTANCES.contains(config.mining.worldBorderDistance);
                        applyChanges();
                        collapsedCategories.clear();
                        collapsedCategories.addAll(ConfigManager.preset(id).blockAlerts.categories.keySet());
                        searchCollapsedCategories.clear();
                        expandedCraftCategories.clear();
                        searchCollapsedCraftCategories.clear();
                        refreshOptions();
                    }));
            return;
        }
        if (category.equals("features") && java.util.Arrays.stream(MiningFeature.values()).anyMatch(feature ->
                feature != MiningFeature.PLACEMENT_WITHOUT_DELAY && preset.allows(feature)
                && matches(Component.translatable(feature.translationKey()).getString()))) {
            options.addOption(new ConfigSectionWidget(0, 0, 100, text("mining").getString(), miningExpanded || !query.isBlank(), () -> {
                miningExpanded = !miningExpanded;
                refreshOptions();
            }));
            if (miningExpanded || !query.isBlank()) {
                for (MiningFeature feature : MiningFeature.values()) {
                    Component label = Component.translatable(feature.translationKey());
                    if (feature == MiningFeature.PLACEMENT_WITHOUT_DELAY || !preset.allows(feature) || !matches(label.getString())) {
                        continue;
                    }
                    if (feature == MiningFeature.WORLD_BORDER) {
                        List<Integer> distances = new ArrayList<>(MiningConfig.BORDER_DISTANCES);
                        distances.add(CUSTOM_BORDER_DISTANCE);
                        options.addLabeledOption(label, CycleButton.builder(distance -> distance == CUSTOM_BORDER_DISTANCE
                                        ? text("custom") : distance == 0
                                        ? CommonComponents.OPTION_OFF.copy().withStyle(style -> style.withColor(0xBBBBBB))
                                        : Component.translatable("minermarmot.config.blocks", distance),
                                        customBorderDistance ? CUSTOM_BORDER_DISTANCE : config.mining.worldBorderDistance)
                                .withValues(distances)
                                .displayOnlyValue()
                                .create(0, 0, 70, 20, label, (button, value) -> {
                                    customBorderDistance = value == CUSTOM_BORDER_DISTANCE;
                                    if (!customBorderDistance) {
                                        config.mining.worldBorderDistance = value;
                                        applyChanges();
                                    }
                                    double scroll = options.scrollAmount();
                                    refreshOptions();
                                    options.setScrollAmount(scroll);
                                }));
                        if (customBorderDistance) {
                            options.addNumber(text("customBorderDistance"), config.mining.worldBorderDistance,
                                    1, Integer.MAX_VALUE, value -> config.mining.worldBorderDistance = value);
                        }
                    } else {
                        options.addLabeledOption(label, toggleBuilder(config.mining.enabled.contains(feature))
                                .create(0, 0, 70, 20, label, (button, value) -> {
                                    if (value) {
                                        config.mining.enabled.add(feature);
                                    } else {
                                        config.mining.enabled.remove(feature);
                                    }
                                    applyChanges();
                                }));
                    }

                }
            }
        }
        Component placementLabel = Component.translatable(MiningFeature.PLACEMENT_WITHOUT_DELAY.translationKey());
        boolean showPlacement = preset.allows(MiningFeature.PLACEMENT_WITHOUT_DELAY)
                && (matches(text("tnt").getString()) || matches(placementLabel.getString()));
        boolean showModes = !preset.tnt.modes().isEmpty() && (matches(text("tnt").getString()) || matches(text("tntMode").getString())
                || preset.tnt.modes().stream().anyMatch(mode -> matches(Component.translatable(mode.translationKey()).getString())));
        if (showModes || showPlacement) {
            options.addOption(new ConfigSectionWidget(0, 0, 100, text("tnt").getString(), tntExpanded || !query.isBlank(), () -> {
                tntExpanded = !tntExpanded;
                refreshOptions();
            }));
            if (tntExpanded || !query.isBlank()) {
                if (showModes) {
                    var mode = CycleButton.builder(value -> Component.translatable(value.translationKey()), config.tntMode)
                            .withValues(preset.tnt.modes()).displayOnlyValue()
                            .create(0, 0, 140, 20, text("tntMode"), (button, value) -> {
                                config.tntMode = value;
                                applyChanges();
                            });
                    mode.setTooltip(Tooltip.create(Component.translatable("minermarmot.tnt.keyHint",
                            TntManager.toggleKeyName())));
                    options.addLabeledOption(text("tntMode"), mode);
                }
                if (showPlacement) {
                    var placement = toggleBuilder(config.mining.enabled.contains(MiningFeature.PLACEMENT_WITHOUT_DELAY))
                            .create(0, 0, 70, 20, placementLabel, (button, value) -> {
                                if (value) config.mining.enabled.add(MiningFeature.PLACEMENT_WITHOUT_DELAY);
                                else config.mining.enabled.remove(MiningFeature.PLACEMENT_WITHOUT_DELAY);
                                applyChanges();
                            });
                    placement.setTooltip(Tooltip.create(Component.translatable("minermarmot.tnt.placementWithoutDelayHint")));
                    options.addLabeledOption(placementLabel, placement);
                }
            }
        }
        var autocrafter = preset.autocrafter;
        boolean craftMatch = matches(craftText("title").getString());
        if (autocrafter.available && (craftMatch || matches(craftText("enabled").getString())
                || matches(craftText("delay").getString()) || autocrafter.categories.entrySet().stream().anyMatch(entry ->
                matches(entry.getKey()) || entry.getValue().stream().anyMatch(recipe -> matches(recipe.id) || matches(recipe.displayName))))) {
            options.addOption(new ConfigSectionWidget(0, 0, 100, craftText("title").getString(), autocrafterExpanded || !query.isBlank(), () -> {
                autocrafterExpanded = !autocrafterExpanded;
                refreshOptions();
            }));
            if (autocrafterExpanded || !query.isBlank()) {
                if (craftMatch || matches(craftText("enabled").getString())) {
                    options.addLabeledOption(craftText("enabled"), toggleBuilder(config.autocrafter.enabled)
                            .create(0, 0, 70, 20, craftText("enabled"), (button, value) -> {
                                config.autocrafter.enabled = value;
                                applyChanges();
                            }));
                }
                if (craftMatch || matches(craftText("delay").getString())) {
                    options.addNumber(craftText("delay"), config.autocrafter.delayMillis, 50, 600000,
                            value -> config.autocrafter.delayMillis = value);
                }
                autocrafter.categories.forEach((name, recipes) -> {
                    var found = recipes.stream().filter(recipe -> craftMatch || matches(name)
                            || matches(recipe.id) || matches(recipe.displayName)).toList();
                    if (found.isEmpty() && !craftMatch && !matches(name)) return;
                    boolean expanded = expandedCraftCategories.contains(name)
                            || !query.isBlank() && !searchCollapsedCraftCategories.contains(name);
                    options.addOption(new ConfigSectionWidget(0, 0, 100, name, expanded, () -> {
                        if (expanded) {
                            expandedCraftCategories.remove(name);
                            if (!query.isBlank()) searchCollapsedCraftCategories.add(name);
                        } else {
                            expandedCraftCategories.add(name);
                            searchCollapsedCraftCategories.remove(name);
                        }
                        refreshOptions();
                    }), 12);
                    if (expanded) found.forEach(options::addCraftRecipe);
                });
            }
        }
        boolean showFps = matches(text("display").getString()) || matches(text("displayFps").getString());
        boolean showPosition = matches(text("display").getString()) || matches(text("displayPosition").getString());
        boolean showObjectives = matches(text("display").getString()) || matches(text("displayObjectives").getString())
                || matches(text("objectivesSide").getString());
        if (showFps || showPosition || showObjectives) {
            options.addOption(new ConfigSectionWidget(0, 0, 100, text("display").getString(), displayExpanded || !query.isBlank(), () -> {
                displayExpanded = !displayExpanded;
                refreshOptions();
            }));
            if (displayExpanded || !query.isBlank()) {
                if (showFps) {
                    options.addLabeledOption(text("displayFps"), toggleBuilder(config.displayFps)
                            .create(0, 0, 70, 20, text("displayFps"), (button, value) -> {
                                config.displayFps = value;
                                applyChanges();
                            }));
                }
                if (showPosition) {
                    options.addLabeledOption(text("displayPosition"), toggleBuilder(config.displayPosition)
                            .create(0, 0, 70, 20, text("displayPosition"), (button, value) -> {
                                config.displayPosition = value;
                                applyChanges();
                            }));
                }
                if (showObjectives) {
                    options.addLabeledOption(text("displayObjectives"), toggleBuilder(config.displayObjectives)
                            .create(0, 0, 70, 20, text("displayObjectives"), (button, value) -> {
                                config.displayObjectives = value;
                                applyChanges();
                            }));
                    options.addLabeledOption(text("objectivesSide"), CycleButton.builder(ConfigScreen::text,
                                    config.objectivesOnRight ? "right" : "left").withValues("left", "right").displayOnlyValue()
                            .create(0, 0, 70, 20, text("objectivesSide"), (button, value) -> {
                                config.objectivesOnRight = value.equals("right");
                                applyChanges();
                            }));
                }
            }
        }
        List<String> playerAlerts = new ArrayList<>();
        if (preset.playerAlerts.inventoryFull && (matches(alertText("inventoryFull").getString()) || matches(text("alerts").getString())
                || matches(alertText("volume").getString()))) {
            playerAlerts.add("inventoryFull");
        }
        if (preset.playerAlerts.privateMessage && (matches(alertText("privateMessage").getString()) || matches(text("alerts").getString())
                || matches(alertText("volume").getString()))) {
            playerAlerts.add("privateMessage");
        }
        if (!playerAlerts.isEmpty()) {
            options.addOption(new ConfigSectionWidget(0, 0, 100, text("alerts").getString(), alertsExpanded || !query.isBlank(), () -> {
                alertsExpanded = !alertsExpanded;
                refreshOptions();
            }));
            if (alertsExpanded || !query.isBlank()) {
                options.addPlayerAlertHeader();
                playerAlerts.forEach(options::addPlayerAlert);
            }
        }
        if (preset.blockAlerts.available) {
            options.addOption(new ConfigSectionWidget(0, 0, 100, text("blockAlerts").getString(), blocksExpanded || !query.isBlank(), () -> {
                blocksExpanded = !blocksExpanded;
                refreshOptions();
            }));
            if (blocksExpanded || !query.isBlank()) {
                if (matches(alertText("vanillaOreScanRadius").getString())) {
                    options.addNumber(alertText("vanillaOreScanRadius"), config.alerts.vanillaOreScanRadius, 1, 128,
                            value -> config.alerts.vanillaOreScanRadius = value);
                }
                if (matches(alertText("otherBlockScanRadius").getString())) {
                    options.addNumber(alertText("otherBlockScanRadius"), config.alerts.otherBlockScanRadius, 1, 128,
                            value -> config.alerts.otherBlockScanRadius = value);
                }
                List<String> channels = new ArrayList<>();
                if (preset.blockAlerts.sounds && (matches(alertText("sounds").getString())
                        || matches(alertText("channel.sounds").getString()) || matches(alertText("soundDelay").getString())
                        || matches(alertText("volume").getString()))) {
                    channels.add("sounds");
                }
                if (preset.blockAlerts.waypoints && (matches(alertText("waypoints").getString())
                        || matches(alertText("channel.waypoints").getString()) || matches(alertText("waypointCount").getString())
                        || matches(alertText("waypointDistance").getString()) || matches(alertText("waypointSort").getString())
                        || matches(alertText("waypointSort.rarity").getString()))) {
                    channels.add("waypoints");
                }
                if (preset.blockAlerts.glow && (matches(alertText("glow").getString())
                        || config.alerts.glow && matches(alertText("glowHiddenEdges").getString()))) {
                    channels.add("glow");
                }
                if (!channels.isEmpty()) {
                    options.addBlockChannelHeader();
                    channels.forEach(options::addBlockChannel);
                }
                preset.blockAlerts.categories.forEach((name, category) -> {
                    List<BlockAlertTarget> found = category.blocks.stream().filter(target -> matches(name)
                            || matches(target.displayName) || matches(target.id)).toList();
                    if (found.isEmpty() && !matches(name)) {
                        return;
                    }
                    boolean expanded = !collapsedCategories.contains(name)
                            || !query.isBlank() && !searchCollapsedCategories.contains(name);
                    options.addOption(new ConfigSectionWidget(0, 0, 100, name, expanded, () -> {
                        if (expanded) {
                            collapsedCategories.add(name);
                            if (!query.isBlank()) {
                                searchCollapsedCategories.add(name);
                            }
                        } else {
                            collapsedCategories.remove(name);
                            searchCollapsedCategories.remove(name);
                        }
                        refreshOptions();
                    }), 12);
                    if (expanded && !found.isEmpty()) {
                        options.addBlockHeader();
                        found.forEach(options::addBlock);
                    }
                });
            }
        }
        var portals = preset.portals;
        boolean portalMatch = matches(portalText("title").getString());
        if (!portals.types.isEmpty() && (portalMatch || matches(portalText("waypointCount").getString())
                || matches(portalText("waypointDistance").getString()) || matches(portalText("reset").getString())
                || portals.types.stream().anyMatch(type -> matches(type.id) || matches(type.displayName)))) {
            options.addOption(new ConfigSectionWidget(0, 0, 100, portalText("title").getString(), portalsExpanded || !query.isBlank(), () -> {
                portalsExpanded = !portalsExpanded;
                refreshOptions();
            }));
            if (portalsExpanded || !query.isBlank()) {
                if (portalMatch || matches(portalText("waypointCount").getString())) {
                    options.addNumber(portalText("waypointCount"), config.portals.displayedWaypointCount, 0, 100,
                            value -> config.portals.displayedWaypointCount = value);
                }
                if (portalMatch || matches(portalText("waypointDistance").getString())) {
                    options.addNumber(portalText("waypointDistance"), config.portals.waypointDisplayDistance, 1, 4096,
                            value -> config.portals.waypointDisplayDistance = value);
                }
                portals.types.stream().filter(type -> portalMatch || matches(type.id) || matches(type.displayName))
                        .forEach(options::addPortal);
                if (portalMatch || matches(portalText("reset").getString())) {
                    options.addOption(Button.builder(portalText("reset"), button -> PortalManager.reset()).bounds(0, 0, 180, 20).build());
                }
            }
        }
    }

    private void applyChanges() {
        saveFailed = !ConfigManager.save(config);
    }

    private boolean matches(String value) {
        return value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static Component text(String key) {
        return Component.translatable("minermarmot.config." + key);
    }

    private static Component alertText(String key) {
        return Component.translatable("minermarmot.alerts." + key);
    }

    private static Component portalText(String key) {
        return Component.translatable("minermarmot.portal." + key);
    }

    private static Component craftText(String key) {
        return Component.translatable("minermarmot.autocraft." + key);
    }

    private static Component skinText(String key) {
        return Component.translatable("minermarmot.skins." + key);
    }

    private static CycleButton.Builder<Boolean> toggleBuilder(boolean value) {
        return CycleButton.booleanBuilder(
                CommonComponents.OPTION_ON.copy().withStyle(style -> style.withColor(0xFFFFFF)),
                CommonComponents.OPTION_OFF.copy().withStyle(style -> style.withColor(0xBBBBBB)), value).displayOnlyValue();
    }

    private static void renderWidget(AbstractWidget widget, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (widget instanceof CycleButton<?> button && button.getValue() instanceof Boolean value) {
            int alpha = Math.round(widget.getAlpha() * 255);
            graphics.fill(widget.getX() + 2, widget.getBottom() - 3, widget.getRight() - 2, widget.getBottom() - 1,
                    alpha << 24 | (value ? 0x4CA66A : 0x666666));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (saveFailed) {
            graphics.centeredText(font, text("saveFailed"), width / 2, height - 42, 0xFFFF5555);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private final class OptionList extends ContainerObjectSelectionList<OptionEntry> {
        OptionList(Minecraft client, int width, int height, int y) {
            super(client, width, height, y, 24);
        }

        void clearOptions() {
            clearEntries();
        }

        void addSkins() {
            if (ConfigManager.preset(config.preset).skins.skins.isEmpty()) addEntry(new SkinInfoEntry(), 30);
            var skins = ConfigManager.preset(config.preset).skins.skins.stream()
                    .filter(skin -> matches(skin.displayName) || matches(skin.itemModel))
                    .filter(skin -> skin.available(minecraft)).toList();
            List<SkinButton> buttons = new ArrayList<>();
            if (query.isBlank() || matches(skinText("original").getString())) buttons.add(new SkinButton(null));
            skins.forEach(skin -> buttons.add(new SkinButton(skin)));
            int columns = Math.max(1, (getRowWidth() + 2) / 118);
            for (int index = 0; index < buttons.size(); index += columns) {
                addEntry(new SkinRow(new ArrayList<>(buttons.subList(index, Math.min(index + columns, buttons.size()))), columns), 94);
            }
        }

        void addOption(AbstractWidget widget) {
            addOption(widget, 0);
        }

        void addOption(AbstractWidget widget, int indent) {
            addEntry(new WidgetEntry(widget, indent, null));
        }

        void addLabeledOption(Component label, AbstractWidget widget) {
            addEntry(new WidgetEntry(widget, 0, label));
        }

        void addBlock(BlockAlertTarget target) {
            addEntry(new BlockEntry(target), blockColumns(getRowWidth() - 28).lines() * 24);
        }

        void addPortal(PortalPreset.Type type) { addEntry(new PortalEntry(type)); }

        void addCraftRecipe(AutocraftPreset.Recipe recipe) { addEntry(new CraftRecipeEntry(recipe)); }

        void addBlockHeader() {
            addEntry(new BlockHeaderEntry(), blockColumns(getRowWidth() - 28).lines() * 24);
        }

        void addPlayerAlert(String key) {
            addEntry(new PlayerAlertEntry(key), new PlayerAlertColumns(getRowWidth() - 28).lines() * 24);
        }

        void addPlayerAlertHeader() {
            addEntry(new PlayerAlertHeaderEntry(), new PlayerAlertColumns(getRowWidth() - 28).lines() * 24);
        }

        void addBlockChannel(String key) {
            addEntry(new BlockChannelEntry(key), new BlockChannelColumns(getRowWidth() - 28).lines() * 24);
        }

        void addBlockChannelHeader() {
            addEntry(new BlockChannelHeaderEntry(), new BlockChannelColumns(getRowWidth() - 28).lines() * 24);
        }

        void addNumber(Component label, int value, int min, int max, IntConsumer set) {
            addEntry(new NumberEntry(label, value, min, max, set));
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 24;
        }
    }

    private abstract static class OptionEntry extends ContainerObjectSelectionList.Entry<OptionEntry> {}

    private final class SkinInfoEntry extends OptionEntry {
        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            var preset = ConfigManager.preset(config.preset).skins;
            Component label = preset.skins.isEmpty() ? skinText("unavailable")
                    : Component.translatable("minermarmot.skins.hint", preset.itemName);
            graphics.text(font, font.plainSubstrByWidth(label.getString(), getContentWidth()), getContentX(), getContentY() + 6, 0xFFCCAD80);
            if (hovered) graphics.setTooltipForNextFrame(label, mouseX, mouseY);
        }

        @Override public List<? extends GuiEventListener> children() { return List.of(); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(); }
    }

    private final class SkinRow extends OptionEntry {
        private final List<SkinButton> buttons;
        private final int columns;

        SkinRow(List<SkinButton> buttons, int columns) {
            this.buttons = buttons;
            this.columns = columns;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int cellWidth = (getContentWidth() - 6 * (columns - 1)) / columns;
            for (int index = 0; index < buttons.size(); index++) {
                SkinButton button = buttons.get(index);
                button.setPosition(getContentX() + index * (cellWidth + 6), getContentY());
                button.setWidth(cellWidth);
                button.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
        }

        @Override public List<? extends GuiEventListener> children() { return buttons; }
        @Override public List<? extends NarratableEntry> narratables() { return buttons; }
    }

    private final class SkinButton extends Button {
        private final SkinsPreset.Skin skin;
        private final AlertIcon original = new AlertIcon();

        SkinButton(SkinsPreset.Skin skin) {
            super(0, 0, 112, 88, skin == null ? skinText("original") : Component.literal(skin.displayName), button -> {
                if (skin == null) config.skinSelections.remove(config.preset);
                else config.skinSelections.put(config.preset, skin.itemModel);
                applyChanges();
            }, DEFAULT_NARRATION);
            this.skin = skin;
            original.item = "minecraft:diamond_pickaxe";
            original.validate();
            setTooltip(Tooltip.create(skin == null ? skinText("original")
                    : Component.literal(skin.displayName + "\n" + skin.itemModel).append("\n").append(skinText("packRequired"))));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = isSelectedSkin();
            graphics.fill(getX(), getY(), getRight(), getBottom(), selected ? 0xB04E3D25 : isHoveredOrFocused() ? 0xA0464646 : 0x80303030);
            if (selected || isFocused()) {
                int color = selected ? 0xFFB88646 : 0xFFFFFFFF;
                graphics.fill(getX(), getY(), getRight(), getY() + 1, color);
                graphics.fill(getX(), getBottom() - 2, getRight(), getBottom(), color);
                graphics.fill(getX(), getY(), getX() + 1, getBottom(), color);
                graphics.fill(getRight() - 1, getY(), getRight(), getBottom(), color);
            }
            ItemStack icon = skin == null || minecraft.getModelManager().getItemModel(skin.model) instanceof MissingItemModel
                    ? original.stack() : skin.icon.stack();
            graphics.pose().pushMatrix();
            graphics.pose().translate(getX() + (getWidth() - 40) / 2.0f, getY() + 7);
            graphics.pose().scale(2.5f, 2.5f);
            graphics.fakeItem(icon, 0, 0);
            graphics.pose().popMatrix();
            var lines = font.split(getMessage(), getWidth() - 8);
            for (int index = 0; index < Math.min(3, lines.size()); index++) {
                graphics.centeredText(font, lines.get(index), getX() + getWidth() / 2, getY() + 55 + index * 9, 0xFFFFFFFF);
            }
        }

        private boolean isSelectedSkin() {
            String selectedModel = config.skinSelections.get(config.preset);
            return skin == null ? selectedModel == null : skin.itemModel.equals(selectedModel);
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            var message = super.createNarrationMessage();
            return isSelectedSkin() ? message.append(", ").append(skinText("selected")) : message;
        }
    }

    private final class PortalEntry extends OptionEntry {
        private final PortalPreset.Type type;
        private final CycleButton<Boolean> monitor;

        PortalEntry(PortalPreset.Type type) {
            this.type = type;
            Component label = Component.literal(type.displayName).append(" · ").append(alertText("column.monitor"));
            monitor = toggleBuilder(Boolean.TRUE.equals(config.portals.monitors.get(type.id)))
                    .create(0, 0, 70, 20, label, (button, value) -> {
                        config.portals.monitors.put(type.id, value);
                        applyChanges();
                    });
            monitor.setTooltip(Tooltip.create(label));
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int rowWidth = Math.min(500, getContentWidth());
            int left = getContentX() + (getContentWidth() - rowWidth) / 2;
            graphics.item(type.icon.stack(), left, getContentY() + 2);
            graphics.text(font, font.plainSubstrByWidth(type.displayName, rowWidth - 100), left + 22, getContentY() + 6,
                    monitor.getValue() ? 0xFFFFFFFF : 0xFF888888);
            if (hovered && mouseX < left + rowWidth - 74) graphics.setTooltipForNextFrame(Component.literal(type.displayName), mouseX, mouseY);
            monitor.setPosition(left + rowWidth - 70, getContentY());
            renderWidget(monitor, graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() { return List.of(monitor); }

        @Override
        public List<? extends NarratableEntry> narratables() { return List.of(monitor); }
    }

    private final class CraftRecipeEntry extends OptionEntry {
        private final AutocraftPreset.Recipe recipe;
        private final CycleButton<Boolean> toggle;

        CraftRecipeEntry(AutocraftPreset.Recipe recipe) {
            this.recipe = recipe;
            Component label = Component.literal(recipe.displayName).append(" · ").append(craftText("enabled"));
            toggle = toggleBuilder(Boolean.TRUE.equals(config.autocrafter.recipes.get(recipe.id)))
                    .create(0, 0, 70, 20, label, (button, value) -> {
                        config.autocrafter.recipes.put(recipe.id, value);
                        applyChanges();
                    });
            toggle.setTooltip(Tooltip.create(label));
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int rowWidth = Math.min(500, getContentWidth() - 24);
            int left = getContentX() + 24 + (getContentWidth() - 24 - rowWidth) / 2;
            graphics.item(recipe.output.stack(), left, getContentY() + 2);
            graphics.text(font, font.plainSubstrByWidth(recipe.displayName, rowWidth - 100), left + 22, getContentY() + 6,
                    config.autocrafter.enabled && toggle.getValue() ? 0xFFFFFFFF : 0xFF888888);
            if (hovered && mouseX < left + rowWidth - 74) graphics.setTooltipForNextFrame(Component.literal(recipe.displayName), mouseX, mouseY);
            toggle.setPosition(left + rowWidth - 70, getContentY());
            renderWidget(toggle, graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() { return List.of(toggle); }

        @Override
        public List<? extends NarratableEntry> narratables() { return List.of(toggle); }
    }

    private final class WidgetEntry extends OptionEntry {
        private final AbstractWidget widget;
        private final int indent;
        private final Component label;

        WidgetEntry(AbstractWidget widget, int indent, Component label) {
            this.widget = widget;
            this.indent = indent;
            this.label = label;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int rowWidth = Math.min(500, getContentWidth());
            int left = getContentX() + (getContentWidth() - rowWidth) / 2;
            if (label != null) {
                graphics.text(font, font.plainSubstrByWidth(label.getString(), rowWidth - widget.getWidth() - 12),
                        left, getContentY() + 6, 0xFFFFFFFF);
                if (hovered && mouseX < left + rowWidth - widget.getWidth() - 8) {
                    graphics.setTooltipForNextFrame(label, mouseX, mouseY);
                }
                widget.setX(left + rowWidth - widget.getWidth());
            } else if (widget instanceof ConfigSectionWidget) {
                widget.setX(getContentX() + indent);
                widget.setWidth(getContentWidth() - indent);
            } else {
                widget.setWidth(Math.min(300, getContentWidth()));
                widget.setX(getContentX() + (getContentWidth() - widget.getWidth()) / 2);
            }
            widget.setY(getContentY());
            renderWidget(widget, graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }
    }

    private final class NumberInput extends EditBox {
        private final BooleanSupplier enabled;
        private boolean valid;

        NumberInput(Component label, int value, int min, int max, IntConsumer set, BooleanSupplier enabled) {
            super(ConfigScreen.this.font, 0, 0, 70, 20, label);
            this.enabled = enabled;
            setMaxLength(Math.max(6, Integer.toString(max).length()));
            valid = value >= min && value <= max;
            setValue(valid ? Integer.toString(value) : "");
            setTooltip(Tooltip.create(label.copy().append("\n")
                    .append(Component.translatable("minermarmot.alerts.numberRange", min, max))));
            setResponder(text -> {
                try {
                    int number = Integer.parseInt(text);
                    valid = number >= min && number <= max;
                    if (valid) {
                        set.accept(number);
                        applyChanges();
                    }
                } catch (NumberFormatException exception) {
                    valid = false;
                }
            });
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            setTextColor(valid ? enabled.getAsBoolean() ? 0xFFE0E0E0 : 0xFF777777 : 0xFFFF5555);
            super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    private final class NumberEntry extends OptionEntry {
        private final Component label;
        private final EditBox input;

        NumberEntry(Component label, int value, int min, int max, IntConsumer set) {
            this.label = label;
            input = new NumberInput(label, value, min, max, set, () -> true);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int rowWidth = Math.min(500, getContentWidth());
            int left = getContentX() + (getContentWidth() - rowWidth) / 2;
            graphics.text(font, font.plainSubstrByWidth(label.getString(), rowWidth - 78),
                    left, getContentY() + 6, 0xFFFFFFFF);
            if (hovered && mouseX < left + rowWidth - 74) {
                graphics.setTooltipForNextFrame(label, mouseX, mouseY);
            }
            input.setPosition(left + rowWidth - 70, getContentY());
            input.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(input);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(input);
        }
    }

    private BlockColumns blockColumns(int width) {
        var preset = ConfigManager.preset(config.preset);
        return new BlockColumns(width, preset.blockAlerts.sounds, preset.blockAlerts.glow,
                preset.allows(MiningFeature.SURFACE_TARGET_MINER));
    }

    private record BlockColumns(int width, boolean sound, boolean glow, boolean mine) {
        int lines() { return width >= 640 ? 1 : width >= 300 ? 2 : 3; }
        int toggleCount() { return 1 + (glow ? 1 : 0) + (mine ? 1 : 0); }
        int toggleWidth() {
            int available = width - (lines() == 2 ? 94 + (sound ? 60 : 0) : 0);
            return Math.min(70, (available - 6 * (toggleCount() + (lines() == 2 && sound ? 1 : 0))) / toggleCount());
        }
        int colorX() {
            return lines() == 1 ? width - 94 - (sound ? 106 : 0) - toggleCount() * 76 + 6 : 0;
        }
        int soundX() { return colorX() + 94; }
        int soundWidth() {
            return lines() == 1 ? 100 : width - 94 - (lines() == 2 ? toggleCount() * (toggleWidth() + 6) : 0);
        }
        int toggleX() { return width - toggleCount() * (toggleWidth() + 6) + 6; }
        int mineX() { return toggleX() + (glow ? toggleWidth() + 6 : 0); }
        int monitorX() { return width - toggleWidth(); }
        int nameWidth() { return lines() == 1 ? colorX() - 8 : width; }
        int colorY() { return lines() == 1 ? 0 : 24; }
        int toggleY() { return (lines() - 1) * 24; }
    }

    private record PlayerAlertColumns(int width) {
        int lines() { return width >= 576 ? 1 : width >= 360 ? 2 : 3; }
        int controlY() { return (lines() - 1) * 24; }
        int soundY() { return lines() == 3 ? 24 : controlY(); }
        int enabledX() { return width - 70; }
        int repeatX() { return width - 146; }
        int volumeX() { return width - 222; }
        int soundX() { return lines() == 1 ? width - 348 : 0; }
        int soundWidth() { return lines() == 1 ? 120 : lines() == 2 ? width - 228 : width; }
        int nameWidth() { return lines() == 1 ? soundX() - 8 : width; }
    }

    private record BlockChannelColumns(int width) {
        int lines() { return width >= 656 ? 1 : 2; }
        int settingsY() { return (lines() - 1) * 24; }
        int enabledX() { return width - 70; }
        int settingsX() { return lines() == 1 ? width - 472 : 0; }
        int settingsWidth() { return lines() == 1 ? 300 : width; }
        int cellWidth() { return (settingsWidth() - 6) / 2; }
        int nameWidth() { return lines() == 1 ? settingsX() - 8 : enabledX() - 102; }
    }

    private final class BlockChannelHeaderEntry extends OptionEntry {
        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX() + 24;
            int y = getContentY();
            BlockChannelColumns columns = new BlockChannelColumns(getContentWidth() - 24);
            graphics.text(font, alertText("channelColumn.function"), x, y + 6, 0xFFCCAD80);
            graphics.text(font, alertText("channelColumn.settings"), x + columns.settingsX(), y + columns.settingsY() + 6, 0xFFCCAD80);
            graphics.centeredText(font, alertText("playerColumn.enabled"), x + columns.enabledX() + 35, y + 6, 0xFFCCAD80);
            graphics.fill(x, y + columns.lines() * 24 - 3, getContentRight(), y + columns.lines() * 24 - 2, 0x605F503A);
        }

        @Override
        public List<? extends GuiEventListener> children() { return List.of(); }

        @Override
        public List<? extends NarratableEntry> narratables() { return List.of(); }
    }

    private final class BlockChannelEntry extends OptionEntry {
        private final Component label;
        private final CycleButton<Boolean> enabled;
        private AbstractWidget sideControl;
        private final List<AbstractWidget> inputs = new ArrayList<>();
        private final List<Component> inputLabels = new ArrayList<>();
        private final List<AbstractWidget> widgets = new ArrayList<>();

        BlockChannelEntry(String key) {
            label = alertText(key.equals("sounds") || key.equals("waypoints") ? "channel." + key : key);
            boolean active = switch (key) {
                case "sounds" -> config.alerts.sounds;
                case "waypoints" -> config.alerts.waypoints;
                default -> config.alerts.glow;
            };
            enabled = toggleBuilder(active).create(0, 0, 70, 20, alertText(key), (button, value) -> {
                switch (key) {
                    case "sounds" -> config.alerts.sounds = value;
                    case "waypoints" -> config.alerts.waypoints = value;
                    default -> config.alerts.glow = value;
                }
                applyChanges();
                if (key.equals("glow")) {
                    refreshOptions();
                }
            });
            enabled.setTooltip(Tooltip.create(alertText(key)));
            if (key.equals("sounds")) {
                inputs.add(new NumberInput(alertText("soundDelay"), config.alerts.soundDelayTicks, 0, 12000,
                        value -> config.alerts.soundDelayTicks = value, enabled::getValue));
                inputLabels.add(alertText("channel.delay"));
                sideControl = SoundSelectionScreen.volumeButton(config.alerts.soundVolume, value -> {
                    config.alerts.soundVolume = value;
                    applyChanges();
                });
            } else if (key.equals("waypoints")) {
                inputs.add(new NumberInput(alertText("waypointCount"), config.alerts.displayedWaypointCount, 0, 100,
                        value -> config.alerts.displayedWaypointCount = value, enabled::getValue));
                inputLabels.add(alertText("channel.count"));
                inputs.add(new NumberInput(alertText("waypointDistance"), config.alerts.waypointDisplayDistance, 1, 4096,
                        value -> config.alerts.waypointDisplayDistance = value, enabled::getValue));
                inputLabels.add(alertText("channel.distance"));
            }
            widgets.addAll(inputs);
            if (key.equals("waypoints")) {
                sideControl = CycleButton.<String>builder(
                            value -> alertText(value.equals("rarity") ? "waypointSort.rarity" : "channel.distance"),
                            config.alerts.waypointSortByRarity ? "rarity" : "distance")
                    .withValues("distance", "rarity").displayOnlyValue()
                    .create(0, 0, 90, 20, alertText("waypointSort"), (button, value) -> {
                        config.alerts.waypointSortByRarity = value.equals("rarity");
                        applyChanges();
                    });
                sideControl.setTooltip(Tooltip.create(alertText("waypointSort")));
            }
            if (key.equals("glow") && active) {
                var hiddenEdges = toggleBuilder(config.alerts.glowHiddenEdges).create(0, 0, 70, 20,
                        alertText("glowHiddenEdges"), (button, value) -> {
                            config.alerts.glowHiddenEdges = value;
                            applyChanges();
                        });
                hiddenEdges.setTooltip(Tooltip.create(alertText("glowHiddenEdgesHint")));
                sideControl = hiddenEdges;
            }
            if (sideControl != null) widgets.add(sideControl);
            widgets.add(enabled);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX() + 24;
            int y = getContentY();
            BlockChannelColumns columns = new BlockChannelColumns(getContentWidth() - 24);
            boolean active = enabled.getValue();
            if (!active) {
                graphics.fill(x, y, getContentRight(), y + columns.lines() * 24 - 4, 0x40303030);
            }
            int textColor = active ? 0xFFFFFFFF : 0xFF777777;
            graphics.text(font, font.plainSubstrByWidth(label.getString(), columns.nameWidth()), x, y + 6, textColor);
            if (hovered && mouseY < y + 20 && mouseX < x + columns.nameWidth()) {
                graphics.setTooltipForNextFrame(label, mouseX, mouseY);
            }
            int cellWidth = inputs.size() == 1 ? columns.settingsWidth() : columns.cellWidth();
            for (int index = 0; index < inputs.size(); index++) {
                AbstractWidget input = inputs.get(index);
                int cellX = x + columns.settingsX() + index * (cellWidth + 6);
                int inputY = y + columns.settingsY();
                graphics.text(font, font.plainSubstrByWidth(inputLabels.get(index).getString(), cellWidth - 66),
                        cellX, inputY + 6, textColor);
                input.setPosition(cellX + cellWidth - 60, inputY);
                input.setWidth(60);
                if (hovered && mouseX >= cellX && mouseX < input.getX() && mouseY >= inputY && mouseY < inputY + 20) {
                    graphics.setTooltipForNextFrame(input.getMessage(), mouseX, mouseY);
                }
            }
            if (inputs.isEmpty() && widgets.size() > 1) {
                int cellX = x + columns.settingsX();
                graphics.text(font, font.plainSubstrByWidth(alertText("glowHiddenEdges").getString(), columns.settingsWidth() - 76),
                        cellX, y + columns.settingsY() + 6, textColor);
            } else if (inputs.isEmpty()) {
                graphics.text(font, Component.literal("—"), x + columns.settingsX(), y + columns.settingsY() + 6, textColor);
            }
            if (sideControl != null) {
                sideControl.setPosition(x + columns.enabledX() - 96, y);
                sideControl.setWidth(90);
            }
            enabled.setPosition(x + columns.enabledX(), y);
            widgets.forEach(widget -> {
                widget.setAlpha(active || widget == enabled ? 1.0f : 0.45f);
                renderWidget(widget, graphics, mouseX, mouseY, partialTick);
            });
        }

        @Override
        public List<? extends GuiEventListener> children() { return widgets; }

        @Override
        public List<? extends NarratableEntry> narratables() { return widgets; }
    }

    private final class PlayerAlertHeaderEntry extends OptionEntry {
        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX() + 24;
            int y = getContentY();
            PlayerAlertColumns columns = new PlayerAlertColumns(getContentWidth() - 24);
            graphics.text(font, alertText("playerColumn.type"), x, y + 6, 0xFFCCAD80);
            graphics.centeredText(font, alertText("playerColumn.sound"), x + columns.soundX() + columns.soundWidth() / 2,
                    y + columns.soundY() + 6, 0xFFCCAD80);
            graphics.centeredText(font, alertText("volume"), x + columns.volumeX() + 35,
                    y + columns.controlY() + 6, 0xFFCCAD80);
            graphics.centeredText(font, alertText("playerColumn.repeat"), x + columns.repeatX() + 35,
                    y + columns.controlY() + 6, 0xFFCCAD80);
            graphics.centeredText(font, alertText("playerColumn.enabled"), x + columns.enabledX() + 35,
                    y + columns.controlY() + 6, 0xFFCCAD80);
            graphics.fill(x, y + columns.lines() * 24 - 3, getContentRight(), y + columns.lines() * 24 - 2, 0x605F503A);
        }

        @Override
        public List<? extends GuiEventListener> children() { return List.of(); }

        @Override
        public List<? extends NarratableEntry> narratables() { return List.of(); }
    }

    private final class PlayerAlertEntry extends OptionEntry {
        private final Component label;
        private final Button sound;
        private final CycleButton<Float> volume;
        private final CycleButton<Integer> repeat;
        private final CycleButton<Boolean> enabled;
        private final List<AbstractWidget> widgets;

        PlayerAlertEntry(String key) {
            label = alertText(key);
            boolean inventory = key.equals("inventoryFull");
            var settings = config.playerAlerts;
            String selectedSound = inventory ? settings.inventoryFullSound : settings.privateMessageSound;
            sound = Button.builder(SoundSelectionScreen.label(selectedSound), button -> minecraft.setScreenAndShow(
                    new SoundSelectionScreen(ConfigScreen.this, selectedSound,
                            inventory ? settings.inventoryFullVolume : settings.privateMessageVolume, value -> {
                        if (inventory) { settings.inventoryFullSound = value; }
                        else { settings.privateMessageSound = value; }
                        applyChanges();
                    }))).bounds(0, 0, 120, 20).build();
            sound.setTooltip(Tooltip.create(selectedSound.isEmpty() ? alertText("noSound") : Component.literal(selectedSound)));
            volume = SoundSelectionScreen.volumeButton(inventory ? settings.inventoryFullVolume : settings.privateMessageVolume, value -> {
                if (inventory) { settings.inventoryFullVolume = value; }
                else { settings.privateMessageVolume = value; }
                applyChanges();
            });
            repeat = CycleButton.builder(value -> Component.literal(Integer.toString(value)),
                            inventory ? settings.inventoryFullRepeat : settings.privateMessageRepeat)
                    .withValues(1, 2, 3, 4, 5).displayOnlyValue()
                    .create(0, 0, 70, 20, alertText("playerColumn.repeat"), (button, value) -> {
                        if (inventory) { settings.inventoryFullRepeat = value; }
                        else { settings.privateMessageRepeat = value; }
                        applyChanges();
                    });
            repeat.setTooltip(Tooltip.create(alertText("repeatHint")));
            enabled = toggleBuilder(inventory ? settings.inventoryFull : settings.privateMessage)
                    .create(0, 0, 70, 20, label, (button, value) -> {
                        if (inventory) { settings.inventoryFull = value; }
                        else { settings.privateMessage = value; }
                        applyChanges();
                    });
            enabled.setTooltip(Tooltip.create(alertText("playerColumn.enabled").copy().append(": ").append(label)));
            widgets = List.of(sound, volume, repeat, enabled);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX() + 24;
            int y = getContentY();
            PlayerAlertColumns columns = new PlayerAlertColumns(getContentWidth() - 24);
            boolean active = enabled.getValue();
            if (!active) {
                graphics.fill(x, y, getContentRight(), y + columns.lines() * 24 - 4, 0x40303030);
            }
            graphics.text(font, font.plainSubstrByWidth(label.getString(), columns.nameWidth()), x, y + 6,
                    active ? 0xFFFFFFFF : 0xFF777777);
            if (hovered && mouseY < y + 20 && mouseX < x + columns.nameWidth()) {
                graphics.setTooltipForNextFrame(label, mouseX, mouseY);
            }
            sound.setPosition(x + columns.soundX(), y + columns.soundY());
            sound.setWidth(columns.soundWidth());
            volume.setPosition(x + columns.volumeX(), y + columns.controlY());
            repeat.setPosition(x + columns.repeatX(), y + columns.controlY());
            enabled.setPosition(x + columns.enabledX(), y + columns.controlY());
            widgets.forEach(widget -> {
                widget.setAlpha(active || widget == enabled ? 1.0f : 0.45f);
                renderWidget(widget, graphics, mouseX, mouseY, partialTick);
            });
        }

        @Override
        public List<? extends GuiEventListener> children() { return widgets; }

        @Override
        public List<? extends NarratableEntry> narratables() { return widgets; }
    }

    private final class BlockHeaderEntry extends OptionEntry {
        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX() + 24;
            int y = getContentY();
            BlockColumns columns = blockColumns(getContentWidth() - 24);
            graphics.text(font, alertText("column.ore"), x, y + 6, 0xFFCCAD80);
            header(graphics, "color", x + columns.colorX() + 43, y + columns.colorY());
            if (columns.sound) {
                header(graphics, "sound", x + columns.soundX() + columns.soundWidth() / 2, y + columns.colorY());
            }
            if (columns.glow) {
                header(graphics, "glow", x + columns.toggleX() + columns.toggleWidth() / 2, y + columns.toggleY());
            }
            if (columns.mine) {
                header(graphics, "mine", x + columns.mineX() + columns.toggleWidth() / 2, y + columns.toggleY());
            }
            header(graphics, "monitor", x + columns.monitorX() + columns.toggleWidth() / 2, y + columns.toggleY());
            graphics.fill(x, y + columns.lines() * 24 - 3, getContentRight(), y + columns.lines() * 24 - 2, 0x605F503A);
        }

        private void header(GuiGraphicsExtractor graphics, String key, int x, int y) {
            graphics.centeredText(font, alertText("column." + key), x, y + 6, 0xFFCCAD80);
        }

        @Override
        public List<? extends GuiEventListener> children() { return List.of(); }

        @Override
        public List<? extends NarratableEntry> narratables() { return List.of(); }
    }

    private final class BlockEntry extends OptionEntry {
        private final BlockAlertTarget target;
        private final BlockAlertSettings settings;
        private final EditBox color;
        private final Button sound;
        private final CycleButton<Boolean> glow;
        private final CycleButton<Boolean> surfaceMine;
        private final CycleButton<Boolean> enabled;
        private final List<AbstractWidget> widgets = new ArrayList<>();
        private final boolean showSound;
        private final boolean showGlow;
        private final boolean showSurfaceMine;

        BlockEntry(BlockAlertTarget target) {
            this.target = target;
            settings = config.alerts.targets.get(target.id);
            var preset = ConfigManager.preset(config.preset).blockAlerts;
            showSound = preset.sounds;
            showGlow = preset.glow;
            showSurfaceMine = ConfigManager.preset(config.preset).allows(MiningFeature.SURFACE_TARGET_MINER);
            color = new EditBox(font, 0, 0, 70, 20, alertText("color"));
            color.setMaxLength(7);
            color.setValue(settings.color);
            color.setTooltip(Tooltip.create(alertText("colorHint")));
            color.setResponder(value -> {
                boolean valid = BlockAlertSettings.validColor(value);
                color.setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF5555);
                if (valid) {
                    settings.color = value.toUpperCase(Locale.ROOT);
                    applyChanges();
                }
            });
            sound = Button.builder(soundLabel(), button -> minecraft.setScreenAndShow(
                    new SoundSelectionScreen(ConfigScreen.this, settings.sound, config.alerts.soundVolume, value -> {
                        settings.sound = value;
                        applyChanges();
                    })))
                    .bounds(0, 0, 90, 20).build();
            sound.setTooltip(Tooltip.create(settings.sound.isEmpty() ? alertText("noSound") : Component.literal(settings.sound)));
            glow = toggleBuilder(settings.glow).create(0, 0, 70, 20, alertText("glow"),
                    (button, value) -> {
                        settings.glow = value;
                        applyChanges();
                    });
            glow.setTooltip(Tooltip.create(alertText("glow")));
            surfaceMine = toggleBuilder(settings.surfaceMine).create(0, 0, 70, 20, alertText("surfaceMine"),
                    (button, value) -> {
                        settings.surfaceMine = value;
                        applyChanges();
                    });
            surfaceMine.setTooltip(Tooltip.create(alertText("surfaceMineHint")));
            enabled = toggleBuilder(settings.enabled).create(0, 0, 70, 20, alertText("enabled"),
                    (button, value) -> {
                        settings.enabled = value;
                        applyChanges();
                    });
            enabled.setTooltip(Tooltip.create(alertText("enabled")));
            widgets.add(color);
            if (showSound) {
                widgets.add(sound);
            }
            if (showGlow) {
                widgets.add(glow);
            }
            if (showSurfaceMine) {
                widgets.add(surfaceMine);
            }
            widgets.add(enabled);
        }

        private Component soundLabel() {
            return SoundSelectionScreen.label(settings.sound);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX() + 24;
            int y = getContentY();
            BlockColumns columns = blockColumns(getContentWidth() - 24);
            if (!settings.enabled) {
                graphics.fill(x, y, getContentRight(), y + columns.lines() * 24 - 4, 0x40303030);
            }
            graphics.item(target.item1.stack(), x, y + 2);
            int nameX = x + 20;
            if (!target.item2.isEmpty()) {
                int index = (int) ((net.minecraft.util.Util.getMillis() / 1500) % target.item2.size());
                graphics.item(target.item2.get(index).stack(), nameX, y + 2);
                nameX += 20;
            }
            if (!settings.enabled) {
                graphics.fill(x, y, nameX - 4, y + 20, 0x99181818);
            }
            int nameWidth = columns.nameWidth() - (nameX - x);
            graphics.text(font, font.plainSubstrByWidth(target.displayName, Math.max(10, nameWidth)),
                    nameX, y + 6, settings.enabled ? 0xFFFFFFFF : 0xFF777777);
            if (hovered && mouseY < y + 20 && mouseX < nameX + nameWidth) {
                ArrayList<Component> lines = new ArrayList<>();
                lines.add(Component.literal(target.displayName));
                lines.add(Component.literal(target.id));
                target.item2.forEach(icon -> lines.add(icon.name()));
                graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY);
            }
            int colorX = x + columns.colorX();
            int colorY = y + columns.colorY();
            int toggleY = y + columns.toggleY();
            graphics.fill(colorX, colorY + 4, colorX + 12, colorY + 16,
                    settings.argb() & 0xFFFFFF | (settings.enabled ? 0xFF000000 : 0x66000000));
            color.setPosition(colorX + 16, colorY);
            color.setTextColor(BlockAlertSettings.validColor(color.getValue())
                    ? settings.enabled ? 0xFFE0E0E0 : 0xFF777777 : 0xFFFF5555);
            if (showSound) {
                sound.setPosition(x + columns.soundX(), colorY);
                sound.setWidth(columns.soundWidth());
            }
            if (showGlow) {
                glow.setPosition(x + columns.toggleX(), toggleY);
                glow.setWidth(columns.toggleWidth());
            }
            if (showSurfaceMine) {
                surfaceMine.setPosition(x + columns.mineX(), toggleY);
                surfaceMine.setWidth(columns.toggleWidth());
            }
            enabled.setPosition(x + columns.monitorX(), toggleY);
            enabled.setWidth(columns.toggleWidth());
            widgets.forEach(widget -> {
                widget.setAlpha(settings.enabled || widget == enabled ? 1.0f : 0.45f);
                renderWidget(widget, graphics, mouseX, mouseY, partialTick);
            });
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}

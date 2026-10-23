package com.minermarmot.client.screen;

import com.minermarmot.client.integration.VersionCheck;

import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.config.ClientConfig;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.mining.MiningConfig;
import com.minermarmot.client.features.mining.MiningFeature;
import com.minermarmot.client.screen.widget.ConfigSectionWidget;
import com.minermarmot.client.screen.widget.DolphinSettingsControls;
import com.minermarmot.client.features.alerts.AlertIcon;
import com.minermarmot.client.features.alerts.SoundSelectionScreen;
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

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
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
    private boolean alertsExpanded;
    private boolean tntExpanded = true;
    private boolean displayExpanded;
    private boolean saveFailed;
    private OptionList options;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("minermarmot.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int tabWidth = Math.min(90, (contentWidth() - 40) / 2);
        addTab("general", contentLeft(), tabWidth);
        addTab("features", contentLeft() + tabWidth + 6, tabWidth);
        EditBox search = new EditBox(font, contentLeft(), 58, contentWidth(), 20, text("search"));
        search.setHint(text("search"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;

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
        if (category.equals("general")) {
            options.addLabeledOption(text("preset"), CycleButton.builder(id -> Component.literal(ConfigManager.preset(id).name), config.preset)
                    .withValues(ConfigManager.presets().keySet())
                    .displayOnlyValue()
                    .create(0, 0, 140, 20, text("preset"), (button, id) -> {
                        config.applyPreset(id, ConfigManager.preset(id));
                        customBorderDistance = !MiningConfig.BORDER_DISTANCES.contains(config.mining.worldBorderDistance);
                        applyChanges();

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
                    if (feature == MiningFeature.DOLPHIN) {
                        options.addDolphin();
                    } else if (feature == MiningFeature.AUTO_BOOST) {
                        options.addAutoBoost();
                    } else if (feature == MiningFeature.WORLD_BORDER) {
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

        boolean showFps = matches(text("display").getString()) || matches(text("displayFps").getString());
        boolean showPosition = matches(text("display").getString()) || matches(text("displayPosition").getString());
        boolean showObjectives = matches(text("display").getString()) || matches(text("displayObjectives").getString())
                || matches(text("objectivesSide").getString());
        boolean showScrollObjectives = matches(text("display").getString()) || matches(text("displayScrollObjectives").getString())
                || matches(text("objectivesSide").getString());
        if (showFps || showPosition || showObjectives || showScrollObjectives) {
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
                }
                if (showScrollObjectives) {
                    options.addLabeledOption(text("displayScrollObjectives"), toggleBuilder(config.displayScrollObjectives)
                            .create(0, 0, 70, 20, text("displayScrollObjectives"), (button, value) -> {
                                config.displayScrollObjectives = value;
                                applyChanges();
                            }));
                }
                if (showObjectives || showScrollObjectives) {
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

        void addOption(AbstractWidget widget) {
            addOption(widget, 0);
        }

        void addOption(AbstractWidget widget, int indent) {
            addEntry(new WidgetEntry(widget, indent, null));
        }

        void addLabeledOption(Component label, AbstractWidget widget) {
            addEntry(new WidgetEntry(widget, 0, label));
        }








        void addPlayerAlert(String key) {
            addEntry(new PlayerAlertEntry(key), new PlayerAlertColumns(getRowWidth() - 28).lines() * 24);
        }

        void addPlayerAlertHeader() {
            addEntry(new PlayerAlertHeaderEntry(), new PlayerAlertColumns(getRowWidth() - 28).lines() * 24);
        }





        void addAutoBoost() { addEntry(new AutoBoostEntry()); }
        void addDolphin() { addEntry(new DolphinEntry(), 40); }

        void addNumber(Component label, int value, int min, int max, IntConsumer set) {
            addEntry(new NumberEntry(label, value, min, max, set));
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 24;
        }
    }

    private abstract static class OptionEntry extends ContainerObjectSelectionList.Entry<OptionEntry> {}

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

    private final class AutoBoostEntry extends OptionEntry {
        private final Component label = Component.translatable(MiningFeature.AUTO_BOOST.translationKey());
        private final CycleButton<Boolean> enabled;
        private final NumberInput interval;

        AutoBoostEntry() {
            enabled = toggleBuilder(config.mining.enabled.contains(MiningFeature.AUTO_BOOST))
                    .create(0, 0, 70, 20, label, (button, value) -> {
                        if (value) config.mining.enabled.add(MiningFeature.AUTO_BOOST);
                        else config.mining.enabled.remove(MiningFeature.AUTO_BOOST);
                        applyChanges();
                    });
            enabled.setTooltip(Tooltip.create(label));
            interval = new NumberInput(text("autoBoostInterval"), config.mining.autoBoostIntervalMs, 0, 60000,
                    value -> config.mining.autoBoostIntervalMs = value, () -> enabled.getValue());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float tick) {
            int rowWidth = Math.min(500, getContentWidth());
            int left = getContentX() + (getContentWidth() - rowWidth) / 2;
            int right = left + rowWidth;
            graphics.text(font, font.plainSubstrByWidth(label.getString(), Math.max(0, rowWidth - 180)), left, getContentY() + 6,
                    enabled.getValue() ? 0xFFFFFFFF : 0xFF888888);
            interval.setPosition(right - 170, getContentY());
            enabled.setPosition(right - 70, getContentY());
            graphics.text(font, "ms", interval.getRight() + 4, getContentY() + 6, 0xFFBBBBBB);
            renderWidget(interval, graphics, mouseX, mouseY, tick);
            renderWidget(enabled, graphics, mouseX, mouseY, tick);
            if (hovered && mouseX < interval.getX()) graphics.setTooltipForNextFrame(label, mouseX, mouseY);
        }

        @Override public List<? extends GuiEventListener> children() { return List.of(interval, enabled); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(interval, enabled); }
    }

    private final class DolphinEntry extends OptionEntry {
        private final DolphinSettingsControls controls = new DolphinSettingsControls(font, config.mining, ConfigScreen.this::applyChanges);

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float tick) {
            controls.render(graphics, getContentX(), getContentY(), getContentWidth(), mouseX, mouseY, hovered, tick);
        }

        @Override public List<? extends GuiEventListener> children() { return controls.children(); }
        @Override public List<? extends NarratableEntry> narratables() { return controls.children(); }
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




}

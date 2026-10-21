package com.minermarmot.client.features.alerts;

import com.minermarmot.client.screen.ConfigMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class SoundSelectionScreen extends ConfigMenuScreen {
    private final Screen parent;
    private final Consumer<String> select;
    private final String selected;
    private final float volume;
    private SoundList sounds;
    private String query = "";

    public SoundSelectionScreen(Screen parent, String selected, Consumer<String> select) {
        this(parent, selected, 1.0f, select);
    }

    public SoundSelectionScreen(Screen parent, String selected, float volume, Consumer<String> select) {
        super(Component.translatable("minermarmot.alerts.selectSound"));
        this.parent = parent;
        this.selected = selected;
        this.select = select;
        this.volume = volume;
    }

    @Override
    protected int contentWidth() {
        return Math.min(420, super.contentWidth());
    }

    @Override
    protected void init() {
        sounds = addRenderableWidget(new SoundList(minecraft, contentWidth(), Math.max(24, height - 124), 84));
        sounds.setX(contentLeft());
        EditBox search = new EditBox(font, contentLeft(), 58, contentWidth() - 126, 20, Component.translatable("minermarmot.config.search"));
        search.setHint(Component.translatable("minermarmot.config.search"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            refresh();
        });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(label(""), button -> choose(""))
                .bounds(contentLeft() + contentWidth() - 120, 58, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("minermarmot.config.cancel"), button -> onClose())
                .bounds(width / 2 - 90, height - 28, 180, 20).build());
        refresh();
    }

    private void refresh() {
        sounds.clearSounds();
        AlertSounds.SOUNDS.stream()
                .filter(id -> (id + " " + label(id).getString()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .forEach(id -> sounds.addSound(new SoundEntry(id)));
    }

    public static Component label(String id) {
        return AlertSounds.SOUNDS.contains(id)
                ? Component.translatable("minermarmot.alerts.sound." + id.substring(id.lastIndexOf('.') + 1))
                : Component.translatable("minermarmot.alerts.noSound").withStyle(style -> style.withColor(0xBBBBBB));
    }

    public static CycleButton<Float> volumeButton(float initial, Consumer<Float> update) {
        return CycleButton.builder(value -> Component.literal("×" + value), initial)
                .withValues(AlertSounds.VOLUMES).displayOnlyValue()
                .create(0, 0, 70, 20, Component.translatable("minermarmot.alerts.volume"), (button, value) -> update.accept(value));
    }

    private void choose(String id) {
        select.accept(id);
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class SoundList extends ContainerObjectSelectionList<SoundEntry> {
        SoundList(Minecraft client, int width, int height, int y) {
            super(client, width, height, y, 24);
        }

        void addSound(SoundEntry entry) {
            addEntry(entry);
        }

        void clearSounds() {
            clearEntries();
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 24;
        }
    }

    private final class SoundEntry extends ContainerObjectSelectionList.Entry<SoundEntry> {
        private final Button choose;
        private final Button preview;

        SoundEntry(String id) {
            Component name = id.equals(selected) ? Component.literal("✓ ").append(label(id)) : label(id);
            choose = Button.builder(name, button -> choose(id))
                    .bounds(0, 0, 100, 20).build();
            choose.setTooltip(Tooltip.create(Component.literal(id)));
            preview = Button.builder(Component.translatable("minermarmot.alerts.previewSound"), button -> AlertSounds.playSound(id, volume))
                    .bounds(0, 0, 60, 20).build();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            choose.setPosition(getContentX(), getContentY());
            choose.setWidth(getContentWidth() - 64);
            preview.setPosition(getContentRight() - 60, getContentY());
            choose.extractRenderState(graphics, mouseX, mouseY, partialTick);
            preview.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(choose, preview);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(choose, preview);
        }
    }
}

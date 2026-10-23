package com.minermarmot.client.screen.widget;

import com.minermarmot.client.features.mining.MiningConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

public final class DolphinSettingsControls {
    private final Font font;
    private final List<ValueInput> inputs;

    public DolphinSettingsControls(Font font, MiningConfig settings, Runnable changed) {
        this.font = font;
        inputs = List.of(
                new ValueInput("dolphinMinY", settings.dolphinMinY, value -> value < settings.dolphinMaxY,
                        value -> settings.dolphinMinY = value, changed),
                new ValueInput("dolphinMaxY", settings.dolphinMaxY, value -> value > settings.dolphinMinY,
                        value -> settings.dolphinMaxY = value, changed),
                new ValueInput("dolphinAdvanceBlocks", settings.dolphinAdvanceBlocks, value -> value >= 1,
                        value -> settings.dolphinAdvanceBlocks = value, changed));
    }

    public List<? extends EditBox> children() { return inputs; }

    public void render(GuiGraphicsExtractor graphics, int x, int y, int width, int mouseX, int mouseY,
                       boolean hovered, float tick) {
        int rowWidth = Math.min(500, width);
        int left = x + (width - rowWidth) / 2;
        int inputWidth = Math.max(24, Math.min(70, (rowWidth - 80) / 3 - 8));
        int controlsX = left + rowWidth - 3 * (inputWidth + 8) + 8;
        Component label = Component.translatable("minermarmot.mining.dolphin");
        graphics.text(font, font.plainSubstrByWidth(label.getString(), Math.max(0, controlsX - left - 8)), left, y + 18, 0xFFFFFFFF);
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            int fieldX = controlsX + i * (inputWidth + 8);
            graphics.text(font, font.plainSubstrByWidth(input.getMessage().getString(), inputWidth), fieldX, y + 1, 0xFFBBBBBB);
            input.setPosition(fieldX, y + 14);
            input.setWidth(inputWidth);
            input.extractRenderState(graphics, mouseX, mouseY, tick);
        }
        if (hovered && mouseX < controlsX) graphics.setTooltipForNextFrame(Component.translatable(
                "minermarmot.config.dolphinHint", KeyMapping.get("key.minermarmot.toggleDolphin").getTranslatedKeyMessage()), mouseX, mouseY);
    }

    private final class ValueInput extends EditBox {
        private boolean valid = true;

        ValueInput(String key, int value, IntPredicate accepts, IntConsumer update, Runnable changed) {
            super(font, 0, 0, 70, 20, Component.translatable("minermarmot.config." + key));
            setMaxLength(11);
            setValue(Integer.toString(value));
            setTooltip(Tooltip.create(getMessage().copy().append("\n")
                    .append(Component.translatable("minermarmot.config.dolphinRange"))));
            setResponder(text -> {
                try {
                    int number = Integer.parseInt(text);
                    valid = accepts.test(number);
                    if (valid) {
                        update.accept(number);
                        changed.run();
                    }
                } catch (NumberFormatException exception) {
                    valid = false;
                }
            });
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
            setTextColor(valid ? 0xFFE0E0E0 : 0xFFFF5555);
            super.extractWidgetRenderState(graphics, mouseX, mouseY, tick);
        }
    }
}

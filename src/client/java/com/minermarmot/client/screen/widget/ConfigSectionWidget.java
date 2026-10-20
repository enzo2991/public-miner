package com.minermarmot.client.screen.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

public class ConfigSectionWidget extends AbstractButton {

    private boolean expanded = false;
    private final Runnable onToggle;

    public ConfigSectionWidget(int x, int y, int width, String name,boolean expanded, Runnable onToggle) {
        super(x, y, width, 20, Component.literal(name));

        this.expanded = expanded;
        this.onToggle = onToggle;

    }

    @Override
    public void onPress(InputWithModifiers input) {
        expanded = !expanded;

        if (onToggle != null) {
            onToggle.run();
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? 0x80483725 : 0x60332920);
        graphics.fill(getX(), getY(), getX() + 2, getBottom(), 0xFFB88646);
        Component text = Component.literal((expanded ? "− " : "+ ") + getMessage().getString());
        graphics.text(Minecraft.getInstance().font, text, getX() + 6, getY() + 6, 0xFFFFFFFF, true);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public Component getDisplayName() {
        return Component.literal(
                (expanded ? "− " : "+ ")
                        + getMessage().getString()
        );
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}

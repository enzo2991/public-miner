package com.minermarmot.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public abstract class ConfigMenuScreen extends Screen {
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath("minermarmot", "icon.png");

    protected ConfigMenuScreen(Component title) {
        super(title);
    }

    protected int contentWidth() {
        return Math.min(720, width - 40);
    }

    protected int contentLeft() {
        return (width - contentWidth()) / 2;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int left = contentLeft() - 8;
        int right = contentLeft() + contentWidth() + 8;
        graphics.fill(left, 50, right, height - 38, 0xA0181818);
        graphics.fill(left, 50, right, 51, 0xFFB88646);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, contentLeft() + contentWidth() + 8 - 32, 8,
                0, 0, 32, 32, 128, 128, 128, 128);
    }
}

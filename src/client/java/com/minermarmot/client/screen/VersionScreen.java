package com.minermarmot.client.screen;

import com.minermarmot.client.integration.VersionCheck;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VersionScreen extends ConfigMenuScreen {
    private final Screen parent;
    private Button retry;

    public VersionScreen(Screen parent) {
        super(Component.translatable("minermarmot.version.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(180, contentWidth());
        retry = addRenderableWidget(Button.builder(Component.translatable("minermarmot.version.retry"), button -> VersionCheck.retry())
                .bounds((width - buttonWidth) / 2, height - 60, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("minermarmot.config.quit"), button -> onClose())
                .bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
    }

    @Override
    public void tick() {
        retry.active = !VersionCheck.result().status().equals("checking");
        if (VersionCheck.isCompatible()) minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, Component.translatable("minermarmot.version.installed", VersionCheck.installedVersion()), width / 2, 70, 0xFFFFFFFF);
        if (!VersionCheck.result().requiredVersion().isEmpty()) {
            graphics.centeredText(font, Component.translatable("minermarmot.version.required", VersionCheck.result().requiredVersion()), width / 2, 86, 0xFFFFFFFF);
        }
        var lines = font.split(Component.translatable("minermarmot.version." + VersionCheck.result().status()), Math.max(40, contentWidth() - 20));
        for (int index = 0; index < lines.size(); index++) graphics.centeredText(font, lines.get(index), width / 2, 112 + index * 12, 0xFFFFCC88);
    }

    @Override
    public void onClose() { minecraft.setScreenAndShow(parent); }
}

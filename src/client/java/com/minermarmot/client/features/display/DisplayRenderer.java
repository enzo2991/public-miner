package com.minermarmot.client.features.display;

import com.minermarmot.Main;
import com.minermarmot.client.config.ConfigManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DisplayRenderer {
    private static List<Component> objectiveLines = List.of();

    private DisplayRenderer() {}

    public static void initialize() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Main.id("display"),
                (graphics, tracker) -> render(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(DisplayRenderer::updateObjectives);
    }

    static void updateObjectives(Minecraft client) {
        objectiveLines = List.of();
        if (client.player == null || client.level == null || !ConfigManager.current().displayObjectives) return;
        objectiveLines = objectives(client.player.getMainHandItem());
        if (objectiveLines.isEmpty()) objectiveLines = objectives(client.player.getOffhandItem());
        for (int slot = 0; objectiveLines.isEmpty() && slot < client.player.getInventory().getContainerSize(); slot++) {
            objectiveLines = objectives(client.player.getInventory().getItem(slot));
        }
    }

    static List<Component> objectives(ItemStack stack) {
        if (stack.isEmpty() || !plainText(stack.getHoverName()).contains("pioche")) return List.of();
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<Component> lines = new ArrayList<>();
        boolean started = false;
        for (Component line : lore.lines()) {
            String text = plainText(line);
            if (!started && text.contains("objectifs d'évolution :")) started = true;
            if (!started) continue;
            if (text.contains("/autosell")) break;
            if (!text.isBlank()) lines.add(line);
        }
        return List.copyOf(lines);
    }

    private static String plainText(Component text) {
        return ChatFormatting.stripFormatting(text.getString()).replace('\u2019', '\'')
                .replace('\u00A0', ' ').toLowerCase(Locale.ROOT);
    }

    static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        var config = ConfigManager.current();
        int y = 6;
        if (config.displayFps) {
            graphics.text(client.font, "FPS : " + client.getFps(), 6, y, 0xFFFFFFFF, true);
            y += client.font.lineHeight + 1;
        }
        if (config.displayPosition) {
            var pos = client.player.blockPosition();
            graphics.text(client.font, "X: " + pos.getX() + ", Y: " + pos.getY() + ", Z: " + pos.getZ(),
                    6, y, 0xFFFFFFFF, true);
        }
        if (config.displayObjectives && !objectiveLines.isEmpty()) {
            int lineHeight = client.font.lineHeight + 1;
            int objectiveY = Math.max(6, (graphics.guiHeight() - objectiveLines.size() * lineHeight + 1) / 2);
            int objectiveX = config.objectivesOnRight ? Math.max(6, graphics.guiWidth() - 6
                    - objectiveLines.stream().mapToInt(client.font::width).max().orElse(0)) : 6;
            for (Component line : objectiveLines) {
                graphics.text(client.font, line, objectiveX, objectiveY, 0xFFFFFFFF, true);
                objectiveY += lineHeight;
            }
        }
    }
}

package com.minermarmot.client.features.display;

import com.minermarmot.client.integration.VersionCheck;

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
import net.minecraft.network.chat.Style;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class DisplayRenderer {
    private static List<Component> objectiveLines = List.of();
    private static List<Component> scrollLines = List.of();
    private static final List<String> SCROLL_ORDER = List.of("emeraude", "ꖋ", "ꖌ", "ꖍ", "ꖎ", "ꖏ",
            "debris", "ꖐ", "ꖑ", "ꖒ", "ꖓ", "ꖔ", "astrallyth", "excellyth", "ꖕ", "ꖖ", "ꖗ", "ꖘ",
            "nebullyth", "ꖙ", "ꖚ", "ꖛ");

    private DisplayRenderer() {}

    public static void initialize() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Main.id("display"),
                (graphics, tracker) -> render(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(DisplayRenderer::updateObjectives);
    }

    static void updateObjectives(Minecraft client) {
        objectiveLines = List.of();
        scrollLines = List.of();
        var config = ConfigManager.current();
        if (!VersionCheck.isCompatible() || client.player == null || client.level == null
                || !config.displayObjectives && !config.displayScrollObjectives) return;
        objectiveLines = objectives(client.player.getMainHandItem());
        if (objectiveLines.isEmpty()) objectiveLines = objectives(client.player.getOffhandItem());
        for (int slot = 0; objectiveLines.isEmpty() && slot < client.player.getInventory().getContainerSize(); slot++) {
            objectiveLines = objectives(client.player.getInventory().getItem(slot));
        }
        if (config.displayScrollObjectives) {
            var active = scrollObjectives(client.player.getInventory());
            if (!active.isEmpty()) {
                Style headingStyle = objectiveLines.isEmpty() ? Style.EMPTY : objectiveLines.getFirst()
                        .visit((style, text) -> text.isBlank() ? Optional.empty() : Optional.of(style), Style.EMPTY).orElse(Style.EMPTY);
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal("Objectifs de parchemin :")
                        .withStyle(headingStyle.applyTo(Style.EMPTY.withColor(ChatFormatting.GOLD))));
                lines.addAll(active);
                scrollLines = List.copyOf(lines);
            }
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

    static List<Component> scrollObjectives(Container inventory) {
        Component[] active = new Component[SCROLL_ORDER.size()];
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !plainText(stack.getHoverName()).stripLeading().startsWith("parchemin")) continue;
            var lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (int line = 0; line + 1 < lore.lines().size(); line++) {
                if (!plainText(lore.lines().get(line)).contains("ᴏʙᴊᴇᴄᴛɪꜰ")) continue;
                Component objective = lore.lines().get(line + 1);
                int order = scrollOrder(plainText(objective));
                if (order >= 0 && active[order] == null) active[order] = objective;
                break;
            }
        }
        List<Component> lines = new ArrayList<>();
        for (Component line : active) if (line != null) lines.add(line);
        return List.copyOf(lines);
    }

    private static int scrollOrder(String text) {
        for (int index = 0; index < SCROLL_ORDER.size(); index++) {
            String target = SCROLL_ORDER.get(index);
            if (target.length() == 1 && text.contains(target)) return index;
        }
        boolean hasGlyph = text.codePoints().anyMatch(code -> switch (Character.getType(code)) {
            case Character.OTHER_LETTER, Character.PRIVATE_USE, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true;
            default -> false;
        });
        if (hasGlyph) return -1;
        String unaccented = text.replace('é', 'e');
        for (int index = 0; index < SCROLL_ORDER.size(); index++) {
            String target = SCROLL_ORDER.get(index);
            if (target.length() > 1 && unaccented.contains(target)) return index;
        }
        return -1;
    }

    private static String plainText(Component text) {
        return ChatFormatting.stripFormatting(text.getString()).replace('\u2019', '\'')
                .replace('\u00A0', ' ').toLowerCase(Locale.ROOT);
    }

    static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!VersionCheck.isCompatible() || client.player == null || client.level == null) {
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
        List<Component> pickaxe = config.displayObjectives ? objectiveLines : List.of();
        List<Component> scrolls = config.displayScrollObjectives ? scrollLines : List.of();
        int gap = !pickaxe.isEmpty() && !scrolls.isEmpty() ? 1 : 0;
        int count = pickaxe.size() + scrolls.size();
        if (count > 0) {
            int lineHeight = client.font.lineHeight + 1;
            int objectiveY = Math.max(6, (graphics.guiHeight() - (count + gap) * lineHeight + 1) / 2);
            int objectiveX = config.objectivesOnRight ? Math.max(6, graphics.guiWidth() - 6
                    - Math.max(pickaxe.stream().mapToInt(client.font::width).max().orElse(0),
                    scrolls.stream().mapToInt(client.font::width).max().orElse(0))) : 6;
            for (int index = 0; index < count; index++) {
                if (index == pickaxe.size()) objectiveY += gap * lineHeight;
                Component line = index < pickaxe.size() ? pickaxe.get(index) : scrolls.get(index - pickaxe.size());
                graphics.text(client.font, line, objectiveX, objectiveY, 0xFFFFFFFF, true);
                objectiveY += lineHeight;
            }
        }
    }
}

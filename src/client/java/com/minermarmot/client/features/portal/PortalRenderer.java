package com.minermarmot.client.features.portal;

import com.minermarmot.client.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public final class PortalRenderer {
    private PortalRenderer() {}

    public static void initialize() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
                net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT, com.minermarmot.Main.id("portal_waypoints"),
                (graphics, tracker) -> render(graphics, 8));
    }

    public static int reservedHeight(Minecraft client) {
        if (client.player == null) return 0;
        int count = Math.min(PortalManager.waypoints(client.player.position()).size(),
                Math.max(1, (client.getWindow().getGuiScaledHeight() - 48) / 40));
        return count == 0 ? 0 : 30 + count * 20;
    }

    public static void render(GuiGraphicsExtractor graphics, int top) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        var waypoints = PortalManager.waypoints(client.player.position());
        if (waypoints.isEmpty()) return;
        for (var waypoint : waypoints) {
            Vec3 marker = waypoint.pattern().marker();
            if (client.player.getLookAngle().dot(marker.subtract(client.player.getEyePosition())) <= 0) continue;
            Vec3 projected = client.gameRenderer.projectPointToScreen(marker);
            if (!Double.isFinite(projected.x) || !Double.isFinite(projected.y)
                    || Math.abs(projected.x) > 1 || Math.abs(projected.y) > 1) continue;
            int x = (int) ((projected.x + 1) * 0.5 * graphics.guiWidth());
            int y = (int) ((1 - projected.y) * 0.5 * graphics.guiHeight());
            graphics.item(waypoint.type().icon.stack(), x - 8, y - 8);
            String text = distance(waypoint, client.player.position());
            int width = client.font.width(text);
            int textX = Math.clamp(x - width / 2, 3, Math.max(3, graphics.guiWidth() - width - 3));
            int textY = Math.max(3, y - client.font.lineHeight - 12);
            graphics.fill(textX - 3, textY - 2, textX + width + 3, textY + client.font.lineHeight + 2, 0xA0101014);
            graphics.text(client.font, text, textX, textY, 0xFFFFFFFF);
        }
        int count = Math.min(waypoints.size(), Math.max(0, (graphics.guiHeight() - top - 34) / 20));
        if (count == 0) return;
        Component title = Component.translatable("minermarmot.portal.topWaypoints", ConfigManager.current().portals.displayedWaypointCount);
        int width = client.font.width(title) + 12;
        for (int i = 0; i < count; i++) {
            var wp = waypoints.get(i);
            width = Math.max(width, client.font.width(wp.type().displayName) + client.font.width(distance(wp, client.player.position())) + 42);
        }
        width = Math.min(width, graphics.guiWidth() / 2);
        int x = graphics.guiWidth() - width - 8;
        graphics.fill(x, top, x + width, top + 28 + count * 20, 0xA0101014);
        graphics.text(client.font, client.font.plainSubstrByWidth(title.getString(), width - 12), x + 6, top + 6, 0xFFFFFFFF);
        int y = top + 22;
        for (int i = 0; i < count; i++) {
            var wp = waypoints.get(i);
            graphics.item(wp.type().icon.stack(), x + 6, y);
            String text = distance(wp, client.player.position());
            int distanceX = x + width - 6 - client.font.width(text);
            String name = client.font.plainSubstrByWidth(wp.type().displayName, Math.max(0, distanceX - x - 32));
            graphics.text(client.font, name, x + 28, y + 4, 0xFFFFFFFF);
            graphics.text(client.font, text, distanceX, y + 4, 0xFFFFFFFF);
            y += 20;
        }
    }

    private static String distance(PortalManager.Waypoint waypoint, Vec3 player) {
        return Math.round(waypoint.pattern().marker().distanceTo(player)) + " m";
    }
}

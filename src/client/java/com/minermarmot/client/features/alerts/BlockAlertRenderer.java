package com.minermarmot.client.features.alerts;

import com.minermarmot.Main;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.portal.PortalRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public final class BlockAlertRenderer {
    private static final RenderType VISIBLE_GLOW_LINES = RenderType.create("minermarmot_visible_block_glow",
            RenderSetup.builder(RenderPipelines.LINES).createRenderSetup());
    private static List<GlowState> glows = List.of();

    private record GlowState(int x, int y, int z, int color, boolean hiddenEdges) {}

    private BlockAlertRenderer() {}

    public static void initialize() {
        LevelExtractionEvents.END_EXTRACTION.register(context -> {
            Minecraft client = Minecraft.getInstance();
            BlockAlertManager.updateVisibility(client);
            var remembered = BlockAlertManager.discoveredWaypoints();
            var settings = ConfigManager.current().alerts;
            var preset = ConfigManager.preset(ConfigManager.current().preset).blockAlerts;
            ArrayList<GlowState> states = new ArrayList<>();
            if (settings.glow && preset.available && preset.glow) {
                for (var waypoint : remembered) {
                    var style = settings.targets.get(waypoint.target().id);
                    var pos = waypoint.pos();
                    if (style != null && style.enabled && style.glow
                            && settings.withinDisplayDistance(pos, client.player.position())
                            && context.level().getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                        states.add(new GlowState(pos.getX(), pos.getY(), pos.getZ(), style.argb(), settings.glowHiddenEdges));
                    }
                }
            }
            glows = List.copyOf(states);
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(BlockAlertRenderer::collect);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Main.id("block_alerts"),
                (graphics, tracker) -> {
                    renderGlowOverlay(graphics);
                    PortalRenderer.render(graphics, renderWaypoints(graphics));
                });
    }

    private static void collect(LevelRenderContext context) {
        List<GlowState> states = glows;
        if (states.isEmpty() || states.getFirst().hiddenEdges) {
            return;
        }
        PoseStack poses = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        poses.pushPose();
        poses.translate(-camera.x, -camera.y, -camera.z);
        float width = Minecraft.getInstance().getWindow().getAppropriateLineWidth();
        RenderType lines = VISIBLE_GLOW_LINES;
        context.submitNodeCollector().submitCustomGeometry(poses, lines, (pose, buffer) -> {
            for (GlowState state : states) {
                box(pose, buffer, state, 0.055f, 0.13f, width * 3);
                box(pose, buffer, state, 0.025f, 0.35f, width * 2);
                box(pose, buffer, state, 0.004f, 1.0f, width);
            }
        });
        poses.popPose();
    }

    private static void box(PoseStack.Pose pose, VertexConsumer buffer, GlowState state, float expand, float alpha, float width) {
        float red = (state.color >> 16 & 255) / 255.0f;
        float green = (state.color >> 8 & 255) / 255.0f;
        float blue = (state.color & 255) / 255.0f;
        for (int corner = 0; corner < 8; corner++) {
            float x = state.x + ((corner & 1) == 0 ? -expand : 1 + expand);
            float y = state.y + ((corner & 2) == 0 ? -expand : 1 + expand);
            float z = state.z + ((corner & 4) == 0 ? -expand : 1 + expand);
            for (int axis = 0; axis < 3; axis++) {
                if ((corner & (1 << axis)) != 0) {
                    continue;
                }
                Vector3f normal = new Vector3f(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
                buffer.addVertex(pose, x, y, z).setColor(red, green, blue, alpha).setNormal(pose, normal).setLineWidth(width);
                buffer.addVertex(pose, x + normal.x * (1 + 2 * expand), y + normal.y * (1 + 2 * expand),
                        z + normal.z * (1 + 2 * expand)).setColor(red, green, blue, alpha).setNormal(pose, normal).setLineWidth(width);
            }
        }
    }

    // HUD geometry is drawn after shader composition, so hidden edges survive world post-processing.
    private static void renderGlowOverlay(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || glows.isEmpty()) return;
        var camera = client.gameRenderer.mainCamera();
        Matrix4f projection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Vec3 origin = camera.position();
        float pixel = (float) graphics.guiWidth() / client.getWindow().getWidth();
        graphics.enableScissor(0, 0, graphics.guiWidth(), graphics.guiHeight());
        for (GlowState state : glows) {
            if (!state.hiddenEdges) continue;
            for (int corner = 0; corner < 8; corner++) {
                float x = (float) (state.x + ((corner & 1) == 0 ? -0.004 : 1.004) - origin.x);
                float y = (float) (state.y + ((corner & 2) == 0 ? -0.004 : 1.004) - origin.y);
                float z = (float) (state.z + ((corner & 4) == 0 ? -0.004 : 1.004) - origin.z);
                for (int axis = 0; axis < 3; axis++) {
                    if ((corner & (1 << axis)) != 0) continue;
                    Vector4f start = projection.transform(new Vector4f(x, y, z, 1));
                    Vector4f end = projection.transform(new Vector4f(x + (axis == 0 ? 1.008f : 0),
                            y + (axis == 1 ? 1.008f : 0), z + (axis == 2 ? 1.008f : 0), 1));
                    if (!clipGlowEdge(start, end)) continue;
                    float screenX = (start.x / start.w + 1) * 0.5f * graphics.guiWidth();
                    float screenY = (1 - start.y / start.w) * 0.5f * graphics.guiHeight();
                    float dx = (end.x / end.w + 1) * 0.5f * graphics.guiWidth() - screenX;
                    float dy = (1 - end.y / end.w) * 0.5f * graphics.guiHeight() - screenY;
                    float length = (float) Math.hypot(dx, dy);
                    if (length < 0.01f) continue;
                    for (int layer = 3; layer >= 1; layer--) {
                        int alpha = Math.round((state.color >>> 24) * (layer == 3 ? 0.13f : layer == 2 ? 0.35f : 1));
                        graphics.pose().pushMatrix();
                        graphics.pose().translate(screenX, screenY).rotate((float) Math.atan2(dy, dx))
                                .scale(length, layer * pixel * 0.5f);
                        graphics.fill(0, -1, 1, 1, alpha << 24 | state.color & 0xFFFFFF);
                        graphics.pose().popMatrix();
                    }
                }
            }
        }
        graphics.disableScissor();
    }

    // Clip in homogeneous coordinates before dividing by w, including edges crossing the camera plane.
    static boolean clipGlowEdge(Vector4f start, Vector4f end) {
        if (!start.isFinite() || !end.isFinite()) return false;
        for (int plane = 0; plane < 5; plane++) {
            float a = switch (plane) {
                case 0 -> start.w - 0.001f;
                case 1 -> start.w + start.x;
                case 2 -> start.w - start.x;
                case 3 -> start.w + start.y;
                default -> start.w - start.y;
            };
            float b = switch (plane) {
                case 0 -> end.w - 0.001f;
                case 1 -> end.w + end.x;
                case 2 -> end.w - end.x;
                case 3 -> end.w + end.y;
                default -> end.w - end.y;
            };
            if (a < 0 && b < 0) return false;
            if (a < 0) start.lerp(end, a / (a - b));
            else if (b < 0) end.lerp(start, b / (b - a));
        }
        return true;
    }

    private static int renderWaypoints(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return 8;
        }
        var preset = ConfigManager.preset(ConfigManager.current().preset).blockAlerts;
        var settings = ConfigManager.current().alerts;
        if (!preset.available || !preset.waypoints || !settings.waypoints) {
            return 8;
        }
        var waypoints = BlockAlertManager.waypointsInRange(client.player.position());
        for (var waypoint : waypoints) {
            if (!BlockAlertManager.onScreen(client, waypoint.pos())) {
                continue;
            }
            Vec3 projected = client.gameRenderer.projectPointToScreen(Vec3.atCenterOf(waypoint.pos()));
            int x = (int) ((projected.x + 1) * 0.5 * graphics.guiWidth());
            int y = (int) ((1 - projected.y) * 0.5 * graphics.guiHeight());
            int color = settings.targets.get(waypoint.target().id).argb();
            graphics.fill(x - 3, y - 3, x + 3, y + 3, color);
            String distance = distance(waypoint, client.player.position());
            int textWidth = client.font.width(distance);
            int textX = Math.clamp(x - textWidth / 2, 3, graphics.guiWidth() - textWidth - 3);
            int textY = Math.max(3, y - client.font.lineHeight - 6);
            graphics.fill(textX - 3, textY - 2, textX + textWidth + 3, textY + client.font.lineHeight + 2, 0xA0101014);
            graphics.text(client.font, distance, textX, textY, 0xFFFFFFFF);
        }
        if (waypoints.isEmpty() || settings.displayedWaypointCount == 0) {
            return 8;
        }
        int padding = 6;
        int rowHeight = client.font.lineHeight + 5;
        int maxRows = Math.max(0, (graphics.guiHeight() - PortalRenderer.reservedHeight(client) - 16
                - padding * 2 - client.font.lineHeight * 2 - 12) / rowHeight);
        int count = Math.min(waypoints.size(), Math.min(settings.displayedWaypointCount, maxRows));
        if (count == 0) {
            return 8;
        }
        int remaining = waypoints.size() - count;
        Component title = Component.translatable("minermarmot.alerts.topWaypoints", settings.displayedWaypointCount);
        Component footer = Component.translatable(remaining == 1 ? "minermarmot.alerts.additionalWaypoint"
                : "minermarmot.alerts.additionalWaypoints", remaining);
        int panelWidth = client.font.width(title) + padding * 2;
        if (remaining > 0) {
            panelWidth = Math.max(panelWidth, client.font.width(footer) + padding * 2);
        }
        for (int index = 0; index < count; index++) {
            var waypoint = waypoints.get(index);
            panelWidth = Math.max(panelWidth, client.font.width(waypoint.target().displayName)
                    + client.font.width(distance(waypoint, client.player.position())) + padding * 2 + 22);
        }
        panelWidth = Math.min(panelWidth, graphics.guiWidth() / 2);
        int x = graphics.guiWidth() - panelWidth - 8;
        int y = 8;
        int panelHeight = padding * 2 + client.font.lineHeight + 8 + count * rowHeight
                + (remaining > 0 ? client.font.lineHeight + 4 : 0);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xA0101014);
        graphics.text(client.font, title, x + padding, y + padding, 0xFFFFFFFF);
        y += padding + client.font.lineHeight + 4;
        graphics.fill(x + padding, y, x + panelWidth - padding, y + 1, 0x30FFFFFF);
        y += 4;
        for (int index = 0; index < count; index++) {
            var waypoint = waypoints.get(index);
            int cubeX = x + padding;
            int color = settings.targets.get(waypoint.target().id).argb();
            graphics.fill(cubeX, y, cubeX + 8, y + 8, color);
            graphics.fill(cubeX + 6, y + 2, cubeX + 8, y + 8, 0x66000000);
            graphics.fill(cubeX, y, cubeX + 8, y + 2, 0x44FFFFFF);
            String distance = distance(waypoint, client.player.position());
            int distanceX = x + panelWidth - padding - client.font.width(distance);
            String name = client.font.plainSubstrByWidth(waypoint.target().displayName, Math.max(0, distanceX - cubeX - 22));
            graphics.text(client.font, name, cubeX + 14, y, 0xFFFFFFFF);
            graphics.text(client.font, distance, distanceX, y, 0xFFFFFFFF);
            y += rowHeight;
        }
        if (remaining > 0) {
            graphics.text(client.font, client.font.plainSubstrByWidth(footer.getString(), panelWidth - padding * 2),
                    x + padding, y + 4, 0xFFBBBBBB);
        }
        return 8 + panelHeight + 6;
    }

    private static String distance(BlockAlertManager.Waypoint waypoint, Vec3 player) {
        return Math.round(Math.sqrt(waypoint.pos().distToCenterSqr(player))) + " m";
    }
}

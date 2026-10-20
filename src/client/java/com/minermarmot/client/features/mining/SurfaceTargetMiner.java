package com.minermarmot.client.features.mining;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.alerts.BlockAlertManager;
import com.minermarmot.client.features.alerts.BlockAlertTarget;
import com.minermarmot.client.features.alerts.BlockAlertsConfig;
import com.minermarmot.client.features.alerts.BlockAlertsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

public final class SurfaceTargetMiner {
    public BlockHitResult findTarget(Minecraft client, BlockPos preferred) {
        BlockAlertsPreset preset = BlockAlertManager.surfacePreset();
        if (preset == null) return null;
        BlockAlertsConfig settings = ConfigManager.current().alerts;
        Set<String> enabled = new HashSet<>();
        int vanillaRadius = 0;
        int otherRadius = 0;
        for (BlockAlertTarget target : preset.targets()) {
            var style = settings.targets.get(target.id);
            if (style == null || !style.enabled || !style.surfaceMine) continue;
            enabled.add(target.id);
            if (target.isVanillaOre()) vanillaRadius = settings.vanillaOreScanRadius;
            else otherRadius = settings.otherBlockScanRadius;
        }
        int radius = Math.max(vanillaRadius, otherRadius);
        if (radius == 0) return null;
        BlockPos center = client.player.blockPosition();
        BlockHitResult crosshair = MiningTargets.crosshair(client);
        if (preferred != null && client.level.getChunkSource().hasChunk(preferred.getX() >> 4, preferred.getZ() >> 4)
                && matches(client, preferred, client.level.getBlockState(preferred), preset, settings, enabled, center, crosshair)) {
            BlockHitResult hit = MiningTargets.visibleHit(client, preferred);
            if (hit != null) {
                return hit;
            }
        }
        var eye = client.player.getEyePosition();
        double reach = client.player.blockInteractionRange();
        BlockPos min = BlockPos.containing(eye.x - reach, eye.y - reach, eye.z - reach);
        BlockPos max = BlockPos.containing(eye.x + reach, eye.y + reach, eye.z + reach);
        int minX = Math.max(min.getX(), center.getX() - radius);
        int maxX = Math.min(max.getX(), center.getX() + radius);
        int minZ = Math.max(min.getZ(), center.getZ() - radius);
        int maxZ = Math.min(max.getZ(), center.getZ() + radius);
        BlockHitResult closest = null;
        double bestDistance = Double.MAX_VALUE;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!client.level.getChunkSource().hasChunk(chunkX, chunkZ)) continue;
                var chunk = client.level.getChunk(chunkX, chunkZ);
                var sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    int baseY = chunk.getSectionYFromSectionIndex(index) * 16;
                    if (baseY > Math.min(max.getY(), center.getY() + radius)
                            || baseY + 15 < Math.max(min.getY(), center.getY() - radius)) continue;
                    var section = sections[index];
                    if (section.hasOnlyAir()) continue;
                    int sectionRadius = 0;
                    if (vanillaRadius > 0 && section.maybeHas(state -> {
                        var target = target(state, preset, enabled);
                        return target != null && target.isVanillaOre();
                    })) sectionRadius = vanillaRadius;
                    if (otherRadius > sectionRadius && section.maybeHas(state -> {
                        var target = target(state, preset, enabled);
                        return target != null && !target.isVanillaOre();
                    })) sectionRadius = otherRadius;
                    if (sectionRadius == 0) continue;
                    BlockPos from = new BlockPos(Math.max(minX, Math.max(chunkX * 16, center.getX() - sectionRadius)),
                            Math.max(baseY, Math.max(min.getY(), center.getY() - sectionRadius)),
                            Math.max(minZ, Math.max(chunkZ * 16, center.getZ() - sectionRadius)));
                    BlockPos to = new BlockPos(Math.min(maxX, Math.min(chunkX * 16 + 15, center.getX() + sectionRadius)),
                            Math.min(baseY + 15, Math.min(max.getY(), center.getY() + sectionRadius)),
                            Math.min(maxZ, Math.min(chunkZ * 16 + 15, center.getZ() + sectionRadius)));
                    if (from.getX() > to.getX() || from.getY() > to.getY() || from.getZ() > to.getZ()) continue;
                    for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
                        double minimumDistance = new AABB(pos).distanceToSqr(eye);
                        if (minimumDistance >= reach * reach
                                || !matches(client, pos, section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15),
                                        preset, settings, enabled, center, crosshair)) continue;
                        BlockHitResult hit = MiningTargets.visibleHit(client, pos);
                        if (hit != null) {
                            double distance = eye.distanceToSqr(hit.getLocation());
                            if (distance < bestDistance) {
                                closest = hit;
                                bestDistance = distance;
                            }
                        }
                    }
                }
            }
        }
        return closest;
    }

    private static BlockAlertTarget target(BlockState state, BlockAlertsPreset preset, Set<String> enabled) {
        BlockAlertTarget target = preset.match(state);
        return target != null && enabled.contains(target.id) ? target : null;
    }

    private boolean matches(Minecraft client, BlockPos pos, BlockState state, BlockAlertsPreset preset,
                            BlockAlertsConfig settings, Set<String> enabled, BlockPos center, BlockHitResult crosshair) {
        BlockAlertTarget target = target(state, preset, enabled);
        if (target == null || !BlockAlertManager.withinScanRadius(pos, center,
                target.isVanillaOre() ? settings.vanillaOreScanRadius : settings.otherBlockScanRadius)) return false;
        return allowedSurface(pos, client.player.position(), client.player.getBoundingBox().maxY,
                client.player.getYRot(), client.player.getAbilities().flying, crosshair);
    }

    static boolean allowedSurface(BlockPos pos, Vec3 feet, double topY, float yaw, boolean flying, BlockHitResult crosshair) {
        if (crosshair != null && crosshair.getDirection().getAxis().isHorizontal()) {
            var axis = crosshair.getDirection().getAxis();
            BlockPos wall = crosshair.getBlockPos();
            if (axis.choose(pos.getX(), pos.getY(), pos.getZ()) == axis.choose(wall.getX(), wall.getY(), wall.getZ())) return false;
        }
        double dx = pos.getX() + 0.5 - feet.x;
        double dz = pos.getZ() + 0.5 - feet.z;
        if (pos.getY() < feet.y) {
            double behind = dx * Math.sin(Math.toRadians(yaw)) - dz * Math.cos(Math.toRadians(yaw));
            return flying || behind >= 3;
        }
        return pos.getY() >= Math.ceil(topY) || !WorldBorderManager.isFrontWall(dx, dz, yaw);
    }
}

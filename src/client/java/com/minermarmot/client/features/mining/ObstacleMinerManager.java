package com.minermarmot.client.features.mining;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class ObstacleMinerManager {
    public BlockHitResult findTarget(Minecraft client, Vec3 forward, BlockPos preferred) {
        AABB passage = client.player.getBoundingBox()
                .expandTowards(forward.x, 0, forward.z).deflate(0.001);
        if (preferred != null && obstructs(client, preferred, passage)) {
            BlockHitResult hit = MiningTargets.visibleHit(client, preferred);
            if (hit != null) {
                return hit;
            }
        }
        BlockHitResult closest = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(passage.minX, passage.minY, passage.minZ),
                BlockPos.containing(passage.maxX, passage.maxY, passage.maxZ))) {
            if (!obstructs(client, pos, passage)) {
                continue;
            }
            BlockHitResult hit = MiningTargets.visibleHit(client, pos);
            if (hit != null) {
                double distance = client.player.getEyePosition().distanceToSqr(hit.getLocation());
                if (distance < bestDistance) {
                    closest = hit;
                    bestDistance = distance;
                }
            }
        }
        return closest;
    }

    private boolean obstructs(Minecraft client, BlockPos pos, AABB passage) {
        if (!client.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        var shape = client.level.getBlockState(pos).getCollisionShape(client.level, pos);
        for (AABB box : shape.toAabbs()) {
            if (box.move(pos).intersects(passage)) {
                return true;
            }
        }
        return false;
    }
}

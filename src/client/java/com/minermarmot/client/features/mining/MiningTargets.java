package com.minermarmot.client.features.mining;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class MiningTargets {
    private MiningTargets() {}

    public static BlockHitResult crosshair(Minecraft client) {
        HitResult hit = client.player.raycastHitResult(1.0f, client.player);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit;
        }
        return null;
    }

    static boolean canMine(Minecraft client, BlockPos pos) {
        return client.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                && client.level.getWorldBorder().isWithinBounds(pos)
                && !client.level.getBlockState(pos).isAir()
                && client.level.getBlockState(pos).getDestroySpeed(client.level, pos) >= 0
                && client.player.isWithinBlockInteractionRange(pos, 0);
    }

    public static BlockHitResult visibleHit(Minecraft client, BlockPos pos) {
        if (!canMine(client, pos)) {
            return null;
        }
        var shape = client.level.getBlockState(pos).getShape(client.level, pos);
        if (shape.isEmpty()) {
            return null;
        }
        if (client.level.getBlockState(pos).isSolidRender() && Block.isShapeFullBlock(shape)
                && !hasExposedFace(client, pos)) return null;
        AABB bounds = shape.bounds().move(pos);
        Vec3 center = bounds.getCenter();
        BlockHitResult hit = ray(client, pos, center);
        if (hit != null) {
            return hit;
        }
        for (Direction face : Direction.values()) {
            Vec3 point = switch (face) {
                case DOWN -> new Vec3(center.x, bounds.minY + 0.001, center.z);
                case UP -> new Vec3(center.x, bounds.maxY - 0.001, center.z);
                case NORTH -> new Vec3(center.x, center.y, bounds.minZ + 0.001);
                case SOUTH -> new Vec3(center.x, center.y, bounds.maxZ - 0.001);
                case WEST -> new Vec3(bounds.minX + 0.001, center.y, center.z);
                case EAST -> new Vec3(bounds.maxX - 0.001, center.y, center.z);
            };
            hit = ray(client, pos, point);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static boolean hasExposedFace(Minecraft client, BlockPos pos) {
        Vec3 eye = client.player.getEyePosition();
        if (new AABB(pos).contains(eye)) return true;
        for (Direction face : Direction.values()) {
            boolean facesEye = switch (face) {
                case WEST -> eye.x <= pos.getX();
                case EAST -> eye.x >= pos.getX() + 1;
                case DOWN -> eye.y <= pos.getY();
                case UP -> eye.y >= pos.getY() + 1;
                case NORTH -> eye.z <= pos.getZ();
                case SOUTH -> eye.z >= pos.getZ() + 1;
            };
            if (facesEye && !client.level.getBlockState(pos.relative(face)).isSolidRender()) return true;
        }
        return false;
    }

    private static BlockHitResult ray(Minecraft client, BlockPos pos, Vec3 point) {
        Vec3 eye = client.player.getEyePosition();
        BlockHitResult hit = client.level.clip(new ClipContext(eye, point,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        double reach = client.player.blockInteractionRange();
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                && eye.distanceToSqr(hit.getLocation()) <= reach * reach ? hit : null;
    }
}

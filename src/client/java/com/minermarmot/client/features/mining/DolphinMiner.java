package com.minermarmot.client.features.mining;

import com.minermarmot.client.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.Vec2;

final class DolphinMiner {
    private final DolphinPath path;
    private final ObstacleMinerManager obstacles = new ObstacleMinerManager();
    private final DolphinCamera camera = new DolphinCamera();
    private float aimPitch;
    private Float returningPitch;
    private boolean active;
    private BlockPos exitBlock;
    private double highestY;
    private int minY;
    private int maxY;
    private boolean forwardBoost;
    private Float originalPitch;
    private boolean originalFlying;

    DolphinMiner(WorldBorderManager border) {
        path = new DolphinPath(border);
    }

    boolean tick(Minecraft client) {
        var player = client.player;
        var settings = ConfigManager.current().mining;
        minY = Math.max(settings.dolphinMinY, client.level.getMinY() + 1);
        maxY = Math.min(settings.dolphinMaxY, (int) Math.floor(client.level.getMaxY() - player.getBbHeight()));
        if (minY >= maxY || player.getY() <= client.level.getMinY()
                || player.isInWater() || player.isInLava()) return false;
        if (!active) {
            camera.reset();
            returningPitch = null;
            path.start(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), minY, maxY);
            originalPitch = player.getXRot();
            originalFlying = player.getAbilities().flying;
            if (originalFlying) {
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            }
            active = true;
            highestY = player.getY();
        }
        var bounds = client.level.getWorldBorder();
        if (minY != settings.dolphinMinY || maxY != settings.dolphinMaxY) {
            settings = settings.copy();
            settings.dolphinMinY = minY;
            settings.dolphinMaxY = maxY;
        }
        if (path.phase() == DolphinPath.Phase.ASCENDING) {
            highestY = Math.max(highestY, player.getY());
            if (exitBlock != null && player.getY() < highestY - 3) {
                path.recoverFromUpperFall(player.getX(), player.getY(), player.getZ());
                exitBlock = null;
            }
        }
        boolean ascending = path.phase() == DolphinPath.Phase.ASCENDING;
        BlockHitResult vertical = path.horizontal() ? null : verticalTarget(client);
        boolean endVertical = false;
        if (vertical != null) {
            var state = client.level.getBlockState(vertical.getBlockPos());
            endVertical = !ascending && state.is(Blocks.BEDROCK);
            if (!state.getFluidState().isEmpty()
                    || state.getDestroySpeed(client.level, vertical.getBlockPos()) < 0 && !endVertical) return false;
            if (ascending && exitBlock == null && openAbove(client, vertical.getBlockPos()))
                exitBlock = vertical.getBlockPos().immutable();
        }
        // Clear the last block and climb above its roof before committing to the horizontal exit.
        if (ascending && exitBlock != null && client.level.getBlockState(exitBlock).isAir()
                && vertical == null && player.getY() >= exitBlock.getY() + 1.1) endVertical = true;
        boolean ready = path.update(player.getX(), player.getY(), player.getZ(), endVertical,
                bounds.getMinX(), bounds.getMaxX(), bounds.getMinZ(), bounds.getMaxZ(),
                MiningManager.enabled(MiningFeature.WORLD_BORDER) ? settings.worldBorderDistance : 0,
                settings);
        if (ascending && path.horizontal()) forwardBoost = true;
        if (!ascending || path.phase() != DolphinPath.Phase.ASCENDING) {
            exitBlock = null;
            highestY = player.getY();
        }
        aimPitch = findPitch(client);
        if (!path.horizontal()) player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
        return ready && safePassage(client);
    }

    private float findPitch(Minecraft client) {
        if (path.phase() != DolphinPath.Phase.ASCENDING) return path.pitch();
        // Reacquire a visible upper face during ascent; forward passages retain horizontal aiming.
        for (float pitch : new float[]{-90, -85, -80, -75, -70, -65, -60, -45, -30, -15}) {
            Vec3 eye = client.player.getEyePosition();
            var hit = client.level.clip(new ClipContext(eye,
                    eye.add(Vec3.directionFromRotation(pitch, path.yaw()).scale(client.player.blockInteractionRange())),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && MiningTargets.canMine(client, hit.getBlockPos())
                    && client.level.getFluidState(hit.getBlockPos()).isEmpty()) {
                return pitch;
            }
        }
        return path.pitch();
    }

    private BlockHitResult verticalTarget(Minecraft client) {
        if (path.phase() == DolphinPath.Phase.ASCENDING && exitBlock != null
                && !client.level.getBlockState(exitBlock).isAir()) return MiningTargets.visibleHit(client, exitBlock);
        Vec3 eye = client.player.getEyePosition();
        boolean up = path.phase() == DolphinPath.Phase.ASCENDING;
        double endY = up ? Math.min(eye.y + client.player.blockInteractionRange(), maxY + client.player.getBbHeight())
                : Math.max(eye.y - client.player.blockInteractionRange(), minY);
        // A player below the lower limit must escape, never mine a ray pointing back upwards.
        if (!up && endY >= eye.y || up && endY <= eye.y) return null;
        var hit = client.level.clip(new ClipContext(eye, new Vec3(eye.x, endY, eye.z),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, client.player));
        return hit.getType() == HitResult.Type.BLOCK && (up || hit.getBlockPos().getY() >= minY) ? hit : null;
    }

    private boolean safePassage(Minecraft client) {
        var player = client.player;
        Vec3 direction = path.horizontal() ? Vec3.directionFromRotation(0, path.yaw())
                : new Vec3(0, path.phase() == DolphinPath.Phase.ASCENDING ? 1 : -1, 0);
        var box = player.getBoundingBox().expandTowards(direction.scale(0.5)).deflate(0.001);
        if (!client.level.getWorldBorder().isWithinBounds(box)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (!client.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    || !client.level.getFluidState(pos).isEmpty()) return false;
            var state = client.level.getBlockState(pos);
            if (state.getDestroySpeed(client.level, pos) < 0 && !state.getCollisionShape(client.level, pos).isEmpty()
                    && !(direction.y < 0 && state.is(Blocks.BEDROCK)))
                return false;
        }
        return true;
    }

    BlockHitResult findTarget(Minecraft client, BlockPos preferred) {
        if (path.horizontal()) {
            BlockHitResult obstacle = obstacles.findTarget(client, Vec3.directionFromRotation(0, path.yaw()), preferred);
            if (obstacle != null) return obstacle;
            BlockHitResult ahead = MiningTargets.crosshair(client);
            return ahead != null && MiningTargets.canMine(client, ahead.getBlockPos()) ? ahead : null;
        }
        BlockHitResult hit = verticalTarget(client);
        if (hit == null) return null;
        BlockPos pos = hit.getBlockPos();
        // Only the current shaft is mined, never a distant floor below the lower limit.
        return pos.getX() == (int) Math.floor(path.centerX())
                && pos.getZ() == (int) Math.floor(path.centerZ())
                && MiningTargets.canMine(client, pos) ? hit : null;
    }

    void blockBroken(Minecraft client, BlockPos pos) {
        path.blockBroken();
        if (path.phase() == DolphinPath.Phase.ASCENDING)
            exitBlock = openAbove(client, pos) ? pos.immutable() : null;
    }

    private boolean openAbove(Minecraft client, BlockPos pos) {
        return DolphinPath.openAbove(offset -> {
            BlockPos above = pos.above(offset);
            return client.level.isInsideBuildHeight(above.getY())
                    && client.level.getChunkSource().hasChunk(above.getX() >> 4, above.getZ() >> 4)
                    && client.level.getBlockState(above).isAir();
        });
    }

    void finishMovement(Minecraft client) {
        var player = client.player;
        boolean horizontal = path.horizontal();
        boolean arrived = horizontal && path.reachedDestination(player.getX(), player.getZ());
        boolean alongZ = WorldBorderManager.stepX(path.yaw()) == 0;
        double x = !horizontal || alongZ ? path.centerX() : arrived ? path.destinationX() : player.getX();
        double z = !horizontal || !alongZ ? path.centerZ() : arrived ? path.destinationZ() : player.getZ();
        var box = player.getBoundingBox().move(x - player.getX(), 0, z - player.getZ());
        if (client.level.getWorldBorder().isWithinBounds(box) && client.level.noCollision(player, box)) {
            player.setPos(x, player.getY(), z);
            Vec3 velocity = player.getDeltaMovement();
            player.setDeltaMovement(!horizontal || arrived || alongZ ? 0 : velocity.x, velocity.y,
                    !horizontal || arrived || !alongZ ? 0 : velocity.z);
        }
        // Vanilla has saved the old angles here; both rendering and movement packets see the gradual turn.
        turnCamera(client, path.yaw(), aimPitch);
    }

    private void turnCamera(Minecraft client, float yaw, float pitch) {
        camera.tick(client.player.getYRot(), client.player.getXRot(), yaw, pitch);
        client.player.setYRot(camera.yaw());
        client.player.setXRot(camera.pitch());
    }

    void finishCameraRestore(Minecraft client) {
        if (returningPitch == null) return;
        if (!camera.matches(client.player.getYRot(), client.player.getXRot())) {
            cancelCameraRestore(); // Manual camera input or a server correction takes priority after stopping.
            return;
        }
        if (Math.abs(client.player.getXRot() - returningPitch) < 0.001f) {
            cancelCameraRestore();
            return;
        }
        turnCamera(client, client.player.getYRot(), returningPitch);
    }

    void cancelCameraRestore() { returningPitch = null; }
    boolean smoothsCamera() { return active || returningPitch != null; }
    boolean waitsForAim() { return active && path.boosts() && !camera.ready(); }
    Vec2 movementInput(float cameraYaw) { return forwardInput(path.yaw(), cameraYaw); }

    static Vec2 forwardInput(float heading, float cameraYaw) {
        double angle = Math.toRadians(heading - cameraYaw);
        return new Vec2((float) -Math.sin(angle), (float) Math.cos(angle));
    }

    boolean active() { return active; }
    boolean moves() { return active && path.horizontal(); }
    boolean boosts() { return active && path.boosts() && camera.ready(); }

    boolean takeForwardBoost() {
        boolean boost = forwardBoost;
        forwardBoost = false;
        return boost;
    }

    void reset(Minecraft client, boolean restoreState) {
        if (active && restoreState && originalPitch != null && client.player != null) {
            returningPitch = originalPitch;
            if (originalFlying && client.player.getAbilities().mayfly) {
                client.player.getAbilities().flying = true;
                client.player.onUpdateAbilities();
            }
        } else {
            cancelCameraRestore();
            camera.reset();
        }
        active = false;
        exitBlock = null;
        forwardBoost = false;
        originalPitch = null;
        originalFlying = false;
    }
}

package com.minermarmot.client.features.mining;

import com.google.gson.Gson;
import com.minermarmot.client.config.ClientConfig;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.config.MiningPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public final class MiningSelfTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        com.minermarmot.client.features.alerts.BlockAlertsSelfTest.run();
        com.minermarmot.client.features.tnt.TntSelfTest.run();
        com.minermarmot.client.features.autocraft.AutocraftSelfTest.run();
        com.minermarmot.client.features.skins.SkinsSelfTest.run();
        com.minermarmot.client.config.ConfigManagerSelfTest.run();
        surfaceRules();
        assert !ConfigManager.parsePreset("{\"name\":\"Legacy\"}").autoBoostBlockUpward;
        for (boolean blockUpward : new boolean[]{true, false}) {
            var boostPreset = ConfigManager.parsePreset("{\"name\":\"Boost\",\"autoBoostBlockUpward\":" + blockUpward + "}");
            assert boostPreset.autoBoostBlockUpward == blockUpward;
            assert ConfigManager.parsePreset(new Gson().toJson(boostPreset)).autoBoostBlockUpward == blockUpward;
        }
        assert !new Gson().toJsonTree(new MiningConfig()).getAsJsonObject().has("autoBoostLockHeight");
        var distances = new java.util.ArrayList<>(MiningConfig.BORDER_DISTANCES);
        distances.addAll(java.util.List.of(1, 4, 38));
        for (int distance : distances) {
            if (distance == 0) {
                continue;
            }
            for (float yaw : new float[]{0, 90, 180, 270}) {
                WorldBorderManager border = new WorldBorderManager();
                double x = yaw == 90 ? -95 : yaw == 270 ? 95 : 0;
                double z = yaw == 180 ? -95 : yaw == 0 ? 95 : 0;
                assert border.update(x, z, yaw, -100, 100, -100, 100, distance) == WorldBorderManager.Action.FIRST_TURN;
                float lateral = WorldBorderManager.cardinalYaw(yaw + 90);
                assert border.yaw() == lateral;
                double endX = x + WorldBorderManager.stepX(lateral) * distance;
                double endZ = z + WorldBorderManager.stepZ(lateral) * distance;
                assert border.update(x, z, yaw, -100, 100, -100, 100, distance) == WorldBorderManager.Action.NONE;
                assert border.update(endX, endZ, yaw, -100, 100, -100, 100, distance) == WorldBorderManager.Action.SECOND_TURN;
                assert border.yaw() == WorldBorderManager.cardinalYaw(yaw + 180);
                assert border.endX() == endX && border.endZ() == endZ;
                border.reset();
                border.reset();
                assert border.update(0, 0, border.yaw(), -100, 100, -100, 100, 0) == WorldBorderManager.Action.NONE;
                assert !border.isTurning() : "Stopping must cancel the active maneuver";
                double oppositeX = yaw == 90 ? 95 : yaw == 270 ? -95 : endX;
                double oppositeZ = yaw == 180 ? 95 : yaw == 0 ? -95 : endZ;
                assert border.update(oppositeX, oppositeZ, border.yaw(), -100, 100, -100, 100, distance)
                        == WorldBorderManager.Action.FIRST_TURN;
                assert border.yaw() == lateral : "Successive turns must alternate right and left";
                assert border.update(oppositeX, oppositeZ, border.yaw(), -100, 100, -100, 100, 0) == WorldBorderManager.Action.NONE;
                assert !border.isTurning();
                assert border.update(oppositeX, oppositeZ, WorldBorderManager.cardinalYaw(yaw + 180),
                        -100, 100, -100, 100, distance) == WorldBorderManager.Action.FIRST_TURN;
                assert border.yaw() == lateral : "Cancelling a lateral leg must preserve the pending turn direction";
            }
        }
        WorldBorderManager border = new WorldBorderManager();
        assert border.update(0, 94.99, 0, -100, 100, -100, 100, 3) == WorldBorderManager.Action.NONE;
        assert border.update(-99, 95, 0, -100, 100, -100, 100, 3) == WorldBorderManager.Action.BLOCKED;
        assert WorldBorderManager.cardinalYaw(-91) == 270;
        assert WorldBorderManager.cardinalYaw(721) == 0;
        assert WorldBorderManager.isFrontWall(0, 2, 0);
        assert !WorldBorderManager.isFrontWall(2, 0, 0);
        assert !WorldBorderManager.isFrontWall(0, -2, 0);
        WorldBorderManager diagonal = new WorldBorderManager();
        assert diagonal.update(0, 96, 30, -100, 100, -100, 100, 3) == WorldBorderManager.Action.FIRST_TURN;
        assert diagonal.yaw() == 120 : "A border turn must remain exactly 90 degrees without camera lock";
        double diagonalX = -Math.sin(Math.toRadians(120)) * 3;
        double diagonalZ = 96 + Math.cos(Math.toRadians(120)) * 3;
        assert diagonal.update(diagonalX, diagonalZ, 120, -100, 100, -100, 100, 3) == WorldBorderManager.Action.SECOND_TURN;
        assert diagonal.yaw() == 210;

        MiningPreset preset = ConfigManager.parsePreset("""
                {"name":"Restricted","features":{"autoMine":false,"autoSprint":true},
                 "defaults":{"enabled":["autoMine","autoSprint"],"worldBorderDistance":7},
                 "monitoredBlocks":["minecraft:diamond_ore"]}
                """);
        assert !preset.allows(MiningFeature.AUTO_MINE);
        assert !preset.allows(MiningFeature.AUTO_BOOST);
        assert preset.defaults.enabled.equals(EnumSet.of(MiningFeature.AUTO_SPRINT));
        ClientConfig config = new ClientConfig();
        config.applyPreset("restricted", preset);
        config.mining.enabled.add(MiningFeature.AUTO_MINE);
        config.alerts.targets.put("minecraft:stone", new com.minermarmot.client.features.alerts.BlockAlertSettings());
        config.validate(preset);
        assert !config.mining.enabled.contains(MiningFeature.AUTO_MINE);
        assert config.mining.worldBorderDistance == 0;
        assert !config.alerts.targets.containsKey("minecraft:stone");
        ClientConfig draft = config.copy();
        draft.mining.enabled.clear();
        draft.alerts.targets.clear();
        assert config.mining.enabled.contains(MiningFeature.AUTO_SPRINT);
        assert config.alerts.targets.get("minecraft:diamond_ore").enabled;
        Gson gson = new Gson();
        ClientConfig restored = gson.fromJson(gson.toJson(config), ClientConfig.class);
        restored.validate(preset);
        assert restored.mining.enabled.equals(config.mining.enabled);
        assert restored.alerts.targets.get("minecraft:diamond_ore").enabled;
        MiningPreset custom = ConfigManager.parsePreset("""
                {"name":"Custom","features":{"worldBorder":true},"defaults":{"worldBorderDistance":4}}
                """);
        for (int distance : new int[]{0, 1, 4, 38, Integer.MAX_VALUE}) {
            custom.defaults.worldBorderDistance = distance;
            custom.validate();
            ClientConfig customConfig = new ClientConfig();
            customConfig.applyPreset("custom", custom);
            ClientConfig customCopy = customConfig.copy();
            ClientConfig customRestored = ConfigManager.parseConfig(gson.toJson(customCopy));
            customRestored.validate(custom);
            assert customRestored.mining.worldBorderDistance == distance;
        }
        assert new WorldBorderManager().update(0, 95, 0, -100, 100, -100, 100, Integer.MAX_VALUE)
                == WorldBorderManager.Action.BLOCKED;
        rejects("{\"name\":\"Bad\",\"defaults\":{\"worldBorderDistance\":-1}}");
        rejects("{\"name\":\"Bad\",\"defaults\":{\"worldBorderDistance\":2147483648}}");
        rejects("{\"name\":\"Bad\",\"monitoredBlocks\":[\"invalid block\"]}");
        rejects("null");
        System.out.println("Mining preset and border self-checks passed.");
    }

    private static void rejects(String json) {
        try {
            ConfigManager.parsePreset(json);
            throw new AssertionError("Invalid preset was accepted: " + json);
        } catch (IllegalArgumentException expected) {
            // Invalid user data must fail before it reaches the mining loop.
        }
    }

    private static void surfaceRules() {
        for (BlockPos origin : new BlockPos[]{new BlockPos(0, 64, 0), new BlockPos(-17, 64, -17)}) {
            Vec3 feet = new Vec3(origin.getX() + 0.5, 64, origin.getZ() + 0.5);
            for (float yaw : new float[]{0, 90, 180, 270}) {
                Direction forward = Direction.fromYRot(yaw);
                BlockPos wall = origin.relative(forward, 2).above();
                var aim = new BlockHitResult(Vec3.atCenterOf(wall), forward.getOpposite(), wall, false);
                BlockPos side = origin.relative(forward.getClockWise(), 2).above();
                assert SurfaceTargetMiner.allowedSurface(side, feet, 65.8, yaw, false, aim);
                assert SurfaceTargetMiner.allowedSurface(origin.above(3), feet, 65.8, yaw, false, aim);
                assert !SurfaceTargetMiner.allowedSurface(wall.above(2), feet, 65.8, yaw, true, aim)
                        : "The aimed wall must stay excluded above player height, even in flight";
                assert !SurfaceTargetMiner.allowedSurface(wall.relative(forward.getClockWise(), 3), feet, 65.8, yaw, false, aim)
                        : "The entire aimed wall plane must be excluded beyond the forward cone";
                BlockPos floor = origin.below();
                assert !SurfaceTargetMiner.allowedSurface(floor, feet, 65.8, yaw, false, null);
                assert SurfaceTargetMiner.allowedSurface(floor, feet, 65.8, yaw, true, null);
                assert !SurfaceTargetMiner.allowedSurface(floor.relative(forward, 3), feet, 65.8, yaw, false, null);
                assert SurfaceTargetMiner.allowedSurface(floor.relative(forward.getOpposite(), 3), feet, 65.8, yaw, false, null)
                        : "Exactly three blocks behind must be eligible";
                Vec3 almost = feet.relative(forward.getOpposite(), 0.01);
                assert !SurfaceTargetMiner.allowedSurface(floor.relative(forward.getOpposite(), 3), almost, 65.8, yaw, false, null);
                assert !SurfaceTargetMiner.allowedSurface(floor, feet.add(0, 1, 0), 66.8, yaw, false, null)
                        : "Jumping without actual flight must not bypass the floor guard";
                var floorAim = new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false);
                assert SurfaceTargetMiner.allowedSurface(side, feet, 65.8, yaw, false, floorAim);
                assert SurfaceTargetMiner.allowedSurface(floor, feet, 65.8, yaw, true, floorAim)
                        : "A vertical crosshair hit must not exclude an entire floor plane";
            }
        }
    }
}

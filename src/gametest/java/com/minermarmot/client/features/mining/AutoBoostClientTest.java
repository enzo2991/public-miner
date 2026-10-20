package com.minermarmot.client.features.mining;

import com.minermarmot.client.config.ConfigManager;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.EnumSet;

public final class AutoBoostClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        var original = context.computeOnClient(client -> ConfigManager.current().copy());

        try (var world = context.worldBuilder().create()) {
            try {
                world.getConnection().waitForChunksDownload();
                world.getServer().runCommand("fill -5 64 -5 5 72 25 minecraft:air");
                world.getServer().runCommand("fill -5 63 -5 5 63 25 minecraft:stone");
                world.getServer().runCommand("gamemode survival @p");
                double sprint = measure(context, world, false);
                double boost = measure(context, world, true);
                check(Math.abs(boost - sprint) < 0.1, "Using a vanilla pickaxe in empty space must preserve sprint speed");
            } finally {
                context.runOnClient(client -> {
                    MiningManager.stop();

                    check(ConfigManager.save(original), "Original settings must be restored");
                });
            }
        }
    }

    private static double measure(ClientGameTestContext context, TestSingleplayerContext world, boolean boost) {
        context.runOnClient(client -> {
            MiningManager.stop();
            var config = ConfigManager.current().copy();
            config.applyPreset("default", ConfigManager.preset("default"));
            config.mining.enabled = boost ? EnumSet.of(MiningFeature.AUTO_SPRINT, MiningFeature.AUTO_BOOST)
                    : EnumSet.of(MiningFeature.AUTO_SPRINT);
            check(ConfigManager.save(config), "Sprint benchmark settings must save");
        });
        world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            player.getFoodData().setFoodLevel(20);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        });
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(3);
        context.runOnClient(client -> {
            check(MiningTargets.crosshair(client) == null, "Benchmark requires a floor but no block ahead");
            MiningManager.toggle();
        });
        context.waitTicks(20);
        double[] start = context.computeOnClient(client -> new double[]{client.player.getZ(), client.player.tickCount});
        context.waitTicks(40);
        return context.computeOnClient(client -> {
            check(client.player.isSprinting(), "Sprint must remain active during speed measurement");
            double speed = 20 * (client.player.getZ() - start[0]) / (client.player.tickCount - start[1]);
            return speed;
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

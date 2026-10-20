package com.minermarmot.client.features.alerts;

import com.minermarmot.client.config.ConfigManager;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;

public final class BlockAlertRecoveryClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {

        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        try {
            context.runOnClient(client -> {
                var config = original.copy();
                config.applyPreset("default", ConfigManager.preset("default"));
                config.alerts.targets.values().forEach(target -> target.enabled = false);
                config.alerts.targets.get("minecraft:diamond_ore").enabled = true;
                config.alerts.sounds = false;
                config.alerts.vanillaOreScanRadius = 16;
                check(ConfigManager.save(config), "Detection test settings must save");
            });
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                world.getServer().runCommand("fill -5 64 -5 5 70 8 minecraft:air");
                world.getServer().runCommand("fill -5 63 -5 5 63 8 minecraft:stone");
                world.getServer().runCommand("gamemode creative @p");
                world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
                world.getServer().runCommand("setblock 0 65 3 minecraft:diamond_ore");
                world.getConnection().waitForClientboundPackets();
                BlockPos ore = new BlockPos(0, 65, 3);
                context.waitFor(client -> detected(ore));
                context.runOnClient(client -> {
                    BlockAlertManager.worldChanged();
                    check(!detected(ore), "A world reset must clear previous discoveries immediately");
                });
                context.waitTicks(12);
                context.runOnClient(client -> check(detected(ore),
                        "Monitoring must resume for chunks still loaded after a world reset without reconnecting or receiving new block packets"));
                distanceCapacity(context, ore);
                world.getServer().runCommand("setblock 0 65 3 minecraft:air");
                context.waitFor(client -> !detected(ore));
                world.getServer().runCommand("setblock 0 65 4 minecraft:stone");
                world.getServer().runCommand("setblock 0 65 5 minecraft:diamond_ore");
                world.getConnection().waitForClientboundPackets();
                context.waitTicks(12);
                BlockPos hidden = new BlockPos(0, 65, 5);
                context.runOnClient(client -> {
                    check(!detected(hidden), "Unseen ores behind a wall must not be discovered");
                    check(BlockAlertManager.waypointList(client.player.position()).stream().noneMatch(wp -> wp.pos().equals(hidden)),
                            "An unseen ore behind a wall must not enter the waypoint display");
                });
                world.getServer().runCommand("setblock 0 65 4 minecraft:air");
                context.waitFor(client -> detected(hidden));
                world.getServer().runCommand("setblock 0 65 4 minecraft:stone");
                world.getConnection().waitForClientboundPackets();
                context.waitTicks(8);
                context.runOnClient(client -> {
                    check(detected(hidden), "Previously seen ores must remain remembered behind walls");
                    check(BlockAlertManager.waypointList(client.player.position()).stream().anyMatch(wp -> wp.pos().equals(hidden)),
                            "A previously visible ore must remain in the waypoint display after a wall hides it");
                });
                context.takeScreenshot("remembered-waypoint-through-wall");
            }
            BlockAlertsClientTest.blockChannelTable(context);
            context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        } finally {
            context.runOnClient(client -> check(ConfigManager.save(original), "Original settings must be restored"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void distanceCapacity(ClientGameTestContext context, BlockPos ore) {
        context.runOnClient(client -> {
            try {
                var settings = ConfigManager.current().alerts;
                settings.waypointDisplayDistance = 6;
                settings.targets.get("minecraft:deepslate_diamond_ore").enabled = true;
                var rare = ConfigManager.preset("default").blockAlerts.match(
                        net.minecraft.world.level.block.Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
                var singleton = BlockAlertManager.class.getDeclaredField("INSTANCE");
                singleton.setAccessible(true);
                Object manager = singleton.get(null);
                var field = BlockAlertManager.class.getDeclaredField("discovered");
                field.setAccessible(true);
                var memory = (java.util.Map<BlockPos, BlockAlertTarget>) field.get(manager);
                memory.clear();
                for (int index = 0; index < 100; index++) memory.put(new BlockPos(1000 + index, 65, 0), rare);
                var tick = BlockAlertManager.class.getDeclaredField("visibilityTick");
                tick.setAccessible(true);
                tick.setLong(manager, Long.MIN_VALUE);
                BlockAlertManager.updateVisibility(client);
                check(memory.containsKey(ore), "One hundred distant rare discoveries must not evict a nearby common block");
                check(memory.size() == 1, "Out-of-display-range discoveries must not occupy the 100 slots");
                BlockPos farther = new BlockPos(0, 65, 5);
                memory.put(farther, rare);
                int count = settings.displayedWaypointCount;
                boolean sortByRarity = settings.waypointSortByRarity;
                var origin = client.player.position();
                try {
                    settings.displayedWaypointCount = 1;
                    settings.waypointSortByRarity = true;
                    check(BlockAlertManager.waypointList(origin).getFirst().pos().equals(farther),
                            "Rarity sorting must put a farther rare block ahead of a nearer common block");
                    settings.waypointSortByRarity = false;
                    check(BlockAlertManager.waypointList(origin).size() == 1, "Nearest-first sorting must respect the configured list size");
                    check(BlockAlertManager.waypointList(origin).getFirst().pos().equals(ore),
                            "A nearby common block must precede a farther rare block in the right-hand list");
                    client.player.setPos(origin.x, origin.y, 6.5);
                    check(BlockAlertManager.waypointList(client.player.position()).getFirst().pos().equals(farther),
                            "The first list entry must update as the player approaches another block");
                } finally {
                    client.player.setPos(origin.x, origin.y, origin.z);
                    settings.displayedWaypointCount = count;
                    settings.waypointSortByRarity = sortByRarity;
                    memory.remove(farther);
                }
                settings.waypointDisplayDistance = 1;
                tick.setLong(manager, Long.MIN_VALUE);
                BlockAlertManager.updateVisibility(client);
                check(memory.isEmpty(), "A visible out-of-range block must not be reinserted during discovery");
                settings.waypointDisplayDistance = 6;
                tick.setLong(manager, Long.MIN_VALUE);
                BlockAlertManager.updateVisibility(client);
                check(memory.containsKey(ore), "An eligible visible block must be discoverable again after increasing distance");
                double x = client.player.getX();
                double y = client.player.getY();
                double z = client.player.getZ();
                try {
                    client.player.setPos(x - 10, y, z);
                    check(!detected(ore), "Moving out of range must free the slot immediately");
                } finally {
                    client.player.setPos(x, y, z);
                }
                settings.waypointDisplayDistance = 256;
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        });
        context.waitFor(client -> detected(ore));
    }

    private static boolean detected(BlockPos pos) {
        return BlockAlertManager.remembered().stream().anyMatch(waypoint -> waypoint.pos().equals(pos));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

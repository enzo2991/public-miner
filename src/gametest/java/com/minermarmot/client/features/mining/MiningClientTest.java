package com.minermarmot.client.features.mining;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.screen.ConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import java.util.EnumSet;

// The bundled experimental client test API is confined to this test mod.
public final class MiningClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {

        context.setScreen(() -> new ConfigScreen(null));
        context.clickScreenButton("minermarmot.config.features");
        context.waitTick();
        context.takeScreenshot("mining-settings");
        context.clickScreenButton("minermarmot.config.quit");

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            try {
                world.getConnection().waitForChunksDownload();
                world.getServer().runCommand("fill -25 63 -25 25 63 25 minecraft:stone");
                world.getServer().runCommand("fill -8 64 -8 8 70 8 minecraft:air");
                world.getServer().runCommand("gamemode survival @p");
                world.getServer().runOnServer(server -> world.getConnection().getServerPlayer()
                        .setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE)));
                world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
                sync(context, world);

                miningInScreens(context, world);
                rapidMining(context, world);
                surfaceMining(context, world);
                surfaceSearchPerformance(context, world);
                obstaclesAndBorder(context, world);
                flightAndCenter(context, world);
                boostAndDelay(context, world);
                context.runOnClient(client -> {
                    if (MiningManager.isRunning()) {
                        MiningManager.toggle();
                    }
                });
                context.waitTicks(3);
                context.runOnClient(client -> check(!client.player.input.keyPresses.forward(), "Movement must stop when mining stops"));
            } finally {
                context.runOnClient(client -> {
                    if (MiningManager.isRunning()) {
                        MiningManager.toggle();
                    }
                    client.gui.setScreen(null);
                });
            }
        }
        context.runOnClient(client -> check(!MiningManager.isRunning(), "Disconnect must stop the mining session"));
    }

    private void miningInScreens(ClientGameTestContext context, TestSingleplayerContext world) {
        configure(context);
        setBlock(context, world, new BlockPos(0, 65, 2), Blocks.STONE.defaultBlockState());
        context.getInput().pressKey(GLFW.GLFW_KEY_F6);
        context.setScreen(() -> new ConfigScreen(null));
        context.clickScreenButton("minermarmot.config.features");
        context.waitTicks(5);
        context.runOnClient(client -> check(client.level.getBlockState(new BlockPos(0, 65, 2)).is(Blocks.STONE),
                "Auto Mine must remain inactive until its menu switch is enabled"));
        toggleMenuFeature(context, MiningFeature.AUTO_MINE);
        context.waitFor(client -> client.level.getBlockState(new BlockPos(0, 65, 2)).isAir());
        context.runOnClient(client -> check(client.gui.screen() instanceof ConfigScreen && MiningManager.isRunning(),
                "Enabling mining in the menu must take effect without closing it or restarting the session"));
        toggleMenuFeature(context, MiningFeature.AUTO_MINE);
        setBlock(context, world, new BlockPos(0, 65, 3), Blocks.STONE.defaultBlockState());
        context.waitTicks(10);
        context.runOnClient(client -> check(client.level.getBlockState(new BlockPos(0, 65, 3)).is(Blocks.STONE),
                "Disabling mining in the menu must stop breaking blocks immediately"));
        toggleMenuFeature(context, MiningFeature.AUTO_MINE);
        context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        context.waitFor(client -> client.level.getBlockState(new BlockPos(0, 65, 3)).isAir());
        context.runOnClient(client -> check(client.gui.screen() instanceof InventoryScreen, "Inventory must remain open"));

        setBlock(context, world, new BlockPos(0, 65, 3), Blocks.STONE.defaultBlockState());
        context.setScreen(() -> new ChatScreen("", false));
        context.getInput().pressKey(GLFW.GLFW_KEY_F6);
        context.getInput().typeChars("mining continues");
        context.waitFor(client -> client.level.getBlockState(new BlockPos(0, 65, 3)).isAir());
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof ChatScreen, "Chat must remain open");
            check(MiningManager.isRunning(), "Typing the toggle key in chat must not stop mining");
            check(client.gui.screen().children().stream().anyMatch(child -> child instanceof EditBox input
                    && input.getValue().equals("mining continues")), "Chat text must remain editable while mining");
        });

        setBlock(context, world, new BlockPos(0, 65, 4), Blocks.STONE.defaultBlockState());
        context.setScreen(() -> new PauseScreen(true));
        context.waitTick();
        context.runOnClient(client -> check(MiningManager.isRunning(), "Chat key presses must not toggle mining after closing chat"));
        context.waitFor(client -> client.level.getBlockState(new BlockPos(0, 65, 4)).isAir());
        context.getInput().pressKey(GLFW.GLFW_KEY_F6);
        context.runOnClient(client -> check(!MiningManager.isRunning(), "The toggle key must stop mining inside screens"));
        context.setScreen(() -> new ChatScreen("", false));
        context.getInput().pressKey(GLFW.GLFW_KEY_F6);
        context.waitTick();
        context.runOnClient(client -> check(!MiningManager.isRunning(), "Typing the toggle key in chat must not start mining"));
        context.setScreen(() -> null);
    }

    private void rapidMining(ClientGameTestContext context, TestSingleplayerContext world) {
        for (boolean rapid : new boolean[]{false, true}) {
            configure(context, rapid ? new MiningFeature[]{MiningFeature.MINING_WITHOUT_DELAY} : new MiningFeature[]{});
            context.runOnClient(client -> {
                check(!MiningManager.isRunning(), "Manual delay checks must run with the mining controller stopped");
                client.gameMode.destroyDelay = 5;
            });
            context.waitTick();
            context.runOnClient(client -> check((client.gameMode.destroyDelay == 0) == rapid,
                    "The native manual-mining cooldown must be cleared only while Mining without delay is enabled"));
        }
        for (boolean creative : new boolean[]{false, true}) {
            world.getServer().runCommand("gamemode " + (creative ? "creative" : "survival") + " @p");
            world.getServer().runCommand("attribute @p minecraft:block_break_speed base set 100");
            for (float yaw : new float[]{0, 90, 180, 270}) {
                for (boolean rapid : new boolean[]{false, true}) {
                    world.getServer().runCommand("fill -8 64 -8 8 70 8 minecraft:air");
                    world.getServer().runCommand("tp @p 0.5 64 0.5 " + yaw + " 0");
                    BlockPos[] blocks = new BlockPos[4];
                    for (int i = 0; i < blocks.length; i++) {
                        blocks[i] = new BlockPos((int) WorldBorderManager.stepX(yaw) * (i + 1), 65,
                                (int) WorldBorderManager.stepZ(yaw) * (i + 1));
                        setBlock(context, world, blocks[i], Blocks.STONE.defaultBlockState());
                    }
                    BlockPos outside = new BlockPos((int) WorldBorderManager.stepX(yaw) * 7, 65,
                            (int) WorldBorderManager.stepZ(yaw) * 7);
                    setBlock(context, world, outside, Blocks.STONE.defaultBlockState());
                    configure(context, rapid ? new MiningFeature[]{MiningFeature.AUTO_MINE, MiningFeature.MINING_WITHOUT_DELAY}
                            : new MiningFeature[]{MiningFeature.AUTO_MINE});
                    context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
                    context.runOnClient(client -> {
                        try {
                            var delay = client.gameMode.getClass().getDeclaredField("destroyDelay");
                            delay.setAccessible(true);
                            delay.setInt(client.gameMode, 0);
                        } catch (ReflectiveOperationException exception) {
                            throw new AssertionError(exception);
                        }
                        check(creative || client.level.getBlockState(blocks[0])
                                        .getDestroyProgress(client.player, client.level, blocks[0]) >= 1,
                                "The server's block-break-speed attribute must allow instant survival mining");
                        MiningManager.toggle();
                    });
                    context.waitTick();
                    context.runOnClient(client -> {
                        for (int i = 0; i < blocks.length; i++) {
                            check(client.level.getBlockState(blocks[i]).isAir() == (rapid || i == 0),
                                    "Rapid mining must clear reachable blocks in one tick; OFF keeps one action: "
                                            + creative + ", " + yaw + ", " + rapid + ", " + blocks[i]);
                        }
                        check(client.level.getBlockState(outside).is(Blocks.STONE),
                                "Rapid mining must preserve blocks beyond native reach");
                        MiningManager.stop();
                    });
                    sync(context, world);
                    world.getServer().runOnServer(server -> {
                        for (int i = 0; i < blocks.length; i++) {
                            check(world.getConnection().getServerLevel().getBlockState(blocks[i]).isAir() == (rapid || i == 0),
                                    "The server must accept the measured mining cadence");
                        }
                    });
                    System.out.println("Rapid mining: creative=" + creative + ", yaw=" + yaw
                            + ", rapid=" + rapid + ", blocks/tick=" + (rapid ? blocks.length : 1));
                    context.setScreen(() -> null);
                }
            }
        }
        world.getServer().runCommand("gamemode survival @p");
        world.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 16");
        world.getServer().runCommand("fill -12 64 -12 12 70 12 minecraft:air");
        world.getServer().runCommand("fill 0 65 1 0 65 10 minecraft:stone");
        world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
        sync(context, world);
        configure(context, MiningFeature.AUTO_MINE, MiningFeature.MINING_WITHOUT_DELAY);
        context.runOnClient(client -> MiningManager.toggle());
        context.waitTick();
        context.runOnClient(client -> {
            for (int z = 1; z <= 10; z++) {
                check(client.level.getBlockState(new BlockPos(0, 65, z)).isAir() == (z <= 8),
                        "A high server reach must still bound client work to eight breaks per tick");
            }
            MiningManager.stop();
        });
        sync(context, world);
        world.getServer().runOnServer(server -> {
            for (int z = 1; z <= 10; z++) {
                check(world.getConnection().getServerLevel().getBlockState(new BlockPos(0, 65, z)).isAir() == (z <= 8),
                        "The server must accept the bounded eight-block burst");
            }
        });
        System.out.println("Rapid mining: extended server reach, eight blocks accepted in one tick");
        world.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 4.5");
        world.getServer().runCommand("attribute @p minecraft:block_break_speed base set 1");
        world.getServer().runCommand("fill -8 64 -8 8 70 8 minecraft:air");
        world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
        BlockPos first = new BlockPos(0, 65, 2);
        BlockPos second = new BlockPos(0, 65, 3);
        setBlock(context, world, first, Blocks.STONE.defaultBlockState());
        setBlock(context, world, second, Blocks.STONE.defaultBlockState());
        configure(context, MiningFeature.AUTO_MINE, MiningFeature.MINING_WITHOUT_DELAY);
        context.runOnClient(client -> MiningManager.toggle());
        context.waitTicks(6);
        context.runOnClient(client -> check(client.level.getBlockState(first).is(Blocks.STONE),
                "Rapid mining must not advance a resistant block more than once per tick"));
        context.waitTick();
        context.runOnClient(client -> {
            check(client.level.getBlockState(first).isAir() && client.level.getBlockState(second).is(Blocks.STONE),
                    "Normal diamond-pickaxe progress must still take seven ticks per stone block");
            MiningManager.stop();
        });
        setBlock(context, world, second, Blocks.AIR.defaultBlockState());
        world.getServer().runCommand("gamemode adventure @p");
        setBlock(context, world, first, Blocks.STONE.defaultBlockState());
        setBlock(context, world, second, Blocks.STONE.defaultBlockState());
        context.runOnClient(client -> MiningManager.toggle());
        context.waitTicks(3);
        context.runOnClient(client -> {
            check(client.level.getBlockState(first).is(Blocks.STONE) && client.level.getBlockState(second).is(Blocks.STONE),
                    "A refused native action must not continue to later blocks");
            MiningManager.stop();
        });
        world.getServer().runCommand("gamemode survival @p");
        setBlock(context, world, first, Blocks.AIR.defaultBlockState());
        setBlock(context, world, second, Blocks.AIR.defaultBlockState());
    }

    private void surfaceMining(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
        sync(context, world);
        BlockPos side = new BlockPos(2, 65, 0);
        BlockPos floor = new BlockPos(-1, 63, 0);
        BlockPos ceiling = new BlockPos(0, 67, 0);
        BlockPos front = new BlockPos(0, 65, 2);
        BlockPos upperFront = front.above(2);
        BlockPos wideFront = front.east(3);
        for (BlockPos pos : new BlockPos[]{side, floor, ceiling, front, upperFront, wideFront}) {
            setBlock(context, world, pos, Blocks.DIAMOND_ORE.defaultBlockState());
        }
        configure(context, MiningFeature.SURFACE_TARGET_MINER);
        context.runOnClient(client -> {
            ConfigManager.current().alerts.targets.forEach((id, settings) -> settings.enabled = id.equals("minecraft:diamond_ore"));
            ConfigManager.current().alerts.targets.get("minecraft:diamond_ore").surfaceMine = false;
            MiningManager.toggle();
        });
        context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        context.waitTicks(30);
        context.runOnClient(client -> {
            check(client.level.getBlockState(side).is(Blocks.DIAMOND_ORE)
                    && client.level.getBlockState(floor).is(Blocks.DIAMOND_ORE)
                    && client.level.getBlockState(ceiling).is(Blocks.DIAMOND_ORE),
                    "Monitored targets with surface mining disabled must remain intact");
            ConfigManager.current().alerts.targets.get("minecraft:diamond_ore").surfaceMine = true;
            check(MiningTargets.crosshair(client).getBlockPos().equals(front), "The fixture must aim at the front wall");
            var miner = new SurfaceTargetMiner();
            for (BlockPos excluded : new BlockPos[]{upperFront, wideFront, floor}) {
                check(MiningTargets.visibleHit(client, excluded) != null, "The excluded fixture must be natively reachable: " + excluded);
                var hit = miner.findTarget(client, excluded);
                check(hit == null || !hit.getBlockPos().equals(excluded), "A preferred target must obey wall and floor safety: " + excluded);
            }
        });
        context.waitFor(client -> client.level.getBlockState(side).isAir()
                && client.level.getBlockState(ceiling).isAir());
        context.runOnClient(client -> {
            for (BlockPos pos : new BlockPos[]{front, upperFront, wideFront, floor}) {
                check(client.level.getBlockState(pos).is(Blocks.DIAMOND_ORE), "The aimed wall and nearby floor must remain intact: " + pos);
            }
            ConfigManager.current().alerts.targets.values().forEach(settings -> settings.enabled = false);
            check(new SurfaceTargetMiner().findTarget(client, side) == null, "Monitor OFF must immediately exclude preferred targets");
        });
        // Confirm the last predicted break before replacing that same position.
        context.waitTicks(5);
        world.getServer().runOnServer(server -> check(world.getConnection().getServerLevel().getBlockState(side).isAir(),
                "The server must confirm the original side break before replacement"));
        setBlock(context, world, side, Blocks.DIAMOND_ORE.defaultBlockState());
        context.waitTicks(30);
        context.runOnClient(client -> {
            check(client.level.getBlockState(side).is(Blocks.DIAMOND_ORE), "Disabled monitored blocks must not be mined");
            MiningManager.toggle();
        });
        for (BlockPos pos : new BlockPos[]{side, front, upperFront, wideFront}) {
            setBlock(context, world, pos, Blocks.AIR.defaultBlockState());
        }
        world.getServer().runCommand("tp @p -0.5 64 3.49 0 0");
        sync(context, world);
        context.runOnClient(client -> {
            ConfigManager.current().alerts.targets.get("minecraft:diamond_ore").enabled = true;
            MiningManager.toggle();
        });
        context.waitTicks(20);
        context.runOnClient(client -> check(client.level.getBlockState(floor).is(Blocks.DIAMOND_ORE),
                "A floor block only 2.99 blocks behind must remain intact"));
        world.getServer().runCommand("tp @p -0.5 64 3.5 0 0");
        context.waitFor(client -> client.level.getBlockState(floor).isAir());
        context.waitTicks(5);
        context.runOnClient(client -> MiningManager.toggle());
        world.getServer().runOnServer(server -> check(world.getConnection().getServerLevel().getBlockState(floor).isAir(),
                "The server must confirm the floor break three blocks behind"));

        world.getServer().runCommand("gamemode creative @p");
        world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
        setBlock(context, world, floor, Blocks.DIAMOND_ORE.defaultBlockState());
        context.runOnClient(client -> {
            check(client.player.getAbilities().mayfly && !client.player.getAbilities().flying,
                    "Creative flight permission alone must not count as actual flight");
            MiningManager.toggle();
        });
        context.waitTicks(20);
        context.runOnClient(client -> check(client.level.getBlockState(floor).is(Blocks.DIAMOND_ORE),
                "Walking creative players must keep the floor guard"));
        world.getServer().runCommand("tp @p 0.5 64.5 0.5 0 0");
        sync(context, world);
        context.runOnClient(client -> {
            client.player.getAbilities().flying = true;
            client.player.setDeltaMovement(0, 0, 0);
            client.player.onUpdateAbilities();
            var hit = new SurfaceTargetMiner().findTarget(client, floor);
            check(hit != null && hit.getBlockPos().equals(floor), "Taking off must immediately allow the nearby floor target");
        });
        context.waitFor(client -> client.level.getBlockState(floor).isAir());
        context.waitTicks(5);
        context.runOnClient(client -> MiningManager.toggle());
        world.getServer().runOnServer(server -> check(world.getConnection().getServerLevel().getBlockState(floor).isAir(),
                "The server must confirm that actual flight permits the nearby floor break"));
        world.getServer().runCommand("gamemode survival @p");
        setBlock(context, world, floor, Blocks.STONE.defaultBlockState());
        context.setScreen(() -> null);
    }

    private void surfaceSearchPerformance(ClientGameTestContext context, TestSingleplayerContext world) {
        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        try {
            world.getServer().runCommand("gamemode survival @p");
            world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
            world.getServer().runCommand("fill -13 64 -13 18 76 13 minecraft:air");
            world.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 16");
            sync(context, world);
            context.waitFor(client -> client.player.blockInteractionRange() == 16);
            context.runOnClient(client -> {
                var config = ConfigManager.current().copy();
                config.mining.enabled = EnumSet.of(MiningFeature.SURFACE_TARGET_MINER);
                config.alerts.targets.forEach((id, settings) -> {
                    settings.enabled = id.equals("minecraft:diamond_ore");
                    settings.surfaceMine = true;
                });
                config.alerts.vanillaOreScanRadius = 128;
                check(ConfigManager.save(config), "Extended surface reach settings must save");
            });
            BlockPos far = new BlockPos(10, 65, 0);
            setBlock(context, world, far, Blocks.DIAMOND_ORE.defaultBlockState());
            context.runOnClient(client -> {
                check(client.player.blockInteractionRange() == 16, "The server's current reach must reach the client");
                var hit = new SurfaceTargetMiner().findTarget(client, null);
                check(hit != null && hit.getBlockPos().equals(far), "Surface mining must retain extended server reach");
                MiningManager.toggle();
            });
            context.waitFor(client -> client.level.getBlockState(far).isAir());
            context.waitTicks(5);
            context.runOnClient(client -> MiningManager.toggle());
            world.getServer().runOnServer(server -> check(world.getConnection().getServerPlayer().level().getBlockState(far).isAir(),
                    "The server must confirm the extended-reach surface break"));
            setBlock(context, world, far, Blocks.DIAMOND_ORE.defaultBlockState());
            world.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 4.5");
            sync(context, world);
            context.runOnClient(client -> check(new SurfaceTargetMiner().findTarget(client, far) == null,
                    "A preferred target must immediately obey a reduced server reach"));
            setBlock(context, world, far, Blocks.AIR.defaultBlockState());
            world.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 16");
            world.getServer().runCommand("fill -12 64 -12 12 75 12 minecraft:diamond_ore");
            world.getServer().runCommand("fill -2 63 -2 2 67 2 minecraft:stone");
            world.getServer().runCommand("fill -1 64 -1 1 66 1 minecraft:air");
            sync(context, world);
            for (int scanRadius : new int[]{2, 16}) {
                context.runOnClient(client -> {
                    ConfigManager.current().alerts.vanillaOreScanRadius = scanRadius;
                    var miner = new SurfaceTargetMiner();
                    check(miner.findTarget(client, null) == null, "Enclosed ore must never become a surface target");
                    long start = System.nanoTime();
                    for (int sample = 0; sample < 3; sample++) {
                        check(miner.findTarget(client, null) == null, "Dense hidden ores must stay hidden");
                    }
                    System.out.println("Surface search benchmark: server reach=16, ore radius=" + scanRadius
                            + ", average=" + (System.nanoTime() - start) / 3_000_000.0 + " ms");
                    System.out.println("Surface search code source: " + SurfaceTargetMiner.class.getProtectionDomain().getCodeSource().getLocation());
                });
            }
            context.runOnClient(client -> {
                ConfigManager.current().alerts.targets.values().forEach(settings -> settings.surfaceMine = false);
                check(new SurfaceTargetMiner().findTarget(client, null) == null, "All Mine switches OFF must select nothing");
            });
            world.getServer().runCommand("fill -1 64 -1 1 66 1 minecraft:diamond_ore");
            sync(context, world);
            context.runOnClient(client -> {
                BlockPos inside = BlockPos.containing(client.player.getEyePosition());
                check(client.level.getBlockState(inside).is(Blocks.DIAMOND_ORE), "The fixture must place the eyes inside a solid target");
                check(MiningTargets.visibleHit(client, inside) != null, "Native hits from inside a full cube must remain available");
            });
        } finally {
            context.runOnClient(client -> {
                if (MiningManager.isRunning()) MiningManager.toggle();
                check(ConfigManager.save(original), "Surface search checks must restore settings");
            });
            world.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 4.5");
            world.getServer().runCommand("fill -13 64 -13 18 76 13 minecraft:air");
            world.getServer().runCommand("fill -13 63 -13 18 63 13 minecraft:stone");
            sync(context, world);
        }
    }

    private void obstaclesAndBorder(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("worldborder center 0 0");
        world.getServer().runCommand("worldborder set 40");
        world.getServer().runCommand("tp @p 0.5 64 15.5 0 0");
        setBlock(context, world, new BlockPos(-1, 64, 15), Blocks.STONE.defaultBlockState());
        setBlock(context, world, new BlockPos(-1, 65, 15), Blocks.STONE.defaultBlockState());
        configure(context, MiningFeature.AUTO_SPRINT, MiningFeature.AUTO_CAMERA_LOCK, MiningFeature.AUTO_CENTER_BLOCK);
        context.runOnClient(client -> {
            ConfigManager.current().mining.worldBorderDistance = 4;
            MiningManager.toggle();
        });
        context.setScreen(() -> new ChatScreen("", false));
        context.waitFor(client -> client.player.getYRot() == 180, 240);
        context.runOnClient(client -> {
            check(Math.abs(client.player.getX() + 3.5) < 0.01, "The lateral border leg must use the custom four-block spacing");
            check(client.level.getBlockState(new BlockPos(-1, 64, 15)).isAir(), "Border movement must mine the lower obstacle");
            check(client.level.getBlockState(new BlockPos(-1, 65, 15)).isAir(), "Border movement must mine the upper obstacle");
            check(client.gui.screen() instanceof ChatScreen, "Border movement must preserve the open screen");
            MiningManager.toggle();
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            for (int i = 0; i < 3; i++) {
                MiningManager.toggle();
                MiningManager.toggle();
            }
            check(!MiningManager.isRunning(), "Repeated mining toggles must leave the session stopped");
        });
        world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
        sync(context, world);
        context.waitTicks(3);
        world.getServer().runCommand("tp @p -3.5 64 10.5 180 0");
        sync(context, world);
        context.runOnClient(client -> MiningManager.toggle());
        context.waitFor(client -> client.player.getYRot() == 90, 300);
        context.runOnClient(client -> {
            check(client.player.getZ() < -14,
                    "The next border maneuver must alternate after mining toggles and a spawn round trip");
            MiningManager.toggle();
        });
        context.setScreen(() -> null);
        world.getServer().runCommand("worldborder set 1000");
    }

    private void flightAndCenter(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("gamemode creative @p");
        world.getServer().runCommand("tp @p 0.2 64.2 0.5 -86 18");
        setBlock(context, world, new BlockPos(0, 63, 0), Blocks.STONE_SLAB.defaultBlockState());
        configure(context, MiningFeature.AUTO_CAMERA_LOCK, MiningFeature.AUTO_CENTER_BLOCK);
        context.runOnClient(client -> MiningManager.toggle());
        context.setScreen(() -> new ConfigScreen(null));
        context.clickScreenButton("minermarmot.config.features");
        toggleMenuFeature(context, MiningFeature.AUTO_FLIGHT_HEIGHT);
        context.runOnClient(client -> {
            client.player.setPos(client.player.getX(), 64.2, client.player.getZ());
            MiningManager.finishMovement();
            check(Math.abs(client.player.getY() - 64.1) < 0.01,
                    "Flight height must use the player's initial Y, independently of the slab below: " + client.player.position());
        });
        context.waitTicks(5);
        context.runOnClient(client -> {
            check(Math.abs(client.player.getY() - 64.1) < 0.01, "Flight height must retain the captured altitude: "
                    + client.player.position() + ", flying=" + client.player.getAbilities().flying
                    + ", mayfly=" + client.player.getAbilities().mayfly + ", running=" + MiningManager.isRunning());
            check(Math.abs(client.player.getZ() - 0.5) < 0.01, "Centering must use the axis perpendicular to travel");
            check(client.player.getYRot() == 270 && client.player.getXRot() == 0, "Camera lock must choose the nearest cardinal direction");
            client.player.turn(100, 100);
            check(client.player.getYRot() == 270 && client.player.getXRot() == 0, "Mouse movement must not undo camera lock");
        });
        setBlock(context, world, new BlockPos(0, 62, 0), Blocks.STONE.defaultBlockState());
        setBlock(context, world, new BlockPos(0, 63, 0), Blocks.AIR.defaultBlockState());
        context.runOnClient(client -> client.player.setDeltaMovement(0, -0.25, 0));
        context.waitTicks(5);
        context.runOnClient(client -> {
            check(Math.abs(client.player.getY() - 64.1) < 0.01,
                    "Flight height must stay at the activation height after the floor changes: " + client.player.position());
            MiningManager.toggle();
            client.player.setPos(client.player.getX(), 59.5, client.player.getZ());
            MiningManager.toggle();
            check(Math.abs(client.player.getY() - 59.1) < 0.01,
                    "Reactivating mining at Y 59.5 must immediately set Y to 59.1: " + client.player.position());
            MiningManager.toggle();
        });
        context.clickScreenButton("minermarmot.config.quit");
    }

    private void boostAndDelay(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("tp @p 4.5 64 0.5 0 0");
        world.getServer().runCommand("gamemode survival @p");
        world.getServer().runOnServer(server -> world.getConnection().getServerPlayer()
                .setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SNOWBALL, 4)));
        sync(context, world);
        configure(context, MiningFeature.AUTO_BOOST);
        context.runOnClient(client -> MiningManager.toggle());
        context.waitFor(client -> client.player.getMainHandItem().getCount() < 4);
        context.runOnClient(client -> {
            double height = client.player.getY();
            Vec3 velocity = client.player.getDeltaMovement();
            var boostPreset = ConfigManager.preset(ConfigManager.current().preset);
            boolean blockUpward = boostPreset.autoBoostBlockUpward;
            try {
                boostPreset.autoBoostBlockUpward = false;
                client.player.setDeltaMovement(0.2, 0.4, -0.1);
                MiningManager.finishMovement();
                check(client.player.getDeltaMovement().equals(new Vec3(0.2, 0.4, -0.1)), "The preset must be able to allow ascent with Auto Boost active");
                client.player.setDeltaMovement(0.2, -0.3, -0.1);
                MiningManager.finishMovement();
                check(client.player.getDeltaMovement().equals(new Vec3(0.2, -0.3, -0.1)), "Allowing ascent must preserve falling velocity");
                boostPreset.autoBoostBlockUpward = true;
                client.player.setDeltaMovement(0.2, 0.4, -0.1);
                MiningManager.finishMovement();
                check(client.player.getDeltaMovement().equals(new Vec3(0.2, 0, -0.1)), "Ordinary Auto Boost must cancel ascent and preserve horizontal velocity");
                client.player.setPos(client.player.getX(), height - 0.25, client.player.getZ());
                client.player.setDeltaMovement(0.2, -0.3, -0.1);
                MiningManager.finishMovement();
                check(Math.abs(client.player.getY() - height + 0.25) < 0.000001, "Ordinary Auto Boost must allow descent without locking height");
                check(client.player.getDeltaMovement().equals(new Vec3(0.2, -0.3, -0.1)), "Ordinary Auto Boost must preserve falling velocity");
                ConfigManager.current().mining.enabled.remove(MiningFeature.AUTO_BOOST);
                client.player.setDeltaMovement(0.2, 0.4, -0.1);
                MiningManager.finishMovement();
                check(client.player.getDeltaMovement().equals(new Vec3(0.2, 0.4, -0.1)), "Disabling Auto Boost must restore ascent");
                ConfigManager.current().mining.enabled.add(MiningFeature.AUTO_BOOST);
            } finally {
                boostPreset.autoBoostBlockUpward = blockUpward;

                client.player.setPos(client.player.getX(), height, client.player.getZ());
                client.player.setDeltaMovement(velocity);
                MiningManager.toggle();
            }
        });

        world.getServer().runCommand("gamemode creative @p");
        world.getServer().runOnServer(server -> world.getConnection().getServerPlayer()
                .setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE)));
        sync(context, world);
        BlockPos first = new BlockPos(4, 65, 2);
        BlockPos second = new BlockPos(4, 65, 3);
        setBlock(context, world, first, Blocks.STONE.defaultBlockState());
        setBlock(context, world, second, Blocks.STONE.defaultBlockState());
        configure(context, MiningFeature.AUTO_MINE, MiningFeature.MINING_WITHOUT_DELAY);
        context.runOnClient(client -> MiningManager.toggle());
        context.waitTicks(2);
        context.runOnClient(client -> {
            check(client.level.getBlockState(first).isAir() && client.level.getBlockState(second).isAir(),
                    "Mining without delay must break consecutive creative blocks on consecutive ticks");
            MiningManager.toggle();
        });
    }

    private static void toggleMenuFeature(ClientGameTestContext context, MiningFeature feature) {
        context.runOnClient(client -> {
            String label = Component.translatable(feature.translationKey()).getString();
            boolean enabled = MiningManager.enabled(feature);
            client.gui.screen().children().stream().filter(child -> child instanceof EditBox)
                    .map(child -> (EditBox) child).findFirst().orElseThrow().setValue(label);
            client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                    .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                    .flatMap(entry -> entry.children().stream()).filter(child -> child instanceof CycleButton<?>)
                    .map(child -> (CycleButton<?>) child).findFirst().orElseThrow().onPress(new MouseButtonInfo(0, 0));
            check(MiningManager.enabled(feature) != enabled, "The menu switch must apply immediately: " + feature);
        });
    }

    private void configure(ClientGameTestContext context, MiningFeature... features) {
        context.runOnClient(client -> {
            check(!MiningManager.isRunning(), "Each scenario must start with mining stopped");
            var config = ConfigManager.current().copy();
            config.mining.enabled = features.length == 0 ? EnumSet.noneOf(MiningFeature.class) : EnumSet.of(features[0], features);
            config.mining.worldBorderDistance = 0;
            check(ConfigManager.save(config), "Test settings must be saved successfully");
        });
    }

    private void setBlock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos pos,
                          net.minecraft.world.level.block.state.BlockState state) {
        world.getServer().runOnServer(server -> world.getConnection().getServerLevel().setBlockAndUpdate(pos, state));
        sync(context, world);
    }

    private void sync(ClientGameTestContext context, TestSingleplayerContext world) {
        context.waitTick();
        world.getConnection().waitForClientboundPackets();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

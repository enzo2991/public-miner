package com.minermarmot.client.features.tnt;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.mining.MiningManager;
import com.minermarmot.client.screen.ConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

// Experimental client test APIs and the simulated server shop stay in the test mod.
public final class TntClientTest implements FabricClientGameTest {
    private final AtomicInteger purchases = new AtomicInteger();
    private final AtomicInteger shopOpens = new AtomicInteger();
    private final AtomicInteger wandUses = new AtomicInteger();
    private final AtomicBoolean rejectCarpet = new AtomicBoolean();
    private List<String> offers = List.of("minecraft:red_shulker_box");
    private boolean mineriteShop;

    @Override
    public void runTest(ClientGameTestContext context) {

        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        var preset = context.computeOnClient(client -> ConfigManager.preset("kazuria").tnt);
        var originalClicks = new ArrayList<>(preset.shopClicks);
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                Commands.literal("shop").executes(command -> {
                    shopOpens.incrementAndGet();
                    openShop(command.getSource().getPlayerOrException(), 0);
                    return 1;
                })));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() && rejectCarpet.get() && player.getItemInHand(hand).is(Items.TNT)) return InteractionResult.FAIL;
            if (!TntManager.isBuilderRod(player.getItemInHand(hand), "Baguette du bâtisseur")) return InteractionResult.PASS;
            if (!level.isClientSide()) {
                wandUses.incrementAndGet();
                var placed = hit.getBlockPos().relative(hit.getDirection());
                if (player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(placed))) return InteractionResult.SUCCESS;
                for (int slot = 0; slot < 36; slot++) {
                    var stack = player.getInventory().getItem(slot);
                    if (stack.is(Items.TNT)) { stack.shrink(1); break; }
                }
                level.setBlock(placed, Blocks.TNT.defaultBlockState(), 3);
                player.inventoryMenu.broadcastChanges();
            }
            return InteractionResult.SUCCESS;
        });
        try {
            context.runOnClient(client -> {
                var config = original.copy();
                config.applyPreset("kazuria", ConfigManager.preset("kazuria"));
                config.tntMode = TntMode.BUILDER;
                check(ConfigManager.save(config), "TNT settings must save");
                check(preset.shopClicks.equals(List.of("minecraft:red_shulker_box")), "Kazuria must use one purchase click");
                check(preset.builderWandName.equals("Baguette du bâtisseur"), "Kazuria wand name must come from the preset");
            });
            settings(context);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                world.getServer().runCommand("fill -25 63 -25 25 63 25 minecraft:stone");
                world.getServer().runCommand("fill -25 64 -25 25 70 25 minecraft:air");
                world.getServer().runCommand("gamemode survival @p");
                straightAndSwap(context, world);
                carpetSkips(context, world);
                reload(context, world, "kazuria", TntMode.CARPET, List.of("minecraft:gold_ingot", "minecraft:red_shulker_box", "minecraft:lime_stained_glass_pane"));
                reload(context, world, "kazuria", TntMode.BUILDER, List.of("minecraft:red_shulker_box"));
                reload(context, world, "minerite", TntMode.CARPET, List.of("minecraft:red_shulker_box", "minecraft:lime_stained_glass_pane"));
                cancellation(context, world);
            }
            context.runOnClient(client -> check(!TntManager.isActive(), "Disconnect must stop TNT placement"));
        } finally {
            context.runOnClient(client -> {
                TntManager.worldChanged();
                MiningManager.stop();
                preset.shopClicks = originalClicks;
                rejectCarpet.set(false);
                check(ConfigManager.save(original), "Tests must restore user settings");
                client.gui.setScreen(null);
            });
        }
    }

    private void settings(ClientGameTestContext context) {
        context.setScreen(() -> new ConfigScreen(null));
        context.clickScreenButton("minermarmot.config.features");
        context.runOnClient(client -> {
            var mode = client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                    .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                    .flatMap(entry -> entry.children().stream()).filter(child -> child instanceof CycleButton<?> button
                            && button.getValue() instanceof TntMode).map(child -> (CycleButton<?>) child).findFirst().orElseThrow();
            check(mode.createDefaultNarrationMessage().getString().contains(Component.translatable("minermarmot.config.tntMode").getString()),
                    "TNT selector narration must retain the setting name");
            mode.onPress(new MouseButtonInfo(0, 0));
            check(ConfigManager.current().tntMode == TntMode.CARPET, "TNT mode edits must apply immediately");
            try {
                var file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minermarmot/config.json");
                check(ConfigManager.parseConfig(java.nio.file.Files.readString(file)).tntMode == TntMode.CARPET,
                        "TNT mode must already be saved before closing");
            } catch (java.io.IOException exception) { throw new AssertionError(exception); }
            client.gui.screen().children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
                    .map(child -> (net.minecraft.client.gui.components.EditBox) child).findFirst().orElseThrow().setValue("TNT");
        });
        context.waitTick();
        context.takeScreenshot("tnt-mode-settings");
        context.clickScreenButton("minermarmot.config.quit");
    }

    private void straightAndSwap(ClientGameTestContext context, TestSingleplayerContext world) {
        prepare(context, world, TntMode.CARPET, 0, 0);
        context.getInput().holdKey(GLFW.GLFW_KEY_S);
        context.waitTicks(10);
        double baselineStart = context.computeOnClient(client -> client.player.getZ());
        context.waitTicks(20);
        double nativeDistance = baselineStart - context.computeOnClient(client -> client.player.getZ());
        context.getInput().releaseKey(GLFW.GLFW_KEY_S);
        context.waitTick();
        check(nativeDistance > 4, "The reference must measure full-speed native backward walking");
        for (int yaw : new int[]{0, 90, 180, 270}) {
            int originalPitch = yaw == 0 ? -45 : 70;
            prepare(context, world, TntMode.CARPET, yaw, originalPitch);
            var facing = net.minecraft.core.Direction.fromYRot(yaw);
            var closeTnt = new BlockPos(0, 64, 0).relative(facing);
            world.getServer().runCommand("setblock " + closeTnt.getX() + " 64 " + closeTnt.getZ() + " minecraft:tnt");
            world.getServer().runOnServer(server -> {
                var inventory = world.getConnection().getServerPlayer().getInventory();
                inventory.setItem(20, new ItemStack(Items.TNT, 64));
                world.getConnection().getServerPlayer().inventoryMenu.broadcastChanges();
            });
            sync(context, world);
            context.setScreen(() -> new ChatScreen("", false));
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.getInput().typeChars("tnt");
            context.waitTick();
            context.runOnClient(client -> {
                check(!TntManager.isActive(), "The TNT toggle key must not activate placement in chat");
                check(client.gui.screen().children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.components.EditBox input
                        && input.getValue().equals("tnt")), "Chat text must remain editable");
            });
            context.setScreen(() -> null);
            context.waitTick();
            context.runOnClient(client -> check(!TntManager.isActive(), "Chat key presses must not activate TNT after closing chat"));
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.waitTicks(8);
            context.setScreen(() -> new ChatScreen("", false));
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.waitTick();
            context.runOnClient(client -> check(TntManager.isActive(), "The TNT toggle key must not stop placement in chat"));
            context.getInput().typeChars("placement continues");
            int chatTntCount = context.computeOnClient(client -> TntManager.countItem(client.player.getInventory(), Items.TNT));
            var walkingStart = context.computeOnClient(client -> client.player.position());
            for (int tick = 0; tick < 20; tick++) {
                context.waitTick();
                context.runOnClient(client -> {
                    check(TntManager.isActive() && TntManager.retreats(), "Placement must never wait for a complete row or server confirmation");
                    check(client.player.input.keyPresses.backward() && client.player.input.getMoveVector().lengthSquared() > 0.99,
                            "Every placement tick must preserve full backward input");
                    check(Math.abs(client.player.getXRot() - 30) < 0.001, "F7 must override the starting pitch with a fixed floor angle");
                    float lockedYaw = client.player.getYRot();
                    client.player.turn(100, -100);
                    check(client.player.getYRot() == lockedYaw && client.player.getXRot() == 30, "Mouse input must not disturb the automatic aim");
                });
            }
            context.runOnClient(client -> {
                check(client.gui.screen() instanceof ChatScreen, "Chat must stay open while placing TNT");
                check(client.gui.screen().children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.components.EditBox input
                        && input.getValue().equals("placement continues")), "Chat must remain editable during TNT placement");
                check(TntManager.countItem(client.player.getInventory(), Items.TNT) < chatTntCount,
                        "TNT placement must consume ammunition while chat is open");
            });
            double carpetDistance = context.computeOnClient(client -> client.player.position().subtract(walkingStart).horizontalDistance());
            context.setScreen(() -> null);
            context.waitTick();
            context.runOnClient(client -> check(TntManager.isActive(), "Chat key presses must not stop TNT after closing chat"));
            check(carpetDistance >= nativeDistance - 0.05 && carpetDistance <= nativeDistance * 1.04,
                    "Carpet must match uninterrupted native backward speed: " + carpetDistance + " versus " + nativeDistance);
            System.out.println("TNT carpet heading " + yaw + ": 20-tick distance=" + carpetDistance + ", native=" + nativeDistance);
            context.runOnClient(client -> {
                check(TntManager.isActive() && client.player.getMainHandItem().is(Items.TNT),
                        "Carpet must auto-swap TNT from outside the hotbar");
                boolean alongZ = net.minecraft.core.Direction.fromYRot(yaw).getAxis() == net.minecraft.core.Direction.Axis.Z;
                check(Math.abs((alongZ ? client.player.getX() : client.player.getZ()) - 0.5) < 0.001,
                        "Camera sweep must not change the backward lane");
                check(client.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(0.5, 64, 0.5)) > 0.25,
                        "Carpet must actually move backward");
                check(TntManager.countItem(client.player.getInventory(), Items.TNT) < 64,
                        "Native TNT placement must consume ammunition");
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.waitTicks(2);
            context.runOnClient(client -> {
                check(!client.player.input.keyPresses.backward(), "Stopping must release backward input");
                check(client.player.getXRot() == originalPitch, "Stopping must restore the initial pitch");
            });
            world.getServer().runOnServer(server -> {
                var level = world.getConnection().getServerPlayer().level();
                int[] columns = new int[5];
                for (int x = -12; x <= 12; x++) {
                    for (int z = -12; z <= 12; z++) {
                        var pos = new BlockPos(x, 64, z);
                        check(level.getBlockState(pos.above()).isAir(), "Nearby TNT must never produce a second carpet layer");
                        if (!level.getBlockState(pos).is(Blocks.TNT)) continue;
                        int lateral = facing.getAxis() == net.minecraft.core.Direction.Axis.Z ? x : z;
                        check(Math.abs(lateral) <= 2, "TNT must stay within the five-block strip");
                        columns[lateral + 2]++;
                    }
                }
                for (int count : columns) check(count >= 3, "Both outer columns must keep receiving TNT despite a nearby central TNT");
                System.out.println("TNT carpet heading " + yaw + ": column counts=" + java.util.Arrays.toString(columns));
            });
            if (yaw == 0) {
                world.getServer().runCommand("gamemode spectator @p");
                world.getServer().runCommand("tp @p 0.5 74 -1.5 0 90");
                sync(context, world);
                context.getInput().pressKey(GLFW.GLFW_KEY_F1);
                context.takeScreenshot("tnt-full-speed-five-wide");
                context.getInput().pressKey(GLFW.GLFW_KEY_F1);
                world.getServer().runCommand("gamemode survival @p");
            }
        }
        prepare(context, world, TntMode.CARPET, 0, 45);
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            player.getInventory().setItem(20, new ItemStack(Items.TNT, 64));
            player.inventoryMenu.broadcastChanges();
        });
        sync(context, world);
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
        context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        var swappingAt = context.computeOnClient(client -> client.player.position());
        context.waitTicks(5);
        context.runOnClient(client -> check(TntManager.isActive() && !TntManager.retreats()
                && client.player.position().distanceToSqr(swappingAt) < 0.001,
                "Opening a user screen during a swap must suspend movement immediately"));
        context.setScreen(() -> null);
        context.waitTicks(5);
        context.runOnClient(client -> check(TntManager.retreats() && client.player.getZ() < swappingAt.z,
                "Closing the screen after a swap must resume continuous retreat"));
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
        prepare(context, world, TntMode.CARPET, 0, 45);
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            player.getInventory().setItem(0, new ItemStack(Items.TNT, 1));
            player.getInventory().setItem(22, new ItemStack(Items.TNT, 32));
            player.inventoryMenu.broadcastChanges();
        });
        sync(context, world);
        context.runOnClient(client -> check(TntManager.countItem(client.player.getInventory(), Items.TNT) == 33,
                "Held-stack exhaustion test must start with both ammunition stacks synchronized"));
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
        context.waitTicks(15);
        context.runOnClient(client -> {
            check(TntManager.isActive() && client.player.getMainHandItem().is(Items.TNT), "An exhausted held stack must swap to remaining TNT");
            check(client.player.getInventory().getItem(22).isEmpty(), "The reserve stack must leave its inventory slot");
            check(shopOpens.get() == 0, "Remaining inventory TNT must prevent any purchase");
        });
        context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        var pausedAt = context.computeOnClient(client -> client.player.position());
        context.waitTicks(5);
        context.runOnClient(client -> check(client.player.position().distanceToSqr(pausedAt) < 0.001,
                "User screens must suspend placement and movement"));
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
        context.runOnClient(client -> check(!TntManager.isActive(), "F7 must also stop inside an inventory screen"));
        context.setScreen(() -> null);
        prepare(context, world, TntMode.CARPET, 0, 45);
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            player.getInventory().setItem(0, new ItemStack(Items.STICK));
            player.getInventory().setItem(40, new ItemStack(Items.TNT, 32));
            player.inventoryMenu.broadcastChanges();
        });
        sync(context, world);
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
        for (int tick = 0; tick < 3; tick++) {
            var before = context.computeOnClient(client -> client.player.getZ());
            context.waitTick();
            context.runOnClient(client -> check(TntManager.retreats() && client.player.getZ() < before,
                    "Native offhand swap must not pause backward movement"));
        }
        context.waitTicks(12);
        context.runOnClient(client -> {
            check(TntManager.isActive() && client.player.getMainHandItem().is(Items.TNT), "Offhand TNT must swap into the main hand");
            check(client.player.getOffhandItem().is(Items.STICK), "Swap must preserve the previous main-hand item");
            check(shopOpens.get() == 0, "Offhand ammunition must prevent purchases");
        });
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
    }

    private void carpetSkips(ClientGameTestContext context, TestSingleplayerContext world) {
        for (String failure : List.of("floor", "obstacle", "server", "border")) {
            prepare(context, world, TntMode.CARPET, 0, 0);
            world.getServer().runCommand("give @p minecraft:tnt 64");
            if (failure.equals("floor")) world.getServer().runCommand("setblock 2 63 2 minecraft:air");
            if (failure.equals("obstacle")) world.getServer().runCommand("setblock 2 64 2 minecraft:stone");
            if (failure.equals("server")) rejectCarpet.set(true);
            if (failure.equals("border")) world.getServer().runCommand("worldborder set 4");
            sync(context, world);
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.waitTicks(failure.equals("border") ? 6 : 20);
            context.runOnClient(client -> check(TntManager.isActive() && TntManager.retreats() && client.player.getZ() < 0.2,
                    "Skipped or rejected cells must not interrupt retreat: " + failure));
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.waitTicks(2);
            world.getServer().runOnServer(server -> {
                var level = world.getConnection().getServerPlayer().level();
                check(!level.getBlockState(new BlockPos(2, 64, 2)).is(Blocks.TNT), "Rejected cells must remain unchanged: " + failure);
                check(level.getBlockState(new BlockPos(2, 65, 2)).isAir(), "An obstacle must never produce a second TNT layer");
            });
            rejectCarpet.set(false);
            world.getServer().runCommand("setblock 2 63 2 minecraft:stone");
            world.getServer().runCommand("worldborder set 59999968");
        }
    }

    private void reload(ClientGameTestContext context, TestSingleplayerContext world, String presetId, TntMode mode, List<String> sequence) {
        prepare(context, world, mode, 0, mode == TntMode.CARPET ? 45 : 0);
        offers = sequence;
        mineriteShop = presetId.equals("minerite");
        context.runOnClient(client -> {
            if (!mineriteShop) ConfigManager.preset(presetId).tnt.shopClicks = new ArrayList<>(sequence);
            check(ConfigManager.preset(presetId).tnt.shopClicks.equals(sequence), "Reload must use the preset's purchase steps");
            var config = ConfigManager.current().copy();
            config.applyPreset(presetId, ConfigManager.preset(presetId));
            config.tntMode = mode;
            check(ConfigManager.save(config), "Reload preset must save");
        });
        world.getServer().runCommand("setblock 0 68 0 minecraft:stone");
        if (mode == TntMode.CARPET) world.getServer().runCommand("give @p minecraft:tnt 2");
        if (mode == TntMode.BUILDER) {
            world.getServer().runCommand("setblock 0 65 2 minecraft:tnt");
            world.getServer().runOnServer(server -> {
                var player = world.getConnection().getServerPlayer();
                var rod = new ItemStack(Items.BREEZE_ROD);
                rod.set(DataComponents.CUSTOM_NAME, Component.literal("Baguette du bâtisseur"));
                player.getInventory().setItem(0, rod);
                player.getInventory().setItem(20, new ItemStack(Items.TNT, 16));
                player.inventoryMenu.broadcastChanges();
            });
        }
        sync(context, world);
        int boughtBefore = purchases.get();
        int openedBefore = shopOpens.get();
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
        context.waitFor(client -> TntManager.isActive() && client.level.getBlockState(new BlockPos(0, 67, 0)).getBlock() instanceof ShulkerBoxBlock
                && TntManager.countItem(client.player.getInventory(), Items.TNT) > 16 && client.gui.screen() == null);
        context.runOnClient(client -> {
            check(purchases.get() == boughtBefore + 1 && shopOpens.get() == openedBefore + 1,
                    "Reload must make exactly one purchase through the preset's ordered sequence");
            check(Math.abs(client.player.getX() - 0.5) < 0.001, "Reload must preserve the travel lane");
        });
        context.waitTicks(5);
        if (mode == TntMode.BUILDER) {
            context.runOnClient(client -> check(TntManager.isActive() && client.player.getMainHandItem().is(Items.BREEZE_ROD)
                    && client.player.getZ() < 0.5, "Reload must restore the builder wand and resume retreat without a collision"));
            context.waitFor(client -> wandUses.get() > 0);
            context.runOnClient(client -> check(wandUses.get() > 0, "The builder must resume native use after transfer"));
        } else {
            var resumedAt = context.computeOnClient(client -> client.player.position());
            context.waitTicks(12);
            context.waitFor(client -> client.player.position().subtract(resumedAt).horizontalDistance() > 0.5);
            context.runOnClient(client -> check(client.player.getMainHandItem().is(Items.TNT) && client.player.getZ() < 0.5,
                    "Carpet must resume movement with TNT in hand after purchase"));
            world.getServer().runOnServer(server -> {
                var level = world.getConnection().getServerPlayer().level();
                int count = 0;
                for (int x = -2; x <= 2; x++) {
                    for (int z = -6; z <= 3; z++) {
                        if (level.getBlockState(new BlockPos(x, 64, z)).is(Blocks.TNT)) count++;
                        check(level.getBlockState(new BlockPos(x, 65, z)).isAir(), "Reload must retain the original carpet height");
                    }
                }
                check(count > 2, "Carpet must resume floor-only placement after reloading");
            });
        }
        context.takeScreenshot(mineriteShop ? "tnt-minerite-reloaded" : mode == TntMode.BUILDER ? "tnt-builder-reloaded" : "tnt-carpet-reloaded");
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
    }

    private void cancellation(ClientGameTestContext context, TestSingleplayerContext world) {
        prepare(context, world, TntMode.CARPET, 0, 45);
        world.getServer().runCommand("give @p minecraft:tnt 64");
        sync(context, world);
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
        context.getInput().pressKey(GLFW.GLFW_KEY_F6);
        context.runOnClient(client -> check(!TntManager.isActive() && MiningManager.isRunning(), "Mining must stop TNT placement"));
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
        context.runOnClient(client -> check(TntManager.isActive() && !MiningManager.isRunning(), "TNT placement must stop mining"));
        context.runOnClient(client -> {
            var config = ConfigManager.current().copy();
            config.applyPreset("default", ConfigManager.preset("default"));
            check(ConfigManager.save(config), "Preset change must save");
        });
        context.waitTick();
        context.runOnClient(client -> check(!TntManager.isActive(), "Preset changes must cancel placement"));
        prepare(context, world, TntMode.CARPET, 0, 45);
        world.getServer().runCommand("give @p minecraft:tnt 64");
        sync(context, world);
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
        context.runOnClient(client -> TntManager.worldChanged());
        context.runOnClient(client -> check(!TntManager.isActive(), "World-transfer reset must stop placement"));
        prepare(context, world, TntMode.CARPET, 0, 45);
        world.getServer().runCommand("give @p minecraft:tnt 64");
        sync(context, world);
        context.getInput().pressKey(GLFW.GLFW_KEY_F7);
    }

    private void prepare(ClientGameTestContext context, TestSingleplayerContext world, TntMode mode, int yaw, int pitch) {
        context.runOnClient(client -> {
            TntManager.worldChanged();
            MiningManager.stop();
            client.gui.setScreen(null);
            var config = ConfigManager.current().copy();
            config.applyPreset("kazuria", ConfigManager.preset("kazuria"));
            config.tntMode = mode;
            check(ConfigManager.save(config), "Test mode must save");
        });
        world.getServer().runCommand("fill -25 64 -25 25 70 25 minecraft:air");
        world.getServer().runCommand("tp @p 0.5 64 0.5 " + yaw + " " + pitch);
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
        });
        sync(context, world);
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
    }

    private void openShop(ServerPlayer player, int step) {
        var inventory = new SimpleContainer(27);
        int offerSlot = mineriteShop ? (step == 0 ? 14 : 11) : 0;
        inventory.setItem(offerSlot, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(offers.get(step)))));
        if (mineriteShop) {
            inventory.setItem(step == 0 ? 19 : 13, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:red_shulker_box"))));
            if (step == 1) inventory.setItem(15, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:red_stained_glass_pane"))));
        }
        player.openMenu(new SimpleMenuProvider((id, items, owner) -> new ChestMenu(MenuType.GENERIC_9x3, id, items, inventory, 3) {
            @Override
            public void clicked(int slot, int button, ContainerInput input, Player clickedBy) {
                check(slot == offerSlot && button == 0 && input == ContainerInput.PICKUP,
                        "Only the first red shulker offer and then the lime confirmation may be clicked");
                if (step + 1 < offers.size()) openShop(player, step + 1);
                else {
                    purchases.incrementAndGet();
                    var box = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:red_shulker_box")));
                    box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.TNT, 64), new ItemStack(Items.TNT, 64))));
                    player.getInventory().setItem(23, box);
                    player.closeContainer();
                    player.inventoryMenu.broadcastChanges();
                }
            }
        }, Component.literal("TNT test shop " + step)));
    }

    private static void sync(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(5);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

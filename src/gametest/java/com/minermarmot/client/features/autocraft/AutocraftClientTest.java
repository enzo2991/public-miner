package com.minermarmot.client.features.autocraft;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.alerts.AlertIcon;
import com.minermarmot.client.features.tnt.TntManager;

import com.minermarmot.client.screen.ConfigScreen;
import com.minermarmot.client.screen.widget.ConfigSectionWidget;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.HashedStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

// Virtual server workbenches and Nexo recipes exist only in the test mod.
public final class AutocraftClientTest implements FabricClientGameTest {
    private final AtomicInteger opens = new AtomicInteger();
    private final AtomicInteger incorrectPredictions = new AtomicInteger();
    private final AtomicInteger inventoryClicks = new AtomicInteger();
    private final AtomicInteger refreshRequests = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> crafts = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> craftTimes = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DelayedClick> delayed = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DelayedOpen> delayedOpens = new ConcurrentLinkedQueue<>();
    private List<AutocraftPreset.Recipe> recipes;
    private volatile boolean nativeMenu, reject, replenish, watchScreens, suppressCursorDeltas, holdSnapshots, normalizeMetadata, decorateGrid, incompatibleGrid;
    private volatile int delayTicks;
    private volatile int rejectAfterClicks;
    private volatile int openDelayTicks;
    private ServerPlayer replenishedPlayer;
    private int visibleCraftScreens;

    @Override
    public void runTest(ClientGameTestContext context) {
        System.out.println("Autocrafter runtime source: " + AutocraftManager.class.getProtectionDomain().getCodeSource().getLocation());

        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        recipes = context.computeOnClient(client -> ConfigManager.preset("minerite").autocrafter.recipes());
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                Commands.literal("craft").executes(command -> {
                    var player = command.getSource().getPlayerOrException();
                    opens.incrementAndGet();
                    if (openDelayTicks > 0) delayedOpens.add(new DelayedOpen(player, player.level().getServer().getTickCount() + openDelayTicks));
                    else openWorkbench(player);
                    return 1;
                })));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var action : List.copyOf(delayedOpens)) {
                if (action.player.level().getServer() == server && server.getTickCount() >= action.tick && delayedOpens.remove(action)
                        && !action.player.hasDisconnected()) openWorkbench(action.player);
            }
            for (var action : List.copyOf(delayed)) {
                if (server.getTickCount() >= action.tick && delayed.remove(action)
                        && action.menu.owner.containerMenu == action.menu) action.menu.applyClick(action.slot, action.button, action.input);
            }
            if (replenish && replenishedPlayer != null) {
                var inventory = replenishedPlayer.getInventory();
                if (inventory.getItem(0).isEmpty()) inventory.setItem(0, new ItemStack(Items.NETHERITE_SCRAP, 16));
                if (inventory.getItem(1).isEmpty()) inventory.setItem(1, new ItemStack(Items.GOLD_INGOT, 16));
                replenishedPlayer.containerMenu.broadcastChanges();
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (watchScreens && AutocraftManager.isBusy() && client.gui.screen() instanceof CraftingScreen) visibleCraftScreens++;
        });
        try {
            settings(context);
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                world.getServer().runCommand("gamemode survival @p");
                world.getServer().runCommand("fill -4 99 -4 4 99 4 minecraft:stone");
                world.getServer().runCommand("tp @p 0.5 100 0.5");
                watchScreens = true;
                chat(context, world);
                nativeMenu = true;
                for (int attemptDelay : new int[]{1000, 10000, 600000}) {
                    long started = Util.getMillis();
                    prepare(context, world, ingredients(recipes.getFirst(), 2), attemptDelay, "netherite");
                    context.waitFor(client -> count(client, recipes.getFirst().output) == 2 && !AutocraftManager.isBusy(), 100);
                    world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().output) == 2,
                            "The first batch must craft and close without waiting for the next attempt delay: " + attemptDelay));
                    System.out.println("Immediate first batch passed: configured delay=" + attemptDelay + "; elapsed ms=" + (Util.getMillis() - started));
                }
                prepare(context, world, List.of(new ItemStack(Items.NETHERITE_SCRAP, 8), new ItemStack(Items.GOLD_INGOT, 8)), "netherite");
                context.waitFor(client -> count(client, recipes.getFirst().output) == 2 && !AutocraftManager.isBusy());
                world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().output) == 2,
                        "Vanilla netherite must actually be crafted on the server"));
                check(visibleCraftScreens == 0, "Autocrafting must never display the native workbench screen");
                // Some servers rely on client cursor prediction and omit successful cursor deltas.
                suppressCursorDeltas = true;
                prepare(context, world, ingredients(recipes.getFirst(), 2), "netherite");
                context.waitFor(client -> count(client, recipes.getFirst().output) == 2 && !AutocraftManager.isBusy());
                world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().output) == 2,
                        "Missing cursor deltas must not block server-confirmed native crafting"));
                check(incorrectPredictions.get() == 0, "Inventory clicks must send the predicted cursor AFTER the action, like manual clicks");
                normalizeMetadata = true;
                inventoryClicks.set(0);
                prepare(context, world, ingredients(recipes.getFirst(), 16), "netherite");
                context.waitFor(client -> count(client, recipes.getFirst().output) == 16 && !AutocraftManager.isBusy());
                world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().output) == 16,
                        "Server-normalized ingredient metadata must not stall native crafting"));
                check(inventoryClicks.get() <= 12, "Bulk drag must distribute both 64-item ingredients without individual right-clicks");
                decorateGrid = true;
                inventoryClicks.set(0);
                refreshRequests.set(0);
                prepare(context, world, ingredients(recipes.getFirst(), 64), "netherite");
                context.waitFor(client -> count(client, recipes.getFirst().output) == 64 && !AutocraftManager.isBusy());
                world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().output) == 64,
                        "Partial cells with client-visible decoration must accept all four ingredient stacks before taking gold"));
                check(inventoryClicks.get() <= 17, "Full source stacks must fill each 64-item cell directly");
                System.out.println("Complete-cell native crafting: 64 ingots; targeted inputs=" + inventoryClicks.get()
                        + "; confirmation requests=" + refreshRequests.get());
                int fullStackRefreshes = refreshRequests.get();
                for (int amount : new int[]{21, 22}) {
                    refreshRequests.set(0);
                    inventoryClicks.set(0);
                    prepare(context, world, ingredients(recipes.getFirst(), amount), "netherite");
                    long started = Util.getMillis();
                    context.waitFor(client -> count(client, recipes.getFirst().output) == amount && !AutocraftManager.isBusy(), 1200);
                    world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().output) == amount,
                            "Uneven native batches must be confirmed on the server"));
                    System.out.println("Uneven native crafting: " + amount + " ingots; targeted inputs=" + inventoryClicks.get()
                            + "; confirmation requests=" + refreshRequests.get() + "; elapsed ms=" + (Util.getMillis() - started));
                    check(refreshRequests.get() <= 16, "Uneven cell quantities must not wait for each individual right-click");
                    if (amount == 21) check(inventoryClicks.get() <= 20, "A 64-item stack must complete three 21-item cells in one native drag");
                }
                check(fullStackRefreshes <= 9, "Whole-stack pickup and empty-cell placement must share one confirmation wait");
                holdSnapshots = true;
                inventoryClicks.set(0);
                prepare(context, world, ingredients(recipes.getFirst(), 64), "netherite");
                context.waitFor(client -> inventoryClicks.get() >= 2);
                context.waitTicks(10);
                check(inventoryClicks.get() == 2, "A paired transfer must await its server snapshot before touching another cell");
                holdSnapshots = false;
                world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().containerMenu.broadcastFullState());
                context.waitFor(client -> count(client, recipes.getFirst().output) == 64 && !AutocraftManager.isBusy());

                holdSnapshots = true;
                inventoryClicks.set(0);
                prepare(context, world, ingredients(recipes.getFirst(), 22), "netherite");
                context.waitFor(client -> inventoryClicks.get() == 1);
                world.getServer().runOnServer(server -> {
                    check(world.getConnection().getServerPlayer().containerMenu.getCarried().getCount() == 64,
                            "Release the pickup snapshot only after the server has applied it");
                    holdSnapshots = false;
                    world.getConnection().getServerPlayer().containerMenu.broadcastFullState();
                    holdSnapshots = true;
                });
                context.waitFor(client -> inventoryClicks.get() >= 23);
                context.waitTicks(10);
                check(inventoryClicks.get() == 23, "Rapid right-clicks must be bounded to one cell until the server confirms it");
                holdSnapshots = false;
                world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().containerMenu.broadcastFullState());
                context.waitFor(client -> count(client, recipes.getFirst().output) == 22 && !AutocraftManager.isBusy());

                inventoryClicks.set(0);
                rejectAfterClicks = 9;
                prepare(context, world, ingredients(recipes.getFirst(), 22), "netherite");
                context.waitFor(client -> AutocraftManager.isBusy());
                context.waitFor(client -> !AutocraftManager.isBusy(), 400);
                world.getServer().runOnServer(server -> {
                    var player = world.getConnection().getServerPlayer();
                    check(count(player, recipes.getFirst().output) == 0, "Partial refusal during rapid placement must never count as crafting");
                    check(count(player, recipes.getFirst().item1) == 88 && count(player, recipes.getFirst().item2) == 88,
                            "Partial refusal must return every ingredient after the bounded timeout");
                });
                rejectAfterClicks = 0;
                incompatibleGrid = true;
                prepare(context, world, ingredients(recipes.getFirst(), 64), "netherite");
                context.waitFor(client -> count(client, recipes.getFirst().output) == 64 && !AutocraftManager.isBusy());
                world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().output) == 64,
                        "Complete cells must work even when the server actually changes their components after placement"));
                List<ItemStack> halfStacks = new ArrayList<>();
                for (ItemStack stack : ingredients(recipes.getFirst(), 64)) {
                    halfStacks.add(stack.copyWithCount(32));
                    halfStacks.add(stack.copyWithCount(32));
                }
                prepare(context, world, halfStacks, "netherite");
                context.waitFor(client -> AutocraftManager.isBusy());
                context.waitFor(client -> !AutocraftManager.isBusy(), 400);
                world.getServer().runOnServer(server -> {
                    var player = world.getConnection().getServerPlayer();
                    check(count(player, recipes.getFirst().output) == 0, "A server-refused merge must never count as a craft");
                    check(count(player, recipes.getFirst().item1) == 256 && count(player, recipes.getFirst().item2) == 256,
                            "A genuinely incompatible partial merge must time out and return every ingredient");
                });
                incompatibleGrid = false;
                nativeMenu = false;
                for (var recipe : recipes) {
                    System.out.println("Checking virtual recipe: " + recipe.id);
                    prepare(context, world, ingredients(recipe, 2), recipe.id);
                    context.waitFor(client -> count(client, recipe.output) == 2 && !AutocraftManager.isBusy());
                    world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipe.output) == 2,
                            "Server must confirm both outputs for " + recipe.id));
                }
                holdSnapshots = true;
                inventoryClicks.set(0);
                prepare(context, world, ingredients(recipes.get(12), 1), "nebullyth");
                awaitCursor(context);
                var positionBefore = context.computeOnClient(client -> client.player.position());
                context.getInput().holdKey(GLFW.GLFW_KEY_W);
                try {
                    context.waitTicks(10);
                    context.runOnClient(client -> check(client.player.position().distanceTo(positionBefore) > 0.5,
                            "The hidden workbench must leave native movement available"));
                } finally { context.getInput().releaseKey(GLFW.GLFW_KEY_W); }
                check(inventoryClicks.get() == 1, "Predicted cursor contents alone must never authorize the next click");
                context.runOnClient(client -> check(client.gui.screen() == null && client.player.containerMenu instanceof CraftingMenu,
                        "The native workbench must remain active without a GUI while awaiting confirmation"));
                context.takeScreenshot("autocrafter-hidden-workbench");
                holdSnapshots = false;
                world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().containerMenu.broadcastFullState());
                context.waitFor(client -> count(client, recipes.get(12).output) == 1 && !AutocraftManager.isBusy());
                world.getServer().runCommand("tp @p 0.5 100 0.5");
                suppressCursorDeltas = false;
                // A 70-craft turn must finish both lots before another recipe, with a pause between lots.
                prepare(context, world, ingredients(recipes.get(6), 70), "astrallyth");
                craftTimes.clear();
                context.runOnClient(client -> {
                    var config = ConfigManager.current().copy();
                    config.autocrafter.delayMillis = 250;
                    check(ConfigManager.save(config), "Batch delay must save");
                });
                context.waitFor(client -> count(client, recipes.get(6).output) == 70 && !AutocraftManager.isBusy(), 1200);
                var times = List.copyOf(craftTimes);
                check(times.size() == 2 && times.get(1) - times.get(0) >= 250, "Both lots must respect the configured delay");

                craftTimes.clear();
                prepare(context, world, ingredients(recipes.get(6), 70), 10000, "astrallyth");
                context.waitFor(client -> count(client, recipes.get(6).output) == 70 && !AutocraftManager.isBusy(), 400);
                var longBatchTimes = List.copyOf(craftTimes);
                check(longBatchTimes.size() == 2 && longBatchTimes.get(1) - longBatchTimes.get(0) >= 10000
                                && longBatchTimes.get(1) - longBatchTimes.get(0) < 15000,
                        "Long batch cooldowns must last once and must not trigger the operation timeout");
                System.out.println("Ten-second batch cooldown passed: spacing ms=" + (longBatchTimes.get(1) - longBatchTimes.get(0)));

                List<ItemStack> spacedRecipes = new ArrayList<>(ingredients(recipes.getFirst(), 1));
                spacedRecipes.addAll(ingredients(recipes.get(6), 1));
                craftTimes.clear();
                prepare(context, world, spacedRecipes, 10000, "netherite", "astrallyth");
                context.waitFor(client -> count(client, recipes.getFirst().output) == 1 && !AutocraftManager.isBusy(), 100);
                int firstRecipeOpens = opens.get();
                context.waitTicks(100);
                check(opens.get() == firstRecipeOpens, "The next recipe must not open before its cooldown expires");
                context.waitFor(client -> count(client, recipes.get(6).output) == 1 && !AutocraftManager.isBusy(), 300);
                var recipeTimes = List.copyOf(craftTimes);
                check(recipeTimes.size() == 2 && recipeTimes.get(1) - recipeTimes.get(0) >= 10000
                                && recipeTimes.get(1) - recipeTimes.get(0) < 15000,
                        "The delay between recipes must apply once, with no extra wait inside the new table");
                System.out.println("Ten-second recipe cooldown passed: spacing ms=" + (recipeTimes.get(1) - recipeTimes.get(0)));

                nativeMenu = true;
                List<ItemStack> full = new ArrayList<>();
                for (int slot = 0; slot < 36; slot++) full.add(new ItemStack(Items.STONE, 64));
                full.set(0, new ItemStack(Items.NETHERITE_SCRAP, 4));
                full.set(1, new ItemStack(Items.GOLD_INGOT, 4));
                prepare(context, world, full, "netherite");
                context.waitFor(client -> count(client, recipes.getFirst().output) == 1 && !AutocraftManager.isBusy());
                full.set(0, new ItemStack(Items.NETHERITE_SCRAP, 5));
                full.set(1, new ItemStack(Items.GOLD_INGOT, 5));
                prepare(context, world, full, "netherite");
                int fullOpens = opens.get();
                context.waitTicks(20);
                check(opens.get() == fullOpens, "No output room must prevent opening /craft");
                nativeMenu = false;

                replenish = true;
                List<ItemStack> chain = new ArrayList<>(ingredients(recipes.getFirst(), 4));
                chain.add(stack(recipes.get(1).item1, 4));
                prepare(context, world, chain, "netherite", "legendaire");
                crafts.clear();
                context.waitFor(client -> count(client, recipes.get(1).output) >= 1 && crafts.contains("legendaire:1"));
                world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.get(1).output) >= 1,
                        "The dependent output must actually exist on the server"));
                var order = List.copyOf(crafts);
                check(order.size() >= 2 && order.get(0).equals("netherite:4") && order.get(1).equals("legendaire:1"),
                        "Continuous pickups must not extend the frozen quota or starve the dependent recipe: " + order);
                replenish = false;
                disable(context);

                delayTicks = 3;
                prepare(context, world, ingredients(recipes.get(12), 1), "nebullyth");
                context.waitFor(client -> count(client, recipes.get(12).output) == 1 && !AutocraftManager.isBusy());
                delayTicks = 0;
                check(visibleCraftScreens == 0, "All sixteen recipes and delayed clicks must work without displaying the workbench");

                prepare(context, world, ingredients(recipes.getFirst(), 2), "netherite");
                awaitCursor(context);
                context.runOnClient(client -> client.player.closeContainer());
                context.waitFor(client -> !AutocraftManager.isBusy());
                int escapedOpens = opens.get();
                context.waitTicks(20);
                check(opens.get() == escapedOpens, "Closing the table must allow time to open settings before retrying");
                context.runOnClient(client -> check(client.gui.screen() == null && client.player.inventoryMenu.getCarried().isEmpty(),
                        "Closing the table must restore the game and clear any in-flight cursor"));
                world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().item1) == 8
                        && count(world.getConnection().getServerPlayer(), recipes.getFirst().item2) == 8, "Closing the table must return the ingredients"));

                prepare(context, world, ingredients(recipes.getFirst(), 2), "netherite");
                awaitCursor(context);
                context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                context.waitFor(client -> client.player.containerMenu == client.player.inventoryMenu);
                context.runOnClient(client -> check(client.gui.screen() instanceof PauseScreen,
                        "Escape must preserve the native pause menu while cancelling the hidden workbench"));
                context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                context.waitFor(client -> !AutocraftManager.isBusy());
                world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().item1) == 8
                        && count(world.getConnection().getServerPlayer(), recipes.getFirst().item2) == 8, "Pause cancellation must return every ingredient"));

                prepare(context, world, ingredients(recipes.getFirst(), 2), "netherite");
                awaitCursor(context);
                context.getInput().pressKey(GLFW.GLFW_KEY_E);
                context.waitFor(client -> !AutocraftManager.isBusy());
                context.runOnClient(client -> check(client.gui.screen() instanceof InventoryScreen
                        && client.player.inventoryMenu.getCarried().isEmpty(),
                        "Opening the inventory must preserve its screen and clear the hidden workbench cursor"));
                world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().item1) == 8
                        && count(world.getConnection().getServerPlayer(), recipes.getFirst().item2) == 8, "Inventory cancellation must return every ingredient"));
                context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                disable(context);

                world.getServer().runCommand("gamemode creative @p");
                prepare(context, world, ingredients(recipes.getFirst(), 2), "netherite");
                awaitCursor(context);
                context.getInput().pressKey(GLFW.GLFW_KEY_E);
                context.waitFor(client -> !AutocraftManager.isBusy());
                context.runOnClient(client -> check(client.gui.screen() instanceof CreativeModeInventoryScreen,
                        "Creative inventory must remain visible after cancelling the hidden workbench"));
                world.getServer().runOnServer(server -> {
                    var player = world.getConnection().getServerPlayer();
                    check(player.containerMenu == player.inventoryMenu, "Creative inventory must close the actual server workbench");
                    check(count(player, recipes.getFirst().item1) == 8 && count(player, recipes.getFirst().item2) == 8,
                            "Creative inventory cancellation must return every ingredient");
                });
                context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
                disable(context);
                world.getServer().runCommand("gamemode survival @p");

                prepare(context, world, ingredients(recipes.getFirst(), 1), "netherite");
                awaitCursor(context);
                disable(context);
                context.waitFor(client -> !AutocraftManager.isBusy());

                List<ItemStack> independent = new ArrayList<>(ingredients(recipes.getFirst(), 1));
                independent.addAll(ingredients(recipes.get(6), 1));
                prepare(context, world, independent, "netherite", "astrallyth");
                awaitCursor(context);
                context.runOnClient(client -> {
                    var config = ConfigManager.current().copy();
                    config.autocrafter.recipes.put("netherite", false);
                    check(ConfigManager.save(config), "Turning the active recipe OFF must save");
                });
                context.waitFor(client -> count(client, recipes.get(6).output) == 1 && !AutocraftManager.isBusy());
                context.runOnClient(client -> check(client.player.inventoryMenu.getCarried().isEmpty(), "Recipe OFF must not leave a phantom cursor"));

                prepare(context, world, ingredients(recipes.getFirst(), 1), "netherite");
                awaitCursor(context);
                context.runOnClient(client -> {
                    var config = ConfigManager.current().copy();
                    config.applyPreset("kazuria", ConfigManager.preset("kazuria"));
                    check(ConfigManager.save(config), "Preset cancellation must save");
                });
                context.waitFor(client -> !AutocraftManager.isBusy());
                context.runOnClient(client -> check(client.player.inventoryMenu.getCarried().isEmpty(), "Preset change must refresh the cursor"));

                prepare(context, world, ingredients(recipes.getFirst(), 1), "netherite");
                awaitCursor(context);
                world.getServer().runOnServer(server -> {
                    world.getConnection().getServerPlayer().getInventory().setItem(20, new ItemStack(Items.TNT, 64));
                    world.getConnection().getServerPlayer().containerMenu.broadcastChanges();
                });
                world.getConnection().waitForClientboundPackets();
                context.runOnClient(client -> TntManager.setActive(true));
                context.waitFor(client -> !AutocraftManager.isBusy());
                context.runOnClient(client -> {
                    check(TntManager.isActive(), "TNT must wait for Autocrafter cleanup before proceeding");
                    TntManager.setActive(false);
                });
                disable(context);
                world.getServer().runCommand("tp @p 0.5 100 0.5");

                openDelayTicks = 5;
                List<ItemStack> lateItems = new ArrayList<>(ingredients(recipes.getFirst(), 1));
                lateItems.add(new ItemStack(Items.TNT, 64));
                prepare(context, world, lateItems, "netherite");
                context.waitFor(client -> AutocraftManager.isBusy() && client.player.containerMenu == client.player.inventoryMenu);
                context.runOnClient(client -> TntManager.setActive(true));
                context.waitFor(client -> !AutocraftManager.isBusy());
                int tntOpens = opens.get();
                int tntScreens = visibleCraftScreens;
                context.waitTicks(3);
                check(opens.get() == tntOpens && visibleCraftScreens == tntScreens, "Active TNT must prevent /craft and a cancelled late menu must remain hidden");
                context.runOnClient(client -> {
                    check(TntManager.isActive(), "TNT must survive the cancelled delayed /craft response");
                    TntManager.setActive(false);
                });
                openDelayTicks = 0;
                disable(context);
                world.getServer().runCommand("tp @p 0.5 100 0.5");

                reject = true;
                prepare(context, world, ingredients(recipes.getFirst(), 1), "netherite");
                context.waitFor(client -> AutocraftManager.isBusy());
                context.waitTicks(210);
                context.runOnClient(client -> check(!AutocraftManager.isBusy(), "A refused click must time out instead of blocking the loop"));
                reject = false;
                disable(context);

                prepare(context, world, List.of(), "netherite");
                int before = opens.get();
                context.waitTicks(25);
                check(opens.get() == before, "Missing ingredients must not send /craft");
                context.runOnClient(client -> {
                    var config = ConfigManager.current().copy();
                    config.applyPreset("kazuria", ConfigManager.preset("kazuria"));
                    config.autocrafter.enabled = true;
                    check(ConfigManager.save(config), "Unavailable preset preferences may save without granting availability");
                });
                context.waitTicks(25);
                check(opens.get() == before, "Kazuria must prohibit autocrafting even with a saved ON preference");
                check(visibleCraftScreens == 0, "Automatic workbenches must never have been displayed");
                layout(context);
                context.runOnClient(client -> client.getConnection().sendCommand("craft"));
                context.waitFor(client -> client.gui.screen() instanceof CraftingScreen);
                context.runOnClient(client -> {
                    check(!AutocraftManager.isBusy(), "A manual /craft must not belong to Autocrafter");
                    client.player.closeContainer();
                });
                prepare(context, world, ingredients(recipes.getFirst(), 2), "netherite");
                awaitCursor(context);
            }
            context.runOnClient(client -> check(!AutocraftManager.isBusy(), "Disconnect must discard the owned workbench"));
        } finally {
            watchScreens = false;
            context.runOnClient(client -> {

                AutocraftManager.worldChanged();
                check(ConfigManager.save(original), "Original preferences must be restored");
            });
        }
    }

    private void chat(ClientGameTestContext context, TestSingleplayerContext world) {
        context.setScreen(() -> new ChatScreen("", false));
        context.getInput().typeChars("craft continues");
        List<ItemStack> independent = new ArrayList<>(ingredients(recipes.getFirst(), 1));
        independent.addAll(ingredients(recipes.get(6), 1));
        prepare(context, world, independent, 250, "netherite", "astrallyth");
        context.waitFor(client -> count(client, recipes.getFirst().output) == 1
                && count(client, recipes.get(6).output) == 1 && !AutocraftManager.isBusy(), 100);
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof ChatScreen, "Starting and completing successive recipes must preserve chat");
            check(client.gui.screen().children().stream().anyMatch(child -> child instanceof EditBox input
                    && input.getValue().equals("craft continues")), "Crafting must preserve editable chat text");
        });
        world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().output) == 1
                && count(world.getConnection().getServerPlayer(), recipes.get(6).output) == 1,
                "Both recipes must actually complete on the server while chat remains open"));
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);

        openDelayTicks = 5;
        prepare(context, world, ingredients(recipes.getFirst(), 2), "netherite");
        context.waitFor(client -> AutocraftManager.isBusy() && client.player.containerMenu == client.player.inventoryMenu);
        context.getInput().pressKey(GLFW.GLFW_KEY_T);
        context.waitFor(client -> count(client, recipes.getFirst().output) == 2 && !AutocraftManager.isBusy(), 100);
        context.runOnClient(client -> check(client.gui.screen() instanceof ChatScreen,
                "Opening chat before the delayed workbench arrives must let crafting complete"));
        openDelayTicks = 0;
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);

        prepare(context, world, ingredients(recipes.getFirst(), 2), "netherite");
        awaitCursor(context);
        context.setScreen(() -> new ChatScreen("", false));
        context.getInput().typeChars("still crafting");
        context.waitFor(client -> count(client, recipes.getFirst().output) == 2 && !AutocraftManager.isBusy(), 100);
        context.runOnClient(client -> {
            check(client.gui.screen() instanceof ChatScreen && client.player.inventoryMenu.getCarried().isEmpty(),
                    "Opening chat during a transfer must complete crafting and clear the cursor without closing chat");
            check(client.gui.screen().children().stream().anyMatch(child -> child instanceof EditBox input
                    && input.getValue().equals("still crafting")), "Chat must stay editable during an active transfer");
        });
        world.getServer().runOnServer(server -> check(count(world.getConnection().getServerPlayer(), recipes.getFirst().output) == 2,
                "Opening chat during a transfer must not return ingredients instead of crafting"));
        disable(context);
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        check(visibleCraftScreens == 0, "Chat must never reveal the automatic workbench");
        System.out.println("Autocrafter chat checks passed: idle, delayed open, active transfer, successive recipes and editable text");
    }

    private void openWorkbench(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new Workbench(id, player), Component.literal("Virtual craft")));
    }

    private static void awaitCursor(ClientGameTestContext context) {
        try {
            context.waitFor(client -> AutocraftManager.isBusy() && !client.player.containerMenu.getCarried().isEmpty());
        } catch (AssertionError failure) {
            context.runOnClient(client -> {
                System.out.println("Autocraft cursor wait: screen=" + client.gui.screen() + ", alive=" + client.player.isAlive()
                        + ", menu=" + client.player.containerMenu + ", inventoryCursor=" + client.player.inventoryMenu.getCarried());
                for (String name : List.of("opening", "ready", "pending", "recipe", "remainingCrafts", "lastFailure")) {
                    try {
                        var field = AutocraftManager.class.getDeclaredField(name);
                        field.setAccessible(true);
                        System.out.println(name + "=" + field.get(null));
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                }
            });
            throw failure;
        }
    }

    private static void layout(ClientGameTestContext context) {
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        int width = context.computeOnClient(client -> client.getWindow().getScreenWidth());
        int height = context.computeOnClient(client -> client.getWindow().getScreenHeight());
        context.runOnClient(client -> {
            var config = ConfigManager.current().copy();
            config.applyPreset("minerite", ConfigManager.preset("minerite"));
            config.autocrafter.enabled = false;
            check(ConfigManager.save(config), "Layout settings must save");
        });
        context.setScreen(() -> new ConfigScreen(null));
        context.clickScreenButton("minermarmot.config.features");
        try {
            for (int guiScale : new int[]{1, 2}) {
                context.runOnClient(client -> {
                    client.options.guiScale().set(guiScale);
                    client.resizeGui();
                    search(client).setValue("spectral");
                });
                context.waitTick();
                context.runOnClient(client -> {
                    var list = client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                            .map(child -> (ContainerObjectSelectionList<?>) child).findFirst().orElseThrow();
                    recipeWidgets(client).forEach(widget -> check(
                            widget.getX() >= list.getX() && widget.getRight() <= list.getRight(), "Recipe controls must fit the list"));
                });
                context.takeScreenshot("autocrafter-recipe-scale-" + guiScale);
            }
            context.runOnClient(client -> client.getWindow().setWindowed(640, 480));
            context.waitFor(client -> client.gui.screen().width == 320);
            context.waitTick();
            context.runOnClient(client -> {
                var toggle = recipeWidgets(client).getFirst();
                check(toggle.getX() >= 24 && toggle.getRight() <= client.gui.screen().width - 24,
                        "Recipe switch must fit the minimum GUI width");
            });
            context.takeScreenshot("autocrafter-recipe-minimum-width");
        } finally {
            context.runOnClient(client -> {
                client.getWindow().setWindowed(width, height);
                client.options.guiScale().set(scale);
                client.resizeGui();
            });
            context.clickScreenButton("minermarmot.config.quit");
        }
    }

    private void settings(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var config = ConfigManager.current().copy();
            config.applyPreset("minerite", ConfigManager.preset("minerite"));
            config.resetPreset(ConfigManager.preset("minerite"));
            check(ConfigManager.save(config), "Minerite defaults must save");
        });
        context.setScreen(() -> new ConfigScreen(null));
        context.clickScreenButton("minermarmot.config.features");
        context.runOnClient(client -> {
            var section = sections(client).stream().filter(widget -> widget.getMessage().getString().equals("Autocrafter")).findFirst().orElseThrow();
            check(!section.isExpanded(), "Autocrafter must start collapsed");
            section.onPress(new MouseButtonInfo(0, 0));
            var categories = sections(client).stream().filter(widget -> List.of("NETHER", "END", "HEAVEN").contains(widget.getMessage().getString())).toList();
            check(categories.size() == 3 && categories.stream().noneMatch(ConfigSectionWidget::isExpanded), "All recipe categories must start collapsed");
            search(client).setValue("Autocrafter");
            check(widgets(client).stream().filter(widget -> widget instanceof CycleButton<?>).count() == 17,
                    "Search must reveal the master switch and all 16 recipes");
            var delay = widgets(client).stream().filter(widget -> widget instanceof EditBox).map(widget -> (EditBox) widget).findFirst().orElseThrow();
            delay.setValue("250");
            check(ConfigManager.current().autocrafter.delayMillis == 250, "The delay must save immediately");
            for (String invalid : List.of("", "0", "-1", "2.5", "600001", "oops")) {
                delay.setValue(invalid);
                check(ConfigManager.current().autocrafter.delayMillis == 250, "Invalid delay must preserve the last valid value");
            }
            delay.setValue("1000");
            var toggle = widgets(client).stream().filter(widget -> widget instanceof CycleButton<?>
                    && widget.getMessage().getString().equals("OFF")).map(widget -> (CycleButton<?>) widget).findFirst().orElseThrow();
            toggle.onPress(new MouseButtonInfo(0, 0));
            check(ConfigManager.current().autocrafter.recipes.get("netherite"), "Recipe switches must save immediately");
        });
        context.waitTick();
        context.takeScreenshot("autocrafter-minerite-settings");
        context.runOnClient(client -> {
            search(client).setValue("NETHER");
            sections(client).stream().filter(widget -> widget.getMessage().getString().equals("NETHER")).findFirst().orElseThrow().onPress(new MouseButtonInfo(0, 0));
            check(recipeWidgets(client).isEmpty(), "Manual collapse must work during search");
            search(client).setValue("spectral");
            check(recipeWidgets(client).size() == 1, "Search must reveal the matching recipe only");
        });
        context.clickScreenButton("minermarmot.config.general");
        context.clickScreenButton("minermarmot.config.resetPreset");
        context.runOnClient(client -> check(ConfigManager.current().autocrafter.recipes.values().stream().noneMatch(Boolean::booleanValue), "Reset must switch every recipe OFF"));
        context.clickScreenButton("minermarmot.config.quit");
    }

    private void prepare(ClientGameTestContext context, TestSingleplayerContext world, List<ItemStack> items, String... enabled) {
        prepare(context, world, items, 50, enabled);
    }

    private void prepare(ClientGameTestContext context, TestSingleplayerContext world, List<ItemStack> items, int attemptDelay, String... enabled) {
        disable(context);
        world.getConnection().waitForClientboundPackets();
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            replenishedPlayer = player;
            player.getInventory().clearContent();
            for (int slot = 0; slot < items.size(); slot++) player.getInventory().setItem(slot, items.get(slot).copy());
            player.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
        context.runOnClient(client -> {
            var config = ConfigManager.current().copy();
            config.applyPreset("minerite", ConfigManager.preset("minerite"));
            config.autocrafter.enabled = true;
            config.autocrafter.delayMillis = attemptDelay;
            config.autocrafter.recipes.replaceAll((id, value) -> List.of(enabled).contains(id));
            check(ConfigManager.save(config), "Recipe test preferences must save");
            AutocraftManager.worldChanged();
        });
    }

    private static void disable(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var config = ConfigManager.current().copy();
            config.autocrafter.enabled = false;
            check(ConfigManager.save(config), "Autocrafter must disable immediately");
            AutocraftManager.worldChanged();
        });
    }

    private static List<ItemStack> ingredients(AutocraftPreset.Recipe recipe, int crafts) {
        List<ItemStack> items = new ArrayList<>();
        for (AlertIcon ingredient : new AlertIcon[]{recipe.item1, recipe.item2}) {
            if (ingredient == null) continue;
            int count = 0;
            for (int cell = 0; cell < 9; cell++) if (recipe.ingredient(cell) == ingredient) count += crafts;
            while (count > 0) { int amount = Math.min(count, 64); items.add(stack(ingredient, amount)); count -= amount; }
        }
        return items;
    }

    private static ItemStack stack(AlertIcon icon, int count) {
        var stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.parse(icon.item)), count);
        if (icon.components.has("minecraft:item_model")) stack.set(DataComponents.ITEM_MODEL, Identifier.parse(icon.components.get("minecraft:item_model").getAsString()));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Server item " + icon.item + icon.components));
        return stack;
    }

    private final class Workbench extends CraftingMenu {
        private final ServerPlayer owner;
        private RemoteSlot cursorPrediction;
        private boolean checkPrediction;
        private boolean initialContentsSent;

        Workbench(int id, ServerPlayer owner) {
            super(id, owner.getInventory(), ContainerLevelAccess.create(owner.level(), owner.blockPosition()));
            this.owner = owner;
        }

        @Override public boolean stillValid(Player player) { return true; }

        @Override public void setSynchronizer(ContainerSynchronizer target) {
            cursorPrediction = target.createSlot();
            super.setSynchronizer(new ContainerSynchronizer() {
                @Override public void sendInitialData(AbstractContainerMenu container, List<ItemStack> items, ItemStack carried, int[] data) {
                    if (initialContentsSent && holdSnapshots) return;
                    initialContentsSent = true;
                    List<ItemStack> displayed = java.util.stream.IntStream.range(0, items.size())
                            .mapToObj(slot -> displayedStack(slot, items.get(slot))).toList();
                    target.sendInitialData(container, displayed, carried, data);
                }
                @Override public void sendSlotChange(AbstractContainerMenu container, int slot, ItemStack item) { target.sendSlotChange(container, slot, displayedStack(slot, item)); }
                @Override public void sendCarriedChange(AbstractContainerMenu container, ItemStack item) {
                    if (!suppressCursorDeltas) target.sendCarriedChange(container, item);
                    else System.out.println("Autocraft fixture omitted cursor delta: " + item.getCount());
                }
                @Override public void sendDataChange(AbstractContainerMenu container, int id, int value) { target.sendDataChange(container, id, value); }
                @Override public RemoteSlot createSlot() { return target.createSlot(); }
            });
        }

        private ItemStack displayedStack(int slot, ItemStack item) {
            if (!decorateGrid || slot < 1 || slot > 9 || item.isEmpty()) return item;
            ItemStack displayed = item.copy();
            displayed.set(DataComponents.CUSTOM_NAME, Component.literal("Server grid decoration"));
            return displayed;
        }

        @Override public void setRemoteCarried(HashedStack prediction) {
            if (checkPrediction && !reject && delayTicks == 0 && !normalizeMetadata) {
                cursorPrediction.receive(prediction);
                if (!cursorPrediction.matches(getCarried())) incorrectPredictions.incrementAndGet();
            }
            super.setRemoteCarried(prediction);
        }

        @Override public void slotsChanged(Container container) {
            if (nativeMenu) { super.slotsChanged(container); return; }
            if (owner == null) return;
            var recipe = matching();
            getSlot(0).set(recipe == null ? ItemStack.EMPTY : stack(recipe.output, 1));
        }

        private AutocraftPreset.Recipe matching() {
            for (var recipe : recipes) {
                boolean matches = true;
                for (int cell = 0; cell < 9; cell++) {
                    var ingredient = recipe.ingredient(cell);
                    var actual = getSlot(cell + 1).getItem();
                    if (ingredient == null ? !actual.isEmpty() : !ingredient.matches(actual)) { matches = false; break; }
                }
                if (matches) return recipe;
            }
            return null;
        }

        @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (slot >= 0) inventoryClicks.incrementAndGet();
            if (slot == -1 && input == ContainerInput.PICKUP_ALL) refreshRequests.incrementAndGet();
            checkPrediction = slot >= 0 && input == ContainerInput.PICKUP;
            if (reject || slot >= 0 && rejectAfterClicks > 0 && inventoryClicks.get() > rejectAfterClicks) return;
            if (delayTicks > 0) delayed.add(new DelayedClick(this, slot, button, input, owner.level().getServer().getTickCount() + delayTicks));
            else applyClick(slot, button, input);
        }

        private void applyClick(int slot, int button, ContainerInput input) {
            if (nativeMenu || slot != 0 || input != ContainerInput.QUICK_MOVE) {
                super.clicked(slot, button, input, owner);
                if (normalizeMetadata && !getCarried().isEmpty()) getCarried().remove(DataComponents.CUSTOM_NAME);
                if (incompatibleGrid) for (int cell = 1; cell <= 9; cell++) {
                    if (getSlot(cell).hasItem()) getSlot(cell).getItem().set(DataComponents.CUSTOM_NAME, Component.literal("Incompatible grid components"));
                }
                broadcastChanges();
                return;
            }
            var recipe = matching();
            check(recipe != null, "Only a complete matching pattern may be collected");
            int count = Integer.MAX_VALUE;
            for (int cell = 0; cell < 9; cell++) if (recipe.ingredient(cell) != null) count = Math.min(count, getSlot(cell + 1).getItem().getCount());
            for (int craft = 0; craft < count; craft++) check(owner.getInventory().add(stack(recipe.output, 1)), "Output must fit without dropping items");
            for (int cell = 0; cell < 9; cell++) if (recipe.ingredient(cell) != null) getSlot(cell + 1).remove(count);
            crafts.add(recipe.id + ":" + count);
            System.out.println("Server crafted batch: " + recipe.id + ":" + count);
            craftTimes.add(Util.getMillis());
            slotsChanged(null);
            broadcastChanges();
        }
    }

    private static int count(Minecraft client, AlertIcon item) { return count(client.player, item); }
    private static int count(Player player, AlertIcon item) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) if (item.matches(player.getInventory().getItem(slot))) total += player.getInventory().getItem(slot).getCount();
        return total;
    }
    private static List<AbstractWidget> widgets(Minecraft client) {
        return client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                .flatMap(entry -> entry.children().stream()).filter(child -> child instanceof AbstractWidget).map(child -> (AbstractWidget) child).toList();
    }
    private static List<ConfigSectionWidget> sections(Minecraft client) { return widgets(client).stream().filter(widget -> widget instanceof ConfigSectionWidget).map(widget -> (ConfigSectionWidget) widget).toList(); }
    private static List<AbstractWidget> recipeWidgets(Minecraft client) {
        return client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                .filter(entry -> entry.getClass().getSimpleName().equals("CraftRecipeEntry"))
                .flatMap(entry -> entry.children().stream()).map(child -> (AbstractWidget) child).toList();
    }
    private static EditBox search(Minecraft client) { return client.gui.screen().children().stream().filter(child -> child instanceof EditBox).map(child -> (EditBox) child).findFirst().orElseThrow(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record DelayedClick(Workbench menu, int slot, int button, ContainerInput input, int tick) { }
    private record DelayedOpen(ServerPlayer player, int tick) { }
}

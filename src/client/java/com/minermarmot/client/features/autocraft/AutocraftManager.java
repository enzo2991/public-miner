package com.minermarmot.client.features.autocraft;

import com.minermarmot.Main;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.tnt.TntManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.HashedStack;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Server-confirmed inventory transactions for the virtual /craft workbench. */
public final class AutocraftManager {
    private static final long TIMEOUT_MILLIS = 10000;
    private static LocalPlayer player;
    private static LocalPlayer closingPlayer;
    private static LocalPlayer cancelledOpenPlayer;
    private static ClientLevel world;
    private static ClientLevel closingWorld;
    private static ClientLevel cancelledOpenWorld;
    private static String presetId;
    private static AutocraftPreset.Recipe recipe;
    private static CraftingMenu menu;
    private static boolean opening, ready, collecting;
    private static int recipeIndex, remainingCrafts, returnSlot;
    private static int batch, outputBefore, outputCount;
    private static int clicks, snapshots;
    private static List<ItemStack> grid = List.of();
    private static Pending pending;
    private static long deadline, nextAttempt;
    private static long closeDeadline;
    private static long cancelledOpenDeadline;
    private static String lastFailure;

    private AutocraftManager() { }

    public static void initialize() {
        Main.LOGGER.info("Autocrafter transaction mode: hidden-craft-v7");
        ClientTickEvents.END_CLIENT_TICK.register(AutocraftManager::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(AutocraftManager::worldChanged));
    }

    public static boolean isBusy() { return opening || menu != null || closingPlayer != null || cancelledOpenPlayer != null; }

    public static void worldChanged() {
        finish(Minecraft.getInstance(), null);
        closingPlayer = null;
        closingWorld = null;
        cancelledOpenPlayer = null;
        cancelledOpenWorld = null;
        recipeIndex = 0;
        nextAttempt = 0;
        lastFailure = null;
    }

    private static AutocraftConfig settings() { return ConfigManager.current().autocrafter; }

    private static boolean screenBlocksCrafting(Minecraft client) {
        return client.gui.screen() != null && !(client.gui.screen() instanceof ChatScreen);
    }

    private static boolean allowed(Minecraft client) {
        var config = ConfigManager.current();
        return config != null && config.autocrafter != null && config.autocrafter.enabled
                && ConfigManager.preset(config.preset).autocrafter.available
                && client.player != null && client.level != null && client.gameMode != null
                && client.player.isAlive() && !client.player.isSpectator() && !client.player.isSleeping()
                && !client.player.isPassenger() && !TntManager.isActive();
    }

    public static boolean openScreen(ClientboundOpenScreenPacket packet) {
        Minecraft client = Minecraft.getInstance();
        // shortcut: vanilla has no /craft request token, use a server signal to distinguish concurrent manual workbenches.
        if (cancelledOpenPlayer != null && packet.getType() == MenuType.CRAFTING) {
            cancelledOpenPlayer = null;
            cancelledOpenWorld = null;
            closeWorkbench(client, packet.getContainerId());
            return true;
        }
        if (!opening || packet.getType() != MenuType.CRAFTING) return false;
        opening = false;
        if (!allowed(client) || client.player != player || client.level != world
                || !presetId.equals(ConfigManager.current().preset) || screenBlocksCrafting(client)) {
            client.getConnection().send(new ServerboundContainerClosePacket(packet.getContainerId()));
            finish(client, null);
            return true;
        }
        // Use the same native menu factory as MenuScreens, without opening a GUI or releasing controls.
        menu = MenuType.CRAFTING.create(packet.getContainerId(), player.getInventory());
        player.containerMenu = menu;
        deadline = Util.getMillis() + TIMEOUT_MILLIS;
        return true;
    }

    public static void contentsReceived(int containerId) {
        if (menu != null && menu.containerId == containerId) {
            ready = true;
            snapshots++;
        }
        if (containerId == 0 && Minecraft.getInstance().player == closingPlayer) {
            closingPlayer = null;
            closingWorld = null;
        }
    }

    private static void tick(Minecraft client) {
        long now = Util.getMillis();
        if (cancelledOpenPlayer != null) {
            if (client.player != cancelledOpenPlayer || client.level != cancelledOpenWorld || now >= cancelledOpenDeadline) {
                cancelledOpenPlayer = null;
                cancelledOpenWorld = null;
            }
            return;
        }
        if (closingPlayer != null) {
            if (client.player != closingPlayer || client.level != closingWorld || now >= closeDeadline) {
                if (client.player == closingPlayer && client.level == closingWorld) {
                    Main.LOGGER.warn("Autocrafter inventory refresh timed out [hidden-craft-v7]: cursor={}",
                            describe(closingPlayer.inventoryMenu.getCarried()));
                }
                closingPlayer = null;
                closingWorld = null;
                nextAttempt = Math.max(nextAttempt, now + 5000);
            }
            return;
        }
        if (isBusy()) {
            if (!allowed(client) || player != client.player || world != client.level
                    || !presetId.equals(ConfigManager.current().preset) || screenBlocksCrafting(client)
                    || !Boolean.TRUE.equals(settings().recipes.get(recipe.id))
                    || menu != null && player.containerMenu != menu) {
                finish(client, null);
                return;
            }
            if (now >= deadline) { finish(client, "timeout"); return; }
            if (client.isPaused() || opening || !ready) return;
            if (pending != null) {
                ItemStack actual = menu.getSlot(pending.slot).getItem();
                // A new pickup may refill the source after the server moved its old stack to the cursor.
                if (!sameIngredient(menu.getCarried(), pending.cursorAfter) || menu.getCarried().getCount() != pending.cursorAfter.getCount()
                        || !pending.slotAfter.isEmpty() && (!sameIngredient(actual, pending.slotAfter)
                        || actual.getCount() < pending.slotAfter.getCount())) return;
                pending = null;
                deadline = now + TIMEOUT_MILLIS;
            }
            if (collecting) {
                if (countOutput() < outputBefore + outputCount || !gridEmpty() || !menu.getCarried().isEmpty()) return;
                remainingCrafts -= batch;
                collecting = false;
                grid = List.of();
                lastFailure = null;
                if (remainingCrafts <= 0) { finish(client, null); return; }
                nextAttempt = now + settings().delayMillis;
                deadline = nextAttempt + TIMEOUT_MILLIS;
            }
            if (now < nextAttempt) return;
            if (grid.isEmpty()) {
                if (!gridEmpty() || !menu.getCarried().isEmpty()) { finish(client, "changed"); return; }
                grid = recipe.plan(inventory(), remainingCrafts);
                if (grid.isEmpty()) { finish(client, null); return; }
                batch = grid.stream().filter(stack -> !stack.isEmpty()).findFirst().orElseThrow().getCount();
            }
            fill(client);
            return;
        }
        if (!allowed(client) || client.isPaused() || screenBlocksCrafting(client) || now < nextAttempt
                || client.player.containerMenu != client.player.inventoryMenu || !client.player.inventoryMenu.getCarried().isEmpty()) return;
        for (int slot = 1; slot <= 4; slot++) if (client.player.inventoryMenu.getSlot(slot).hasItem()) return;
        nextAttempt = now + settings().delayMillis;
        var recipes = ConfigManager.preset(ConfigManager.current().preset).autocrafter.recipes();
        List<ItemStack> inventory = java.util.stream.IntStream.range(0, 36)
                .mapToObj(slot -> client.player.getInventory().getItem(slot)).toList();
        for (int step = 0; step < recipes.size(); step++) {
            int index = Math.floorMod(recipeIndex + step, recipes.size());
            var candidate = recipes.get(index);
            if (!Boolean.TRUE.equals(settings().recipes.get(candidate.id)) || candidate.plan(inventory, Integer.MAX_VALUE).isEmpty()) continue;
            player = client.player;
            world = client.level;
            presetId = ConfigManager.current().preset;
            recipe = candidate;
            recipeIndex = index;
            // New pickups cannot extend this recipe's turn indefinitely.
            remainingCrafts = candidate.maximumCrafts(inventory);
            clicks = snapshots = 0;
            // The idle polling cooldown must not delay an already requested workbench.
            nextAttempt = now;
            opening = true;
            ready = false;
            deadline = now + TIMEOUT_MILLIS;
            client.getConnection().sendCommand("craft");
            return;
        }
    }

    private static void fill(Minecraft client) {
        ItemStack cursor = menu.getCarried();
        for (int cell = 0; cell < 9; cell++) {
            ItemStack wanted = grid.get(cell);
            ItemStack actual = menu.getSlot(cell + 1).getItem();
            if (sameIngredient(wanted, actual) && wanted.getCount() == actual.getCount()) continue;
            if (wanted.isEmpty() || !actual.isEmpty() && (!sameIngredient(wanted, actual)
                    || actual.getCount() > wanted.getCount())) { finish(client, "changed"); return; }
            if (cursor.isEmpty()) {
                int sourceSlot = -1;
                for (int slot = 10; slot < 46; slot++) {
                    ItemStack source = menu.getSlot(slot).getItem();
                    if (source.isEmpty() || !sameIngredient(wanted, source)) continue;
                    boolean preferred = ItemStack.isSameItemSameComponents(wanted, source);
                    boolean bestPreferred = sourceSlot >= 0 && ItemStack.isSameItemSameComponents(wanted, menu.getSlot(sourceSlot).getItem());
                    if (sourceSlot < 0 || preferred && !bestPreferred || preferred == bestPreferred
                            && source.getCount() > menu.getSlot(sourceSlot).getItem().getCount()) sourceSlot = slot;
                }
                if (sourceSlot >= 0) {
                    returnSlot = sourceSlot;
                    ItemStack source = menu.getSlot(sourceSlot).getItem().copy();
                    if (actual.isEmpty() && source.getCount() <= wanted.getCount()) {
                        // An empty destination needs no intermediate metadata/merge decision.
                        ready = false;
                        nativeClick(client, sourceSlot, 0, ContainerInput.PICKUP);
                        click(client, cell + 1, 0, source, ItemStack.EMPTY);
                    } else click(client, sourceSlot, 0, ItemStack.EMPTY, source);
                    return;
                }
                finish(client, "changed");
                return;
            }
            if (!sameIngredient(wanted, cursor)) {
                returnCursor(client);
                return;
            }
            if (spread(client, cell, cursor)) return;
            int missing = wanted.getCount() - actual.getCount();
            int moved = Math.min(cursor.getCount(), missing);
            click(client, cell + 1, cursor.getCount() <= missing ? 0 : 1,
                    wanted.copyWithCount(actual.getCount() + moved), cursor.copyWithCount(cursor.getCount() - moved),
                    cursor.getCount() <= missing ? 1 : moved);
            return;
        }
        if (!cursor.isEmpty()) { returnCursor(client); return; }
        ItemStack result = menu.getSlot(0).getItem();
        if (result.isEmpty() || !recipe.output.matches(result)) return;
        outputCount = result.getCount() * batch;
        if (outputCapacity(inventory(), result) < outputCount) { finish(client, "full"); return; }
        outputBefore = countOutput();
        collecting = true;
        sendClick(client, 0, 0, ContainerInput.QUICK_MOVE);
    }

    private static void returnCursor(Minecraft client) {
        ItemStack source = menu.getSlot(returnSlot).getItem();
        ItemStack cursor = menu.getCarried();
        if (!source.isEmpty() && !sameIngredient(source, cursor)
                || source.getCount() + cursor.getCount() > cursor.getMaxStackSize()) {
            finish(client, "changed");
            return;
        }
        click(client, returnSlot, 0, cursor.copyWithCount(source.getCount() + cursor.getCount()), ItemStack.EMPTY);
    }

    private static boolean sameIngredient(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        // Servers may normalize extra metadata in transit; configured item/model predicates remain mandatory.
        return recipe.item1.matches(first) && recipe.item1.matches(second)
                || recipe.item2 != null && recipe.item2.matches(first) && recipe.item2.matches(second);
    }

    private static boolean spread(Minecraft client, int cell, ItemStack cursor) {
        ItemStack wanted = grid.get(cell);
        int existing = menu.getSlot(cell + 1).getItem().getCount();
        List<Integer> slots = new java.util.ArrayList<>();
        for (int other = cell; other < 9; other++) {
            ItemStack actual = menu.getSlot(other + 1).getItem();
            if (ItemStack.isSameItemSameComponents(wanted, grid.get(other)) && actual.getCount() == existing
                    && (actual.isEmpty() || sameIngredient(actual, cursor))) slots.add(other + 1);
        }
        // A subset can finish uneven batches: 64 items fill three 21-item cells with one left over.
        while (slots.size() > 1 && cursor.getCount() / slots.size() != wanted.getCount() - existing) slots.removeLast();
        if (slots.size() < 2) return false;
        int each = cursor.getCount() / slots.size();
        pending = new Pending(slots.getFirst(), cursor.copyWithCount(existing + each),
                cursor.copyWithCount(cursor.getCount() - each * slots.size()));
        ready = false;
        nativeClick(client, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT);
        for (int slot : slots) nativeClick(client, slot, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT);
        nativeClick(client, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT);
        requestContents(client, menu.containerId, menu.getCarried());
        deadline = Util.getMillis() + TIMEOUT_MILLIS;
        return true;
    }

    private static void click(Minecraft client, int slot, int button, ItemStack slotAfter, ItemStack cursorAfter) {
        click(client, slot, button, slotAfter, cursorAfter, 1);
    }

    private static void click(Minecraft client, int slot, int button, ItemStack slotAfter, ItemStack cursorAfter, int repetitions) {
        pending = new Pending(slot, slotAfter, cursorAfter);
        // At most one planned cell (64 items); the next cell still waits for authoritative counts.
        ready = false;
        for (int repeat = 1; repeat < repetitions; repeat++) nativeClick(client, slot, button, ContainerInput.PICKUP);
        sendClick(client, slot, button, ContainerInput.PICKUP);
    }

    private static void sendClick(Minecraft client, int slot, int button, ContainerInput input) {
        // Native clicks send post-action slot/cursor hashes, as required by server/proxy prediction checks.
        ready = false;
        nativeClick(client, slot, button, input);
        // Local prediction alone never confirms a transaction, including a collected custom recipe.
        requestContents(client, menu.containerId, menu.getCarried());
        deadline = Util.getMillis() + TIMEOUT_MILLIS;
    }

    private static void nativeClick(Minecraft client, int slot, int button, ContainerInput input) {
        clicks++;
        client.gameMode.handleContainerInput(menu.containerId, slot, button, input, player);
    }

    private static List<ItemStack> inventory() {
        return menu.slots.subList(10, 46).stream().map(slot -> slot.getItem()).toList();
    }

    static int outputCapacity(List<ItemStack> inventory, ItemStack output) {
        int count = 0;
        for (ItemStack stack : inventory) {
            if (stack.isEmpty()) count += output.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(stack, output)) count += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return count;
    }

    private static int countOutput() {
        return inventory().stream().filter(recipe.output::matches).mapToInt(ItemStack::getCount).sum();
    }

    private static boolean gridEmpty() {
        for (int slot = 1; slot <= 9; slot++) if (menu.getSlot(slot).hasItem()) return false;
        return true;
    }

    private static void finish(Minecraft client, String failure) {
        boolean advance = opening || menu != null;
        boolean screenInterrupted = advance && (screenBlocksCrafting(client) || menu != null && player.containerMenu != menu);
        if (advance && (failure != null || remainingCrafts > 0)) logStop(client, failure);
        if (opening) {
            cancelledOpenPlayer = player;
            cancelledOpenWorld = world;
            cancelledOpenDeadline = deadline;
        }
        if (menu != null && client.player == player && client.level == world && client.getConnection() != null) {
            if (player.containerMenu == menu) {
                closeWorkbench(client, menu.containerId);
                player.containerMenu = player.inventoryMenu;
            } else if (player.containerMenu == player.inventoryMenu) {
                refreshInventory(client);
            } else if (client.gui.screen() instanceof CreativeModeInventoryScreen) {
                // Creative inventory replaces the client menu locally without closing the server workbench.
                closeWorkbench(client, menu.containerId);
            }
        }
        if (failure != null && !failure.equals(lastFailure) && client.player != null) {
            client.player.sendOverlayMessage(Component.translatable("minermarmot.autocraft." + failure));
            lastFailure = failure;
        }
        if (advance) recipeIndex++;
        nextAttempt = Util.getMillis() + Math.max(settings() == null ? 1000 : settings().delayMillis, failure == null && !screenInterrupted ? 0 : 5000);
        menu = null;
        recipe = null;
        pending = null;
        grid = List.of();
        opening = ready = collecting = false;
        player = null;
        world = null;
    }

    private static void closeWorkbench(Minecraft client, int containerId) {
        client.getConnection().send(new ServerboundContainerClosePacket(containerId));
        refreshInventory(client);
    }

    private static void refreshInventory(Minecraft client) {
        // Close has no acknowledgement; a harmless stale-state click requests a full inventory/cursor refresh.
        requestContents(client, 0, ItemStack.EMPTY);
        closingPlayer = client.player;
        closingWorld = client.level;
        closeDeadline = Util.getMillis() + TIMEOUT_MILLIS;
    }

    private static void requestContents(Minecraft client, int containerId, ItemStack cursor) {
        client.getConnection().send(new ServerboundContainerClickPacket(containerId, -1, (short) -1, (byte) 0,
                ContainerInput.PICKUP_ALL, Int2ObjectMaps.emptyMap(),
                HashedStack.create(cursor, client.getConnection().decoratedHashOpsGenenerator())));
    }

    private static void logStop(Minecraft client, String failure) {
        Main.LOGGER.warn("Autocrafter stopped [hidden-craft-v7]: reason={}, recipe={}, batch={}, remaining={}, menu={}/{}, ready={}, opening={}, collecting={}, clicks={}, snapshots={}, screen={}, TNT={}",
                failure == null ? "cancelled" : failure, recipe == null ? "none" : recipe.id,
                batch, remainingCrafts,
                menu == null ? -1 : menu.containerId, client.player == null ? -1 : client.player.containerMenu.containerId,
                ready, opening, collecting, clicks, snapshots,
                client.gui.screen() == null ? "none" : client.gui.screen().getClass().getSimpleName(), TntManager.isActive());
        if (menu != null) {
            Main.LOGGER.warn("Autocrafter contents: cursor={}, expectedCursor={}, pendingSlot={}, actualSlot={}, expectedSlot={}, grid={}, result={}",
                    describe(menu.getCarried()), pending == null ? "none" : describe(pending.cursorAfter),
                    pending == null ? -1 : pending.slot,
                    pending == null ? "none" : describe(menu.getSlot(pending.slot).getItem()),
                    pending == null ? "none" : describe(pending.slotAfter),
                    menu.slots.subList(1, 10).stream().map(slot -> describe(slot.getItem())).toList(), describe(menu.getSlot(0).getItem()));
        }
    }

    private static String describe(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        // Keep server names, lore and custom data values out of diagnostics.
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount()
                + "[model=" + stack.get(DataComponents.ITEM_MODEL)
                + ",components=" + Integer.toHexString(stack.getComponentsPatch().hashCode()) + "]";
    }

    private record Pending(int slot, ItemStack slotAfter, ItemStack cursorAfter) { }
}

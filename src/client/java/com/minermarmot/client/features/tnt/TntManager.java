package com.minermarmot.client.features.tnt;

import com.minermarmot.client.integration.VersionCheck;

import com.minermarmot.client.minermarmotClient;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.mining.MiningManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.world.phys.Vec2;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Reload sequence with a single purchase and bounded placement retries. */
public final class TntManager {
    private enum State { BUILD, TNT_EQUIP, SHOP, DELIVERY, EQUIP, SNEAK, PLACE_WAIT, OPEN_WAIT, TRANSFER }
    private static final int TIMEOUT = 200;
    static final float CARPET_PITCH = 30;
    private static final int[] CARPET_OFFSETS = {-2, -1, 0, 1, 2, 1, 0, -1};
    private static KeyMapping toggleKey;
    private static TntMode mode;
    private static String presetId;
    private static LocalPlayer player;
    private static boolean retreating;
    private static int carpetHeight, carpetStep;
    private static int shopStep, shopStateId;
    private static AbstractContainerMenu shopMenu;
    private static boolean active;
    private static State state = State.BUILD;
    private static boolean placedDuringTntEquip;
    private static boolean tntCompactionPending;
    private static int ticks;
    private static float yaw, pitch;
    private static double centeredLane;
    private static double reloadFlightY;
    private static int rodSlot;
    private static ClientLevel world;
    private static BlockPos ceiling, shulker;
    private static AbstractContainerMenu transferMenu;
    private static boolean shiftOwned;
    private static int[] shulkersBeforePurchase;
    private static int purchasedSlot;
    private static int transferCount, transferTicks;
    private static Vec3 previousPosition;
    private static int stuckTicks;

    private TntManager() { }

    public static void initialize() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minermarmot.toggleTnt",
                GLFW.GLFW_KEY_F7, minermarmotClient.KEY_CATEGORY));
        ClientTickEvents.START_CLIENT_TICK.register(TntManager::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(TntManager::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stop(client, null));
    }

    public static boolean handleToggleKey(InputConstants.Key key) {
        Minecraft client = Minecraft.getInstance();
        if (toggleKey != null && toggleKey.matches(key) && client.player != null
                && !(client.gui.screen() instanceof KeyBindsScreen) && !(client.gui.screen() instanceof ChatScreen)) {
            setActive(!active);
            return true;
        }
        return false;
    }

    public static Component toggleKeyName() { return toggleKey.getTranslatedKeyMessage(); }

    public static void worldChanged() { stop(Minecraft.getInstance(), null); }

    private static boolean canAct(Minecraft client) {
        return VersionCheck.isCompatible() && client.player != null && client.level != null && client.gameMode != null && client.player.isAlive()
                && !client.player.isSpectator() && !client.player.isPassenger() && !client.player.isSleeping();
    }

    public static boolean controlsMovement() { return active && canAct(Minecraft.getInstance()); }
    public static boolean smoothsCamera() { return controlsMovement() && mode == TntMode.CARPET && (state == State.BUILD || state == State.TNT_EQUIP); }
    public static boolean retreats() { return (state == State.BUILD || state == State.TNT_EQUIP) && retreating; }
    public static boolean sneaks() { return shiftOwned; }
    public static Vec2 movementInput() {
        if (!retreats()) return Vec2.ZERO;
        return backwardInput(yaw, Minecraft.getInstance().player.getYRot());
    }

    static Vec2 backwardInput(float heading, float cameraYaw) {
        double angle = Math.toRadians(heading - cameraYaw);
        return new Vec2((float) Math.sin(angle), (float) -Math.cos(angle));
    }

    public static void finishMovement() {
        Minecraft client = Minecraft.getInstance();
        if (!controlsMovement() || client.isPaused()) return;
        centerOnTravelAxis(client);
        if (!active) return;
        if (state != State.BUILD && state != State.TNT_EQUIP) haltReloadMovement(client);
        // Apply the sweep after vanilla saves the old rotation, so rendering interpolates between ticks.
        if (mode == TntMode.CARPET && retreats()) setCarpetView(client);
    }

    private static TntPreset preset() { return ConfigManager.preset(presetId).tnt; }
    private static Item shulkerItem() { return BuiltInRegistries.ITEM.getValue(Identifier.parse(preset().shulkerItem)); }

    private static void startTick(Minecraft client) {
        while (toggleKey.consumeClick()) {
            if (!(client.gui.screen() instanceof ChatScreen)) {
                setActive(!active);
            }
        }
        if (!active) return;
        if (!canAct(client) || world != client.level || player != client.player || !presetId.equals(ConfigManager.current().preset) || mode != ConfigManager.current().tntMode || !preset().allows(mode)) {
            stop(client, null);
            return;
        }
        if (client.isPaused()) return;
        retreating = false;
        client.player.setSprinting(false);
        if (state != State.BUILD) {
            if (state == State.TNT_EQUIP) {
                if (client.gui.screen() != null && !(client.gui.screen() instanceof ChatScreen)) { stopHorizontal(client); return; }
                retreating = client.player.getMainHandItem().is(Items.TNT);
                if (!retreating) stopHorizontal(client);
                return;
            }
            client.player.setYRot(yaw);
            if (state == State.SNEAK || state == State.PLACE_WAIT) client.player.setXRot(-90);
            haltReloadMovement(client);
            return;
        }
        if (client.gui.screen() != null && !(client.gui.screen() instanceof ChatScreen)) {
            stopHorizontal(client);
            return;
        }
        if (mode != TntMode.CARPET) {
            client.player.setYRot(yaw);
            client.player.setXRot(pitch);
        }
        centerOnTravelAxis(client);
        if (!active) return;
        if (needsReload(countTnt(client), mode)) {
            beginReload(client);
            return;
        }
        if (mode == TntMode.BUILDER && !isBuilderRod(client.player.getMainHandItem(), preset().builderWandName)) {
            stop(client, "wandRemoved");
            return;
        }
        if (mode == TntMode.CARPET) {
            retreating = true;
            if (!equipTnt(client)) return;
        } else {
            BlockHitResult hit = placementHit(client);
            if (hit != null) retreating = shouldRetreat(hit.getLocation().subtract(client.player.getEyePosition()).horizontalDistance());
        }
        if (!retreating) stopHorizontal(client);
        if (retreating && client.player.position().distanceToSqr(previousPosition) < 0.0001) stuckTicks++;
        else stuckTicks = 0;
        previousPosition = client.player.position();
        if (stuckTicks > 40) stop(client, "blocked");
    }

    public static boolean isActive() { return active; }

    public static void setActive(boolean value) {
        Minecraft client = Minecraft.getInstance();
        if (active == value) return;
        if (!value) {
            stop(client, "stopped");
            return;
        }
        if (!VersionCheck.isCompatible()) {
            client.setScreenAndShow(VersionCheck.configScreen(client.gui.screen()));
            return;
        }
        if (!canAct(client)) return;
        presetId = ConfigManager.current().preset;
        mode = ConfigManager.current().tntMode;
        if (!preset().allows(mode)) { message(client, "unavailable"); return; }
        if (mode == TntMode.BUILDER && !isBuilderRod(client.player.getMainHandItem(), preset().builderWandName)) {
            message(client, "holdWand", preset().builderWandName);
            return;
        }
        MiningManager.stop();
        active = true;
        world = client.level;
        player = client.player;
        yaw = cardinalYaw(player.getYRot());
        pitch = player.getXRot();
        centeredLane = Direction.fromYRot(yaw).getAxis() == Direction.Axis.Z ? player.getX() : player.getZ();
        rodSlot = player.getInventory().getSelectedSlot();
        ceiling = shulker = null;
        transferMenu = shopMenu = null;
        retreating = false;
        carpetHeight = player.blockPosition().getY();
        carpetStep = 0;
        tntCompactionPending = true;
        if (mode == TntMode.CARPET) setCarpetView(client);
        stuckTicks = 0;
        previousPosition = player.position();
        enter(State.BUILD);
        message(client, "started", Component.translatable(mode.translationKey()));
    }

    static boolean isBuilderRod(ItemStack stack, String name) {
        return stack.is(Items.BREEZE_ROD) && name.equals(net.minecraft.ChatFormatting.stripFormatting(stack.getHoverName().getString()));
    }

    static boolean needsReload(int count, TntMode mode) { return count <= (mode == TntMode.BUILDER ? 16 : 0); }
    static boolean shouldRetreat(double distance) { return distance < 4.0; }
    static float cardinalYaw(float angle) { return Math.round(angle / 90.0F) * 90.0F; }

    static Vec3 centeredPosition(Vec3 position, float facingYaw, double lane) {
        return Direction.fromYRot(facingYaw).getAxis() == Direction.Axis.Z
                ? new Vec3(lane, position.y, position.z)
                : new Vec3(position.x, position.y, lane);
    }

    private static void centerOnTravelAxis(Minecraft client) {
        Vec3 centered = centeredPosition(client.player.position(), yaw, centeredLane);
        var bounds = client.player.getBoundingBox().move(centered.subtract(client.player.position()));
        if (!client.level.noCollision(client.player, bounds) || !client.level.getWorldBorder().isWithinBounds(bounds)) {
            stop(client, "blocked");
            return;
        }
        client.player.setPos(centered.x, centered.y, centered.z);
        // Keep the travel axis free, while removing sideways drift from the preceding movement.
        Vec3 velocity = client.player.getDeltaMovement();
        boolean alongZ = Direction.fromYRot(yaw).getAxis() == Direction.Axis.Z;
        client.player.setDeltaMovement(alongZ
                ? new Vec3(0, velocity.y, velocity.z) : new Vec3(velocity.x, velocity.y, 0));
    }

    private static void tick(Minecraft client) {
        if (!active || !canAct(client) || client.isPaused()) return;
        if (state == State.TNT_EQUIP) {
            if (++ticks > TIMEOUT) { stop(client, "timeout"); return; }
            reload(client);
        }
        if (state == State.BUILD || state == State.TNT_EQUIP) {
            if (client.gui.screen() != null && !(client.gui.screen() instanceof ChatScreen)) return;
            BlockHitResult hit = placementHit(client);
            if (mode == TntMode.CARPET) carpetStep = (carpetStep + 1) % CARPET_OFFSETS.length;
            if (hit == null) return;
            if (mode == TntMode.CARPET && !client.player.getMainHandItem().is(Items.TNT)) return;
            var result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
            if (mode == TntMode.CARPET && !result.consumesAction()) return;
            if (state == State.TNT_EQUIP) placedDuringTntEquip = true;
            if (mode == TntMode.BUILDER && !result.consumesAction()) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            }
            client.player.swing(InteractionHand.MAIN_HAND);
            if (mode == TntMode.BUILDER) reselectBuilderRod(client);
            return;
        }
        if (state != State.TNT_EQUIP) haltReloadMovement(client);
        if (++ticks > TIMEOUT) { stop(client, "timeout"); return; }
        reload(client);
    }

    private static BlockHitResult placementHit(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        if (mode == TntMode.CARPET) {
            Vec3 top = carpetFloorPoint(eye, client.player.getYRot(), carpetHeight);
            if (top == null) return null;
            BlockPos target = BlockPos.containing(top);
            int lateral = Direction.fromYRot(yaw).getAxis() == Direction.Axis.Z ? target.getX() : target.getZ();
            if (Math.abs(lateral - (int) Math.floor(centeredLane)) > 2 || !client.level.getWorldBorder().isWithinBounds(target)
                    || client.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(target))) return null;
            return carpetHit(client.level, eye, top, client.player.blockInteractionRange(), CollisionContext.of(client.player));
        }
        HitResult aimed = client.player.raycastHitResult(1.0F, client.player);
        if (!(aimed instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || eye.distanceTo(hit.getLocation()) > client.player.blockInteractionRange()) return null;
        return client.level.getBlockState(hit.getBlockPos()).is(Blocks.TNT) ? hit : null;
    }

    static float carpetYawOffset(float heading, double lane, double eyeHeight, int step) {
        Direction right = Direction.fromYRot(heading).getClockWise();
        int side = right.getAxis() == Direction.Axis.X ? right.getStepX() : right.getStepZ();
        double lateral = (Math.floor(lane) + 0.5 - lane) * side + CARPET_OFFSETS[step];
        double distance = eyeHeight / Math.tan(Math.toRadians(CARPET_PITCH));
        return distance <= 0 ? 0 : (float) Math.toDegrees(Math.asin(Math.clamp(lateral / distance, -1, 1)));
    }

    private static void setCarpetView(Minecraft client) {
        client.player.setYRot(yaw + carpetYawOffset(yaw, centeredLane, client.player.getEyePosition().y - carpetHeight, carpetStep));
        client.player.setXRot(CARPET_PITCH);
    }

    static Vec3 carpetFloorPoint(Vec3 eye, float aimYaw, int height) {
        double distance = (eye.y - height) / Math.tan(Math.toRadians(CARPET_PITCH));
        if (distance <= 0) return null;
        double angle = Math.toRadians(aimYaw);
        return new Vec3(eye.x - Math.sin(angle) * distance, height, eye.z + Math.cos(angle) * distance);
    }

    static BlockHitResult carpetHit(BlockGetter level, Vec3 eye, Vec3 top, double reach, CollisionContext context) {
        BlockPos target = BlockPos.containing(top);
        var support = level.getBlockState(target.below());
        if (!level.getBlockState(target).canBeReplaced() || support.canBeReplaced() || support.is(Blocks.TNT)) return null;
        if (eye.distanceTo(top) > reach) return null;
        BlockHitResult hit = level.clip(new ClipContext(eye, top.add(0, -0.001, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
        return hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP
                && hit.getBlockPos().equals(target.below()) && Math.abs(hit.getLocation().y - target.getY()) < 0.001 ? hit : null;
    }

    private static boolean equipTnt(Minecraft client) {
        ItemStack held = client.player.getMainHandItem();
        int heldCount = held.is(Items.TNT) ? held.getCount() : 0;
        var inventory = client.player.getInventory();
        if (tntCompactionPending || heldCount <= 10) {
            boolean compacted = compactTnt(client);
            tntCompactionPending = false;
            if (!active) return false;
            if (compacted) {
                placedDuringTntEquip = false;
                enter(State.TNT_EQUIP);
                held = client.player.getMainHandItem();
                heldCount = held.is(Items.TNT) ? held.getCount() : 0;
            }
        }
        int slot = findTntSlot(inventory, heldCount);
        if (slot < 0) return heldCount > 0;
        if (slot >= 9) {
            var menuSlot = client.player.inventoryMenu.findSlot(inventory, slot);
            if (menuSlot.isEmpty() || !client.player.inventoryMenu.getCarried().isEmpty()) { stop(client, "inventoryBusy"); return false; }
            int target = rodSlot;
            client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, menuSlot.getAsInt(), target,
                    ContainerInput.SWAP, client.player);
            inventory.setSelectedSlot(target);
            // Keep placing while inventory updates settle, without swapping again or buying early.
            placedDuringTntEquip = false;
            enter(State.TNT_EQUIP);
            return false;
        }
        inventory.setSelectedSlot(slot);
        return client.player.getMainHandItem().is(Items.TNT);
    }

    private static boolean compactTnt(Minecraft client) {
        var inventory = client.player.getInventory();
        var menu = client.player.inventoryMenu;
        boolean compacted = false;
        for (int slot = 0; slot < Math.min(41, inventory.getContainerSize()); slot++) {
            if (slot >= 36 && slot != 40 || !hasTntMergeTarget(inventory, slot)) continue;
            var menuSlot = menu.findSlot(inventory, slot);
            if (client.player.containerMenu != menu || menuSlot.isEmpty() || !menu.getCarried().isEmpty()) {
                stop(client, "inventoryBusy");
                return false;
            }
            // Vanilla double-click gathers matching partial stacks before splitting a full stack.
            client.gameMode.handleContainerInput(menu.containerId, menuSlot.getAsInt(), 0, ContainerInput.PICKUP, client.player);
            client.gameMode.handleContainerInput(menu.containerId, menuSlot.getAsInt(), 0, ContainerInput.PICKUP_ALL, client.player);
            client.gameMode.handleContainerInput(menu.containerId, menuSlot.getAsInt(), 0, ContainerInput.PICKUP, client.player);
            if (!menu.getCarried().isEmpty()) { stop(client, "inventoryBusy"); return false; }
            compacted = true;
        }
        return compacted;
    }

    static boolean hasTntMergeTarget(net.minecraft.world.Container inventory, int slot) {
        ItemStack stack = inventory.getItem(slot);
        if (!stack.is(Items.TNT) || stack.getCount() >= stack.getMaxStackSize()) return false;
        for (int other = 0; other < Math.min(41, inventory.getContainerSize()); other++) {
            if (other == slot || other >= 36 && other != 40) continue;
            ItemStack candidate = inventory.getItem(other);
            if (candidate.getCount() < candidate.getMaxStackSize() && ItemStack.isSameItemSameComponents(stack, candidate)) return true;
        }
        return false;
    }

    static int findTntSlot(net.minecraft.world.Container inventory, int heldCount) {
        if (heldCount > 10) return -1;
        int slot = -1;
        int largest = heldCount > 0 ? 10 : 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.TNT) && stack.getCount() > largest) {
                slot = i;
                largest = stack.getCount();
            }
        }
        if (inventory.getContainerSize() > 40 && inventory.getItem(40).is(Items.TNT)
                && inventory.getItem(40).getCount() > largest) return 40;
        return slot;
    }

    private static void beginReload(Minecraft client) {
        retreating = false;
        reloadFlightY = client.player.getY();
        haltReloadMovement(client);
        if (preset().shopClicks.isEmpty()) { stop(client, "empty"); return; }
        if (!hasInventorySpace(client)) { stop(client, "space"); return; }
        if (!client.player.inventoryMenu.getCarried().isEmpty()) { stop(client, "inventoryBusy"); return; }
        ceiling = findCeiling(client);
        if (ceiling == null) { stop(client, "ceiling"); return; }
        shulker = ceiling.below();
        shulkersBeforePurchase = new int[36];
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            shulkersBeforePurchase[i] = stack.is(shulkerItem()) ? stack.getCount() : 0;
        }
        shopStep = 0;
        shopMenu = null;
        purchasedSlot = -1;
        client.player.connection.sendCommand("shop");
        enter(State.SHOP);
    }

    private static void reselectBuilderRod(Minecraft client) {
        var inventory = client.player.getInventory();
        int originalSlot = inventory.getSelectedSlot();
        int otherSlot = (originalSlot + 1) % 9;
        // Prefer another item (or an empty hand) instead of a second builder rod.
        for (int i = 1; i < 9; i++) {
            int candidate = (originalSlot + i) % 9;
            if (!isBuilderRod(inventory.getItem(candidate), preset().builderWandName)) {
                otherSlot = candidate;
                break;
            }
        }
        inventory.setSelectedSlot(otherSlot);
        try {
            // Send both changes explicitly: a local away/back selection within one tick
            // would otherwise be collapsed into no change by the normal item synchronization.
            client.player.connection.send(new ServerboundSetCarriedItemPacket(otherSlot));
        } finally {
            inventory.setSelectedSlot(originalSlot);
            client.player.connection.send(new ServerboundSetCarriedItemPacket(originalSlot));
        }
    }

    private static void reload(Minecraft client) {
        switch (state) {
            case TNT_EQUIP -> {
                if (ticks >= 3 && (placedDuringTntEquip || client.player.getMainHandItem().is(Items.TNT) || countTnt(client) == 0)) enter(State.BUILD);
            }
            case SHOP -> {
                if (!hasContainer(client) || ticks < 4) return;
                AbstractContainerMenu menu = client.player.containerMenu;
                if (shopMenu == menu && shopStateId == menu.getStateId()) return;
                if (clickShopItem(client, BuiltInRegistries.ITEM.getValue(Identifier.parse(preset().shopClicks.get(shopStep))))) {
                    shopMenu = menu;
                    shopStateId = menu.getStateId();
                    if (++shopStep == preset().shopClicks.size()) enter(State.DELIVERY);
                    else ticks = 0;
                }
            }
            case DELIVERY -> {
                if (receivedShulker(client)) { closeContainer(client); enter(State.EQUIP); }
            }
            case EQUIP -> {
                if (hasContainer(client) || ticks < 3) return;
                if (client.gui.screen() != null && !(client.gui.screen() instanceof ChatScreen)) { stop(client, "unexpectedScreen"); return; }
                if (client.player.getInventory().getSelectedSlot() == purchasedSlot
                        && client.player.getMainHandItem().is(shulkerItem())) {
                    client.player.setXRot(-90);
                    shiftOwned = true;
                    enter(State.SNEAK);
                } else {
                    equipShulker(client);
                }
            }
            case SNEAK -> {
                if (ticks < 5) return; // Allow the sneak input/packet to reach the server first.
                if (!client.player.isShiftKeyDown()) return;
                if (!client.player.getMainHandItem().is(shulkerItem())) {
                    equipShulker(client);
                    return;
                }
                if (tryPlaceShulker(client)) enter(State.PLACE_WAIT);
            }
            case PLACE_WAIT -> {
                if (ticks < 3) return;
                if (!(client.level.getBlockState(shulker).getBlock() instanceof ShulkerBoxBlock)) {
                    // Retry only if the box is still in hand and no placed box is visible.
                    // Keep the timeout running and never purchase again on a failed placement.
                    if ((ticks == 10 || ticks == 20) && client.player.isShiftKeyDown()) tryPlaceShulker(client);
                    return;
                }
                releaseShift(client);
                enter(State.OPEN_WAIT);
            }
            case OPEN_WAIT -> {
                if (client.player.containerMenu instanceof ShulkerBoxMenu) {
                    transferMenu = client.player.containerMenu;
                    transferCount = transferTicks = 0;
                    enter(State.TRANSFER);
                    return;
                }
                if (hasContainer(client) || (client.gui.screen() != null && !(client.gui.screen() instanceof ChatScreen))) {
                    stop(client, "unexpectedScreen"); return;
                }
                if (ticks == 5) {
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atBottomCenterOf(shulker), Direction.DOWN, shulker, false));
                }
            }
            case TRANSFER -> transfer(client);
            default -> { }
        }
    }

    private static void transfer(Minecraft client) {
        if (client.player.containerMenu != transferMenu || !(transferMenu instanceof ShulkerBoxMenu)) {
            stop(client, "transferClosed"); return;
        }
        if (ticks < 4) return;
        if (transferCount > 0) {
            if (++transferTicks < 4) return;
            if (countShulkerTnt(transferMenu) >= transferCount) {
                if (transferTicks > 40) stop(client, "transferBlocked");
                return;
            }
            transferCount = 0;
        }
        transferCount = countShulkerTnt(transferMenu);
        if (transferCount > 0) {
            transferTicks = 0;
            // Queue every TNT stack in one tick; wait for the batch rather than each individual slot.
            quickMoveTnt(transferMenu, slot -> client.gameMode.handleContainerInput(
                    transferMenu.containerId, slot, 0, ContainerInput.QUICK_MOVE, client.player));
            return;
        }
        if (needsReload(countTnt(client), mode)) {
            stop(client, "insufficient"); return;
        }
        closeContainer(client);
        client.player.getInventory().setSelectedSlot(rodSlot);
        if (mode == TntMode.CARPET) setCarpetView(client);
        else {
            client.player.setYRot(yaw);
            client.player.setXRot(pitch);
        }
        if (mode == TntMode.BUILDER && !isBuilderRod(client.player.getMainHandItem(), preset().builderWandName)) {
            stop(client, "wandRemoved"); return;
        }
        stuckTicks = 0;
        previousPosition = client.player.position();
        enter(State.BUILD);
    }

    private static int countShulkerTnt(AbstractContainerMenu menu) {
        int count = 0;
        for (int i = 0; i < 27; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.is(Items.TNT)) count += stack.getCount();
        }
        return count;
    }

    static void quickMoveTnt(AbstractContainerMenu menu, java.util.function.IntConsumer move) {
        for (int i = 0; i < 27; i++) {
            if (menu.getSlot(i).getItem().is(Items.TNT)) move.accept(i);
        }
    }

    private static boolean clickShopItem(Minecraft client, Item item) {
        if (!hasContainer(client)) return false;
        AbstractContainerMenu menu = client.player.containerMenu;
        if (!menu.getCarried().isEmpty()) {
            stop(client, "inventoryBusy");
            return false;
        }
        int slot = findShopSlot(menu, client.player.getInventory(), item);
        if (slot < 0) return false;
        client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, client.player);
        return true;
    }

    static int findShopSlot(AbstractContainerMenu menu, net.minecraft.world.Container inventory, Item item) {
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.getSlot(i);
            if (slot.container != inventory && slot.getItem().is(item)) return i;
        }
        return -1;
    }

    private static void equipShulker(Minecraft client) {
        var inventory = client.player.getInventory();
        if (purchasedSlot < 0 || !inventory.getItem(purchasedSlot).is(shulkerItem())) {
            stop(client, "deliveryMissing"); return;
        }
        if (purchasedSlot < 9) inventory.setSelectedSlot(purchasedSlot);
        else {
            // Keep the builder rod in its original slot; SWAP also handles non-hotbar delivery.
            int target = (rodSlot + 1) % 9;
            var menuSlot = client.player.inventoryMenu.findSlot(inventory, purchasedSlot);
            if (menuSlot.isEmpty()) { stop(client, "deliveryMissing"); return; }
            client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId, menuSlot.getAsInt(), target,
                    ContainerInput.SWAP, client.player);
            purchasedSlot = target;
            inventory.setSelectedSlot(target);
        }
    }

    private static BlockHitResult ceilingHit(BlockPos pos) {
        return new BlockHitResult(Vec3.atBottomCenterOf(pos), Direction.DOWN, pos, false);
    }

    private static void stopHorizontal(Minecraft client) {
        client.player.setDeltaMovement(0, client.player.getDeltaMovement().y, 0);
    }

    private static void haltReloadMovement(Minecraft client) {
        Vec3 velocity = client.player.getDeltaMovement();
        if (client.player.getAbilities().flying) {
            var bounds = client.player.getBoundingBox().move(0, reloadFlightY - client.player.getY(), 0);
            if (client.level.noCollision(client.player, bounds)) {
                client.player.setPos(client.player.getX(), reloadFlightY, client.player.getZ());
            }
        }
        client.player.setDeltaMovement(0, client.player.getAbilities().flying ? 0 : velocity.y, 0);
    }

    private static boolean tryPlaceShulker(Minecraft client) {
        if (!client.player.getMainHandItem().is(shulkerItem()) || (client.gui.screen() != null && !(client.gui.screen() instanceof ChatScreen))) return false;
        BlockPos currentCeiling = findCeiling(client);
        if (currentCeiling == null) return false;
        ceiling = currentCeiling;
        shulker = ceiling.below();
        client.player.setXRot(-90);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, ceilingHit(ceiling));
        return true;
    }

    private static BlockPos findCeiling(Minecraft client) {
        return findCeiling(client.level, client.player.getEyePosition(), client.player.getBoundingBox().maxY,
                client.player.blockInteractionRange(), CollisionContext.of(client.player));
    }

    static BlockPos findCeiling(BlockGetter level, Vec3 eye, double headTop, double reach, CollisionContext context) {
        // Absolute head height works across crouching, fractional Y and block-boundary changes.
        BlockPos column = BlockPos.containing(eye);
        for (int y = (int) Math.ceil(headTop + 1); y <= Math.floor(eye.y + reach); y++) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            if (level.getBlockState(pos).isAir()) continue;
            // The box must be above the player's head, in the same X/Z column.
            if (!level.getBlockState(pos.below()).isAir()) return null;
            Vec3 hit = Vec3.atBottomCenterOf(pos);
            if (eye.distanceTo(hit) > reach) return null;
            BlockHitResult visible = level.clip(new ClipContext(eye, hit.add(0, 0.001, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
            return visible.getType() == HitResult.Type.BLOCK && visible.getBlockPos().equals(pos)
                    && visible.getDirection() == Direction.DOWN ? pos : null;
        }
        return null;
    }

    private static int countTnt(Minecraft client) {
        var offhand = client.player.getOffhandItem();
        return countItem(client.player.getInventory(), Items.TNT) + (offhand.is(Items.TNT) ? offhand.getCount() : 0);
    }
    static int countItem(net.minecraft.world.Container inventory, Item item) {
        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static boolean receivedShulker(Minecraft client) {
        purchasedSlot = findDeliverySlot(client.player.getInventory(), shulkersBeforePurchase, shulkerItem());
        return purchasedSlot >= 0;
    }
    static int findDeliverySlot(net.minecraft.world.Container inventory, int[] before, Item item) {
        if (countItem(inventory, item) <= java.util.Arrays.stream(before).sum()) return -1;
        for (int i = 0; i < before.length; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item) && stack.getCount() > before[i]) return i;
        }
        return -1;
    }
    private static boolean hasInventorySpace(Minecraft client) {
        for (int i = 0; i < 36; i++) if (client.player.getInventory().getItem(i).isEmpty()) return true;
        return false;
    }
    private static boolean hasContainer(Minecraft client) {
        return client.player.containerMenu != client.player.inventoryMenu;
    }
    private static void closeContainer(Minecraft client) {
        if (hasContainer(client)) client.player.closeContainer();
    }
    private static void enter(State next) { state = next; ticks = 0; }
    private static void releaseMovement(Minecraft client) { retreating = false; }
    private static void releaseShift(Minecraft client) { shiftOwned = false; }
    private static void stop(Minecraft client, String reason) {
        releaseMovement(client);
        releaseShift(client);
        if (active && client.player == player && client.level == world) {
            stopHorizontal(client);
            client.player.getInventory().setSelectedSlot(rodSlot);
            client.player.setYRot(yaw);
            client.player.setXRot(pitch);
        }
        active = false;
        world = null;
        player = null;
        shopMenu = null;
        ceiling = shulker = null;
        transferMenu = null;
        shulkersBeforePurchase = null;
        enter(State.BUILD);
        if (reason != null) message(client, reason);
    }
    private static void message(Minecraft client, String key, Object... args) {
        if (client.player != null) client.player.sendSystemMessage(Component.translatable("minermarmot.tnt." + key, args));
    }
}

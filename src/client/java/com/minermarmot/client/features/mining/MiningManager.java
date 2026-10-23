package com.minermarmot.client.features.mining;

import com.minermarmot.client.integration.VersionCheck;
import com.minermarmot.client.features.alerts.AlertSounds;

import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.minermarmotClient;
import com.minermarmot.client.config.ConfigManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.Vec2;
import org.lwjgl.glfw.GLFW;

public final class MiningManager {
    private static final MiningManager INSTANCE = new MiningManager();

    private final ObstacleMinerManager obstacles = new ObstacleMinerManager();
    private final WorldBorderManager border = new WorldBorderManager();
    private final DolphinMiner dolphin = new DolphinMiner(border);
    private KeyMapping toggleKey;
    private KeyMapping dolphinKey;
    private boolean running;
    private boolean dolphinMode;
    private boolean stopMovement;
    private boolean sprinting;
    private Float lockedYaw;
    private Double lockedFlightHeight;
    private int boostElapsedTicks = Integer.MAX_VALUE;
    private BlockPos target;
    private ClientLevel level;
    private LocalPlayer player;

    private MiningManager() {}

    public static void initialize() {
        INSTANCE.toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minermarmot.toggleMining",
                GLFW.GLFW_KEY_F6, minermarmotClient.KEY_CATEGORY));
        INSTANCE.dolphinKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minermarmot.toggleDolphin",
                GLFW.GLFW_KEY_F9, minermarmotClient.KEY_CATEGORY));
        ClientTickEvents.START_CLIENT_TICK.register(INSTANCE::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::endTick);
    }

    public static boolean isRunning() {
        return INSTANCE.running;
    }

    public static boolean handleToggleKey(InputConstants.Key key) {
        Minecraft client = Minecraft.getInstance();
        if (INSTANCE.toggleKey != null && INSTANCE.toggleKey.matches(key) && canAct(client) && !(client.gui.screen() instanceof KeyBindsScreen) && !(client.gui.screen() instanceof ChatScreen)) {
            toggle();
            return true;
        }
        if (INSTANCE.dolphinKey != null && INSTANCE.dolphinKey.matches(key) && canAct(client)
                && !(client.gui.screen() instanceof KeyBindsScreen) && !(client.gui.screen() instanceof ChatScreen)) {
            toggleDolphin();
            return true;
        }
        return false;
    }

    public static boolean keepsWorldRunning() {
        Minecraft client = Minecraft.getInstance();
        return INSTANCE.running && canAct(client) && client.gui.overlay() == null;
    }

    public static void finishMovement() {
        Minecraft client = Minecraft.getInstance();
        if (canAct(client) && !client.isPaused()) {
            if (!INSTANCE.running) {
                if (TntManager.isActive()) INSTANCE.dolphin.cancelCameraRestore();
                else INSTANCE.dolphin.finishCameraRestore(client);
                return;
            }
            if (isDolphin() && INSTANCE.dolphin.active()) {
                INSTANCE.dolphin.finishMovement(client);
                return;
            }
            INSTANCE.dolphin.cancelCameraRestore();
            if (enabled(MiningFeature.AUTO_CENTER_BLOCK)) {
                INSTANCE.centerPlayer(client);
            }
            if (!isDolphin() && enabled(MiningFeature.AUTO_FLIGHT_HEIGHT)) {
                INSTANCE.maintainFlightHeight(client);
            }
        }
    }

    public static void toggle() {
        toggleMode(false);
    }

    public static void toggleDolphin() {
        toggleMode(true);
    }

    private static void toggleMode(boolean dolphinMode) {
        Minecraft client = Minecraft.getInstance();
        if (!VersionCheck.isCompatible()) {
            INSTANCE.stop(client);
            client.setScreenAndShow(VersionCheck.configScreen(client.gui.screen()));
            return;
        }
        if (dolphinMode && !enabled(MiningFeature.DOLPHIN)) return;
        if (INSTANCE.running && INSTANCE.dolphinMode == dolphinMode) {
            INSTANCE.stop(client);
        } else if (canAct(client) && ConfigManager.presets().get(ConfigManager.current().preset).features.containsValue(true)) {
            INSTANCE.stop(client);
            TntManager.worldChanged();
            INSTANCE.running = true;
            INSTANCE.dolphinMode = dolphinMode;
            INSTANCE.level = client.level;
            INSTANCE.player = client.player;
            if (controlsBreaking()) {
                client.gameMode.stopDestroyBlock();
            }
            if (!isDolphin() && enabled(MiningFeature.AUTO_FLIGHT_HEIGHT)) {
                INSTANCE.captureFlightHeight(client);
            }
        }
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.translatable(INSTANCE.running
                    ? "minermarmot.mining.started" : "minermarmot.mining.stopped"));
        }
    }

    public static void stop() {
        INSTANCE.stop(Minecraft.getInstance());
    }

    public static boolean enabled(MiningFeature feature) {
        if (!VersionCheck.isCompatible() || ConfigManager.current() == null) {
            return false;
        }
        var settings = ConfigManager.current();
        return ConfigManager.preset(settings.preset).allows(feature)
                && (feature == MiningFeature.DOLPHIN || (feature == MiningFeature.WORLD_BORDER ? settings.mining.worldBorderDistance != 0
                : settings.mining.enabled.contains(feature)));
    }

    public static boolean controlsBreaking() {
        Minecraft client = Minecraft.getInstance();
        return INSTANCE.running && canAct(client) && (enabled(MiningFeature.AUTO_MINE)
                || isDolphin()
                || enabled(MiningFeature.OBSTACLE_MINER)
                || INSTANCE.border.isTurning());
    }

    public static boolean controlsMovement() {
        return INSTANCE.running && canAct(Minecraft.getInstance())
                && (isDolphin() || enabled(MiningFeature.AUTO_SPRINT) || INSTANCE.border.isTurning() || INSTANCE.stopMovement);
    }

    public static boolean shouldMove() {
        return !INSTANCE.stopMovement && (!isDolphin() || INSTANCE.dolphin.moves());
    }

    public static Vec2 movementInput() {
        if (!shouldMove()) return Vec2.ZERO;
        return isDolphin() ? INSTANCE.dolphin.movementInput(Minecraft.getInstance().player.getYRot()) : new Vec2(0, 1);
    }

    public static boolean smoothsCamera() { return INSTANCE.dolphin.smoothsCamera() && canAct(Minecraft.getInstance()); }

    public static boolean isDolphin() { return INSTANCE.running && INSTANCE.dolphinMode; }

    public static boolean locksCamera() {
        return INSTANCE.running && canAct(Minecraft.getInstance())
                && (isDolphin() || enabled(MiningFeature.AUTO_CAMERA_LOCK) || INSTANCE.border.isTurning());
    }

    private static boolean canAct(Minecraft client) {
        return VersionCheck.isCompatible() && client.player != null && client.level != null && client.gameMode != null && client.player.isAlive() && !client.player.isSpectator()  && !client.player.isPassenger() && !client.player.isSleeping();
    }

    private void startTick(Minecraft client) {
        while (toggleKey.consumeClick()) {
            if (!(client.gui.screen() instanceof ChatScreen) && !(client.gui.screen() instanceof KeyBindsScreen)) toggle();
        }
        while (dolphinKey.consumeClick()) {
            if (!(client.gui.screen() instanceof ChatScreen) && !(client.gui.screen() instanceof KeyBindsScreen)) toggleDolphin();
        }
        if (level != client.level || player != client.player || !canAct(client)) {
            stop(client);
            level = client.level;
            player = client.player;
        }
        if (!running || client.isPaused()) {
            return;
        }
        stopMovement = false;
        if (dolphinMode && !enabled(MiningFeature.DOLPHIN)) {
            stop(client);
            return;
        }
        if (isDolphin()) {
            lockedFlightHeight = null;
            if (!dolphin.tick(client)) {
                stop(client);
                client.player.sendOverlayMessage(Component.translatable("minermarmot.mining.dolphinBlocked"));
                AlertSounds.playSound("minecraft:block.note_block.bell");
                return;
            }
            if (dolphin.takeForwardBoost()) boostElapsedTicks = Integer.MAX_VALUE;
            client.player.setSprinting(dolphin.moves());
            sprinting = dolphin.moves();
            return;
        }
        if (dolphin.active()) {
            dolphin.reset(client, true);
            border.reset();
            lockedYaw = null;
            stopBreaking(client);
        }
        if (enabled(MiningFeature.AUTO_CAMERA_LOCK)) {
            if (lockedYaw == null) {
                lockedYaw = WorldBorderManager.cardinalYaw(client.player.getYRot());
            }
            client.player.setYRot(lockedYaw);
            client.player.setXRot(0);
        } else {
            lockedYaw = null;
        }
        if (enabled(MiningFeature.WORLD_BORDER)) {
            var bounds = client.level.getWorldBorder();
            var action = border.update(client.player.getX(), client.player.getZ(), client.player.getYRot(),
                    bounds.getMinX(), bounds.getMaxX(), bounds.getMinZ(), bounds.getMaxZ(),
                    ConfigManager.current().mining.worldBorderDistance);
            if (action == WorldBorderManager.Action.BLOCKED) {
                stop(client);
                client.player.sendOverlayMessage(Component.translatable("minermarmot.mining.borderBlocked"));
                return;
            }
            if (action == WorldBorderManager.Action.FIRST_TURN || action == WorldBorderManager.Action.SECOND_TURN) {
                stopHorizontal(client.player);
                stopMovement = true;
                stopBreaking(client);
                if (action == WorldBorderManager.Action.SECOND_TURN) {
                    moveSafely(client, border.endX(), client.player.getY(), border.endZ());
                }
                lockedYaw = border.yaw();
                client.player.setYRot(border.yaw());
                client.player.setXRot(0);
            } else if (border.isTurning()) {
                client.player.setYRot(border.yaw());
                client.player.setXRot(0);
            }
        } else {
            border.reset();
        }
        if (enabled(MiningFeature.AUTO_CENTER_BLOCK)) {
            centerPlayer(client);
        }
        if ((enabled(MiningFeature.AUTO_SPRINT) || border.isTurning()) && !stopMovement) {
            client.player.setSprinting(true);
            sprinting = true;
        } else if (sprinting) {
            client.player.setSprinting(false);
            sprinting = false;
        }
    }

    private BlockHitResult findTarget(Minecraft client, BlockHitResult crosshair) {
        if (isDolphin()) return dolphin.findTarget(client, target);
        BlockHitResult hit = null;
        if (enabled(MiningFeature.OBSTACLE_MINER) || border.isTurning()) {
            hit = obstacles.findTarget(client, Vec3.directionFromRotation(0, client.player.getYRot()), target);
        }
        if (hit == null && enabled(MiningFeature.AUTO_MINE) && crosshair != null
                && MiningTargets.canMine(client, crosshair.getBlockPos())) hit = crosshair;
        return hit;
    }

    private void endTick(Minecraft client) {
        if (!running || !canAct(client) || client.isPaused()) {
            return;
        }
        BlockHitResult crosshair = MiningTargets.crosshair(client);
        if (boostElapsedTicks < Integer.MAX_VALUE) boostElapsedTicks++;
        // The server must receive the block-dependent boost before its mining packet removes the target.
        if (isDolphin() && dolphin.boosts() && crosshair != null
                && client.level.getFluidState(crosshair.getBlockPos()).isEmpty()
                && boostElapsedTicks >= ConfigManager.current().mining.autoBoostIntervalTicks()
                && !client.player.isUsingItem() && !stopMovement) {
            boostElapsedTicks = 0;
            useItem(client);
        }
        mineBlock(client, crosshair);
        if (!isDolphin() && enabled(MiningFeature.AUTO_BOOST)
                && target == null && MiningTargets.crosshair(client) == null
                && boostElapsedTicks >= ConfigManager.current().mining.autoBoostIntervalTicks()
                && !client.player.isUsingItem() && !stopMovement) {
            boostElapsedTicks = 0;
            useItem(client);
        }
    }

    private void mineBlock(Minecraft client, BlockHitResult crosshair) {
        if (isDolphin() && (client.player.isUsingItem() || dolphin.waitsForAim()) && !stopMovement) return;
        BlockHitResult hit = findTarget(client, crosshair);
        if (hit == null || client.player.isUsingItem() || stopMovement) {
            stopBreaking(client);
            return;
        }
        // A single controller preserves vanilla break progress across all mining actions.
        target = hit.getBlockPos().immutable();
        if (client.gameMode.continueDestroyBlock(target, hit.getDirection())) {
            client.level.addBreakingBlockEffect(target, hit.getDirection());
            client.player.swing(InteractionHand.MAIN_HAND);
            if (isDolphin() && client.level.getBlockState(target).isAir()) {
                dolphin.blockBroken(client, target);
                target = null;
            }
        }
    }

    private void useItem(Minecraft client) {
        for (InteractionHand hand : InteractionHand.values()) {
            var stack = client.player.getItemInHand(hand);
            if (stack.isEmpty() || !stack.isItemEnabled(client.level.enabledFeatures())) {
                continue;
            }
            InteractionResult result = client.gameMode.useItem(client.player, hand);
            if (result instanceof InteractionResult.Success success) {
                if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                    client.player.swing(hand);
                }
                return;
            }
            if (result instanceof InteractionResult.Fail) {
                return;
            }
        }
    }

    private void centerPlayer(Minecraft client) {
        var player = client.player;
        Direction direction = Direction.fromYRot(player.getYRot());
        boolean alongZ = direction.getAxis() == Direction.Axis.Z;
        double x = alongZ ? Math.floor(player.getX()) + 0.5 : player.getX();
        double z = alongZ ? player.getZ() : Math.floor(player.getZ()) + 0.5;
        if (moveSafely(client, x, player.getY(), z)) {
            Vec3 velocity = player.getDeltaMovement();
            player.setDeltaMovement(alongZ ? new Vec3(0, velocity.y, velocity.z) : new Vec3(velocity.x, velocity.y, 0));
        }
    }

    private void captureFlightHeight(Minecraft client) {
        var player = client.player;
        if (!player.getAbilities().mayfly) {
            return;
        }
        lockedFlightHeight = Math.floor(player.getY()) + 0.1;
        maintainFlightHeight(client);
    }

    private void maintainFlightHeight(Minecraft client) {
        var player = client.player;
        if (!player.getAbilities().mayfly) {
            return;
        }
        if (lockedFlightHeight == null) {
            lockedFlightHeight = Math.floor(player.getY()) + 0.1;
        }
        if (moveSafely(client, player.getX(), lockedFlightHeight, player.getZ())) {
            if (!player.getAbilities().flying) {
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            }
            Vec3 velocity = player.getDeltaMovement();
            player.setDeltaMovement(velocity.x, 0, velocity.z);
        }
    }

    private boolean moveSafely(Minecraft client, double x, double y, double z) {
        var player = client.player;
        var bounds = player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ());
        if (!client.level.getWorldBorder().isWithinBounds(bounds) || !client.level.noCollision(player, bounds)) {
            return false;
        }
        player.setPos(x, y, z);
        return true;
    }

    private static void stopHorizontal(LocalPlayer player) {
        player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
    }

    private void stopBreaking(Minecraft client) {
        if (target != null && client.gameMode != null && client.player != null && client.level != null) {
            client.gameMode.stopDestroyBlock();
        }
        target = null;
    }

    private void stop(Minecraft client) {
        stopBreaking(client);
        dolphin.reset(client, player == client.player && level == client.level);
        if (sprinting && client.player != null) {
            client.player.setSprinting(false);
            stopHorizontal(client.player);
        }
        running = false;
        dolphinMode = false;
        sprinting = false;
        stopMovement = false;
        lockedYaw = null;
        lockedFlightHeight = null;
        boostElapsedTicks = Integer.MAX_VALUE;
        border.reset();
    }
}

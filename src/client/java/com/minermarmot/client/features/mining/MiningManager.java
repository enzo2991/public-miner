package com.minermarmot.client.features.mining;

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
import org.lwjgl.glfw.GLFW;

public final class MiningManager {
    private static final MiningManager INSTANCE = new MiningManager();
    private static final int MAX_BREAKS_PER_TICK = 8;

    private final ObstacleMinerManager obstacles = new ObstacleMinerManager();
    private final SurfaceTargetMiner surfaces = new SurfaceTargetMiner();
    private final WorldBorderManager border = new WorldBorderManager();
    private KeyMapping toggleKey;
    private boolean running;
    private boolean stopMovement;
    private boolean sprinting;
    private Float lockedYaw;
    private Double lockedFlightHeight;
    private int boostCooldown;
    private BlockPos target;
    private ClientLevel level;
    private LocalPlayer player;

    private MiningManager() {}

    public static void initialize() {
        INSTANCE.toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minermarmot.toggleMining",
                GLFW.GLFW_KEY_F6, minermarmotClient.KEY_CATEGORY));
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
        return false;
    }

    public static boolean keepsWorldRunning() {
        Minecraft client = Minecraft.getInstance();
        return INSTANCE.running && canAct(client) && client.gui.overlay() == null;
    }

    public static void finishMovement() {
        Minecraft client = Minecraft.getInstance();
        if (INSTANCE.running && canAct(client) && !client.isPaused()) {
            if (enabled(MiningFeature.AUTO_CENTER_BLOCK)) {
                INSTANCE.centerPlayer(client);
            }
            if (!INSTANCE.applyBoostMovement(client) && enabled(MiningFeature.AUTO_FLIGHT_HEIGHT)) {
                INSTANCE.maintainFlightHeight(client);
            }
        }
    }

    public static void toggle() {
        Minecraft client = Minecraft.getInstance();
        if (INSTANCE.running) {
            INSTANCE.stop(client);
        } else if (canAct(client) && ConfigManager.presets().get(ConfigManager.current().preset).features.containsValue(true)) {
            TntManager.worldChanged();
            INSTANCE.running = true;
            INSTANCE.level = client.level;
            INSTANCE.player = client.player;
            if (controlsBreaking()) {
                client.gameMode.stopDestroyBlock();
            }
            if (!INSTANCE.applyBoostMovement(client) && enabled(MiningFeature.AUTO_FLIGHT_HEIGHT)) {
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
        if (ConfigManager.current() == null) {
            return false;
        }
        var settings = ConfigManager.current();
        return ConfigManager.preset(settings.preset).allows(feature)
                && (feature == MiningFeature.WORLD_BORDER ? settings.mining.worldBorderDistance != 0
                : settings.mining.enabled.contains(feature));
    }

    public static boolean controlsBreaking() {
        Minecraft client = Minecraft.getInstance();
        return INSTANCE.running && canAct(client) && (enabled(MiningFeature.AUTO_MINE)
                || enabled(MiningFeature.OBSTACLE_MINER) || enabled(MiningFeature.SURFACE_TARGET_MINER)
                || INSTANCE.border.isTurning());
    }

    public static boolean controlsMovement() {
        return INSTANCE.running && canAct(Minecraft.getInstance())
                && (enabled(MiningFeature.AUTO_SPRINT) || INSTANCE.border.isTurning() || INSTANCE.stopMovement);
    }

    public static boolean shouldMove() {
        return !INSTANCE.stopMovement;
    }

    public static boolean locksCamera() {
        return INSTANCE.running && canAct(Minecraft.getInstance())
                && (enabled(MiningFeature.AUTO_CAMERA_LOCK) || INSTANCE.border.isTurning());
    }

    private static boolean canAct(Minecraft client) {
        return client.player != null && client.level != null && client.gameMode != null && client.player.isAlive() && !client.player.isSpectator()  && !client.player.isPassenger() && !client.player.isSleeping();
    }

    private void startTick(Minecraft client) {
        if (client.gameMode != null && enabled(MiningFeature.MINING_WITHOUT_DELAY)) {
            client.gameMode.destroyDelay = 0;
        }
        while (toggleKey.consumeClick()) {
            if (!(client.gui.screen() instanceof ChatScreen)) toggle();
        }
        if (level != client.level || player != client.player || !canAct(client)) {
            stop(client);
            level = client.level;
            player = client.player;
        }
        if (!running || client.isPaused()) {
            return;
        }
        applyBoostMovement(client);
        stopMovement = false;
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

    private void endTick(Minecraft client) {
        if (!running || !canAct(client) || client.isPaused()) {
            return;
        }
        BlockHitResult crosshair = MiningTargets.crosshair(client);
        int attempts = enabled(MiningFeature.AUTO_MINE) && enabled(MiningFeature.MINING_WITHOUT_DELAY)
                ? MAX_BREAKS_PER_TICK : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            BlockHitResult hit = null;
            if (enabled(MiningFeature.OBSTACLE_MINER) || border.isTurning()) {
                hit = obstacles.findTarget(client, Vec3.directionFromRotation(0, client.player.getYRot()), target);
            }
            if (hit == null && enabled(MiningFeature.SURFACE_TARGET_MINER) && !border.isTurning()) {
                hit = surfaces.findTarget(client, target);
            }
            if (hit == null && enabled(MiningFeature.AUTO_MINE) && crosshair != null
                    && MiningTargets.canMine(client, crosshair.getBlockPos())) {
                hit = crosshair;
            }
            if (hit == null || client.player.isUsingItem() || stopMovement) {
                stopBreaking(client);
                break;
            }
            // A single controller preserves vanilla break progress across all mining actions.
            target = hit.getBlockPos().immutable();
            if (enabled(MiningFeature.MINING_WITHOUT_DELAY)) client.gameMode.destroyDelay = 0;
            if (client.gameMode.continueDestroyBlock(target, hit.getDirection())) {
                client.level.addBreakingBlockEffect(target, hit.getDirection());
                client.player.swing(InteractionHand.MAIN_HAND);
            }
            // Retry only after removal; a resistant block must progress at most once per tick.
            if (!client.level.getBlockState(target).isAir() || attempt + 1 == attempts) {
                break;
            }
            crosshair = MiningTargets.crosshair(client);
        }
        if (boostCooldown > 0) {
            boostCooldown--;
        }
        if (enabled(MiningFeature.AUTO_BOOST) && crosshair == null && target == null
                && (boostCooldown == 0 || enabled(MiningFeature.PLACEMENT_WITHOUT_DELAY))
                && !client.player.isUsingItem() && !stopMovement) {
            boostCooldown = 4;
            useItem(client);
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

    private boolean applyBoostMovement(Minecraft client) {
        if (enabled(MiningFeature.AUTO_BOOST)) {
            var player = client.player;
            Vec3 velocity = player.getDeltaMovement();
            if (ConfigManager.preset(ConfigManager.current().preset).autoBoostBlockUpward && velocity.y > 0) {
                player.setDeltaMovement(velocity.x, 0, velocity.z);
            }
        }
        return false;
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
        if (sprinting && client.player != null) {
            client.player.setSprinting(false);
            stopHorizontal(client.player);
        }
        running = false;
        sprinting = false;
        stopMovement = false;
        lockedYaw = null;
        lockedFlightHeight = null;
        boostCooldown = 0;
        border.reset();
    }
}

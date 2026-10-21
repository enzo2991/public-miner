package com.minermarmot.client.features.alerts;

import com.minermarmot.client.integration.VersionCheck;

import com.minermarmot.client.config.ConfigManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PlayerAlertManager {
    private static final PlayerAlertManager INSTANCE = new PlayerAlertManager();
    private final Map<BlockPos, Long> recentBreaks = new HashMap<>();
    private final Map<Integer, Long> processedDrops = new HashMap<>();
    private final List<Repetition> repetitions = new ArrayList<>();
    private ClientLevel level;
    private LocalPlayer player;
    private String preset;

    private PlayerAlertManager() {}

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> worldChanged());
        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
            Minecraft client = Minecraft.getInstance();
            INSTANCE.updateContext(client);
            if (inventoryEnabled() && !player.isDeadOrDying()) {
                INSTANCE.recentBreaks.put(pos.immutable(), Util.getMillis());
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                privateMessage(message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, chatType, timestamp) -> privateMessage(message));
    }

    public static void worldChanged() {
        INSTANCE.recentBreaks.clear();
        INSTANCE.processedDrops.clear();
        INSTANCE.repetitions.clear();
        INSTANCE.level = null;
        INSTANCE.player = null;
    }

    private void updateContext(Minecraft client) {
        String selected = ConfigManager.current().preset;
        if (level != client.level || player != client.player || !Objects.equals(preset, selected)) {
            recentBreaks.clear();
            processedDrops.clear();
            repetitions.clear();
            level = client.level;
            player = client.player;
            preset = selected;
        }
    }

    private static boolean inventoryEnabled() {
        var config = ConfigManager.current();
        return VersionCheck.isCompatible() && ConfigManager.preset(config.preset).playerAlerts.inventoryFull && config.playerAlerts.inventoryFull;
    }

    private void tick(Minecraft client) {
        updateContext(client);
        if (!VersionCheck.isCompatible()) { worldChanged(); return; }
        if (player == null || level == null || player.isDeadOrDying()) {
            repetitions.clear();
        } else {
            repeatSounds();
        }
        if (level == null || player == null || player.isDeadOrDying() || !inventoryEnabled()) {
            recentBreaks.clear();
            processedDrops.clear();
            return;
        }
        long now = Util.getMillis();
        recentBreaks.values().removeIf(time -> now - time > 2000);
        processedDrops.values().removeIf(time -> now - time > 5000);
        if (recentBreaks.isEmpty()) {
            return;
        }
        for (var entity : level.getEntities(player, player.getBoundingBox().inflate(6), entity -> entity instanceof ItemEntity)) {
            ItemEntity drop = (ItemEntity) entity;
            // shortcut: nearby drops can be unrelated to the break; use a server signal if exact attribution becomes available.
            if (processedDrops.containsKey(drop.getId()) || recentBreaks.keySet().stream()
                    .noneMatch(pos -> pos.distSqr(drop.blockPosition()) <= 9)) {
                continue;
            }
            processedDrops.put(drop.getId(), now);
            if (!canStoreCompletely(player.getInventory(), drop.getItem())) {
                var settings = ConfigManager.current().playerAlerts;
                notifyPlayer(client, "inventoryFull", settings.inventoryFullSound, settings.inventoryFullRepeat, settings.inventoryFullVolume);
                recentBreaks.clear();
                return;
            }
        }
    }

    static boolean canStoreCompletely(Container inventory, ItemStack incoming) {
        int remaining = incoming.getCount();
        for (int slot = 0; slot < Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize()); slot++) {
            ItemStack stored = inventory.getItem(slot);
            if (stored.isEmpty()) {
                remaining -= inventory.getMaxStackSize(incoming);
            } else if (ItemStack.isSameItemSameComponents(stored, incoming)) {
                remaining -= Math.max(0, inventory.getMaxStackSize(stored) - stored.getCount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return incoming.isEmpty();
    }

    private static void privateMessage(Component message) {
        Minecraft client = Minecraft.getInstance();
        var config = ConfigManager.current();
        if (VersionCheck.isCompatible() && client.player != null && client.level != null && !client.player.isDeadOrDying() && config.playerAlerts.privateMessage
                && ConfigManager.preset(config.preset).playerAlerts.matchesPrivateMessage(ChatFormatting.stripFormatting(message.getString()))) {
            INSTANCE.updateContext(client);
            INSTANCE.notifyPlayer(client, "privateMessage", config.playerAlerts.privateMessageSound,
                    config.playerAlerts.privateMessageRepeat, config.playerAlerts.privateMessageVolume);
        }
    }

    private void notifyPlayer(Minecraft client, String key, String sound, int repeat, float volume) {
        client.player.sendOverlayMessage(Component.translatable("minermarmot.alerts." + key + "Notification"));
        AlertSounds.playSound(sound, volume);
        if (!sound.isEmpty() && repeat > 1) {
            repetitions.add(new Repetition(key, sound, repeat - 1, Util.getMillis() + 250));
        }
    }

    private void repeatSounds() {
        var settings = ConfigManager.current().playerAlerts;
        var available = ConfigManager.preset(preset).playerAlerts;
        long now = Util.getMillis();
        for (var iterator = repetitions.listIterator(); iterator.hasNext();) {
            Repetition pending = iterator.next();
            boolean inventory = pending.key.equals("inventoryFull");
            boolean enabled = inventory ? inventoryEnabled() : available.privateMessage && settings.privateMessage;
            String sound = inventory ? settings.inventoryFullSound : settings.privateMessageSound;
            if (!enabled || !pending.sound.equals(sound)) {
                iterator.remove();
                continue;
            }
            if (now >= pending.nextPlay) {
                AlertSounds.playSound(sound, inventory ? settings.inventoryFullVolume : settings.privateMessageVolume);
                if (pending.remaining == 1) {
                    iterator.remove();
                } else {
                    iterator.set(new Repetition(pending.key, sound, pending.remaining - 1, now + 250));
                }
            }
        }
    }

    private record Repetition(String key, String sound, int remaining, long nextPlay) {}
}

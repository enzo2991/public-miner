package com.minermarmot.client.mixin;

import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.features.alerts.PlayerAlertManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    // shortcut: an alive-player respawn signals a world transfer; silent server teleports need a server-specific signal.
    @Inject(method = "handleRespawn", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER))
    private void minermarmot$clearWorldDiscoveries(ClientboundRespawnPacket packet, CallbackInfo ci) {
        TntManager.worldChanged();
        if (!Minecraft.getInstance().player.isDeadOrDying()) {
            PlayerAlertManager.worldChanged();
        }
    }
}

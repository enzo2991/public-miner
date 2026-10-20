package com.minermarmot.client.mixin;

import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.features.mining.MiningManager;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    // Apply positioning after vanilla physics, before the movement packet is sent.
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void minermarmot$finishMovement(CallbackInfo ci) {
        MiningManager.finishMovement();
        TntManager.finishMovement();
    }
}

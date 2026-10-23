package com.minermarmot.client.mixin;

import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.features.mining.MiningManager;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    // LocalPlayer bypasses vanilla rotation interpolation, with no camera event to restore it.
    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void minermarmot$smoothCameraYaw(float partialTick, CallbackInfoReturnable<Float> callback) {
        if (TntManager.smoothsCamera() || MiningManager.smoothsCamera())
            callback.setReturnValue(((LocalPlayer) (Object) this).getYRot(partialTick));
    }

    // LocalPlayer also bypasses pitch interpolation during automated vertical turns.
    @Inject(method = "getViewXRot", at = @At("HEAD"), cancellable = true)
    private void minermarmot$smoothCameraPitch(float partialTick, CallbackInfoReturnable<Float> callback) {
        if (MiningManager.smoothsCamera()) callback.setReturnValue(((LocalPlayer) (Object) this).getXRot(partialTick));
    }

    // Apply positioning after vanilla physics, before the movement packet is sent.
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void minermarmot$finishMovement(CallbackInfo ci) {
        MiningManager.finishMovement();
        TntManager.finishMovement();
    }
}

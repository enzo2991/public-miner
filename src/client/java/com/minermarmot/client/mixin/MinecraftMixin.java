package com.minermarmot.client.mixin;

import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.features.mining.MiningManager;
import com.minermarmot.client.features.mining.MiningFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow private int rightClickDelay;

    // Fabric exposes no callback for the delay between held right-click actions.
    @Inject(method = "tick", at = @At("HEAD"))
    private void minermarmot$removePlacementDelay(CallbackInfo ci) {
        if (MiningManager.enabled(MiningFeature.PLACEMENT_WITHOUT_DELAY)) {
            rightClickDelay = 0;
        }
    }

    // Vanilla would abort the shared target whenever the attack key is released.
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void minermarmot$keepMining(boolean down, CallbackInfo ci) {
        if (MiningManager.controlsBreaking()) {
            ci.cancel();
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void minermarmot$avoidDuplicateBreak(CallbackInfoReturnable<Boolean> ci) {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (MiningManager.controlsBreaking() && hit != null && hit.getType() == HitResult.Type.BLOCK) {
            ci.setReturnValue(false);
        }
    }

    @Inject(method = "handleGlobalKeyPress", at = @At("RETURN"), cancellable = true)
    private void minermarmot$toggleMiningInScreens(InputConstants.Key key, boolean controlDown,
                                                 CallbackInfoReturnable<Boolean> ci) {
        if (!ci.getReturnValue() && (MiningManager.handleToggleKey(key)
                || TntManager.handleToggleKey(key))) {
            ci.setReturnValue(true);
        }
    }
}

package com.minermarmot.client.mixin;

import com.minermarmot.client.features.mining.MiningFeature;
import com.minermarmot.client.features.mining.MiningManager;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Shadow private int destroyDelay;

    // Fabric exposes no callback for the vanilla delay between block breaks.
    @Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"))
    private void minermarmot$removeBreakDelay(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> ci) {
        if (MiningManager.enabled(MiningFeature.MINING_WITHOUT_DELAY)) {
            destroyDelay = 0;
        }
    }
}

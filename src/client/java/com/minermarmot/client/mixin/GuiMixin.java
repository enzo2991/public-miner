package com.minermarmot.client.mixin;

import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.features.mining.MiningManager;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Gui.class)
abstract class GuiMixin {
    // Single-player screens otherwise pause both the player and the integrated server.
    @Inject(method = "isPausing", at = @At("HEAD"), cancellable = true)
    private void minermarmot$continueBehindScreens(CallbackInfoReturnable<Boolean> ci) {
        if (MiningManager.keepsWorldRunning() || TntManager.controlsMovement()) {
            ci.setReturnValue(false);
        }
    }
}

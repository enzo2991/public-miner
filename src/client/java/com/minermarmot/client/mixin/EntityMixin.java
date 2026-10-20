package com.minermarmot.client.mixin;

import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.features.mining.MiningManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class EntityMixin {
    // Tick-time snapping alone still allows mouse movement between ticks.
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void minermarmot$lockPlayerCamera(double yaw, double pitch, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player && (MiningManager.locksCamera()
                || TntManager.isActive())) {
            ci.cancel();
        }
    }
}

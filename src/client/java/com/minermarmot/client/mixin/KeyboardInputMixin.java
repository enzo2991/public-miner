package com.minermarmot.client.mixin;

import com.minermarmot.client.features.mining.MiningManager;
import com.minermarmot.client.features.tnt.TntManager;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin extends ClientInput {
    // No input event supplies movement while a screen owns the keyboard.
    @Inject(method = "tick", at = @At("TAIL"))
    private void minermarmot$moveForward(CallbackInfo ci) {
        if (TntManager.controlsMovement()) {
            keyPresses = new Input(false, TntManager.retreats(), false, false, false, TntManager.sneaks(), false);
            moveVector = TntManager.movementInput();
        } else if (MiningManager.controlsMovement()) {
            boolean move = MiningManager.shouldMove();
            keyPresses = new Input(move, false, false, false, !MiningManager.isDolphin() && keyPresses.jump(),
                    !MiningManager.isDolphin() && keyPresses.shift(), move);
            moveVector = MiningManager.movementInput();
        }
    }
}

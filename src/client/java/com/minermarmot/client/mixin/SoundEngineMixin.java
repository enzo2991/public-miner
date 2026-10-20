package com.minermarmot.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.minermarmot.client.features.alerts.AlertSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.ChannelAccess;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "play", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V", ordinal = 0))
    private void minermarmot$allowAmplification(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir,
                                               @Local ChannelAccess.ChannelHandle handle) {
        if (instance instanceof AlertSoundInstance alert && alert.volumeMultiplier() > 1) {
            handle.execute(channel -> {
                // OpenAL otherwise clamps each source to 1, even after Minecraft's clamp is bypassed.
                if (AL10.alIsExtensionPresent("AL_SOFT_gain_clamp_ex")) {
                    AL10.alSourcef(((AudioChannelAccessor) channel).minermarmot$source(), AL10.AL_MAX_GAIN, alert.volumeMultiplier());
                }
            });
        }
    }

    // Vanilla clamps instance volume before category gain; apply only our alerts' multiplier afterward.
    @ModifyExpressionValue(method = {"play", "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
    private float minermarmot$alertVolume(float original, SoundInstance instance) {
        return instance instanceof AlertSoundInstance alert ? original * alert.volumeMultiplier() : original;
    }
}

package com.minermarmot.client.features.alerts;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

public final class AlertSoundInstance extends SimpleSoundInstance {
    private final float volumeMultiplier;

    public AlertSoundInstance(Identifier sound, float volumeMultiplier) {
        super(sound, SoundSource.UI, 1.0f, 1.0f, SoundInstance.createUnseededRandom(), false, 0,
                SoundInstance.Attenuation.NONE, 0, 0, 0, true);
        this.volumeMultiplier = volumeMultiplier;
    }

    public float volumeMultiplier() {
        return volumeMultiplier;
    }
}

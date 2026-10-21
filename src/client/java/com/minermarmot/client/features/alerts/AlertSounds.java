package com.minermarmot.client.features.alerts;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class AlertSounds {
    private AlertSounds() {}
    public static final List<Float> VOLUMES = List.of(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f);
    public static final List<String> SOUNDS = List.of(
            "minecraft:block.note_block.pling",
            "minecraft:block.note_block.bell",
            "minecraft:block.note_block.bit",
            "minecraft:block.note_block.harp",
            "minecraft:block.note_block.guitar",
            "minecraft:entity.generic.explode",
            "minecraft:ui.toast.challenge_complete");
    public static boolean validVolume(float value) { return Float.isFinite(value) && value >= 0.5f && value <= 5.0f; }
    public static void playSound(String sound) { playSound(sound, 1.0f); }
    public static void playSound(String sound, float volume) {
        if (!validVolume(volume) || !SOUNDS.contains(sound)) return;
        var client = Minecraft.getInstance();
        var id = Identifier.parse(sound);
        if (client.getSoundManager().getSoundEvent(id) != null) client.getSoundManager().play(new AlertSoundInstance(id, volume));
    }
}

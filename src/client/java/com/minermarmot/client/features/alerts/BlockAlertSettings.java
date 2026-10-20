package com.minermarmot.client.features.alerts;

import net.minecraft.resources.Identifier;

import java.util.List;

public final class BlockAlertSettings {
    public static final List<Float> VOLUMES = List.of(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f);
    public static final List<String> SOUNDS = List.of(
            "minecraft:block.note_block.pling",
            "minecraft:block.note_block.bell",
            "minecraft:block.note_block.bit",
            "minecraft:block.note_block.harp",
            "minecraft:block.note_block.guitar",
            "minecraft:entity.generic.explode",
            "minecraft:ui.toast.challenge_complete");
    public boolean enabled = true;
    public boolean surfaceMine = true;
    public String color = "#55FFFF";
    public String sound = "minecraft:block.note_block.pling";
    public boolean glow;

    public BlockAlertSettings copy() {
        BlockAlertSettings copy = new BlockAlertSettings();
        copy.enabled = enabled;
        copy.surfaceMine = surfaceMine;
        copy.color = color;
        copy.sound = sound;
        copy.glow = glow;
        return copy;
    }

    public static boolean validColor(String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}");
    }

    public static boolean validVolume(float value) {
        return Float.isFinite(value) && value >= 0.5f && value <= 5.0f;
    }

    public int argb() {
        return 0xFF000000 | Integer.parseInt(color.substring(1), 16);
    }

    public void validate() {
        if (!validColor(color) || sound == null || !sound.isEmpty() && Identifier.tryParse(sound) == null) {
            throw new IllegalArgumentException("Invalid block alert color or sound");
        }
        // Preserve other preferences when an older configuration uses a sound outside the supported list.
        if (!sound.isEmpty() && !SOUNDS.contains(sound)) {
            sound = "";
        }
    }
}

package com.minermarmot.client.features.alerts;

import net.minecraft.resources.Identifier;

public final class PlayerAlertsConfig {
    public boolean inventoryFull = true;
    public String inventoryFullSound = "minecraft:block.note_block.bell";
    public float inventoryFullVolume = 1.0f;
    public int inventoryFullRepeat = 1;
    public boolean privateMessage = true;
    public String privateMessageSound = "minecraft:block.note_block.pling";
    public float privateMessageVolume = 1.0f;
    public int privateMessageRepeat = 1;

    public PlayerAlertsConfig copy() {
        PlayerAlertsConfig copy = new PlayerAlertsConfig();
        copy.inventoryFull = inventoryFull;
        copy.inventoryFullSound = inventoryFullSound;
        copy.inventoryFullVolume = inventoryFullVolume;
        copy.inventoryFullRepeat = inventoryFullRepeat;
        copy.privateMessage = privateMessage;
        copy.privateMessageSound = privateMessageSound;
        copy.privateMessageVolume = privateMessageVolume;
        copy.privateMessageRepeat = privateMessageRepeat;
        return copy;
    }

    public void validate() {
        if (!BlockAlertSettings.validVolume(inventoryFullVolume) || !BlockAlertSettings.validVolume(privateMessageVolume)) {
            throw new IllegalArgumentException("Player alert volumes must be between 0.5 and 5");
        }
        if (inventoryFullRepeat < 1 || inventoryFullRepeat > 5 || privateMessageRepeat < 1 || privateMessageRepeat > 5) {
            throw new IllegalArgumentException("Player alert repeat counts must be between 1 and 5");
        }
        inventoryFullSound = validateSound(inventoryFullSound);
        privateMessageSound = validateSound(privateMessageSound);
    }

    private static String validateSound(String sound) {
        if (sound == null || !sound.isEmpty() && Identifier.tryParse(sound) == null) {
            throw new IllegalArgumentException("Invalid player alert sound");
        }
        return BlockAlertSettings.SOUNDS.contains(sound) ? sound : "";
    }
}

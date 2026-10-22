package com.minermarmot.client;

import com.minermarmot.Main;
import com.minermarmot.client.input.AutomationInput;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.client.Minecraft;
import com.minermarmot.client.integration.VersionCheck;
import com.minermarmot.client.features.tnt.TntManager;
import com.minermarmot.client.features.display.DisplayRenderer;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.mining.MiningManager;
import com.minermarmot.client.features.alerts.PlayerAlertManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class minermarmotClient implements ClientModInitializer {
    public static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Main.id("main"));

    @Override
    public void onInitializeClient() {
        ConfigManager.initialize();
        VersionCheck.initialize();
        MiningManager.initialize();
        PlayerAlertManager.initialize();
        TntManager.initialize();
        DisplayRenderer.initialize();
        ClientTickEvents.START_CLIENT_TICK.register(AutomationInput::install);
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity == Minecraft.getInstance().player) {
                TntManager.worldChanged();
                PlayerAlertManager.worldChanged();
            }
        });
        KeyMapping openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minermarmot.openConfig",
                GLFW.GLFW_KEY_F8, KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.consumeClick()) {
                if (client.player != null && client.gui.screen() == null) {
                    client.setScreenAndShow(VersionCheck.configScreen(null));
                }
            }
        });
    }
}

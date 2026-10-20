package com.minermarmot.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

public final class ConfigKeyClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.getName().equals("minermarmot-discord")),
                    "The mod must start without a Discord verification worker or login");
            var mappings = Arrays.stream(client.options.keyMappings)
                    .filter(key -> key.getName().startsWith("key.minermarmot.")).toList();
            check(mappings.size() == 3, "All three MinerMarmot shortcuts must be registered exactly once");
            check(mappings.stream().map(KeyMapping::getCategory).distinct().count() == 1,
                    "All shortcuts must share one category");
            check(mappings.getFirst().getCategory().label().getString().equals("MinerMarmot"),
                    "The category must be named MinerMarmot");
            check(configKey().getDefaultKey().getValue() == GLFW.GLFW_KEY_F8, "Configuration defaults to F8");
            check(KeyMapping.get("key.minermarmot.toggleMining").getDefaultKey().getValue() == GLFW.GLFW_KEY_F6,
                    "Mining must retain its existing F6 default and binding ID");
            check(KeyMapping.get("key.minermarmot.toggleTnt").getDefaultKey().getValue() == GLFW.GLFW_KEY_F7,
                    "TNT must retain its existing F7 default and binding ID");
        });
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            context.waitFor(client -> client.gui.screen() instanceof ConfigScreen);
            context.takeScreenshot("config-key-open");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == null);

            context.setScreen(() -> new ChatScreen("", false));
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            context.waitTick();
            context.runOnClient(client -> check(client.gui.screen() instanceof ChatScreen, "F8 must leave chat open"));
            context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            context.waitTick();
            context.runOnClient(client -> check(client.gui.screen() instanceof InventoryScreen, "F8 must leave inventory open"));
            context.setScreen(() -> null);
            context.waitTick();
            context.runOnClient(client -> check(client.gui.screen() == null, "Ignored presses must not open configuration later"));

            context.setScreen(() -> new KeyBindsScreen(null, Minecraft.getInstance().options));
            context.runOnClient(client -> {
                for (var child : client.gui.screen().children()) {
                    if (child instanceof ContainerObjectSelectionList<?> list) list.setScrollAmount(Double.MAX_VALUE);
                }
            });
            context.takeScreenshot("config-key-bindings");
            context.runOnClient(client -> ((KeyBindsScreen) client.gui.screen()).selectedKey = configKey());
            context.getInput().pressKey(GLFW.GLFW_KEY_F9);
            context.waitTick();
            context.runOnClient(client -> {
                check(client.gui.screen() instanceof KeyBindsScreen, "Rebinding must keep the controls screen open");
                check(configKey().matches(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F9)),
                        "The native editor must rebind the configuration shortcut");
            });
            context.setScreen(() -> null);
            context.getInput().pressKey(GLFW.GLFW_KEY_F8);
            context.waitTick();
            context.runOnClient(client -> check(client.gui.screen() == null, "The old key must stop opening configuration"));
            context.getInput().pressKey(GLFW.GLFW_KEY_F9);
            context.waitFor(client -> client.gui.screen() instanceof ConfigScreen);
            context.clickScreenButton("minermarmot.config.quit");

            context.getInput().pressKey(GLFW.GLFW_KEY_F9);
            context.waitFor(client -> client.gui.screen() instanceof ConfigScreen);
            context.clickScreenButton("minermarmot.config.quit");
            context.runOnClient(client -> check(client.gui.screen() == null, "Closing settings must return to the game without a Discord login"));
        } finally {
            context.runOnClient(client -> {

                configKey().setKey(configKey().getDefaultKey());
                KeyMapping.resetMapping();
                client.options.save();
                client.gui.setScreen(null);
            });
        }
    }

    private static KeyMapping configKey() {
        return KeyMapping.get("key.minermarmot.openConfig");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

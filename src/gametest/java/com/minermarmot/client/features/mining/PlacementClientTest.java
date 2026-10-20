package com.minermarmot.client.features.mining;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.screen.ConfigScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

// Count real native item interactions without relying on a server-specific boost item.
public final class PlacementClientTest implements FabricClientGameTest {
    private final List<Integer> attempts = new ArrayList<>();
    private int clientTick;
    private boolean recording;

    @Override
    public void runTest(ClientGameTestContext context) {

        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        ClientTickEvents.START_CLIENT_TICK.register(client -> clientTick++);
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!level.isClientSide() || !recording || hand != InteractionHand.MAIN_HAND
                    || !player.getMainHandItem().is(Items.STICK)) return InteractionResult.PASS;
            attempts.add(clientTick);
            return InteractionResult.SUCCESS;
        });
        try {
            context.runOnClient(client -> {
                var config = original.copy();
                config.applyPreset("kazuria", ConfigManager.preset("kazuria"));
                check(ConfigManager.save(config), "Placement test settings must save");
                presetChecks();
            });
            settings(context);
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                world.getServer().runCommand("gamemode survival @p");
                world.getServer().runCommand("tp @p 0.5 100 0.5 0 -90");
                world.getServer().runOnServer(server -> {
                    var player = world.getConnection().getServerPlayer();
                    player.getAbilities().mayfly = player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                });
                world.getConnection().waitForClientboundPackets();
                context.waitTicks(3);
                context.runOnClient(client -> recording = true);
                cadence(context, false, false);
                cadence(context, true, false);
                cadence(context, false, true);
                cadence(context, true, true);
                context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
                sample(context, 1);
                context.setScreen(() -> null);
                context.runOnClient(client -> {
                    var config = ConfigManager.current().copy();
                    config.mining.enabled.remove(MiningFeature.PLACEMENT_WITHOUT_DELAY);
                    check(ConfigManager.save(config), "Disabling rapid placement must save while running");
                });
                sample(context, 4);
                context.runOnClient(client -> {
                    MiningManager.stop();
                    client.options.keyUse.setDown(false);
                });
            }
        } finally {
            context.runOnClient(client -> {
                recording = false;
                MiningManager.stop();
                client.options.keyUse.setDown(false);
                client.gui.setScreen(null);
                check(ConfigManager.save(original), "Placement tests must restore settings");
            });
        }
    }

    private void cadence(ClientGameTestContext context, boolean rapid, boolean boost) {
        context.runOnClient(client -> {
            MiningManager.stop();
            client.options.keyUse.setDown(false);
            var config = ConfigManager.current().copy();
            config.mining.enabled = EnumSet.noneOf(MiningFeature.class);
            config.mining.worldBorderDistance = 0;
            if (rapid) config.mining.enabled.add(MiningFeature.PLACEMENT_WITHOUT_DELAY);
            if (boost) config.mining.enabled.add(MiningFeature.AUTO_BOOST);
            check(ConfigManager.save(config), "Cadence settings must save");
            if (boost) MiningManager.toggle();
            else client.options.keyUse.setDown(true);
        });
        sample(context, rapid ? 1 : 4);
    }

    private void sample(ClientGameTestContext context, int interval) {
        // Let an existing four-tick delay expire before measuring steady cadence.
        context.waitTicks(5);
        context.runOnClient(client -> attempts.clear());
        context.waitTicks(20);
        var ticks = context.computeOnClient(client -> List.copyOf(attempts));
        check(ticks.size() >= (interval == 1 ? 18 : 4), "Expected repeated item uses, got " + ticks);
        for (int i = 1; i < ticks.size(); i++) {
            check(ticks.get(i) - ticks.get(i - 1) == interval,
                    "Item uses must be spaced by " + interval + " ticks, got " + ticks);
        }
        System.out.println("Placement cadence: interval=" + interval + ", attempts=" + ticks.size());
    }

    private void settings(ClientGameTestContext context) {
        context.setScreen(() -> new ConfigScreen(null));
        context.clickScreenButton("minermarmot.config.features");
        context.runOnClient(client -> search(client.gui.screen(), "TNT"));
        context.waitTick();
        context.runOnClient(client -> {
            var button = placementButton(client.gui.screen());
            check(button.getWidth() == 70 && !Boolean.TRUE.equals(button.getValue()), "Rapid placement starts OFF");
            button.onPress(new MouseButtonInfo(0, 0));
            check(MiningManager.enabled(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Toggle applies immediately");
            try {
                check(ConfigManager.parseConfig(Files.readString(FabricLoader.getInstance().getConfigDir()
                        .resolve("minermarmot/config.json"))).mining.enabled.contains(MiningFeature.PLACEMENT_WITHOUT_DELAY),
                        "Toggle saves before closing the menu");
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });
        context.takeScreenshot("placement-without-delay-tnt");
        context.runOnClient(client -> search(client.gui.screen(), Component.translatable(
                MiningFeature.PLACEMENT_WITHOUT_DELAY.translationKey()).getString()));
        context.waitTick();
        context.runOnClient(client -> {
            check(placementButton(client.gui.screen()) != null, "Setting name must be searchable");
            var preset = ConfigManager.preset("kazuria");
            preset.features.put(MiningFeature.PLACEMENT_WITHOUT_DELAY, false);
            try {
                client.setScreenAndShow(new ConfigScreen(null));
            } finally {
                preset.features.put(MiningFeature.PLACEMENT_WITHOUT_DELAY, true);
            }
        });
        context.clickScreenButton("minermarmot.config.features");
        context.runOnClient(client -> {
            var preset = ConfigManager.preset("kazuria");
            preset.features.put(MiningFeature.PLACEMENT_WITHOUT_DELAY, false);
            try {
                search(client.gui.screen(), Component.translatable(MiningFeature.PLACEMENT_WITHOUT_DELAY.translationKey()).getString());
                check(buttons(client.gui.screen()).isEmpty(), "Unavailable placement control must be hidden");
                check(!MiningManager.enabled(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Unavailable placement must be inactive");
            } finally {
                preset.features.put(MiningFeature.PLACEMENT_WITHOUT_DELAY, true);
            }
        });
        context.setScreen(() -> null);
    }

    private static void presetChecks() {
        var bundled = ConfigManager.preset("kazuria");
        check(bundled.allows(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Bundled preset allows rapid placement");
        check(!bundled.defaults.enabled.contains(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Bundled default remains OFF");
        var inherited = ConfigManager.parsePreset("{\"name\":\"Legacy\"}", bundled);
        check(inherited.allows(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Legacy bundled preset inherits availability");
        var disabled = ConfigManager.parsePreset("{\"name\":\"Restricted\",\"features\":{\"placementWithoutDelay\":false}}", bundled);
        check(!disabled.allows(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Explicit restrictions take precedence");
        check(!ConfigManager.parsePreset("{\"name\":\"Custom\"}").allows(MiningFeature.PLACEMENT_WITHOUT_DELAY),
                "Custom presets must opt in");
        var config = ConfigManager.current().copy();
        config.mining.enabled.add(MiningFeature.PLACEMENT_WITHOUT_DELAY);
        var copy = config.copy();
        check(ConfigManager.parseConfig(new com.google.gson.Gson().toJson(copy)).mining.enabled
                .contains(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Copy and JSON retain activation");
        copy.resetPreset(bundled);
        check(!copy.mining.enabled.contains(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Reset restores OFF");
        check(config.mining.enabled.contains(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Copy must remain independent");
        config.validate(disabled);
        check(!config.mining.enabled.contains(MiningFeature.PLACEMENT_WITHOUT_DELAY), "Validation removes unavailable activation");
    }

    private static void search(net.minecraft.client.gui.screens.Screen screen, String query) {
        screen.children().stream().filter(child -> child instanceof EditBox).map(child -> (EditBox) child)
                .findFirst().orElseThrow().setValue(query);
    }

    private static List<? extends CycleButton<?>> buttons(net.minecraft.client.gui.screens.Screen screen) {
        return screen.children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                .flatMap(entry -> entry.children().stream()).filter(child -> child instanceof CycleButton<?>)
                .map(child -> (CycleButton<?>) child).toList();
    }

    private static CycleButton<?> placementButton(net.minecraft.client.gui.screens.Screen screen) {
        return buttons(screen).stream().filter(button -> button.createDefaultNarrationMessage().getString()
                .contains(Component.translatable(MiningFeature.PLACEMENT_WITHOUT_DELAY.translationKey()).getString()))
                .findFirst().orElseThrow();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package com.minermarmot.client.features.alerts;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.mixin.AudioChannelAccessor;
import com.minermarmot.client.screen.ConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL10;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class AlertVolumeClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {

        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        int width = context.computeOnClient(client -> client.getWindow().getWidth());
        int height = context.computeOnClient(client -> client.getWindow().getHeight());
        double ui = context.computeOnClient(client -> client.options.getSoundSourceOptionInstance(SoundSource.UI).get());
        List<AlertSoundInstance> played = new ArrayList<>();
        SoundEventListener listener = (sound, event, range) -> {
            if (sound instanceof AlertSoundInstance alert) played.add(alert);
        };
        try {
            context.runOnClient(client -> {
                var config = original.copy();
                config.applyPreset("kazuria", ConfigManager.preset("kazuria"));
                config.resetPreset(ConfigManager.preset("kazuria"));
                check(ConfigManager.save(config), "Volume fixture must save");
                client.getSoundManager().addListener(listener);
                client.options.getSoundSourceOptionInstance(SoundSource.UI).set(0.2);
                checkEngine(client, played);
            });
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.features");
            BlockAlertsClientTest.search(context, Component.translatable("minermarmot.alerts.volume").getString());
            context.waitTick();
            context.runOnClient(client -> {
                var controls = volumeControls(client);
                check(controls.size() == 3, "Volume search must reveal inventory, PM and block channel controls");
                for (var control : controls) {
                    for (float expected : new float[]{1.5f, 2, 2.5f, 3, 3.5f, 4, 4.5f, 5, 0.5f, 1}) {
                        control.onPress(new MouseButtonInfo(0, 0));
                        check(control.getValue() == expected, "Volume must cycle through every half-step and wrap");
                        BlockAlertsClientTest.checkSaved();
                    }
                }
            });
            for (int guiScale : new int[]{1, 2}) {
                context.runOnClient(client -> {
                    client.options.guiScale().set(guiScale);
                    client.resizeGui();
                });
                context.waitTick();
                context.runOnClient(AlertVolumeClientTest::checkLayout);
                context.takeScreenshot("alert-volume-scale-" + guiScale);
            }
            context.runOnClient(client -> {
                client.options.guiScale().set(1);
                client.getWindow().setWindowed(320, 640);
            });
            context.waitFor(client -> client.gui.screen().width == 320);
            context.waitTick();
            context.runOnClient(AlertVolumeClientTest::checkLayout);
            context.takeScreenshot("alert-volume-minimum");
            context.runOnClient(client -> {
                for (var row : optionList(client).children()) {
                    if (row.children().stream().noneMatch(child -> child instanceof CycleButton<?> button && button.getValue() instanceof Float)) continue;
                    row.children().stream().filter(child -> child instanceof CycleButton<?> button && button.getValue() instanceof Boolean)
                            .map(child -> (CycleButton<?>) child).forEach(button -> button.onPress(new MouseButtonInfo(0, 0)));
                }
                var controls = volumeControls(client);
                controls.forEach(control -> {
                    for (int step = 0; step < 9; step++) control.onPress(new MouseButtonInfo(0, 0));
                });
                check(ConfigManager.current().playerAlerts.inventoryFullVolume == 0.5f
                                && ConfigManager.current().playerAlerts.privateMessageVolume == 0.5f
                                && ConfigManager.current().alerts.soundVolume == 0.5f,
                        "All three independent volume controls must apply");
                BlockAlertsClientTest.checkSaved();
            });
            BlockAlertsClientTest.search(context, Component.translatable("minermarmot.alerts.privateMessage").getString());
            BlockAlertsClientTest.clickListButton(context, "minermarmot.alerts.sound.pling");
            BlockAlertsClientTest.clickListButton(context, "minermarmot.alerts.previewSound");
            context.runOnClient(client -> {
                check(played.getLast().volumeMultiplier() == 0.5f, "The real player-alert picker must preview at its selected volume");
                client.gui.screen().onClose();
            });
            context.clickScreenButton("minermarmot.config.general");
            context.clickScreenButton("minermarmot.config.resetPreset");
            context.runOnClient(client -> check(ConfigManager.current().alerts.soundVolume == 1
                            && ConfigManager.current().playerAlerts.inventoryFullVolume == 1
                            && ConfigManager.current().playerAlerts.privateMessageVolume == 1,
                    "Reset must restore the three volumes"));
        } finally {
            context.runOnClient(client -> {
                client.getSoundManager().removeListener(listener);
                client.getSoundManager().stop();
                client.options.getSoundSourceOptionInstance(SoundSource.UI).set(ui);
                client.getWindow().setWindowed(width, height);
                client.options.guiScale().set(scale);
                client.resizeGui();
                check(ConfigManager.save(original), "Volume tests must restore preferences");
                client.gui.setScreen(null);
            });
        }
    }

    private static void checkEngine(Minecraft client, List<AlertSoundInstance> played) {
        try {
            var field = SoundManager.class.getDeclaredField("soundEngine");
            field.setAccessible(true);
            SoundEngine engine = (SoundEngine) field.get(client.getSoundManager());
            var calculate = SoundEngine.class.getDeclaredMethod("calculateVolume", SoundInstance.class);
            calculate.setAccessible(true);
            var channelsField = SoundEngine.class.getDeclaredField("instanceToChannel");
            channelsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var channels = (Map<SoundInstance, ChannelAccess.ChannelHandle>) channelsField.get(engine);
            Identifier id = Identifier.parse("minecraft:block.note_block.pling");
            for (float multiplier : BlockAlertSettings.VOLUMES) {
                BlockAlertManager.playSound(id.toString(), multiplier);
                AlertSoundInstance alert = played.getLast();
                check(alert.volumeMultiplier() == multiplier, "Playback must retain its multiplier");
                float calculated = (float) calculate.invoke(engine, alert);
                check(Math.abs(calculated - 0.2f * multiplier) < 0.0001f, "Engine refresh must retain amplification");
                CompletableFuture<float[]> gains = new CompletableFuture<>();
                channels.get(alert).execute(channel -> {
                    int source = ((AudioChannelAccessor) channel).minermarmot$source();
                    gains.complete(new float[]{AL10.alGetSourcef(source, AL10.AL_GAIN), AL10.alGetSourcef(source, AL10.AL_MAX_GAIN)});
                });
                float[] actual = gains.get(5, TimeUnit.SECONDS);
                check(Math.abs(actual[0] - calculated) < 0.0001f, "Initial native channel gain must match the requested multiplier");
                check(actual[1] >= multiplier, "OpenAL must allow amplification above its default source ceiling");
                client.getSoundManager().stop(alert);
            }
            var vanilla = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), 1, 1);
            vanilla.resolve(client.getSoundManager());
            check(Math.abs((float) calculate.invoke(engine, vanilla) - 0.2f) < 0.0001f, "Ordinary sounds must retain vanilla gain");
            client.options.getSoundSourceOptionInstance(SoundSource.UI).set(0.0);
            var muted = new AlertSoundInstance(id, 5);
            muted.resolve(client.getSoundManager());
            check((float) calculate.invoke(engine, muted) == 0, "Minecraft mute must silence amplified alerts");
            client.options.getSoundSourceOptionInstance(SoundSource.UI).set(0.2);
        } catch (Exception exception) {
            throw new AssertionError("Cannot validate native alert gain", exception);
        }
    }

    private static List<CycleButton<Float>> volumeControls(Minecraft client) {
        return optionList(client).children().stream().flatMap(entry -> entry.children().stream())
                .filter(child -> child instanceof CycleButton<?> button && button.getValue() instanceof Float)
                .map(child -> {
                    @SuppressWarnings("unchecked")
                    var button = (CycleButton<Float>) child;
                    return button;
                }).toList();
    }

    private static ContainerObjectSelectionList<?> optionList(Minecraft client) {
        return client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).findFirst().orElseThrow();
    }

    private static void checkLayout(Minecraft client) {
        var list = optionList(client);
        check(volumeControls(client).size() == 3, "Resizing must preserve volume search results");
        for (var row : list.children()) {
            if (row.getContentY() < list.getY() || row.getContentY() >= list.getBottom()) continue;
            var widgets = row.children().stream().map(child -> (AbstractWidget) child).toList();
            for (var widget : widgets) {
                check(widget.getX() >= list.getX() && widget.getRight() <= list.getRight(), "Volume controls must fit the panel");
                for (var other : widgets) {
                    check(widget == other || widget.getRight() <= other.getX() || other.getRight() <= widget.getX()
                                    || widget.getBottom() <= other.getY() || other.getBottom() <= widget.getY(),
                            "Volume columns must not overlap");
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

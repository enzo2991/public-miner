package com.minermarmot.client.features.portal;

import com.minermarmot.client.config.ConfigManager;

import com.minermarmot.client.screen.ConfigScreen;
import com.minermarmot.client.screen.widget.ConfigSectionWidget;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.List;

public final class PortalClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {

        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            context.runOnClient(client -> {
                var config = original.copy();
                config.applyPreset("kazuria", ConfigManager.preset("kazuria"));
                config.portals = ConfigManager.preset("kazuria").portals.newConfig();
                config.alerts.waypoints = false;
                check(ConfigManager.save(config), "Portal settings must save");
            });
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.features");
            context.runOnClient(client -> {
                var section = widgets(client).stream().filter(widget -> widget instanceof ConfigSectionWidget
                        && widget.getMessage().getString().equals(Component.translatable("minermarmot.portal.title").getString()))
                        .map(widget -> (ConfigSectionWidget) widget).findFirst().orElseThrow();
                check(!section.isExpanded(), "Portal must start collapsed");
                section.onPress(new MouseButtonInfo(0, 0));
                search(client).setValue("Portal");
                toggle(client, "Portail Aether");
                toggle(client, "Portail Warden");
                check(ConfigManager.current().portals.monitors.values().stream().allMatch(Boolean.TRUE::equals), "Monitor switches must save immediately");
                input(client, "waypointCount").setValue("101");
                check(ConfigManager.current().portals.displayedWaypointCount == 3, "Invalid count must preserve settings");
                input(client, "waypointCount").setValue("10");
                input(client, "waypointDistance").setValue("0");
                check(ConfigManager.current().portals.waypointDisplayDistance == 256, "Invalid distance must preserve settings");
                input(client, "waypointDistance").setValue("128");
                try {
                    var saved = ConfigManager.parseConfig(Files.readString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minermarmot/config.json")));
                    check(saved.portals.displayedWaypointCount == 10 && saved.portals.waypointDisplayDistance == 128
                            && saved.portals.monitors.get("warden"), "Portal settings must already be on disk");
                } catch (java.io.IOException exception) { throw new AssertionError(exception); }
            });
            for (int value : new int[]{1, 2}) {
                context.runOnClient(client -> { client.options.guiScale().set(value); client.resizeGui(); });
                context.waitTick();
                context.runOnClient(client -> {
                    var list = list(client);
                    widgets(client).stream().filter(widget -> widget.getBottom() > list.getY() && widget.getY() < list.getBottom())
                            .forEach(widget -> check(widget.getX() >= list.getX() && widget.getRight() <= list.getRight(), "Visible portal controls must fit the list"));
                });
                context.takeScreenshot("portal-settings-scale-" + value);
            }
            int windowWidth = context.computeOnClient(client -> client.getWindow().getScreenWidth());
            int windowHeight = context.computeOnClient(client -> client.getWindow().getScreenHeight());
            try {
                context.runOnClient(client -> client.getWindow().setWindowed(640, 480));
                context.waitFor(client -> client.gui.screen().width == 320);
                context.runOnClient(client -> list(client).setScrollAmount(list(client).maxScrollAmount()));
                context.waitTick();
                context.runOnClient(client -> {
                    var list = list(client);
                    widgets(client).stream().filter(widget -> widget.getBottom() > list.getY() && widget.getY() < list.getBottom())
                            .forEach(widget -> check(widget.getX() >= list.getX() && widget.getRight() <= list.getRight(), "Portal controls must fit minimum width"));
                });
                context.takeScreenshot("portal-settings-minimum-width");
            } finally {
                context.runOnClient(client -> client.getWindow().setWindowed(windowWidth, windowHeight));
                context.waitFor(client -> client.getWindow().getScreenWidth() == windowWidth);
            }
            context.clickScreenButton("minermarmot.config.quit");
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                world.getServer().runCommand("gamemode creative @p");
                world.getServer().runCommand("fill -4 64 -4 24 73 18 minecraft:air");
                world.getServer().runCommand("fill -4 63 -4 24 63 18 minecraft:stone");
                world.getServer().runCommand("tp @p 0.5 64 0.5 180 0");
                world.getServer().runCommand("fill 0 65 5 1 65 5 minecraft:sea_lantern");
                world.getServer().runCommand("fill 9 64 4 12 67 6 minecraft:stone");
                world.getServer().runCommand("fill 10 65 5 11 65 5 minecraft:sea_lantern");
                world.getServer().runCommand("fill -4 64 14 -2 64 16 minecraft:end_portal");
                world.getServer().runCommand("setblock 4 65 5 minecraft:sea_lantern");
                world.getConnection().waitForClientboundPackets();
                context.waitTicks(12);
                context.runOnClient(client -> check(points(client).isEmpty(), "Unseen and hidden portals must not be discovered"));
                world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
                world.getConnection().waitForClientboundPackets();
                context.waitTicks(8);
                context.takeScreenshot("portal-visible-fixture");
                context.waitFor(client -> points(client).size() == 2);
                context.runOnClient(client -> {
                    check(points(client).getFirst().type().id.equals("aether") && points(client).getLast().type().id.equals("warden"), "Portals must sort by actual marker distance");
                    check(points(client).stream().noneMatch(wp -> wp.pattern().anchor().getX() == 10), "Hidden lantern pair must remain unknown");
                    var config = ConfigManager.current().copy();
                    config.portals.displayedWaypointCount = 1;
                    check(ConfigManager.save(config), "Count change must save");
                    check(points(client).size() == 1, "Count must limit portal markers and list together");
                    config.portals.displayedWaypointCount = 0;
                    check(ConfigManager.save(config) && points(client).isEmpty(), "Zero must hide all portal waypoints");
                    config.portals.displayedWaypointCount = 10;
                    config.portals.waypointDisplayDistance = 1;
                    check(ConfigManager.save(config) && points(client).isEmpty(), "Portal distance must filter immediately");
                    config.portals.waypointDisplayDistance = 128;
                    config.portals.monitors.put("aether", false);
                    check(ConfigManager.save(config) && points(client).size() == 1, "Each Monitor must hide only its type");
                    config.portals.monitors.put("aether", true);
                    check(ConfigManager.save(config) && points(client).size() == 2, "Monitor OFF must preserve memory");
                });
                context.takeScreenshot("portal-discovered-hud");
                world.getServer().runCommand("setblock -2 65 5 minecraft:note_block[instrument=basedrum,note=2]");
                world.getConnection().waitForClientboundPackets();
                context.runOnClient(client -> {
                    var config = ConfigManager.current().copy();
                    config.alerts.waypoints = true;
                    config.alerts.targets.get("citrouille_commun").enabled = true;
                    check(ConfigManager.save(config), "Enable a block waypoint beside portal waypoints");
                });
                context.waitFor(client -> !com.minermarmot.client.features.alerts.BlockAlertManager.remembered().isEmpty());
                context.takeScreenshot("portal-and-block-lists");
                world.getServer().runCommand("tp @p 0.5 64 0.5 180 0");
                world.getConnection().waitForClientboundPackets();
                context.runOnClient(client -> {
                    var chunk = client.level.getChunkSource().getChunk(0, 0, ChunkStatus.FULL, false);
                    ClientChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(client.level, chunk);
                    check(points(client).size() == 2, "Chunk unload must preserve portal memory");
                    ClientChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(client.level, chunk);
                });
                world.getServer().runCommand("execute in minecraft:the_nether run tp @p 0.5 64 0.5 180 0");
                world.getConnection().waitForClientboundPackets();
                context.waitFor(client -> client.level.dimension() == net.minecraft.world.level.Level.NETHER);
                context.runOnClient(client -> check(points(client).isEmpty(), "Other dimensions must hide remembered portal waypoints"));
                world.getServer().runCommand("execute in minecraft:overworld run tp @p 0.5 64 0.5 180 0");
                world.getConnection().waitForClientboundPackets();
                context.waitFor(client -> client.level.dimension() == net.minecraft.world.level.Level.OVERWORLD);
                context.runOnClient(client -> check(points(client).size() == 2, "Returning to the world must retain portal memory"));
                context.setScreen(() -> new ConfigScreen(null));
                context.clickScreenButton("minermarmot.config.features");
                context.runOnClient(client -> search(client).setValue("Portal"));
                context.runOnClient(client -> widgets(client).stream().filter(widget -> widget instanceof Button
                                && widget.getMessage().equals(Component.translatable("minermarmot.portal.reset")))
                        .map(widget -> (Button) widget).findFirst().orElseThrow().onPress(new MouseButtonInfo(0, 0)));
                context.runOnClient(client -> check(points(client).isEmpty(), "Portal reset button must clear memory immediately"));
                context.clickScreenButton("minermarmot.config.quit");
                world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
                world.getConnection().waitForClientboundPackets();
                context.waitFor(client -> points(client).size() == 2);
            }
            context.runOnClient(client -> check(PortalManager.waypoints(Vec3.ZERO).isEmpty(), "Disconnect must clear portal memory"));
            try (var nextWorld = context.worldBuilder().create()) {
                nextWorld.getConnection().waitForChunksDownload();
                nextWorld.getServer().runCommand("tp @p 0.5 64 0.5 180 0");
                nextWorld.getConnection().waitForClientboundPackets();
                context.waitTicks(8);
                context.runOnClient(client -> check(points(client).isEmpty(), "Reconnection must not restore previous session discoveries"));
            }
        } finally {
            context.runOnClient(client -> { PortalManager.reset(); client.options.guiScale().set(scale); client.resizeGui(); check(ConfigManager.save(original), "Restore original settings"); });
        }
    }

    private static List<PortalManager.Waypoint> points(Minecraft client) { return PortalManager.waypoints(client.player.position()); }
    private static ContainerObjectSelectionList<?> list(Minecraft client) {
        return client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).findFirst().orElseThrow();
    }
    private static List<AbstractWidget> widgets(Minecraft client) {
        return list(client).children().stream().flatMap(entry -> entry.children().stream())
                .filter(child -> child instanceof AbstractWidget).map(child -> (AbstractWidget) child).toList();
    }
    private static EditBox search(Minecraft client) {
        return client.gui.screen().children().stream().filter(child -> child instanceof EditBox).map(child -> (EditBox) child).findFirst().orElseThrow();
    }
    private static EditBox input(Minecraft client, String key) {
        return widgets(client).stream().filter(widget -> widget instanceof EditBox && widget.getMessage().equals(Component.translatable("minermarmot.portal." + key)))
                .map(widget -> (EditBox) widget).findFirst().orElseThrow();
    }
    private static void toggle(Minecraft client, String label) {
        widgets(client).stream().filter(widget -> widget instanceof CycleButton<?> button && button.createDefaultNarrationMessage().getString().contains(label))
                .map(widget -> (CycleButton<?>) widget).findFirst().orElseThrow().onPress(new MouseButtonInfo(0, 0));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

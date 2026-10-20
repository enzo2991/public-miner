package com.minermarmot.client.features.display;

import com.google.gson.Gson;
import com.minermarmot.client.config.ClientConfig;
import com.minermarmot.client.config.ConfigManager;

import com.minermarmot.client.screen.ConfigScreen;
import com.minermarmot.client.screen.widget.ConfigSectionWidget;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// Experimental client test APIs are confined to the test mod.
public final class DisplayClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {

        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            context.runOnClient(client -> {
                var old = ConfigManager.parseConfig("{\"preset\":\"default\"}");
                old.validate(ConfigManager.preset("default"));
                check(!old.displayFps && !old.displayPosition && !old.displayObjectives, "Old settings must default all displays to OFF");
                check(!old.objectivesOnRight, "Old settings must place objectives on the left");
                old.displayFps = old.displayPosition = old.displayObjectives = true;
                old.objectivesOnRight = true;
                var copy = old.copy();
                copy.displayFps = false;
                copy.displayObjectives = false;
                copy.objectivesOnRight = false;
                check(old.displayFps && old.displayObjectives, "Editing a copy must preserve the original display preferences");
                check(old.objectivesOnRight, "Editing a copy must preserve the original side");
                var restored = ConfigManager.parseConfig(new Gson().toJson(old));
                check(restored.displayFps && restored.displayPosition && restored.displayObjectives, "JSON must retain display preferences");
                check(restored.objectivesOnRight, "JSON must retain the selected side");
                restored.applyPreset("kazuria", ConfigManager.preset("kazuria"));
                check(restored.displayFps && restored.displayPosition && restored.displayObjectives, "Preset changes must retain global display preferences");
                check(restored.objectivesOnRight, "Preset changes must retain the selected side");
                restored.resetPreset(ConfigManager.preset("kazuria"));
                check(!restored.displayFps && !restored.displayPosition && !restored.displayObjectives, "Reset must disable all displays");
                check(!restored.objectivesOnRight, "Reset must restore the left side");
                var config = original.copy();
                config.displayFps = config.displayPosition = config.displayObjectives = false;
                config.objectivesOnRight = false;
                check(ConfigManager.save(config), "Display settings must save");
                var graphics = new TextCapture(client);
                DisplayRenderer.render(graphics);
                check(graphics.lines.isEmpty(), "The display must be absent outside a world");
            });
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.features");
            context.runOnClient(client -> {
                var section = widgets(client).stream().filter(widget -> widget instanceof ConfigSectionWidget
                        && widget.getMessage().getString().equals("Display")).map(widget -> (ConfigSectionWidget) widget)
                        .findFirst().orElseThrow();
                check(!section.isExpanded(), "Display must start collapsed");
                section.onPress(new MouseButtonInfo(0, 0));
                toggle(client, "displayFps");
                toggle(client, "displayPosition");
                toggle(client, "displayObjectives");
                toggle(client, "objectivesSide");
                check(ConfigManager.current().displayFps && ConfigManager.current().displayPosition && ConfigManager.current().displayObjectives,
                        "All switches must apply immediately");
                check(ConfigManager.current().objectivesOnRight, "The side must switch to right immediately");
                checkSaved();
            });
            for (int scale : new int[]{1, 2}) {
                context.runOnClient(client -> {
                    client.options.guiScale().set(scale);
                    client.resizeGui();
                    check(widgets(client).stream().filter(widget -> widget instanceof ConfigSectionWidget
                            && widget.getMessage().getString().equals("Display"))
                            .allMatch(widget -> ((ConfigSectionWidget) widget).isExpanded()),
                            "Resizing must preserve the expanded section");
                    search(client).setValue("Display");
                });
                context.waitTick();
                context.runOnClient(client -> {
                    var list = optionList(client);
                    check(displayWidgets(client).size() == 5,
                            "Display search must reveal all switches and the side selector");
                    displayWidgets(client).forEach(widget -> check(widget.getX() >= list.getX() && widget.getRight() <= list.getRight(),
                            "Display controls must stay inside the list"));
                });
                context.takeScreenshot("display-settings-scale-" + scale);
            }
            context.runOnClient(client -> {
                search(client).setValue("FPS");
                check(widgets(client).stream().filter(widget -> widget instanceof CycleButton<?>).count() == 1,
                        "FPS search must reveal only the FPS switch");
                toggle(client, "displayFps");
                check(!ConfigManager.current().displayFps && ConfigManager.current().displayPosition,
                        "Switches must work independently");
                checkSaved();
                search(client).setValue("Position");
                toggle(client, "displayPosition");
                check(!ConfigManager.current().displayPosition, "Position must turn OFF immediately");
                checkSaved();
                search(client).setValue("Objectives");
                check(widgets(client).stream().filter(widget -> widget instanceof CycleButton<?>).count() == 2,
                        "Objectives search must reveal the objectives switch and side selector");
                toggle(client, "displayObjectives");
                check(!ConfigManager.current().displayObjectives, "Objectives must turn OFF immediately");
                toggle(client, "objectivesSide");
                check(!ConfigManager.current().objectivesOnRight, "The side must remain editable with Objectives OFF");
                checkSaved();
                search(client).setValue("Objectives side");
                toggle(client, "objectivesSide");
                check(ConfigManager.current().objectivesOnRight, "Searching the side must reveal its selector");
                checkSaved();
            });
            context.takeScreenshot("display-objectives-side-selector");
            int windowWidth = context.computeOnClient(client -> client.getWindow().getScreenWidth());
            int windowHeight = context.computeOnClient(client -> client.getWindow().getScreenHeight());
            try {
                context.runOnClient(client -> {
                    search(client).setValue("Display");
                    client.getWindow().setWindowed(640, 480);
                });
                context.waitFor(client -> client.gui.screen().width == 320);
                context.waitTick();
                context.runOnClient(client -> displayWidgets(client).forEach(widget -> check(
                        widget.getX() >= optionList(client).getX() && widget.getRight() <= optionList(client).getRight(),
                        "Display controls must fit at minimum GUI width")));
                context.takeScreenshot("display-settings-minimum-width");
            } finally {
                context.runOnClient(client -> client.getWindow().setWindowed(windowWidth, windowHeight));
            }
            context.clickScreenButton("minermarmot.config.general");
            context.runOnClient(client -> ConfigManager.current().displayObjectives = true);
            context.clickScreenButton("minermarmot.config.resetPreset");
            context.runOnClient(client -> {
                check(!ConfigManager.current().displayFps && !ConfigManager.current().displayPosition && !ConfigManager.current().displayObjectives,
                        "The General reset button must disable all displays");
                check(!ConfigManager.current().objectivesOnRight, "The General reset button must restore the left side");
                checkSaved();
            });
            context.clickScreenButton("minermarmot.config.quit");
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                context.runOnClient(client -> parserChecks());
                world.getServer().runCommand("gamemode spectator @p");
                world.getServer().runCommand("tp @p -0.25 100.75 -12.25 0 0");
                world.getConnection().waitForClientboundPackets();
                context.waitTick();
                for (int flags = 0; flags < 4; flags++) {
                    final int enabled = flags;
                    context.runOnClient(client -> {
                        var config = ConfigManager.current().copy();
                        config.displayFps = (enabled & 1) != 0;
                        config.displayPosition = (enabled & 2) != 0;
                        check(ConfigManager.save(config), "Each display combination must save");
                        checkSaved();
                        var graphics = new TextCapture(client);
                        client.gui.hud.extractRenderState(graphics, DeltaTracker.ZERO);
                        check(graphics.lines.size() == Integer.bitCount(enabled), "Only enabled displays must render");
                        if (config.displayFps) {
                            check(graphics.lines.getFirst().text.equals("FPS : " + client.getFps()), "FPS format must match exactly");
                        }
                        if (config.displayPosition) {
                            check(graphics.lines.getLast().text.equals("X: -1, Y: 100, Z: -13"),
                                    "Position must use floored block coordinates, including negative values");
                        }
                        for (int row = 0; row < graphics.lines.size(); row++) {
                            var line = graphics.lines.get(row);
                            check(line.x == 6 && line.y == 6 + row * (client.font.lineHeight + 1)
                                    && line.color == 0xFFFFFFFF && line.shadow, "HUD lines must stack at the upper left in white with a shadow");
                        }
                    });
                    context.takeScreenshot("display-hud-" + flags);
                }
                objectiveHud(context, world);
                context.getInput().pressKey(GLFW.GLFW_KEY_F1);
                context.runOnClient(client -> {
                    check(client.gui.hud.isHidden(), "F1 must hide the HUD");
                    var graphics = new TextCapture(client);
                    client.gui.hud.extractRenderState(graphics, DeltaTracker.ZERO);
                    check(graphics.lines.isEmpty() && graphics.objectives.isEmpty(), "F1 must hide every HUD line");
                });
                context.getInput().pressKey(GLFW.GLFW_KEY_F1);
            }
            context.runOnClient(client -> {
                var graphics = new TextCapture(client);
                DisplayRenderer.render(graphics);
                check(graphics.lines.isEmpty() && graphics.objectives.isEmpty(), "Disconnect must hide all displays");
            });
        } finally {
            context.runOnClient(client -> {
                if (client.gui.hud.isHidden()) client.gui.hud.toggle();
                client.options.guiScale().set(originalScale);
                client.resizeGui();
                check(ConfigManager.save(original), "Original settings must be restored");
            });
        }
    }

    private static void parserChecks() {
        check(DisplayRenderer.objectives(ItemStack.EMPTY).isEmpty(), "Empty slots must have no objectives");
        var stack = new ItemStack(Items.DIAMOND_PICKAXE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§bPIOCHE évolutive"));
        check(DisplayRenderer.objectives(stack).isEmpty(), "A pickaxe without lore must have no objectives");
        var heading = Component.literal("Objectifs d’évolution :").withStyle(ChatFormatting.GOLD);
        var progress = Component.literal("Miner : ").append(Component.literal("12 / 100").withStyle(ChatFormatting.GREEN));
        var lore = new ItemLore(List.of(Component.literal("Before /autosell"), Component.literal("Before heading"),
                heading, Component.literal(""), Component.literal("§7  \u00a0"), progress,
                Component.literal("Utilisez /autosell"), Component.literal("After autosell")));
        stack.set(DataComponents.LORE, lore);
        var lines = DisplayRenderer.objectives(stack);
        check(lines.equals(List.of(heading, progress)), "Only the heading and nonblank lines before /autosell must remain");
        check(lines.getFirst() == heading && lines.getLast() == progress && stack.get(DataComponents.LORE) == lore,
                "Extraction must preserve original components, styles and item lore");
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Sword"));
        check(DisplayRenderer.objectives(stack).isEmpty(), "Other item names must be ignored even with matching lore");
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Pioche"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(progress)));
        check(DisplayRenderer.objectives(stack).isEmpty(), "Missing heading must hide unrelated lore");
        var legacyHeading = Component.literal("§6Objectifs d'évolution :");
        stack.set(DataComponents.LORE, new ItemLore(List.of(legacyHeading, progress)));
        check(DisplayRenderer.objectives(stack).equals(List.of(legacyHeading, progress)),
                "A straight apostrophe and legacy colors must match; missing /autosell must retain the remaining lore");
    }

    private static ItemStack pickaxe(String objective) {
        var stack = new ItemStack(Items.DIAMOND_PICKAXE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Pioche évolutive"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Description before objectives"),
                Component.literal("Objectifs d'évolution :").withStyle(ChatFormatting.GOLD), Component.empty(),
                Component.literal(objective).withStyle(ChatFormatting.GREEN), Component.literal(" "),
                Component.literal("/autosell"), Component.literal("Description after autosell"))));
        return stack;
    }

    private static void objectiveHud(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> {
            ConfigManager.current().displayObjectives = true;
            check(ConfigManager.save(ConfigManager.current()), "Objectives must save independently of the other displays");
        });
        world.getServer().runOnServer(server -> {
            var inventory = world.getConnection().getServerPlayer().getInventory();
            inventory.setItem(10, new ItemStack(Items.DIAMOND_PICKAXE));
            inventory.setItem(20, pickaxe("Miner : 12 / 100"));
        });
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(3);
        checkObjectives(context, "Miner : 12 / 100");
        context.takeScreenshot("display-objectives-inventory");
        context.runOnClient(client -> {
            ConfigManager.current().objectivesOnRight = true;
            check(ConfigManager.save(ConfigManager.current()), "The right side must save");
            checkSaved();
        });
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        for (int scale : new int[]{1, 2}) {
            context.runOnClient(client -> {
                client.options.guiScale().set(scale);
                client.resizeGui();
            });
            checkObjectives(context, "Miner : 12 / 100");
            context.takeScreenshot("display-objectives-right-scale-" + scale);
        }
        context.runOnClient(client -> {
            client.options.guiScale().set(originalScale);
            client.resizeGui();
            ConfigManager.current().objectivesOnRight = false;
            check(ConfigManager.save(ConfigManager.current()), "Switching back to the left must save");
            checkSaved();
        });
        checkObjectives(context, "Miner : 12 / 100");
        world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().getInventory()
                .setItem(0, pickaxe("Miner : 50 / 100")));
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(3);
        checkObjectives(context, "Miner : 50 / 100");
        world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().getInventory()
                .getItem(0).set(DataComponents.LORE, pickaxe("Miner : 60 / 100").get(DataComponents.LORE)));
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(3);
        checkObjectives(context, "Miner : 60 / 100");
        context.runOnClient(client -> {
            ConfigManager.current().displayFps = ConfigManager.current().displayPosition = false;
            DisplayRenderer.updateObjectives(client);
            var graphics = new TextCapture(client);
            DisplayRenderer.render(graphics);
            check(graphics.lines.isEmpty() && graphics.objectives.size() == 2, "Objectives must render with FPS and Position OFF");
            ConfigManager.current().displayFps = ConfigManager.current().displayPosition = true;
        });
        world.getServer().runOnServer(server -> {
            var inventory = world.getConnection().getServerPlayer().getInventory();
            inventory.setItem(0, ItemStack.EMPTY);
            inventory.setItem(40, pickaxe("Miner : 75 / 100"));
        });
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(3);
        checkObjectives(context, "Miner : 75 / 100");
        context.runOnClient(client -> {
            ConfigManager.current().displayObjectives = false;
            var graphics = new TextCapture(client);
            DisplayRenderer.render(graphics);
            check(graphics.objectives.isEmpty() && graphics.lines.size() == 2, "OFF must hide objectives immediately and retain FPS/Position");
            ConfigManager.current().displayObjectives = true;
        });
        world.getServer().runOnServer(server -> {
            var inventory = world.getConnection().getServerPlayer().getInventory();
            inventory.setItem(20, ItemStack.EMPTY);
            inventory.setItem(40, ItemStack.EMPTY);
        });
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(3);
        context.runOnClient(client -> {
            var graphics = new TextCapture(client);
            DisplayRenderer.render(graphics);
            check(graphics.objectives.isEmpty(), "Removing the matching items must remove the objectives");
        });
        world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().getInventory()
                .setItem(20, pickaxe("Miner : 100 / 100")));
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(3);
        checkObjectives(context, "Miner : 100 / 100");
    }

    private static void checkObjectives(ClientGameTestContext context, String progress) {
        context.runOnClient(client -> {
            var graphics = new TextCapture(client);
            client.gui.hud.extractRenderState(graphics, DeltaTracker.ZERO);
            check(graphics.objectives.size() == 2 && graphics.objectives.getFirst().text.equals("Objectifs d'évolution :")
                    && graphics.objectives.getLast().text.equals(progress), "The HUD must use current server lore without blank or unrelated lines");
            int lineHeight = client.font.lineHeight + 1;
            int top = (graphics.guiHeight() - 2 * lineHeight + 1) / 2;
            int left = graphics.objectives.getFirst().x;
            int right = graphics.objectives.stream().mapToInt(line -> line.x + client.font.width(line.text)).max().orElseThrow();
            check(ConfigManager.current().objectivesOnRight ? right == graphics.guiWidth() - 6 : left == 6,
                    "The objective block must keep a six-pixel margin on the selected screen edge");
            for (int row = 0; row < 2; row++) {
                var line = graphics.objectives.get(row);
                check(line.x == left && line.y == top + row * lineHeight && line.color == 0xFFFFFFFF && line.shadow,
                        "Objectives must remain vertically centered and internally aligned with native styling and a shadow");
            }
        });
    }

    private static ContainerObjectSelectionList<?> optionList(Minecraft client) {
        return client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).findFirst().orElseThrow();
    }

    private static List<AbstractWidget> widgets(Minecraft client) {
        return optionList(client).children().stream().flatMap(entry -> entry.children().stream())
                .filter(child -> child instanceof AbstractWidget).map(child -> (AbstractWidget) child).toList();
    }

    private static EditBox search(Minecraft client) {
        return client.gui.screen().children().stream().filter(child -> child instanceof EditBox)
                .map(child -> (EditBox) child).findFirst().orElseThrow();
    }

    private static List<AbstractWidget> displayWidgets(Minecraft client) {
        return widgets(client).stream().filter(widget -> widget instanceof ConfigSectionWidget
                && widget.getMessage().getString().equals("Display") || widget instanceof CycleButton<?> button
                && (button.createDefaultNarrationMessage().getString().contains("FPS:")
                || button.createDefaultNarrationMessage().getString().contains("Position:")
                || button.createDefaultNarrationMessage().getString().contains("Objectives:")
                || button.createDefaultNarrationMessage().getString().contains("Objectives side:"))).toList();
    }

    private static void toggle(Minecraft client, String key) {
        String label = Component.translatable("minermarmot.config." + key).getString() + ":";
        widgets(client).stream().filter(widget -> widget instanceof CycleButton<?> button
                && button.createDefaultNarrationMessage().getString().contains(label))
                .map(widget -> (CycleButton<?>) widget).findFirst().orElseThrow().onPress(new MouseButtonInfo(0, 0));
    }

    private static void checkSaved() {
        try {
            var file = FabricLoader.getInstance().getConfigDir().resolve("minermarmot/config.json");
            var saved = ConfigManager.parseConfig(Files.readString(file));
            check(saved.displayFps == ConfigManager.current().displayFps
                    && saved.displayPosition == ConfigManager.current().displayPosition
                    && saved.displayObjectives == ConfigManager.current().displayObjectives
                    && saved.objectivesOnRight == ConfigManager.current().objectivesOnRight, "Display preferences must already be persisted");
        } catch (java.io.IOException exception) {
            throw new AssertionError("Cannot inspect saved display preferences", exception);
        }
    }

    private record Line(String text, int x, int y, int color, boolean shadow) {}

    private static final class TextCapture extends GuiGraphicsExtractor {
        final List<Line> lines = new ArrayList<>();
        final List<Line> objectives = new ArrayList<>();

        TextCapture(Minecraft client) { super(client, new GuiRenderState(), 0, 0); }

        @Override
        public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
            if (text.getString().startsWith("Objectifs d'évolution :") || text.getString().startsWith("Miner : ")) {
                objectives.add(new Line(text.getString(), x, y, color, shadow));
            }
        }

        @Override
        public void text(Font font, String text, int x, int y, int color, boolean shadow) {
            if (text.startsWith("FPS : ") || text.startsWith("X: ")) {
                lines.add(new Line(text, x, y, color, shadow));
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package com.minermarmot.client.features.skins;

import com.minermarmot.client.config.ConfigManager;

import com.minermarmot.client.screen.ConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.player.RemotePlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

public final class SkinsClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        int windowWidth = context.computeOnClient(client -> client.getWindow().getWidth());
        int windowHeight = context.computeOnClient(client -> client.getWindow().getHeight());
        boolean staff = FabricLoader.getInstance().isModLoaded("minermarmot-staff");
        var originalModels = context.computeOnClient(SkinsClientTest::models);
        var originalSkins = context.computeOnClient(client -> ConfigManager.preset("minerite").skins);
        try {
            context.runOnClient(client -> {

                try (var input = ConfigManager.class.getResourceAsStream("/assets/minermarmot/presets/minerite.json")) {
                    ConfigManager.preset("minerite").skins = ConfigManager.parsePreset(
                            new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).skins;
                } catch (java.io.IOException exception) {
                    throw new AssertionError("Cannot load the bundled skin test catalog", exception);
                }
                var config = original.copy();
                config.applyPreset("minerite", ConfigManager.preset("minerite"));
                config.skinSelections.clear();
                check(ConfigManager.save(config), "Minerite must save");
            });
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.skins");
            context.waitTick();
            context.runOnClient(client -> {
                check(tiles(client).size() == 1, "Without the server pack, only the original appearance must be shown");
                var fixture = new LinkedHashMap<>(originalModels);
                var valid = originalModels.get(Identifier.parse("minecraft:emerald"));
                var missing = client.getModelManager().getItemModel(Identifier.parse("minermarmot:missing_skin_test"));
                var catalog = ConfigManager.preset("minerite").skins.skins;
                catalog.forEach(skin -> fixture.put(skin.model, valid));
                replaceModels(client, fixture);
                var first = catalog.getFirst();
                check(first.available(client), "A loaded model with valid textures must be available");
                fixture.put(first.model, new CompositeModel(List.of(valid, missing)));
                check(!first.available(client), "A model containing a missing texture must be hidden");
                fixture.put(first.model, valid);
                check(first.available(client), "Availability must recover after resources are replaced");
            });
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.skins");
            context.waitTick();
            context.runOnClient(client -> check(tiles(client).size() == 189, "Loaded skins and the original tile must render before joining a world"));
            context.clickScreenButton("minermarmot.config.quit");
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                context.setScreen(() -> new ConfigScreen(null));
                if (staff) {
                    context.runOnClient(client -> {
                        var screen = client.gui.screen();
                        int logoX = (screen.width - Math.min(720, screen.width - 40)) / 2 + Math.min(720, screen.width - 40) - 8;
                        for (int index = 0; index < 3; index++) screen.mouseClicked(new MouseButtonEvent(logoX, 20, new MouseButtonInfo(0, 0)), false);
                    });
                    context.clickScreenButton("minermarmot.staff.tab");
                    context.clickScreenButton("minermarmot.config.skins");
                } else context.clickScreenButton("minermarmot.config.skins");
                for (int guiScale : new int[]{1, 2}) {
                    context.runOnClient(client -> {
                        client.options.guiScale().set(guiScale);
                        client.resizeGui();
                    });
                    context.waitTick();
                    context.runOnClient(client -> checkLayout(client, staff));
                    context.takeScreenshot("skins-mosaic-scale-" + guiScale);
                }
                context.runOnClient(client -> {
                    client.options.guiScale().set(2);
                    client.getWindow().setWindowed(640, 480);
                });
                context.waitFor(client -> client.gui.screen().width == 320);
                context.waitTick();
                context.runOnClient(client -> checkLayout(client, staff));
                context.takeScreenshot("skins-mosaic-minimum-width");
                context.runOnClient(client -> {
                    client.getWindow().setWindowed(windowWidth, windowHeight);
                    search(client).setValue("plasma");
                    check(tiles(client).size() == 1, "Name search must isolate Plasma Pickaxe");
                });
                context.waitTick();
                clickTile(context, "Plasma Pickaxe");
                context.runOnClient(client -> {
                    check("nexo:skin_plasma_pickaxe".equals(ConfigManager.current().skinSelections.get("minerite")), "Tile clicks must apply immediately");
                    checkSaved();
                    search(client).setValue("nexo:echoing_");
                    check(tiles(client).size() == 1, "Model search must find only Echoing Mace");
                    search(client).setValue("no-skin-matches-this");
                    check(tiles(client).isEmpty(), "Unmatched searches must show no tiles");
                    search(client).setValue("");
                    list(client).setScrollAmount(list(client).maxScrollAmount());
                });
                context.waitTick();
                clickTile(context, "Zebra Pickaxe");
                context.runOnClient(client -> {
                    check("nexo:skin_zebre_pickaxe".equals(ConfigManager.current().skinSelections.get("minerite")), "The final tile must be reachable by scrolling");
                    checkSaved();
                    list(client).setScrollAmount(0);
                    rendererChecks(client);
                });
                context.waitTick();
                clickTile(context, Component.translatable("minermarmot.skins.original").getString());
                context.runOnClient(client -> {
                    check(!ConfigManager.current().skinSelections.containsKey("minerite"), "Original appearance must clear the override");
                    checkSaved();
                    var list = list(client);
                    var row = list.children().getFirst();
                    client.gui.screen().setFocused(list);
                    list.setFocused(row);
                    row.setFocused(row.children().get(1));
                });
                context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
                context.runOnClient(client -> {
                    check("nexo:skin_sakura_pickaxe".equals(ConfigManager.current().skinSelections.get("minerite")), "Keyboard activation must select the focused tile");
                    checkSaved();
                });
                context.setScreen(() -> new ConfigScreen(null));
                context.clickScreenButton("minermarmot.config.resetPreset");
                context.runOnClient(client -> check(!ConfigManager.current().skinSelections.containsKey("minerite"), "General reset must clear the selected skin"));
                context.clickScreenButton("minermarmot.config.quit");
            }
        } finally {
            context.runOnClient(client -> {
                replaceModels(client, originalModels);
                ConfigManager.preset("minerite").skins = originalSkins;

                client.options.guiScale().set(scale);
                client.getWindow().setWindowed(windowWidth, windowHeight);
                client.resizeGui();
                check(ConfigManager.save(original), "Original settings must be restored");
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Identifier, ItemModel> models(Minecraft client) {
        try {
            var field = ModelManager.class.getDeclaredField("bakedItemStackModels");
            field.setAccessible(true);
            return (Map<Identifier, ItemModel>) field.get(client.getModelManager());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void replaceModels(Minecraft client, Map<Identifier, ItemModel> models) {
        try {
            var field = ModelManager.class.getDeclaredField("bakedItemStackModels");
            field.setAccessible(true);
            field.set(client.getModelManager(), models);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void rendererChecks(Minecraft client) {
        var skin = ConfigManager.preset("minerite").skins.skins.getFirst();
        Identifier actualModel = skin.model;
        var stack = new ItemStack(Items.DIAMOND_PICKAXE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§bPIOCHE évolutive"));
        var unchanged = stack.copy();
        var inventory = client.player.getInventory();
        var previousSlot = inventory.getItem(8);
        var previousCarried = client.player.containerMenu.getCarried();
        inventory.setItem(8, stack);
        ConfigManager.current().skinSelections.put("minerite", skin.itemModel);
        try {
            skin.model = Identifier.parse("minecraft:emerald");
            var otherPlayer = new RemotePlayer(client.level, new GameProfile(UUID.randomUUID(), "OtherMiner"));
            check(identity(client, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, otherPlayer)
                    .equals(identity(client, new ItemStack(Items.DIAMOND_PICKAXE), ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, otherPlayer)),
                    "Another player's identically named pickaxe must retain its original model");
            skin.model = Identifier.parse("minermarmot:missing_skin_test");
            for (var display : new ItemDisplayContext[]{ItemDisplayContext.GUI,
                    ItemDisplayContext.FIRST_PERSON_LEFT_HAND, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
                    ItemDisplayContext.THIRD_PERSON_LEFT_HAND, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND}) {
                Object original = identity(client, new ItemStack(Items.DIAMOND_PICKAXE), display);
                check(identity(client, stack, display).equals(original), "Missing pack models must retain the original appearance");
                skin.model = Identifier.parse("minecraft:emerald");
                check(identity(client, stack, display).equals(identity(client, new ItemStack(Items.EMERALD), display)), "The selected model must reach the actual renderer in " + display);
                check(identity(client, stack.copy(), display, otherPlayer).equals(original), "Another player's identical item must retain its model in " + display);
                check(stack.getComponents().equals(unchanged.getComponents()) && stack.getCount() == unchanged.getCount(), "Rendering must not change the real item");
                var ordinary = stack.copy();
                ordinary.set(DataComponents.CUSTOM_NAME, Component.literal("Hammer"));
                check(identity(client, ordinary, display).equals(original), "Other named items must retain their own model");


                skin.model = Identifier.parse("minermarmot:missing_skin_test");
            }
            skin.model = Identifier.parse("minecraft:emerald");
            Object originalGui = identity(client, new ItemStack(Items.DIAMOND_PICKAXE), ItemDisplayContext.GUI);
            check(identity(client, stack.copy(), ItemDisplayContext.GUI).equals(originalGui), "An identical container or preview item must not count as the local inventory item");
            check(identity(client, stack, ItemDisplayContext.GUI, null).equals(originalGui), "Ownerless previews must not receive a skin");
            var dropped = new ItemEntity(client.level, 0, 64, 0, stack.copy());
            check(identity(client, dropped.getItem(), ItemDisplayContext.GROUND, dropped)
                    .equals(identity(client, new ItemStack(Items.DIAMOND_PICKAXE), ItemDisplayContext.GROUND, dropped)), "Dropped items must retain their original model");
            var frame = new ItemFrame(client.level, BlockPos.ZERO, Direction.NORTH);
            check(identity(client, stack.copy(), ItemDisplayContext.FIXED, frame)
                    .equals(identity(client, new ItemStack(Items.DIAMOND_PICKAXE), ItemDisplayContext.FIXED, frame)), "Framed items must retain their original model");
            inventory.setItem(8, ItemStack.EMPTY);
            client.player.containerMenu.setCarried(stack);
            check(identity(client, stack, ItemDisplayContext.GUI).equals(identity(client, new ItemStack(Items.EMERALD), ItemDisplayContext.GUI)), "The local cursor item must retain its skin");
            check(identity(client, stack.copy(), ItemDisplayContext.GUI).equals(originalGui), "An identical container item must not inherit the cursor skin");
            check(identity(client, stack.copy(), ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
                    .equals(identity(client, new ItemStack(Items.EMERALD), ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)), "Local held-item animation copies must retain the selected skin");
        } finally {
            skin.model = actualModel;
            inventory.setItem(8, previousSlot);
            client.player.containerMenu.setCarried(previousCarried);
        }
    }

    private static Object identity(Minecraft client, ItemStack stack, ItemDisplayContext display) {
        return identity(client, stack, display, client.player);
    }

    private static Object identity(Minecraft client, ItemStack stack, ItemDisplayContext display, ItemOwner owner) {
        var state = new TrackingItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(state, stack, display, client.level, owner, 0);
        return state.getModelIdentity();
    }

    private static void clickTile(ClientGameTestContext context, String name) {
        context.runOnClient(client -> {
            var tile = tiles(client).stream().filter(button -> button.getMessage().getString().equals(name)).findFirst().orElseThrow();
            var event = new MouseButtonEvent(tile.getX() + tile.getWidth() / 2.0, tile.getY() + 16, new MouseButtonInfo(0, 0));
            check(client.gui.screen().mouseClicked(event, false), "The complete tile must accept mouse clicks: " + name);
            client.gui.screen().mouseReleased(event);
        });
    }

    private static void checkLayout(Minecraft client, boolean staff) {
        var tabs = client.gui.screen().children().stream().filter(child -> child instanceof Button button && button.getY() == 28)
                .map(child -> (Button) child).toList();
        check(tabs.size() == (staff ? 4 : 3), "Skins must coexist with the other tabs");
        for (int index = 1; index < tabs.size(); index++) check(tabs.get(index - 1).getRight() < tabs.get(index).getX(), "Tabs must not overlap");
        var list = list(client);
        var visible = tiles(client).stream().filter(tile -> tile.getY() >= list.getY() && tile.getBottom() <= list.getBottom()).toList();
        check(!visible.isEmpty(), "The mosaic must contain visible tiles");
        visible.forEach(tile -> check(tile.getX() >= list.getX() && tile.getRight() <= list.getRight()
                && tile.getWidth() >= 100 && tile.getHeight() >= 80, "Large tiles must fit inside the list"));
    }

    private static ContainerObjectSelectionList<?> list(Minecraft client) {
        return (ContainerObjectSelectionList<?>) client.gui.screen().children().stream()
                .filter(child -> child instanceof ContainerObjectSelectionList<?>).findFirst().orElseThrow();
    }

    private static List<AbstractWidget> tiles(Minecraft client) {
        return list(client).children().stream().flatMap(entry -> entry.children().stream())
                .map(child -> (AbstractWidget) child).toList();
    }

    private static EditBox search(Minecraft client) {
        return (EditBox) client.gui.screen().children().stream().filter(child -> child instanceof EditBox).findFirst().orElseThrow();
    }

    private static void checkSaved() {
        try {
            var saved = ConfigManager.parseConfig(Files.readString(FabricLoader.getInstance().getConfigDir().resolve("minermarmot/config.json")));
            check(saved.skinSelections.equals(ConfigManager.current().skinSelections), "Skin choices must already be saved to disk");
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

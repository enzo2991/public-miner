package com.minermarmot.client.features.autocraft;

import com.google.gson.Gson;
import com.minermarmot.client.config.ClientConfig;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.config.MiningPreset;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class AutocraftSelfTest {
    public static void run() throws Exception {
        for (var item : List.of(Items.NETHERITE_SCRAP, Items.GOLD_INGOT, Items.NETHERITE_INGOT, Items.STONE, Items.PAPER)) {
            var holder = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)).orElseThrow();
            if (!holder.areComponentsBound()) holder.bindComponents(net.minecraft.core.component.DataComponentMap.builder()
                    .set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
        }
        Gson gson = new Gson();
        MiningPreset minerite;
        try (var input = AutocraftSelfTest.class.getResourceAsStream("/assets/minermarmot/presets/minerite.json")) {
            minerite = ConfigManager.parsePreset(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        var preset = minerite.autocrafter;
        assert preset.available && preset.defaults.enabled;
        assert new ArrayList<>(preset.categories.keySet()).equals(List.of("NETHER", "END", "HEAVEN"));
        assert preset.recipes().size() == 16 && preset.recipes().stream().noneMatch(recipe -> recipe.enabled);
        assert preset.categories.get("NETHER").size() == 6 && preset.categories.get("END").size() == 6
                && preset.categories.get("HEAVEN").size() == 4;
        assert preset.recipes().stream().map(recipe -> recipe.id).toList().equals(List.of("netherite", "legendaire",
                "ultime", "antique", "mythique", "ancestral", "astrallyth", "excellyth", "primordial", "astral",
                "celeste", "divin", "nebullyth", "spectral", "pulsar", "void"));
        int[] expectedPatterns = {1,1,1,1,1,1,2,1,3,3,3,3,2,4,4,4};
        for (int index = 0; index < expectedPatterns.length; index++) {
            var recipe = preset.recipes().get(index);
            assert recipe.pattern == expectedPatterns[index];
            if (index > 0) {
                assert recipe.output.components.get("minecraft:item_model").getAsString().equals("nexo:" + recipe.id + "_ingot");
                assert recipe.item1.components.get("minecraft:item_model").getAsString().equals("nexo:" + recipe.id + "_fragment");
            }
        }
        int[][] patterns = {{1,1,1,1,2,2,2,2,0}, {1,1,1,1,1,1,1,1,1},
                {1,1,0,2,2,0,0,0,0}, {1,1,1,1,2,1,1,1,1}};
        for (var recipe : preset.recipes()) {
            int[] pattern = patterns[recipe.pattern - 1];
            for (int cell = 0; cell < 9; cell++) assert recipe.ingredient(cell) ==
                    (pattern[cell] == 1 ? recipe.item1 : pattern[cell] == 2 ? recipe.item2 : null);
        }
        var netherite = preset.recipes().getFirst();
        List<ItemStack> inventory = new ArrayList<>(List.of(new ItemStack(Items.NETHERITE_SCRAP, 64),
                new ItemStack(Items.GOLD_INGOT, 40)));
        while (inventory.size() < 36) inventory.add(ItemStack.EMPTY);
        assert netherite.maximumCrafts(inventory) == 10;
        var plan = netherite.plan(inventory, 100);
        assert plan.size() == 9 && plan.getFirst().getCount() == 10 && plan.get(8).isEmpty();
        assert inventory.getFirst().getCount() == 64 : "Planning must not consume inventory";
        assert netherite.plan(inventory, 3).getFirst().getCount() == 3 : "Respect the frozen remaining quota";
        assert netherite.plan(List.of(new ItemStack(Items.NETHERITE_SCRAP, 3), new ItemStack(Items.GOLD_INGOT, 64)), 64).isEmpty();
        assert !preset.recipes().get(1).item1.matches(new ItemStack(Items.PAPER)) : "Plain paper is not a Nexo fragment";
        var fragment = new ItemStack(Items.PAPER);
        fragment.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.parse("nexo:legendaire_fragment"));
        fragment.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Server fragment"));
        assert preset.recipes().get(1).item1.matches(fragment) : "Server names must not prevent model matching";
        fragment.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.parse("nexo:ultime_fragment"));
        assert !preset.recipes().get(1).item1.matches(fragment) : "A different custom model cannot substitute";
        List<ItemStack> full = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) full.add(new ItemStack(Items.STONE, 64));
        full.set(0, new ItemStack(Items.NETHERITE_SCRAP, 4));
        full.set(1, new ItemStack(Items.GOLD_INGOT, 4));
        assert !netherite.plan(full, 64).isEmpty() : "Consumed ingredient stacks may free output space";
        full.set(0, new ItemStack(Items.NETHERITE_SCRAP, 5));
        full.set(1, new ItemStack(Items.GOLD_INGOT, 5));
        assert netherite.plan(full, 64).isEmpty() : "Do not start a craft whose output has nowhere to go";
        assert AutocraftManager.outputCapacity(List.of(new ItemStack(Items.NETHERITE_INGOT, 63)),
                new ItemStack(Items.NETHERITE_INGOT)) == 1;
        assert AutocraftManager.outputCapacity(List.of(new ItemStack(Items.STONE, 64)),
                new ItemStack(Items.NETHERITE_INGOT)) == 0;
        assert AutocraftManager.outputCapacity(List.of(ItemStack.EMPTY), new ItemStack(Items.NETHERITE_INGOT)) == 64;
        var oldJson = gson.toJsonTree(minerite).getAsJsonObject();
        oldJson.remove("autocrafter");
        assert ConfigManager.parsePreset(oldJson.toString(), minerite).autocrafter.recipes().size() == 16;
        oldJson.add("autocrafter", com.google.gson.JsonParser.parseString("{\"available\":false}"));
        assert !ConfigManager.parsePreset(oldJson.toString(), minerite).autocrafter.available;
        assert !ConfigManager.parsePreset("{\"name\":\"Custom\"}").autocrafter.available;
        ClientConfig config = new ClientConfig();
        config.applyPreset("minerite", minerite);
        assert config.autocrafter.enabled && config.autocrafter.recipes.values().stream().noneMatch(Boolean::booleanValue);
        config.autocrafter.recipes.put("netherite", true);
        config.autocrafter.delayMillis = 2345;
        var copy = config.copy();
        copy.autocrafter.recipes.put("netherite", false);
        assert config.autocrafter.recipes.get("netherite");
        config.applyPreset("custom", ConfigManager.parsePreset("{\"name\":\"Custom\"}"));
        config.applyPreset("minerite", minerite);
        assert config.autocrafter.recipes.get("netherite") && config.autocrafter.delayMillis == 2345;
        var restored = ConfigManager.parseConfig(gson.toJson(config));
        restored.validate(minerite);
        assert restored.autocrafter.recipes.get("netherite") && restored.autocrafter.delayMillis == 2345;
        restored.resetPreset(minerite);
        assert restored.autocrafter.enabled && restored.autocrafter.delayMillis == 1000
                && restored.autocrafter.recipes.values().stream().noneMatch(Boolean::booleanValue);
        for (String invalid : List.of("{\"delayMillis\":0}", "{\"delayMillis\":600001}", "{\"recipes\":null}")) {
            try { gson.fromJson(invalid, AutocraftConfig.class).validate(); throw new AssertionError("Accepted " + invalid); }
            catch (IllegalArgumentException expected) { }
        }
        for (String field : List.of("output", "item1", "item2")) {
            var invalid = gson.toJsonTree(minerite).getAsJsonObject();
            invalid.getAsJsonObject("autocrafter").getAsJsonObject("categories").getAsJsonArray("NETHER")
                    .get(0).getAsJsonObject().add(field, com.google.gson.JsonNull.INSTANCE);
            try { ConfigManager.parsePreset(invalid.toString()); throw new AssertionError("Accepted null " + field); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("Autocrafter presets, patterns, models, batch planning and settings checks passed.");
    }
}

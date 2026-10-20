package com.minermarmot.client.features.skins;

import com.google.gson.Gson;
import com.minermarmot.client.config.ClientConfig;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.config.MiningPreset;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;

public final class SkinsSelfTest {
    public static void run() throws Exception {
        Gson gson = new Gson();
        MiningPreset minerite;
        try (var input = SkinsSelfTest.class.getResourceAsStream("/assets/minermarmot/presets/minerite.json")) {
            minerite = ConfigManager.parsePreset(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assert minerite.skins.itemName.equals("Pioche");
        assert minerite.skins.skins.size() == 188;
        assert minerite.skins.skins.subList(0, 15).stream().map(skin -> skin.itemModel + ";" + skin.displayName).toList().equals("""
                nexo:skin_sakura_pickaxe;Sakura Pickaxe
                nexo:blackhole_weapon;Blackhole Weapon
                nexo:echoing_mace;Echoing Mace
                nexo:entropy_hammer;Entropy Hammer
                nexo:viperbone_wand;Viperbone Wand
                nexo:skin_amanite_pickaxe;Amanita Pickaxe
                nexo:skin_blanc_fantome_pickaxe;Ghost White Pickaxe
                nexo:skin_enderman_pickaxe;Enderman Pickaxe
                nexo:skin_forge_pickaxe;Forge Pickaxe
                nexo:skin_marguerite_pickaxe;Daisy Pickaxe
                nexo:skin_mineur_pickaxe;Miner Pickaxe
                nexo:skin_panda_pickaxe;Panda Pickaxe
                nexo:skin_plasma_pickaxe;Plasma Pickaxe
                nexo:skin_seraphin_pickaxe;Seraph Pickaxe
                nexo:skin_terre_pickaxe;Earth Pickaxe
                """.lines().toList());
        assert minerite.skins.skins.getFirst().itemModel.equals("nexo:skin_sakura_pickaxe");
        assert minerite.skins.skins.getLast().displayName.equals("Zebra Pickaxe");
        assert minerite.skins.skins.stream().allMatch(skin -> skin.icon.components.get("minecraft:item_model").getAsString().equals(skin.itemModel));
        var plain = ConfigManager.parsePreset("{\"name\":\"Plain\"}");
        assert plain.skins.skins.isEmpty();
        assert ConfigManager.parsePreset("{\"name\":\"Installed Minerite\"}", minerite).skins.skins.size() == 188;
        assert ConfigManager.parsePreset("{\"name\":\"Explicit\",\"skins\":{}}", minerite).skins.skins.isEmpty();
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§bPIOCHE évolutive"));
        String model = minerite.skins.skins.getFirst().itemModel;
        assert minerite.skins.modelFor(stack, model).equals(Identifier.parse(model));
        assert minerite.skins.modelFor(stack, null) == null;
        assert minerite.skins.modelFor(ItemStack.EMPTY, model) == null;
        assert minerite.skins.modelFor(stack, "nexo:unknown") == null;
        assert plain.skins.modelFor(stack, model) == null;
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Épée"));
        assert minerite.skins.modelFor(stack, model) == null;
        var config = new ClientConfig();
        config.applyPreset("minerite", minerite);
        config.skinSelections.put("minerite", model);
        config.validate(minerite);
        var copy = config.copy();
        copy.skinSelections.clear();
        assert config.skinSelections.get("minerite").equals(model);
        var restored = ConfigManager.parseConfig(gson.toJson(config));
        restored.validate(minerite);
        assert restored.skinSelections.equals(config.skinSelections);
        restored.applyPreset("plain", plain);
        assert restored.skinSelections.get("minerite").equals(model);
        restored.applyPreset("minerite", minerite);
        assert restored.skinSelections.get("minerite").equals(model);
        restored.resetPreset(minerite);
        assert restored.skinSelections.isEmpty();
        assert ConfigManager.parseConfig("{\"preset\":\"minerite\"}").skinSelections.isEmpty();
        for (String json : new String[]{
                "{\"name\":\"Bad\",\"skins\":null}",
                "{\"name\":\"Bad\",\"skins\":{\"skins\":[{\"itemModel\":\"nexo:test\",\"displayName\":\"Test\"}]}}",
                "{\"name\":\"Bad\",\"skins\":{\"itemName\":\"Pioche\",\"skins\":[{\"itemModel\":\"bad model\",\"displayName\":\"Test\"}]}}",
                "{\"name\":\"Bad\",\"skins\":{\"itemName\":\"Pioche\",\"skins\":[{\"itemModel\":\"nexo:test\",\"displayName\":\"A\"},{\"itemModel\":\"nexo:test\",\"displayName\":\"B\"}]}}"}) {
            try {
                ConfigManager.parsePreset(json);
                throw new AssertionError("Invalid skins must be rejected");
            } catch (IllegalArgumentException expected) {}
        }
        config.skinSelections.put("minerite", null);
        try {
            config.validate(minerite);
            throw new AssertionError("Null selections must be rejected");
        } catch (IllegalArgumentException expected) {}
    }
}

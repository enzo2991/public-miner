package com.minermarmot.client.features.tnt;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.item.BlockItem;

import java.util.ArrayList;
import java.util.List;

public final class TntPreset {
    public boolean builder;
    public boolean carpet;
    public String builderWandName = "";
    public String shulkerItem = "minecraft:red_shulker_box";
    public List<String> shopClicks = new ArrayList<>();

    public boolean allows(TntMode mode) {
        return mode == TntMode.BUILDER ? builder : mode == TntMode.CARPET && carpet;
    }

    public List<TntMode> modes() {
        return java.util.Arrays.stream(TntMode.values()).filter(this::allows).toList();
    }

    public void validate() {
        if (builderWandName == null || builderWandName.length() > 128
                || builder && builderWandName.isBlank() || shopClicks == null || shopClicks.size() > 16) {
            throw new IllegalArgumentException("Invalid TNT preset");
        }
        shulkerItem = itemId(shulkerItem);
        var box = BuiltInRegistries.ITEM.getValue(Identifier.parse(shulkerItem));
        if (!(box instanceof BlockItem block) || !(block.getBlock() instanceof ShulkerBoxBlock)) {
            throw new IllegalArgumentException("TNT shulkerItem must identify a shulker box");
        }
        shopClicks.replaceAll(TntPreset::itemId);
    }

    private static String itemId(String value) {
        Identifier id = value == null ? null : Identifier.tryParse(value);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.ITEM.getValue(id) == Items.AIR) {
            throw new IllegalArgumentException("Invalid TNT item identifier: " + value);
        }
        return id.toString();
    }
}

package com.minermarmot.client.features.alerts;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BlockAlertTarget {
    public String id;
    public String block;
    public Map<String, String> properties = new LinkedHashMap<>();
    public String displayName;
    public AlertIcon item1;
    public List<AlertIcon> item2 = new ArrayList<>();
    public BlockAlertSettings defaults = new BlockAlertSettings();
    public transient String category;
    public transient int rarity;
    private transient Block minecraftBlock;
    private transient boolean vanillaOre;
    private transient Map<Property<?>, Comparable<?>> required = new LinkedHashMap<>();

    public void validate() {
        Identifier blockId = block == null ? null : Identifier.tryParse(block);
        if (id == null || !id.matches("[a-zA-Z0-9_.:/-]{1,128}") || blockId == null
                || !BuiltInRegistries.BLOCK.containsKey(blockId) || properties == null
                || displayName == null || displayName.isBlank() || displayName.length() > 256
                || defaults == null || item1 == null || item2 == null || item2.size() > 64 || item2.contains(null)) {
            throw new IllegalArgumentException("Invalid block alert target: " + id);
        }
        minecraftBlock = BuiltInRegistries.BLOCK.getValue(blockId);
        vanillaOre = blockId.getNamespace().equals("minecraft")
                && (blockId.getPath().endsWith("_ore") || minecraftBlock == Blocks.ANCIENT_DEBRIS);
        if (minecraftBlock == Blocks.AIR) {
            throw new IllegalArgumentException("Air cannot be monitored");
        }
        required = new LinkedHashMap<>();
        for (var entry : properties.entrySet()) {
            Property<?> property = minecraftBlock.getStateDefinition().getProperty(entry.getKey());
            if (property == null || entry.getValue() == null) {
                throw new IllegalArgumentException("Unknown block property for " + id + ": " + entry.getKey());
            }
            required.put(property, property.getValue(entry.getValue()).orElseThrow(
                    () -> new IllegalArgumentException("Invalid block property value for " + id)));
        }
        if (minecraftBlock == Blocks.NOTE_BLOCK && !properties.isEmpty()
                && (!properties.containsKey("instrument") || !properties.containsKey("note"))) {
            throw new IllegalArgumentException("Note targets require both instrument and note");
        }
        defaults.validate();
        item1.validate();
        item2.forEach(AlertIcon::validate);
    }

    public boolean matches(BlockState state) {
        return state.is(minecraftBlock) && required.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(state.getValue(entry.getKey())));
    }

    Block blockType() {
        return minecraftBlock;
    }

    public boolean isVanillaOre() {
        return vanillaOre;
    }

    public boolean overlaps(BlockAlertTarget other) {
        return minecraftBlock == other.minecraftBlock && required.entrySet().stream()
                .allMatch(entry -> !other.required.containsKey(entry.getKey())
                        || other.required.get(entry.getKey()).equals(entry.getValue()));
    }
}

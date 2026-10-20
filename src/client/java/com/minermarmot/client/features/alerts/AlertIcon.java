package com.minermarmot.client.features.alerts;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;

public final class AlertIcon {
    public String item;
    public JsonObject components = new JsonObject();
    private transient ItemStack stack;
    private transient Item minecraftItem;
    private transient DataComponentPatch patch;

    public void validate() {
        Identifier id = item == null ? null : Identifier.tryParse(item);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || components == null
                || components.keySet().stream().anyMatch(key -> !key.equals("minecraft:item_model")
                && !key.equals("minecraft:custom_model_data"))) {
            throw new IllegalArgumentException("Invalid alert icon: " + item);
        }
        minecraftItem = BuiltInRegistries.ITEM.getValue(id);
        if (minecraftItem == Items.AIR) {
            throw new IllegalArgumentException("Alert icons cannot be air");
        }
        patch = DataComponentPatch.CODEC.parse(JsonOps.INSTANCE, components).getOrThrow(IllegalArgumentException::new);
        stack = null;
    }

    public ItemStack stack() {
        // The settings menu can render before item components are loaded by a world.
        if (!BuiltInRegistries.ITEM.get(Identifier.parse(item)).orElseThrow().areComponentsBound()) {
            return ItemStack.EMPTY;
        }
        if (stack == null) {
            stack = new ItemStack(minecraftItem);
            stack.applyComponentsAndValidate(patch);
        }
        return stack;
    }

    public Component name() {
        ItemStack icon = stack();
        return icon.isEmpty() ? Component.literal(item) : icon.getHoverName();
    }

    public boolean matches(ItemStack candidate) {
        return !candidate.isEmpty() && candidate.is(minecraftItem) && patch.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(java.util.Optional.ofNullable(candidate.get(entry.getKey()))));
    }
}

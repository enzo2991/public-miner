package com.minermarmot.client.features.skins;

import com.minermarmot.client.features.alerts.AlertIcon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SkinsPreset {
    public String itemName = "";
    public List<Skin> skins = new ArrayList<>();
    private transient Map<String, Skin> byModel = new LinkedHashMap<>();
    private transient String normalizedName = "";

    public void validate() {
        if (itemName == null || itemName.length() > 128 || skins == null || skins.size() > 4096
                || !skins.isEmpty() && itemName.isBlank()) {
            throw new IllegalArgumentException("Invalid skins preset");
        }
        normalizedName = itemName.toLowerCase(Locale.ROOT);
        byModel = new LinkedHashMap<>();
        for (Skin skin : skins) {
            if (skin == null || skin.itemModel == null || Identifier.tryParse(skin.itemModel) == null
                    || skin.displayName == null || skin.displayName.isBlank() || skin.displayName.length() > 128
                    || byModel.putIfAbsent(skin.itemModel, skin) != null) {
                throw new IllegalArgumentException("Invalid or duplicate skin");
            }
            skin.model = Identifier.parse(skin.itemModel);
            skin.icon = new AlertIcon();
            skin.icon.item = "minecraft:paper";
            skin.icon.components.addProperty("minecraft:item_model", skin.itemModel);
            skin.icon.validate();
        }
    }

    public Identifier modelFor(ItemStack stack, String selected) {
        Skin skin = byModel.get(selected);
        if (skin == null || stack.isEmpty()) return null;
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        return name != null && name.toLowerCase(Locale.ROOT).contains(normalizedName) ? skin.model : null;
    }

    public static final class Skin {
        public String itemModel;
        public String displayName;
        public transient Identifier model;
        public transient AlertIcon icon;

        public boolean available(Minecraft client) {
            var itemModel = client.getModelManager().getItemModel(model);
            if (itemModel instanceof MissingItemModel) return false;
            var layers = new ArrayList<ItemStackRenderState.LayerRenderState>();
            var state = new ItemStackRenderState() {
                @Override
                public LayerRenderState newLayer() {
                    var layer = super.newLayer();
                    layers.add(layer);
                    return layer;
                }
            };
            // Before joining a world, item components are unbound; inspect the model directly.
            itemModel.update(state, icon.stack(), client.getItemModelResolver(), ItemDisplayContext.GUI, client.level, null, 0);
            return !state.isEmpty() && layers.stream().flatMap(layer -> layer.prepareQuadList().stream())
                    .noneMatch(quad -> quad.materialInfo().sprite().contents().name().equals(MissingTextureAtlasSprite.getLocation()));
        }
    }
}

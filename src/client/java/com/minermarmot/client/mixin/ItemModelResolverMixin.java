package com.minermarmot.client.mixin;

import com.minermarmot.client.integration.VersionCheck;

import com.minermarmot.client.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Fabric model-loading callbacks cannot select a model per stack at render time.
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {
    @Shadow @Final private ModelManager modelManager;

    @ModifyVariable(method = "appendItemLayers", at = @At("STORE"), ordinal = 0)
    private Identifier minermarmot$skinModel(Identifier original, ItemStackRenderState output, ItemStack stack,
                                          ItemDisplayContext context, Level level, ItemOwner owner, int seed) {
        var config = ConfigManager.current();
        if (!VersionCheck.isCompatible() || config == null) return original;
        var preset = ConfigManager.preset(config.preset);
        if (preset == null) return original;
        Identifier selected = preset.skins.modelFor(stack, config.skinSelections.get(config.preset));
        return selected == null || !minermarmot$isOwnItem(stack, context, owner)
                || modelManager.getItemModel(selected) instanceof MissingItemModel ? original : selected;
    }

    @Unique
    private static boolean minermarmot$isOwnItem(ItemStack stack, ItemDisplayContext context, ItemOwner owner) {
        var player = Minecraft.getInstance().player;
        if (player == null || owner != player) return false;
        if (context != ItemDisplayContext.GUI) {
            return switch (context) {
                case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> true;
                default -> false;
            };
        }
        if (player.containerMenu.getCarried() == stack) return true;
        var inventory = player.getInventory();
        // Identical components do not establish ownership of another inventory's item.
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot) == stack) return true;
        }
        return false;
    }
}

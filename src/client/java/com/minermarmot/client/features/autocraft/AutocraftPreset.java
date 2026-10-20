package com.minermarmot.client.features.autocraft;

import com.minermarmot.client.features.alerts.AlertIcon;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AutocraftPreset {
    private static final int[][] PATTERNS = {
            {1, 1, 1, 1, 2, 2, 2, 2, 0},
            {1, 1, 1, 1, 1, 1, 1, 1, 1},
            {1, 1, 0, 2, 2, 0, 0, 0, 0},
            {1, 1, 1, 1, 2, 1, 1, 1, 1}
    };
    public boolean available;
    public AutocraftConfig defaults = new AutocraftConfig();
    public Map<String, List<Recipe>> categories = new LinkedHashMap<>();

    public void validate() {
        if (defaults == null || categories == null || categories.size() > 32) {
            throw new IllegalArgumentException("Invalid autocrafter preset");
        }
        defaults.validate();
        HashSet<String> ids = new HashSet<>();
        for (var entry : categories.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 128 || entry.getValue() == null) {
                throw new IllegalArgumentException("Invalid autocrafter category");
            }
            for (Recipe recipe : entry.getValue()) {
                if (recipe == null) throw new IllegalArgumentException("Missing autocrafter recipe");
                recipe.validate();
                if (!ids.add(recipe.id) || ids.size() > 256) throw new IllegalArgumentException("Duplicate or excessive autocrafter recipes");
            }
        }
    }

    public List<Recipe> recipes() { return categories.values().stream().flatMap(List::stream).toList(); }

    public void initialize(AutocraftConfig config) {
        var recipes = recipes();
        config.recipes.keySet().removeIf(id -> recipes.stream().noneMatch(recipe -> recipe.id.equals(id)));
        recipes.forEach(recipe -> config.recipes.putIfAbsent(recipe.id, defaults.recipes.getOrDefault(recipe.id, recipe.enabled)));
    }

    public AutocraftConfig newConfig() {
        AutocraftConfig config = defaults.copy();
        initialize(config);
        return config;
    }

    public static final class Recipe {
        public String id;
        public String displayName;
        public AlertIcon output;
        public AlertIcon item1;
        public AlertIcon item2;
        public int pattern;
        public boolean enabled;

        private void validate() {
            if (id == null || !id.matches("[a-z0-9_.-]{1,64}") || displayName == null || displayName.isBlank()
                    || displayName.length() > 128 || output == null || item1 == null || pattern < 1 || pattern > 4
                    || pattern != 2 && item2 == null) {
                throw new IllegalArgumentException("Invalid autocrafter recipe: " + id);
            }
            output.validate();
            item1.validate();
            if (item2 != null) item2.validate();
        }

        public AlertIcon ingredient(int cell) {
            return switch (PATTERNS[pattern - 1][cell]) { case 1 -> item1; case 2 -> item2; default -> null; };
        }

        public int maximumCrafts(List<ItemStack> inventory) {
            int maximum = Integer.MAX_VALUE;
            for (int kind = 1; kind <= 2; kind++) {
                int needed = 0;
                for (int value : PATTERNS[pattern - 1]) if (value == kind) needed++;
                if (needed == 0) continue;
                AlertIcon ingredient = kind == 1 ? item1 : item2;
                int count = inventory.stream().filter(ingredient::matches).mapToInt(ItemStack::getCount).sum();
                maximum = Math.min(maximum, count / needed);
            }
            return maximum;
        }

        public List<ItemStack> plan(List<ItemStack> inventory, int limit) {
            for (int batch = Math.min(64, Math.min(limit, maximumCrafts(inventory))); batch > 0; batch--) {
                int[] remaining = inventory.stream().mapToInt(ItemStack::getCount).toArray();
                List<ItemStack> grid = new ArrayList<>();
                for (int cell = 0; cell < 9; cell++) {
                    AlertIcon ingredient = ingredient(cell);
                    if (ingredient == null) { grid.add(ItemStack.EMPTY); continue; }
                    ItemStack selected = ItemStack.EMPTY;
                    for (ItemStack candidate : inventory) {
                        if (!ingredient.matches(candidate) || candidate.getMaxStackSize() < batch) continue;
                        int count = 0;
                        for (int slot = 0; slot < inventory.size(); slot++) {
                            if (ItemStack.isSameItemSameComponents(candidate, inventory.get(slot))) count += remaining[slot];
                        }
                        if (count >= batch) { selected = candidate; break; }
                    }
                    if (selected.isEmpty()) break;
                    int needed = batch;
                    for (int slot = 0; slot < inventory.size() && needed > 0; slot++) {
                        if (!ItemStack.isSameItemSameComponents(selected, inventory.get(slot))) continue;
                        int used = Math.min(remaining[slot], needed);
                        remaining[slot] -= used;
                        needed -= used;
                    }
                    grid.add(selected.copyWithCount(batch));
                }
                if (grid.size() == 9) {
                    ItemStack result = output.stack();
                    int capacity = 0;
                    for (int slot = 0; slot < inventory.size(); slot++) {
                        ItemStack stack = inventory.get(slot);
                        if (remaining[slot] == 0) capacity += result.getMaxStackSize();
                        else if (ItemStack.isSameItemSameComponents(stack, result)) capacity += Math.max(0, stack.getMaxStackSize() - remaining[slot]);
                    }
                    if (capacity >= batch) return grid;
                }
            }
            return List.of();
        }
    }
}

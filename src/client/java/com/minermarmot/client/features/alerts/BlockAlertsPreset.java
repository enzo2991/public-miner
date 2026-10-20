package com.minermarmot.client.features.alerts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;

public final class BlockAlertsPreset {
    public boolean available = true;
    public boolean sounds = true;
    public boolean waypoints = true;
    public boolean glow = true;
    public BlockAlertsConfig defaults = new BlockAlertsConfig();
    public Map<String, Category> categories = new LinkedHashMap<>();
    private transient List<BlockAlertTarget> targets = List.of();
    private transient Map<Block, List<BlockAlertTarget>> byBlock = Map.of();
    private transient Map<BlockState, BlockAlertTarget> stateMatches = new IdentityHashMap<>();

    public static final class Category {
        public List<BlockAlertTarget> blocks = new ArrayList<>();
    }

    public void validate() {
        if (defaults == null || categories == null || categories.size() > 128 || categories.containsKey(null)
                || categories.containsValue(null)) {
            throw new IllegalArgumentException("Invalid block alert preset");
        }
        defaults.validate();
        ArrayList<BlockAlertTarget> flattened = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        Map<Block, List<BlockAlertTarget>> indexed = new LinkedHashMap<>();
        for (var entry : categories.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 128 || entry.getValue().blocks == null) {
                throw new IllegalArgumentException("Invalid block alert category");
            }
            List<BlockAlertTarget> blocks = entry.getValue().blocks;
            for (int rank = 0; rank < blocks.size(); rank++) {
                BlockAlertTarget target = blocks.get(rank);
                if (target == null || !ids.add(target.id) || flattened.size() >= 4096) {
                    throw new IllegalArgumentException("Duplicate or invalid block alert target");
                }
                target.validate();
                target.category = entry.getKey();
                target.rarity = rank;
                if (flattened.stream().anyMatch(target::overlaps)) {
                    throw new IllegalArgumentException("Overlapping block alert criteria: " + target.id);
                }
                flattened.add(target);
                indexed.computeIfAbsent(target.blockType(), block -> new ArrayList<>()).add(target);
            }
        }
        targets = List.copyOf(flattened);
        byBlock = indexed;
        stateMatches.clear();
    }

    public List<BlockAlertTarget> targets() {
        return targets;
    }

    public BlockAlertTarget match(BlockState state) {
        if (stateMatches.containsKey(state)) return stateMatches.get(state);
        List<BlockAlertTarget> candidates = byBlock.get(state.getBlock());
        if (candidates == null) return null;
        // Block states are immutable; retain both matches and misses until preset validation.
        for (BlockAlertTarget target : candidates) {
            if (target.matches(state)) {
                stateMatches.put(state, target);
                return target;
            }
        }
        stateMatches.put(state, null);
        return null;
    }

    public void initialize(BlockAlertsConfig config) {
        for (BlockAlertTarget target : targets) {
            config.targets.computeIfAbsent(target.id, id -> target.defaults.copy());
        }
        config.targets.keySet().removeIf(id -> targets.stream().noneMatch(target -> target.id.equals(id)));
        config.sounds &= sounds && available;
        config.waypoints &= waypoints && available;
        config.glow &= glow && available;
    }

    public BlockAlertsConfig newConfig() {
        BlockAlertsConfig config = defaults.copy();
        initialize(config);
        return config;
    }
}

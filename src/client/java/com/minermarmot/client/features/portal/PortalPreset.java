package com.minermarmot.client.features.portal;

import com.minermarmot.client.features.alerts.AlertIcon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

public final class PortalPreset {
    public PortalConfig defaults = new PortalConfig();
    public List<Type> types = new ArrayList<>();

    public void validate() {
        if (defaults == null || types == null || types.size() > 32) {
            throw new IllegalArgumentException("Invalid portal preset");
        }
        defaults.validate();
        HashSet<String> ids = new HashSet<>();
        HashSet<String> patterns = new HashSet<>();
        for (Type type : types) {
            if (type == null) throw new IllegalArgumentException("Missing portal type");
            type.validate();
            if (!ids.add(type.id) || !patterns.add(type.block)) {
                throw new IllegalArgumentException("Duplicate portal identifier or pattern");
            }
        }
    }

    public Type match(BlockState state) {
        for (Type type : types) if (type.matches(state)) return type;
        return null;
    }

    public Type type(String id) {
        return types.stream().filter(type -> type.id.equals(id)).findFirst().orElse(null);
    }

    public void initialize(PortalConfig config) {
        config.monitors.keySet().removeIf(id -> type(id) == null);
        types.forEach(type -> config.monitors.putIfAbsent(type.id, defaults.monitors.getOrDefault(type.id, type.monitor)));
    }

    public PortalConfig newConfig() {
        PortalConfig config = defaults.copy();
        initialize(config);
        return config;
    }

    public static final class Type {
        public String id;
        public String displayName;
        public String block;
        public String shape = "pair";
        public int size = 2;
        public double markerOffsetY;
        public boolean monitor;
        public AlertIcon icon;
        private transient Block minecraftBlock;

        private void validate() {
            Identifier identifier = block == null ? null : Identifier.tryParse(block);
            if (id == null || !id.matches("[a-z0-9_.-]{1,64}") || displayName == null || displayName.isBlank()
                    || displayName.length() > 128 || identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier)
                    || !("pair".equals(shape) && size == 2 || "square".equals(shape) && size >= 2 && size <= 8)
                    || !Double.isFinite(markerOffsetY) || Math.abs(markerOffsetY) > 64 || icon == null) {
                throw new IllegalArgumentException("Invalid portal type: " + id);
            }
            minecraftBlock = BuiltInRegistries.BLOCK.getValue(identifier);
            if (minecraftBlock == Blocks.AIR) throw new IllegalArgumentException("Portal blocks cannot be air");
            icon.validate();
        }

        public boolean matches(BlockState state) { return state.is(minecraftBlock); }

        public Pattern find(BlockGetter world, BlockPos pos, Predicate<BlockPos> loaded) {
            if (!loaded.test(pos) || !matches(world.getBlockState(pos))) return null;
            if (shape.equals("pair")) {
                BlockPos partner = onlyNeighbor(world, pos, loaded);
                if (partner == null || !pos.equals(onlyNeighbor(world, partner, loaded))) return null;
                BlockPos first = pos.getX() < partner.getX() || pos.getX() == partner.getX() && pos.getZ() < partner.getZ() ? pos : partner;
                BlockPos second = first.equals(pos) ? partner : pos;
                return new Pattern(first.immutable(), Vec3.atCenterOf(first).add(
                        (second.getX() - first.getX()) * 0.5, markerOffsetY, (second.getZ() - first.getZ()) * 0.5),
                        List.of(first.immutable(), second.immutable()));
            }
            BlockPos corner = pos.immutable();
            for (int i = 1; i < size && loaded.test(corner.west()) && matches(world.getBlockState(corner.west())); i++) corner = corner.west();
            for (int i = 1; i < size && loaded.test(corner.north()) && matches(world.getBlockState(corner.north())); i++) corner = corner.north();
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = -1; x <= size; x++) {
                for (int z = -1; z <= size; z++) {
                    boolean inside = x >= 0 && x < size && z >= 0 && z < size;
                    if (!inside && (x == -1 || x == size) && (z == -1 || z == size)) continue;
                    BlockPos cell = corner.offset(x, 0, z);
                    if (!loaded.test(cell) || matches(world.getBlockState(cell)) != inside) return null;
                    if (inside) blocks.add(cell);
                }
            }
            BlockPos anchor = corner.offset(size / 2, 0, size / 2);
            return new Pattern(anchor, Vec3.atLowerCornerOf(corner).add(size * 0.5, 0.5 + markerOffsetY, size * 0.5), List.copyOf(blocks));
        }

        private BlockPos onlyNeighbor(BlockGetter world, BlockPos pos, Predicate<BlockPos> loaded) {
            BlockPos found = null;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(direction);
                if (!loaded.test(next)) return null;
                if (matches(world.getBlockState(next))) {
                    if (found != null) return null;
                    found = next;
                }
            }
            return found;
        }
    }

    public record Pattern(BlockPos anchor, Vec3 marker, List<BlockPos> blocks) {}
}

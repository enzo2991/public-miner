package com.minermarmot.client.features.portal;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.alerts.BlockAlertManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PortalManager {
    private static final PortalManager INSTANCE = new PortalManager();
    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final Map<Long, Map<BlockPos, PortalPreset.Type>> candidates = new HashMap<>();
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final HashSet<Long> queued = new HashSet<>();
    private final Map<Key, Waypoint> discovered = new HashMap<>();
    private ClientLevel level;
    private String preset;
    private long visibilityTick = Long.MIN_VALUE;

    // shortcut: worlds sharing one dimension share anchors, add a server world identifier if one becomes available.
    private record Key(String preset, Identifier dimension, String type, BlockPos anchor) {}
    public record Waypoint(PortalPreset.Type type, PortalPreset.Pattern pattern, Identifier dimension) {}

    private PortalManager() {}

    public static void initialize() {
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            INSTANCE.setLevel(world);
            long key = chunk.getPos().pack();
            INSTANCE.chunks.put(key, chunk);
            INSTANCE.queue(key);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (INSTANCE.level == world) {
                long key = chunk.getPos().pack();
                INSTANCE.chunks.remove(key);
                INSTANCE.candidates.remove(key);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            reset();
            INSTANCE.setLevel(null);
        });
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
        LevelExtractionEvents.END_EXTRACTION.register(context -> INSTANCE.discover(Minecraft.getInstance()));
    }

    private void setLevel(ClientLevel world) {
        if (level == world) return;
        level = world;
        chunks.clear();
        candidates.clear();
        pending.clear();
        queued.clear();
        visibilityTick = Long.MIN_VALUE;
    }

    private void queue(long key) { if (queued.add(key)) pending.add(key); }

    private static PortalPreset portalPreset() { return ConfigManager.preset(ConfigManager.current().preset).portals; }

    private void tick(Minecraft client) {
        setLevel(client.level);
        if (!Objects.equals(preset, ConfigManager.current().preset)) {
            preset = ConfigManager.current().preset;
            candidates.clear();
            chunks.keySet().forEach(this::queue);
            visibilityTick = Long.MIN_VALUE;
        }
        if (level == null || client.player == null) return;
        for (int budget = 2; budget > 0 && !pending.isEmpty(); budget--) {
            long key = pending.remove();
            queued.remove(key);
            LevelChunk chunk = chunks.get(key);
            if (chunk != null) scan(chunk);
        }
    }

    private void scan(LevelChunk chunk) {
        Map<BlockPos, PortalPreset.Type> found = new HashMap<>();
        PortalPreset definitions = portalPreset();
        var sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int index = 0; index < sections.length; index++) {
            var section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> definitions.match(state) != null)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(index) * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        var type = definitions.match(section.getBlockState(x, y, z));
                        if (type == null) continue;
                        pos.set(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                        found.put(pos.immutable(), type);
                    }
                }
            }
        }
        candidates.put(chunk.getPos().pack(), found);
        visibilityTick = Long.MIN_VALUE;
    }

    public static void blockChanged(BlockPos pos, BlockState state) {
        Minecraft client = Minecraft.getInstance();
        INSTANCE.setLevel(client.level);
        if (client.level == null || !loaded(client.level, pos)) return;
        var found = INSTANCE.candidates.computeIfAbsent(ChunkPos.pack(pos), key -> new HashMap<>());
        var type = portalPreset().match(state);
        if (type == null) found.remove(pos);
        else found.put(pos.immutable(), type);
        INSTANCE.visibilityTick = Long.MIN_VALUE;
    }

    private void discover(Minecraft client) {
        if (client.level == null || client.player == null || client.level != level
                || !Objects.equals(preset, ConfigManager.current().preset)) return;
        long now = level.getGameTime();
        if (visibilityTick != Long.MIN_VALUE && now - visibilityTick < 5) return;
        visibilityTick = now;
        HashSet<BlockPos> inspected = new HashSet<>();
        for (var chunk : candidates.values()) {
            for (var entry : chunk.entrySet()) {
                var type = entry.getValue();
                if (!Boolean.TRUE.equals(ConfigManager.current().portals.monitors.get(type.id))) continue;
                var pattern = type.find(level, entry.getKey(), pos -> loaded(level, pos));
                if (pattern == null || !inspected.add(pattern.anchor())) continue;
                Identifier dimension = level.dimension().identifier();
                Key key = new Key(ConfigManager.current().preset, dimension, type.id, pattern.anchor());
                if (!discovered.containsKey(key) && canDiscover(client, pattern)) {
                    discovered.put(key, new Waypoint(type, pattern, dimension));
                }
            }
        }
    }

    private static boolean loaded(ClientLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static boolean canDiscover(Minecraft client, PortalPreset.Pattern pattern) {
        return pattern.blocks().stream().anyMatch(pos -> BlockAlertManager.onScreen(client, pos)
                && hasVisibleFace(client, pos));
    }

    private static boolean hasVisibleFace(Minecraft client, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (Direction direction : Direction.values()) {
            Vec3 face = center.add(direction.getStepX() * 0.499, direction.getStepY() * 0.499, direction.getStepZ() * 0.499);
            // End portals have no collision shape; their outline still exposes the rendered surface.
            var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), face,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return true;
        }
        return false;
    }

    public static List<Waypoint> waypoints(Vec3 player) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return List.of();
        var config = ConfigManager.current();
        return INSTANCE.discovered.entrySet().stream()
                .filter(entry -> entry.getKey().preset.equals(config.preset)
                        && entry.getKey().dimension.equals(client.level.dimension().identifier())
                        && Boolean.TRUE.equals(config.portals.monitors.get(entry.getKey().type)))
                .map(Map.Entry::getValue)
                .filter(wp -> wp.pattern.marker().distanceToSqr(player) <= (double) config.portals.waypointDisplayDistance * config.portals.waypointDisplayDistance)
                .sorted(Comparator.<Waypoint>comparingDouble(wp -> wp.pattern.marker().distanceToSqr(player))
                        .thenComparing(wp -> wp.type.id).thenComparing(wp -> wp.dimension.toString())
                        .thenComparingLong(wp -> wp.pattern.anchor().asLong()))
                .limit(config.portals.displayedWaypointCount).toList();
    }

    public static void reset() {
        INSTANCE.discovered.clear();
        INSTANCE.visibilityTick = Long.MIN_VALUE;
    }
}

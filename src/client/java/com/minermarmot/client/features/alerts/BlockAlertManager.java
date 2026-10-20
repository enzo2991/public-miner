package com.minermarmot.client.features.alerts;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.mining.MiningFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;

public final class BlockAlertManager {
    static final int MAX_WAYPOINTS = 100;
    private static final BlockAlertManager INSTANCE = new BlockAlertManager();
    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final Map<Long, Map<BlockPos, BlockAlertTarget>> candidates = new HashMap<>();
    private final Map<BlockPos, BlockAlertTarget> discovered = new HashMap<>();
    private final Map<BlockPos, BlockAlertTarget> seen = new HashMap<>();
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final HashSet<Long> queued = new HashSet<>();
    private Set<String> monitored = Set.of();
    private ClientLevel level;
    private String preset;
    private List<Waypoint> visible = List.of();
    private long visibilityTick = Long.MIN_VALUE;
    private int soundCooldown;
    private BlockAlertTarget pendingSound;
    private BlockPos scanCenter;
    private int vanillaScanRadius;
    private int otherScanRadius;

    public record Waypoint(BlockPos pos, BlockAlertTarget target) {}

    private BlockAlertManager() {}

    public static void initialize() {
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            INSTANCE.setLevel(world);
            long key = chunk.getPos().pack();
            INSTANCE.chunks.put(key, chunk);
            INSTANCE.queue(key);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (world == INSTANCE.level) {
                long key = chunk.getPos().pack();
                INSTANCE.chunks.remove(key);
                INSTANCE.candidates.remove(key);
                INSTANCE.seen.keySet().removeIf(pos -> chunkKey(pos) == key);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.setLevel(null));
        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> blockChanged(pos, world.getBlockState(pos)));
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
        BlockAlertRenderer.initialize();
    }

    private void setLevel(ClientLevel world) {
        if (world != level) {
            level = world;
            chunks.clear();
            candidates.clear();
            pending.clear();
            queued.clear();
            monitored = Set.of();
            scanCenter = null;
            resetDiscoveries();
        }
    }

    public static void worldChanged() {
        INSTANCE.setLevel(null);
    }

    private void resetDiscoveries() {
        discovered.clear();
        seen.clear();
        visible = List.of();
        visibilityTick = Long.MIN_VALUE;
        pendingSound = null;
        soundCooldown = 0;
    }

    private void queue(long key) {
        if (!monitored.isEmpty() && queued.add(key)) {
            pending.add(key);
        }
    }

    private void tick(Minecraft client) {
        setLevel(client.level);
        if (level == null || client.player == null) {
            return;
        }
        if (!Objects.equals(preset, ConfigManager.current().preset)) {
            preset = ConfigManager.current().preset;
            candidates.clear();
            resetDiscoveries();
            chunks.keySet().forEach(this::queue);
        }
        synchronizeTargets();
        synchronizeScanArea(client);
        for (int budget = 2; budget > 0 && !pending.isEmpty(); budget--) {
            long key = pending.remove();
            queued.remove(key);
            LevelChunk chunk = chunks.get(key);
            if (chunk != null) {
                scanChunk(chunk);
            }
        }
        if (soundCooldown > 0) {
            soundCooldown--;
        }
        if (pendingSound != null && soundCooldown == 0) {
            var settings = ConfigManager.current().alerts;
            var targetSettings = settings.targets.get(pendingSound.id);
            if (settings.sounds && alertPreset().sounds && alertPreset().available
                    && targetSettings != null && targetSettings.enabled && !targetSettings.sound.isEmpty()) {
                playSound(targetSettings.sound, settings.soundVolume);
                soundCooldown = settings.soundDelayTicks;
            }
        }
        pendingSound = null;
    }

    private static BlockAlertsPreset alertPreset() {
        return ConfigManager.preset(ConfigManager.current().preset).blockAlerts;
    }

    private void synchronizeTargets() {
        Set<String> enabled = new HashSet<>();
        if (alertPreset().available) {
            ConfigManager.current().alerts.targets.forEach((id, settings) -> {
                if (settings.enabled) enabled.add(id);
            });
        }
        if (enabled.equals(monitored)) return;
        boolean added = !monitored.containsAll(enabled);
        monitored = enabled;
        candidates.values().forEach(chunk -> chunk.values().removeIf(target -> !enabled.contains(target.id)));
        candidates.values().removeIf(Map::isEmpty);
        discovered.values().removeIf(target -> !enabled.contains(target.id));
        seen.values().removeIf(target -> !enabled.contains(target.id));
        visible = visible.stream().filter(wp -> enabled.contains(wp.target().id)).toList();
        if (pendingSound != null && !enabled.contains(pendingSound.id)) pendingSound = null;
        visibilityTick = Long.MIN_VALUE;
        if (enabled.isEmpty()) {
            pending.clear();
            queued.clear();
        } else if (added) {
            chunks.keySet().forEach(this::queue);
        }
    }

    private BlockAlertTarget monitoredTarget(BlockState state) {
        BlockAlertTarget target = alertPreset().match(state);
        return target != null && monitored.contains(target.id) ? target : null;
    }

    private int scanRadius(boolean vanillaOre) {
        var settings = ConfigManager.current().alerts;
        return vanillaOre ? settings.vanillaOreScanRadius : settings.otherBlockScanRadius;
    }

    public static boolean withinScanRadius(BlockPos pos, BlockPos center, int radius) {
        return pos.distSqr(center) <= (double) radius * radius;
    }

    private boolean withinScanArea(BlockPos pos, BlockAlertTarget target) {
        return scanCenter != null && withinScanRadius(pos, scanCenter,
                target.isVanillaOre() ? vanillaScanRadius : otherScanRadius);
    }

    private void synchronizeScanArea(Minecraft client) {
        if (client.player == null || client.level == null) return;
        var settings = ConfigManager.current().alerts;
        Vec3 position = client.player.position();
        if (discovered.entrySet().removeIf(entry -> !settings.withinDisplayDistance(entry.getKey(), position))) {
            visibilityTick = Long.MIN_VALUE;
        }
        BlockPos center = client.player.blockPosition();
        int vanilla = scanRadius(true);
        int other = scanRadius(false);
        if (center.equals(scanCenter) && vanilla == vanillaScanRadius && other == otherScanRadius) return;
        scanCenter = center.immutable();
        vanillaScanRadius = vanilla;
        otherScanRadius = other;
        candidates.values().forEach(chunk -> chunk.entrySet().removeIf(entry -> !withinScanArea(entry.getKey(), entry.getValue())));
        candidates.values().removeIf(Map::isEmpty);
        visible = visible.stream().filter(wp -> withinScanArea(wp.pos(), wp.target())).toList();
        visibilityTick = Long.MIN_VALUE;
        int radius = Math.max(vanilla, other);
        // Same-dimension resets can retain loaded chunks without emitting new chunk-load events.
        for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++) {
            for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++) {
                LevelChunk chunk = client.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    long key = chunk.getPos().pack();
                    chunks.put(key, chunk);
                    queue(key);
                }
            }
        }
    }

    private void scanChunk(LevelChunk chunk) {
        if (monitored.isEmpty() || scanCenter == null) return;
        Map<BlockPos, BlockAlertTarget> found = new HashMap<>();
        var sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int index = 0; index < sections.length; index++) {
            var section = sections[index];
            int baseY = chunk.getSectionYFromSectionIndex(index) * 16;
            int radius = Math.max(vanillaScanRadius, otherScanRadius);
            if (section.hasOnlyAir() || baseY > scanCenter.getY() + radius || baseY + 15 < scanCenter.getY() - radius
                    || baseX > scanCenter.getX() + radius || baseX + 15 < scanCenter.getX() - radius
                    || baseZ > scanCenter.getZ() + radius || baseZ + 15 < scanCenter.getZ() - radius) {
                continue;
            }
            radius = 0;
            if (section.maybeHas(state -> {
                BlockAlertTarget target = monitoredTarget(state);
                return target != null && target.isVanillaOre();
            })) radius = vanillaScanRadius;
            if (otherScanRadius > radius && section.maybeHas(state -> {
                BlockAlertTarget target = monitoredTarget(state);
                return target != null && !target.isVanillaOre();
            })) radius = otherScanRadius;
            if (radius == 0) continue;
            for (int y = Math.max(0, scanCenter.getY() - radius - baseY); y <= Math.min(15, scanCenter.getY() + radius - baseY); y++) {
                for (int x = Math.max(0, scanCenter.getX() - radius - baseX); x <= Math.min(15, scanCenter.getX() + radius - baseX); x++) {
                    for (int z = Math.max(0, scanCenter.getZ() - radius - baseZ); z <= Math.min(15, scanCenter.getZ() + radius - baseZ); z++) {
                        BlockAlertTarget target = monitoredTarget(section.getBlockState(x, y, z));
                        if (target != null) {
                            pos.set(baseX + x, baseY + y, baseZ + z);
                            if (withinScanArea(pos, target)) found.put(pos.immutable(), target);
                        }
                    }
                }
            }
        }
        long key = chunk.getPos().pack();
        if (found.isEmpty()) candidates.remove(key);
        else candidates.put(key, found);
        for (var memory : List.of(discovered, seen)) {
            memory.entrySet().removeIf(entry -> {
                if (chunkKey(entry.getKey()) != key) return false;
                // A reduced scan must not erase remembered blocks outside its new radius.
                BlockAlertTarget target = monitoredTarget(chunk.getBlockState(entry.getKey()));
                return target == null || !target.id.equals(entry.getValue().id);
            });
        }
        visibilityTick = Long.MIN_VALUE;
    }

    private static long chunkKey(BlockPos pos) {
        return ChunkPos.pack(pos);
    }

    public static void blockChanged(BlockPos pos, BlockState state) {
        Minecraft client = Minecraft.getInstance();
        INSTANCE.setLevel(client.level);
        if (client.level == null || !client.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return;
        }
        INSTANCE.synchronizeTargets();
        INSTANCE.synchronizeScanArea(client);
        BlockAlertTarget target = INSTANCE.monitoredTarget(state);
        Map<BlockPos, BlockAlertTarget> found = INSTANCE.candidates.get(chunkKey(pos));
        BlockPos key = pos.immutable();
        boolean changed = found != null && found.remove(key) != null;
        if (target != null && INSTANCE.withinScanArea(pos, target)) {
            if (found == null) {
                found = new HashMap<>();
                INSTANCE.candidates.put(chunkKey(pos), found);
            }
            found.put(key, target);
            changed = true;
        }
        BlockAlertTarget previous = INSTANCE.discovered.get(key);
        if (previous != null && (target == null || !target.id.equals(previous.id))) {
            INSTANCE.discovered.remove(key);
            changed = true;
        }
        previous = INSTANCE.seen.get(key);
        if (previous != null && (target == null || !target.id.equals(previous.id))) {
            INSTANCE.seen.remove(key);
            changed = true;
        }
        if (changed) INSTANCE.visibilityTick = Long.MIN_VALUE;
    }

    public static BlockAlertsPreset surfacePreset() {
        if (!alertPreset().available || !ConfigManager.preset(ConfigManager.current().preset).allows(MiningFeature.SURFACE_TARGET_MINER)) {
            return null;
        }
        return alertPreset();
    }

    public static BlockAlertTarget surfaceTarget(BlockPos pos, BlockState state) {
        BlockAlertsPreset preset = surfacePreset();
        if (preset == null) return null;
        BlockAlertTarget target = preset.match(state);
        var settings = target == null ? null : ConfigManager.current().alerts.targets.get(target.id);
        var player = Minecraft.getInstance().player;
        return settings != null && settings.enabled && settings.surfaceMine && player != null
                && withinScanRadius(pos, player.blockPosition(), INSTANCE.scanRadius(target.isVanillaOre())) ? target : null;
    }

    static List<Waypoint> updateVisibility(Minecraft client) {
        INSTANCE.setLevel(client.level);
        if (client.level == null || client.player == null || !alertPreset().available) {
            INSTANCE.visible = List.of();
            return INSTANCE.visible;
        }
        INSTANCE.synchronizeTargets();
        INSTANCE.synchronizeScanArea(client);
        long now = client.level.getGameTime();
        if (INSTANCE.visibilityTick != Long.MIN_VALUE && now - INSTANCE.visibilityTick < 5) {
            return INSTANCE.visible;
        }
        INSTANCE.visibilityTick = now;
        ArrayList<Waypoint> visible = new ArrayList<>();
        var settings = ConfigManager.current().alerts;
        for (var chunk : INSTANCE.candidates.values()) {
            for (var entry : chunk.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockAlertTarget target = entry.getValue();
                if (!settings.targets.containsKey(target.id) || !settings.targets.get(target.id).enabled
                        || !onScreen(client, pos) || !hasVisibleFace(client, pos)) {
                    continue;
                }
                visible.add(new Waypoint(pos, target));
                // Keep sound deduplication only for loaded chunks, independently of waypoint eviction.
                BlockAlertTarget previous = INSTANCE.seen.put(pos, target);
                if (previous == null && !INSTANCE.discovered.containsKey(pos)
                        && settings.sounds && !settings.targets.get(target.id).sound.isEmpty()
                        && (INSTANCE.pendingSound == null || target.rarity > INSTANCE.pendingSound.rarity)) {
                    INSTANCE.pendingSound = target;
                }
            }
        }
        var retained = prioritizeWaypoints(Stream.concat(
                INSTANCE.discovered.entrySet().stream().map(entry -> new Waypoint(entry.getKey(), entry.getValue())),
                visible.stream().filter(wp -> !INSTANCE.discovered.containsKey(wp.pos())))
                .filter(wp -> settings.withinDisplayDistance(wp.pos(), client.player.position())), client.player.position());
        INSTANCE.discovered.clear();
        retained.forEach(wp -> INSTANCE.discovered.put(wp.pos(), wp.target()));
        INSTANCE.visible = List.copyOf(visible);
        return INSTANCE.visible;
    }

    public static boolean onScreen(Minecraft client, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        if (client.player.getLookAngle().dot(center.subtract(client.player.getEyePosition())) <= 0) {
            return false;
        }
        Vec3 projected = client.gameRenderer.projectPointToScreen(center);
        return Double.isFinite(projected.x) && Double.isFinite(projected.y)
                && projected.x >= -1 && projected.x <= 1 && projected.y >= -1 && projected.y <= 1;
    }

    // shortcut: six face centers can miss tiny exposed corners, use render-based visibility if required later.
    public static boolean hasVisibleFace(Minecraft client, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (Direction direction : Direction.values()) {
            if (client.level.getBlockState(pos.relative(direction)).isSolidRender()) continue;
            Vec3 face = center.add(direction.getStepX() * 0.499, direction.getStepY() * 0.499, direction.getStepZ() * 0.499);
            var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), face,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public static List<Waypoint> remembered() {
        return discoveredWaypoints();
    }

    // Glow must use discoveries, independently of extensions to waypoint output.
    static List<Waypoint> discoveredWaypoints() {
        INSTANCE.synchronizeTargets();
        if (!alertPreset().available) {
            return List.of();
        }
        INSTANCE.synchronizeScanArea(Minecraft.getInstance());
        var settings = ConfigManager.current().alerts;
        Stream<Waypoint> waypoints = INSTANCE.discovered.entrySet().stream()
                .map(entry -> new Waypoint(entry.getKey(), entry.getValue()));
        return waypoints.filter(wp -> settings.targets.containsKey(wp.target().id)
                && settings.targets.get(wp.target().id).enabled).toList();
    }

    public static List<Waypoint> waypointList(Vec3 player) {
        return waypointsInRange(player).stream().limit(ConfigManager.current().alerts.displayedWaypointCount).toList();
    }

    static List<Waypoint> waypointsInRange(Vec3 player) {
        var settings = ConfigManager.current().alerts;
        var retained = prioritizeWaypoints(remembered().stream().filter(wp -> settings.withinDisplayDistance(wp.pos(), player)), player);
        return settings.waypointSortByRarity ? retained : retained.stream()
                .sorted(Comparator.<Waypoint>comparingDouble(wp -> wp.pos().distToCenterSqr(player))
                        .thenComparingLong(wp -> wp.pos().asLong())).toList();
    }

    static List<Waypoint> prioritizeWaypoints(Stream<Waypoint> waypoints, Vec3 player) {
        Comparator<Waypoint> order = Comparator.<Waypoint>comparingInt(wp -> wp.target().rarity).reversed()
                .thenComparingDouble(wp -> wp.pos().distToCenterSqr(player))
                .thenComparingLong(wp -> wp.pos().asLong());
        PriorityQueue<Waypoint> retained = new PriorityQueue<>(MAX_WAYPOINTS, order.reversed());
        waypoints.forEach(waypoint -> {
            if (retained.size() < MAX_WAYPOINTS) {
                retained.add(waypoint);
            } else if (order.compare(waypoint, retained.peek()) < 0) {
                retained.remove();
                retained.add(waypoint);
            }
        });
        return retained.stream().sorted(order).toList();
    }

    public static void playSound(String sound) {
        playSound(sound, 1.0f);
    }

    public static void playSound(String sound, float volume) {
        if (!BlockAlertSettings.validVolume(volume)) return;
        if (BlockAlertSettings.SOUNDS.contains(sound)) {
            Minecraft client = Minecraft.getInstance();
            Identifier id = Identifier.parse(sound);
            if (client.getSoundManager().getSoundEvent(id) != null) {
                client.getSoundManager().play(new AlertSoundInstance(id, volume));
            }
        }
    }
}

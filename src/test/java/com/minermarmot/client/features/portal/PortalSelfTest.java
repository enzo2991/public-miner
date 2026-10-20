package com.minermarmot.client.features.portal;

import com.google.gson.Gson;
import com.minermarmot.client.config.ClientConfig;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.config.MiningPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class PortalSelfTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        run();
    }

    public static void run() throws Exception {
        MiningPreset kazuria;
        try (var input = PortalSelfTest.class.getResourceAsStream("/assets/minermarmot/presets/kazuria.json")) {
            kazuria = ConfigManager.parsePreset(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assert kazuria.portals.types.size() == 2;
        var pair = kazuria.portals.type("aether");
        var square = kazuria.portals.type("warden");
        assert pair.icon.components.get("minecraft:item_model").getAsString().equals("nexo:larme_seraphin");
        assert square.icon.components.get("minecraft:item_model").getAsString().equals("nexo:familier_warden");
        assert pair.icon.stack().isEmpty() : "Unbound icon components must remain safe";
        Map<BlockPos, BlockState> cells = new HashMap<>();
        BlockGetter world = new BlockGetter() {
            @Override public BlockState getBlockState(BlockPos pos) { return cells.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public int getHeight() { return 384; }
            @Override public int getMinY() { return -64; }
        };
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cells.clear();
            BlockPos a = new BlockPos(-16, 100, -16), b = a.relative(direction);
            cells.put(a, Blocks.SEA_LANTERN.defaultBlockState());
            assert pair.find(world, a, pos -> true) == null;
            cells.put(b, Blocks.SEA_LANTERN.defaultBlockState());
            var pattern = pair.find(world, a, pos -> true);
            assert pattern != null && pattern.equals(pair.find(world, b, pos -> true));
            assert pattern.marker().equals(Vec3.atCenterOf(a).add(direction.getStepX() * 0.5, -7, direction.getStepZ() * 0.5));
            assert pair.find(world, a, pos -> !pos.equals(b)) == null : "Do not discover incomplete loaded structures";
            cells.put(b.relative(direction), Blocks.SEA_LANTERN.defaultBlockState());
            assert pair.find(world, a, pos -> true) == null : "Reject longer lantern chains";
        }
        cells.clear();
        BlockPos corner = new BlockPos(-17, 60, 15);
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) cells.put(corner.offset(x, 0, z), Blocks.END_PORTAL.defaultBlockState());
        var pattern = square.find(world, corner, pos -> true);
        assert pattern != null && pattern.anchor().equals(corner.offset(1, 0, 1));
        for (var pos : pattern.blocks()) assert pattern.equals(square.find(world, pos, cell -> true)) : "All cells must share one marker";
        assert square.find(world, corner, pos -> pos.getX() < -16) == null : "Cross-chunk borders must be loaded";
        cells.remove(corner.offset(1, 0, 1));
        assert square.find(world, corner, pos -> true) == null;
        cells.put(corner.offset(1, 0, 1), Blocks.END_PORTAL.defaultBlockState());
        cells.put(corner.west(), Blocks.END_PORTAL.defaultBlockState());
        assert square.find(world, corner, pos -> true) == null : "Reject extended squares";
        Gson gson = new Gson();
        var json = gson.toJsonTree(kazuria).getAsJsonObject();
        json.remove("portals");
        var legacy = ConfigManager.parsePreset(json.toString(), kazuria);
        assert legacy.portals.types.size() == 2 : "Installed Kazuria files inherit portals without rewriting";
        json.add("portals", com.google.gson.JsonParser.parseString("{\"types\":[]}"));
        assert ConfigManager.parsePreset(json.toString(), kazuria).portals.types.isEmpty() : "Explicit empty types override fallback";
        assert ConfigManager.parsePreset("{\"name\":\"Custom\"}").portals.types.isEmpty();
        ClientConfig config = new ClientConfig();
        config.applyPreset("kazuria", kazuria);
        assert config.portals.displayedWaypointCount == 3 && config.portals.waypointDisplayDistance == 256;
        assert !config.portals.monitors.get("aether") && !config.portals.monitors.get("warden");
        config.portals.monitors.put("aether", true);
        config.portals.displayedWaypointCount = 7;
        var copy = config.copy();
        copy.portals.monitors.put("aether", false);
        assert config.portals.monitors.get("aether");
        config.applyPreset("custom", ConfigManager.parsePreset("{\"name\":\"Custom\"}"));
        config.applyPreset("kazuria", kazuria);
        assert config.portals.monitors.get("aether") && config.portals.displayedWaypointCount == 7;
        var restored = ConfigManager.parseConfig(gson.toJson(config));
        restored.validate(kazuria);
        assert restored.portals.monitors.get("aether") && restored.portals.displayedWaypointCount == 7;
        restored.resetPreset(kazuria);
        assert !restored.portals.monitors.get("aether") && restored.portals.displayedWaypointCount == 3;
        var old = ConfigManager.parseConfig("{\"preset\":\"kazuria\"}");
        old.validate(kazuria);
        assert old.portals.monitors.size() == 2 && !old.portals.monitors.get("aether");
        for (String invalid : new String[]{"null", "{\"defaults\":{\"displayedWaypointCount\":-1}}",
                "{\"defaults\":{\"displayedWaypointCount\":101}}", "{\"defaults\":{\"waypointDisplayDistance\":0}}",
                "{\"defaults\":{\"waypointDisplayDistance\":4097}}", "{\"types\":[null]}"}) {
            rejects("{\"name\":\"Bad\",\"portals\":" + invalid + "}");
        }
        for (String field : new String[]{"block", "shape", "id"}) {
            var bad = gson.toJsonTree(kazuria).getAsJsonObject();
            bad.getAsJsonObject("portals").getAsJsonArray("types").get(0).getAsJsonObject().addProperty(field, "INVALID");
            rejects(bad.toString());
        }
        System.out.println("Portal patterns, presets, migration and settings checks passed.");
    }

    private static void rejects(String json) {
        try { ConfigManager.parsePreset(json); throw new AssertionError("Invalid portal preset accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}

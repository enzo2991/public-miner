package com.minermarmot.client.features.alerts;

import com.google.gson.Gson;
import com.minermarmot.client.config.ClientConfig;
import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.config.MiningPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.Vec3;

public final class BlockAlertsSelfTest {
    public static void run() {
        verifyVolumes();
        var sortGson = new Gson();
        assert !sortGson.fromJson("{}", BlockAlertsConfig.class).waypointSortByRarity : "Legacy settings must default to distance sorting";
        var sorting = new BlockAlertsConfig();
        sorting.waypointSortByRarity = true;
        assert sorting.copy().waypointSortByRarity : "Preset and configuration copies must preserve sorting";
        assert sortGson.fromJson(sortGson.toJson(sorting), BlockAlertsConfig.class).waypointSortByRarity : "Sorting must survive saving";
        var start = new org.joml.Vector4f(-0.5f, 0, 0, 1);
        var end = new org.joml.Vector4f(0.5f, 0, 0, 1);
        assert BlockAlertRenderer.clipGlowEdge(start, end);
        assert start.x == -0.5f && end.x == 0.5f;
        start.set(-2, 0, 0, 1);
        assert BlockAlertRenderer.clipGlowEdge(start, end) && Math.abs(start.x + start.w) < 0.0001f;
        start.set(2, 0, 0, 1);
        end.set(3, 0, 0, 1);
        assert !BlockAlertRenderer.clipGlowEdge(start, end) : "Off-screen edges must be rejected";
        start.set(0, 0, 0, -1);
        end.set(0, 0, 0, -2);
        assert !BlockAlertRenderer.clipGlowEdge(start, end) : "Edges behind the camera must not mirror on screen";
        start.set(0, 0, 0, -1);
        end.set(0.5f, 0, 0, 1);
        assert BlockAlertRenderer.clipGlowEdge(start, end) && start.w > 0 && Math.abs(start.x) <= start.w + 0.0001f;
        start.set(Float.NaN, 0, 0, 1);
        assert !BlockAlertRenderer.clipGlowEdge(start, end);
        String json = """
                {"name":"Alerts","blockAlerts":{"categories":{"OVERWORLD":{"blocks":[
                  {"id":"ruby","block":"minecraft:note_block","properties":{"instrument":"harp","note":"3"},
                   "displayName":"Ruby","item1":{"item":"minecraft:paper","components":{
                     "minecraft:item_model":"minecraft:diamond","minecraft:custom_model_data":{
                       "floats":[123.0],"flags":[true],"strings":["ruby"],"colors":[16711680]}}},
                   "defaults":{"color":"#FF0000","sound":"","glow":true}},
                  {"id":"diamond","block":"minecraft:diamond_ore","displayName":"Diamond",
                   "item1":{"item":"minecraft:diamond_ore"},"item2":[{"item":"minecraft:diamond"}]}]}}}}
                """;
        MiningPreset preset = ConfigManager.parsePreset(json);
        assert !net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.parse("minecraft:paper")).orElseThrow().areComponentsBound();
        assert preset.blockAlerts.targets().getFirst().item1.stack().isEmpty()
                : "Icons before world component binding must not crash";
        assert preset.blockAlerts.targets().getFirst().item1.name().getString().equals("minecraft:paper")
                : "Icon tooltips before world component binding must use the item identifier";
        var note = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.HARP)
                .setValue(NoteBlock.NOTE, 3);
        assert preset.blockAlerts.match(note).id.equals("ruby");
        assert preset.blockAlerts.match(note.setValue(NoteBlock.NOTE, 4)) == null;
        assert preset.blockAlerts.match(note.setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.BASS)) == null;
        for (var state : Blocks.NOTE_BLOCK.getStateDefinition().getPossibleStates()) {
            var expected = preset.blockAlerts.targets().stream().filter(target -> target.matches(state)).findFirst().orElse(null);
            assert preset.blockAlerts.match(state) == expected : "Cached matching must preserve every note-block variant";
            assert preset.blockAlerts.match(state) == expected : "Repeated matches and misses must remain identical";
        }
        assert preset.blockAlerts.match(Blocks.STONE.defaultBlockState()) == null;
        assert preset.blockAlerts.match(Blocks.AIR.defaultBlockState()) == null;
        assert preset.blockAlerts.match(Blocks.DIAMOND_ORE.defaultBlockState()).id.equals("diamond");
        var ruby = preset.blockAlerts.targets().getFirst();
        ruby.properties.put("note", "4");
        preset.blockAlerts.validate();
        assert preset.blockAlerts.match(note) == null : "Revalidation must discard cached positive matches";
        assert preset.blockAlerts.match(note.setValue(NoteBlock.NOTE, 4)) == ruby : "Revalidation must discard cached misses";
        ruby.properties.put("note", "3");
        preset.blockAlerts.validate();
        assert preset.blockAlerts.match(note) == ruby;
        assert preset.blockAlerts.targets().get(1).rarity == 1;
        assert preset.blockAlerts.targets().getFirst().category.equals("OVERWORLD");
        var icon = net.minecraft.core.component.DataComponentPatch.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                preset.blockAlerts.targets().getFirst().item1.components).getOrThrow();
        assert icon.get(net.minecraft.core.component.DataComponentMap.EMPTY, DataComponents.ITEM_MODEL).toString().equals("minecraft:diamond");
        assert icon.get(net.minecraft.core.component.DataComponentMap.EMPTY, DataComponents.CUSTOM_MODEL_DATA).floats().equals(java.util.List.of(123.0f));
        assert icon.get(net.minecraft.core.component.DataComponentMap.EMPTY, DataComponents.CUSTOM_MODEL_DATA).colors().equals(java.util.List.of(16711680));

        ClientConfig config = new ClientConfig();
        config.applyPreset("alerts", preset);
        assert config.alerts.targets.get("ruby").glow;
        assert config.alerts.targets.get("ruby").surfaceMine;
        assert config.alerts.glowHiddenEdges;
        assert new Gson().fromJson("{}", BlockAlertsConfig.class).glowHiddenEdges : "Old settings must retain the complete contour";
        assert ConfigManager.parseConfig("{\"alerts\":{},\"alertPresets\":{\"old\":{}}}").alertPresets.get("old").glowHiddenEdges;
        assert !ConfigManager.parsePreset(json.replace("\"categories\":", "\"defaults\":{\"glowHiddenEdges\":false},\"categories\":"))
                .blockAlerts.newConfig().glowHiddenEdges;
        config.alerts.glowHiddenEdges = false;
        assert new Gson().fromJson("{}", BlockAlertsConfig.class).vanillaOreScanRadius == 16;
        assert ConfigManager.parseConfig("{\"alerts\":{},\"alertPresets\":{\"old\":{}}}")
                .alertPresets.get("old").otherBlockScanRadius == 16;
        assert !preset.blockAlerts.targets().getFirst().isVanillaOre();
        assert preset.blockAlerts.targets().get(1).isVanillaOre();
        assert ConfigManager.parsePreset(json.replace("minecraft:diamond_ore", "minecraft:ancient_debris"))
                .blockAlerts.targets().get(1).isVanillaOre();
        assert !ConfigManager.parsePreset(json.replace("minecraft:diamond_ore", "minecraft:stone"))
                .blockAlerts.targets().get(1).isVanillaOre();
        config.alerts.vanillaOreScanRadius = 1;
        config.alerts.otherBlockScanRadius = 128;
        BlockAlertsConfig range = new BlockAlertsConfig();
        range.waypointDisplayDistance = 5;
        BlockPos position = new BlockPos(-10, 65, -20);
        Vec3 center = Vec3.atCenterOf(position);
        assert range.withinDisplayDistance(position, center.add(3, 4, 0)) : "The exact 3D distance boundary must be included";
        assert !range.withinDisplayDistance(position, center.add(3, 4.001, 0)) : "Targets beyond the boundary must be hidden";
        range.waypointDisplayDistance = 1;
        assert range.withinDisplayDistance(position, center.add(0, 0, 1));
        assert !range.withinDisplayDistance(position, center.add(0, 1.001, 0));
        range.waypointDisplayDistance = 4096;
        assert range.withinDisplayDistance(position, center.add(4096, 0, 0));
        assert !range.withinDisplayDistance(position, center.add(4096.001, 0, 0));
        config.alerts.targets.get("ruby").color = "#123456";
        config.alerts.targets.get("ruby").enabled = false;
        config.alerts.targets.get("ruby").surfaceMine = false;
        ClientConfig copy = config.copy();
        assert !copy.alerts.glowHiddenEdges;
        assert copy.alerts.vanillaOreScanRadius == 1 && copy.alerts.otherBlockScanRadius == 128;
        copy.alerts.glowHiddenEdges = true;
        assert !config.alerts.glowHiddenEdges;
        copy.alerts.targets.get("ruby").color = "#654321";
        copy.alerts.targets.get("ruby").surfaceMine = true;
        assert config.alerts.targets.get("ruby").color.equals("#123456");
        assert !config.alerts.targets.get("ruby").surfaceMine;
        config.applyPreset("other", preset);
        assert config.alerts.vanillaOreScanRadius == 16 && config.alerts.otherBlockScanRadius == 16;
        assert config.alerts.glowHiddenEdges;
        config.applyPreset("alerts", preset);
        assert config.alerts.vanillaOreScanRadius == 1 && config.alerts.otherBlockScanRadius == 128;
        assert !config.alerts.glowHiddenEdges;
        assert config.alerts.targets.get("ruby").color.equals("#123456");
        assert !config.alerts.targets.get("ruby").enabled;
        assert !config.alerts.targets.get("ruby").surfaceMine;
        Gson gson = new Gson();
        var legacy = gson.fromJson("{\"waypointVisibleFacesOnly\":true}", BlockAlertsConfig.class);
        assert !gson.toJsonTree(legacy).getAsJsonObject().has("waypointVisibleFacesOnly")
                : "Old visibility settings must be ignored and omitted when saving";
        ClientConfig restored = gson.fromJson(gson.toJson(config), ClientConfig.class);
        restored.validate(preset);
        assert restored.alerts.vanillaOreScanRadius == 1 && restored.alerts.otherBlockScanRadius == 128;
        assert !restored.alerts.glowHiddenEdges;
        assert restored.alerts.targets.get("ruby").color.equals("#123456");
        assert !restored.alerts.targets.get("ruby").surfaceMine;
        restored.resetPreset(preset);
        assert restored.alerts.vanillaOreScanRadius == 16 && restored.alerts.otherBlockScanRadius == 16;
        for (String field : new String[]{"vanillaOreScanRadius", "otherBlockScanRadius"}) {
            for (int invalid : new int[]{0, -1, 129, Integer.MAX_VALUE}) {
                rejects(json.replace("\"categories\":", "\"defaults\":{\"" + field + "\":" + invalid + "},\"categories\":"));
            }
            assert ConfigManager.parsePreset(json.replace("\"categories\":", "\"defaults\":{\"" + field + "\":8},\"categories\":"))
                    .blockAlerts.newConfig() != null;
        }
        BlockPos scanCenter = new BlockPos(-10, -20, -30);
        assert BlockAlertManager.withinScanRadius(scanCenter.offset(3, 4, 0), scanCenter, 5);
        assert !BlockAlertManager.withinScanRadius(scanCenter.offset(3, 4, 1), scanCenter, 5);
        assert BlockAlertManager.withinScanRadius(scanCenter.offset(0, -128, 0), scanCenter, 128);
        assert !BlockAlertManager.withinScanRadius(scanCenter.offset(0, -129, 0), scanCenter, 128);
        assert restored.alerts.glowHiddenEdges;
        assert restored.alerts.targets.get("ruby").color.equals("#FF0000");
        assert restored.alerts.targets.get("ruby").enabled;
        assert restored.alerts.targets.get("ruby").surfaceMine;
        assert !ConfigManager.parsePreset(json.replace("\"glow\":true", "\"glow\":true,\"surfaceMine\":false"))
                .blockAlerts.newConfig().targets.get("ruby").surfaceMine;
        rejects(json.replace("\"note\":\"3\"", "\"note\":\"25\""));
        rejects(json.replace("\"instrument\":\"harp\",", ""));
        rejects(json.replace("\"id\":\"diamond\"", "\"id\":\"ruby\""));
        rejects(json.replace("#FF0000", "#FF00"));
        rejects(json.replace("minecraft:paper", "minecraft:missing_item"));
        rejects(json.replace("minecraft:custom_model_data", "minecraft:unknown_component"));
        assert BlockAlertSettings.validColor("#abcdef");
        assert !BlockAlertSettings.validColor("#GG0000");
        assert config.alerts.targets.get("ruby").argb() == 0xFF123456;
        assert BlockAlertSettings.SOUNDS.size() == 7;
        BlockAlertSettings soundSettings = new BlockAlertSettings();
        for (String sound : BlockAlertSettings.SOUNDS) {
            soundSettings.sound = sound;
            soundSettings.validate();
            assert soundSettings.sound.equals(sound);
        }
        soundSettings.sound = "minecraft:block.note_block.basedrum";
        soundSettings.color = "#123456";
        soundSettings.surfaceMine = false;
        soundSettings.validate();
        assert soundSettings.sound.isEmpty();
        assert soundSettings.color.equals("#123456") && !soundSettings.surfaceMine;
        assert ConfigManager.parsePreset(json.replace("\"sound\":\"\"", "\"sound\":\"my_server:old_alert\""))
                .blockAlerts.newConfig().targets.get("ruby").sound.isEmpty();
        rejects(json.replace("\"sound\":\"\"", "\"sound\":\"not a sound id\""));
        MiningPreset grouped = ConfigManager.parsePreset("""
                {"name":"Categories","blockAlerts":{"categories":{
                 "NETHER":{"blocks":[{"id":"minecraft:ancient_debris","block":"minecraft:ancient_debris",
                   "displayName":"Debris","item1":{"item":"minecraft:ancient_debris"}}]},
                 "OVERWORLD":{"blocks":[{"id":"minecraft:diamond_ore","block":"minecraft:diamond_ore",
                   "displayName":"Diamond","item1":{"item":"minecraft:diamond_ore"}}]},
                 "CUSTOM_WORLD":{"blocks":[]}}}}
                """);
        assert grouped.blockAlerts.categories.keySet().stream().toList().equals(java.util.List.of("NETHER", "OVERWORLD", "CUSTOM_WORLD"));
        String legacyJson = """
                {"name":"Old default","monitoredBlocks":["minecraft:diamond_ore","minecraft:ancient_debris"]}
                """;
        MiningPreset migrated = ConfigManager.parsePreset(legacyJson, grouped);
        assert migrated.blockAlerts.categories.keySet().stream().toList().equals(java.util.List.of("NETHER", "OVERWORLD", "CUSTOM_WORLD"));
        assert migrated.blockAlerts.categories.get("NETHER").blocks.getFirst().id.equals("minecraft:ancient_debris");
        assert migrated.blockAlerts.categories.get("OVERWORLD").blocks.getFirst().id.equals("minecraft:diamond_ore");
        assert ConfigManager.parsePreset(legacyJson).blockAlerts.categories.containsKey("BLOCKS");
        assert ConfigManager.parsePreset(legacyJson.replace("minecraft:diamond_ore", "minecraft:stone"), grouped)
                .blockAlerts.categories.containsKey("BLOCKS");
        assert ConfigManager.parsePreset(new Gson().toJson(grouped), preset).blockAlerts.categories.keySet()
                .stream().toList().equals(java.util.List.of("NETHER", "OVERWORLD", "CUSTOM_WORLD"));
        verifyKazuria();
        verifyMinerite();
        verifyPlayerAlerts();
        verifyWaypointLimit();
        System.out.println("Block alert matching, icons, settings and validation self-checks passed.");
    }

    private static void verifyWaypointLimit() {
        Vec3 player = new Vec3(-10.5, 65.5, -20.5);
        var waypoints = new java.util.ArrayList<BlockAlertManager.Waypoint>();
        for (int index = 0; index < 10000; index++) {
            BlockAlertTarget target = new BlockAlertTarget();
            target.rarity = index % 7;
            waypoints.add(new BlockAlertManager.Waypoint(new BlockPos(index - 5000, 65, -20), target));
        }
        var expected = waypoints.stream().sorted(java.util.Comparator
                .<BlockAlertManager.Waypoint>comparingInt(wp -> wp.target().rarity).reversed()
                .thenComparingDouble(wp -> wp.pos().distToCenterSqr(player))
                .thenComparingLong(wp -> wp.pos().asLong())).limit(100).toList();
        var retained = BlockAlertManager.prioritizeWaypoints(waypoints.stream(), player);
        assert retained.size() == 100;
        assert retained.equals(expected) : "Keep the rarest, then nearest waypoints, regardless of arrival order";
        java.util.Collections.reverse(waypoints);
        assert BlockAlertManager.prioritizeWaypoints(waypoints.stream(), player).equals(expected);
        assert BlockAlertManager.prioritizeWaypoints(java.util.stream.Stream.empty(), player).isEmpty();
        assert BlockAlertManager.prioritizeWaypoints(expected.stream().limit(3), player).equals(expected.subList(0, 3));
        BlockAlertTarget rare = new BlockAlertTarget();
        rare.rarity = 100;
        var distant = new BlockAlertManager.Waypoint(new BlockPos(100000, 65, 0), rare);
        assert BlockAlertManager.prioritizeWaypoints(java.util.stream.Stream.concat(waypoints.stream(),
                java.util.stream.Stream.of(distant)), player).getFirst().equals(distant)
                : "A newly discovered rare block must displace a common block even at greater distance";
        BlockAlertTarget common = new BlockAlertTarget();
        var left = new BlockAlertManager.Waypoint(new BlockPos(-12, 65, -21), common);
        var right = new BlockAlertManager.Waypoint(new BlockPos(-11, 65, -20), common);
        var ties = BlockAlertManager.prioritizeWaypoints(java.util.stream.Stream.of(right, left), player);
        assert ties.equals(BlockAlertManager.prioritizeWaypoints(java.util.stream.Stream.of(left, right), player))
                : "Equal-distance ties must be stable";
    }

    private static void verifyVolumes() {
        Gson gson = new Gson();
        var old = gson.fromJson("{}", PlayerAlertsConfig.class);
        assert old.inventoryFullVolume == 1 && old.privateMessageVolume == 1;
        assert gson.fromJson("{}", BlockAlertsConfig.class).soundVolume == 1;
        MiningPreset preset = ConfigManager.parsePreset("""
                {"name":"Volume","blockAlerts":{"defaults":{"soundVolume":2}},
                 "playerAlerts":{"inventoryFull":true,"privateMessage":true,"privateMessagePattern":"^PM",
                  "defaults":{"inventoryFullVolume":0.5,"privateMessageVolume":5}}}
                """);
        ClientConfig config = new ClientConfig();
        config.applyPreset("volume", preset);
        assert config.alerts.soundVolume == 2 && config.playerAlerts.inventoryFullVolume == 0.5f
                && config.playerAlerts.privateMessageVolume == 5;
        config.alerts.soundVolume = 5;
        config.playerAlerts.inventoryFullVolume = 3;
        config.playerAlerts.privateMessageVolume = 0.5f;
        var copy = config.copy();
        assert copy.alerts.soundVolume == 5 && copy.playerAlerts.inventoryFullVolume == 3;
        copy.alerts.soundVolume = 1;
        copy.playerAlerts.privateMessageVolume = 1;
        assert config.alerts.soundVolume == 5 && config.playerAlerts.privateMessageVolume == 0.5f;
        config.applyPreset("other", ConfigManager.parsePreset("{\"name\":\"Other\"}"));
        assert config.alerts.soundVolume == 1 && config.playerAlerts.inventoryFullVolume == 1;
        config.applyPreset("volume", preset);
        assert config.alerts.soundVolume == 5 && config.playerAlerts.inventoryFullVolume == 3
                && config.playerAlerts.privateMessageVolume == 0.5f;
        var restored = gson.fromJson(gson.toJson(config), ClientConfig.class);
        restored.validate(preset);
        assert restored.alerts.soundVolume == 5 && restored.playerAlerts.inventoryFullVolume == 3
                && restored.playerAlerts.privateMessageVolume == 0.5f;
        restored.resetPreset(preset);
        assert restored.alerts.soundVolume == 2 && restored.playerAlerts.inventoryFullVolume == 0.5f
                && restored.playerAlerts.privateMessageVolume == 5;
        for (float value : BlockAlertSettings.VOLUMES) {
            old.inventoryFullVolume = old.privateMessageVolume = value;
            old.validate();
        }
        for (float invalid : new float[]{0, 0.49f, 5.01f, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            for (String field : new String[]{"soundVolume", "inventoryFullVolume", "privateMessageVolume"}) {
                String section = field.equals("soundVolume") ? "blockAlerts" : "playerAlerts";
                rejects("{\"name\":\"Invalid volume\",\"" + section + "\":{\"defaults\":{\"" + field + "\":" + invalid + "}}}");
            }
        }
    }

    private static void verifyPlayerAlerts() {
        try (var input = ConfigManager.class.getResourceAsStream("/assets/minermarmot/presets/kazuria.json")) {
            MiningPreset kazuria = ConfigManager.parsePreset(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var alerts = kazuria.playerAlerts;
            assert alerts.inventoryFull && alerts.privateMessage;
            assert alerts.defaults.inventoryFullRepeat == 3 && alerts.defaults.privateMessageRepeat == 3;
            assert alerts.defaults.inventoryFullSound.equals("minecraft:block.note_block.pling");
            assert alerts.defaults.privateMessageSound.equals("minecraft:block.note_block.pling");
            assert alerts.matchesPrivateMessage("[Player -> Moi] Hello!");
            assert alerts.matchesPrivateMessage("[VIP Player -> Moi] Hello!\nSecond line");
            assert !alerts.matchesPrivateMessage("[Moi -> Player] Hello!");
            assert !alerts.matchesPrivateMessage("[Player -> Other] Hello!");
            assert !alerts.matchesPrivateMessage("prefix [Player -> Moi] Hello!");
            assert !alerts.matchesPrivateMessage("[ -> Moi] Hello!");
            assert !alerts.matchesPrivateMessage("[Player -> Moi Hello!");
            MiningPreset migrated = ConfigManager.parsePreset("{\"name\":\"Kazuria old\"}", kazuria);
            assert migrated.playerAlerts.matchesPrivateMessage("[Player -> Moi] Test");
            assert migrated.playerAlerts != alerts;
            assert migrated.playerAlerts.defaults.inventoryFullRepeat == 3;
            MiningPreset oldRepeats = ConfigManager.parsePreset("{\"name\":\"Old\",\"playerAlerts\":{\"defaults\":{\"inventoryFullSound\":\"minecraft:block.note_block.guitar\"}}}", kazuria);
            assert oldRepeats.playerAlerts.defaults.inventoryFullRepeat == 3 && oldRepeats.playerAlerts.defaults.privateMessageRepeat == 3;
            assert oldRepeats.playerAlerts.defaults.inventoryFullSound.equals("minecraft:block.note_block.guitar");
            assert !ConfigManager.parsePreset("{\"name\":\"Custom\"}").playerAlerts.inventoryFull;
            MiningPreset disabled = ConfigManager.parsePreset("{\"name\":\"Disabled\",\"playerAlerts\":{}}", kazuria);
            assert !disabled.playerAlerts.inventoryFull && !disabled.playerAlerts.privateMessage;
            rejects("{\"name\":\"Bad regex\",\"playerAlerts\":{\"privateMessage\":true,\"privateMessagePattern\":\"[\"}}");
            rejects("{\"name\":\"Missing regex\",\"playerAlerts\":{\"privateMessage\":true}}");
            rejects("{\"name\":\"Bad sound\",\"playerAlerts\":{\"defaults\":{\"inventoryFullSound\":\"bad sound\"}}}");
            for (int invalid : new int[]{0, 6, -1}) {
                for (String key : new String[]{"inventoryFullRepeat", "privateMessageRepeat"}) {
                    rejects("{\"name\":\"Bad repeat\",\"playerAlerts\":{\"defaults\":{\"" + key + "\":" + invalid + "}}}");
                }
            }
            ClientConfig config = new ClientConfig();
            config.applyPreset("kazuria", kazuria);
            config.playerAlerts.inventoryFull = false;
            config.playerAlerts.privateMessageSound = "minecraft:block.note_block.guitar";
            config.playerAlerts.inventoryFullRepeat = 1;
            config.playerAlerts.privateMessageRepeat = 5;
            ClientConfig copy = config.copy();
            copy.playerAlerts.inventoryFull = true;
            assert !config.playerAlerts.inventoryFull;
            config.applyPreset("disabled", disabled);
            assert !config.playerAlerts.inventoryFull && !config.playerAlerts.privateMessage;
            config.applyPreset("kazuria", kazuria);
            assert !config.playerAlerts.inventoryFull;
            assert config.playerAlerts.privateMessageSound.equals("minecraft:block.note_block.guitar");
            assert config.playerAlerts.inventoryFullRepeat == 1 && config.playerAlerts.privateMessageRepeat == 5;
            Gson gson = new Gson();
            ClientConfig restored = gson.fromJson(gson.toJson(config), ClientConfig.class);
            restored.validate(kazuria);
            assert !restored.playerAlerts.inventoryFull;
            assert restored.playerAlerts.privateMessageSound.equals(config.playerAlerts.privateMessageSound);
            assert restored.playerAlerts.inventoryFullRepeat == 1 && restored.playerAlerts.privateMessageRepeat == 5;
            restored.resetPreset(kazuria);
            assert restored.playerAlerts.inventoryFull;
            assert restored.playerAlerts.privateMessageSound.equals(alerts.defaults.privateMessageSound);
            assert restored.playerAlerts.inventoryFullRepeat == 3 && restored.playerAlerts.privateMessageRepeat == 3;
            ClientConfig old = gson.fromJson("{\"preset\":\"kazuria\"}", ClientConfig.class);
            old.validate(kazuria);
            assert old.playerAlerts.inventoryFull && old.playerAlerts.privateMessage;
            old.playerAlerts.inventoryFullSound = "minecraft:old.sound";
            old.validate(kazuria);
            assert old.playerAlerts.inventoryFullSound.isEmpty();
        } catch (java.io.IOException exception) {
            throw new AssertionError("Cannot load Kazuria player alerts", exception);
        }
    }

    private static void verifyKazuria() {
        try (var input = ConfigManager.class.getResourceAsStream("/assets/minermarmot/presets/kazuria.json")) {
            assert input != null;
            MiningPreset preset = ConfigManager.parsePreset(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var categories = preset.blockAlerts.categories;
            assert preset.name.equals("Kazuria");
            assert categories.keySet().stream().toList().equals(java.util.List.of("OVERWORLD", "NETHER", "AETHER", "WARDEN", "AQUATIQUE"));
            assert categories.values().stream().map(category -> category.blocks.size()).toList().equals(java.util.List.of(6, 7, 1, 5, 6));
            assert preset.blockAlerts.newConfig().targets.values().stream().allMatch(settings ->
                    !settings.enabled && !settings.glow && !settings.surfaceMine && settings.sound.isEmpty());
            var basedrum = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.BASEDRUM);
            assert preset.blockAlerts.match(basedrum) == null;
            for (int note = 1; note <= 24; note++) {
                assert preset.blockAlerts.match(basedrum.setValue(NoteBlock.NOTE, note)) != null;
                assert preset.blockAlerts.match(basedrum.setValue(NoteBlock.NOTE, note)
                        .setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.HARP)) == null;
            }
            var mythicalAlgae = preset.blockAlerts.match(basedrum.setValue(NoteBlock.NOTE, 1));
            assert mythicalAlgae.id.equals("minerai_algue_mythique") && mythicalAlgae.rarity == 5;
            assert mythicalAlgae.displayName.equals("Minerai d'algue \uF105");
            assert mythicalAlgae.defaults.argb() == 0xFFE42A2B;
            var glowstone = preset.blockAlerts.match(Blocks.GLOWSTONE.defaultBlockState());
            assert glowstone.id.equals("glowstone_vanilla") && glowstone.rarity == 0;
            assert glowstone.item1.item.equals("minecraft:glowstone");
            assert glowstone.item2.getFirst().item.equals("minecraft:glowstone_dust");
            assert glowstone.defaults.argb() == 0xFFA9794A;
            var seraphin = categories.get("AETHER").blocks.getFirst();
            assert seraphin.displayName.equals("Minerai de Séraphin");
            assert seraphin.item1.components.get("minecraft:item_model").getAsString().equals("nexo:minerai_seraphin");
            assert seraphin.item2.stream().map(icon -> icon.components.get("minecraft:item_model").getAsString()).toList()
                    .equals(java.util.List.of("nexo:seraphite_commun", "nexo:seraphite_peu_commun", "nexo:seraphite_rare",
                            "nexo:seraphite_epic", "nexo:seraphite_legendaire", "nexo:seraphite_mythique"));
            assert ConfigManager.parsePreset(new Gson().toJson(preset)).blockAlerts.categories.keySet().stream().toList()
                    .equals(categories.keySet().stream().toList());
        } catch (java.io.IOException exception) {
            throw new AssertionError("Cannot read bundled Kazuria preset", exception);
        }
    }

    private static void verifyMinerite() {
        try (var input = ConfigManager.class.getResourceAsStream("/assets/minermarmot/presets/minerite.json")) {
            assert input != null;
            MiningPreset preset = ConfigManager.parsePreset(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var categories = preset.blockAlerts.categories;
            assert preset.name.equals("Minerite");
            assert categories.keySet().stream().toList().equals(java.util.List.of("OVERWORLD", "NETHER", "END", "HEAVEN"));
            assert categories.values().stream().map(category -> category.blocks.size()).toList().equals(java.util.List.of(7, 6, 6, 4));
            assert !preset.allows(com.minermarmot.client.features.mining.MiningFeature.SURFACE_TARGET_MINER);
            assert preset.portals.types.isEmpty();
            assert !preset.tnt.builder && preset.tnt.carpet && preset.tnt.shopClicks.equals(java.util.List.of(
                    "minecraft:red_shulker_box", "minecraft:lime_stained_glass_pane"));
            assert preset.blockAlerts.newConfig().targets.values().stream().allMatch(settings ->
                    !settings.enabled && !settings.glow && !settings.surfaceMine && settings.sound.isEmpty());
            String[] noteIds = {"ancient_debris_legendaire", "ancient_debris_ultime", "ancient_debris_antique",
                    "ancient_debris_mythique", "ancient_debris_ancestral", "peu_commun_ore", "rare_ore",
                    "super_rare_ore", "epique_ore", "elite_ore", "excellyth_ore", "primordial_ore", "astral_ore",
                    "celeste_ore", "divin_ore", "astrallyth_ore", "nebullyth_ore", "spectral_ore", "pulsar_ore", "void_ore"};
            var basedrum = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.BASEDRUM);
            for (int note = 0; note <= 24; note++) {
                var state = basedrum.setValue(NoteBlock.NOTE, note);
                var target = preset.blockAlerts.match(state);
                if (note < 4 || note > 23) {
                    assert target == null;
                    continue;
                }
                assert target.id.equals(noteIds[note - 4]);
                assert preset.blockAlerts.match(state.setValue(NoteBlock.POWERED, true)) == target;
                assert preset.blockAlerts.match(state.setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.HARP)) == null;
                assert target.item1.item.equals("minecraft:paper") && target.item2.size() == 1;
                assert target.item1.components.get("minecraft:item_model").getAsString().equals("nexo:" + target.id);
            }
            assert preset.blockAlerts.match(Blocks.DIAMOND_ORE.defaultBlockState()).id.equals("diamant_vanilla");
            assert preset.blockAlerts.match(Blocks.EMERALD_ORE.defaultBlockState()).id.equals("emeraude_vanilla");
            assert preset.blockAlerts.match(Blocks.ANCIENT_DEBRIS.defaultBlockState()).item2.getFirst().item.equals("minecraft:netherite_scrap");
            assert preset.blockAlerts.match(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()) == null;
            var end = categories.get("END").blocks;
            assert end.getFirst().id.equals("astrallyth_ore") && end.get(1).id.equals("excellyth_ore");
            assert categories.get("OVERWORLD").blocks.get(2).displayName.equals("Minerai ꖋ");
            assert categories.get("HEAVEN").blocks.getLast().displayName.equals("Minerai ꖛ");
            assert preset.playerAlerts.privateMessagePattern.equals("^\\[[^\\]]+ → toi\\]");
            assert preset.playerAlerts.matchesPrivateMessage("[Player → toi] Bonjour !");
            assert preset.playerAlerts.matchesPrivateMessage("[VIP Player → toi] Bonjour !\nSuite");
            for (String message : java.util.List.of("[toi → Player] Bonjour !", "[Player → autre] Bonjour !",
                    "prefix [Player → toi] Bonjour !", "[ → toi] Bonjour !", "[Player -> toi] Bonjour !", "[Player → toi Bonjour !")) {
                assert !preset.playerAlerts.matchesPrivateMessage(message);
            }
            ClientConfig config = new ClientConfig();
            config.applyPreset("minerite", preset);
            config.alerts.targets.get("void_ore").enabled = true;
            ClientConfig restored = new Gson().fromJson(new Gson().toJson(config), ClientConfig.class);
            restored.validate(preset);
            assert restored.alerts.targets.get("void_ore").enabled;
            restored.resetPreset(preset);
            assert !restored.alerts.targets.get("void_ore").enabled;
            assert restored.playerAlerts.privateMessage && restored.portals.monitors.isEmpty();
            assert ConfigManager.parsePreset(new Gson().toJson(preset)).blockAlerts.targets().size() == 23;
        } catch (java.io.IOException exception) {
            throw new AssertionError("Cannot read bundled Minerite preset", exception);
        }
    }

    private static void rejects(String json) {
        try {
            ConfigManager.parsePreset(json);
            throw new AssertionError("Invalid block alert preset was accepted");
        } catch (IllegalArgumentException expected) {
            // Reject invalid presets before scanning or rendering starts.
        }
    }
}

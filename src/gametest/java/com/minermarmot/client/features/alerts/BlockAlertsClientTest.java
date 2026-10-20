package com.minermarmot.client.features.alerts;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.mining.MiningManager;
import com.minermarmot.client.features.mining.MiningFeature;
import com.minermarmot.client.features.mining.MiningConfig;
import com.minermarmot.client.screen.ConfigScreen;
import com.minermarmot.client.screen.widget.ConfigSectionWidget;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.chunk.status.ChunkStatus;

// Experimental client test APIs are confined to the test mod.
public final class BlockAlertsClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {

        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        var preset = ConfigManager.preset(original.preset);
        Boolean surfaceAvailability = preset.features.get(MiningFeature.SURFACE_TARGET_MINER);
        BlockAlertsPreset.Category testCategory = new BlockAlertsPreset.Category();
        BlockAlertTarget note = new BlockAlertTarget();
        note.id = "test_ruby";
        note.block = "minecraft:note_block";
        note.properties.put("instrument", "harp");
        note.properties.put("note", "3");
        note.displayName = "Test Ruby";
        note.item1 = new AlertIcon();
        note.item1.item = "minecraft:paper";
        note.item1.components = com.google.gson.JsonParser.parseString("""
                {"minecraft:item_model":"minecraft:diamond","minecraft:custom_model_data":{"floats":[123.0]}}
                """).getAsJsonObject();
        note.defaults.glow = true;
        AlertIcon secondary = new AlertIcon();
        secondary.item = "minecraft:redstone";
        AlertIcon alternative = new AlertIcon();
        alternative.item = "minecraft:redstone_block";
        note.item2.add(secondary);
        note.item2.add(alternative);
        testCategory.blocks.add(note);
        try {
            context.runOnClient(client -> {
                check(ConfigManager.preset("kazuria") != null, "Kazuria must be loaded at client startup");
                check(java.nio.file.Files.exists(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                        .resolve("minermarmot/presets/kazuria.json")), "The bundled Kazuria preset must be copied to the preset directory");
                check(ConfigManager.preset("minerite") != null, "Minerite must be loaded at client startup");
                check(java.nio.file.Files.exists(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                        .resolve("minermarmot/presets/minerite.json")), "The bundled Minerite preset must be copied to the preset directory");
            });
            context.setScreen(() -> new ConfigScreen(null));
            configurationLayout(context);
            selectPreset(context, "minerite");
            context.runOnClient(client -> {
                var minerite = ConfigManager.preset("minerite");
                check(ConfigManager.current().preset.equals("minerite"), "Selecting Minerite must apply immediately");
                check(ConfigManager.current().alerts.targets.size() == 23, "Minerite must initialize all 23 target settings");
                check(ConfigManager.current().alerts.targets.values().stream().allMatch(settings ->
                                !settings.enabled && !settings.surfaceMine && !settings.glow && settings.sound.isEmpty()),
                        "Minerite targets must start with monitoring, surface mining, glow and sounds OFF");
                check(!minerite.allows(MiningFeature.SURFACE_TARGET_MINER) && minerite.portals.types.isEmpty()
                                && !minerite.tnt.builder && minerite.tnt.carpet && minerite.tnt.shopClicks.equals(java.util.List.of(
                                "minecraft:red_shulker_box", "minecraft:lime_stained_glass_pane")),
                        "Minerite must retain carpet with red-shulker purchase and lime-glass confirmation");
                check(ConfigManager.current().playerAlerts.privateMessage && minerite.playerAlerts.matchesPrivateMessage("[Player → toi] Hello!"),
                        "Minerite must enable private-message alerts with its own format");
                checkSaved();
            });
            selectPreset(context, "kazuria");
            context.runOnClient(client -> {
                check(client.gui.screen() instanceof ConfigScreen, "Preset selection must keep the configuration menu open");
                check(ConfigManager.current().preset.equals("kazuria"), "Selecting Kazuria must apply its preset immediately");
                check(ConfigManager.current().alerts.targets.size() == 25, "Kazuria must initialize all 25 target settings");
                check(ConfigManager.current().alerts.targets.values().stream().allMatch(settings ->
                        !settings.enabled && !settings.surfaceMine && !settings.glow && settings.sound.isEmpty()),
                        "Kazuria must start with monitoring, surface mining, glow and sounds disabled for every target");
                checkSaved();
            });
            context.clickScreenButton("minermarmot.config.quit");
            context.runOnClient(client -> {
                check(ConfigManager.current().preset.equals("kazuria"), "Quit must retain the selected preset");
                check(ConfigManager.save(original), "The original test settings must be restored");
            });
            context.runOnClient(client -> {
                preset.blockAlerts.categories.put("TEST", testCategory);
                preset.features.put(MiningFeature.SURFACE_TARGET_MINER, true);
                preset.validate();
                var settings = original.copy();
                settings.alerts = preset.blockAlerts.newConfig();
                settings.alerts.sounds = false;
                settings.alerts.targets.values().forEach(style -> style.glow = true);
                settings.alerts.targets.get("minecraft:emerald_ore").color = "#55FF55";
                check(ConfigManager.save(settings), "Alert test configuration must save");
            });
            blockChannelTable(context);
            liveSettings(context);
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.features");
            int categoryScale = context.computeOnClient(client -> client.options.guiScale().get());
            try {
                context.runOnClient(client -> {
                    client.options.guiScale().set(1);
                    client.resizeGui();
                });
                clickSection(context, Component.translatable("minermarmot.config.mining").getString());
                clickSection(context, Component.translatable("minermarmot.config.blockAlerts").getString());
                context.waitTick();
                context.runOnClient(client -> {
                    var categoryNames = preset.blockAlerts.categories.keySet().stream().toList();
                    var headers = sections(client.gui.screen()).stream()
                            .filter(section -> preset.blockAlerts.categories.containsKey(section.getMessage().getString())).toList();
                    check(headers.stream().map(section -> section.getMessage().getString()).toList().equals(categoryNames),
                            "Category headers must follow preset insertion order");
                    check(headers.stream().noneMatch(ConfigSectionWidget::isExpanded), "Categories must start collapsed");
                });
                context.takeScreenshot("block-alert-categories");
                var categoryNames = preset.blockAlerts.categories.keySet().stream().toList();
                String first = categoryNames.getFirst();
                String second = categoryNames.get(1);
                int foldedRows = context.computeOnClient(client -> listRowCount(client.gui.screen()));
                clickSection(context, first);
                context.runOnClient(client -> check(listRowCount(client.gui.screen()) == foldedRows
                                + preset.blockAlerts.categories.get(first).blocks.size() + 1,
                        "Expanding one category must reveal its column header and targets"));
                clickSection(context, second);
                context.runOnClient(client -> check(listRowCount(client.gui.screen()) == foldedRows
                                + preset.blockAlerts.categories.get(first).blocks.size() + preset.blockAlerts.categories.get(second).blocks.size() + 2,
                        "Categories must expand independently"));
                clickSection(context, first);
                context.runOnClient(client -> check(listRowCount(client.gui.screen()) == foldedRows
                                + preset.blockAlerts.categories.get(second).blocks.size() + 1,
                        "Collapsing a category must keep other categories expanded"));
                clickSection(context, second);
            } finally {
                context.runOnClient(client -> {
                    client.options.guiScale().set(categoryScale);
                    client.resizeGui();
                });
            }
            context.runOnClient(client -> client.gui.screen().children().stream()
                    .filter(child -> child instanceof EditBox).map(child -> (EditBox) child).findFirst().orElseThrow().setValue("Test Ruby"));
            scrollToBottom(context);
            context.waitTick();
            context.runOnClient(client -> checkBlockTable(client.gui.screen(), true));
            context.takeScreenshot("block-alert-settings");
            cycleListButton(context, "minermarmot.alerts.enabled");
            context.waitTick();
            context.runOnClient(client -> checkBlockTable(client.gui.screen(), false));
            context.takeScreenshot("block-alert-settings-disabled");
            cycleListButton(context, "minermarmot.alerts.enabled");
            context.runOnClient(client -> check(hasSurfaceMineButton(client.gui.screen()),
                    "The target mining switch must be visible when the preset allows surface mining"));
            clickSection(context, "TEST");
            context.runOnClient(client -> check(!hasSurfaceMineButton(client.gui.screen()),
                    "Category collapse must hide matching targets even while searching"));
            clickSection(context, "TEST");
            context.runOnClient(client -> check(hasSurfaceMineButton(client.gui.screen()),
                    "A searched category must expand again independently"));
            int guiScale = context.computeOnClient(client -> client.options.guiScale().get());
            try {
                context.runOnClient(client -> {
                    client.options.guiScale().set(1);
                    client.resizeGui();
                });
                context.waitTick();
                context.runOnClient(client -> checkBlockTable(client.gui.screen(), true));
                context.takeScreenshot("block-alert-settings-wide");
                minimumWidthTable(context);
            } finally {
                context.runOnClient(client -> {
                    client.options.guiScale().set(guiScale);
                    client.resizeGui();
                });
            }
            context.clickScreenButton("minermarmot.config.quit");

            context.runOnClient(client -> {
                preset.features.put(MiningFeature.SURFACE_TARGET_MINER, false);
                check(BlockAlertManager.surfaceTarget(BlockPos.ZERO, Blocks.DIAMOND_ORE.defaultBlockState()) == null,
                        "The preset must prohibit per-target surface mining even if its saved switch is on");
            });
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.features");
            context.runOnClient(client -> client.gui.screen().children().stream()
                    .filter(child -> child instanceof EditBox).map(child -> (EditBox) child).findFirst().orElseThrow().setValue("Test Ruby"));
            context.waitTick();
            context.runOnClient(client -> check(!hasSurfaceMineButton(client.gui.screen()),
                    "The target mining switch must be hidden when the preset prohibits surface mining"));
            context.clickScreenButton("minermarmot.config.quit");
            context.runOnClient(client -> preset.features.put(MiningFeature.SURFACE_TARGET_MINER, true));

            String[] chosen = {"unchanged"};
            context.setScreen(() -> new SoundSelectionScreen(new ConfigScreen(null), "", value -> chosen[0] = value));
            context.waitTick();
            context.runOnClient(client -> {
                check(listRowCount(client.gui.screen()) == 7, "The sound selector must show exactly seven sounds");
                checkLayout(client.gui.screen(), 420);
                check(client.gui.screen().children().stream().filter(child -> child instanceof Button)
                                .map(child -> (Button) child).anyMatch(button -> button.active
                                        && button.getMessage().getString().equals(SoundSelectionScreen.label("").getString())
                                        && button.getMessage().getStyle().getColor().getValue() == 0xBBBBBB),
                        "No sound must be gray and remain selectable");
                for (String id : BlockAlertSettings.SOUNDS) {
                    check(client.getSoundManager().getSoundEvent(net.minecraft.resources.Identifier.parse(id)) != null,
                            "Every supported sound must be present in loaded resources: " + id);
                    check(!SoundSelectionScreen.label(id).getString().startsWith("minermarmot."), "Sound labels must be translated");
                }
            });
            context.takeScreenshot("block-alert-sounds");
            context.clickScreenButton("minermarmot.alerts.noSound");
            context.runOnClient(client -> check(chosen[0].isEmpty(), "No sound must select the empty identifier"));
            context.setScreen(() -> new SoundSelectionScreen(new ConfigScreen(null), "", value -> chosen[0] = value));
            context.runOnClient(client -> client.gui.screen().children().stream()
                    .filter(child -> child instanceof EditBox).map(child -> (EditBox) child).findFirst().orElseThrow()
                    .setValue(Component.translatable("minermarmot.alerts.sound.bell").getString()));
            context.waitTick();
            context.runOnClient(client -> check(listRowCount(client.gui.screen()) == 1,
                    "Searching by a localized name must filter the sound selector"));
            clickListButton(context, "minermarmot.alerts.previewSound");
            clickListButton(context, "minermarmot.alerts.sound.bell");
            context.runOnClient(client -> check(chosen[0].equals("minecraft:block.note_block.bell"),
                    "Choosing a friendly label must retain the Minecraft sound identifier"));
            context.setScreen(() -> null);

            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                world.getServer().runCommand("fill -5 64 -5 5 70 5 minecraft:air");
                world.getServer().runCommand("fill -5 63 -5 5 63 5 minecraft:stone");
                world.getServer().runCommand("gamemode creative @p");
                world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
                context.waitTick();
                world.getConnection().waitForClientboundPackets();
                context.waitTicks(5);
                scanRadii(context, world, note);
                BlockPos diamond = new BlockPos(0, 65, 3);
                BlockPos wall = new BlockPos(0, 65, 2);
                setBlock(context, world, wall, Blocks.STONE.defaultBlockState());
                setBlock(context, world, diamond, Blocks.DIAMOND_ORE.defaultBlockState());
                context.waitTicks(12);
                context.runOnClient(client -> {
                    check(!MiningManager.isRunning(), "Monitoring must run without mining");
                    check(!hasWaypoint(diamond), "Fully hidden blocks must not be discovered");
                    check(!BlockAlertManager.hasVisibleFace(client, diamond), "Visual rays must stop at walls");
                });
                setBlock(context, world, wall, Blocks.AIR.defaultBlockState());
                context.waitFor(client -> hasWaypoint(diamond));
                context.takeScreenshot("block-alert-glow");
                context.runOnClient(client -> {
                    ConfigManager.current().alerts.glowHiddenEdges = false;
                    check(ConfigManager.save(ConfigManager.current()), "Visible glow edges must save");
                });
                context.waitTicks(3);
                context.runOnClient(client -> checkGlowDepth(false));
                context.takeScreenshot("block-alert-glow-visible-edges");
                context.runOnClient(client -> {
                    ConfigManager.current().alerts.glowHiddenEdges = true;
                    check(ConfigManager.save(ConfigManager.current()), "Complete glow edges must save");
                });
                context.waitTicks(3);
                context.runOnClient(client -> checkGlowDepth(true));
                BlockPos rarer = new BlockPos(1, 65, 3);
                setBlock(context, world, rarer, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
                context.waitFor(client -> hasWaypoint(rarer));
                BlockPos emerald = new BlockPos(-1, 65, 3);
                setBlock(context, world, emerald, Blocks.EMERALD_ORE.defaultBlockState());
                context.waitFor(client -> hasWaypoint(emerald));
                displayDistance(context, diamond);
                int originalCount = context.computeOnClient(client -> ConfigManager.current().alerts.displayedWaypointCount);
                context.runOnClient(client -> {
                    ConfigManager.current().alerts.displayedWaypointCount = 2;
                    check(BlockAlertManager.waypointsInRange(client.player.position()).size() == 3,
                            "The additional-waypoint count must include eligible entries beyond the display limit");
                });
                context.waitTicks(3);
                context.takeScreenshot("block-alert-waypoint-panel");
                context.runOnClient(client -> ConfigManager.current().alerts.displayedWaypointCount = originalCount);
                setBlock(context, world, emerald, Blocks.AIR.defaultBlockState());
                context.runOnClient(client -> {
                    var settings = ConfigManager.current().alerts;
                    int count = settings.displayedWaypointCount;
                    int distance = settings.waypointDisplayDistance;
                    settings.displayedWaypointCount = 1;
                    check(BlockAlertManager.waypointList(client.player.position()).getFirst().pos().equals(diamond),
                            "The nearest target must precede a farther rare target and respect the list limit");
                    settings.waypointDisplayDistance = 1;
                    check(BlockAlertManager.waypointList(client.player.position()).isEmpty(),
                            "Display distance must hide distant entries");
                    check(!hasWaypoint(diamond), "Distant entries must release discovery capacity");
                    settings.displayedWaypointCount = count;
                    settings.waypointDisplayDistance = distance;
                });
                context.waitFor(client -> hasWaypoint(diamond));
                setBlock(context, world, rarer, Blocks.AIR.defaultBlockState());
                setBlock(context, world, wall, Blocks.STONE.defaultBlockState());
                context.waitTicks(8);
                context.runOnClient(client -> {
                    check(hasWaypoint(diamond), "A discovered block remains remembered when hidden");
                    checkGlowDepth(true);
                    check(hasGlows(), "Remembered blocks must retain glow behind walls");
                });
                setBlock(context, world, wall, Blocks.AIR.defaultBlockState());
                context.waitFor(client -> hasWaypoint(diamond));
                setBlock(context, world, wall, Blocks.STONE.defaultBlockState());

                context.runOnClient(client -> {
                    var chunk = client.level.getChunkSource().getChunk(0, 0, ChunkStatus.FULL, false);
                    ClientChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(client.level, chunk);
                    check(hasWaypoint(diamond), "Chunk unload events must preserve remembered waypoints");
                    ClientChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(client.level, chunk);
                });
                setBlock(context, world, diamond, Blocks.AIR.defaultBlockState());
                context.waitFor(client -> !hasWaypoint(diamond));

                setBlock(context, world, wall, Blocks.AIR.defaultBlockState());
                BlockPos ruby = new BlockPos(1, 65, 3);
                BlockState correct = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 3)
                        .setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.HARP);
                setBlock(context, world, ruby, correct.setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.BASS));
                context.waitTicks(8);
                context.runOnClient(client -> check(!hasWaypoint(ruby), "Wrong instruments must not match"));
                setBlock(context, world, ruby, correct);
                context.waitFor(client -> hasWaypoint(ruby));
                setBlock(context, world, ruby, correct.setValue(NoteBlock.NOTE, 4));
                context.waitFor(client -> !hasWaypoint(ruby));
                setBlock(context, world, ruby, correct);
                context.waitFor(client -> hasWaypoint(ruby));
                monitorLifecycle(context, world, ruby, correct);
                context.runOnClient(client -> {
                    ConfigManager.current().alerts.targets.get("test_ruby").enabled = false;
                    check(!hasWaypoint(ruby), "Disabled targets must hide remembered waypoints");
                    checkNotStored("test_ruby");
                    ConfigManager.current().alerts.targets.get("test_ruby").enabled = true;
                });
                context.waitFor(client -> hasWaypoint(ruby));
                waypointLimit(context, world);
                world.getServer().runOnServer(server -> {
                    var player = world.getConnection().getServerPlayer();
                    player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(player.level()),
                            ClientboundRespawnPacket.KEEP_ALL_DATA));
                });
                context.waitTick();
                world.getConnection().waitForClientboundPackets();
                context.runOnClient(client -> check(BlockAlertManager.remembered().isEmpty(),
                        "A same-dimension world transfer must clear discovery memory"));
            }
            context.runOnClient(client -> check(BlockAlertManager.remembered().isEmpty(), "Disconnect must clear discovery memory"));
        } finally {
            context.runOnClient(client -> {
                preset.blockAlerts.categories.remove("TEST");
                if (surfaceAvailability == null) {
                    preset.features.remove(MiningFeature.SURFACE_TARGET_MINER);
                } else {
                    preset.features.put(MiningFeature.SURFACE_TARGET_MINER, surfaceAvailability);
                }
                preset.validate();
                ConfigManager.save(original);
                client.gui.setScreen(null);
            });
        }
    }

    private static void selectPreset(ClientGameTestContext context, String id) {
        context.runOnClient(client -> {
            for (int attempt = 0; attempt <= ConfigManager.presets().size(); attempt++) {
                var selector = client.gui.screen().children().stream()
                        .filter(child -> child instanceof ContainerObjectSelectionList<?>)
                        .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                        .flatMap(entry -> entry.children().stream()).filter(child -> child instanceof CycleButton<?>)
                        .map(child -> (CycleButton<?>) child).findFirst().orElseThrow();
                if (selector.getMessage().getString().equals(ConfigManager.preset(id).name)) return;
                selector.onPress(new MouseButtonInfo(0, 0));
            }
            throw new AssertionError("Preset must appear in the selector: " + id);
        });
    }

    private static void waypointLimit(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> ConfigManager.current().alerts.vanillaOreScanRadius = 128);
        BlockPos rare = new BlockPos(0, 70, 16);
        world.getServer().runCommand("fill -5 64 16 5 74 16 minecraft:diamond_ore");
        setBlock(context, world, rare, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
        context.waitFor(client -> BlockAlertManager.remembered().size() == 100 && hasWaypoint(rare));
        context.waitTicks(8);
        context.runOnClient(client -> {
            check(BlockAlertManager.waypointsInRange(client.player.position()).size() == 100,
                    "World markers must have a separate hard limit of 100");
            check(BlockAlertManager.waypointsInRange(client.player.position()).stream().anyMatch(wp -> wp.pos().equals(rare)),
                    "The rarest newly discovered target must survive overflow regardless of display order");
            try {
                var singleton = BlockAlertManager.class.getDeclaredField("INSTANCE");
                singleton.setAccessible(true);
                Object manager = singleton.get(null);
                var memory = BlockAlertManager.class.getDeclaredField("discovered");
                memory.setAccessible(true);
                check(((java.util.Map<?, ?>) memory.get(manager)).size() == 100,
                        "Discovery memory must stay bounded, not only the rendered list");
                var seen = BlockAlertManager.class.getDeclaredField("seen");
                seen.setAccessible(true);
                check(((java.util.Map<?, ?>) seen.get(manager)).size() > 100,
                        "The fixture must discover more blocks than the waypoint capacity");
                var tick = BlockAlertManager.class.getDeclaredField("visibilityTick");
                tick.setAccessible(true);
                tick.setLong(manager, Long.MIN_VALUE);
                BlockAlertManager.updateVisibility(client);
                var sound = BlockAlertManager.class.getDeclaredField("pendingSound");
                sound.setAccessible(true);
                check(sound.get(manager) == null, "Evicted waypoints must not repeat sounds while their chunk stays loaded");
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Cannot inspect bounded waypoint memory", exception);
            }
            check(BlockAlertManager.remembered().size() == 100, "Remembered waypoints must retain the discovery cap");
        });
        context.takeScreenshot("block-alert-waypoint-limit");
        world.getServer().runCommand("fill -5 64 16 5 74 16 minecraft:air");
        context.waitFor(client -> !hasWaypoint(rare));
    }

    private static void scanRadii(ClientGameTestContext context, TestSingleplayerContext world, BlockAlertTarget note) {
        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        BlockPos ore = new BlockPos(2, 65, 0);
        BlockPos custom = new BlockPos(-2, 65, 0);
        BlockPos distant = new BlockPos(0, 65, 8);
        BlockPos visible = new BlockPos(0, 65, 3);
        var noteState = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.INSTRUMENT, NoteBlockInstrument.HARP)
                .setValue(NoteBlock.NOTE, 3);
        try {
            context.runOnClient(client -> {
                ConfigManager.current().alerts.vanillaOreScanRadius = 1;
                ConfigManager.current().alerts.otherBlockScanRadius = 4;
            });
            setBlock(context, world, ore, Blocks.DIAMOND_ORE.defaultBlockState());
            setBlock(context, world, custom, noteState);
            setBlock(context, world, distant, Blocks.DIAMOND_ORE.defaultBlockState());
            context.waitTicks(12);
            context.runOnClient(client -> {
                check(!cachedTargets().containsKey(ore) && !cachedTargets().containsKey(distant), "Packets must not cache out-of-range ores");
                check(cachedTargets().get(custom) == note, "Custom blocks must use their independent radius");
                check(BlockAlertManager.surfaceTarget(ore, client.level.getBlockState(ore)) == null, "Surface mining must reject ores outside the radius");
                check(BlockAlertManager.surfaceTarget(custom, noteState) == note, "Surface mining must accept in-range custom blocks");
                var hit = new com.minermarmot.client.features.mining.SurfaceTargetMiner().findTarget(client, custom);
                check(hit != null && hit.getBlockPos().equals(custom), "The native surface selector must honor the custom radius");
                ConfigManager.current().alerts.vanillaOreScanRadius = 4;
                ConfigManager.current().alerts.otherBlockScanRadius = 1;
            });
            context.waitFor(client -> cachedTargets().containsKey(ore) && !cachedTargets().containsKey(custom));
            context.runOnClient(client -> {
                check(BlockAlertManager.surfaceTarget(custom, noteState) == null, "Shrinking the other-block radius must exclude its surface target");
                var hit = new com.minermarmot.client.features.mining.SurfaceTargetMiner().findTarget(client, custom);
                check(hit != null && hit.getBlockPos().equals(ore), "Even a preferred surface target must obey its radius");
            });
            setBlock(context, world, visible, Blocks.DIAMOND_ORE.defaultBlockState());
            context.waitFor(client -> hasWaypoint(visible));
            context.runOnClient(client -> ConfigManager.current().alerts.vanillaOreScanRadius = 1);
            context.waitTicks(8);
            context.runOnClient(client -> {
                check(!cachedTargets().containsKey(visible) && hasWaypoint(visible), "A radius reduction must prune candidates but preserve discoveries");
                var chunk = client.level.getChunk(0, 0);
                ClientChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(client.level, chunk);
                ClientChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(client.level, chunk);
            });
            context.waitTicks(8);
            context.runOnClient(client -> check(hasWaypoint(visible), "A bounded rescan must preserve valid out-of-range memory"));
            setBlock(context, world, visible, Blocks.AIR.defaultBlockState());
            context.runOnClient(client -> check(!hasWaypoint(visible), "Out-of-range packets must still remove destroyed discoveries"));
            world.getServer().runCommand("tp @p 0.5 64 7.5 0 0");
            world.getConnection().waitForClientboundPackets();
            context.runOnClient(client -> ConfigManager.current().alerts.vanillaOreScanRadius = 2);
            context.waitFor(client -> cachedTargets().containsKey(distant));
            context.runOnClient(client -> check(!cachedTargets().containsKey(ore), "Moving must prune the previous scan area"));
            world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
            world.getConnection().waitForClientboundPackets();
            world.getServer().runCommand("fill -16 48 -16 16 62 16 minecraft:diamond_ore");
            world.getConnection().waitForClientboundPackets();
            context.waitTicks(12);
            context.runOnClient(client -> {
                var found = cachedTargets();
                check(!found.isEmpty() && found.size() <= 33, "A dense ore field must retain only the radius-two sphere");
                check(found.entrySet().stream().allMatch(entry -> BlockAlertManager.withinScanRadius(entry.getKey(), client.player.blockPosition(),
                        entry.getValue().isVanillaOre() ? 2 : 1)), "Every cached target must obey its own 3D radius");
                System.out.println("Dense scan fixture: 16,335 ores, " + found.size() + " local candidates.");
            });
            world.getServer().runCommand("fill -16 48 -16 16 62 16 minecraft:air");
            world.getConnection().waitForClientboundPackets();
        } finally {
            for (BlockPos pos : new BlockPos[]{ore, custom, distant, visible}) setBlock(context, world, pos, Blocks.AIR.defaultBlockState());
            context.runOnClient(client -> check(ConfigManager.save(original), "Scan-radius checks must restore settings"));
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<BlockPos, BlockAlertTarget> cachedTargets() {
        try {
            var singleton = BlockAlertManager.class.getDeclaredField("INSTANCE");
            singleton.setAccessible(true);
            var field = BlockAlertManager.class.getDeclaredField("candidates");
            field.setAccessible(true);
            var chunks = (java.util.Map<Long, java.util.Map<BlockPos, BlockAlertTarget>>) field.get(singleton.get(null));
            var found = new java.util.HashMap<BlockPos, BlockAlertTarget>();
            chunks.values().forEach(found::putAll);
            return found;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect bounded scan candidates", exception);
        }
    }

    private static void monitorLifecycle(ClientGameTestContext context, TestSingleplayerContext world,
                                         BlockPos ruby, BlockState state) {
        context.runOnClient(client -> {
            ConfigManager.current().alerts.targets.get("test_ruby").enabled = false;
            check(!hasWaypoint(ruby), "Monitor OFF must remove remembered waypoints immediately");
            checkNotStored("test_ruby");
        });
        setBlock(context, world, ruby, Blocks.AIR.defaultBlockState());
        setBlock(context, world, ruby, state);
        context.runOnClient(client -> {
            var chunk = client.level.getChunk(ruby.getX() >> 4, ruby.getZ() >> 4);
            ClientChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(client.level, chunk);
        });
        context.waitTicks(8);
        context.runOnClient(client -> checkNotStored("test_ruby"));
        var active = context.computeOnClient(client -> ConfigManager.current().alerts.copy());
        context.runOnClient(client -> {
            ConfigManager.current().alerts.targets.values().forEach(settings -> settings.enabled = false);
            check(BlockAlertManager.remembered().isEmpty(), "All Monitor switches OFF must empty waypoint output");
        });
        context.waitTicks(8);
        context.runOnClient(client -> {
            ConfigManager.current().alerts.targets.keySet().forEach(BlockAlertsClientTest::checkNotStored);
            ConfigManager.current().alerts = active;
        });
        world.getServer().runCommand("fill 0 64 2 2 66 4 minecraft:stone");
        setBlock(context, world, ruby, state);
        context.runOnClient(client -> ConfigManager.current().alerts.targets.get("test_ruby").enabled = true);
        context.waitTicks(8);
        context.runOnClient(client -> check(!hasWaypoint(ruby), "Reactivation must discover again rather than restore old memory"));
        world.getServer().runCommand("fill 0 64 2 2 66 4 minecraft:air");
        setBlock(context, world, ruby, state);
        context.waitFor(client -> hasWaypoint(ruby));
    }

    private static void checkNotStored(String id) {
        try {
            var singleton = BlockAlertManager.class.getDeclaredField("INSTANCE");
            singleton.setAccessible(true);
            Object manager = singleton.get(null);
            for (String name : new String[]{"discovered", "seen", "candidates"}) {
                var field = BlockAlertManager.class.getDeclaredField(name);
                field.setAccessible(true);
                var values = ((java.util.Map<?, ?>) field.get(manager)).values().stream()
                        .flatMap(value -> value instanceof java.util.Map<?, ?> chunk ? chunk.values().stream()
                                : java.util.stream.Stream.of(value));
                check(values.noneMatch(value -> ((BlockAlertTarget) value).id.equals(id)),
                        "Monitor OFF must remove every cached position for " + id + " from " + name);
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect disabled-target caches", exception);
        }
    }

    private static void displayDistance(ClientGameTestContext context, BlockPos diamond) {
        var original = context.computeOnClient(client -> ConfigManager.current().alerts.copy());
        try {
            context.runOnClient(client -> {
                var settings = ConfigManager.current().alerts;
                settings.displayedWaypointCount = 0;
                settings.waypointDisplayDistance = 1;
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                check(BlockAlertManager.waypointsInRange(client.player.position()).isEmpty(), "Distant markers must be excluded");
                check(!hasGlows(), "Distant glow geometry must be excluded");
                check(!hasWaypoint(diamond), "Reducing display distance must free slots occupied by distant discoveries");
            });
            context.takeScreenshot("block-alert-distance-out");
            context.runOnClient(client -> ConfigManager.current().alerts.waypointDisplayDistance = original.waypointDisplayDistance);
            context.waitFor(client -> hasGlows());
            context.runOnClient(client -> {
                check(BlockAlertManager.waypointsInRange(client.player.position()).stream().anyMatch(wp -> wp.pos().equals(diamond)),
                        "Increasing distance must restore markers even when the list count is zero");
                check(BlockAlertManager.waypointList(client.player.position()).isEmpty(), "Zero count must still hide only the list");
                ConfigManager.current().alerts.waypoints = false;
            });
            context.waitTicks(2);
            context.runOnClient(client -> check(hasGlows(), "Glow must work with the Waypoints channel OFF"));
            context.takeScreenshot("block-alert-distance-glow-only");
            context.runOnClient(client -> ConfigManager.current().alerts.waypoints = true);
            context.waitTick();
            context.takeScreenshot("block-alert-distance-in");
            context.runOnClient(client -> {
                ConfigManager.current().alerts.waypoints = false;
                ConfigManager.current().alerts.waypointDisplayDistance = 1;
            });
            context.waitTicks(2);
            context.runOnClient(client -> check(!hasGlows(), "Distance must also limit glow with the Waypoints channel OFF"));
        } finally {
            context.runOnClient(client -> {
                var settings = ConfigManager.current().alerts;
                settings.displayedWaypointCount = original.displayedWaypointCount;
                settings.waypointDisplayDistance = original.waypointDisplayDistance;
                settings.waypoints = original.waypoints;
            });
        }
    }

    private static void checkGlowDepth(boolean hiddenEdges) {
        try {
            var field = BlockAlertRenderer.class.getDeclaredField("glows");
            field.setAccessible(true);
            var states = (java.util.List<?>) field.get(null);
            check(!states.isEmpty(), "Changing edges must keep visible targets eligible for glow");
            var mode = states.getFirst().getClass().getDeclaredMethod("hiddenEdges");
            mode.setAccessible(true);
            check(mode.invoke(states.getFirst()).equals(hiddenEdges), "The next extracted frame must use the saved edge mode");
            var lines = BlockAlertRenderer.class.getDeclaredField("VISIBLE_GLOW_LINES");
            lines.setAccessible(true);
            var pipeline = ((net.minecraft.client.renderer.rendertype.RenderType) lines.get(null)).pipeline();
            check(pipeline.getDepthStencilState() != null,
                    "Visible-only glow must retain world depth; hidden edges are drawn in the HUD overlay");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect glow render state", exception);
        }
    }

    private static boolean hasGlows() {
        try {
            var field = BlockAlertRenderer.class.getDeclaredField("glows");
            field.setAccessible(true);
            return !((java.util.List<?>) field.get(null)).isEmpty();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void liveSettings(ClientGameTestContext context) {
        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        try {
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.features");
            for (MiningFeature feature : MiningFeature.values()) {
                if (!ConfigManager.preset(original.preset).allows(feature)) {
                    continue;
                }
                search(context, Component.translatable(feature.translationKey()).getString());
                boolean enabled = context.computeOnClient(client -> MiningManager.enabled(feature));
                cycleListButton(context, feature.translationKey());
                context.runOnClient(client -> {
                    if (feature == MiningFeature.WORLD_BORDER) {
                        int index = MiningConfig.BORDER_DISTANCES.indexOf(original.mining.worldBorderDistance);
                        check(ConfigManager.current().mining.worldBorderDistance
                                        == MiningConfig.BORDER_DISTANCES.get((index + 1) % MiningConfig.BORDER_DISTANCES.size()),
                                "Border distance must apply immediately");
                    } else {
                        check(MiningManager.enabled(feature) != enabled, "Mining switch must apply immediately: " + feature);
                    }
                    checkSaved();
                });
            }
            search(context, Component.translatable(MiningFeature.WORLD_BORDER.translationKey()).getString());
            for (int attempt = 0; attempt < MiningConfig.BORDER_DISTANCES.size(); attempt++) {
                cycleListButton(context, MiningFeature.WORLD_BORDER.translationKey());
            }
            context.runOnClient(client -> {
                check(optionList(client.gui.screen()).children().stream().flatMap(entry -> entry.children().stream())
                                .noneMatch(child -> child instanceof EditBox), "OFF must hide the custom spacing field");
                check(ConfigManager.current().mining.worldBorderDistance == 0, "Custom mode must cycle back to OFF");
            });
            for (int attempt = 0; attempt < MiningConfig.BORDER_DISTANCES.size(); attempt++) {
                cycleListButton(context, MiningFeature.WORLD_BORDER.translationKey());
            }
            context.runOnClient(client -> {
                check(ConfigManager.current().mining.worldBorderDistance == 25,
                        "Selecting Custom must retain the previous spacing until a valid edit");
                check(optionList(client.gui.screen()).children().stream().flatMap(entry -> entry.children().stream())
                                .anyMatch(child -> child instanceof EditBox input && input.getValue().equals("25")),
                        "Custom must reveal an input with the current distance");
                checkSaved();
            });
            for (String value : new String[]{"1", "3", "2147483647", "12"}) {
                editListInput(context, value);
                context.runOnClient(client -> {
                    check(ConfigManager.current().mining.worldBorderDistance == Integer.parseInt(value),
                            "Custom spacing must apply immediately, including quick-pick values and the integer limit");
                    checkSaved();
                });
            }
            var validBorder = context.computeOnClient(client -> ConfigManager.current());
            for (String invalid : new String[]{"", "0", "-1", "1.5", "2147483648", "oops"}) {
                editListInput(context, invalid);
                context.runOnClient(client -> {
                    check(ConfigManager.current() == validBorder, "Invalid custom spacing must keep the last valid settings");
                    checkSaved();
                });
            }
            editListInput(context, "12");
            context.waitTick();
            context.runOnClient(client -> checkLayout(client.gui.screen(), 720));
            context.takeScreenshot("world-border-custom");
            context.clickScreenButton("minermarmot.config.quit");
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.features");
            search(context, Component.translatable(MiningFeature.WORLD_BORDER.translationKey()).getString());
            context.runOnClient(client -> check(optionList(client.gui.screen()).children().stream()
                            .flatMap(entry -> entry.children().stream())
                            .anyMatch(child -> child instanceof EditBox input && input.getValue().equals("12")),
                    "Reopening the menu must restore a custom distance and its input"));
            cycleListButton(context, MiningFeature.WORLD_BORDER.translationKey());
            cycleListButton(context, MiningFeature.WORLD_BORDER.translationKey());
            context.runOnClient(client -> {
                check(ConfigManager.current().mining.worldBorderDistance == 3, "Quick picks must work after Custom");
                check(optionList(client.gui.screen()).children().stream().flatMap(entry -> entry.children().stream())
                                .noneMatch(child -> child instanceof EditBox), "Quick picks must hide the custom field");
            });
            String[] channels = {"sounds", "waypoints", "glow"};
            for (String channel : channels) {
                search(context, Component.translatable("minermarmot.alerts." + channel).getString());
                cycleListButton(context, "minermarmot.alerts." + channel);
                context.runOnClient(client -> {
                    var current = ConfigManager.current().alerts;
                    check(switch (channel) {
                        case "sounds" -> current.sounds != original.alerts.sounds;
                        case "waypoints" -> current.waypoints != original.alerts.waypoints;
                        default -> current.glow != original.alerts.glow;
                    }, "Alert channel must apply immediately: " + channel);
                    checkSaved();
                });
            }
            String[] numbers = {"soundDelay", "waypointCount", "waypointDistance", "vanillaOreScanRadius", "otherBlockScanRadius"};
            int[] values = {57, 4, 32, 8, 48};
            for (int index = 0; index < numbers.length; index++) {
                String key = numbers[index];
                int value = values[index];
                search(context, Component.translatable("minermarmot.alerts." + key).getString());
                editListInput(context, "minermarmot.alerts." + key, Integer.toString(value));
                context.runOnClient(client -> {
                    var current = ConfigManager.current().alerts;
                    check(switch (key) {
                        case "soundDelay" -> current.soundDelayTicks == value;
                        case "waypointCount" -> current.displayedWaypointCount == value;
                        case "vanillaOreScanRadius" -> current.vanillaOreScanRadius == value;
                        case "otherBlockScanRadius" -> current.otherBlockScanRadius == value;
                        default -> current.waypointDisplayDistance == value;
                    }, "Numeric setting must apply immediately: " + key);
                    checkSaved();
                });
                var valid = context.computeOnClient(client -> ConfigManager.current());
                for (String invalid : key.endsWith("ScanRadius")
                        ? new String[]{"", "0", "-1", "129", "1.5", "2147483648", "oops"}
                        : new String[]{"", "-1", "999999", "oops"}) {
                    editListInput(context, "minermarmot.alerts." + key, invalid);
                    context.runOnClient(client -> check(ConfigManager.current() == valid,
                            "Invalid numeric input must keep the last valid configuration"));
                }
            }
            search(context, "scan");
            context.waitTick();
            context.takeScreenshot("block-scan-radii");
            search(context, "Test Ruby");
            editListInput(context, "#12ab34");
            context.runOnClient(client -> check(ConfigManager.current().alerts.targets.get("test_ruby").color.equals("#12AB34"),
                    "Valid colors must apply immediately"));
            editListInput(context, "#oops");
            cycleListButton(context, "minermarmot.alerts.glow");
            cycleListButton(context, "minermarmot.alerts.surfaceMine");
            cycleListButton(context, "minermarmot.alerts.enabled");
            context.runOnClient(client -> {
                var current = ConfigManager.current().alerts.targets.get("test_ruby");
                var previous = original.alerts.targets.get("test_ruby");
                check(current.color.equals("#12AB34"), "Invalid color input must keep the last valid color");
                check(current.glow != previous.glow && current.surfaceMine != previous.surfaceMine && current.enabled != previous.enabled,
                        "All target switches must apply despite another field's invalid input");
                checkSaved();
            });
            clickListButton(context, "minermarmot.alerts.sound.pling");
            clickListButton(context, "minermarmot.alerts.sound.bell");
            context.runOnClient(client -> {
                check(client.gui.screen() instanceof ConfigScreen, "Choosing a sound must return to the configuration menu");
                check(ConfigManager.current().alerts.targets.get("test_ruby").sound.equals("minecraft:block.note_block.bell"),
                        "Sound selection must apply immediately");
                checkSaved();
            });
            clickListButton(context, "minermarmot.alerts.sound.bell");
            context.clickScreenButton("minermarmot.alerts.noSound");
            context.runOnClient(client -> {
                check(ConfigManager.current().alerts.targets.get("test_ruby").sound.isEmpty(),
                        "No sound must apply immediately");
                check(optionList(client.gui.screen()).children().stream().flatMap(entry -> entry.children().stream())
                                .filter(child -> child instanceof Button).map(child -> (Button) child)
                                .anyMatch(button -> button.getMessage().getString().equals(SoundSelectionScreen.label("").getString())
                                        && button.getMessage().getStyle().getColor().getValue() == 0xBBBBBB),
                        "A target with no sound must display a gray label");
                checkSaved();
                client.gui.screen().onClose();
            });
            context.setScreen(() -> new ConfigScreen(null));
            context.runOnClient(client -> check(ConfigManager.current().alerts.targets.get("test_ruby").color.equals("#12AB34"),
                    "Closing and reopening must retain edits"));
            context.clickScreenButton("minermarmot.config.resetPreset");
            context.runOnClient(client -> {
                var current = ConfigManager.current();
                var preset = ConfigManager.preset(current.preset);
                check(current.mining.enabled.equals(preset.defaults.enabled)
                                && current.mining.worldBorderDistance == preset.defaults.worldBorderDistance,
                        "Reset must apply mining defaults immediately");
                var gson = new com.google.gson.Gson();
                check(gson.toJsonTree(current.alerts).equals(gson.toJsonTree(preset.blockAlerts.newConfig())),
                        "Reset must apply all alert defaults immediately");
                checkSaved();
            });
            context.clickScreenButton("minermarmot.config.quit");
        } finally {
            context.runOnClient(client -> {
                check(ConfigManager.save(original), "Live settings tests must restore their configuration");
                client.gui.setScreen(null);
            });
        }
    }

    static void search(ClientGameTestContext context, String query) {
        context.runOnClient(client -> client.gui.screen().children().stream()
                .filter(child -> child instanceof EditBox).map(child -> (EditBox) child).findFirst().orElseThrow().setValue(query));
    }

    private static void configurationLayout(ClientGameTestContext context) {
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            for (int scale : new int[]{1, 2}) {
                context.runOnClient(client -> {
                    client.options.guiScale().set(scale);
                    client.resizeGui();
                });
                context.waitTick();
                context.runOnClient(client -> {
                    checkLayout(client.gui.screen(), 720);
                    var list = optionList(client.gui.screen());
                    check(list.children().size() == 1 && list.children().getFirst().children().size() == 1,
                            "General must only list the preset selector, with no mining action");
                    var reset = client.gui.screen().children().stream().filter(child -> child instanceof Button)
                            .map(child -> (Button) child).filter(button -> button.getMessage().getString()
                                    .equals(Component.translatable("minermarmot.config.resetPreset").getString())).findFirst().orElseThrow();
                    check(reset.getY() > list.getBottom() && reset.getBottom() < client.gui.screen().height - 28,
                            "Reset must stay below the list and above Quit");
                    check(client.getResourceManager().getResource(net.minecraft.resources.Identifier
                            .fromNamespaceAndPath("minermarmot", "icon.png")).isPresent(), "The configuration logo must be available");
                });
                context.takeScreenshot("configuration-general-scale-" + scale);
                context.clickScreenButton("minermarmot.config.features");
                context.waitTick();
                context.runOnClient(client -> checkLayout(client.gui.screen(), 720));
                context.takeScreenshot("configuration-features-scale-" + scale);
                context.clickScreenButton("minermarmot.config.general");
            }
        } finally {
            context.runOnClient(client -> {
                client.options.guiScale().set(originalScale);
                client.resizeGui();
            });
        }
    }

    private static void checkLayout(Screen screen, int maxWidth) {
        var list = screen.children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).findFirst().orElseThrow();
        check(list.getWidth() <= maxWidth && list.getX() == (screen.width - list.getWidth()) / 2,
                "The configuration list must stay centered and bounded");
        screen.children().stream().filter(child -> child instanceof Button).map(child -> (Button) child)
                .filter(button -> button.getY() == 28).forEach(button ->
                        check(button.getRight() <= list.getRight() - 40, "Tabs must leave room for the panel-aligned logo"));
        for (var entry : list.children()) {
            if (entry.getContentY() < list.getY() || entry.getContentY() >= list.getBottom()) {
                continue;
            }
            for (var child : entry.children()) {
                if (child instanceof AbstractWidget widget) {
                    check(widget.getX() >= list.getX() && widget.getRight() <= list.getRight(),
                            "Configuration controls must stay inside the centered list");
                    if (widget instanceof CycleButton<?> button) {
                        check(button.getWidth() <= 140, "Setting values must use compact buttons");
                        check(button.createDefaultNarrationMessage().getString().contains(":"),
                                "Compact values must retain the setting name for narration");
                        if (button.getValue() instanceof Boolean || button.getValue() instanceof Integer) {
                            check(button.getWidth() == 70, "Mining switches and world border spacing must share a width");
                        }
                        if (button.getValue() instanceof Integer && button.createDefaultNarrationMessage().getString()
                                .contains(Component.translatable(MiningFeature.WORLD_BORDER.translationKey()).getString() + ":")) {
                            @SuppressWarnings("unchecked")
                            var border = (CycleButton<Integer>) button;
                            int original = border.getValue();
                            border.setValue(0);
                            check(border.getMessage().getString().equals(net.minecraft.network.chat.CommonComponents.OPTION_OFF.getString())
                                            && border.getMessage().getStyle().getColor().getValue() == 0xBBBBBB,
                                    "World border OFF must match the other switches and use gray text");
                            border.setValue(original);
                        }
                    } else if (widget instanceof Button) {
                        check(widget.getWidth() <= 340, "Action buttons must not stretch across the screen");
                    }
                }
            }
        }
    }

    private static ContainerObjectSelectionList<?> optionList(Screen screen) {
        return screen.children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).findFirst().orElseThrow();
    }

    private static void scrollToBottom(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var list = optionList(client.gui.screen());
            list.setScrollAmount(list.maxScrollAmount());
        });
    }

    private static void checkBlockTable(Screen screen, boolean monitored) {
        var list = optionList(screen);
        var row = list.children().getLast();
        check(row.children().stream().filter(child -> child instanceof CycleButton<?>).count() == 3,
                "The target row must expose separate Glow, Mine and Monitor controls");
        for (var child : row.children()) {
            var widget = (AbstractWidget) child;
            check(widget.getX() >= list.getX() && widget.getRight() <= list.getRight(), "All table columns must fit the panel");
            check(widget.active, "Dimmed target preferences must remain editable");
            boolean monitor = widget instanceof CycleButton<?> button && button.createDefaultNarrationMessage().getString()
                    .contains(Component.translatable("minermarmot.alerts.enabled").getString() + ":");
            check(widget.getAlpha() == (monitored || monitor ? 1.0f : 0.45f), "Monitoring OFF must dim the row and retain its switch");
            if (widget instanceof CycleButton<?> button) {
                check(!button.getMessage().getString().contains(":"), "Table switches must only show their value");
                check(button.getMessage().getStyle().getColor().getValue()
                                == (Boolean.TRUE.equals(button.getValue()) ? 0xFFFFFF : 0xBBBBBB),
                        "ON must use white text and OFF must use gray text");
            }
        }
        for (String column : new String[]{"ore", "color", "sound", "glow", "mine", "monitor"}) {
            check(!Component.translatable("minermarmot.alerts.column." + column).getString().startsWith("minermarmot."),
                    "Table column names must be localized");
        }
    }

    private static void minimumWidthTable(ClientGameTestContext context) {
        int windowWidth = context.computeOnClient(client -> client.getWindow().getScreenWidth());
        int windowHeight = context.computeOnClient(client -> client.getWindow().getScreenHeight());
        try {
            context.runOnClient(client -> {
                client.getWindow().setWindowed(640, 480);
                client.options.guiScale().set(2);
            });
            context.waitFor(client -> client.gui.screen().width == 320);
            scrollToBottom(context);
            context.waitTick();
            context.runOnClient(client -> {
                checkLayout(client.gui.screen(), 720);
                checkBlockTable(client.gui.screen(), true);
            });
            context.takeScreenshot("block-alert-settings-minimum");
        } finally {
            context.runOnClient(client -> {
                client.getWindow().setWindowed(windowWidth, windowHeight);
                client.options.guiScale().set(1);
            });
            context.waitFor(client -> client.gui.screen().width == windowWidth);
        }
    }

    private static void editListInput(ClientGameTestContext context, String value) {
        editListInput(context, null, value);
    }

    private static void editListInput(ClientGameTestContext context, String key, String value) {
        context.runOnClient(client -> client.gui.screen().children().stream()
                .filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                .flatMap(entry -> entry.children().stream()).filter(child -> child instanceof EditBox)
                .map(child -> (EditBox) child)
                .filter(input -> key == null || input.getMessage().getString().equals(Component.translatable(key).getString()))
                .findFirst().orElseThrow().setValue(value));
    }

    static void blockChannelTable(ClientGameTestContext context) {
        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        var availability = ConfigManager.preset(original.preset).blockAlerts;
        boolean sounds = availability.sounds;
        boolean waypoints = availability.waypoints;
        boolean glow = availability.glow;
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        int windowWidth = context.computeOnClient(client -> client.getWindow().getScreenWidth());
        int windowHeight = context.computeOnClient(client -> client.getWindow().getScreenHeight());
        try {
            context.setScreen(() -> new ConfigScreen(null));
            context.clickScreenButton("minermarmot.config.features");
            for (int testScale : new int[]{1, 2}) {
                context.runOnClient(client -> {
                    client.options.guiScale().set(testScale);
                    client.resizeGui();
                });
                for (String key : new String[]{"sounds", "waypoints", "glow"}) {
                    search(context, Component.translatable("minermarmot.alerts." + key).getString());
                    scrollToBottom(context);
                    context.waitTick();
                    context.runOnClient(client -> checkBlockChannelRow(client.gui.screen(), key));
                    if (key.equals("glow")) {
                        cycleListButton(context, "minermarmot.alerts.glowHiddenEdges");
                        context.runOnClient(client -> {
                            check(!ConfigManager.current().alerts.glowHiddenEdges, "Hidden edges must apply immediately");
                            checkSaved();
                        });
                        context.takeScreenshot("glow-hidden-edges-scale-" + testScale);
                    }
                    cycleListButton(context, "minermarmot.alerts." + key);
                    context.waitTick();
                    context.runOnClient(client -> {
                        checkBlockChannelRow(client.gui.screen(), key);
                        checkSaved();
                    });
                    if (key.equals("waypoints")) {
                        boolean before = context.computeOnClient(client -> ConfigManager.current().alerts.waypointSortByRarity);
                        cycleListButton(context, "minermarmot.alerts.waypointSort");
                        context.runOnClient(client -> {
                            check(ConfigManager.current().alerts.waypointSortByRarity != before,
                                    "Sorting must remain clickable with waypoints OFF");
                            checkSaved();
                        });
                        context.takeScreenshot("block-channel-waypoints-scale-" + testScale);
                    }
                    cycleListButton(context, "minermarmot.alerts." + key);
                    if (key.equals("glow")) {
                        context.runOnClient(client -> check(!ConfigManager.current().alerts.glowHiddenEdges,
                                "Turning Glow OFF and ON must preserve its edge preference"));
                        cycleListButton(context, "minermarmot.alerts.glowHiddenEdges");
                    }
                }
            }
            context.runOnClient(client -> {
                client.options.guiScale().set(1);
                client.resizeGui();
            });
            search(context, "");
            clickSection(context, Component.translatable("minermarmot.config.mining").getString());
            clickSection(context, Component.translatable("minermarmot.config.blockAlerts").getString());
            context.waitTick();
            context.runOnClient(client -> {
                for (String key : new String[]{"sounds", "waypoints", "glow"}) {
                    checkBlockChannelRow(client.gui.screen(), key);
                }
            });
            context.takeScreenshot("block-channel-overview");
            context.runOnClient(client -> {
                client.getWindow().setWindowed(640, 480);
                client.options.guiScale().set(2);
            });
            context.waitFor(client -> client.gui.screen().width == 320);
            search(context, Component.translatable("minermarmot.alerts.glowHiddenEdges").getString());
            scrollToBottom(context);
            context.waitTick();
            context.runOnClient(client -> checkBlockChannelRow(client.gui.screen(), "glow"));
            context.takeScreenshot("glow-hidden-edges-minimum");
            search(context, Component.translatable("minermarmot.alerts.waypointDistance").getString());
            scrollToBottom(context);
            context.waitTick();
            context.runOnClient(client -> checkBlockChannelRow(client.gui.screen(), "waypoints"));
            cycleListButton(context, "minermarmot.alerts.waypoints");
            context.waitTick();
            context.runOnClient(client -> checkBlockChannelRow(client.gui.screen(), "waypoints"));
            context.takeScreenshot("block-channel-waypoints-minimum-off");
            editListInput(context, "minermarmot.alerts.waypointDistance", "oops");
            context.waitTick();
            context.runOnClient(client -> {
                var input = optionList(client.gui.screen()).children().stream().flatMap(entry -> entry.children().stream())
                        .filter(child -> child instanceof EditBox box && box.getMessage().getString()
                                .equals(Component.translatable("minermarmot.alerts.waypointDistance").getString()))
                        .map(child -> (EditBox) child).findFirst().orElseThrow();
                check(inputTextColor(input) == 0xFFFF5555, "Invalid OFF-row input must remain red");
                checkSaved();
            });
            context.runOnClient(client -> {
                availability.sounds = false;
                availability.waypoints = false;
                availability.glow = false;
            });
            for (String key : new String[]{"sounds", "soundDelay", "waypoints", "waypointCount", "waypointDistance", "glow"}) {
                search(context, Component.translatable("minermarmot.alerts." + key).getString());
                context.runOnClient(client -> check(optionList(client.gui.screen()).children().stream()
                                .flatMap(entry -> entry.children().stream()).noneMatch(child -> child instanceof AbstractWidget widget
                                        && (widget instanceof EditBox && widget.getMessage().getString().equals(Component.translatable("minermarmot.alerts." + key).getString())
                                        || widget instanceof CycleButton<?> button && button.createDefaultNarrationMessage().getString()
                                                .contains(Component.translatable("minermarmot.alerts." + key).getString() + ":"))),
                        "Unavailable block channels and their settings must be hidden: " + key));
            }
            for (String key : new String[]{"channelColumn.function", "channelColumn.settings", "playerColumn.enabled"}) {
                check(!Component.translatable("minermarmot.alerts." + key).getString().startsWith("minermarmot."),
                        "Block channel columns must be localized");
            }
        } finally {
            context.runOnClient(client -> {
                availability.sounds = sounds;
                availability.waypoints = waypoints;
                availability.glow = glow;
                check(ConfigManager.save(original), "Channel table tests must restore the original preferences");
                client.getWindow().setWindowed(windowWidth, windowHeight);
                client.options.guiScale().set(scale);
            });
            context.waitFor(client -> client.getWindow().getScreenWidth() == windowWidth);
        }
    }

    private static void checkBlockChannelRow(Screen screen, String key) {
        var list = optionList(screen);
        var rows = list.children().stream().filter(entry -> entry.children().stream()
                .anyMatch(child -> child instanceof CycleButton<?> button && button.createDefaultNarrationMessage().getString()
                        .contains(Component.translatable("minermarmot.alerts." + key).getString() + ":"))).toList();
        check(rows.size() == 1, "Each block channel must occupy one table row");
        var widgets = rows.getFirst().children().stream().map(child -> (AbstractWidget) child).toList();
        int inputCount = key.equals("waypoints") ? 2 : key.equals("sounds") ? 1 : 0;
        var enabled = (CycleButton<?>) widgets.getLast();
        boolean active = Boolean.TRUE.equals(enabled.getValue());
        check(widgets.size() == inputCount + 1 + (key.equals("sounds") || key.equals("waypoints") || key.equals("glow") && active ? 1 : 0),
                "Channel rows must include waypoint sorting and show glow edge settings only when ON");
        if (!key.equals("glow") || active) {
            var side = widgets.get(widgets.size() - 2);
            check(side.getWidth() == 90 && side.getRight() + 6 == enabled.getX() && side.getY() == enabled.getY(),
                    "Volume, waypoint sorting and hidden edges must share the same column next to ON/OFF");
        }
        if (key.equals("waypoints")) {
            check(((CycleButton<?>) widgets.get(widgets.size() - 2)).getValue() instanceof String,
                    "Waypoint sorting must use neutral choices rather than an ON/OFF indicator");
        }
        for (var widget : widgets) {
            check(widget.getX() >= list.getX() && widget.getRight() <= list.getRight(), "Block channel controls must fit the panel");
            check(widget.active, "OFF channel settings must remain editable");
            check(widget.getAlpha() == (active || widget == enabled ? 1.0f : 0.45f), "OFF must dim settings and preserve its switch");
            if (widget instanceof EditBox input) {
                check(inputTextColor(input) == (active ? 0xFFE0E0E0 : 0xFF777777), "OFF must gray numeric values as well as labels");
            }
            for (var other : widgets) {
                check(widget == other || widget.getRight() <= other.getX() || other.getRight() <= widget.getX()
                                || widget.getBottom() <= other.getY() || other.getBottom() <= widget.getY(),
                        "Block channel controls must not overlap");
            }
        }
        check(enabled.getWidth() == 70 && enabled.getMessage().getStyle().getColor().getValue() == (active ? 0xFFFFFF : 0xBBBBBB),
                "Channel switches must retain the shared width and ON/OFF text colors");
    }

    private static int inputTextColor(EditBox input) {
        try {
            var field = EditBox.class.getDeclaredField("textColor");
            field.setAccessible(true);
            return field.getInt(input);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect the native input text color", exception);
        }
    }

    static void cycleListButton(ClientGameTestContext context, String key) {
        context.runOnClient(client -> client.gui.screen().children().stream()
                .filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                .flatMap(entry -> entry.children().stream()).filter(child -> child instanceof CycleButton<?>)
                .map(child -> (CycleButton<?>) child)
                .filter(button -> button.createDefaultNarrationMessage().getString().contains(Component.translatable(key).getString() + ":"))
                .findFirst().orElseThrow().onPress(new MouseButtonInfo(0, 0)));
    }

    static void checkSaved() {
        try {
            var file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minermarmot/config.json");
            check(com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file))
                            .equals(new com.google.gson.Gson().toJsonTree(ConfigManager.current())),
                    "Every applied change must already be saved while the menu is open");
        } catch (java.io.IOException exception) {
            throw new AssertionError("Cannot inspect saved settings", exception);
        }
    }

    private static boolean hasWaypoint(BlockPos pos) {
        return BlockAlertManager.remembered().stream().anyMatch(waypoint -> waypoint.pos().equals(pos));
    }

    private static boolean hasSurfaceMineButton(Screen screen) {
        String label = Component.translatable("minermarmot.alerts.surfaceMine").getString() + ":";
        return screen.children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                .flatMap(entry -> entry.children().stream())
                .anyMatch(child -> child instanceof CycleButton<?> button && button.createDefaultNarrationMessage().getString().contains(label));
    }

    private static int listRowCount(Screen screen) {
        return screen.children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).mapToInt(list -> list.children().size()).sum();
    }

    private static java.util.List<ConfigSectionWidget> sections(Screen screen) {
        return screen.children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                .flatMap(entry -> entry.children().stream()).filter(child -> child instanceof ConfigSectionWidget)
                .map(child -> (ConfigSectionWidget) child).toList();
    }

    static void clickSection(ClientGameTestContext context, String name) {
        context.runOnClient(client -> sections(client.gui.screen()).stream()
                .filter(section -> section.getMessage().getString().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing section: " + name)).onPress(new MouseButtonInfo(0, 0)));
    }

    static void clickListButton(ClientGameTestContext context, String key) {
        context.runOnClient(client -> {
            String label = Component.translatable(key).getString();
            Button button = client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                    .map(child -> (ContainerObjectSelectionList<?>) child).flatMap(list -> list.children().stream())
                    .flatMap(entry -> entry.children().stream()).filter(child -> child instanceof Button)
                    .map(child -> (Button) child).filter(child -> child.getMessage().getString().equals(label))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing list button: " + key));
            button.onPress(new MouseButtonInfo(0, 0));
        });
    }

    private static void setBlock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos pos, BlockState state) {
        world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().level().setBlock(pos, state, 3));
        context.waitTick();
        world.getConnection().waitForClientboundPackets();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

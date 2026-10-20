package com.minermarmot.client.features.tnt;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class TntSelfTest {
    private static final Item RED_SHULKER = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
            net.minecraft.resources.Identifier.withDefaultNamespace("red_shulker_box"));
    public static void run() {
        check(TntManager.needsReload(0, TntMode.BUILDER), "Empty inventory must reload");
        check(TntManager.needsReload(15, TntMode.BUILDER), "Under 16 TNT must reload");
        check(TntManager.needsReload(16, TntMode.BUILDER), "Exactly 16 TNT must reload before using the rod");
        check(!TntManager.needsReload(17, TntMode.BUILDER), "17 TNT permits building");
        check(TntManager.shouldRetreat(1.9), "Retreat when closer than four blocks");
        check(TntManager.shouldRetreat(3.9), "Keep retreating until four blocks");
        check(!TntManager.shouldRetreat(4.0), "Stop retreating at four blocks");
        check(!TntManager.shouldRetreat(4.5), "Do not retreat farther from a distant wall");
        check(TntManager.needsReload(0, TntMode.CARPET), "Carpet reloads only after inventory exhaustion");
        check(!TntManager.needsReload(1, TntMode.CARPET), "Last TNT must be used before buying");
        testPresets();
        testMineritePurchase();
        testPlacementTargets();
        testStraightMovement();
        check(TntManager.cardinalYaw(34) == 0 && TntManager.cardinalYaw(62) == 90,
                "Snap the initial facing to the nearest cardinal direction");
        check(TntManager.cardinalYaw(-104) == -90 && TntManager.cardinalYaw(170) == 180,
                "Negative and north-facing rotations must also snap correctly");
        Vec3 moving = new Vec3(-2.8, 12.1, 7.3);
        for (float heading : new float[]{0, 180}) {
            check(TntManager.centeredPosition(moving, heading, -2.5).equals(new Vec3(-2.5, 12.1, 7.3)),
                    "North/south centering must preserve backward progress and player height");
        }
        for (float heading : new float[]{90, -90}) {
            check(TntManager.centeredPosition(moving, heading, 7.5).equals(new Vec3(-2.8, 12.1, 7.5)),
                    "East/west centering must preserve backward progress and player height");
        }
        check(TntManager.centeredPosition(new Vec3(-1.1, 12.1, 8.6), 0, -2.5)
                        .equals(new Vec3(-2.5, 12.1, 8.6)),
                "Sideways drift must return to the original lane, without resetting travel progress");
        ItemStack rod = testStack(Items.BREEZE_ROD);
        check(!TntManager.isBuilderRod(rod, "Baguette du bâtisseur"), "Ordinary breeze rods must not be used");
        rod.set(DataComponents.CUSTOM_NAME, Component.literal("Baguette du bâtisseur"));
        check(TntManager.isBuilderRod(rod, "Baguette du bâtisseur"), "Named builder rod must be accepted");
        ItemStack wrongItem = testStack(Items.STICK);
        wrongItem.set(DataComponents.CUSTOM_NAME, Component.literal("Baguette du bâtisseur"));
        check(!TntManager.isBuilderRod(wrongItem, "Baguette du bâtisseur"), "Name alone must not select a different item");

        // The original reference searched ALL menu slots, risking a click on the player's own box.
        SimpleContainer inventory = new SimpleContainer(1);
        SimpleContainer shop = new SimpleContainer(1);
        inventory.setItem(0, testStack(RED_SHULKER));
        TestMenu menu = new TestMenu(inventory, shop);
        check(TntManager.findShopSlot(menu, inventory, RED_SHULKER) == -1,
                "Player shulkers must never be clicked as shop offers");
        shop.setItem(0, testStack(RED_SHULKER));
        check(TntManager.findShopSlot(menu, inventory, RED_SHULKER) == 1,
                "Click only the shop's red shulker");
        SimpleContainer delivered = new SimpleContainer(36);
        delivered.setItem(0, testStack(RED_SHULKER));
        int[] before = new int[36];
        before[0] = 1;
        check(TntManager.findDeliverySlot(delivered, before, RED_SHULKER) == -1,
                "An existing shulker is not proof of purchase");
        delivered.setItem(20, testStack(RED_SHULKER));
        check(TntManager.findDeliverySlot(delivered, before, RED_SHULKER) == 20,
                "Select the newly delivered shulker outside the hotbar, not a pre-existing one");
        delivered.setItem(0, ItemStack.EMPTY);
        check(TntManager.findDeliverySlot(delivered, before, RED_SHULKER) == -1,
                "Moving a pre-existing shulker must not be mistaken for a delivery");
        SimpleContainer ammunition = new SimpleContainer(36);
        ammunition.setItem(20, testStack(Items.TNT));
        check(TntManager.findItemSlot(ammunition, Items.TNT) == 20, "Auto-swap must search outside the hotbar");
        ammunition.setItem(2, testStack(Items.TNT));
        check(TntManager.findItemSlot(ammunition, Items.TNT) == 2, "Auto-swap prefers the hotbar");
        check(TntManager.countItem(ammunition, Items.TNT) == 2, "Every main inventory TNT stack counts");
        SimpleContainer offhand = new SimpleContainer(41);
        offhand.setItem(40, testStack(Items.TNT));
        check(TntManager.findItemSlot(offhand, Items.TNT) == 40, "Auto-swap must also use offhand ammunition");
        offhand.setItem(0, testStack(Items.TNT));
        check(TntManager.findItemSlot(offhand, Items.TNT) == 0, "Main inventory ammunition takes priority over offhand");
        SimpleContainer box = new SimpleContainer(27);
        SimpleContainer playerItems = new SimpleContainer(1);
        for (int i = 0; i < 27; i++) box.setItem(i, testStack(Items.TNT));
        box.setItem(13, testStack(Items.STICK));
        playerItems.setItem(0, testStack(Items.TNT));
        TestMenu boxMenu = new TestMenu(box, playerItems);
        var moved = new java.util.ArrayList<Integer>();
        TntManager.quickMoveTnt(boxMenu, slot -> {
            moved.add(slot);
            boxMenu.getSlot(slot).set(ItemStack.EMPTY);
        });
        check(moved.size() == 26 && new java.util.HashSet<>(moved).size() == 26,
                "All TNT stacks must be queued exactly once in a single batch");
        check(moved.contains(0) && moved.contains(26) && !moved.contains(13) && !moved.contains(27),
                "Batch must include the entire box but exclude other items and player inventory");
        check(box.getItem(13).is(Items.STICK) && playerItems.getItem(0).is(Items.TNT),
                "Unrelated items must remain untouched");
        TntManager.quickMoveTnt(boxMenu, moved::add);
        check(moved.size() == 26, "An emptied box must not queue more transfers");
        System.out.println("TNT regressions passed: fixed-pitch five-column sweep, floor-only placement, thresholds, rod identity, shop ownership, delivery, transfer.");
    }

    private static void testStraightMovement() {
        for (float heading : new float[]{0, 90, 180, 270}) {
            for (float offset : new float[]{-38, -15, 0, 15, 38}) {
                var input = TntManager.backwardInput(heading, heading + offset);
                double camera = Math.toRadians(heading + offset);
                double x = input.x * Math.cos(camera) - input.y * Math.sin(camera);
                double z = input.y * Math.cos(camera) + input.x * Math.sin(camera);
                check(Math.abs(x - Math.sin(Math.toRadians(heading))) < 0.00001
                        && Math.abs(z + Math.cos(Math.toRadians(heading))) < 0.00001,
                        "Backward movement must preserve its heading throughout the camera sweep");
            }
        }
    }

    private static void testPresets() {
        var bundled = com.minermarmot.client.config.ConfigManager.parsePreset("""
                {"name":"TNT", "tnt":{"builder":true,"carpet":true,
                 "builderWandName":"Custom wand", "shopClicks":["red_shulker_box","lime_stained_glass_pane"]}}
                """);
        check(bundled.tnt.modes().size() == 2, "Both preset modes must be available");
        check(bundled.tnt.shopClicks.equals(java.util.List.of("minecraft:red_shulker_box", "minecraft:lime_stained_glass_pane")),
                "Shop steps must retain order and normalize bare item names");
        var inherited = com.minermarmot.client.config.ConfigManager.parsePreset("{\"name\":\"Old TNT\"}", bundled);
        check(inherited.tnt.builder && inherited.tnt.builderWandName.equals("Custom wand"), "Old bundled files inherit TNT in memory");
        inherited.tnt.shopClicks.clear();
        check(bundled.tnt.shopClicks.size() == 2, "Migration must copy the sequence");
        var disabled = com.minermarmot.client.config.ConfigManager.parsePreset("{\"name\":\"Disabled\",\"tnt\":{}}", bundled);
        check(disabled.tnt.modes().isEmpty(), "Explicit TNT restrictions take precedence");
        var custom = com.minermarmot.client.config.ConfigManager.parsePreset("{\"name\":\"Custom\"}");
        check(custom.tnt.modes().isEmpty(), "Custom presets without TNT cannot run it");
        var config = new com.minermarmot.client.config.ClientConfig();
        config.applyPreset("tnt", bundled);
        config.tntMode = TntMode.CARPET;
        var copy = config.copy();
        var gson = new com.google.gson.Gson();
        var restored = gson.fromJson(gson.toJson(copy), com.minermarmot.client.config.ClientConfig.class);
        restored.validate(bundled);
        check(restored.tntMode == TntMode.CARPET, "Mode must survive copying and JSON round trips");
        config.resetPreset(bundled);
        check(config.tntMode == TntMode.BUILDER, "Reset selects the first available mode");
        var carpet = com.minermarmot.client.config.ConfigManager.parsePreset("{\"name\":\"Carpet\",\"tnt\":{\"carpet\":true}}");
        config.validate(carpet);
        check(config.tntMode == TntMode.CARPET, "Unavailable modes must be replaced with an allowed mode");
        for (String fields : new String[]{"null", "{\"builder\":true}", "{\"builderWandName\":null}",
                "{\"shulkerItem\":\"tnt\"}", "{\"shopClicks\":[\"unknown_item\"]}",
                "{\"shopClicks\":null}", "{\"shopClicks\":[null]}", "{\"shopClicks\":[\"Bad Id\"]}"}) {
            try {
                com.minermarmot.client.config.ConfigManager.parsePreset("{\"name\":\"Invalid\",\"tnt\":" + fields + "}");
                throw new AssertionError("Invalid TNT preset was accepted: " + fields);
            } catch (IllegalArgumentException expected) { }
        }
    }

    private static void testMineritePurchase() {
        try (var input = TntSelfTest.class.getResourceAsStream("/assets/minermarmot/presets/minerite.json")) {
            var bundled = com.minermarmot.client.config.ConfigManager.parsePreset(
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var sequence = java.util.List.of("minecraft:red_shulker_box", "minecraft:lime_stained_glass_pane");
            check(bundled.tnt.shopClicks.equals(sequence), "Minerite must select the red shulker then confirm with lime glass");
            var legacy = new com.google.gson.Gson().toJsonTree(bundled).getAsJsonObject();
            legacy.getAsJsonObject("tnt").add("shopClicks", new com.google.gson.JsonArray());
            legacy.addProperty("name", "Customized Minerite");
            var migrated = com.minermarmot.client.config.ConfigManager.parsePreset(legacy.toString(), bundled);
            check(migrated.name.equals("Customized Minerite") && migrated.tnt.shopClicks.equals(sequence),
                    "Installed Minerite defaults must inherit the purchase fix without replacing other settings");
            migrated.tnt.shopClicks.clear();
            check(bundled.tnt.shopClicks.equals(sequence), "Migration must copy the purchase steps");
            check(com.minermarmot.client.config.ConfigManager.parsePreset(legacy.toString()).tnt.shopClicks.isEmpty(),
                    "Custom presets without a bundled template must retain disabled purchases");
            var otherTemplate = com.minermarmot.client.config.ConfigManager.parsePreset("{\"name\":\"Default\"}");
            check(com.minermarmot.client.config.ConfigManager.parsePreset(legacy.toString(), otherTemplate).tnt.shopClicks.isEmpty(),
                    "Other bundled presets must retain disabled purchases");
            var tnt = legacy.getAsJsonObject("tnt");
            tnt.add("shopClicks", new com.google.gson.Gson().toJsonTree(java.util.List.of("minecraft:gold_ingot")));
            check(com.minermarmot.client.config.ConfigManager.parsePreset(legacy.toString(), bundled).tnt.shopClicks
                    .equals(java.util.List.of("minecraft:gold_ingot")), "Customized purchase steps must remain authoritative");
            tnt.add("shopClicks", new com.google.gson.JsonArray());
            tnt.addProperty("carpet", false);
            check(com.minermarmot.client.config.ConfigManager.parsePreset(legacy.toString(), bundled).tnt.shopClicks.isEmpty(),
                    "Explicitly disabled TNT modes must retain their restrictions");
            tnt.addProperty("carpet", true);
            tnt.addProperty("shulkerItem", "minecraft:blue_shulker_box");
            check(com.minermarmot.client.config.ConfigManager.parsePreset(legacy.toString(), bundled).tnt.shopClicks.isEmpty(),
                    "A customized delivery item must not inherit the old red-shulker purchase fix");
        } catch (java.io.IOException exception) {
            throw new AssertionError("Cannot read Minerite purchase preset", exception);
        }
        var inventory = new SimpleContainer(36);
        var shop = new SimpleContainer(27);
        inventory.setItem(0, testStack(RED_SHULKER));
        shop.setItem(14, testStack(RED_SHULKER));
        shop.setItem(19, testStack(RED_SHULKER));
        var menu = new TestMenu(shop, inventory);
        check(TntManager.findShopSlot(menu, inventory, RED_SHULKER) == 14,
                "Minerite must choose the first red shulker, not the lower color selection or player inventory");
    }

    private static ItemStack testStack(Item item) {
        // 26.2 normally loads item components from packs; supply just those this headless test needs.
        var holder = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)).orElseThrow();
        if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.builder()
                .set(DataComponents.ITEM_NAME, Component.literal("Test item"))
                .set(DataComponents.MAX_STACK_SIZE, 64).build());
        return new ItemStack(item);
    }

    private static void testPlacementTargets() {
        var blocks = new java.util.HashMap<BlockPos, BlockState>();
        BlockGetter world = new BlockGetter() {
            @Override public BlockState getBlockState(BlockPos pos) {
                return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public int getHeight() { return 384; }
            @Override public int getMinY() { return -64; }
        };
        BlockPos roof = new BlockPos(0, 3, 0);
        blocks.put(roof, Blocks.STONE.defaultBlockState());
        check(roof.equals(TntManager.findCeiling(world, new Vec3(0.5, 1.62, 0.5), 1.8, 4.5, CollisionContext.empty())),
                "Standing player must find a ceiling above the head");
        check(roof.equals(TntManager.findCeiling(world, new Vec3(0.5, 1.27, 0.5), 1.5, 4.5, CollisionContext.empty())),
                "Crouching must not invalidate the same accessible ceiling");
        check(roof.equals(TntManager.findCeiling(world, new Vec3(0.5, 1.15, 0.5), 1.38, 4.5, CollisionContext.empty())),
                "Small vertical changes during sneak must preserve the ceiling search");
        BlockPos nextRoof = roof.east();
        blocks.put(nextRoof, Blocks.STONE.defaultBlockState());
        check(nextRoof.equals(TntManager.findCeiling(world, new Vec3(1.1, 1.27, 0.5), 1.5, 4.5, CollisionContext.empty())),
                "Retarget the ceiling in the current column after residual movement");
        check(TntManager.findCeiling(world, new Vec3(0.5, 1.27, 0.5), 1.5, 1, CollisionContext.empty()) == null,
                "An out-of-reach ceiling must never be clicked");
        blocks.put(roof.below(), Blocks.STONE.defaultBlockState());
        check(TntManager.findCeiling(world, new Vec3(0.5, 1.27, 0.5), 1.5, 4.5, CollisionContext.empty()) == null,
                "A ceiling without an empty shulker position must be rejected");
        BlockPos origin = new BlockPos(-3, 64, -4);
        for (float yaw : new float[]{0, 90, 180, 270}) {
            var facing = net.minecraft.core.Direction.fromYRot(yaw);
            for (double fraction : new double[]{0.1, 0.5, 0.9}) {
                blocks.clear();
                Vec3 eye = Vec3.atLowerCornerOf(origin).add(fraction, 1.62, 1 - fraction);
                double lane = facing.getAxis() == net.minecraft.core.Direction.Axis.Z ? eye.x : eye.z;
                for (int x = -10; x <= 10; x++) {
                    for (int z = -10; z <= 10; z++) blocks.put(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
                }
                var columns = new java.util.HashSet<Integer>();
                for (int step = 0; step < 5; step++) {
                    float aim = yaw + TntManager.carpetYawOffset(yaw, lane, 1.62, step);
                    Vec3 top = TntManager.carpetFloorPoint(eye, aim, 64);
                    BlockPos target = BlockPos.containing(top);
                    int lateral = facing.getAxis() == net.minecraft.core.Direction.Axis.Z ? target.getX() : target.getZ();
                    check(top.y == 64 && columns.add(lateral), "Each sweep must visit five different columns at the captured height");
                    check(Math.abs(lateral - (int) Math.floor(lane)) <= 2, "All sweep cells must remain in the five-column strip");
                    double pitch = Math.toDegrees(Math.atan2(eye.y - top.y, top.subtract(eye).horizontalDistance()));
                    check(Math.abs(pitch - 30) < 0.0001, "Every placement ray must preserve the fixed thirty-degree pitch");
                    check(TntManager.carpetHit(world, eye, top, 4.5, CollisionContext.empty()) != null,
                            "Fixed-angle floor points must be reachable in every direction and fractional lane");
                    blocks.put(target, Blocks.TNT.defaultBlockState());
                    check(TntManager.carpetHit(world, eye, top, 4.5, CollisionContext.empty()) == null, "Existing TNT must never become support for another layer");
                    blocks.remove(target);
                    blocks.put(target.below(), Blocks.TNT.defaultBlockState());
                    check(TntManager.carpetHit(world, eye, top, 4.5, CollisionContext.empty()) == null, "TNT at the floor height must never be used as support for another TNT");
                    blocks.put(target.below(), Blocks.STONE.defaultBlockState());
                    check(TntManager.carpetHit(world, eye, top, 1, CollisionContext.empty()) == null, "Carpet must preserve vanilla reach");
                    blocks.remove(target.below());
                    check(TntManager.carpetHit(world, eye, top, 4.5, CollisionContext.empty()) == null, "Unsupported cells must be skipped");
                    blocks.put(target.below(), Blocks.STONE.defaultBlockState());
                    blocks.put(target, Blocks.STONE.defaultBlockState());
                    check(TntManager.carpetHit(world, eye, top, 4.5, CollisionContext.empty()) == null, "Obstacles must never become a second TNT layer");
                    blocks.remove(target);
                }
                check(columns.size() == 5, "Outer and inner columns must receive one attempt each per cycle");
            }
        }
        check(TntManager.carpetFloorPoint(new Vec3(0.5, 64, 0.5), 0, 64) == null, "A floor plane at or above the eyes must be skipped");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestMenu extends AbstractContainerMenu {
        TestMenu(SimpleContainer first, SimpleContainer second) {
            super(null, 1);
            for (int i = 0; i < first.getContainerSize(); i++) addSlot(new Slot(first, i, 0, 0));
            for (int i = 0; i < second.getContainerSize(); i++) addSlot(new Slot(second, i, 0, 0));
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}

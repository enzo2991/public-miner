package com.minermarmot.client.features.alerts;

import com.minermarmot.client.config.ConfigManager;
import com.minermarmot.client.features.mining.MiningManager;
import com.minermarmot.client.screen.ConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import com.minermarmot.client.screen.widget.ConfigSectionWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

// Experimental client test APIs are confined to the test mod.
public final class PlayerAlertsClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {

        var original = context.computeOnClient(client -> ConfigManager.current().copy());
        List<String> played = new ArrayList<>();
        List<Long> playedAt = new ArrayList<>();
        boolean[] inventoryPhase = {false};
        SoundEventListener listener = (sound, event, range) -> {
            String id = sound.getIdentifier().toString();
            if (BlockAlertSettings.SOUNDS.contains(id)) {
                var settings = ConfigManager.current().playerAlerts;
                check(sound instanceof AlertSoundInstance alert && alert.volumeMultiplier()
                                == (inventoryPhase[0] ? settings.inventoryFullVolume : settings.privateMessageVolume),
                        "Every initial alert and repetition must use its selected live volume");
                played.add(id);
                playedAt.add(Util.getMillis());
            }
        };
        try {
            context.runOnClient(client -> {
                var config = original.copy();
                config.applyPreset("kazuria", ConfigManager.preset("kazuria"));
                config.playerAlerts = ConfigManager.preset("kazuria").playerAlerts.newConfig();
                check(ConfigManager.save(config), "Player alert settings must save");
                var gson = new com.google.gson.Gson();
                var old = gson.toJsonTree(config).getAsJsonObject();
                var fields = old.getAsJsonObject("playerAlerts");
                fields.remove("inventoryFullRepeat");
                fields.remove("privateMessageRepeat");
                fields.addProperty("inventoryFullSound", "minecraft:block.note_block.guitar");
                var restored = ConfigManager.parseConfig(old.toString());
                check(restored.playerAlerts.inventoryFullRepeat == 3 && restored.playerAlerts.privateMessageRepeat == 3,
                        "Older saved settings must inherit the selected preset repeat counts");
                check(restored.playerAlerts.inventoryFullSound.equals("minecraft:block.note_block.guitar"),
                        "Repeat migration must preserve sound preferences");
            });
            settings(context);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksDownload();
                context.runOnClient(client -> capacityChecks());
                world.getServer().runCommand("gamemode creative @p");
                world.getServer().runCommand("fill -5 63 -5 5 63 5 minecraft:stone");
                world.getServer().runCommand("fill -5 64 -5 5 70 5 minecraft:air");
                world.getServer().runCommand("tp @p 0.5 64 0.5 0 0");
                world.getConnection().waitForClientboundPackets();
                context.waitTicks(5);
                context.runOnClient(client -> client.getSoundManager().addListener(listener));
                context.runOnClient(client -> ConfigManager.current().playerAlerts.privateMessageVolume = 5);
                privateMessages(context, world, played, playedAt);
                context.runOnClient(client -> {
                    inventoryPhase[0] = true;
                    ConfigManager.current().playerAlerts.inventoryFullVolume = 0.5f;
                });
                inventoryDrops(context, world, played);
            }
        } finally {
            context.runOnClient(client -> {
                client.getSoundManager().removeListener(listener);
                PlayerAlertManager.worldChanged();
                check(ConfigManager.save(original), "Player alert tests must restore settings");
                client.gui.setScreen(null);
            });
        }
    }

    private static void capacityChecks() {
        SimpleContainer inventory = new SimpleContainer(41);
        ItemStack incoming = new ItemStack(Items.COBBLESTONE, 2);
        check(PlayerAlertManager.canStoreCompletely(inventory, incoming), "Empty inventory must fit drops");
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        check(!PlayerAlertManager.canStoreCompletely(inventory, incoming), "Equipment and offhand must not count as free storage");
        inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 63));
        check(!PlayerAlertManager.canStoreCompletely(inventory, incoming), "One free item space cannot hold two items");
        inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 63));
        check(PlayerAlertManager.canStoreCompletely(inventory, incoming), "Space across matching stacks must combine");
        inventory.getItem(1).set(DataComponents.CUSTOM_NAME, Component.literal("Different components"));
        check(!PlayerAlertManager.canStoreCompletely(inventory, incoming), "Different components must prevent stacking");
        inventory.setItem(1, ItemStack.EMPTY);
        check(PlayerAlertManager.canStoreCompletely(inventory, incoming), "An empty main slot must accept drops");
        check(PlayerAlertManager.canStoreCompletely(inventory, ItemStack.EMPTY), "Empty drops need no storage");
    }

    private static void settings(ClientGameTestContext context) {
        context.setScreen(() -> new ConfigScreen(null));
        context.clickScreenButton("minermarmot.config.features");
        context.runOnClient(client -> {
            check(client.gui.screen().children().stream().filter(child -> child instanceof Button button && button.getY() == 28).count() == 3,
                    "The configuration menu must have General, Features and Skins tabs");
            var section = optionList(client).children().stream().flatMap(entry -> entry.children().stream())
                    .filter(child -> child instanceof ConfigSectionWidget).map(child -> (ConfigSectionWidget) child)
                    .filter(widget -> widget.getMessage().getString().equals(Component.translatable("minermarmot.config.alerts").getString()))
                    .findFirst().orElseThrow();
            check(!section.isExpanded(), "The player Alerts section must start collapsed inside Features");
        });
        BlockAlertsClientTest.clickSection(context, Component.translatable("minermarmot.config.mining").getString());
        BlockAlertsClientTest.clickSection(context, Component.translatable("minermarmot.config.alerts").getString());
        BlockAlertsClientTest.search(context, Component.translatable("minermarmot.config.alerts").getString());
        context.waitTick();
        context.runOnClient(client -> check(optionList(client).children().stream().filter(entry -> entry.children().size() == 4).count() == 2,
                "Expanding Alerts must expose its two four-control rows"));
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            for (int scale : new int[]{1, 2}) {
                context.runOnClient(client -> {
                    client.options.guiScale().set(scale);
                    client.resizeGui();
                });
                context.waitTick();
                context.runOnClient(client -> optionList(client).setScrollAmount(optionList(client).maxScrollAmount()));
                context.waitTick();
                context.runOnClient(client -> checkTable(client, true));
                context.takeScreenshot("player-alert-table-scale-" + scale);
            }
            int windowWidth = context.computeOnClient(client -> client.getWindow().getWidth());
            int windowHeight = context.computeOnClient(client -> client.getWindow().getHeight());
            try {
                context.runOnClient(client -> {
                    client.options.guiScale().set(1);
                    client.getWindow().setWindowed(320, 640);
                });
                context.waitFor(client -> client.gui.screen().width == 320);
                context.waitTick();
                context.runOnClient(client -> checkTable(client, true));
                context.takeScreenshot("player-alert-table-minimum");
            } finally {
                context.runOnClient(client -> client.getWindow().setWindowed(windowWidth, windowHeight));
                context.waitFor(client -> client.gui.screen().width == windowWidth);
            }
        } finally {
            context.runOnClient(client -> {
                client.options.guiScale().set(originalScale);
                client.resizeGui();
            });
        }
        for (String key : new String[]{"inventoryFull", "privateMessage"}) {
            BlockAlertsClientTest.search(context, Component.translatable("minermarmot.alerts." + key).getString());
            BlockAlertsClientTest.cycleListButton(context, "minermarmot.alerts." + key);
            context.runOnClient(client -> {
                var settings = ConfigManager.current().playerAlerts;
                check(key.equals("inventoryFull") ? !settings.inventoryFull : !settings.privateMessage,
                        "Player alert toggles must apply immediately");
                BlockAlertsClientTest.checkSaved();
            });
            context.waitTick();
            context.runOnClient(client -> checkTable(client, false));
            context.takeScreenshot("player-alert-table-disabled-" + key);
            for (int expected : new int[]{4, 5, 1, 2, 3}) {
                BlockAlertsClientTest.cycleListButton(context, "minermarmot.alerts.playerColumn.repeat");
                context.runOnClient(client -> {
                    var settings = ConfigManager.current().playerAlerts;
                    check((key.equals("inventoryFull") ? settings.inventoryFullRepeat : settings.privateMessageRepeat) == expected,
                            "Repeat counts must cycle from 1 through 5 and save while disabled");
                    BlockAlertsClientTest.checkSaved();
                });
            }
            BlockAlertsClientTest.cycleListButton(context, "minermarmot.alerts." + key);
        }
        BlockAlertsClientTest.search(context, Component.translatable("minermarmot.alerts.privateMessage").getString());
        BlockAlertsClientTest.clickListButton(context, "minermarmot.alerts.sound.pling");
        BlockAlertsClientTest.clickListButton(context, "minermarmot.alerts.sound.guitar");
        context.runOnClient(client -> {
            check(ConfigManager.current().playerAlerts.privateMessageSound.equals("minecraft:block.note_block.guitar"),
                    "Player alert sound selection must apply immediately");
            BlockAlertsClientTest.checkSaved();
        });
        BlockAlertsClientTest.clickListButton(context, "minermarmot.alerts.sound.guitar");
        context.clickScreenButton("minermarmot.alerts.noSound");
        context.runOnClient(client -> {
            check(ConfigManager.current().playerAlerts.privateMessageSound.isEmpty(), "No sound must save for player alerts");
            BlockAlertsClientTest.checkSaved();
        });
        context.clickScreenButton("minermarmot.config.general");
        context.clickScreenButton("minermarmot.config.resetPreset");
        context.runOnClient(client -> {
            var gson = new com.google.gson.Gson();
            check(gson.toJsonTree(ConfigManager.current().playerAlerts)
                            .equals(gson.toJsonTree(ConfigManager.preset("kazuria").playerAlerts.newConfig())),
                    "Reset must restore player alerts");
            BlockAlertsClientTest.checkSaved();
        });
        context.clickScreenButton("minermarmot.config.features");
        BlockAlertsClientTest.search(context, "");
        context.waitTick();
        context.takeScreenshot("player-alert-table-kazuria-settings");
        context.clickScreenButton("minermarmot.config.quit");
    }

    private static ContainerObjectSelectionList<?> optionList(Minecraft client) {
        return client.gui.screen().children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>)
                .map(child -> (ContainerObjectSelectionList<?>) child).findFirst().orElseThrow();
    }

    private static void checkTable(Minecraft client, boolean enabled) {
        var list = optionList(client);
        var rows = list.children().stream().filter(entry -> entry.children().size() == 4
                && entry.getContentY() >= list.getY() && entry.getContentY() < list.getBottom()).toList();
        check(!rows.isEmpty(), "The alert table must have rows");
        for (var row : rows) {
            var widgets = row.children().stream().map(child -> (AbstractWidget) child).toList();
            for (var widget : widgets) {
                check(widget.getX() >= list.getX() && widget.getRight() <= list.getRight(), "Alert columns must fit the panel");
                check(widget.active, "Dimmed alert settings must remain editable");
                boolean toggle = widget instanceof CycleButton<?> button && button.getValue() instanceof Boolean;
                check(widget.getAlpha() == (enabled || toggle ? 1.0f : 0.45f), "OFF must dim only the preferences");
                if (toggle) {
                    check(widget.getMessage().getStyle().getColor().getValue() == (enabled ? 0xFFFFFF : 0xBBBBBB),
                            "Enabled switches must use the shared white ON and gray OFF labels");
                }
            }
            for (var widget : widgets) {
                for (var other : widgets) {
                    check(widget == other || widget.getRight() <= other.getX() || other.getRight() <= widget.getX()
                                    || widget.getBottom() <= other.getY() || other.getBottom() <= widget.getY(),
                            "Table columns must not overlap");
                }
            }
            check(widgets.get(2) instanceof CycleButton<?> repeat && repeat.getValue() instanceof Integer
                            && repeat.getMessage().getString().equals("3"), "Repeat must show its value only");
        }
    }

    private static void privateMessages(ClientGameTestContext context, TestSingleplayerContext world, List<String> played, List<Long> playedAt) {
        context.runOnClient(client -> {
            played.clear();
            playedAt.clear();
            ConfigManager.current().playerAlerts.inventoryFull = false;
        });
        for (String message : new String[]{"[Moi -> Player] outgoing", "[Player -> Other] other", "prefix [Player -> Moi] unrelated"}) {
            sendMessage(context, world, message, false);
        }
        sendMessage(context, world, "[Player -> Moi] action bar", true);
        context.runOnClient(client -> check(played.isEmpty(), "Unrelated chat and action bar messages must not alert"));
        sendMessage(context, world, "§a[Player -> Moi] Hello!", false);
        checkBurst(context, played, 3);
        context.runOnClient(client -> {
            check(playedAt.get(2) - playedAt.get(0) >= 500, "The three sounds must be spaced at least 250 ms apart");
            checkOverlay(client, "privateMessage");
        });
        context.takeScreenshot("player-alert-repeat-private-message");
        context.runOnClient(client -> ConfigManager.current().playerAlerts.privateMessage = false);
        sendMessage(context, world, "[Player -> Moi] disabled", false);
        context.runOnClient(client -> check(played.size() == 3, "Disabled private messages must stay silent"));
        for (int repeat : new int[]{1, 5}) {
            context.runOnClient(client -> {
                played.clear();
                ConfigManager.current().playerAlerts.privateMessage = true;
                ConfigManager.current().playerAlerts.privateMessageRepeat = repeat;
            });
            sendMessage(context, world, "[Player -> Moi] repeat " + repeat, false);
            checkBurst(context, played, repeat);
        }
        context.runOnClient(client -> {
            played.clear();
            ConfigManager.current().playerAlerts.privateMessageRepeat = 3;
        });
        sendMessage(context, world, "[Player -> Moi] first burst", false);
        sendMessage(context, world, "[Player -> Moi] second burst", false);
        checkBurst(context, played, 6);
        context.runOnClient(client -> ConfigManager.current().playerAlerts.privateMessageRepeat = 5);
        context.runOnClient(client -> played.clear());
        sendMessage(context, world, "[Player -> Moi] stop pending sounds", false);
        int beforeDisable = context.computeOnClient(client -> {
            ConfigManager.current().playerAlerts.privateMessage = false;
            return played.size();
        });
        check(beforeDisable > 0 && beforeDisable < 5, "Disabling must happen during the five-sound burst");
        context.waitTicks(25);
        context.runOnClient(client -> check(played.size() == beforeDisable, "OFF must cancel pending repetitions"));
        context.runOnClient(client -> {
            ConfigManager.current().playerAlerts.privateMessage = true;
            ConfigManager.current().playerAlerts.privateMessageSound = "";
        });
        sendMessage(context, world, "[Player -> Moi] visual only", false);
        context.runOnClient(client -> {
            check(played.size() == beforeDisable, "No sound must keep private message alerts silent");
            checkOverlay(client, "privateMessage");
            var config = ConfigManager.current().copy();
            config.applyPreset("default", ConfigManager.preset("default"));
            check(ConfigManager.save(config), "Default preset must apply");
        });
        sendMessage(context, world, "[Player -> Moi] unavailable", false);
        context.runOnClient(client -> {
            check(played.size() == beforeDisable, "A preset without private messages must prohibit their alert");
            var config = ConfigManager.current().copy();
            config.applyPreset("kazuria", ConfigManager.preset("kazuria"));
            config.playerAlerts = ConfigManager.preset("kazuria").playerAlerts.newConfig();
            check(ConfigManager.save(config), "Kazuria must restore");
        });
        context.runOnClient(client -> played.clear());
        sendMessage(context, world, "[Player -> Moi] transfer", false);
        int beforeTransfer = context.computeOnClient(client -> {
            PlayerAlertManager.worldChanged();
            return played.size();
        });
        context.waitTicks(15);
        context.runOnClient(client -> check(played.size() == beforeTransfer, "World transfers must discard pending repetitions"));
    }

    private static void inventoryDrops(ClientGameTestContext context, TestSingleplayerContext world, List<String> played) {
        BlockPos mined = new BlockPos(0, 65, 3);
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            }
            player.inventoryMenu.broadcastChanges();
            ItemEntity drop = new ItemEntity(player.level(), 0.5, 64.2, 3.5, new ItemStack(Items.COBBLESTONE));
            player.level().addFreshEntity(drop);
        });
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(5);
        context.runOnClient(client -> {
            played.clear();
            check(!MiningManager.isRunning(), "Player alerts must work without automatic mining");
            check(!PlayerAlertManager.canStoreCompletely(client.player.getInventory(), new ItemStack(Items.COBBLESTONE)),
                    "The full server inventory must reach the client");
        });
        context.waitTicks(5);
        context.runOnClient(client -> check(played.isEmpty(), "A full inventory and an unrelated drop must not alert before mining"));
        breakBlock(context, world, mined);
        checkBurst(context, played, 3);
        context.runOnClient(client -> {
            check(played.equals(java.util.Collections.nCopies(3, "minecraft:block.note_block.pling")),
                    "An unstoreable drop near a successful break must alert: " + played);
            checkOverlay(client, "inventoryFull");
        });
        context.takeScreenshot("player-alert-repeat-inventory-full");
        breakBlock(context, world, mined);
        context.waitTicks(8);
        context.runOnClient(client -> check(played.size() == 3, "The same drop must not alert again on subsequent ticks or breaks"));
        context.runOnClient(client -> {
            ConfigManager.current().playerAlerts.inventoryFull = false;
            PlayerAlertManager.worldChanged();
        });
        breakBlock(context, world, mined);
        context.waitTicks(5);
        context.runOnClient(client -> check(played.size() == 3, "Disabled inventory alerts must remain silent"));
    }

    private static void checkBurst(ClientGameTestContext context, List<String> played, int repeat) {
        context.waitFor(client -> played.size() >= repeat);
        context.waitTicks(8);
        context.runOnClient(client -> check(played.equals(java.util.Collections.nCopies(repeat, "minecraft:block.note_block.pling")),
                "The alert must play exactly " + repeat + " Pling sounds: " + played));
    }

    private static void breakBlock(ClientGameTestContext context, TestSingleplayerContext world, BlockPos pos) {
        world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().level().setBlock(pos, Blocks.STONE.defaultBlockState(), 3));
        world.getConnection().waitForClientboundPackets();
        context.waitTick();
        context.runOnClient(client -> {
            check(client.gameMode.startDestroyBlock(pos, Direction.UP), "The vanilla client block break must succeed");
            check(client.level.getBlockState(pos).isAir(), "Creative mining must remove the block locally");
        });
        world.getConnection().waitForClientboundPackets();
    }

    private static void sendMessage(ClientGameTestContext context, TestSingleplayerContext world, String message, boolean overlay) {
        world.getServer().runOnServer(server -> world.getConnection().getServerPlayer().connection
                .send(new ClientboundSystemChatPacket(Component.literal(message), overlay)));
        world.getConnection().waitForClientboundPackets();
        context.waitTicks(2);
    }

    private static void checkOverlay(Minecraft client, String key) {
        try {
            var field = client.gui.hud.getClass().getDeclaredField("overlayMessageString");
            field.setAccessible(true);
            check(Component.translatable("minermarmot.alerts." + key + "Notification").getString()
                    .equals(((Component) field.get(client.gui.hud)).getString()), "The alert must display a translated action bar notification");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect the native action bar", exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

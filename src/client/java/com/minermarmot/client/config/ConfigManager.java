package com.minermarmot.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.minermarmot.Main;
import com.minermarmot.client.features.alerts.BlockAlertsPreset;
import com.minermarmot.client.features.alerts.PlayerAlertsConfig;
import com.minermarmot.client.features.mining.MiningFeature;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, MiningPreset> PRESETS = new LinkedHashMap<>();
    private static ClientConfig current;
    private static Path directory;

    private ConfigManager() {}

    public static void initialize() {
        directory = FabricLoader.getInstance().getConfigDir().resolve("minermarmot");
        for (String id : List.of("default", "kazuria", "minerite")) {
            try (InputStream input = ConfigManager.class.getResourceAsStream("/assets/minermarmot/presets/" + id + ".json")) {
                if (input == null) {
                    throw new IOException("Missing built-in mining preset: " + id);
                }
                MiningPreset preset = parsePreset(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                PRESETS.put(id, preset);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Cannot load the built-in mining preset " + id, exception);
            }
        }

        Path presetsDirectory = directory.resolve("presets");
        try {
            Files.createDirectories(presetsDirectory);
            for (var entry : PRESETS.entrySet()) {
                Path presetFile = presetsDirectory.resolve(entry.getKey() + ".json");
                if (!Files.exists(presetFile)) {
                    Files.writeString(presetFile, GSON.toJson(entry.getValue()));
                }
            }
            try (var files = Files.list(presetsDirectory)) {
                Map<String, MiningPreset> bundled = new LinkedHashMap<>(PRESETS);
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    try {
                        String filename = file.getFileName().toString();
                        PRESETS.put(filename.substring(0, filename.length() - 5), loadPreset(file,
                                bundled.get(filename.substring(0, filename.length() - 5))));
                    } catch (IOException | RuntimeException exception) {
                        Main.LOGGER.error("Cannot load mining preset {}", file, exception);
                    }
                }
            }
        } catch (IOException exception) {
            Main.LOGGER.error("Cannot read mining presets", exception);
        }

        current = new ClientConfig();
        current.applyPreset("default", PRESETS.get("default"));
        Path file = directory.resolve("config.json");
        if (Files.exists(file)) {
            try {
                ClientConfig loaded = parseConfig(Files.readString(file));
                if (loaded == null || !PRESETS.containsKey(loaded.preset)) {
                    throw new IllegalArgumentException("Unknown mining preset");
                }
                loaded.validate(PRESETS.get(loaded.preset));
                current = loaded;
            } catch (IOException | RuntimeException exception) {
                Main.LOGGER.error("Cannot load mining settings; the original file is preserved", exception);
            }
        }
    }

    static MiningPreset loadPreset(Path file, MiningPreset template) throws IOException {
        String json = Files.readString(file);
        MiningPreset preset = parsePreset(json, template);
        var document = JsonParser.parseString(json).getAsJsonObject();
        if (!document.has("autoBoostBlockUpward")) {
            document.addProperty("autoBoostBlockUpward", preset.autoBoostBlockUpward);
            try {
                Path temporary = Files.createTempFile(file.getParent(), "preset-", ".tmp");
                try {
                    Files.writeString(temporary, GSON.toJson(document));
                    try {
                        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException exception) {
                        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException exception) {
                Main.LOGGER.warn("Cannot update mining preset {}; using the in-memory default", file, exception);
            }
        }
        return preset;
    }

    public static MiningPreset parsePreset(String json) {
        return parsePreset(json, null);
    }

    public static MiningPreset parsePreset(String json, MiningPreset legacyTemplate) {
        var document = JsonParser.parseString(json);
        MiningPreset preset = GSON.fromJson(document, MiningPreset.class);
        if (preset == null) {
            throw new IllegalArgumentException("Empty mining preset");
        }
        if (legacyTemplate != null && !document.getAsJsonObject().has("autoBoostBlockUpward")) {
            preset.autoBoostBlockUpward = legacyTemplate.autoBoostBlockUpward;
        }
        if (legacyTemplate != null && preset.features != null
                && !preset.features.containsKey(MiningFeature.PLACEMENT_WITHOUT_DELAY)) {
            preset.features.put(MiningFeature.PLACEMENT_WITHOUT_DELAY,
                    legacyTemplate.allows(MiningFeature.PLACEMENT_WITHOUT_DELAY));
        }
        if (legacyTemplate != null && !document.getAsJsonObject().has("tnt")) {
            preset.tnt = GSON.fromJson(GSON.toJson(legacyTemplate.tnt),
                    com.minermarmot.client.features.tnt.TntPreset.class);
        }
        if (legacyTemplate != null && !document.getAsJsonObject().has("portals")) {
            preset.portals = GSON.fromJson(GSON.toJson(legacyTemplate.portals),
                    com.minermarmot.client.features.portal.PortalPreset.class);
        }
        if (legacyTemplate != null && !document.getAsJsonObject().has("autocrafter")) {
            preset.autocrafter = GSON.fromJson(GSON.toJson(legacyTemplate.autocrafter),
                    com.minermarmot.client.features.autocraft.AutocraftPreset.class);
        }
        if (legacyTemplate != null && !document.getAsJsonObject().has("skins")) {
            preset.skins = GSON.fromJson(GSON.toJson(legacyTemplate.skins),
                    com.minermarmot.client.features.skins.SkinsPreset.class);
        }
        if (legacyTemplate != null && !document.getAsJsonObject().has("playerAlerts")) {
            // Supply new bundled alerts in memory without overwriting customized preset files.
            preset.playerAlerts = GSON.fromJson(GSON.toJson(legacyTemplate.playerAlerts),
                    com.minermarmot.client.features.alerts.PlayerAlertsPreset.class);
        } else if (legacyTemplate != null && preset.playerAlerts != null && preset.playerAlerts.defaults != null) {
            inheritRepeats(preset.playerAlerts.defaults, document.getAsJsonObject().getAsJsonObject("playerAlerts").get("defaults"),
                    legacyTemplate.playerAlerts.defaults);
        }
        preset.validate();
        if (legacyTemplate != null && "Minerite".equals(legacyTemplate.name)
                && !preset.tnt.builder && preset.tnt.carpet && preset.tnt.builderWandName.isEmpty()
                && preset.tnt.shulkerItem.equals("minecraft:red_shulker_box") && preset.tnt.shopClicks.isEmpty()) {
            // Repair the original bundled Minerite TNT defaults in memory, preserving the installed file.
            preset.tnt.shopClicks = new java.util.ArrayList<>(legacyTemplate.tnt.shopClicks);
        }
        if (legacyTemplate != null && !document.getAsJsonObject().has("blockAlerts")
                && !preset.blockAlerts.targets().isEmpty()) {
            Map<String, String> categoryById = new LinkedHashMap<>();
            legacyTemplate.blockAlerts.categories.forEach((name, category) ->
                    category.blocks.forEach(target -> categoryById.put(target.id, name)));
            if (preset.blockAlerts.targets().stream().allMatch(target -> categoryById.containsKey(target.id))) {
                // Only the legacy default's known targets have categories we can recover safely.
                Map<String, BlockAlertsPreset.Category> categories = new LinkedHashMap<>();
                legacyTemplate.blockAlerts.categories.keySet().forEach(name -> categories.put(name, new BlockAlertsPreset.Category()));
                preset.blockAlerts.targets().forEach(target -> categories.get(categoryById.get(target.id)).blocks.add(target));
                preset.blockAlerts.categories = categories;
                preset.validate();
            }
        }
        return preset;
    }

    public static ClientConfig parseConfig(String json) {
        var document = JsonParser.parseString(json);
        ClientConfig loaded = GSON.fromJson(document, ClientConfig.class);
        if (loaded != null && loaded.alerts == null && PRESETS.containsKey(loaded.preset)) {
            loaded.alerts = PRESETS.get(loaded.preset).blockAlerts.newConfig();
            if (document.getAsJsonObject().has("enabledBlockAlerts")) {
                var enabled = document.getAsJsonObject().getAsJsonArray("enabledBlockAlerts");
                loaded.alerts.targets.forEach((id, settings) -> settings.enabled = enabled.contains(GSON.toJsonTree(id)));
            }
        }
        if (loaded != null && PRESETS.containsKey(loaded.preset)) {
            inheritRepeats(loaded.playerAlerts, document.getAsJsonObject().get("playerAlerts"),
                    PRESETS.get(loaded.preset).playerAlerts.defaults);
            var saved = document.getAsJsonObject().getAsJsonObject("playerAlertPresets");
            if (saved != null && loaded.playerAlertPresets != null) {
                loaded.playerAlertPresets.forEach((id, settings) -> {
                    if (PRESETS.containsKey(id)) {
                        inheritRepeats(settings, saved.get(id), PRESETS.get(id).playerAlerts.defaults);
                    }
                });
            }
        }
        return loaded;
    }

    private static void inheritRepeats(PlayerAlertsConfig settings, JsonElement fields, PlayerAlertsConfig defaults) {
        if (settings != null && (fields == null || fields.isJsonObject())) {
            if (fields == null || !fields.getAsJsonObject().has("inventoryFullRepeat")) {
                settings.inventoryFullRepeat = defaults.inventoryFullRepeat;
            }
            if (fields == null || !fields.getAsJsonObject().has("privateMessageRepeat")) {
                settings.privateMessageRepeat = defaults.privateMessageRepeat;
            }
        }
    }

    public static ClientConfig current() {
        return current;
    }

    public static Map<String, MiningPreset> presets() {
        return Collections.unmodifiableMap(PRESETS);
    }

    public static MiningPreset preset(String id) {
        return PRESETS.get(id);
    }

    public static boolean save(ClientConfig draft) {
        Path temporary = null;
        try {
            ClientConfig saved = draft.copy();
            MiningPreset preset = PRESETS.get(saved.preset);
            if (preset == null) {
                throw new IllegalArgumentException("Unknown mining preset");
            }
            saved.validate(preset);
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "config-", ".tmp");
            Files.writeString(temporary, GSON.toJson(saved));
            Path target = directory.resolve("config.json");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            current = saved;
            return true;
        } catch (IOException | RuntimeException exception) {
            Main.LOGGER.error("Cannot save mining settings", exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    Main.LOGGER.warn("Cannot remove temporary mining settings {}", temporary, exception);
                }
            }
        }
    }
}

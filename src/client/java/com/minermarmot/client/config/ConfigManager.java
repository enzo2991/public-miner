package com.minermarmot.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.minermarmot.Main;
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
        return parsePreset(Files.readString(file), template);
    }

    public static MiningPreset parsePreset(String json) {
        return parsePreset(json, null);
    }

    public static MiningPreset parsePreset(String json, MiningPreset legacyTemplate) {
        var document = JsonParser.parseString(json);
        removeUnknownFeatures(document);
        MiningPreset preset = GSON.fromJson(document, MiningPreset.class);
        if (preset == null) {
            throw new IllegalArgumentException("Empty mining preset");
        }
        if (legacyTemplate != null && preset.features != null
                && !preset.features.containsKey(MiningFeature.DOLPHIN)) {
            preset.features.put(MiningFeature.DOLPHIN, legacyTemplate.allows(MiningFeature.DOLPHIN));
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

        return preset;
    }

    public static ClientConfig parseConfig(String json) {
        var document = JsonParser.parseString(json);
        removeUnknownFeatures(document);
        ClientConfig loaded = GSON.fromJson(document, ClientConfig.class);

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

    private static void removeUnknownFeatures(JsonElement document) {
        if (document == null || !document.isJsonObject()) return;
        var root = document.getAsJsonObject();
        var names = java.util.Arrays.stream(MiningFeature.values()).flatMap(feature -> java.util.stream.Stream.of(
                GSON.toJsonTree(feature), GSON.toJsonTree(feature.name()), GSON.toJsonTree(feature.toString()))).collect(java.util.stream.Collectors.toSet());
        if (root.has("features") && root.get("features").isJsonObject()) {
            root.getAsJsonObject("features").entrySet().removeIf(entry -> !names.contains(GSON.toJsonTree(entry.getKey())));
        }
        for (String key : List.of("mining", "defaults")) {
            if (!root.has(key) || !root.get(key).isJsonObject()) continue;
            var section = root.getAsJsonObject(key);
            if (section.has("enabled") && section.get("enabled").isJsonArray()) {
                section.getAsJsonArray("enabled").asList().removeIf(value -> !value.isJsonNull() && !names.contains(value));
            }
        }
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

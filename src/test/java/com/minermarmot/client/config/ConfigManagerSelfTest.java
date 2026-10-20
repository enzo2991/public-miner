package com.minermarmot.client.config;

import com.google.gson.JsonParser;
import java.nio.file.Files;

public final class ConfigManagerSelfTest {
    public static void run() throws Exception {
        var directory = Files.createTempDirectory("minermarmot-presets-");
        var file = directory.resolve("custom.json");
        try {
            String legacy = "{\"name\":\"Custom\",\"features\":{\"autoBoost\":false},\"custom\":{\"value\":42}}";
            Files.writeString(file, legacy);
            assert !ConfigManager.loadPreset(file, null).autoBoostBlockUpward;
            var migrated = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assert !migrated.remove("autoBoostBlockUpward").getAsBoolean();
            assert migrated.equals(JsonParser.parseString(legacy)) : "Migration must preserve all existing and unknown settings";
            String updated = Files.readString(file);
            ConfigManager.loadPreset(file, null);
            assert Files.readString(file).equals(updated) : "Migration must be idempotent";
            for (boolean blockUpward : new boolean[]{true, false}) {
                var template = new MiningPreset();
                template.name = "Bundled";
                template.autoBoostBlockUpward = blockUpward;
                Files.writeString(file, legacy);
                assert ConfigManager.loadPreset(file, template).autoBoostBlockUpward == blockUpward;
                assert JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                        .get("autoBoostBlockUpward").getAsBoolean() == blockUpward : "Migration must write the bundled preset value";
                template.autoBoostBlockUpward = !blockUpward;
                String explicit = "{\"name\":\"Custom\", \"autoBoostBlockUpward\": " + blockUpward + "}";
                Files.writeString(file, explicit);
                assert ConfigManager.loadPreset(file, template).autoBoostBlockUpward == blockUpward;
                assert Files.readString(file).equals(explicit) : "Explicit settings and formatting must remain unchanged";
            }
            String invalid = "{\"features\":null}";
            Files.writeString(file, invalid);
            try {
                ConfigManager.loadPreset(file, null);
                throw new AssertionError("Invalid presets must be rejected");
            } catch (IllegalArgumentException expected) {
                assert Files.readString(file).equals(invalid) : "Invalid presets must not be rewritten";
            }
            try (var files = Files.list(directory)) {
                assert files.count() == 1 : "Migration must clean up temporary files";
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
}

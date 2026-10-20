package com.minermarmot.release;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.minermarmot.client.config.ClientConfig;
import com.minermarmot.client.config.ConfigManager;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;

// Run with the distribution JAR, never the ordinary main/client class directories.
public final class ObfuscatedJarSelfTest {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        String mapping = Files.readString(Path.of(args[1]));
        assert Path.of(ConfigManager.class.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(jar)
                : "Checks must load the obfuscated artifact";
        try (var archive = new JarFile(jar.toFile())) {
            for (var entry : archive.stream().filter(item -> !item.isDirectory()).toList()) {
                assert !entry.getName().contains("minermarmot-staff") && !entry.getName().contains("/staff/");
                try (var input = archive.getInputStream(entry)) {
                    String bytes = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                    assert !bytes.contains("minermarmot.auth.") && !bytes.contains("api.rawcode.fr")
                            : "Discord authentication resources must not be packaged: " + entry.getName();
                    assert !bytes.contains("autoBoostLockHeight") && !bytes.contains("lockedBoostHeight")
                            && !bytes.contains("isStaff") : "Staff-only code or resources leaked into core: " + entry.getName();
                }
            }
            assert archive.getEntry("fabric.mod.json") != null;
            assert archive.getEntry("minermarmot.client.mixins.json") != null;
            assert archive.getEntry("minermarmot.classtweaker") != null : "The native mining delay access must survive protection";
            assert archive.getEntry("com/minermarmot/client/mixin/MultiPlayerGameModeMixin.class") == null;
            try (var input = archive.getInputStream(archive.getJarEntry("fabric.mod.json"))) {
                assert JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject().get("accessWidener").getAsString().equals("minermarmot.classtweaker");
            }
            assert archive.getEntry("com/minermarmot/client/mixin/MinecraftMixin.class") != null;
            assert archive.getEntry("com/minermarmot/client/integration/api/authmanager.class") == null;
            assert archive.getEntry("com/minermarmot/client/features/mining/MiningManager.class") == null;
            assert archive.getEntry("com/minermarmot/client/features/tnt/TntManager.class") == null;
            assert mapping.lines().noneMatch(line -> line.startsWith("com.minermarmot.client.integration.api."))
                    : "Discord API classes must not be packaged";
            for (String feature : new String[]{"autocraft.AutocraftManager", "autocraft.AutocraftConfig",
                    "autocraft.AutocraftPreset", "autocraft.AutocraftPreset$Recipe", "mining.SurfaceTargetMiner",
                    "alerts.BlockAlertsPreset", "alerts.BlockAlertRenderer"}) {
                String original = "com.minermarmot.client.features." + feature;
                assert archive.getEntry(original.replace('.', '/') + ".class") == null
                        : "New feature classes must be renamed: " + original;
                String renamed = mapping.lines().filter(line -> line.startsWith(original + " -> "))
                        .findFirst().orElseThrow().split(" -> ")[1].replace(":", "");
                Class<?> type = Class.forName(renamed, false, ObfuscatedJarSelfTest.class.getClassLoader());
                assert Arrays.stream(type.getDeclaredFields()).noneMatch(field -> field.getName().equals("stateMatches"))
                        : "The transient matching cache must be renamed";
                assert Arrays.stream(type.getDeclaredMethods()).noneMatch(method ->
                        method.getName().equals("renderGlowOverlay") || method.getName().equals("clipGlowEdge"))
                        : "Glow implementation method names must be hidden";
            }
            assert archive.stream().noneMatch(entry -> entry.getName().endsWith(".java")
                    || entry.getName().endsWith("mapping.txt") || entry.getName().endsWith("session-token"));
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var matchingPreset = ConfigManager.parsePreset("""
                {"name":"Matching","blockAlerts":{"categories":{"CUSTOM":{"blocks":[
                {"id":"ruby","block":"minecraft:note_block","properties":{"instrument":"harp","note":"3"},
                 "displayName":"Ruby","item1":{"item":"minecraft:paper"}}]}}}}
                """);
        Object alerts = matchingPreset.getClass().getField("blockAlerts").get(matchingPreset);
        var match = alerts.getClass().getMethod("match", net.minecraft.world.level.block.state.BlockState.class);
        for (var state : net.minecraft.world.level.block.Blocks.NOTE_BLOCK.getStateDefinition().getPossibleStates()) {
            boolean expected = state.getValue(net.minecraft.world.level.block.NoteBlock.NOTE) == 3
                    && state.getValue(net.minecraft.world.level.block.NoteBlock.INSTRUMENT)
                    == net.minecraft.world.level.block.state.properties.NoteBlockInstrument.HARP;
            Object first = match.invoke(alerts, state);
            assert (first != null) == expected : "Protected matching must preserve every note-block variant";
            assert match.invoke(alerts, state) == first : "Protected cache must preserve repeated matches and misses";
        }
        assert match.invoke(alerts, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()) == null;
        Gson gson = new Gson();
        try (var input = ConfigManager.class.getResourceAsStream("/assets/minermarmot/presets/minerite.json")) {
            var craftingPreset = ConfigManager.parsePreset(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var craftingJson = gson.toJsonTree(craftingPreset).getAsJsonObject();
            var categories = craftingJson.getAsJsonObject("autocrafter").getAsJsonObject("categories");
            assert !categories.isEmpty() : "The protected artifact must retain autocrafter recipes";
            for (var category : categories.entrySet()) {
                for (var recipe : category.getValue().getAsJsonArray()) {
                    assert recipe.getAsJsonObject().has("pattern") && recipe.getAsJsonObject().has("output")
                            && recipe.getAsJsonObject().has("item1") : "Recipe JSON field names must survive obfuscation";
                }
            }
            assert gson.toJsonTree(ConfigManager.parsePreset(craftingJson.toString())).equals(craftingJson)
                    : "Autocrafter preset round trip must survive protection";
        }
        String presetJson = "{\"name\":\"Restricted\",\"features\":{\"autoMine\":false},\"monitoredBlocks\":[\"minecraft:diamond_ore\"]}";
        var preset = ConfigManager.parsePreset(presetJson);
        assert !preset.autoBoostBlockUpward;
        preset.autoBoostBlockUpward = true;
        assert ConfigManager.parsePreset(gson.toJson(preset)).autoBoostBlockUpward;
        preset.autoBoostBlockUpward = false;
        assert !ConfigManager.parsePreset(gson.toJson(preset)).autoBoostBlockUpward;
        assert gson.toJsonTree(preset).getAsJsonObject().has("autoBoostBlockUpward");
        ClientConfig config = new ClientConfig();
        config.applyPreset("default", preset);
        String json = gson.toJson(config);
        ClientConfig copy = gson.fromJson(json, ClientConfig.class);
        assert JsonParser.parseString(json).equals(gson.toJsonTree(copy)) : "JSON field names must survive obfuscation";
        assert JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("alerts").has("waypointDisplayDistance");
        assert !JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("mining").has("autoBoostLockHeight");
        var restored = ConfigManager.parsePreset(gson.toJson(preset));
        assert gson.toJsonTree(preset).equals(gson.toJsonTree(restored)) : "Preset round trip must preserve enum names and fields";
        try (var coreJar = new JarFile(jar.toFile()); var staffJar = new JarFile(args[2])) {
            for (var entry : staffJar.stream().filter(item -> item.getName().endsWith(".class")).toList()) {
                assert coreJar.getEntry(entry.getName()) == null : "Core/staff class collision: " + entry.getName();
            }
        }
        try (var archive = new JarFile(args[2]);
             var input = archive.getInputStream(archive.getJarEntry("com/minermarmot/staff/mixin/ConfigScreenMixin.class"))) {
            var mixin = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(input).accept(mixin, 0);
            var constructor = mixin.methods.stream().filter(method -> method.name.equals("<init>")).findFirst().orElseThrow();
            assert Arrays.stream(constructor.instructions.toArray()).anyMatch(instruction ->
                    instruction instanceof org.objectweb.asm.tree.LineNumberNode)
                    : "Mixin needs constructor line numbers to inject the staff field initializer";
            assert Arrays.stream(constructor.instructions.toArray()).anyMatch(instruction ->
                    instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                            && field.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD && field.name.equals("staff$unlock"))
                    : "The staff unlock field must be initialized";
        }
        verifyStaffLinkage(Path.of(args[2]));
        System.out.println("Obfuscated JAR: packaging, JSON and staff linkage checks passed");
    }
    private static void verifyStaffLinkage(Path staffJar) throws Exception {
        try (var archive = new JarFile(staffJar.toFile())) {
            assert archive.getEntry("com/minermarmot/staff/StaffSettings.class") == null : "The staff guard class name must be hidden";
            for (var entry : archive.stream().filter(item -> item.getName().endsWith(".class")).toList()) {
                try (var input = archive.getInputStream(entry)) {
                    new org.objectweb.asm.ClassReader(input).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                                    if (!coreOwner(owner)) return;
                                    try {
                                        Class<?> type = Class.forName(owner.replace('/', '.'), false, ObfuscatedJarSelfTest.class.getClassLoader());
                                        boolean found = name.equals("<init>")
                                                ? Arrays.stream(type.getDeclaredConstructors()).anyMatch(method ->
                                                        org.objectweb.asm.Type.getConstructorDescriptor(method).equals(descriptor))
                                                : hasMethod(type, name, descriptor);
                                        if (!found) throw new AssertionError("Staff calls missing method: " + owner + "." + name + descriptor);
                                    } catch (ClassNotFoundException exception) {
                                        throw new AssertionError("Staff references missing class: " + owner, exception);
                                    }
                                }

                                @Override
                                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                    if (!coreOwner(owner)) return;
                                    try {
                                        Class<?> type = Class.forName(owner.replace('/', '.'), false, ObfuscatedJarSelfTest.class.getClassLoader());
                                        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                                            if (Arrays.stream(current.getDeclaredFields()).anyMatch(field -> field.getName().equals(name)
                                                    && org.objectweb.asm.Type.getDescriptor(field.getType()).equals(descriptor))) return;
                                        }
                                        throw new AssertionError("Staff references missing field: " + owner + "." + name + descriptor);
                                    } catch (ClassNotFoundException exception) {
                                        throw new AssertionError("Staff references missing class: " + owner, exception);
                                    }
                                }
                            };
                        }
                    }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
                }
            }
        }
    }

    private static boolean coreOwner(String owner) {
        return owner.equals("com/minermarmot/Main") || owner.startsWith("com/minermarmot/client/") || owner.startsWith("x/");
    }

    private static boolean hasMethod(Class<?> type, String name, String descriptor) {
        if (Arrays.stream(type.getDeclaredMethods()).anyMatch(method -> method.getName().equals(name)
                && org.objectweb.asm.Type.getMethodDescriptor(method).equals(descriptor))) return true;
        for (Class<?> parent : type.getInterfaces()) if (hasMethod(parent, name, descriptor)) return true;
        return type.getSuperclass() != null && hasMethod(type.getSuperclass(), name, descriptor);
    }

}

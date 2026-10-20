package com.minermarmot.release;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

// Only distribution copies are rewritten; development binaries keep their original names.
public final class StaffJarRemapper {
    public static void main(String[] args) throws Exception {
        Map<String, String> classes = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0)) && line.endsWith(":") && line.contains(" -> ")) {
                String[] names = line.substring(0, line.length() - 1).split(" -> ");
                classes.put(names[0].replace('.', '/'), names[1].replace('.', '/'));
            }
        }
        classes.put("com/minermarmot/staff/StaffSettings", "com/minermarmot/staff/a");
        classes.put("com/minermarmot/staff/StaffSettings$Preferences", "com/minermarmot/staff/a$b");
        classes.put("com/minermarmot/staff/StaffSettings$Document", "com/minermarmot/staff/a$c");
        if (args.length > 3 && args[3].equals("helpers")) {
            // Both obfuscation passes inject a.* helpers; isolate the staff copies.
            classes.clear();
            try (var input = new JarFile(args[1])) {
                for (var entry : input.stream().filter(item -> item.getName().endsWith(".class")).toList()) {
                    String name = entry.getName().substring(0, entry.getName().length() - 6);
                    if (!name.startsWith("com/minermarmot/staff/")) {
                        classes.put(name, "com/minermarmot/staff/internal/" + name);
                    }
                }
            }
        }
        var remapper = new Remapper() {
            @Override public String map(String name) { return classes.getOrDefault(name, name); }
        };
        Path output = Path.of(args[2]);
        Files.createDirectories(output.getParent());
        try (var input = new JarFile(args[1]); var jar = new JarOutputStream(Files.newOutputStream(output))) {
            for (var entry : input.stream().toList()) {
                if (entry.isDirectory()) continue;
                String outputName = entry.getName();
                byte[] bytes;
                try (var stream = input.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                if (entry.getName().endsWith(".class")) {
                    var writer = new ClassWriter(0);
                    // Mixin extracts field initializers from constructor line numbers.
                    int flags = entry.getName().startsWith("com/minermarmot/staff/mixin/") ? 0 : ClassReader.SKIP_DEBUG;
                    new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), flags);
                    bytes = writer.toByteArray();
                    outputName = new ClassReader(bytes).getClassName() + ".class";
                }
                jar.putNextEntry(new JarEntry(outputName));
                jar.write(bytes);
                jar.closeEntry();
            }
        }
        System.out.println("Staff distribution references adapted to the core mapping");
    }
}

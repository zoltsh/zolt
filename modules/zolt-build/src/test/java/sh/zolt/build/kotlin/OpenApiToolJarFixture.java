package sh.zolt.build.kotlin;

import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Builds a tiny OpenAPI-compatible JVM tool that emits one Kotlin source file. */
final class OpenApiToolJarFixture {
    private OpenApiToolJarFixture() {
    }

    static Path generatorJar(Path workDirectory) throws IOException {
        Path toolSource = source(
                workDirectory,
                "openapi-tool-src/org/openapitools/codegen/OpenAPIGenerator.java",
                """
                package org.openapitools.codegen;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;

                public final class OpenAPIGenerator {
                    public static void main(String[] args) throws Exception {
                        Path output = Path.of(option(args, "--output"));
                        String revision = property(option(args, "--additional-properties"), "revision");
                        Path target = output.resolve("com/example/GeneratedClient.kt");
                        Files.createDirectories(target.getParent());
                        Files.writeString(target,
                                "package com.example\\n\\n"
                                        + "object GeneratedClient {\\n"
                                        + "    @JvmStatic\\n"
                                        + "    fun message(): String = \\\"openapi-" + revision + "\\\"\\n"
                                        + "}\\n");
                        Files.writeString(
                                Path.of("openapi-invocations.txt"),
                                revision + "\\n",
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    }

                    private static String option(String[] args, String name) {
                        for (int index = 0; index + 1 < args.length; index++) {
                            if (name.equals(args[index])) {
                                return args[index + 1];
                            }
                        }
                        throw new IllegalArgumentException("Missing option " + name);
                    }

                    private static String property(String properties, String name) {
                        for (String property : properties.split(",")) {
                            String[] parts = property.split("=", 2);
                            if (parts.length == 2 && name.equals(parts[0])) {
                                return parts[1];
                            }
                        }
                        throw new IllegalArgumentException("Missing property " + name);
                    }
                }
                """);
        Path classes = workDirectory.resolve("openapi-tool-classes");
        new JavacRunner().compile(currentJavac(), List.of(toolSource), new Classpath(List.of()), classes);
        Path jar = workDirectory.resolve("openapi-generator-cli.jar");
        writeJar(classes, jar);
        return jar;
    }

    private static Path source(Path root, String path, String content) throws IOException {
        Path source = root.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static void writeJar(Path root, Path jar) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                JarEntry entry = new JarEntry(root.relativize(file).toString().replace(File.separatorChar, '/'));
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? name + ".exe" : name;
    }
}

package sh.zolt.cli.testcmd;

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

final class GeneratedTestToolJarFixture {
    private GeneratedTestToolJarFixture() {
    }

    static void writeJvmGenerator(
            Path jar,
            Path workDirectory) throws IOException {
        compileJar(
                jar,
                workDirectory,
                "com/example/tool/FixtureGenerator.java",
                """
                package com.example.tool;

                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class FixtureGenerator {
                    public static void main(String[] args) throws Exception {
                        Path output = Path.of(
                                System.getenv("ZOLT_OUTPUT_DIR"))
                                .resolve(args[0]);
                        Files.createDirectories(output.getParent());
                        Files.writeString(output, args[1]);
                    }
                }
                """);
    }

    static void writeOpenApiGenerator(
            Path jar,
            Path workDirectory) throws IOException {
        compileJar(
                jar,
                workDirectory,
                "org/openapitools/codegen/OpenAPIGenerator.java",
                """
                package org.openapitools.codegen;

                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class OpenAPIGenerator {
                    public static void main(String[] args) throws Exception {
                        Path output = null;
                        for (int index = 0; index + 1 < args.length; index++) {
                            if ("--output".equals(args[index])) {
                                output = Path.of(args[index + 1]);
                            }
                        }
                        if (output == null) {
                            throw new IllegalArgumentException(
                                    "missing --output");
                        }
                        Path source = output.resolve(
                                "com/example/generated/GeneratedApi.java");
                        Files.createDirectories(source.getParent());
                        Files.writeString(
                                source,
                                "package com.example.generated; "
                                        + "public final class GeneratedApi {}\\n");
                    }
                }
                """);
    }

    static void writeRuntimeMarker(
            Path jar,
            Path workDirectory) throws IOException {
        compileJar(
                jar,
                workDirectory,
                "com/example/runtime/GeneratorRuntime.java",
                """
                package com.example.runtime;

                public final class GeneratorRuntime {
                    private GeneratorRuntime() {
                    }
                }
                """);
    }

    private static void compileJar(
            Path jar,
            Path workDirectory,
            String sourcePath,
            String content) throws IOException {
        Path source = workDirectory.resolve("src").resolve(sourcePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        Path classes = workDirectory.resolve("classes");
        new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                classes);
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output =
                        new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> paths = Files.walk(classes)) {
            for (Path file : paths
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList()) {
                output.putNextEntry(new JarEntry(
                        classes.relativize(file)
                                .toString()
                                .replace(
                                        File.separatorChar,
                                        '/')));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name")
                        .toLowerCase(Locale.ROOT)
                        .contains("win")
                ? name + ".exe"
                : name;
    }
}

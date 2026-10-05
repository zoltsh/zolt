package sh.zolt.cli.build.kotlin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import sh.zolt.cli.CliTestRepository;

/** Publishes a tiny OpenAPI-compatible tool that emits one Kotlin JUnit test. */
final class OpenApiKotlinCliFixture {
    static final String VERSION = "7.11.0";

    private OpenApiKotlinCliFixture() {
    }

    static void publish(CliTestRepository repository, Path workDirectory) throws IOException {
        repository.addArtifact(
                "org.openapitools",
                "openapi-generator-cli",
                VERSION,
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.openapitools</groupId>
                  <artifactId>openapi-generator-cli</artifactId>
                  <version>7.11.0</version>
                </project>
                """,
                jar(workDirectory));
    }

    private static byte[] jar(Path workDirectory) throws IOException {
        Path source = workDirectory.resolve(
                "src/org/openapitools/codegen/OpenAPIGenerator.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package org.openapitools.codegen;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;

                public final class OpenAPIGenerator {
                    public static void main(String[] args) throws Exception {
                        Path output = Path.of(option(args, "--output"));
                        String revision = Files.readString(
                                Path.of(option(args, "--input-spec"))).strip();
                        Path target = output.resolve("com/example/GeneratedOpenApiTest.kt");
                        Files.createDirectories(target.getParent());
                        Files.writeString(target,
                                "package com.example\\n\\n"
                                        + "import org.junit.jupiter.api.Assertions.assertEquals\\n"
                                        + "import org.junit.jupiter.api.Test\\n\\n"
                                        + "class GeneratedOpenApiTest {\\n"
                                        + "    @Test\\n"
                                        + "    fun runsGeneratedTest() {\\n"
                                        + "        assertEquals(\\\"main-" + revision + "\\\", "
                                        + "Main.message() + \\\"-\\\" + revision())\\n"
                                        + "    }\\n\\n"
                                        + "    private fun revision(): String = \\\"" + revision + "\\\"\\n"
                                        + "}\\n");
                        Files.writeString(
                                Path.of("openapi-test-invocations.txt"),
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

                }
                """);
        Path classes = workDirectory.resolve("classes");
        new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                classes);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            output.putNextEntry(new JarEntry("org/openapitools/codegen/OpenAPIGenerator.class"));
            output.write(Files.readAllBytes(
                    classes.resolve("org/openapitools/codegen/OpenAPIGenerator.class")));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Path currentJavac() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "javac.exe"
                : "javac";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}

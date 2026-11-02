package sh.zolt.cli.build.kotlin.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;
import sh.zolt.cli.build.kotlin.ProtobufKotlinCliFixture;

/** Offline CLI proof for Protobuf-owned Java consumed by Kotlin main sources. */
final class BuildCommandProtobufGeneratedJavaWithKotlinIntegrationTest {
    private static final String INPUT = "src/main/proto/provider.proto";
    private static final String GENERATED_ROOT = "target/generated/sources/protobuf/com/example";

    @TempDir
    private Path tempDir;

    @Test
    void repairsAndRecompilesProtobufGeneratedJavaOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            ProtobufKotlinCliFixture.publish(repository);
            writeProject(project, repository);
            writeProto(project, "GeneratedApi");

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();

            CommandResult first = build(project, offlineCache);
            assertSuccessful(first);
            Path generatedSource = project.resolve(GENERATED_ROOT + "/GeneratedApi.java");
            Path generatedClass = project.resolve("target/classes/com/example/GeneratedApi.class");
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(generatedClass));
            assertTrue(Files.isRegularFile(project.resolve("target/classes/com/example/Main.class")));
            assertTiming(first, "full");

            CommandResult warm = build(project, offlineCache);
            assertSuccessful(warm);
            assertTiming(warm, "skipped");

            Files.writeString(generatedSource, "not Java\n");
            CommandResult regenerated = build(project, offlineCache);
            assertSuccessful(regenerated);
            assertTrue(Files.readString(generatedSource).contains("class GeneratedApi"));
            assertTiming(regenerated, "skipped");

            writeProto(project, "ReplacementApi");
            CommandResult incompatible = build(project, offlineCache);
            assertEquals(1, incompatible.exitCode());
            assertTrue(incompatible.stderr().contains("GeneratedApi"), incompatible.stderr());
            assertFalse(Files.exists(generatedClass));
            assertTrue(Files.isRegularFile(project.resolve(GENERATED_ROOT + "/ReplacementApi.java")));

            writeProto(project, "GeneratedApi");
            CommandResult repaired = build(project, offlineCache);
            assertSuccessful(repaired);
            assertTrue(Files.isRegularFile(generatedClass));
            assertFalse(Files.exists(project.resolve(GENERATED_ROOT + "/ReplacementApi.java")));
            assertTiming(repaired, "full");

            CommandResult run = execute(
                    "run",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString());
            assertEquals(0, run.exitCode(), run.stderr());
            assertTrue(run.stdout().contains("protobuf-GeneratedApi"), run.stdout());
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult build(Path project, Path cache) {
        return execute(
                "build",
                "--offline",
                "--no-build-cache",
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
        Files.createDirectories(project.resolve(INPUT).getParent());
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "protobuf-java-kotlin"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [generated.tools.protobuf]
                protocCoordinate = "%s"
                protocVersion = "%s"

                [generated.main.protocol]
                kind = "protobuf"
                inputs = ["%s"]
                output = "target/generated/sources/protobuf"
                javaPackage = "com.example"
                grpc = false

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                ProtobufKotlinCliFixture.COORDINATE,
                ProtobufKotlinCliFixture.VERSION,
                INPUT,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION));
        Files.writeString(project.resolve("src/main/kotlin/com/example/Main.kt"), """
                package com.example

                object Main {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        val generated = GeneratedApi.getDefaultInstance()
                        println("protobuf-" + generated.javaClass.simpleName)
                    }
                }
                """);
    }

    private static void writeProto(Path project, String message) throws IOException {
        Files.writeString(project.resolve(INPUT), """
                syntax = "proto3";
                package com.example;

                message %s {}
                """.formatted(message));
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(
                0,
                result.exitCode(),
                () -> "stderr:\n" + result.stderr() + "\nstdout:\n" + result.stdout());
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile main\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing compile timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"mainCompilationMode\":\"" + mode + "\""), line);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}

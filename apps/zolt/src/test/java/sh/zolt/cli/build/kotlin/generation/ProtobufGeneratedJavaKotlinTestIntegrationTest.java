package sh.zolt.cli.build.kotlin.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;
import sh.zolt.cli.build.kotlin.ProtobufKotlinCliFixture;

/** CLI/worker proof for Protobuf-owned Java consumed by authored Kotlin tests. */
final class ProtobufGeneratedJavaKotlinTestIntegrationTest {
    private static final String GENERATED_ROOT =
            "target/generated/test-sources/protobuf/com/example/protocol";

    @TempDir
    private Path tempDir;

    @Test
    void generatesRunsRepairsAndInvalidatesJavaForKotlinTestsOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            ProtobufKotlinCliFixture.publish(repository);
            writeProject(project, repository);

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            CommandResult offlineResolve = execute(
                    "resolve",
                    "--locked",
                    "--offline",
                    "--cwd", project.toString(),
                    "--cache-root", offlineCache.toString(),
                    "--no-progress");
            assertEquals(0, offlineResolve.exitCode(), offlineResolve.stderr());

            CommandResult first = test(project, offlineCache);
            assertSuccessful(first);
            Path generatedSource = project.resolve(GENERATED_ROOT + "/EchoRequest.java");
            Path generatedClass = project.resolve(
                    "target/test-classes/com/example/protocol/EchoRequest.class");
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(generatedClass));
            byte[] initialSource = Files.readAllBytes(generatedSource);
            byte[] initialClass = Files.readAllBytes(generatedClass);
            assertTiming(first, "full");

            CommandResult warm = test(project, offlineCache);
            assertSuccessful(warm);
            assertTiming(warm, "skipped");

            Files.writeString(generatedSource, "not Java\n");
            CommandResult repaired = test(project, offlineCache);
            assertSuccessful(repaired);
            assertArrayEquals(initialSource, Files.readAllBytes(generatedSource));
            assertArrayEquals(initialClass, Files.readAllBytes(generatedClass));
            assertTiming(repaired, "skipped");

            writeProto(project, true);
            CommandResult changed = test(project, offlineCache);
            assertSuccessful(changed);
            assertTrue(Files.isRegularFile(project.resolve(GENERATED_ROOT + "/AddedReply.java")));
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/test-classes/com/example/protocol/AddedReply.class")));
            assertTiming(changed, "full");
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult test(Path project, Path cache) {
        return execute(
                "test",
                "--no-build-cache",
                "--no-progress",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void writeProject(Path project, CliTestRepository repository) throws IOException {
        Files.createDirectories(project.resolve("src/test/kotlin/com/example"));
        Files.writeString(project.resolve("src/test/kotlin/com/example/ProtobufJavaConsumerTest.kt"), """
                package com.example

                import com.example.protocol.EchoRequest
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class ProtobufJavaConsumerTest {
                    @Test
                    fun consumesGeneratedJava() {
                        assertEquals(
                            "EchoRequest",
                            EchoRequest.getDefaultInstance().javaClass.simpleName)
                    }
                }
                """);
        writeProto(project, false);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "protobuf-java-kotlin-test"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [generated.tools.protobuf]
                protocCoordinate = "%s"
                protocVersion = "%s"

                [generated.test.protocol]
                kind = "protobuf"
                inputs = ["src/test/proto/echo.proto"]
                output = "target/generated/test-sources/protobuf"
                javaPackage = "com.example.protocol"
                grpc = false

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies.test]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                currentJavaMajorVersion(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                ProtobufKotlinCliFixture.COORDINATE,
                ProtobufKotlinCliFixture.VERSION,
                repository.baseUri(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
    }

    private static void writeProto(Path project, boolean addedReply) throws IOException {
        Path proto = project.resolve("src/test/proto/echo.proto");
        Files.createDirectories(proto.getParent());
        Files.writeString(proto, """
                syntax = "proto3";
                package com.example.echo;

                message EchoRequest {}
                %s
                """.formatted(addedReply ? "message AddedReply {}" : ""));
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().matches("(?s).*\\b1 tests successful\\b.*"), result.stdout());
    }

    private static void assertTiming(CommandResult result, String mode) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing test compilation timing in:\n" + result.stderr()));
        assertTrue(line.contains("\"testCompilationMode\":\"" + mode + "\""), line);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}

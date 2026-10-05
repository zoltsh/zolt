package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Canonical CLI/worker proof for Protobuf-owned Kotlin test sources. */
final class ProtobufGeneratedKotlinTestIntegrationTest {
    private static final String PROTOC_VERSION = "1.0.0";
    private static final String GENERATED_ROOT =
            "target/generated/test-sources/protobuf/com/example_$/protocol";

    @TempDir
    private Path tempDir;

    @Test
    void generatesCompilesRunsRepairsAndInvalidatesKotlinTestsOffline() throws Exception {
        Path project = tempDir.resolve("project");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            publishProtoc(repository);
            writeProject(project, repository.baseUri());

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", project.toString(),
                    "--cache-root", onlineCache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult first = test(project, offlineCache);
            Path generatedSource = project.resolve(GENERATED_ROOT + "/EchoRequest.kt");
            Path generatedClass = project.resolve(
                    "target/test-classes/com/example_$/protocol/EchoRequest.class");
            assertSuccessful(first);
            assertTiming(first, "\"testCompilationMode\":\"full\"");
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(generatedClass));
            byte[] initialSource = Files.readAllBytes(generatedSource);
            byte[] initialClass = Files.readAllBytes(generatedClass);
            FileTime initialClassTime = Files.getLastModifiedTime(generatedClass);

            CommandResult warm = test(project, offlineCache);
            assertSuccessful(warm);
            assertTiming(warm, "\"testCompilationMode\":\"skipped\"");
            assertArrayEquals(initialClass, Files.readAllBytes(generatedClass));
            assertEquals(initialClassTime, Files.getLastModifiedTime(generatedClass));

            Files.writeString(generatedSource, "not Kotlin\n");
            CommandResult repaired = test(project, offlineCache);
            assertSuccessful(repaired);
            assertTiming(repaired, "\"testCompilationMode\":\"skipped\"");
            assertArrayEquals(initialSource, Files.readAllBytes(generatedSource));
            assertArrayEquals(initialClass, Files.readAllBytes(generatedClass));

            writeProto(project, true);
            CommandResult changed = test(project, offlineCache);
            assertSuccessful(changed);
            assertTiming(changed, "\"testCompilationMode\":\"full\"");
            assertTrue(Files.isRegularFile(project.resolve(GENERATED_ROOT + "/AddedReply.kt")));
            assertTrue(Files.isRegularFile(project.resolve(
                    "target/test-classes/com/example_$/protocol/AddedReply.class")));
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult test(Path project, Path cache) {
        return execute(
                "test",
                "--no-build-cache",
                "--timings",
                "--timings-format", "json",
                "--cwd", project.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Tests passed"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b1 tests successful\\b.*"), result.stdout());
    }

    private static void assertTiming(CommandResult result, String expected) {
        String line = result.stderr().lines()
                .filter(value -> value.contains("\"phase\":\"compile test sources\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing compile test sources timing in:\n" + result.stderr()));
        assertTrue(line.contains(expected), line);
    }

    private static void writeProject(Path project, URI repository) throws IOException {
        Files.createDirectories(project.resolve("src/test/java/com/example"));
        Files.writeString(project.resolve("src/test/java/com/example/ProtobufGeneratedTest.java"), """
                package com.example;

                import com.example_$.protocol.EchoRequest;
                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                final class ProtobufGeneratedTest {
                    @Test
                    void usesGeneratedKotlinProtocol() {
                        assertEquals(
                                "EchoRequest",
                                EchoRequest.getDefaultInstance().getClass().getSimpleName());
                    }
                }
                """);
        writeProto(project, false);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "protobuf-generated-kotlin-test"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [generated.tools.protobuf]
                protocCoordinate = "com.example:protoc-stub"
                protocVersion = "%s"

                [generated.test.protocol]
                kind = "protobuf"
                language = "kotlin"
                inputs = ["src/test/proto/echo.proto"]
                output = "target/generated/test-sources/protobuf"
                javaPackage = "com.example_$.protocol"
                grpc = false

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies.test]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                PROTOC_VERSION,
                repository,
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

    private static void publishProtoc(CliTestRepository repository) throws IOException {
        repository.addArtifact(
                "com.example",
                "protoc-stub",
                PROTOC_VERSION,
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>protoc-stub</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(PROTOC_VERSION),
                emptyJar());
    }

    private static byte[] emptyJar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream ignored = new JarOutputStream(bytes)) {
            // A valid empty archive is sufficient because the typed generator is in-process.
        }
        return bytes.toByteArray();
    }
}

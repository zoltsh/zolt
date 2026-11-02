package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Offline workspace proof for Kotlin main sources owned by the Protobuf generator. */
final class BuildCommandProtobufGeneratedKotlinWorkspaceIntegrationTest {
    private static final String INPUT = "src/main/proto/provider.proto";
    private static final String GENERATED_ROOT =
            "target/generated/sources/protobuf/probe/provider";

    @TempDir
    private Path tempDir;

    @Test
    void repairsGeneratedOutputAndPropagatesKotlinAbiToJavaConsumersOffline()
            throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            ProtobufKotlinCliFixture.publish(repository);
            writeWorkspace(workspace, repository.baseUri());
            Path provider = workspace.resolve("modules/provider");
            writeProvider(provider, "GeneratedApi");
            writeConsumer(workspace.resolve("apps/consumer"));

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", onlineCache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult first = build(workspace, offlineCache);
            assertSuccessful(first);
            Path generatedSource = provider.resolve(GENERATED_ROOT + "/GeneratedApi.kt");
            Path generatedClass = provider.resolve("target/classes/probe/provider/GeneratedApi.class");
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(generatedClass));
            assertTrue(Files.isRegularFile(workspace.resolve(
                    "apps/consumer/target/classes/probe/consumer/Consumer.class")));
            byte[] initialSource = Files.readAllBytes(generatedSource);

            CommandResult warm = build(workspace, offlineCache);
            assertSuccessful(warm);
            assertSkipped(warm, "modules/provider");
            assertSkipped(warm, "apps/consumer");

            Files.writeString(generatedSource, "not Kotlin\n");
            CommandResult regenerated = build(workspace, offlineCache);
            assertSuccessful(regenerated);
            assertSkipped(regenerated, "modules/provider");
            assertSkipped(regenerated, "apps/consumer");
            assertArrayEquals(initialSource, Files.readAllBytes(generatedSource));

            writeProto(provider, "ReplacementApi");
            CommandResult incompatible = build(workspace, offlineCache);
            assertEquals(1, incompatible.exitCode(), incompatible.stderr());
            assertTrue(incompatible.stderr().contains("GeneratedApi"), incompatible.stderr());
            assertFalse(Files.exists(generatedClass));
            assertTrue(Files.isRegularFile(provider.resolve(
                    "target/classes/probe/provider/ReplacementApi.class")));

            writeProto(provider, "GeneratedApi");
            CommandResult repaired = build(workspace, offlineCache);
            assertSuccessful(repaired);
            assertTrue(Files.isRegularFile(generatedClass));
            assertFalse(Files.exists(provider.resolve(
                    "target/classes/probe/provider/ReplacementApi.class")));
            assertEquals(Map.of(), repository.authorizations());
        }
    }

    private static CommandResult build(Path workspace, Path cache) {
        return execute(
                "build",
                "--workspace",
                "--all",
                "--offline",
                "--no-build-cache",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertSkipped(CommandResult result, String member) {
        assertTrue(
                result.stdout().contains(
                        "Skipped main compilation in " + member + "; inputs are unchanged"),
                result.stdout());
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(
                0,
                result.exitCode(),
                () -> "stderr:\n" + result.stderr() + "\nstdout:\n" + result.stdout());
    }

    private static void writeWorkspace(Path workspace, URI repository) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "protobuf-generated-kotlin-workspace"

                [workspace.members]
                include = ["modules/provider", "apps/consumer"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository));
    }

    private static void writeProvider(Path provider, String message) throws Exception {
        writeProto(provider, message);
        write(provider.resolve("zolt.toml"), """
                [project]
                name = "provider"
                version = "0.1.0"
                group = "probe"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [generated.tools.protobuf]
                protocCoordinate = "%s"
                protocVersion = "%s"

                [generated.main.protocol]
                kind = "protobuf"
                language = "kotlin"
                inputs = ["%s"]
                output = "target/generated/sources/protobuf"
                javaPackage = "probe.provider"
                grpc = false

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                ProtobufKotlinCliFixture.COORDINATE,
                ProtobufKotlinCliFixture.VERSION,
                INPUT,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
    }

    private static void writeProto(Path provider, String message) throws Exception {
        write(provider.resolve(INPUT), """
                syntax = "proto3";
                package probe.provider;

                message %s {}
                """.formatted(message));
    }

    private static void writeConsumer(Path consumer) throws Exception {
        write(consumer.resolve("src/main/java/probe/consumer/Consumer.java"), """
                package probe.consumer;

                import probe.provider.GeneratedApi;

                public final class Consumer {
                    public static String value() {
                        return GeneratedApi.getDefaultInstance().getClass().getSimpleName();
                    }
                }
                """);
        write(consumer.resolve("zolt.toml"), """
                [project]
                name = "consumer"
                version = "0.1.0"
                group = "probe"
                java = %s

                [dependencies]
                "probe:provider" = { workspace = true }
                """.formatted(Runtime.version().feature()));
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}

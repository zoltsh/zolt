package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Offline workspace proof for Kotlin main sources owned by the OpenAPI generator. */
final class BuildCommandOpenApiGeneratedKotlinWorkspaceIntegrationTest {
    private static final String INPUT = "src/main/openapi/provider.yaml";
    private static final String GENERATED_SOURCE =
            "target/generated/sources/openapi/provider/probe/provider/GeneratedApi.kt";

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
            OpenApiKotlinCliFixture.publish(repository, tempDir.resolve("fixture-work"));
            writeWorkspace(workspace, repository.baseUri());
            writeProvider(workspace.resolve("modules/provider"), "String", "\"v1\"");
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
            Path provider = workspace.resolve("modules/provider");
            Path generatedSource = provider.resolve(GENERATED_SOURCE);
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(provider.resolve(
                    "target/classes/probe/provider/GeneratedApi.class")));
            assertTrue(Files.isRegularFile(workspace.resolve(
                    "apps/consumer/target/classes/probe/consumer/Consumer.class")));
            assertEquals(List.of("run"), invocations(provider));

            CommandResult warm = build(workspace, offlineCache);
            assertSuccessful(warm);
            assertSkipped(warm, "modules/provider");
            assertSkipped(warm, "apps/consumer");
            assertEquals(List.of("run"), invocations(provider));

            Files.writeString(generatedSource, "not Kotlin\n");
            CommandResult regenerated = build(workspace, offlineCache);
            assertSuccessful(regenerated);
            assertSkipped(regenerated, "modules/provider");
            assertSkipped(regenerated, "apps/consumer");
            assertEquals(List.of("run", "run"), invocations(provider));

            writeGeneratedApi(provider, "Int", "1");
            CommandResult incompatible = build(workspace, offlineCache);
            assertEquals(1, incompatible.exitCode(), incompatible.stderr());
            assertTrue(
                    incompatible.stderr().contains("String")
                            && incompatible.stderr().contains("int"),
                    incompatible.stderr());
            assertEquals(List.of("run", "run", "run"), invocations(provider));

            writeGeneratedApi(provider, "String", "\"v2\"");
            CommandResult repaired = build(workspace, offlineCache);
            assertSuccessful(repaired);
            assertEquals(List.of("run", "run", "run", "run"), invocations(provider));
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

    private static List<String> invocations(Path provider) throws Exception {
        return Files.readAllLines(provider.resolve("openapi-main-invocations.txt"));
    }

    private static void writeWorkspace(Path workspace, URI repository) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "openapi-generated-kotlin-workspace"

                [workspace.members]
                include = ["modules/provider", "apps/consumer"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository));
    }

    private static void writeProvider(Path provider, String returnType, String value)
            throws Exception {
        writeGeneratedApi(provider, returnType, value);
        write(provider.resolve("zolt.toml"), """
                [project]
                name = "provider"
                version = "0.1.0"
                group = "probe"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "%s"

                [generated.main.provider]
                kind = "openapi"
                language = "kotlin"
                input = "%s"
                output = "target/generated/sources/openapi/provider"
                generator = "kotlin"
                additionalProperties = { relativePath = "probe/provider/GeneratedApi.kt", logFile = "openapi-main-invocations.txt" }

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                OpenApiKotlinCliFixture.VERSION,
                INPUT,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
    }

    private static void writeGeneratedApi(Path provider, String returnType, String value)
            throws Exception {
        write(provider.resolve(INPUT), """
                package probe.provider

                object GeneratedApi {
                    @JvmStatic
                    fun value(): %s = %s
                }
                """.formatted(returnType, value));
    }

    private static void writeConsumer(Path consumer) throws Exception {
        write(consumer.resolve("src/main/java/probe/consumer/Consumer.java"), """
                package probe.consumer;

                import probe.provider.GeneratedApi;

                public final class Consumer {
                    public static String value() {
                        return GeneratedApi.value();
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

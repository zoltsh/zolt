package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler workspace proof for a declared generated Kotlin main root. */
final class BuildCommandGeneratedKotlinWorkspaceIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void compilesGeneratedKotlinAndPropagatesItsAbiToJavaConsumers() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path cache = tempDir.resolve("cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            writeWorkspace(workspace, repository.baseUri());
            writeProvider(workspace.resolve("modules/provider"), "String", "\"v1\"");
            writeConsumer(workspace.resolve("apps/consumer"));

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());

            CommandResult first = build(workspace, cache);
            assertEquals(0, first.exitCode(), first.stderr());
            assertTrue(Files.isRegularFile(workspace.resolve(
                    "modules/provider/target/classes/probe/provider/GeneratedApi.class")));
            assertTrue(Files.isRegularFile(workspace.resolve(
                    "apps/consumer/target/classes/probe/consumer/Consumer.class")));

            CommandResult warm = build(workspace, cache);
            assertEquals(0, warm.exitCode(), warm.stderr());
            assertTrue(
                    warm.stdout().contains(
                            "Skipped main compilation in modules/provider; inputs are unchanged"),
                    warm.stdout());

            writeProvider(workspace.resolve("modules/provider"), "Int", "1");
            CommandResult incompatible = build(workspace, cache);

            assertEquals(1, incompatible.exitCode(), incompatible.stderr());
            assertTrue(
                    incompatible.stderr().contains("String")
                            && incompatible.stderr().contains("int"),
                    incompatible.stderr());

            writeProvider(workspace.resolve("modules/provider"), "String", "\"v2\"");
            CommandResult repaired = build(workspace, cache);
            assertEquals(0, repaired.exitCode(), repaired.stderr());
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

    private static void writeWorkspace(Path workspace, URI repository) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "generated-kotlin-workspace"

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
        write(provider.resolve("schema/model.txt"), "generated Kotlin model\n");
        write(provider.resolve("generated/main/probe/provider/GeneratedApi.kt"), """
                package probe.provider

                object GeneratedApi {
                    @JvmStatic
                    fun value(): %s = %s
                }
                """.formatted(returnType, value));
        write(provider.resolve("zolt.toml"), """
                [project]
                name = "provider"
                version = "0.1.0"
                group = "probe"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [generated.main.model]
                kind = "declared-root"
                language = "kotlin"
                output = "generated/main"
                inputs = ["schema/model.txt"]
                required = true
                clean = false

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
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

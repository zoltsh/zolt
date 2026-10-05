package sh.zolt.cli.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import sh.zolt.cli.build.ExecSourceGeneratorCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real-compiler workspace proof for Kotlin main sources owned by a pinned exec tool. */
final class BuildCommandExecGeneratedKotlinWorkspaceIntegrationTest {
    private static final String TEMPLATE = "schema/GeneratedApi.kt.in";
    private static final String GENERATED_SOURCE =
            "target/generated/sources/kotlin/probe/provider/GeneratedApi.kt";

    @TempDir
    private Path tempDir;

    @Test
    void regeneratesKotlinAndPropagatesItsAbiToJavaConsumersOffline() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            ExecSourceGeneratorCliFixture.publish(repository, tempDir.resolve("fixture-work"));
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

            CommandResult first = build(workspace, offlineCache);
            assertSuccessful(first);
            assertTrue(Files.isRegularFile(workspace.resolve("modules/provider/" + GENERATED_SOURCE)));
            assertTrue(Files.isRegularFile(workspace.resolve(
                    "modules/provider/target/classes/probe/provider/GeneratedApi.class")));
            assertTrue(Files.isRegularFile(workspace.resolve(
                    "apps/consumer/target/classes/probe/consumer/Consumer.class")));

            CommandResult warm = build(workspace, offlineCache);
            assertSuccessful(warm);
            assertSkipped(warm, "modules/provider");
            assertSkipped(warm, "apps/consumer");

            Files.delete(workspace.resolve("modules/provider/" + GENERATED_SOURCE));
            CommandResult regenerated = build(workspace, offlineCache);
            assertSuccessful(regenerated);
            assertTrue(Files.isRegularFile(workspace.resolve("modules/provider/" + GENERATED_SOURCE)));
            assertSkipped(regenerated, "modules/provider");
            assertSkipped(regenerated, "apps/consumer");

            writeTemplate(workspace.resolve("modules/provider"), "Int", "1");
            CommandResult incompatible = build(workspace, offlineCache);
            assertEquals(1, incompatible.exitCode(), incompatible.stderr());
            assertTrue(
                    incompatible.stderr().contains("String")
                            && incompatible.stderr().contains("int"),
                    incompatible.stderr());

            writeTemplate(workspace.resolve("modules/provider"), "String", "\"v2\"");
            CommandResult repaired = build(workspace, offlineCache);
            assertSuccessful(repaired);
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
                name = "exec-generated-kotlin-workspace"

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
        writeTemplate(provider, returnType, value);
        write(provider.resolve("zolt.toml"), """
                [project]
                name = "provider"
                version = "0.1.0"
                group = "probe"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [generated.tools.kotlin-source-generator]
                kind = "jvm"
                coordinates = [{ coordinate = "%s", version = "%s" }]
                mainClass = "%s"

                [generated.main.model]
                kind = "exec"
                language = "kotlin"
                tool = "kotlin-source-generator"
                args = ["%s", "probe/provider/GeneratedApi.kt"]
                inputs = ["%s"]
                output = "target/generated/sources/kotlin"
                produces = "java-sources"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                ExecSourceGeneratorCliFixture.COORDINATE,
                ExecSourceGeneratorCliFixture.VERSION,
                ExecSourceGeneratorCliFixture.MAIN_CLASS,
                TEMPLATE,
                TEMPLATE,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
    }

    private static void writeTemplate(Path provider, String returnType, String value)
            throws Exception {
        write(provider.resolve(TEMPLATE), """
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

package sh.zolt.cli.build.kotlin.workspace.kapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.KaptProcessorCliFixture;
import sh.zolt.cli.build.KotlinCliBuildCacheTestSupport;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real CLI proof for KAPT invalidation and compiled-output reuse across Kotlin workspace members. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinWorkspaceKaptIntegrationTest {
    private static final FileTime WARM_SENTINEL = FileTime.fromMillis(946_684_800_000L);

    @TempDir
    private Path tempDir;

    @Test
    void regeneratesProviderAndRebuildsItsConsumerOnceOffline() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KaptProcessorCliFixture.publish(repository, tempDir.resolve("processor"));
            writeWorkspace(workspace, repository.baseUri(), "configured-workspace");

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult first = build(workspace, offlineCache);
            assertEquals(0, first.exitCode(), combined(first));
            Path providerClass = workspace.resolve(
                    "modules/provider/target/classes/com/example/provider/ProviderApi.class");
            Path generatedClass = workspace.resolve(
                    "modules/provider/target/classes/com/example/GeneratedTestMessage.class");
            Path generatedSource = workspace.resolve(
                    "modules/provider/target/generated/sources/annotations/com/example/GeneratedTestMessage.java");
            Path consumerClass = workspace.resolve(
                    "apps/consumer/target/classes/com/example/consumer/Main.class");
            assertTrue(Files.isRegularFile(providerClass));
            assertTrue(Files.isRegularFile(generatedClass));
            assertTrue(Files.isRegularFile(generatedSource));
            assertTrue(Files.isRegularFile(consumerClass));

            setSentinels(providerClass, generatedClass, consumerClass);
            CommandResult warm = build(workspace, offlineCache);
            assertEquals(0, warm.exitCode(), combined(warm));
            assertSentinel(providerClass);
            assertSentinel(generatedClass);
            assertSentinel(consumerClass);

            replace(
                    workspace.resolve("modules/provider/zolt.toml"),
                    "configured-workspace",
                    "updated-workspace");
            CommandResult updated = build(workspace, offlineCache);
            assertEquals(0, updated.exitCode(), combined(updated));
            assertNotEquals(WARM_SENTINEL, Files.getLastModifiedTime(providerClass));
            assertNotEquals(WARM_SENTINEL, Files.getLastModifiedTime(generatedClass));
            assertNotEquals(WARM_SENTINEL, Files.getLastModifiedTime(consumerClass));
            assertTrue(Files.readString(generatedSource).contains("updated-workspace"));

            setSentinels(providerClass, generatedClass, consumerClass);
            CommandResult updatedWarm = build(workspace, offlineCache);
            assertEquals(0, updatedWarm.exitCode(), combined(updatedWarm));
            assertSentinel(providerClass);
            assertSentinel(generatedClass);
            assertSentinel(consumerClass);

            CommandResult run = execute(
                    "run",
                    "--workspace",
                    "--member", "apps/consumer",
                    "--cwd", workspace.toString(),
                    "--cache-root", offlineCache.toString());
            assertEquals(0, run.exitCode(), combined(run));
            assertTrue(run.stdout().contains("updated-workspace"), run.stdout());
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "post-resolve workspace commands must remain cache-only");
        }
    }

    @Test
    void restoresProviderAndConsumerClassesFromBuildCacheOffline() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("cache-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path workspace = tempDir.resolve("cached-workspace");
            Path onlineCache = tempDir.resolve("cached-online-cache");
            Path offlineCache = tempDir.resolve("cached-offline-cache");
            KotlinCliBuildCacheTestSupport.configure(fakeUserHome);
            KotlinCompilerCliFixture.publish(repository);
            KaptProcessorCliFixture.publish(repository, tempDir.resolve("cached-processor"));
            writeWorkspace(workspace, repository.baseUri(), "cached-workspace");

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult first = cachedBuild(workspace, offlineCache);
            assertEquals(0, first.exitCode(), combined(first));
            assertWorkspaceCompilation(first, 0, 0, 2);
            Path providerTarget = workspace.resolve("modules/provider/target");
            Path consumerTarget = workspace.resolve("apps/consumer/target");
            Path generatedSources = providerTarget.resolve("generated/sources/annotations");
            Path generatedSource = generatedSources.resolve("com/example/GeneratedTestMessage.java");
            assertTrue(Files.readString(generatedSource).contains("cached-workspace"));

            KotlinCliBuildCacheTestSupport.deleteTrees(providerTarget, consumerTarget);
            CommandResult restored = cachedBuild(workspace, offlineCache);
            assertEquals(0, restored.exitCode(), combined(restored));
            assertWorkspaceCompilation(restored, 0, 2, 0);
            assertTrue(Files.isDirectory(generatedSources));
            assertTrue(Files.isRegularFile(providerTarget.resolve(
                    "classes/com/example/GeneratedTestMessage.class")));
            assertTrue(Files.isRegularFile(consumerTarget.resolve(
                    "classes/com/example/consumer/Main.class")));

            CommandResult warm = cachedBuild(workspace, offlineCache);
            assertEquals(0, warm.exitCode(), combined(warm));
            assertWorkspaceCompilation(warm, 2, 0, 0);
            CommandResult run = execute(
                    "run",
                    "--workspace",
                    "--member", "apps/consumer",
                    "--cwd", workspace.toString(),
                    "--cache-root", offlineCache.toString());
            assertEquals(0, run.exitCode(), combined(run));
            assertTrue(run.stdout().contains("cached-workspace"), run.stdout());
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "workspace cache restoration must remain cache-only");
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static CommandResult build(Path workspace, Path cache) {
        return execute(
                "build",
                "--workspace",
                "--all",
                "--offline",
                "--no-build-cache",
                "--no-progress",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static CommandResult cachedBuild(Path workspace, Path cache) {
        return execute(
                "build",
                "--workspace",
                "--all",
                "--offline",
                "--timings",
                "--timings-format", "json",
                "--no-progress",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertWorkspaceCompilation(
            CommandResult result,
            int skipped,
            int restored,
            int executed) {
        String timing = result.stderr().lines()
                .filter(line -> line.contains("\"phase\":\"build workspace\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing workspace timing in:\n" + result.stderr()));
        assertTrue(timing.contains("\"mainCompilationsSkipped\":\"" + skipped + "\""), timing);
        assertTrue(timing.contains("\"mainCompilationsRestored\":\"" + restored + "\""), timing);
        assertTrue(timing.contains("\"mainCompilationsExecuted\":\"" + executed + "\""), timing);
    }

    private static void writeWorkspace(
            Path workspace,
            URI repository,
            String processorMessage) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-kapt-workspace"

                [workspace.members]
                include = ["modules/provider", "apps/consumer"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository));
        writeProvider(workspace.resolve("modules/provider"), processorMessage);
        writeConsumer(workspace.resolve("apps/consumer"));
    }

    private static void writeProvider(Path provider, String processorMessage) throws Exception {
        Path source = provider.resolve("src/main/kotlin/com/example/provider/ProviderApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(provider.resolve("zolt.toml"), """
                [project]
                name = "provider"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [compiler]
                args = ["-Azolt.message=%s"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"

                [dependencies.processor]
                "%s:%s" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                processorMessage,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KaptProcessorCliFixture.GROUP,
                KaptProcessorCliFixture.ARTIFACT,
                KaptProcessorCliFixture.VERSION));
        Files.writeString(source, """
                package com.example.provider

                import com.example.GeneratedTestMessage

                object ProviderApi {
                    @JvmStatic
                    fun value(): String = GeneratedTestMessage.value()
                }
                """);
    }

    private static void writeConsumer(Path consumer) throws Exception {
        Path source = consumer.resolve("src/main/kotlin/com/example/consumer/Main.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(consumer.resolve("zolt.toml"), """
                [project]
                name = "consumer"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.consumer.Main"

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "com.example:provider" = { workspace = true }
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
        Files.writeString(source, """
                package com.example.consumer

                import com.example.provider.ProviderApi

                object Main {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        println(ProviderApi.value())
                    }
                }
                """);
    }

    private static void setSentinels(Path... paths) throws Exception {
        for (Path path : paths) {
            Files.setLastModifiedTime(path, WARM_SENTINEL);
        }
    }

    private static void assertSentinel(Path path) throws Exception {
        assertEquals(WARM_SENTINEL, Files.getLastModifiedTime(path), path.toString());
    }

    private static void replace(Path path, String before, String after) throws Exception {
        Files.writeString(path, Files.readString(path).replace(before, after));
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}

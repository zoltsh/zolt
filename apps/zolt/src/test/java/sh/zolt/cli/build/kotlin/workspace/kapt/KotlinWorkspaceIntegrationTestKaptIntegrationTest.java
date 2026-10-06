package sh.zolt.cli.build.kotlin.workspace.kapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KaptProcessorCliFixture;
import sh.zolt.cli.build.KotlinCliBuildCacheTestSupport;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real CLI proof for KAPT-generated integration tests inside a Kotlin workspace. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinWorkspaceIntegrationTestKaptIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void compilesRestoresAndInvalidatesWorkspaceIntegrationTestKaptOffline() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path workspace = tempDir.resolve("workspace");
            Path onlineCache = tempDir.resolve("online-cache");
            Path artifactCache = tempDir.resolve("artifact-cache");
            KotlinCliBuildCacheTestSupport.configure(fakeUserHome);
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            KaptProcessorCliFixture.publish(repository, tempDir.resolve("processor"));
            writeWorkspace(workspace, repository.baseUri(), "configured-integration-workspace");

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", onlineCache.toString(),
                    "--no-progress");
            assertEquals(0, resolve.exitCode(), combined(resolve));
            Files.move(onlineCache, artifactCache);
            repository.clearAuthorizations();
            repository.close();

            CommandResult first = integrationTest(
                    workspace, artifactCache, "configured-integration-workspace");
            assertSuccessful(first);
            assertWorkspaceCompilation(first, 0, 0, 2, 0, 0, 1);
            Path providerTarget = workspace.resolve("modules/provider/target");
            Path consumerTarget = workspace.resolve("apps/consumer/target");
            Path integrationOutput = consumerTarget.resolve("integration-test-classes");
            Path generatedSources = consumerTarget.resolve("generated/test-sources/annotations");
            Path generatedSource = generatedSources.resolve("com/example/GeneratedTestMessage.java");
            assertTrue(Files.isRegularFile(providerTarget.resolve(
                    "classes/com/example/provider/ProviderApi.class")));
            assertTrue(Files.isRegularFile(consumerTarget.resolve(
                    "classes/com/example/consumer/ConsumerApi.class")));
            assertTrue(Files.isRegularFile(integrationOutput.resolve(
                    "com/example/GeneratedTestMessage.class")));
            assertTrue(Files.isRegularFile(integrationOutput.resolve(
                    "com/example/consumer/KaptWorkspaceIntegrationTest.class")));
            assertTrue(Files.isRegularFile(integrationOutput.resolve(
                    "com/example/consumer/KaptWorkspaceJavaIntegrationTest.class")));
            assertTrue(Files.readString(generatedSource).contains("configured-integration-workspace"));

            CommandResult warm = integrationTest(
                    workspace, artifactCache, "configured-integration-workspace");
            assertSuccessful(warm);
            assertWorkspaceCompilation(warm, 2, 0, 0, 1, 0, 0);

            KotlinCliBuildCacheTestSupport.deleteTrees(providerTarget, consumerTarget);
            CommandResult restored = integrationTest(
                    workspace, artifactCache, "configured-integration-workspace");
            assertSuccessful(restored);
            assertWorkspaceCompilation(restored, 0, 2, 0, 0, 1, 0);
            assertTrue(Files.isDirectory(generatedSources));
            assertTrue(Files.isRegularFile(integrationOutput.resolve(
                    "com/example/GeneratedTestMessage.class")));

            CommandResult restoredWarm = integrationTest(
                    workspace, artifactCache, "configured-integration-workspace");
            assertSuccessful(restoredWarm);
            assertWorkspaceCompilation(restoredWarm, 2, 0, 0, 1, 0, 0);

            replace(
                    workspace.resolve("apps/consumer/zolt.toml"),
                    "configured-integration-workspace",
                    "updated-integration-workspace");
            CommandResult updated = integrationTest(
                    workspace, artifactCache, "updated-integration-workspace");
            assertSuccessful(updated);
            assertWorkspaceCompilation(updated, 0, 0, 2, 0, 0, 1);
            assertTrue(Files.readString(generatedSource).contains("updated-integration-workspace"));

            CommandResult updatedWarm = integrationTest(
                    workspace, artifactCache, "updated-integration-workspace");
            assertSuccessful(updatedWarm);
            assertWorkspaceCompilation(updatedWarm, 2, 0, 0, 1, 0, 0);
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "workspace integration-test KAPT commands must remain cache-only after resolve");
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static CommandResult integrationTest(Path workspace, Path cache, String expectedMessage) {
        return execute(
                "integration-test",
                "--workspace",
                "--member", "apps/consumer",
                "--timings",
                "--timings-format", "json",
                "--no-progress",
                "--jvm-arg=-Dzolt.expected.message=" + expectedMessage,
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), combined(result));
        assertTrue(result.stdout().contains("Integration tests passed in apps/consumer"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b2 tests found\\b.*"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b2 tests successful\\b.*"), result.stdout());
    }

    private static void assertWorkspaceCompilation(
            CommandResult result,
            int mainSkipped,
            int mainRestored,
            int mainExecuted,
            int testSkipped,
            int testRestored,
            int testExecuted) {
        String timing = result.stderr().lines()
                .filter(line -> line.contains("\"phase\":\"run workspace integration-test members\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing workspace integration-test timing in:\n" + result.stderr()));
        assertTrue(timing.contains("\"mainCompilationsSkipped\":\"" + mainSkipped + "\""), timing);
        assertTrue(timing.contains("\"mainCompilationsRestored\":\"" + mainRestored + "\""), timing);
        assertTrue(timing.contains("\"mainCompilationsExecuted\":\"" + mainExecuted + "\""), timing);
        assertTrue(timing.contains("\"testCompilationsSkipped\":\"" + testSkipped + "\""), timing);
        assertTrue(timing.contains("\"testCompilationsRestored\":\"" + testRestored + "\""), timing);
        assertTrue(timing.contains("\"testCompilationsExecuted\":\"" + testExecuted + "\""), timing);
    }

    private static void writeWorkspace(Path workspace, URI repository, String message) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-integration-kapt-workspace"

                [workspace.members]
                include = ["modules/provider", "apps/consumer"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository));
        writeProvider(workspace.resolve("modules/provider"));
        writeConsumer(workspace.resolve("apps/consumer"), message);
    }

    private static void writeProvider(Path provider) throws Exception {
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

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
        Files.writeString(source, """
                package com.example.provider

                object ProviderApi {
                    @JvmStatic
                    fun value(): String = "provider"
                }
                """);
    }

    private static void writeConsumer(Path consumer, String message) throws Exception {
        Path mainSource = consumer.resolve("src/main/kotlin/com/example/consumer/ConsumerApi.kt");
        Path kotlinTest = consumer.resolve(
                "src/integration-test/kotlin/com/example/consumer/KaptWorkspaceIntegrationTest.kt");
        Path javaTest = consumer.resolve(
                "src/integration-test/java/com/example/consumer/KaptWorkspaceJavaIntegrationTest.java");
        Files.createDirectories(mainSource.getParent());
        Files.createDirectories(kotlinTest.getParent());
        Files.createDirectories(javaTest.getParent());
        Files.writeString(consumer.resolve("zolt.toml"), """
                [project]
                name = "consumer"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "%s"

                [compiler.test]
                args = ["-Azolt.message=%s"]

                [test.integration]
                sources = ["src/integration-test/kotlin", "src/integration-test/java"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "com.example:provider" = { workspace = true }

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "%s"

                [dependencies.test-processor]
                "%s:%s" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                message,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION,
                KaptProcessorCliFixture.GROUP,
                KaptProcessorCliFixture.ARTIFACT,
                KaptProcessorCliFixture.VERSION));
        Files.writeString(mainSource, """
                package com.example.consumer

                import com.example.provider.ProviderApi

                object ConsumerApi {
                    @JvmStatic
                    fun value(): String = ProviderApi.value()
                }
                """);
        Files.writeString(kotlinTest, """
                package com.example.consumer

                import com.example.GeneratedTestMessage
                import com.example.provider.ProviderApi
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class KaptWorkspaceIntegrationTest {
                    @Test
                    fun kotlinSeesWorkspaceMainAndGeneratedTypes() {
                        assertEquals("provider", ConsumerApi.value())
                        assertEquals("provider", ProviderApi.value())
                        assertEquals(System.getProperty("zolt.expected.message"), generated())
                    }

                    companion object {
                        @JvmStatic
                        fun generated(): String = GeneratedTestMessage.value()
                    }
                }
                """);
        Files.writeString(javaTest, """
                package com.example.consumer;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import com.example.GeneratedTestMessage;
                import com.example.provider.ProviderApi;
                import org.junit.jupiter.api.Test;

                public final class KaptWorkspaceJavaIntegrationTest {
                    @Test
                    void javaSeesWorkspaceKotlinAndGeneratedTypes() {
                        assertEquals("provider", ConsumerApi.value());
                        assertEquals("provider", ProviderApi.value());
                        assertEquals(
                                System.getProperty("zolt.expected.message"),
                                KaptWorkspaceIntegrationTest.generated());
                        assertEquals(
                                System.getProperty("zolt.expected.message"),
                                GeneratedTestMessage.value());
                    }
                }
                """);
    }

    private static void replace(Path path, String before, String after) throws Exception {
        Files.writeString(path, Files.readString(path).replace(before, after));
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}

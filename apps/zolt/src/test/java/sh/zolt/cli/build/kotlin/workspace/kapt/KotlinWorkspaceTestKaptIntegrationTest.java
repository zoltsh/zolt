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
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KaptProcessorCliFixture;
import sh.zolt.cli.build.KotlinCliBuildCacheTestSupport;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Real CLI proof for conservative KAPT test invalidation inside a Kotlin workspace. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class KotlinWorkspaceTestKaptIntegrationTest {
    private static final FileTime WARM_SENTINEL = FileTime.fromMillis(946_684_800_000L);

    @TempDir
    private Path tempDir;

    @Test
    void regeneratesConsumerTestsAndRebuildsItsMainOnceWhenKaptOptionChangesOffline() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            KaptProcessorCliFixture.publish(repository, tempDir.resolve("processor"));
            writeWorkspace(workspace, repository.baseUri(), "configured-workspace-test");

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

            CommandResult first = test(workspace, offlineCache, "configured-workspace-test");
            assertSuccessful(first);
            Path providerClass = workspace.resolve(
                    "modules/provider/target/classes/com/example/provider/ProviderApi.class");
            Path consumerClass = workspace.resolve(
                    "apps/consumer/target/classes/com/example/consumer/ConsumerApi.class");
            Path generatedClass = workspace.resolve(
                    "apps/consumer/target/test-classes/com/example/GeneratedTestMessage.class");
            Path kotlinTestClass = workspace.resolve(
                    "apps/consumer/target/test-classes/com/example/consumer/KaptWorkspaceTest.class");
            Path javaTestClass = workspace.resolve(
                    "apps/consumer/target/test-classes/com/example/consumer/KaptWorkspaceJavaTest.class");
            Path generatedSource = workspace.resolve(
                    "apps/consumer/target/generated/test-sources/annotations/com/example/GeneratedTestMessage.java");
            assertTrue(Files.isRegularFile(providerClass));
            assertTrue(Files.isRegularFile(consumerClass));
            assertTrue(Files.isRegularFile(generatedClass));
            assertTrue(Files.isRegularFile(kotlinTestClass));
            assertTrue(Files.isRegularFile(javaTestClass));
            assertTrue(Files.readString(generatedSource).contains("configured-workspace-test"));

            setSentinels(providerClass, consumerClass, generatedClass, kotlinTestClass, javaTestClass);
            CommandResult warm = test(workspace, offlineCache, "configured-workspace-test");
            assertSuccessful(warm);
            assertSentinels(providerClass, consumerClass, generatedClass, kotlinTestClass, javaTestClass);

            replace(
                    workspace.resolve("apps/consumer/zolt.toml"),
                    "configured-workspace-test",
                    "updated-workspace-test");
            CommandResult updated = test(workspace, offlineCache, "updated-workspace-test");
            assertSuccessful(updated);
            assertSentinels(providerClass);
            assertNotEquals(WARM_SENTINEL, Files.getLastModifiedTime(consumerClass));
            assertNotEquals(WARM_SENTINEL, Files.getLastModifiedTime(generatedClass));
            assertNotEquals(WARM_SENTINEL, Files.getLastModifiedTime(kotlinTestClass));
            assertNotEquals(WARM_SENTINEL, Files.getLastModifiedTime(javaTestClass));
            assertTrue(Files.readString(generatedSource).contains("updated-workspace-test"));

            setSentinels(consumerClass, generatedClass, kotlinTestClass, javaTestClass);
            CommandResult updatedWarm = test(workspace, offlineCache, "updated-workspace-test");
            assertSuccessful(updatedWarm);
            assertSentinels(providerClass, consumerClass, generatedClass, kotlinTestClass, javaTestClass);
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "workspace KAPT test commands must remain cache-only after resolve");
        }
    }

    @Test
    void restoresProviderConsumerAndGeneratedTestClassesFromBuildCacheOffline() throws Exception {
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
            JUnitConsoleCliFixture.publish(repository);
            KaptProcessorCliFixture.publish(repository, tempDir.resolve("cached-processor"));
            writeWorkspace(workspace, repository.baseUri(), "cached-workspace-test");

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

            CommandResult first = cachedTest(workspace, offlineCache, "cached-workspace-test");
            assertSuccessful(first);
            assertWorkspaceCompilation(first, "main", 0, 0, 2);
            assertWorkspaceCompilation(first, "test", 0, 0, 1);
            Path providerTarget = workspace.resolve("modules/provider/target");
            Path consumerTarget = workspace.resolve("apps/consumer/target");
            Path generatedTestSources = consumerTarget.resolve("generated/test-sources/annotations");

            KotlinCliBuildCacheTestSupport.deleteTrees(providerTarget, consumerTarget);
            CommandResult restored = cachedTest(workspace, offlineCache, "cached-workspace-test");
            assertSuccessful(restored);
            assertWorkspaceCompilation(restored, "main", 0, 2, 0);
            assertWorkspaceCompilation(restored, "test", 0, 1, 0);
            assertTrue(Files.isDirectory(generatedTestSources));
            assertTrue(Files.isRegularFile(consumerTarget.resolve(
                    "test-classes/com/example/GeneratedTestMessage.class")));
            assertTrue(Files.isRegularFile(consumerTarget.resolve(
                    "test-classes/com/example/consumer/KaptWorkspaceTest.class")));

            CommandResult warm = cachedTest(workspace, offlineCache, "cached-workspace-test");
            assertSuccessful(warm);
            assertWorkspaceCompilation(warm, "main", 2, 0, 0);
            assertWorkspaceCompilation(warm, "test", 1, 0, 0);
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "workspace KAPT test cache restoration must remain cache-only");
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static CommandResult test(Path workspace, Path cache, String expectedMessage) {
        return execute(
                "test",
                "--workspace",
                "--member", "apps/consumer",
                "--no-build-cache",
                "--no-progress",
                "--jvm-arg=-Dzolt.expected.message=" + expectedMessage,
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static CommandResult cachedTest(Path workspace, Path cache, String expectedMessage) {
        return execute(
                "test",
                "--workspace",
                "--member", "apps/consumer",
                "--timings",
                "--timings-format", "json",
                "--no-progress",
                "--jvm-arg=-Dzolt.expected.message=" + expectedMessage,
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertWorkspaceCompilation(
            CommandResult result,
            String scope,
            int skipped,
            int restored,
            int executed) {
        String timing = result.stderr().lines()
                .filter(line -> line.contains("\"phase\":\"test workspace\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing workspace test timing in:\n" + result.stderr()));
        assertTrue(timing.contains("\"" + scope + "CompilationsSkipped\":\"" + skipped + "\""), timing);
        assertTrue(timing.contains("\"" + scope + "CompilationsRestored\":\"" + restored + "\""), timing);
        assertTrue(timing.contains("\"" + scope + "CompilationsExecuted\":\"" + executed + "\""), timing);
    }

    private static void assertSuccessful(CommandResult result) {
        assertEquals(0, result.exitCode(), combined(result));
        assertTrue(result.stdout().contains("Tests passed in apps/consumer"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b2 tests found\\b.*"), result.stdout());
        assertTrue(result.stdout().matches("(?s).*\\b2 tests successful\\b.*"), result.stdout());
    }

    private static void writeWorkspace(Path workspace, URI repository, String message) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-test-kapt-workspace"

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
        Path kotlinTest = consumer.resolve("src/test/kotlin/com/example/consumer/KaptWorkspaceTest.kt");
        Path javaTest = consumer.resolve("src/test/java/com/example/consumer/KaptWorkspaceJavaTest.java");
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

                [test.sources]
                kotlin = ["src/test/kotlin"]

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
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class KaptWorkspaceTest {
                    @Test
                    fun kotlinSeesWorkspaceAndGeneratedTypes() {
                        assertEquals("provider", ConsumerApi.value())
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
                import org.junit.jupiter.api.Test;

                public final class KaptWorkspaceJavaTest {
                    @Test
                    void javaSeesWorkspaceAndGeneratedTypes() {
                        assertEquals("provider", ConsumerApi.value());
                        assertEquals(System.getProperty("zolt.expected.message"), KaptWorkspaceTest.generated());
                        assertEquals(System.getProperty("zolt.expected.message"), GeneratedTestMessage.value());
                    }
                }
                """);
    }

    private static void setSentinels(Path... paths) throws Exception {
        for (Path path : paths) {
            Files.setLastModifiedTime(path, WARM_SENTINEL);
        }
    }

    private static void assertSentinels(Path... paths) throws Exception {
        for (Path path : paths) {
            assertEquals(WARM_SENTINEL, Files.getLastModifiedTime(path), path.toString());
        }
    }

    private static void replace(Path path, String before, String after) throws Exception {
        Files.writeString(path, Files.readString(path).replace(before, after));
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}

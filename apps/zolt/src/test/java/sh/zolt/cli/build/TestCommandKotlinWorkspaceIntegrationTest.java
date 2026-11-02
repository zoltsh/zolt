package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler CLI coverage for Kotlin tests that consume workspace members. */
final class TestCommandKotlinWorkspaceIntegrationTest {
    private static final FileTime WARM_SENTINEL = FileTime.fromMillis(946_684_800_000L);

    @TempDir
    private Path tempDir;

    @Test
    void compilesRunsAndInvalidatesKotlinTestsAcrossWorkspaceScopes() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeWorkspace(workspace, repository.baseUri());
            assertResolveSucceeds(workspace, onlineCache);
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();

            CommandResult first = testConsumer(workspace, offlineCache);
            assertEquals(0, first.exitCode(), first.stderr());
            assertTrue(first.stdout().contains("Tests passed in apps/consumer"), first.stdout());

            Path consumerMain = workspace.resolve(
                    "apps/consumer/target/classes/probe/consumer/ConsumerApi.class");
            Path testClass = workspace.resolve(
                    "apps/consumer/target/test-classes/probe/consumer/ConsumerTest.class");
            Path testModule = kotlinModule(workspace.resolve("apps/consumer/target/test-classes"));
            Path supportApi = workspace.resolve(
                    "modules/test-support/target/classes/probe/support/SupportApi.class");
            Path supportMetadata = workspace.resolve(
                    "modules/test-support/target/classes/probe/support/SupportKt.class");
            Path supportModule = kotlinModule(
                    workspace.resolve("modules/test-support/target/classes"));
            byte[] stableConsumerMain = Files.readAllBytes(consumerMain);
            byte[] stableTestClass = Files.readAllBytes(testClass);
            byte[] stableTestModule = Files.readAllBytes(testModule);
            byte[] stableSupportApi = Files.readAllBytes(supportApi);
            byte[] stringSupportMetadata = Files.readAllBytes(supportMetadata);
            byte[] stringSupportModule = Files.readAllBytes(supportModule);
            setWarmSentinel(consumerMain, testClass, testModule);

            CommandResult warm = testConsumer(workspace, offlineCache);
            assertEquals(0, warm.exitCode(), warm.stderr());
            assertWarmSentinel(consumerMain, testClass, testModule);
            assertArrayEquals(stableConsumerMain, Files.readAllBytes(consumerMain));
            assertArrayEquals(stableTestClass, Files.readAllBytes(testClass));
            assertArrayEquals(stableTestModule, Files.readAllBytes(testModule));

            writeTestSupport(workspace, "Int");
            CommandResult incompatible = testConsumer(workspace, offlineCache);
            assertEquals(1, incompatible.exitCode());
            assertTrue(
                    incompatible.stderr().contains("Kotlin test compilation failed"),
                    incompatible.stderr());
            assertTrue(
                    incompatible.stderr().contains("String")
                            && incompatible.stderr().contains("Int"),
                    incompatible.stderr());
            assertArrayEquals(stableConsumerMain, Files.readAllBytes(consumerMain));
            assertEquals(WARM_SENTINEL, Files.getLastModifiedTime(consumerMain));
            assertFalse(Files.exists(testClass));
            assertFalse(Files.exists(testModule));
            assertArrayEquals(stableSupportApi, Files.readAllBytes(supportApi));
            assertFalse(java.util.Arrays.equals(
                    stringSupportMetadata,
                    Files.readAllBytes(supportMetadata)));

            writeTestSupport(workspace, "String");
            CommandResult repaired = testConsumer(workspace, offlineCache);
            assertEquals(0, repaired.exitCode(), repaired.stderr());
            assertTrue(repaired.stdout().contains("Tests passed in apps/consumer"), repaired.stdout());
            assertArrayEquals(stableConsumerMain, Files.readAllBytes(consumerMain));
            assertArrayEquals(stableTestClass, Files.readAllBytes(testClass));
            assertArrayEquals(stableTestModule, Files.readAllBytes(testModule));
            assertArrayEquals(stableSupportApi, Files.readAllBytes(supportApi));
            assertArrayEquals(stringSupportMetadata, Files.readAllBytes(supportMetadata));
            assertArrayEquals(stringSupportModule, Files.readAllBytes(supportModule));

            setWarmSentinel(consumerMain, testClass, testModule);
            repository.close();
            CommandResult settled = testConsumer(workspace, offlineCache);
            assertEquals(0, settled.exitCode(), settled.stderr());
            assertWarmSentinel(consumerMain, testClass, testModule);

            writeDependencyInternalTest(workspace);
            CommandResult dependencyInternal = testConsumer(workspace, offlineCache);
            assertEquals(1, dependencyInternal.exitCode());
            assertTrue(
                    dependencyInternal.stderr().contains("ProviderInternal")
                            && dependencyInternal.stderr().contains("internal"),
                    dependencyInternal.stderr());
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "post-resolve workspace tests must not contact the repository");
        }
    }

    private static CommandResult testConsumer(Path workspace, Path cache) {
        return execute(
                "test",
                "--workspace",
                "--member", "apps/consumer",
                "--no-build-cache",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertResolveSucceeds(Path workspace, Path cache) {
        CommandResult resolve = execute(
                "resolve",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
        assertEquals(0, resolve.exitCode(), resolve.stderr());
    }

    private static void writeWorkspace(Path workspace, URI repository) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-test-workspace"

                [workspace.members]
                include = ["modules/api-provider", "modules/provider", "modules/test-support", "apps/consumer"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository));
        writeKotlinMember(workspace.resolve("modules/api-provider"), "api-provider");
        Path apiProviderSource = workspace.resolve(
                "modules/api-provider/src/main/kotlin/probe/api/ApiProvider.kt");
        Files.createDirectories(apiProviderSource.getParent());
        Files.writeString(apiProviderSource, """
                package probe.api

                typealias ApiValue = String

                object ApiProvider {
                    @JvmStatic
                    fun value(): String = "api"
                }
                """);
        writeKotlinMember(workspace.resolve("modules/provider"), "provider");
        Path providerSource = workspace.resolve(
                "modules/provider/src/main/kotlin/probe/provider/Provider.kt");
        Files.createDirectories(providerSource.getParent());
        Files.writeString(
                providerSource,
                """
                package probe.provider

                typealias ProviderValue = String

                object ProviderApi {
                    @JvmStatic
                    fun value(): String = "provider"
                }

                internal object ProviderInternal {
                    @JvmStatic
                    fun value(): String = "internal-provider"
                }
                """);
        writeKotlinMember(workspace.resolve("modules/test-support"), "test-support");
        writeTestSupport(workspace, "String");
        writeConsumer(workspace.resolve("apps/consumer"));
    }

    private static void writeKotlinMember(Path directory, String name) throws Exception {
        Files.createDirectories(directory.resolve("src/main/kotlin"));
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "probe"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [build]
                sources = ["src/main/kotlin"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                name,
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION));
    }

    private static void writeConsumer(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("src/main/kotlin/probe/consumer"));
        Files.createDirectories(directory.resolve("src/test/kotlin/probe/consumer"));
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "consumer"
                version = "0.1.0"
                group = "probe"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [build]
                sources = ["src/main/kotlin"]

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                "probe:provider" = { workspace = true }

                [dependencies.api]
                "probe:api-provider" = { workspace = true }

                [dependencies.test]
                "probe:test-support" = { workspace = true }
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION));
        Files.writeString(directory.resolve("src/main/kotlin/probe/consumer/Consumer.kt"), """
                package probe.consumer

                import probe.api.ApiProvider
                import probe.api.ApiValue
                import probe.provider.ProviderApi
                import probe.provider.ProviderValue

                internal object ConsumerApi {
                    @JvmStatic
                    fun value(): ProviderValue = ProviderApi.value()

                    @JvmStatic
                    fun apiValue(): ApiValue = ApiProvider.value()
                }
                """);
        Files.writeString(directory.resolve("src/test/kotlin/probe/consumer/ConsumerTest.kt"), """
                package probe.consumer

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test
                import probe.api.ApiProvider
                import probe.api.ApiValue
                import probe.provider.ProviderApi
                import probe.provider.ProviderValue
                import probe.support.SupportApi
                import probe.support.SupportValue

                class ConsumerTest {
                    @Test
                    fun usesMainAndTestWorkspaceDependencies() {
                        val api: ApiValue = ApiProvider.value()
                        val consumer: ProviderValue = ConsumerApi.value()
                        val consumerApi: ApiValue = ConsumerApi.apiValue()
                        val provider: ProviderValue = ProviderApi.value()
                        val support: SupportValue = SupportApi.value()
                        assertEquals("api", api)
                        assertEquals("provider", consumer)
                        assertEquals("api", consumerApi)
                        assertEquals("provider", provider)
                        assertEquals("support", support)
                    }
                }
                """);
    }

    private static void writeDependencyInternalTest(Path workspace) throws Exception {
        Files.writeString(
                workspace.resolve(
                        "apps/consumer/src/test/kotlin/probe/consumer/DependencyInternalTest.kt"),
                """
                package probe.consumer

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test
                import probe.provider.ProviderInternal

                class DependencyInternalTest {
                    @Test
                    fun cannotUseDependencyInternal() {
                        assertEquals("internal-provider", ProviderInternal.value())
                    }
                }
                """);
    }

    private static void writeTestSupport(Path workspace, String alias) throws Exception {
        Path source = workspace.resolve(
                "modules/test-support/src/main/kotlin/probe/support/Support.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                """
                package probe.support

                typealias SupportValue = %s

                object SupportApi {
                    @JvmStatic
                    fun value(): String = "support"
                }
                """.formatted(alias));
    }

    private static Path kotlinModule(Path outputDirectory) throws Exception {
        try (Stream<Path> paths = Files.list(outputDirectory.resolve("META-INF"))) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".kotlin_module"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Kotlin module metadata was not compiled"));
        }
    }

    private static void setWarmSentinel(Path... paths) throws Exception {
        for (Path path : paths) {
            Files.setLastModifiedTime(path, WARM_SENTINEL);
        }
    }

    private static void assertWarmSentinel(Path... paths) throws Exception {
        for (Path path : paths) {
            assertEquals(WARM_SENTINEL, Files.getLastModifiedTime(path), path.toString());
        }
    }
}

package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler CLI canary for mixed Kotlin integration tests in a complete workspace. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class IntegrationTestCommandKotlinWorkspaceIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void runsAndReusesMixedIntegrationTestsAcrossTheWorkspaceOffline() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path workspace = tempDir.resolve("workspace");
            Path cache = tempDir.resolve("artifact-cache");
            KotlinCliBuildCacheTestSupport.configure(fakeUserHome);
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            writeWorkspace(workspace, repository.baseUri());

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", cache.toString());
            assertEquals(0, resolve.exitCode(), resolve.stderr());
            repository.clearAuthorizations();

            CommandResult first = integrationTest(workspace, cache);
            KotlinCliBuildCacheTestSupport.assertThreeTestsPassed(first);
            Path libraryOutput = workspace.resolve("modules/library/target/classes");
            Path libraryIntegrationOutput = workspace.resolve(
                    "modules/library/target/integration-test-classes");
            Path applicationIntegrationOutput = workspace.resolve(
                    "apps/application/target/integration-test-classes");
            Path libraryClass = libraryOutput.resolve("probe/library/WorkspaceApi.class");
            Path libraryIntegrationClass = libraryIntegrationOutput.resolve(
                    "probe/library/WorkspaceApiIntegrationTest.class");
            Path kotlinTestClass = applicationIntegrationOutput.resolve(
                    "probe/application/KotlinWorkspaceIntegrationTest.class");
            Path javaTestClass = applicationIntegrationOutput.resolve(
                    "probe/application/JavaWorkspaceIntegrationTest.class");
            Path integrationModule = kotlinModule(applicationIntegrationOutput);
            Path resource = applicationIntegrationOutput.resolve("workspace-integration.properties");

            assertTrue(Files.isRegularFile(libraryClass));
            assertTrue(Files.isRegularFile(kotlinModule(libraryOutput)));
            assertTrue(Files.isRegularFile(libraryIntegrationClass));
            assertTrue(Files.isRegularFile(kotlinTestClass));
            assertTrue(Files.isRegularFile(javaTestClass));
            assertEquals("mode=workspace-integration\n", Files.readString(resource));
            assertTrue(Files.isRegularFile(integrationModule));
            KotlinCliBuildCacheTestSupport.assertColdMetadata(
                    libraryIntegrationOutput, applicationIntegrationOutput);
            KotlinCliBuildCacheTestSupport.assertUnitOutputsAbsent(workspace);
            assertTrue(Files.isDirectory(fakeUserHome.resolve(".zolt/build-cache")));
            Map<String, String> libraryPayload = KotlinCliBuildCacheTestSupport.payload(
                    libraryIntegrationOutput);
            Map<String, String> applicationPayload = KotlinCliBuildCacheTestSupport.payload(
                    applicationIntegrationOutput);

            CommandResult warm = integrationTest(workspace, cache);

            KotlinCliBuildCacheTestSupport.assertThreeTestsPassed(warm);
            KotlinCliBuildCacheTestSupport.assertWorkspaceCompilation(warm, 2, 0);
            assertEquals(libraryPayload, KotlinCliBuildCacheTestSupport.payload(libraryIntegrationOutput));
            assertEquals(
                    applicationPayload,
                    KotlinCliBuildCacheTestSupport.payload(applicationIntegrationOutput));

            KotlinCliBuildCacheTestSupport.deleteTrees(
                    libraryIntegrationOutput, applicationIntegrationOutput);
            CommandResult restored = integrationTest(workspace, cache);

            KotlinCliBuildCacheTestSupport.assertThreeTestsPassed(restored);
            KotlinCliBuildCacheTestSupport.assertWorkspaceCompilation(restored, 0, 2);
            assertEquals(libraryPayload, KotlinCliBuildCacheTestSupport.payload(libraryIntegrationOutput));
            assertEquals(
                    applicationPayload,
                    KotlinCliBuildCacheTestSupport.payload(applicationIntegrationOutput));
            KotlinCliBuildCacheTestSupport.assertRestoredMetadata(
                    libraryIntegrationOutput, applicationIntegrationOutput);
            KotlinCliBuildCacheTestSupport.assertUnitOutputsAbsent(workspace);
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "workspace integration tests after resolve must not contact the repository");
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private static CommandResult integrationTest(Path workspace, Path cache) {
        return execute(
                "integration-test",
                "--workspace",
                "--all",
                "--timings",
                "--timings-format", "json",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void writeWorkspace(Path workspace, URI repository) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-integration-workspace"

                [workspace.members]
                include = ["modules/library", "apps/application"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository));
        writeLibrary(workspace.resolve("modules/library"));
        writeApplication(workspace.resolve("apps/application"));
    }

    private static void writeLibrary(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("src/main/kotlin/probe/library"));
        Files.createDirectories(directory.resolve("src/integration-test/java/probe/library"));
        Files.writeString(directory.resolve("zolt.toml"), kotlinProject("library") + """

                [build]
                sources = ["src/main/kotlin"]

                [test.integration]
                sources = ["src/integration-test/java"]

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(JUnitConsoleCliFixture.VERSION));
        Files.writeString(directory.resolve("src/main/kotlin/probe/library/WorkspaceApi.kt"), """
                package probe.library

                object WorkspaceApi {
                    @JvmStatic
                    fun message(): String = "workspace-api"

                    @JvmStatic
                    fun named(libraryValue: String): String = libraryValue
                }
                """);
        Files.writeString(
                directory.resolve("src/integration-test/java/probe/library/WorkspaceApiIntegrationTest.java"),
                """
                package probe.library;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                public final class WorkspaceApiIntegrationTest {
                    @Test
                    void exposesWorkspaceApi() throws Exception {
                        assertEquals("workspace-api", WorkspaceApi.message());
                        var parameter = WorkspaceApi.class
                                .getDeclaredMethod("named", String.class)
                                .getParameters()[0];
                        assertEquals(true, parameter.isNamePresent());
                        assertEquals("libraryValue", parameter.getName());
                    }
                }
                """);
    }

    private static void writeApplication(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("src/main/kotlin/probe/application"));
        Files.createDirectories(directory.resolve("src/integration-test/java/probe/application"));
        Files.createDirectories(directory.resolve("src/integration-test/kotlin/probe/application"));
        Files.createDirectories(directory.resolve("src/integration-test/resources"));
        Files.writeString(directory.resolve("zolt.toml"), kotlinProject("application") + """

                [build]
                sources = ["src/main/kotlin"]

                [test.integration]
                sources = ["src/integration-test/java", "src/integration-test/kotlin"]
                resources = ["src/integration-test/resources"]

                [dependencies.api]
                "probe:library" = { workspace = true }

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "%s"
                """.formatted(JUnitConsoleCliFixture.VERSION));
        Files.writeString(directory.resolve("src/main/kotlin/probe/application/ApplicationApi.kt"), """
                package probe.application

                import probe.library.WorkspaceApi

                internal object ApplicationApi {
                    fun message(): String = WorkspaceApi.message()
                }
                """);
        Files.writeString(
                directory.resolve(
                        "src/integration-test/kotlin/probe/application/KotlinWorkspaceIntegrationTest.kt"),
                """
                package probe.application

                import java.util.Properties
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test
                import probe.library.WorkspaceApi

                class KotlinWorkspaceIntegrationTest {
                    @Test
                    fun seesJavaTestWorkspaceApiAndResource() {
                        assertEquals("workspace-api", JavaWorkspaceIntegrationTest.javaMessage())
                        assertEquals("workspace-api", WorkspaceApi.message())
                        assertEquals(
                            "javaValue:workspace-api",
                            JavaWorkspaceIntegrationTest.javaParameter("workspace-api")
                        )
                        val properties = Properties()
                        javaClass.getResourceAsStream("/workspace-integration.properties").use { input ->
                            requireNotNull(input) { "workspace integration resource is missing" }
                            properties.load(input)
                        }
                        assertEquals("workspace-integration", properties.getProperty("mode"))
                    }

                    companion object {
                        @JvmStatic
                        fun kotlinMessage(): String = ApplicationApi.message()

                        @JvmStatic
                        fun kotlinParameter(kotlinValue: String): String {
                            val parameter = KotlinWorkspaceIntegrationTest::class.java
                                .getDeclaredMethod("kotlinParameter", String::class.java)
                                .parameters.single()
                            check(parameter.isNamePresent)
                            return "${parameter.name}:$kotlinValue"
                        }
                    }
                }
                """);
        Files.writeString(
                directory.resolve(
                        "src/integration-test/java/probe/application/JavaWorkspaceIntegrationTest.java"),
                """
                package probe.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;
                import probe.library.WorkspaceApi;

                public final class JavaWorkspaceIntegrationTest {
                    @Test
                    void seesKotlinTestAndWorkspaceApi() {
                        assertEquals("workspace-api", KotlinWorkspaceIntegrationTest.kotlinMessage());
                        assertEquals("workspace-api", WorkspaceApi.message());
                        assertEquals(
                                "kotlinValue:workspace-api",
                                KotlinWorkspaceIntegrationTest.kotlinParameter("workspace-api"));
                    }

                    static String javaMessage() {
                        return WorkspaceApi.message();
                    }

                    static String javaParameter(String javaValue) throws Exception {
                        var parameter = JavaWorkspaceIntegrationTest.class
                                .getDeclaredMethod("javaParameter", String.class)
                                .getParameters()[0];
                        if (!parameter.isNamePresent()) {
                            throw new AssertionError("Java integration-test parameter metadata is missing");
                        }
                        return parameter.getName() + ":" + javaValue;
                    }
                }
                """);
        Files.writeString(
                directory.resolve("src/integration-test/resources/workspace-integration.properties"),
                "mode=workspace-integration\n");
    }

    private static String kotlinProject(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "probe"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [compiler]
                args = ["-parameters"]

                [compiler.test]
                args = ["-parameters"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                """.formatted(
                name,
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION);
    }

    private static Path kotlinModule(Path output) throws Exception {
        try (Stream<Path> paths = Files.walk(output.resolve("META-INF"))) {
            var modules = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kotlin_module"))
                    .toList();
            assertEquals(1, modules.size(), "Kotlin output must contain exactly one module metadata file");
            return modules.getFirst();
        }
    }

}

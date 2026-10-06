package sh.zolt.cli.build.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.build.JUnitConsoleCliFixture;
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Workspace-lock and member-target lifecycle proof for test-scope KSP2 generation. */
final class KspWorkspaceTestCommandIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void regeneratesKspTestsForAWorkspaceMemberWithoutRepositoryAccess() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path member = workspace.resolve("apps/consumer");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            JUnitConsoleCliFixture.publish(repository);
            KspCliFixture.publish(repository, tempDir.resolve("processor"));
            writeWorkspace(workspace, repository.baseUri());
            writeMember(member, "workspace-test-ksp");

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

            CommandResult first = testMember(workspace, offlineCache);

            assertEquals(0, first.exitCode(), combined(first));
            assertTrue(first.stdout().contains("Tests passed in apps/consumer"), first.stdout());
            assertGenerated(member, "workspace-test-ksp");

            writeMember(member, "updated-test-ksp");
            CommandResult refreshed = resolveOffline(workspace, offlineCache);
            assertEquals(0, refreshed.exitCode(), combined(refreshed));
            CommandResult updated = testMember(workspace, offlineCache);

            assertEquals(0, updated.exitCode(), combined(updated));
            assertTrue(updated.stdout().contains("Tests passed in apps/consumer"), updated.stdout());
            assertGenerated(member, "updated-test-ksp");

            deleteTree(member.resolve("target"));
            CommandResult regenerated = testMember(workspace, offlineCache);

            assertEquals(0, regenerated.exitCode(), combined(regenerated));
            assertTrue(regenerated.stdout().contains("Tests passed in apps/consumer"), regenerated.stdout());
            assertGenerated(member, "updated-test-ksp");
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "post-resolve workspace KSP tests must remain cache-only");
        }
    }

    private static CommandResult testMember(Path workspace, Path cache) {
        return execute(
                "test",
                "--workspace",
                "--member", "apps/consumer",
                "--no-build-cache",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString(),
                "--no-progress");
    }

    private static CommandResult resolveOffline(Path workspace, Path cache) {
        return execute(
                "resolve",
                "--workspace",
                "--offline",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString(),
                "--no-progress");
    }

    private static void assertGenerated(Path member, String message) throws Exception {
        Path generated = member.resolve("target/generated/ksp/test/symbols");
        Path testOutput = member.resolve("target/test-classes");
        assertTrue(Files.readString(
                generated.resolve("kotlin/com/example/GeneratedKspMessage.kt"))
                .contains("\"" + message + "\""));
        assertTrue(Files.isRegularFile(
                generated.resolve("java/com/example/GeneratedJavaMessage.java")));
        assertEquals(
                message + "-resource\n",
                Files.readString(generated.resolve("resources/META-INF/ksp-cli.txt")));
        assertEquals(
                message + "-resource\n",
                Files.readString(testOutput.resolve("META-INF/ksp-cli.txt")));
        assertTrue(Files.isRegularFile(
                testOutput.resolve("com/example/WorkspaceKspTest.class")));
        assertTrue(Files.isRegularFile(
                testOutput.resolve("com/example/GeneratedKspMessage.class")));
        assertTrue(Files.isRegularFile(
                testOutput.resolve("com/example/GeneratedJavaMessage.class")));
    }

    private static void writeWorkspace(Path workspace, URI repository) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "ksp-test-workspace"

                [workspace.members]
                include = ["apps/consumer"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository));
    }

    private static void writeMember(Path member, String message) throws Exception {
        write(member.resolve("zolt.toml"), """
                [project]
                name = "consumer"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [toolchain.kotlin]
                version = "%s"

                [test.sources]
                kotlin = ["src/test/kotlin"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"

                [dependencies.test]
                "org.junit.platform:junit-platform-console-standalone" = "%s"

                [generated.tools.ksp]
                version = "%s"
                coordinates = [
                    { coordinate = "%s:%s", version = "%s" },
                ]

                [generated.test.symbols]
                kind = "ksp"
                options = { "fixture.message" = "%s" }
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                JUnitConsoleCliFixture.VERSION,
                KspCliFixture.KSP_VERSION,
                KspCliFixture.PROCESSOR_GROUP,
                KspCliFixture.PROCESSOR_ARTIFACT,
                KspCliFixture.PROCESSOR_VERSION,
                message));
        write(member.resolve("src/main/java/com/example/Application.java"), """
                package com.example;

                public final class Application {
                    private Application() {}

                    public static String value() {
                        return "main-output";
                    }
                }
                """);
        write(member.resolve("src/test/kotlin/com/example/WorkspaceKspTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class WorkspaceKspTest {
                    @Test
                    fun consumesMainAndEveryGeneratedLane() {
                        assertEquals("main-output", Application.value())
                        val generated = GeneratedKspMessage.value()
                        assertEquals(generated, GeneratedJavaMessage.value())
                        val resource = javaClass.classLoader
                            .getResourceAsStream("META-INF/ksp-cli.txt")!!
                            .bufferedReader()
                            .use { it.readText() }
                        assertEquals("${generated}-resource\\n", resource)
                    }
                }
                """);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String combined(CommandResult result) {
        return result.stdout() + "\n" + result.stderr();
    }
}

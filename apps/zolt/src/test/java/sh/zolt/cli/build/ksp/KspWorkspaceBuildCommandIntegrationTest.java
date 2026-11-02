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
import sh.zolt.cli.build.KotlinCompilerCliFixture;

/** Workspace boundary proof for KSP generation before provider and consumer compilation. */
final class KspWorkspaceBuildCommandIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void regeneratesKspProviderBeforeBuildingOfflineWorkspaceDependents() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KspCliFixture.publish(repository, tempDir.resolve("processor"));
            writeWorkspace(workspace, repository.baseUri());
            writeProvider(workspace.resolve("modules/provider"), "workspace-ksp");
            writeConsumer(workspace.resolve("apps/consumer"));

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
            assertGenerated(workspace, "workspace-ksp");
            assertRun(workspace, offlineCache, "workspace-ksp-workspace-ksp");

            writeProvider(workspace.resolve("modules/provider"), "updated-ksp");
            CommandResult refreshed = resolveOffline(workspace, offlineCache);
            assertEquals(0, refreshed.exitCode(), combined(refreshed));
            CommandResult updated = build(workspace, offlineCache);
            assertEquals(0, updated.exitCode(), combined(updated));
            assertGenerated(workspace, "updated-ksp");
            assertRun(workspace, offlineCache, "updated-ksp-updated-ksp");

            deleteTree(workspace.resolve("modules/provider/target"));
            CommandResult regenerated = build(workspace, offlineCache);
            assertEquals(0, regenerated.exitCode(), combined(regenerated));
            assertGenerated(workspace, "updated-ksp");
            assertRun(workspace, offlineCache, "updated-ksp-updated-ksp");
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "post-resolve workspace KSP commands must remain cache-only");
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

    private static CommandResult resolveOffline(Path workspace, Path cache) {
        return execute(
                "resolve",
                "--workspace",
                "--offline",
                "--no-progress",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void assertRun(Path workspace, Path cache, String expected) {
        CommandResult run = execute(
                "run",
                "--workspace",
                "--member", "apps/consumer",
                "--no-progress",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
        assertEquals(0, run.exitCode(), combined(run));
        assertTrue(run.stdout().contains(expected), run.stdout());
    }

    private static void assertGenerated(Path workspace, String message) throws Exception {
        Path target = workspace.resolve("modules/provider/target");
        Path generated = target.resolve("generated/ksp/main/symbols");
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
                Files.readString(target.resolve("classes/META-INF/ksp-cli.txt")));
        assertTrue(Files.isRegularFile(target.resolve(
                "classes/com/example/provider/ProviderApi.class")));
        assertTrue(Files.isRegularFile(workspace.resolve(
                "apps/consumer/target/classes/com/example/consumer/Main.class")));
    }

    private static void writeWorkspace(Path workspace, URI repository) throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "ksp-workspace"

                [workspace.members]
                include = ["modules/provider", "apps/consumer"]

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(repository));
    }

    private static void writeProvider(Path provider, String message) throws Exception {
        write(provider.resolve("zolt.toml"), """
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

                [generated.tools.ksp]
                version = "%s"
                coordinates = [
                    { coordinate = "%s:%s", version = "%s" },
                ]

                [generated.main.symbols]
                kind = "ksp"
                options = { "fixture.message" = "%s" }
                """.formatted(
                Runtime.version().feature(),
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KspCliFixture.KSP_VERSION,
                KspCliFixture.PROCESSOR_GROUP,
                KspCliFixture.PROCESSOR_ARTIFACT,
                KspCliFixture.PROCESSOR_VERSION,
                message));
        write(provider.resolve("src/main/kotlin/com/example/provider/ProviderApi.kt"), """
                package com.example.provider

                import com.example.GeneratedJavaMessage
                import com.example.GeneratedKspMessage

                object ProviderApi {
                    @JvmStatic
                    fun value(): String =
                        GeneratedKspMessage.value() + "-" + GeneratedJavaMessage.value()
                }
                """);
    }

    private static void writeConsumer(Path consumer) throws Exception {
        write(consumer.resolve("zolt.toml"), """
                [project]
                name = "consumer"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.consumer.Main"

                [dependencies]
                "com.example:provider" = { workspace = true }
                """.formatted(Runtime.version().feature()));
        write(consumer.resolve("src/main/java/com/example/consumer/Main.java"), """
                package com.example.consumer;

                import com.example.provider.ProviderApi;

                public final class Main {
                    private Main() {}

                    public static void main(String[] args) {
                        System.out.println(ProviderApi.value());
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

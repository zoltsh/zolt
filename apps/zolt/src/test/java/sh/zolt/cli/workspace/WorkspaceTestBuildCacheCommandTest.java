package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** CLI proof that workspace test routes share the configured main/test output cache. */
@Isolated("mutates user.home so the command reads an isolated build-cache config")
final class WorkspaceTestBuildCacheCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void restoresWorkspaceMainAndTestOutputsAndHonorsNoBuildCache() throws Exception {
        assumeTrue(System.getenv("ZOLT_USER_HOME") == null, "test needs an isolated user.home fallback");
        String previousUserHome = System.getProperty("user.home");
        Path fakeUserHome = tempDir.resolve("fake-user-home");
        System.setProperty("user.home", fakeUserHome.toString());
        try {
            Path workspace = writeWorkspace();
            Path artifactCache = tempDir.resolve("artifact-cache");
            Path buildCache = configureBuildCache(fakeUserHome);
            writeFakeConsoleJar(artifactCache.resolve(
                    "org/junit/platform/junit-platform-console-standalone/1.11.4/"
                            + "junit-platform-console-standalone-1.11.4.jar"));
            WorkspaceTestCommandTestSupport.writeWorkspaceTestLockfile(
                    workspace, artifactCache, "apps/api", "modules/core");

            CommandResult first = compileTests(workspace, artifactCache, false);
            assertEquals(0, first.exitCode(), first.stderr());
            Path coreClass = workspace.resolve(
                    "modules/core/target/classes/com/example/core/Core.class");
            Path mainClass = workspace.resolve("apps/api/target/classes/com/example/api/Api.class");
            Path testClass = workspace.resolve(
                    "apps/api/target/test-classes/com/example/api/ApiTest.class");
            byte[] coreBytes = Files.readAllBytes(coreClass);
            byte[] mainBytes = Files.readAllBytes(mainClass);
            byte[] testBytes = Files.readAllBytes(testClass);
            assertTrue(Files.isDirectory(buildCache));
            Map<String, String> storedMetadata = cacheMetadata(buildCache);
            assertTrue(
                    storedMetadata.values().stream().anyMatch(value -> value.contains("scope=main\n")),
                    storedMetadata.toString());
            assertTrue(
                    storedMetadata.values().stream().anyMatch(value -> value.contains("scope=test\n")),
                    storedMetadata.toString());

            wipeMemberTargets(workspace);
            CommandResult restored = runTests(workspace, artifactCache);
            assertEquals(0, restored.exitCode(), restored.stderr());
            assertTrue(restored.stdout().contains("fake console"), restored.stdout());
            assertTrue(
                    restored.stderr().contains(
                            "\"workspaceTestRuntimeToolchainIdentityCalculations\":\"1\""),
                    restored.stderr());
            assertArrayEquals(coreBytes, Files.readAllBytes(coreClass));
            assertArrayEquals(mainBytes, Files.readAllBytes(mainClass));
            assertArrayEquals(testBytes, Files.readAllBytes(testClass));
            assertFalse(Files.exists(workspace.resolve(
                    "modules/core/target/classes/.zolt-incremental-main.state")));
            assertFalse(Files.exists(workspace.resolve(
                    "apps/api/target/classes/.zolt-incremental-main.state")));
            assertFalse(Files.exists(workspace.resolve(
                    "apps/api/target/test-classes/.zolt-incremental-test.state")));

            wipeMemberTargets(workspace);
            CommandResult bypassed = compileTests(workspace, artifactCache, true);
            assertEquals(0, bypassed.exitCode(), bypassed.stderr());
            assertFalse(bypassed.stdout().contains("fake console"), bypassed.stdout());
            assertTrue(Files.isRegularFile(workspace.resolve(
                    "modules/core/target/classes/.zolt-incremental-main.state")));
            assertTrue(Files.isRegularFile(workspace.resolve(
                    "apps/api/target/classes/.zolt-incremental-main.state")));
            assertTrue(Files.isRegularFile(workspace.resolve(
                    "apps/api/target/test-classes/.zolt-incremental-test.state")));
            assertEquals(storedMetadata, cacheMetadata(buildCache));
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private Path writeWorkspace() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path api = workspace.resolve("apps/api");
        Path core = workspace.resolve("modules/core");
        Files.createDirectories(api);
        Files.createDirectories(core);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "workspace-test-cache"

                [workspace.members]
                include = ["apps/api", "modules/core"]
                """);
        Files.writeString(core.resolve("zolt.toml"), memberConfig("core"));
        Path coreSource = core.resolve("src/main/java/com/example/core/Core.java");
        Files.createDirectories(coreSource.getParent());
        Files.writeString(coreSource, """
                package com.example.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        Files.writeString(api.resolve("zolt.toml"), memberConfig("api") + testToolchain() + """

                [dependencies]
                "com.example:core" = { workspace = true }
                """);
        Path apiSource = api.resolve("src/main/java/com/example/api/Api.java");
        Files.createDirectories(apiSource.getParent());
        Files.writeString(apiSource, """
                package com.example.api;

                import com.example.core.Core;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return Core.message();
                    }
                }
                """);
        Path testSource = api.resolve("src/test/java/com/example/api/ApiTest.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, """
                package com.example.api;

                public final class ApiTest {
                    public String message() {
                        return Api.message();
                    }
                }
                """);
        return workspace;
    }

    private static String testToolchain() {
        return """

                [toolchain.java]
                version = %d
                features = []
                policy = "prefer-managed"

                [toolchain.java.test]
                version = %d
                """.formatted(
                Runtime.version().feature(),
                Runtime.version().feature());
    }

    private static Path configureBuildCache(Path fakeUserHome) throws IOException {
        Path globalDirectory = fakeUserHome.resolve(".zolt");
        Files.createDirectories(globalDirectory);
        Files.writeString(globalDirectory.resolve("config.toml"), """
                version = 1

                [buildCache]
                enabled = true
                dir = "build-cache"
                """);
        return globalDirectory.resolve("build-cache");
    }

    private static CommandResult compileTests(Path workspace, Path artifactCache, boolean bypassCache) {
        java.util.List<String> arguments = new java.util.ArrayList<>(java.util.List.of(
                "test",
                "--workspace",
                "--member", "apps/api",
                "--compile-only",
                "--cwd", workspace.toString(),
                "--cache-root", artifactCache.toString()));
        if (bypassCache) {
            arguments.add("--no-build-cache");
        }
        return execute(arguments.toArray(String[]::new));
    }

    private static CommandResult runTests(Path workspace, Path artifactCache) {
        return execute(
                "test",
                "--workspace",
                "--member", "apps/api",
                "--timings",
                "--timings-format", "json",
                "--cwd", workspace.toString(),
                "--cache-root", artifactCache.toString());
    }

    private static Map<String, String> cacheMetadata(Path buildCache) throws IOException {
        Map<String, String> metadata = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(buildCache)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".zbc.meta"))
                    .toList()) {
                metadata.put(buildCache.relativize(path).toString(), Files.readString(path));
            }
        }
        return Map.copyOf(metadata);
    }

    private static void wipeMemberTargets(Path workspace) throws IOException {
        deleteTree(workspace.resolve("apps/api/target"));
        deleteTree(workspace.resolve("modules/core/target"));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}

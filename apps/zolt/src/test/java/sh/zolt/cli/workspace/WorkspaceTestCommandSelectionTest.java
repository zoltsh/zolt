package sh.zolt.cli.workspace;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceTestCommandSelectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void testWorkspaceMemberAppliesSelectedTestPattern() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-selected-tests");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Path workerDir = workspaceDir.resolve("apps/worker");
        Path cacheRoot = tempDir.resolve("cache-selected-tests");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.createDirectories(workerDir);
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"

                [workspace.members]
                include = ["apps/api", "modules/core", "apps/worker"]
                """);
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));
        Path coreSource = coreDir.resolve("src/main/java/com/example/core/Core.java");
        Files.createDirectories(coreSource.getParent());
        Files.writeString(coreSource, """
                package com.example.core;

                public final class Core {
                    public static String message() {
                        return "core";
                    }
                }
                """);
        Files.writeString(workerDir.resolve("zolt.toml"), memberConfig("worker"));
        Path workerSource = workerDir.resolve("src/main/java/com/example/worker/Worker.java");
        Files.createDirectories(workerSource.getParent());
        Files.writeString(workerSource, """
                package com.example.worker;

                public final class Worker {
                    public static String message() {
                        return "worker";
                    }
                }
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api") + """

                [dependencies]
                "com.example:core" = { workspace = true }
                """);
        Path apiSource = apiDir.resolve("src/main/java/com/example/api/Api.java");
        Files.createDirectories(apiSource.getParent());
        Files.writeString(apiSource, """
                package com.example.api;

                import com.example.core.Core;

                public final class Api {
                    public static String message() {
                        return Core.message();
                    }
                }
                """);
        Path apiTest = apiDir.resolve("src/test/java/com/example/api/ApiTest.java");
        Files.createDirectories(apiTest.getParent());
        Files.writeString(apiTest, """
                package com.example.api;

                public final class ApiTest {
                    public String message() {
                        return Api.message();
                    }
                }
                """);
        WorkspaceTestCommandTestSupport.writeWorkspaceTestLockfile(
                workspaceDir, cacheRoot, "apps/api", "modules/core", "apps/worker");

        CommandResult result = execute(
                "test",
                "--workspace",
                "--member", "apps/api",
                "--tests", "*ApiTest",
                "--timings",
                "--timings-format", "json",
                "--cwd", apiDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("Tests passed in apps/api"));
        assertFalse(result.stdout().contains("Tests passed in modules/core"));
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(5, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"workspace lock freshness\""));
        assertTrue(lines[0].contains("\"workspaceLockFreshness\""));
        assertTrue(lines[1].contains("\"selectedMembers\":\"1\""));
        assertTrue(lines[1].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[2].contains("\"phase\":\"build workspace test inputs\""));
        assertTrue(lines[2].contains("\"members\":\"2\""));
        assertTrue(lines[2].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[2].contains("\"selectedMembers\":\"1\""));
        assertTrue(lines[2].contains("\"dependencyMembers\":\"1\""));
        assertTrue(lines[3].contains("\"phase\":\"run workspace test members\""));
        assertTrue(lines[3].contains("\"members\":\"1\""));
        assertTrue(lines[3].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[3].contains("\"selectedMembers\":\"1\""));
        assertTrue(lines[3].contains("\"dependencyMembers\":\"1\""));
        assertTrue(lines[3].contains("\"testPatterns\":\"1\""));
        assertTrue(lines[4].contains("\"phase\":\"test workspace\""));
        assertTrue(lines[4].contains("\"members\":\"1\""));
        assertTrue(lines[4].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[4].contains("\"selectedMembers\":\"1\""));
        assertTrue(lines[4].contains("\"dependencyMembers\":\"1\""));
        assertTrue(lines[4].contains("\"testPatterns\":\"1\""));
        assertTrue(Files.exists(coreDir.resolve("target/classes/com/example/core/Core.class")));
        assertTrue(Files.exists(apiDir.resolve("target/test-classes/com/example/api/ApiTest.class")));
        assertFalse(Files.exists(workerDir.resolve("target/classes/com/example/worker/Worker.class")));
    }

    @Test
    void testWorkspaceDefaultMembersSubsetReportsTestedVersusTotal() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-default-subset");
        Path cacheRoot = tempDir.resolve("cache-default-subset");
        writeApiCoreWorkspace(workspaceDir, cacheRoot, """
                [workspace]
                name = "workspace"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api", "modules/core"]
                """);

        CommandResult result = execute(
                "test",
                "--workspace",
                "--cwd", workspaceDir.resolve("apps/api").toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Tests passed in apps/api"));
        assertFalse(result.stdout().contains("Tests passed in modules/core"));
        assertTrue(result.stdout().contains("Tested 1 of 2 workspace members; use --all to test every member"));
        assertFalse(result.stdout().contains("Tests passed for 1 workspace members"));
    }

    @Test
    void testWorkspaceAllDoesNotReadAsPartialRun() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-all-run");
        Path cacheRoot = tempDir.resolve("cache-all-run");
        writeApiCoreWorkspace(workspaceDir, cacheRoot, """
                [workspace]
                name = "workspace"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api", "modules/core"]
                """);

        CommandResult result = execute(
                "test",
                "--workspace",
                "--all",
                "--cwd", workspaceDir.resolve("apps/api").toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Tests passed in apps/api"));
        assertTrue(result.stdout().contains("Tests passed in modules/core"));
        assertTrue(result.stdout().contains("Tests passed for 2 workspace members"));
        assertFalse(result.stdout().contains(" of 2 workspace members"));
    }

    private static void writeApiCoreWorkspace(Path workspaceDir, Path cacheRoot, String workspaceToml)
            throws IOException {
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.writeString(workspaceDir.resolve("zolt.toml"), workspaceToml);
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));
        Path coreSource = coreDir.resolve("src/main/java/com/example/core/Core.java");
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
        Path coreTest = coreDir.resolve("src/test/java/com/example/core/CoreTest.java");
        Files.createDirectories(coreTest.getParent());
        Files.writeString(coreTest, """
                package com.example.core;

                public final class CoreTest {
                    public String message() {
                        return Core.message();
                    }
                }
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api") + """

                [dependencies]
                "com.example:core" = { workspace = true }
                """);
        Path apiSource = apiDir.resolve("src/main/java/com/example/api/Api.java");
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
        Path apiTest = apiDir.resolve("src/test/java/com/example/api/ApiTest.java");
        Files.createDirectories(apiTest.getParent());
        Files.writeString(apiTest, """
                package com.example.api;

                import com.example.core.Core;

                public final class ApiTest {
                    public String message() {
                        return Api.message() + Core.message();
                    }
                }
                """);
        WorkspaceTestCommandTestSupport.writeWorkspaceTestLockfile(
                workspaceDir, cacheRoot, "apps/api", "modules/core");
    }
}

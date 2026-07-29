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

final class WorkspaceTestCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void testWorkspaceRunsMembersInDependencyOrder() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"
                members = ["apps/api", "modules/core"]
                """);
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
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api") + """

                [dependencies]
                "com.example:core" = { workspace = "modules/core" }
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
        WorkspaceTestCommandTestSupport.writeWorkspaceTestLockfile(workspaceDir, "apps/api", "modules/core");

        CommandResult result = execute(
                "test",
                "--workspace",
                "--all",
                "--reports-dir", "target/test-reports",
                "--timings",
                "--timings-format", "json",
                "--cwd", apiDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("Tests passed in modules/core"));
        assertTrue(result.stdout().contains("→ wrote "));
        assertTrue(result.stdout().contains("Tests passed in apps/api"));
        assertTrue(result.stdout().contains("Tests passed for 2 workspace members"));
        assertTrue(Files.exists(coreDir.resolve("target/test-reports/modules/core/TEST-fake-console.xml")));
        assertTrue(Files.exists(apiDir.resolve("target/test-reports/apps/api/TEST-fake-console.xml")));
        String[] lines = result.stderr().lines().toArray(String[]::new);
        assertEquals(4, lines.length);
        assertTrue(lines[0].contains("\"phase\":\"plan workspace tests\""));
        assertTrue(lines[0].contains("\"depth\":1"));
        assertTrue(lines[0].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[0].contains("\"selectedMembers\":\"2\""));
        assertTrue(lines[1].contains("\"phase\":\"build workspace test inputs\""));
        assertTrue(lines[1].contains("\"depth\":1"));
        assertTrue(lines[1].contains("\"members\":\"2\""));
        assertTrue(lines[1].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[1].contains("\"selectedMembers\":\"2\""));
        assertTrue(lines[1].contains("\"dependencyMembers\":\"0\""));
        assertTrue(lines[1].contains("\"sourceFiles\":\"2\""));
        assertTrue(lines[1].contains("\"mainCompilationsSkipped\":\"0\""));
        assertTrue(lines[1].contains("\"mainCompilationsExecuted\":\"2\""));
        assertTrue(lines[2].contains("\"phase\":\"run workspace test members\""));
        assertTrue(lines[2].contains("\"depth\":1"));
        assertTrue(lines[2].contains("\"members\":\"2\""));
        assertTrue(lines[2].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[2].contains("\"selectedMembers\":\"2\""));
        assertTrue(lines[2].contains("\"dependencyMembers\":\"0\""));
        assertTrue(lines[2].contains("\"testSourceFiles\":\"1\""));
        assertTrue(lines[2].contains("\"mainCompilationsSkipped\":\"0\""));
        assertTrue(lines[2].contains("\"mainCompilationsExecuted\":\"2\""));
        assertTrue(lines[2].contains("\"testCompilationsSkipped\""));
        assertTrue(lines[2].contains("\"testCompilationsExecuted\""));
        assertTrue(lines[2].contains("\"testDiscoveryScanRoots\""));
        assertTrue(lines[3].contains("\"phase\":\"test workspace\""));
        assertTrue(lines[3].contains("\"depth\":0"));
        assertTrue(lines[3].contains("\"members\":\"2\""));
        assertTrue(lines[3].contains("\"includedMembers\":\"2\""));
        assertTrue(lines[3].contains("\"selectedMembers\":\"2\""));
        assertTrue(lines[3].contains("\"dependencyMembers\":\"0\""));
        assertTrue(lines[3].contains("\"testSourceFiles\":\"1\""));
        assertTrue(lines[3].contains("\"mainCompilationsSkipped\":\"0\""));
        assertTrue(lines[3].contains("\"mainCompilationsExecuted\":\"2\""));
        assertTrue(lines[3].contains("\"testCompilationsSkipped\""));
        assertTrue(lines[3].contains("\"testCompilationsExecuted\""));
        assertTrue(lines[3].contains("\"testDiscoveryScanRoots\""));
        assertTrue(Files.exists(coreDir.resolve("target/classes/com/example/core/Core.class")));
        assertTrue(Files.exists(apiDir.resolve("target/classes/com/example/api/Api.class")));
        assertTrue(Files.exists(apiDir.resolve("target/test-classes/com/example/api/ApiTest.class")));

        CommandResult compileOnly = execute(
                "test",
                "--workspace",
                "--all",
                "--compile-only",
                "--timings",
                "--timings-format", "json",
                "--cwd", apiDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, compileOnly.exitCode());
        assertTrue(compileOnly.stdout().contains("Tests compiled in modules/core"));
        assertTrue(compileOnly.stdout().contains("Tests compiled in apps/api"));
        assertTrue(compileOnly.stdout().contains("Compiled tests for 2 workspace members"));
        assertFalse(compileOnly.stdout().contains("fake console"));
        String[] compileLines = compileOnly.stderr().lines().toArray(String[]::new);
        assertEquals(4, compileLines.length);
        assertTrue(compileLines[2].contains("\"phase\":\"compile workspace test members\""));
        assertTrue(compileLines[2].contains("\"workspaceBuildMaxWorkers\":\"2\""));
        assertTrue(compileLines[3].contains("\"phase\":\"compile workspace tests\""));
    }

}

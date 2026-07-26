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

final class WorkspaceIntegrationTestCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void integrationTestWorkspaceRunsSelectedMembersWithSeparateReports() throws IOException {
        Path workspaceDir = tempDir.resolve("workspace-it");
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Path workerDir = workspaceDir.resolve("apps/worker");
        Path cacheRoot = tempDir.resolve("cache-it");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.createDirectories(workerDir);
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"));
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"
                members = ["apps/api", "modules/core", "apps/worker"]
                """);
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));
        Path coreSource = coreDir.resolve("src/main/java/com/example/core/Core.java");
        Files.createDirectories(coreSource.getParent());
        Files.writeString(coreSource, "package com.example.core; public final class Core { public static String message() { return \"core\"; } }\n");
        Files.writeString(workerDir.resolve("zolt.toml"), memberConfig("worker"));
        Path workerSource = workerDir.resolve("src/main/java/com/example/worker/Worker.java");
        Files.createDirectories(workerSource.getParent());
        Files.writeString(workerSource, "package com.example.worker; public final class Worker {}\n");
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api") + """

                [dependencies]
                "com.example:core" = { workspace = "modules/core" }
                """);
        Path apiSource = apiDir.resolve("src/main/java/com/example/api/Api.java");
        Files.createDirectories(apiSource.getParent());
        Files.writeString(apiSource, "package com.example.api; import com.example.core.Core; public final class Api { public static String message() { return Core.message(); } }\n");
        Path apiIntegrationTest = apiDir.resolve("src/integration-test/java/com/example/api/ApiIT.java");
        Files.createDirectories(apiIntegrationTest.getParent());
        Files.writeString(apiIntegrationTest, "package com.example.api; public final class ApiIT { String ok() { return Api.message(); } }\n");
        WorkspaceTestCommandTestSupport.writeWorkspaceTestLockfile(
                workspaceDir, "apps/api", "modules/core", "apps/worker");

        CommandResult result = execute(
                "integration-test",
                "--workspace",
                "--member", "apps/api",
                "--tests", "*ApiIT",
                "--cwd", apiDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("fake console"));
        assertTrue(result.stdout().contains("Integration tests passed in apps/api"));
        assertTrue(result.stdout().contains("→ wrote "));
        assertTrue(result.stdout().contains("Integration tests passed for 1 workspace members"));
        assertTrue(Files.exists(coreDir.resolve("target/classes/com/example/core/Core.class")));
        assertTrue(Files.exists(apiDir.resolve("target/integration-test-classes/com/example/api/ApiIT.class")));
        assertTrue(Files.exists(apiDir.resolve("target/integration-test-reports/apps/api/TEST-fake-console.xml")));
        assertFalse(Files.exists(workerDir.resolve("target/classes/com/example/worker/Worker.class")));
    }
}

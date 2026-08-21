package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.bomConfig;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceProviderPackageModeCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolveRejectsEveryNonLibraryProviderBeforeWritingTheWorkspaceLock()
            throws IOException {
        for (String mode : List.of(
                "spring-boot",
                "quarkus",
                "uber-jar",
                "war",
                "spring-boot-war",
                "bom")) {
            Path workspace = writeWorkspace(mode);

            CommandResult result = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspace.toString(),
                    "--cache-root", tempDir.resolve("cache-" + mode).toString());

            // The effective workspace refuses the edge before any lock is written (design 9.8).
            assertEquals(1, result.exitCode(), result.stderr());
            assertTrue(result.stderr().contains("`" + mode + "`"), result.stderr());
            assertTrue(result.stderr().contains("not a consumable library JAR"), result.stderr());
            assertFalse(Files.exists(workspace.resolve("zolt.lock")), mode);
        }
    }

    @Test
    void resolveMaterializesAThinWorkspaceProvider() throws IOException {
        Path workspace = writeWorkspace("jar");

        CommandResult result = execute(
                "resolve",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache-jar").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        String lockfile = Files.readString(workspace.resolve("zolt.lock"));
        assertTrue(lockfile.startsWith("version = 7"), lockfile);
        assertTrue(lockfile.contains("id = \"com.example:provider\""), lockfile);
        assertTrue(lockfile.contains("workspace = \"modules/provider\""), lockfile);
    }

    private Path writeWorkspace(String mode) throws IOException {
        Path workspace = tempDir.resolve(mode);
        Path app = workspace.resolve("apps/app");
        Path provider = workspace.resolve("modules/provider");
        Files.createDirectories(app);
        Files.createDirectories(provider);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "provider-%s"

                [workspace.members]
                include = ["modules/provider", "apps/app"]
                """.formatted(mode));
        Files.writeString(app.resolve("zolt.toml"), memberConfig("app") + """

                [dependencies]
                "com.example:provider" = { workspace = true }
                """);
        Files.writeString(provider.resolve("zolt.toml"), mode.equals("bom")
                ? bomConfig("provider") + """

                [bom]
                members = true
                """
                : memberConfig("provider") + """

                [package]
                mode = "%s"
                """.formatted(mode));
        return workspace;
    }
}

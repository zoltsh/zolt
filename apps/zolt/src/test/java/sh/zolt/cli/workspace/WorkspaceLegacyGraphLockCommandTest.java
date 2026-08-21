package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceLegacyGraphLockCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void workspaceGraphCommandsRefuseAV4LockBeforeExecution()
            throws IOException {
        Path workspace = writeWorkspace();

        CommandResult build = execute(
                "build",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        CommandResult test = execute(
                "test",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        CommandResult packageResult = execute(
                "package",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        CommandResult run = execute(
                "run",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        CommandResult nativeResult = execute(
                "native",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        CommandResult publish = execute(
                "publish",
                "--workspace",
                "--dry-run",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        CommandResult sbom = execute(
                "sbom",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache").toString());
        CommandResult ide = execute(
                "ide",
                "model",
                "--workspace",
                "--format", "json",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        for (CommandResult result : new CommandResult[] {
                build,
                test,
                packageResult,
                run,
                nativeResult
        }) {
            assertEquals(1, result.exitCode(), result.stderr());
            assertTrue(
                    result.stderr().contains("version 4 is older than this Zolt supports (current 7)"),
                    result.stderr());
            assertTrue(result.stderr().contains("zolt resolve` with this Zolt version"), result.stderr());
        }
        for (CommandResult result : new CommandResult[] {
                publish,
                sbom
        }) {
            assertEquals(1, result.exitCode(), result.stderr());
            assertTrue(
                    result.stderr().contains("version 4 is older than this Zolt supports (current 7)"),
                    result.stderr());
            assertTrue(result.stderr().contains("zolt resolve` with this Zolt version"), result.stderr());
        }
        assertEquals(0, ide.exitCode(), ide.stderr());
        assertEquals("", ide.stderr());
        assertTrue(ide.stdout().contains("\"code\": \"LOCKFILE_UNREADABLE\""), ide.stdout());
        assertTrue(ide.stdout().contains("version 4"), ide.stdout());
        assertTrue(ide.stdout().contains("\"nextStep\": \"Run zolt resolve --workspace.\""), ide.stdout());
        assertFalse(Files.exists(workspace.resolve(
                "apps/app/target/classes/com/acme/app/App.class")));
        assertTrue(Files.readString(workspace.resolve("zolt.lock")).startsWith("version = 4"));
    }

    private Path writeWorkspace() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path core = workspace.resolve("modules/core");
        Path app = workspace.resolve("apps/app");
        Files.createDirectories(core);
        Files.createDirectories(app);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "legacy-optional-boundary"

                [workspace.members]
                include = ["modules/core", "apps/app"]
                """);
        Files.writeString(core.resolve("zolt.toml"), memberConfig("core") + """

                [dependencies.api]
                "com.example:feature-sdk" = { version = "1.0.0", optional = true }
                """);
        Files.writeString(app.resolve("zolt.toml"), memberConfig("app") + """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        Files.writeString(workspace.resolve("zolt.lock"), """
                version = 4

                [[package]]
                id = "com.example:feature-sdk"
                version = "1.0.0"
                source = "test"
                scope = "compile"
                direct = true
                members = ["modules/core"]
                dependencies = []

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                members = ["apps/app"]
                dependencies = []

                [[memberGraph]]
                member = "modules/core"
                id = "com.example:feature-sdk"
                version = "1.0.0"
                scope = "compile"
                dependencies = []
                """);
        return workspace;
    }

    private static String memberConfig(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = 21
                """.formatted(name);
    }
}

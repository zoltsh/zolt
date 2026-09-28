package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.build.CompilationSemantics;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.WorkspaceCommandFixture;
import sh.zolt.cli.WorkspaceCommandFixture.WorkspaceApplicationFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceStateUpgradeCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void buildWorkspaceInvalidatesPreConservativeReuseState() throws IOException {
        WorkspaceApplicationFixture fixture = WorkspaceCommandFixture.create(tempDir, "workspace");
        Path cacheRoot = tempDir.resolve("cache");
        CommandResult first = execute(
                "build",
                "--workspace",
                "--all",
                "--no-build-cache",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", cacheRoot.toString());
        Path state = fixture.workspaceDir().resolve(".zolt/workspace-state-v1");
        Path fingerprint = fixture.coreDir().resolve("target/classes/.zolt-build-main.fingerprint");
        Path coreClass = fixture.coreDir().resolve("target/classes/com/example/core/Core.class");
        byte[] stale = "stale pre-upgrade class".getBytes(StandardCharsets.UTF_8);

        replaceVersion(state, "4");
        replaceVersion(fingerprint, "2");
        Files.write(coreClass, stale);

        CommandResult upgraded = execute(
                "build",
                "--workspace",
                "--all",
                "--no-build-cache",
                "--cwd", fixture.apiDir().toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, first.exitCode(), first.stderr());
        assertEquals(0, upgraded.exitCode(), upgraded.stderr());
        assertTrue(upgraded.stdout().contains("Compiled 1 main source files in modules/core"), upgraded.stdout());
        assertFalse(Arrays.equals(stale, Files.readAllBytes(coreClass)));
        assertTrue(Files.readString(state).startsWith("version=5\nchecksum="));
        assertTrue(Files.readString(fingerprint)
                .startsWith("version=" + CompilationSemantics.VERSION + "\n"));
    }

    private static void replaceVersion(Path path, String version) throws IOException {
        String content = Files.readString(path);
        int lineBreak = content.indexOf('\n');
        Files.writeString(path, "version=" + version + content.substring(lineBreak));
    }
}

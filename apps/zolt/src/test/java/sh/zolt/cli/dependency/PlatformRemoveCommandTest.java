package sh.zolt.cli.dependency;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlatformRemoveCommandTest extends PlatformCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void platformRemoveDeletesPlatformAndRefreshesLockfile() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);
        CommandResult add = execute(
                "platforms",
                "set",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "com.example:enterprise-platform", "2026.1.0");

        CommandResult remove = execute(
                "platforms",
                "remove",
                "--directory", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "com.example:enterprise-platform");

        assertEquals(0, add.exitCode(), add.stderr());
        assertEquals(0, remove.exitCode(), remove.stderr());
        assertTrue(remove.stdout().contains("Removed platform com.example:enterprise-platform from [platforms]"));
        assertTrue(remove.stdout().contains("Resolved 0 packages"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertFalse(config.contains("\"com.example:enterprise-platform\""));
    }

    @Test
    void platformRemoveRejectsVersionedCoordinate() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "platforms",
                "remove",
                "--cwd", projectDir.toString(),
                "com.example:enterprise-platform:2026.1.0");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Invalid dependency coordinate"));
    }
}

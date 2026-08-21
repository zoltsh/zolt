package sh.zolt.cli.command.dependency;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code zolt add group:artifact:1.0-SNAPSHOT} writes the declaration to {@code zolt.toml} under the
 * deferred-SNAPSHOT policy and leaves acceptance to resolve, rather than rejecting the coordinate up
 * front. Non-SNAPSHOT dynamic versions (ranges, dynamic selectors) still fail at the add command.
 */
final class AddCommandSnapshotTest {
    @TempDir
    private Path tempDir;

    @Test
    void addWritesSnapshotDependencyWhenResolveIsSkipped() throws IOException {
        Path projectDir = tempDir.resolve("add-snap");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("add-snap"));

        CommandResult result = execute(
                "add", "com.example:snap:1.0.0-SNAPSHOT",
                "--no-resolve",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        String toml = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(toml.contains("com.example:snap"), toml);
        assertTrue(toml.contains("1.0.0-SNAPSHOT"), toml);
    }

    @Test
    void failedSnapshotResolveLeavesManifestAndLockfileUnchanged() throws IOException {
        Path projectDir = tempDir.resolve("add-snap-resolve");
        Files.createDirectories(projectDir);
        String original = memberConfig("add-snap-resolve") + """

                """;
        Files.writeString(projectDir.resolve("zolt.toml"), original);

        CommandResult result = execute(
                "add", "com.example:snap:1.0.0-SNAPSHOT",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unsupported SNAPSHOT dependency version `1.0.0-SNAPSHOT`"),
                result.stderr());
        assertEquals(original, Files.readString(projectDir.resolve("zolt.toml")));
        assertTrue(Files.notExists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void failedResolvePreservesAnExistingLockfileByteForByte() throws IOException {
        Path projectDir = tempDir.resolve("add-snap-existing-lock");
        Files.createDirectories(projectDir);
        String originalManifest = memberConfig("add-snap-existing-lock");
        Files.writeString(projectDir.resolve("zolt.toml"), originalManifest);
        CommandResult initialResolve = execute(
                "resolve",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("existing-lock-cache").toString());
        assertEquals(0, initialResolve.exitCode(), initialResolve.stderr());
        String originalLockfile = Files.readString(projectDir.resolve("zolt.lock"));

        CommandResult result = execute(
                "add", "com.example:snap:1.0.0-SNAPSHOT",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("existing-lock-cache").toString());

        assertEquals(1, result.exitCode());
        assertEquals(originalManifest, Files.readString(projectDir.resolve("zolt.toml")));
        assertEquals(originalLockfile, Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void addStillRejectsNonSnapshotDynamicVersions() throws IOException {
        Path projectDir = tempDir.resolve("add-range");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("add-range"));

        CommandResult result = execute(
                "add", "com.example:lib:[1.0,2.0)",
                "--no-resolve",
                "--cwd", projectDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Invalid external dependency version `[1.0,2.0)`"), result.stderr());
    }
}

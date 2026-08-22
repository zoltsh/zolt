package sh.zolt.cli.dependency;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AddCommandValidationTest {
    @TempDir
    private Path tempDir;

    @Test
    void addRejectsManagedDependencyWithExplicitVersion() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--managed",
                "com.example:legacy-api:1.0.0");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Managed dependency coordinate must not include a version."));
        assertTrue(result.stderr().contains("Next: Use `group:artifact`."));
    }

    @Test
    void addRejectsUnsupportedLiteralDependencyVersion() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        // A SNAPSHOT now writes and defers to resolve (see AddCommandSnapshotTest); a version range is
        // still rejected up front at the add command.
        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "com.example:legacy-api:[1.0,2.0)");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(
                "Invalid external dependency version `[1.0,2.0)` for dependency. Version ranges are not supported"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertFalse(config.contains("legacy-api"));
    }

    @Test
    void addRejectsUnknownVersionRef() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--version-ref",
                "guava",
                "com.google.guava:guava");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown versionRef `guava`."));
        assertTrue(result.stderr().contains("Next: Add [versions].guava or use an explicit version."));
    }

    @Test
    void addRejectsVersionRefWithManagedOrExplicitVersion() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult managedResult = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--managed",
                "--version-ref",
                "guava",
                "com.google.guava:guava");
        CommandResult explicitVersionResult = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--version-ref",
                "guava",
                "com.google.guava:guava:33.4.8-jre");

        assertEquals(1, managedResult.exitCode());
        assertTrue(managedResult.stderr().contains("`--managed` and `--version-ref` cannot be used together"));
        assertEquals(1, explicitVersionResult.exitCode());
        assertTrue(explicitVersionResult.stderr().contains(
                "Version-ref dependency coordinate must not include a version."));
        assertTrue(explicitVersionResult.stderr().contains(
                "Next: Use `--version-ref guava group:artifact`."));
    }

    @Test
    void addRejectsUnknownDependencyScopeWithSupportedScopes() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "com.example:tool:1.0.0",
                "--scope", "compile-only");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unexpected dependency scope `compile-only`"), result.stderr());
        assertTrue(
                result.stderr().contains(
                        "zolt add group:artifact --scope "
                                + "<implementation|api|runtime|provided|dev|test|processor|test-processor>"),
                result.stderr());
    }

    private static void writeProjectConfig(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """
                main = "com.example.Main"

                [repositories.test]
                url = "https://repo.maven.apache.org/maven2"

                [dependencies.test]
                """);
    }
}

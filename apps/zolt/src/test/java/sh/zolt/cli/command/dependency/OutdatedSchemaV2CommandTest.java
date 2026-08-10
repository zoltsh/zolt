package sh.zolt.cli.command.dependency;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OutdatedSchemaV2CommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void standaloneSchemaV2ReportsCanonicalPaths() throws IOException {
        Path project = writeProject();

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--all",
                "--offline",
                "--cwd", project.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 2"));
        assertTrue(result.stdout().contains("\"manifestPath\": \"zolt.toml\""));
        assertTrue(result.stdout().contains("\"lockfilePath\": \"zolt.lock\""));
        assertTrue(result.stdout().contains(
                "\"targetId\": \"zt1_7JDO7hkQrBl5dUC14pm3rxY9MvxgOtULf2HZW3iM3j0\""));
        assertTrue(result.stdout().contains("\"updateable\": true"));
    }

    @Test
    void validSchemaV2FailureUsesTheSelectedEnvelope() {
        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--cwd", tempDir.resolve("missing").toString());

        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 2"));
        assertTrue(result.stdout().contains("\"status\": \"failed\""));
    }

    @Test
    void malformedRootWorkspaceFailsWithTheSelectedEnvelope() throws IOException {
        Path project = writeProject();
        Files.writeString(project.resolve("zolt.toml"), Files.readString(project.resolve("zolt.toml")) + """

                [workspace]
                name = "broken"
                members = ["missing-member"]
                """);

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "2",
                "--offline",
                "--cwd", project.toString());

        assertEquals(1, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"schemaVersion\": 2"));
        assertTrue(result.stdout().contains("\"status\": \"failed\""));
        assertTrue(result.stdout().contains("missing-member"));
    }

    @Test
    void schemaV1PreservesDecomposedDisplayLabelsAndVersionText() throws IOException {
        String decomposed = "cafe\u0301";
        Path project = tempDir.resolve(decomposed);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "com.example:lib" = "1.0.0-%s"
                """.formatted(decomposed));

        CommandResult result = execute(
                "outdated",
                "--format", "json",
                "--all",
                "--offline",
                "--cwd", project.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"label\": \"" + decomposed + "\""));
        assertTrue(result.stdout().contains("\"current\": \"1.0.0-" + decomposed + "\""));
    }

    @Test
    void schemaSelectionRequiresJsonAndOneSupportedVersion() throws IOException {
        Path project = writeProject();

        CommandResult text = execute(
                "outdated",
                "--schema-version", "2",
                "--cwd", project.toString());
        CommandResult unsupported = execute(
                "outdated",
                "--format", "json",
                "--schema-version", "3",
                "--cwd", project.toString());

        assertEquals(1, text.exitCode());
        assertTrue(text.stderr().contains("--schema-version is available only with --format json"));
        assertEquals(1, unsupported.exitCode());
        assertEquals("", unsupported.stderr());
        assertTrue(unsupported.stdout().contains("\"schemaVersion\": 1"));
        assertTrue(unsupported.stdout().contains("Unsupported outdated JSON schema version `3`"));
    }

    private Path writeProject() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        return project;
    }
}

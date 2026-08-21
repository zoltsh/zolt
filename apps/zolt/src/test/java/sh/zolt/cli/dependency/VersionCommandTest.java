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

final class VersionCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void versionPrintsZoltVersion() {
        CommandResult result = execute("--version");

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("0.1.0-SNAPSHOT\n", result.stdout());
    }

    @Test
    void versionCommandPrintsZoltVersion() {
        CommandResult result = execute("version");

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("0.1.0-SNAPSHOT\n", result.stdout());
    }

    @Test
    void versionRemoveDeletesUnusedAliasWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("version-alias-remove");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "version-alias-remove"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                guava = "33.4.8-jre"
                junit = "5.12.1"
                """);

        CommandResult result = execute(
                "--color=always",
                "versions",
                "remove",
                "--directory", projectDir.toString(),
                "--no-resolve",
                "guava");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\u001B[32mRemoved\u001B[0m version alias guava from [versions]"));
        assertFalse(result.stdout().contains("\u001B[32mRemoved version alias guava from [versions]\u001B[0m"));
        assertTrue(result.stdout().contains(
                "\u001B[32mSkipped\u001B[0m resolve; run zolt resolve to refresh zolt.lock."));
        assertFalse(result.stdout().contains(
                "\u001B[32mSkipped resolve; run zolt resolve to refresh zolt.lock.\u001B[0m"));
        assertEquals("", result.stderr());
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertFalse(config.contains("guava ="));
        assertTrue(config.contains("[versions]\njunit = \"5.12.1\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void versionRemoveRefreshesLockfileByDefault() throws IOException {
        Path projectDir = tempDir.resolve("version-alias-remove-resolve");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "version-alias-remove-resolve"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                guava = "33.4.8-jre"
                """);

        CommandResult result = execute(
                "versions",
                "remove",
                "--cwd", projectDir.toString(),
                "guava");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Removed version alias guava from [versions]"));
        assertTrue(result.stdout().contains("Resolved 0 packages"));
        assertTrue(Files.exists(projectDir.resolve("zolt.lock")));
        assertTrue(Files.readString(projectDir.resolve("zolt.toml")).contains("[versions]"));
    }

    @Test
    void versionRemoveRejectsReferencedAlias() throws IOException {
        Path projectDir = tempDir.resolve("version-alias-remove-referenced");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "version-alias-remove-referenced"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                guava = "33.4.8-jre"

                [dependencies]
                "com.google.guava:guava" = { versionRef = "guava" }
                """);

        CommandResult result = execute(
                "versions",
                "remove",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "guava");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Version alias `guava` is still referenced by [dependencies].com.google.guava:guava."));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("guava = \"33.4.8-jre\""));
        assertTrue(config.contains("\"com.google.guava:guava\" = { versionRef = \"guava\" }"));
    }

    @Test
    void versionRemoveRejectsAliasReferencedByPlatformConstraintAndOpenApiTool() throws IOException {
        Path projectDir = tempDir.resolve("version-alias-remove-reference-categories");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "version-alias-remove-reference-categories"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                shared = "1.0.0"

                [platforms]
                "com.example:platform" = { versionRef = "shared" }

                [dependencies.constraints]
                "com.example:core" = { versionRef = "shared" }

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                versionRef = "shared"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                """);

        CommandResult result = execute(
                "versions",
                "remove",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "shared");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("[platforms].com.example:platform"));
        assertTrue(result.stderr().contains("[dependencies.constraints].com.example:core"));
        assertTrue(result.stderr().contains("[generated.tools.openapi].versionRef"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("shared = \"1.0.0\""));
    }

}

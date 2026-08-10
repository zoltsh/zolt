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

final class VersionCommandSetTest {
    @TempDir
    private Path tempDir;

    @Test
    void versionSetAddsAndUpdatesAliasWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("version-alias-set");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "version-alias-set"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                "central" = "https://repo.maven.apache.org/maven2"
                """);

        CommandResult added = execute(
                "--color=always",
                "version",
                "set",
                "--directory", projectDir.toString(),
                "--no-resolve",
                "guava",
                "33.4.8-jre");

        assertEquals(0, added.exitCode());
        assertTrue(added.stdout().contains("\u001B[32mAdded\u001B[0m version alias guava = 33.4.8-jre to [versions]"));
        assertFalse(added.stdout().contains(
                "\u001B[32mAdded version alias guava = 33.4.8-jre to [versions]\u001B[0m"));
        assertTrue(added.stdout().contains(
                "\u001B[32mSkipped\u001B[0m resolve; run zolt resolve to refresh zolt.lock."));
        assertFalse(added.stdout().contains(
                "\u001B[32mSkipped resolve; run zolt resolve to refresh zolt.lock.\u001B[0m"));
        assertEquals("", added.stderr());
        String addedConfig = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(addedConfig.contains("[versions]\n\"guava\" = \"33.4.8-jre\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));

        CommandResult updated = execute(
                "version",
                "set",
                "--directory", projectDir.toString(),
                "--no-resolve",
                "guava",
                "33.4.9-jre");

        assertEquals(0, updated.exitCode());
        assertTrue(updated.stdout().contains("Updated version alias guava from 33.4.8-jre to 33.4.9-jre in [versions]"));
        assertEquals("", updated.stderr());
        String updatedConfig = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(updatedConfig.contains("[versions]\n\"guava\" = \"33.4.9-jre\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));

        CommandResult quiet = execute(
                "--quiet",
                "version",
                "set",
                "--directory", projectDir.toString(),
                "--no-resolve",
                "junit",
                "5.12.1");
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
        assertTrue(Files.readString(projectDir.resolve("zolt.toml")).contains("\"junit\" = \"5.12.1\""));
    }

    @Test
    void versionSetRejectsInvalidAliasNames() throws IOException {
        Path projectDir = tempDir.resolve("version-alias-invalid");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "version-alias-invalid"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);

        CommandResult result = execute(
                "version",
                "set",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "spring boot",
                "4.0.6");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(
                "Invalid version alias `spring boot`. Alias names may contain only letters, digits, dot, underscore, and hyphen."));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertFalse(config.contains("[versions]"));
    }

    @Test
    void versionSetRejectsInvalidAliasValues() throws IOException {
        Path projectDir = tempDir.resolve("version-alias-invalid-value");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "version-alias-invalid-value"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);

        CommandResult result = execute(
                "version",
                "set",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "guava",
                "1.0-SNAPSHOT");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(
                "Invalid version alias `1.0-SNAPSHOT` for [versions].guava. SNAPSHOT versions are not supported in this context"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertFalse(config.contains("[versions]"));
    }

    @Test
    void versionSetRefreshesLockfileByDefault() throws IOException {
        Path projectDir = tempDir.resolve("version-alias-resolve");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "version-alias-resolve"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);

        CommandResult result = execute(
                "version",
                "set",
                "--cwd", projectDir.toString(),
                "guava",
                "33.4.8-jre");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Added version alias guava = 33.4.8-jre to [versions]"));
        assertTrue(result.stdout().contains("Resolved 0 packages"));
        assertTrue(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void settingAnAlreadyCurrentAliasIsASuccessfulNoOp() throws IOException {
        Path projectDir = tempDir.resolve("version-alias-current");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "version-alias-current"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [versions]
                guava = "33.4.8-jre"
                """);
        String original = Files.readString(projectDir.resolve("zolt.toml"));

        CommandResult result = execute(
                "version",
                "set",
                "--cwd", projectDir.toString(),
                "guava",
                "33.4.8-jre");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("already equals 33.4.8-jre"));
        assertEquals(original, Files.readString(projectDir.resolve("zolt.toml")));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }
}

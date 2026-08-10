package sh.zolt.cli.dependency;

import static sh.zolt.cli.dependency.AddCommandNoResolveTestSupport.occurrences;
import static sh.zolt.cli.dependency.AddCommandNoResolveTestSupport.writeProjectConfig;
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

final class AddCommandNoResolveTest {
    @TempDir
    private Path tempDir;

    @Test
    void addAddsCompileDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--directory", projectDir.toString(),
                "--no-resolve",
                "com.google.guava:guava:33.4.0-jre");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Added dependency com.google.guava:guava:33.4.0-jre to [dependencies]"));
        assertTrue(result.stdout().contains("Skipped resolve"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("\"com.google.guava:guava\" = \"33.4.0-jre\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void addPreservesCommentedManifestWithoutARewriteWarning() throws IOException {
        Path projectDir = tempDir.resolve("commented-demo");
        writeProjectConfig(projectDir);
        Path configPath = projectDir.resolve("zolt.toml");
        Files.writeString(configPath, "# kept for humans\n" + Files.readString(configPath));

        CommandResult result = execute(
                "add",
                "--directory", projectDir.toString(),
                "--no-resolve",
                "com.google.guava:guava:33.4.0-jre");

        assertEquals(0, result.exitCode());
        assertFalse(result.stdout().contains("Warning:"));
        assertTrue(Files.readString(configPath).contains("# kept for humans"));
    }

    @Test
    void addHumanOutputSupportsForcedColorAndQuietMode() throws IOException {
        Path colorProjectDir = tempDir.resolve("color-demo");
        writeProjectConfig(colorProjectDir);
        Path quietProjectDir = tempDir.resolve("quiet-demo");
        writeProjectConfig(quietProjectDir);
        Path quietConfigPath = quietProjectDir.resolve("zolt.toml");
        Files.writeString(quietConfigPath, "# quiet rewrite note\n" + Files.readString(quietConfigPath));

        CommandResult color = execute(
                "--color=always",
                "add",
                "--directory", colorProjectDir.toString(),
                "--no-resolve",
                "com.google.guava:guava:33.4.0-jre");
        CommandResult quiet = execute(
                "--quiet",
                "add",
                "--directory", quietProjectDir.toString(),
                "--no-resolve",
                "com.google.guava:guava:33.4.0-jre");

        assertEquals(0, color.exitCode());
        assertTrue(color.stdout().contains("\u001B[32m✔\u001B[0m Added dependency "
                + "com.google.guava:guava:33.4.0-jre to [dependencies]"));
        assertFalse(color.stdout().contains(
                "\u001B[32mAdded dependency com.google.guava:guava:33.4.0-jre to [dependencies]\u001B[0m"));
        assertTrue(color.stdout().contains(
                "\u001B[32mSkipped\u001B[0m resolve; run zolt resolve to refresh zolt.lock."));
        assertFalse(color.stdout().contains(
                "\u001B[32mSkipped resolve; run zolt resolve to refresh zolt.lock.\u001B[0m"));
        assertEquals(0, quiet.exitCode());
        assertEquals("", quiet.stdout());
        String quietConfig = Files.readString(quietConfigPath);
        assertTrue(quietConfig.contains("\"com.google.guava:guava\" = \"33.4.0-jre\""));
        assertTrue(quietConfig.contains("# quiet rewrite note"));
        assertFalse(Files.exists(quietProjectDir.resolve("zolt.lock")));
    }

    @Test
    void addAddsTestDependencyWithoutDuplicatingExistingEntry() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult first = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "test",
                "org.junit.jupiter:junit-jupiter:5.11.4");
        CommandResult second = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "test",
                "org.junit.jupiter:junit-jupiter:5.11.4");

        assertEquals(0, first.exitCode());
        assertEquals(0, second.exitCode());
        assertTrue(first.stdout().contains("Added dependency org.junit.jupiter:junit-jupiter:5.11.4 to [test.dependencies]"));
        assertTrue(second.stdout().contains("already exists in [test.dependencies]"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertEquals(1, occurrences(config, "\"org.junit.jupiter:junit-jupiter\" = \"5.11.4\""));
    }

    @Test
    void addAddsManagedDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "--managed",
                "com.example:legacy-api");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "Added dependency com.example:legacy-api with a platform-managed version to [dependencies]"));
        assertTrue(result.stdout().contains("Skipped resolve"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("\"com.example:legacy-api\" = {}"));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

}

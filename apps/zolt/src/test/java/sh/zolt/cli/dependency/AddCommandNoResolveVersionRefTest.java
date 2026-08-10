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

final class AddCommandNoResolveVersionRefTest {
    @TempDir
    private Path tempDir;

    @Test
    void addAddsVersionRefDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [repositories]
                "central" = "https://repo.maven.apache.org/maven2"

                [versions]
                guava = "33.4.8-jre"
                """);

        CommandResult result = execute(
                "--color=always",
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "--version-ref",
                "guava",
                "com.google.guava:guava");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "\u001B[32m✔\u001B[0m Added dependency com.google.guava:guava with versionRef `guava` = 33.4.8-jre to [dependencies]"));
        assertFalse(result.stdout().contains(
                "\u001B[32mAdded dependency com.google.guava:guava with versionRef `guava` = 33.4.8-jre to [dependencies]\u001B[0m"));
        assertTrue(result.stdout().contains(
                "\u001B[32mSkipped\u001B[0m resolve; run zolt resolve to refresh zolt.lock."));
        assertFalse(result.stdout().contains(
                "\u001B[32mSkipped resolve; run zolt resolve to refresh zolt.lock.\u001B[0m"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[versions]\nguava = \"33.4.8-jre\""));
        assertTrue(config.contains("\"com.google.guava:guava\" = { versionRef = \"guava\" }"));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
        assertEquals(1, occurrences(config, "guava = \"33.4.8-jre\""));
    }
}

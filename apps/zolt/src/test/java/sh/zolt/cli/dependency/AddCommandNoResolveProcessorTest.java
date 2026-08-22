package sh.zolt.cli.dependency;

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

final class AddCommandNoResolveProcessorTest {
    @TempDir
    private Path tempDir;

    @Test
    void addAddsProcessorDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "--color=always",
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "org.mapstruct:mapstruct-processor:1.6.3",
                "--scope", "processor");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "\u001B[32m✔\u001B[0m Added dependency org.mapstruct:mapstruct-processor:1.6.3 to [dependencies.processor]"));
        assertFalse(result.stdout().contains(
                "\u001B[32mAdded dependency org.mapstruct:mapstruct-processor:1.6.3 to [dependencies.processor]\u001B[0m"));
        assertTrue(result.stdout().contains(
                "\u001B[32mSkipped\u001B[0m resolve; run zolt resolve to refresh zolt.lock."));
        assertFalse(result.stdout().contains(
                "\u001B[32mSkipped resolve; run zolt resolve to refresh zolt.lock.\u001B[0m"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[dependencies.processor]"));
        assertTrue(config.contains("\"org.mapstruct:mapstruct-processor\" = \"1.6.3\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void addAddsManagedTestProcessorDependencyWithoutResolveWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "add",
                "--cwd", projectDir.toString(),
                "--no-resolve",
                "--managed",
                "io.micronaut:micronaut-inject-java",
                "--scope", "test-processor");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "Added dependency io.micronaut:micronaut-inject-java with a platform-managed version to [dependencies.test-processor]"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[dependencies.test-processor]"));
        assertTrue(config.contains("\"io.micronaut:micronaut-inject-java\" = { managed = true }"));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }
}

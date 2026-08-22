package sh.zolt.cli.dependency;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RemoveCommandSectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void removeDeletesDependencyFromRequestedSectionOnly() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "tool", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>tool</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("demo");
            // One ordinary variant lives in one lane (design §9.7), so the test lane holds its own.
            RemoveCommandSectionTestSupport.writeSectionedDependencyProject(
                    projectDir,
                    repository.baseUri().toString(),
                    Map.of("com.example:tool", "1.0.0"),
                    Map.of("com.example:test-tool", "1.0.0"));

            CommandResult result = execute(
                    "remove",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString(),
                    "com.example:test-tool",
                    "--scope", "test");

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(
                    result.stdout().contains("Removed dependency com.example:test-tool from [dependencies.test]"));
            String config = Files.readString(projectDir.resolve("zolt.toml"));
            assertEquals(0, occurrences(config, "com.example:test-tool"));
            assertEquals(1, occurrences(config, "\"com.example:tool\" = \"1.0.0\""));
            assertTrue(config.indexOf("[dependencies]") < config.indexOf("\"com.example:tool\" = \"1.0.0\""));
        }
    }

    @Test
    void removeDeletesProcessorDependencyFromRequestedSection() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "processor", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>processor</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("demo");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("zolt.toml"), """
                    [project]
                    name = "demo"
                    version = "0.1.0"
                    group = "com.example"
                    java = 21

                    [repositories.local]
                    url = "%s"

                    [dependencies]
                    "com.example:processor" = "1.0.0"

                    [dependencies.processor]
                    "com.example:processor" = "1.0.0"
                    """.formatted(repository.baseUri()));

            CommandResult result = execute(
                    "remove",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString(),
                    "com.example:processor",
                    "--scope", "processor");

            assertEquals(0, result.exitCode(), result.stderr());
            assertTrue(result.stdout().contains("Removed dependency com.example:processor from [dependencies.processor]"));
            assertTrue(result.stdout().contains("Resolved 1 packages"));
            String config = Files.readString(projectDir.resolve("zolt.toml"));
            assertTrue(config.contains("[dependencies]\n\"com.example:processor\" = \"1.0.0\""));
            assertFalse(config.contains("[dependencies.processor]\n\"com.example:processor\" = \"1.0.0\""));
        }
    }

    @Test
    void removeDeletesApiDependency() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        RemoveCommandSectionTestSupport.writeApiDependencyProject(projectDir);

        CommandResult result = execute(
                "remove",
                "--cwd", projectDir.toString(),
                "com.example:contract",
                "--scope", "api");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("Removed dependency com.example:contract from [dependencies.api]"));
        assertTrue(result.stdout().contains("Resolved 0 packages"));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertFalse(config.contains("\"com.example:contract\" = \"1.0.0\""));
    }

    @Test
    void removeWithoutSectionDoesNotDeleteApiDependency() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        RemoveCommandSectionTestSupport.writeApiDependencyProject(projectDir);

        CommandResult result = execute(
                "remove",
                "--cwd", projectDir.toString(),
                "com.example:contract");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "Dependency com.example:contract is not present in [dependencies]; nothing to remove."));
        String config = Files.readString(projectDir.resolve("zolt.toml"));
        assertTrue(config.contains("[dependencies.api]\n\"com.example:contract\" = \"1.0.0\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int index = value.indexOf(needle);
        while (index >= 0) {
            count++;
            index = value.indexOf(needle, index + needle.length());
        }
        return count;
    }
}

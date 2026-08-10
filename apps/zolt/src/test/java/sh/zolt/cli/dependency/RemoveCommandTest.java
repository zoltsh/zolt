package sh.zolt.cli.dependency;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
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

final class RemoveCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void removeDeletesDependencyAndPrunesUnusedTransitivesFromLockfile() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                      <dependencies>
                        <dependency>
                          <groupId>com.example</groupId>
                          <artifactId>lib</artifactId>
                          <version>1.0.0</version>
                        </dependency>
                      </dependencies>
                    </project>
                    """);
            repository.addArtifact("com.example", "lib", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("demo");
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of("com.example:app", "1.0.0"), Map.of());
            Path configPath = projectDir.resolve("zolt.toml");
            Files.writeString(configPath, "# dependency note\n" + Files.readString(configPath));

            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());
            String initialLockfile = Files.readString(projectDir.resolve("zolt.lock"));

            CommandResult remove = execute(
                    "remove",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString(),
                    "com.example:app");

            assertEquals(0, resolve.exitCode());
            assertTrue(initialLockfile.contains("com.example:app"));
            assertTrue(initialLockfile.contains("com.example:lib"));
            assertEquals(0, remove.exitCode());
            assertFalse(remove.stdout().contains("Warning:"));
            assertTrue(remove.stdout().contains("Removed dependency com.example:app from [dependencies]"));
            assertTrue(remove.stdout().contains("Resolved 0 packages"));
            String config = Files.readString(configPath);
            String updatedLockfile = Files.readString(projectDir.resolve("zolt.lock"));
            assertTrue(config.contains("# dependency note"));
            assertFalse(config.contains("\"com.example:app\" = \"1.0.0\""));
            assertFalse(updatedLockfile.contains("com.example:app"));
            assertFalse(updatedLockfile.contains("com.example:lib"));
        }
    }

    @Test
    void removeUsesModernHumanOutputControls() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path colorProject = tempDir.resolve("color-remove");
            Path quietProject = tempDir.resolve("quiet-remove");
            writeProjectConfig(
                    colorProject,
                    repository.baseUri().toString(),
                    Map.of("com.example:app", "1.0.0"),
                    Map.of());
            writeProjectConfig(quietProject);

            CommandResult color = execute(
                    "--color=always",
                    "remove",
                    "--directory", colorProject.toString(),
                    "--cache-root", tempDir.resolve("color-cache").toString(),
                    "com.example:app");
            CommandResult quiet = execute(
                    "--quiet",
                    "remove",
                    "--directory", quietProject.toString(),
                    "com.example:missing");

            assertEquals(0, color.exitCode(), color.stderr());
            assertTrue(color.stdout().contains("\u001B[32m✔\u001B[0m Removed dependency com.example:app from [dependencies]"));
            assertFalse(
                    color.stdout().contains("\u001B[32mRemoved dependency com.example:app from [dependencies]\u001B[0m"));
            assertTrue(color.stdout().contains("\u001B[32m✔\u001B[0m Resolved 0 packages"));
            assertFalse(color.stdout().contains("\u001B[32mResolved 0 packages\u001B[0m"));
            assertEquals(0, quiet.exitCode(), quiet.stderr());
            assertEquals("", quiet.stdout());
            assertFalse(Files.exists(quietProject.resolve("zolt.lock")));
        }
    }

    @Test
    void removeMissingDependencyPrintsFriendlyNoOpMessage() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir);

        CommandResult result = execute(
                "remove",
                "--directory", projectDir.toString(),
                "com.example:missing");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "Dependency com.example:missing is not present in [dependencies]; nothing to remove."));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    private static void writeProjectConfig(Path projectDir) throws IOException {
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2", Map.of(), Map.of());
    }

    private static void writeProjectConfig(
            Path projectDir,
            String repositoryUrl,
            Map<String, String> dependencies,
            Map<String, String> testDependencies) throws IOException {
        Files.createDirectories(projectDir);
        StringBuilder config = new StringBuilder(memberConfig("demo") + """
                main = "com.example.Main"

                [repositories]
                test = "%s"

                [dependencies]
                """.formatted(repositoryUrl));
        appendDependencies(config, dependencies);
        config.append("\n[test.dependencies]\n");
        appendDependencies(config, testDependencies);
        config.append("""

                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "target/classes"
                testOutput = "target/test-classes"
                """);
        Files.writeString(projectDir.resolve("zolt.toml"), config.toString());
    }

    private static void appendDependencies(StringBuilder config, Map<String, String> dependencies) {
        dependencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> config.append('"')
                        .append(entry.getKey())
                        .append("\" = \"")
                        .append(entry.getValue())
                        .append("\"\n"));
    }
}

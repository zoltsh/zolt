package sh.zolt.cli.resolve;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.resolve.ResolveCommandTestSupport.writeProjectConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ResolveCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolveReadsConfigWritesLockfileAndPrintsSummary() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("demo");
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of("com.example:app", "1.0.0"));

            CommandResult result = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("Resolved 1 packages"));
            assertTrue(result.stdout().contains("2 downloaded"));
            assertTrue(result.stdout().contains("0 conflicts"));
            assertTrue(result.stdout().contains("wrote " + projectDir.resolve("zolt.lock")));
            assertEquals("", result.stderr());
            assertTrue(Files.exists(projectDir.resolve("zolt.lock")));
        }
    }

    @Test
    void resolveAcceptsVisibleProjectDirectoryOption() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("directory-demo");
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of("com.example:app", "1.0.0"));

            CommandResult result = execute(
                    "resolve",
                    "--directory", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("wrote " + projectDir.resolve("zolt.lock")));
            assertTrue(Files.exists(projectDir.resolve("zolt.lock")));
        }
    }

    @Test
    void resolveLockedVerifiesExistingLockfile() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("demo");
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of("com.example:app", "1.0.0"));
            CommandResult unlocked = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            CommandResult locked = execute(
                    "resolve",
                    "--locked",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            assertEquals(0, unlocked.exitCode());
            assertEquals(0, locked.exitCode());
            assertTrue(locked.stdout().contains("verified " + projectDir.resolve("zolt.lock")));
        }
    }

    @Test
    void resolveRejectsUnknownRepositoryOverlay() throws IOException {
        Path projectDir = tempDir.resolve("resolve-unknown-overlay");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("resolve-unknown-overlay"));

        CommandResult result = execute(
                "resolve",
                "--cwd", projectDir.toString(),
                "--repository-overlay", "gradle-cache");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unsupported repository overlay `gradle-cache`"));
        assertTrue(result.stderr().contains("Supported overlays: maven-local"));
    }

    @Test
    void resolveRejectsConflictingLocalOverlayOptions() throws IOException {
        Path projectDir = tempDir.resolve("resolve-conflicting-overlays");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("resolve-conflicting-overlays"));

        CommandResult result = execute(
                "resolve",
                "--cwd", projectDir.toString(),
                "--repository-overlay", "local-maven",
                "--no-local-overlays");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Cannot combine local repository overlays with local-overlay rejection"));
        assertTrue(result.stderr().contains("Remove --repository-overlay or remove --no-local-overlays"));
    }

    @Test
    void resolveUnsupportedVersionRetryHintNamesResolve() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addRangeRoot(repository);
            Path projectDir = tempDir.resolve("resolve-unsupported-version");
            writeProjectConfig(
                    projectDir,
                    repository.baseUri().toString(),
                    Map.of("com.example:root", "1.0.0"));

            CommandResult result = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", tempDir.resolve("cache-unsupported-version").toString());

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("Unsupported transitive dependency version `[1.0,2.0)`"));
            assertTrue(result.stderr().contains("run `zolt resolve` again"));
        }
    }

    @Test
    void resolveWorkspaceUnsupportedVersionRetryHintKeepsWorkspaceFlag() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addRangeRoot(repository);
            Path workspaceDir = tempDir.resolve("resolve-workspace-unsupported-version");
            Files.createDirectories(workspaceDir);
            Files.writeString(workspaceDir.resolve("zolt.toml"), """
                    [workspace]
                    name = "demo"

                    [workspace.members]
                    include = ["app"]

                    [repositories]
                    central = false

                    [repositories.test]
                    url = "%s"
                    """.formatted(repository.baseUri()));
            writeMemberConfig(
                    workspaceDir.resolve("app"),
                    Map.of("com.example:root", "1.0.0"));

            CommandResult result = execute(
                    "resolve",
                    "--workspace",
                    "--cwd", workspaceDir.toString(),
                    "--cache-root", tempDir.resolve("cache-workspace-unsupported-version").toString());

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("Unsupported transitive dependency version `[1.0,2.0)`"));
            assertTrue(result.stderr().contains("run `zolt resolve --workspace` again"));
        }
    }

    /** A workspace member never owns repositories; the root universe is authoritative (design 8.7). */
    private static void writeMemberConfig(
            Path memberDir,
            Map<String, String> dependencies) throws IOException {
        Files.createDirectories(memberDir);
        StringBuilder config = new StringBuilder(memberConfig("demo") + """
                main = "com.example.Main"

                [dependencies]
                """);
        dependencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> config.append('"')
                        .append(entry.getKey())
                        .append("\" = \"")
                        .append(entry.getValue())
                        .append("\"\n"));
        Files.writeString(memberDir.resolve("zolt.toml"), config.toString());
    }

    @Test
    void resolveLockedReportsMissingLockfileClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, Map.of());

        CommandResult result = execute(
                "resolve",
                "--locked",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Locked resolve requires zolt.lock"));
        assertTrue(result.stderr().contains("File: " + projectDir.resolve("zolt.lock")));
        assertTrue(result.stderr().contains("Next: Run `zolt resolve` to create it"));
    }

    @Test
    void resolveOfflineReportsMissingCachedArtifactClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2", Map.of("com.example:missing", "1.0.0"));

        CommandResult result = execute(
                "resolve",
                "--offline",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Offline mode requires cached POM"));
        assertTrue(result.stderr().contains("Coordinate: com.example:missing:1.0.0"));
        assertTrue(result.stderr().contains("Next: Run the command without --offline"));
    }

    private static void addRangeRoot(CliTestRepository repository) {
        repository.addArtifact("com.example", "root", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>bad</artifactId>
                      <version>[1.0,2.0)</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

}

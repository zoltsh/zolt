package sh.zolt.cli.ide;

import sh.zolt.cli.CliTestRepository;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.ide.IdeModelCommandTestSupport.writeProjectConfig;
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

final class IdeModelLockDiagnosticCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkLockReportsFreshLockfileWithoutDiagnostics() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("demo");
            Path cacheRoot = tempDir.resolve("cache");
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of("com.example:app", "1.0.0"), Map.of());
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());
            String lockfile = Files.readString(projectDir.resolve("zolt.lock"));

            CommandResult result = execute(
                    "ide",
                    "model",
                    "--check-lock",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString(),
                    "--format", "json");

            assertEquals(0, resolve.exitCode());
            assertEquals(0, result.exitCode());
            assertEquals("", result.stderr());
            assertTrue(result.stdout().contains("\"diagnostics\": []"));
            assertEquals(lockfile, Files.readString(projectDir.resolve("zolt.lock")));
        }
    }

    @Test
    void reportsStaleLockfileWithoutRewritingItByDefault() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "app", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>app</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            repository.addArtifact("com.example", "extra", "1.0.0", """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>extra</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
            Path projectDir = tempDir.resolve("demo");
            Path cacheRoot = tempDir.resolve("cache");
            writeProjectConfig(projectDir, repository.baseUri().toString(), Map.of("com.example:app", "1.0.0"), Map.of());
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString());
            String lockfile = Files.readString(projectDir.resolve("zolt.lock"));
            writeProjectConfig(
                    projectDir,
                    repository.baseUri().toString(),
                    Map.of(
                            "com.example:app", "1.0.0",
                            "com.example:extra", "1.0.0"),
                    Map.of());

            CommandResult result = execute(
                    "ide",
                    "model",
                    "--cwd", projectDir.toString(),
                    "--cache-root", cacheRoot.toString(),
                    "--format", "json");

            assertEquals(0, resolve.exitCode());
            assertEquals(0, result.exitCode());
            assertEquals("", result.stderr());
            assertTrue(result.stdout().contains("\"code\": \"LOCKFILE_STALE\""));
            assertTrue(result.stdout().contains("zolt.lock is out of date"));
            assertTrue(result.stdout().contains("\"nextStep\": \"Run zolt resolve.\""));
            assertEquals(lockfile, Files.readString(projectDir.resolve("zolt.lock")));
        }
    }

    @Test
    void reportsMissingLockfileAsJsonDiagnostic() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "ide",
                "model",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"code\": \"LOCKFILE_MISSING\""));
        assertTrue(result.stdout().contains("\"nextStep\": \"Run zolt resolve.\""));
        assertTrue(result.stdout().contains("\"compile\": []"));
        assertTrue(result.stdout().contains("\"runtime\": []"));
        assertTrue(result.stdout().contains("\"test\": []"));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void reportsUnreadableLockfileAsJsonDiagnostic() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("zolt.lock"), "this is not toml");

        CommandResult result = execute(
                "ide",
                "model",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"code\": \"LOCKFILE_UNREADABLE\""));
        assertTrue(result.stdout().contains("Could not parse zolt.lock"));
        assertTrue(result.stdout().contains("\"nextStep\": \"Run zolt resolve.\""));
        assertTrue(result.stdout().contains("\"compile\": []"));
        assertTrue(result.stdout().contains("\"runtime\": []"));
        assertTrue(result.stdout().contains("\"test\": []"));
    }

    @Test
    void checkLockReportsMissingLockfileAsJsonDiagnostic() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "ide",
                "model",
                "--check-lock",
                "--cwd", projectDir.toString(),
                "--cache-root", tempDir.resolve("cache").toString(),
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"code\": \"LOCKFILE_MISSING\""));
        assertTrue(result.stdout().contains("\"nextStep\": \"Run zolt resolve.\""));
        assertFalse(Files.exists(projectDir.resolve("zolt.lock")));
    }

    @Test
    void checkLockOfflineReportsUnavailableCacheAsJsonDiagnostic() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeProjectConfig(
                projectDir,
                "https://repo.maven.apache.org/maven2",
                Map.of("com.example:missing", "1.0.0"),
                Map.of());
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "ide",
                "model",
                "--check-lock",
                "--offline",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString(),
                "--format", "json");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("\"code\": \"LOCKFILE_CHECK_UNAVAILABLE\""));
        assertTrue(result.stdout().contains("Offline mode requires cached POM"));
        assertTrue(result.stdout().contains("retry zolt ide model --offline"));
        assertEquals("version = 7\n", Files.readString(projectDir.resolve("zolt.lock")));
    }
}

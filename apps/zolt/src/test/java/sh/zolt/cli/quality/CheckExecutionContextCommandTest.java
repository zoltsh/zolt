package sh.zolt.cli.quality;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckExecutionContextCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkContextCiRunsBuiltInReproducibilityChecks() throws IOException {
        Path projectDir = tempDir.resolve("check-context-ci");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-ci"));
        CommandResult resolveResult = execute("resolve", "--cwd", projectDir.toString());

        CommandResult result = execute("check", "--context", "ci", "--cwd", projectDir.toString());

        assertEquals(0, resolveResult.exitCode());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context ci CI context policy is active"));
        assertTrue(result.stdout().contains("Policy source: built-in ci context"));
        assertTrue(result.stdout().contains("ok lockfile zolt.lock zolt.lock matches zolt.toml."));
        assertTrue(result.stdout().contains("ok project-model check-context-ci Project model is valid"));
        assertTrue(result.stdout().contains("ok dependency-policy check-context-ci Dependency policy baseline is explainable"));
        assertTrue(result.stdout().contains("ok generated-sources check-context-ci No declared generated-source steps require validation."));
        assertTrue(result.stdout().contains("ok package-contents check-context-ci Package mode `jar` has 0 dependency dispositions."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextLocalReportsDeveloperPolicyWithoutLockfile() throws IOException {
        Path projectDir = tempDir.resolve("check-context-local");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-local"));

        CommandResult result = execute("check", "--context", "local", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context local Local context policy is active"));
        assertTrue(result.stdout().contains("Policy source: built-in local context"));
        assertTrue(result.stdout().contains("Local overlays are allowed"));
        assertFalse(result.stdout().contains("lockfile zolt.lock"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkContextLocalPrependsPolicyToExplicitChecks() throws IOException {
        Path projectDir = tempDir.resolve("check-context-local-explicit-check");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-local-explicit-check"));

        CommandResult result = execute(
                "check",
                "--context", "local",
                "--check", "project-model",
                "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok execution-context local Local context policy is active"));
        assertTrue(result.stdout().contains("Policy source: built-in local context"));
        assertTrue(result.stdout().contains("ok project-model check-context-local-explicit-check Project model is valid"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkRejectsMalformedContext() throws IOException {
        Path projectDir = tempDir.resolve("check-context-malformed");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-context-malformed"));

        CommandResult result = execute("check", "--context", "profile-dev", "--cwd", projectDir.toString());

        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("Invalid value for option '--context': expected one of [LOCAL, CI]"));
    }
}

package sh.zolt.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sh.zolt.build.ProjectMutationLock;

final class CommandMutationExecutionTest {
    @TempDir
    private Path project;

    @ParameterizedTest
    @ValueSource(strings = {"build", "clean", "package", "native", "resolve", "test", "integration-test", "coverage"})
    void finiteCommandsWaitBeforeReadingConfiguration(String command) throws Exception {
        Files.writeString(project.resolve("zolt.toml"), CliTestSupport.memberConfig("lease"));
        var parsed = CliTestSupport.newCommandLine().parseArgs(command, "--cwd", project.toString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Integer> result;
            try (ProjectMutationLock ignored = ProjectMutationLock.acquire(project)) {
                result = executor.submit(() -> CommandMutationExecution.execute(parsed, () -> 42));
                assertThrows(TimeoutException.class, () -> result.get(250, TimeUnit.MILLISECONDS));
            }
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"build", "clean", "package", "native", "resolve", "test", "integration-test", "coverage"})
    void invalidManifestPreflightUsesCommandDiagnostics(String command) throws Exception {
        Files.writeString(project.resolve("zolt.toml"),
                CliTestSupport.memberConfig("invalid") + "packaging = \"jar\"\n");

        var result = CliTestSupport.execute(command, "--cwd", project.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().startsWith("error: "), result.stderr());
        assertTrue(result.stderr().contains("Field: project.packaging"), result.stderr());
        assertTrue(result.stderr().contains("Next: Remove it or check the spelling."), result.stderr());
        assertFalse(result.stderr().contains("\tat "), result.stderr());
    }

    @Test
    void invalidManifestPreflightPreservesTheSelectedCommandsJsonEnvelope() throws Exception {
        Files.writeString(project.resolve("zolt.toml"),
                CliTestSupport.memberConfig("invalid") + "\n[bogusSection]\n");

        var result = CliTestSupport.execute("update", "--cwd", project.toString(), "--format", "json");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("\"command\": \"update\""), result.stdout());
        assertTrue(result.stdout().contains("\"status\": \"failed\""), result.stdout());
        assertTrue(result.stdout().contains("Remove it or check the spelling."), result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void lockAcquisitionFailureUsesCommandDiagnostics() throws Exception {
        Files.writeString(project.resolve("zolt.toml"), CliTestSupport.memberConfig("lease"));
        Files.writeString(project.resolve(".zolt"), "blocks the lock directory");

        var result = CliTestSupport.execute("build", "--cwd", project.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().startsWith("error: Could not acquire project mutation lock"), result.stderr());
        assertFalse(result.stderr().contains("\tat "), result.stderr());
    }

    @Test
    void dependencyEditWaitsForTheStandaloneBuildLease() throws Exception {
        Files.writeString(project.resolve("zolt.toml"), CliTestSupport.memberConfig("lease"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<CliTestSupport.CommandResult> result;
            try (ProjectMutationLock ignored = ProjectMutationLock.acquire(project)) {
                result = executor.submit(() -> CliTestSupport.execute(
                        "add", "--cwd", project.toString(), "--no-resolve", "example:library:1.0.0"));
                assertThrows(TimeoutException.class, () -> result.get(1, TimeUnit.SECONDS));
                assertFalse(Files.readString(project.resolve("zolt.toml")).contains("library"));
            }
            var edited = result.get(10, TimeUnit.SECONDS);
            assertEquals(0, edited.exitCode(), edited.stderr());
            assertTrue(Files.readString(project.resolve("zolt.toml")).contains("example:library"));
        }
    }

    @Test
    void helpAndRunningApplicationsDoNotHoldTheFiniteCommandLease() throws Exception {
        for (String[] arguments : new String[][] {
                {"build", "--help", "--cwd", project.toString()},
                {"run", "--cwd", project.toString()},
                {"run-package", "--cwd", project.toString()}}) {
            var parsed = CliTestSupport.newCommandLine().parseArgs(arguments);
            try (var executor = Executors.newSingleThreadExecutor();
                    ProjectMutationLock ignored = ProjectMutationLock.acquire(project)) {
                assertEquals(42, executor.submit(() -> CommandMutationExecution.execute(parsed, () -> 42))
                        .get(5, TimeUnit.SECONDS));
            }
        }
    }
}

package sh.zolt.cli.dependency;

import sh.zolt.cli.CliTestSupport;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class ConflictsCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void conflictsPrintsConflictSummaryFromLockfile() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[conflict]]
                id = "org.slf4j:slf4j-api"
                selected = "2.0.16"
                requested = ["1.7.36", "2.0.16"]
                reason = "direct dependency wins"
                """);

        CommandResult result = execute("conflicts", "--directory", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("""
                Dependency conflicts:
                - org.slf4j:slf4j-api
                  selected: 2.0.16
                  requested: 1.7.36, 2.0.16
                  reason: direct dependency wins
                """, result.stdout());
    }

    @Test
    void conflictsPrintsExecToolAttributionFromLockfile() throws IOException {
        Path projectDir = tempDir.resolve("tooled");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[conflict]]
                id = "com.example:shared"
                tool = "codegen"
                selected = "2.0.0"
                requested = ["1.0.0", "2.0.0"]
                reason = "newest version wins"
                """);

        CommandResult result = execute("conflicts", "--directory", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("""
                Dependency conflicts:
                - com.example:shared
                  tool: codegen
                  selected: 2.0.0
                  requested: 1.0.0, 2.0.0
                  reason: newest version wins
                """, result.stdout());
    }

    @Test
    void conflictsExitsSuccessfullyWhenNoConflictsExist() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute("conflicts", "--cwd", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("No dependency conflicts found.\n", result.stdout());
    }

    @Test
    void conflictsOutputIgnoresForcedColorAndProgress() throws IOException {
        Path projectDir = tempDir.resolve("forced-controls");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[conflict]]
                id = "org.slf4j:slf4j-api"
                selected = "2.0.16"
                requested = ["1.7.36", "2.0.16"]
                reason = "direct dependency wins"
                """);

        CommandResult result = execute(
                "--color=always",
                "--progress=always",
                "conflicts",
                "--directory", projectDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertEquals("""
                Dependency conflicts:
                - org.slf4j:slf4j-api
                  selected: 2.0.16
                  requested: 1.7.36, 2.0.16
                  reason: direct dependency wins
                """, result.stdout());
        assertFalse(result.stdout().contains("\u001B["), "conflicts output should not contain ANSI");
        assertFalse(result.stdout().contains("Resolving "), "conflicts output should not contain progress text");
        assertFalse(result.stdout().contains("Still running:"), "conflicts output should not contain progress text");
    }

    @Test
    void formattedCommandsFlushOutputBeforeExit() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        FlushTrackingPrintWriter stdout = new FlushTrackingPrintWriter();
        CommandLine commandLine = CliTestSupport.newCommandLine();
        commandLine.setOut(stdout);
        commandLine.setErr(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("conflicts", "--cwd", projectDir.toString());

        assertEquals(0, exitCode);
        assertEquals("No dependency conflicts found.\n", stdout.content());
        assertTrue(stdout.flushed());
    }

    private static final class FlushTrackingPrintWriter extends PrintWriter {
        private final StringWriter writer;
        private boolean flushed;

        private FlushTrackingPrintWriter() {
            this(new StringWriter());
        }

        private FlushTrackingPrintWriter(StringWriter writer) {
            super(writer);
            this.writer = writer;
        }

        @Override
        public void flush() {
            flushed = true;
            super.flush();
        }

        private boolean flushed() {
            return flushed;
        }

        private String content() {
            return writer.toString();
        }
    }
}

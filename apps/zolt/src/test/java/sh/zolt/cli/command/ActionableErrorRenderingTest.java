package sh.zolt.cli.command;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden enforcement test: every representative migrated failure must emit both an {@code error:}
 * summary and a non-empty {@code Next:} remediation line on stderr. Mirrors {@code CliSurfaceTest}
 * by driving scenarios end-to-end through {@link CliTestSupport#execute}.
 */
final class ActionableErrorRenderingTest {
    @TempDir
    private Path tempDir;

    @Test
    void missingZoltTomlEmitsErrorAndNextLines() {
        CommandResult result = execute("resolve", "--cwd", tempDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("error: Could not read zolt.toml"), result.stderr());
        assertTrue(result.stderr().contains("File: " + tempDir.resolve("zolt.toml")), result.stderr());
        assertNextLineIsNonEmpty(result.stderr());
        assertTrue(result.stderr().contains("Next: Check that the file exists"), result.stderr());
    }

    @Test
    void unknownTopLevelSectionEmitsErrorAndNextLines() throws IOException {
        writeConfig("unknown", "\n[bogusSection]\n");

        CommandResult result = execute("resolve", "--cwd", tempDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown manifest section `[bogusSection]`"), result.stderr());
        assertTrue(result.stderr().contains("Section: [bogusSection]"), result.stderr());
        assertNextLineIsNonEmpty(result.stderr());
        assertTrue(result.stderr().contains("Next: Remove it or check the spelling."), result.stderr());
    }

    @Test
    void unsupportedSectionEmitsErrorAndNextLines() throws IOException {
        writeConfig("unsupported", "\n[kotlin]\n");

        CommandResult result = execute("resolve", "--cwd", tempDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Unknown manifest section `[kotlin]`"), result.stderr());
        assertNextLineIsNonEmpty(result.stderr());
        assertTrue(result.stderr().contains("Next: Remove it or check the spelling."), result.stderr());
    }

    private void writeConfig(String name, String extraSection) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig(name) + extraSection);
    }

    private static void assertNextLineIsNonEmpty(String stderr) {
        boolean hasNonEmptyNext = stderr.lines()
                .filter(line -> line.startsWith("Next: "))
                .map(line -> line.substring("Next: ".length()).trim())
                .anyMatch(remediation -> !remediation.isEmpty());
        assertTrue(hasNonEmptyNext, () -> "Expected a non-empty 'Next:' remediation line in:\n" + stderr);
    }
}

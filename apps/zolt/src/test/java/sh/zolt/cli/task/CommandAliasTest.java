package sh.zolt.cli.task;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CommandAliasTest {
    @TempDir
    private Path tempDir;

    @Test
    void listsConfiguredAliases() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("alias-list") + """

                [aliases]
                tl = ["tasks"]
                ci = ["check", "--context", "ci"]
                """);

        CommandResult result = execute("aliases", "--cwd", tempDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Aliases:"));
        assertTrue(result.stdout().contains("tl"));
        assertTrue(result.stdout().contains("tasks"));
        assertTrue(result.stdout().contains("ci"));
        assertTrue(result.stdout().contains("check --context ci"));
        assertEquals("", result.stderr());
    }

    @Test
    void expandsAliasToBuiltInCommandAndAppendsUserArguments() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("alias-expand") + """

                [aliases]
                tl = ["tasks"]

                [tasks.fmt]
                description = "Format Java sources"
                run = ["scripts/format"]
                """);

        CommandResult result = execute("--color=never", "tl", "--cwd", tempDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Tasks:"));
        assertTrue(result.stdout().contains("fmt"));
        assertTrue(result.stdout().contains("Format Java sources"));
        assertEquals("", result.stderr());
    }

    @Test
    void invalidAliasConfigDoesNotBreakRootHelp() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("alias-invalid") + """

                [aliases]
                bad = ["./scripts/check"]
                """);

        CommandResult result = execute("help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("The modern Java build toolkit."));
        assertEquals("", result.stderr());
    }

    @Test
    void unknownCommandMentionsAliasesOnlyWhenAliasesExist() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("alias-unknown") + """

                [aliases]
                tl = ["tasks"]
                """);

        CommandResult result = execute("missing", "--cwd", tempDir.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("Unmatched argument"));
        assertTrue(result.stderr().contains("Run `zolt aliases` to list configured aliases."));
        assertEquals("", result.stdout());
    }

    @Test
    void unknownCommandKeepsNormalDiagnosticWhenNoAliasesExist() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("alias-none"));

        CommandResult result = execute("missing", "--cwd", tempDir.toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("Unmatched argument"));
        assertFalse(result.stderr().contains("zolt aliases"));
        assertEquals("", result.stdout());
    }
}

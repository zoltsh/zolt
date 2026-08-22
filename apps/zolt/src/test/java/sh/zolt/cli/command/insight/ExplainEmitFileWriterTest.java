package sh.zolt.cli.command.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.emit.DraftZoltTomlDocument;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * {@code --emit-toml-overwrite} can target a live {@code zolt.toml}, so replacing one must not go
 * through the file the project is still using.
 */
final class ExplainEmitFileWriterTest {
    private final StringWriter stderr = new StringWriter();

    @TempDir
    private Path tempDir;

    @Test
    void overwriteReplacesTheTargetInsteadOfTruncatingItInPlace() throws IOException {
        Path target = tempDir.resolve("zolt.toml");
        Files.writeString(target, "[project]\nname = \"live\"\n");
        Object identityBefore = fileIdentity(target);

        writer(true).write(tempDir, List.of(new DraftZoltTomlDocument("zolt.toml", "[project]\nname = \"draft\"\n")));

        assertEquals("[project]\nname = \"draft\"\n", Files.readString(target));
        assertNotEquals(
                identityBefore,
                fileIdentity(target),
                "an in-place truncating write reuses the live file; the replacement must be a rename");
        assertEquals(List.of("zolt.toml"), directoryEntries(tempDir));
    }

    @Test
    void overwriteFailsClosedWhenTheTargetChangedSinceItWasPlanned() throws IOException {
        Path target = tempDir.resolve("zolt.toml");
        Files.writeString(target, "[project]\nname = \"live\"\n");
        ExplainEmitFileWriter writer = writer(true);
        List<ExplainEmitFileWriter.EmitWrite> planned =
                writer.plan(tempDir, List.of(new DraftZoltTomlDocument("zolt.toml", "[project]\nname = \"draft\"\n")));

        Files.writeString(target, "[project]\nname = \"edited-by-hand\"\n");

        assertThrows(CommandLine.ExecutionException.class, () -> writer.commit(planned));
        assertEquals("[project]\nname = \"edited-by-hand\"\n", Files.readString(target));
        assertTrue(stderr.toString().contains("changed while the migration was running"), stderr.toString());
        assertEquals(List.of("zolt.toml"), directoryEntries(tempDir));
    }

    @Test
    void plannedTargetThatAppearsBeforeTheWriteIsNotClobbered() throws IOException {
        Path target = tempDir.resolve("zolt.toml");
        ExplainEmitFileWriter writer = writer(true);
        List<ExplainEmitFileWriter.EmitWrite> planned =
                writer.plan(tempDir, List.of(new DraftZoltTomlDocument("zolt.toml", "[project]\nname = \"draft\"\n")));

        Files.writeString(target, "[project]\nname = \"appeared\"\n");

        assertThrows(CommandLine.ExecutionException.class, () -> writer.commit(planned));
        assertEquals("[project]\nname = \"appeared\"\n", Files.readString(target));
    }

    private ExplainEmitFileWriter writer(boolean overwrite) {
        return new ExplainEmitFileWriter(spec(), Path.of("."), overwrite);
    }

    private CommandSpec spec() {
        CommandLine commandLine = new CommandLine(CommandSpec.create().name("explain"));
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.getCommandSpec();
    }

    /** The filesystem's identity for the file behind {@code path}, or null where it has none. */
    private static Object fileIdentity(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
    }

    private static List<String> directoryEntries(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }
}

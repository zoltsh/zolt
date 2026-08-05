package sh.zolt.workspace.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fence rules, stated as behaviour: what must be re-read, what may be trusted, and the fact that
 * a reused hash and a fresh one produce the same digest.
 */
final class WorkspaceFileSnapshotTest {
    private static final String MEMBER = "modules/core";

    @TempDir
    private Path root;
    private Path source;

    @BeforeEach
    void writeSource() throws IOException {
        source = root.resolve("modules/core/src/main/java/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Main {}\n");
    }

    @Test
    void anUnchangedFileBehindTheFenceIsStattedButNotRead() throws IOException {
        WorkspaceFileSnapshot first = snapshot(WorkspaceFileState.empty(), false);
        String digest = sources(first);
        assertEquals(1, first.filesHashed());

        WorkspaceFileSnapshot second = snapshot(settled(first), false);

        assertEquals(digest, sources(second));
        assertEquals(0, second.filesHashed());
        assertEquals(0L, second.bytesHashed());
        assertEquals(1, second.filesStatted());
        assertEquals(1, second.filesReused());
    }

    @Test
    void aTouchedFileIsReadAgainAndStillDigestsTheSame() throws IOException {
        WorkspaceFileSnapshot first = snapshot(WorkspaceFileState.empty(), false);
        String digest = sources(first);
        WorkspaceFileState previous = settled(first);
        Files.setLastModifiedTime(source, FileTime.fromMillis(modified(source) + 4_000L));

        WorkspaceFileSnapshot second = snapshot(previous, false);

        assertEquals(digest, sources(second));
        assertEquals(1, second.filesHashed());
        assertEquals(0, second.filesReused());
    }

    @Test
    void anEditedFileIsReadAgainAndMovesTheDigest() throws IOException {
        WorkspaceFileSnapshot first = snapshot(WorkspaceFileState.empty(), false);
        String digest = sources(first);
        WorkspaceFileState previous = settled(first);
        Files.writeString(source, "class Main { int value; }\n");

        WorkspaceFileSnapshot second = snapshot(previous, false);

        assertNotEquals(digest, sources(second));
        assertEquals(1, second.filesHashed());
    }

    /**
     * The same-size same-timestamp edit: only the fence can catch it, because nothing the filesystem
     * reports has moved. A row whose file is not strictly older than the state it was written beside
     * is refused, so the bytes are read again and the edit is seen.
     */
    @Test
    void anEditInsideTheRacyWindowIsReadAgain() throws IOException {
        WorkspaceFileSnapshot first = snapshot(WorkspaceFileState.empty(), false);
        String digest = sources(first);
        long fence = modifiedNanos(source);
        Files.writeString(source, "class Mai2 {}\n");
        Files.setLastModifiedTime(source, FileTime.from(fence, TimeUnit.NANOSECONDS));
        assertEquals(14L, Files.size(source));

        WorkspaceFileSnapshot second = snapshot(first.state().withFence(fence), false);

        assertNotEquals(digest, sources(second));
        assertEquals(1, second.filesHashed());
    }

    /**
     * The negative control for the rule above, and the residual this design carries: an edit that
     * keeps the size and back-dates the timestamp behind the fence leaves nothing for a metadata
     * comparison to see. Paranoid mode is the answer, and this pins that it is the only one.
     */
    @Test
    void aBackDatedSameSizeEditIsInvisibleUntilParanoidMode() throws IOException {
        WorkspaceFileSnapshot first = snapshot(WorkspaceFileState.empty(), false);
        String digest = sources(first);
        WorkspaceFileState previous = settled(first);
        Files.writeString(source, "class Mai2 {}\n");
        Files.setLastModifiedTime(
                source,
                FileTime.from(previous.files().values().iterator().next().modifiedNanos(),
                        TimeUnit.NANOSECONDS));

        assertEquals(digest, sources(snapshot(previous, false)));
        assertNotEquals(digest, sources(snapshot(previous, true)));
    }

    @Test
    void anAddedFileIsHashedAndADeletedFileLosesItsRow() throws IOException {
        WorkspaceFileSnapshot first = snapshot(WorkspaceFileState.empty(), false);
        String digest = sources(first);
        Path added = source.resolveSibling("Other.java");
        Files.writeString(added, "class Other {}\n");

        WorkspaceFileSnapshot second = snapshot(settled(first), false);
        String withAdded = sources(second);
        assertNotEquals(digest, withAdded);
        assertEquals(1, second.filesHashed());
        assertEquals(2, second.state().files().size());

        Files.delete(added);
        WorkspaceFileSnapshot third = snapshot(settled(second), false);

        assertEquals(digest, sources(third));
        assertEquals(0, third.filesHashed());
        assertEquals(1, third.state().files().size());
    }

    @Test
    void paranoidModeReadsEveryFileAndStillAgreesOnTheDigest() throws IOException {
        WorkspaceFileSnapshot first = snapshot(WorkspaceFileState.empty(), false);
        String digest = sources(first);

        WorkspaceFileSnapshot second = snapshot(settled(first), true);

        assertEquals(digest, sources(second));
        assertEquals(1, second.filesHashed());
        assertEquals(0, second.filesReused());
    }

    @Test
    void aRowRecordsTheStatItWasHashedBeside() throws IOException {
        WorkspaceFileSnapshot snapshot = snapshot(WorkspaceFileState.empty(), false);
        sources(snapshot);

        WorkspaceFileRecord record = snapshot
                .state()
                .file("modules/core/src/main/java/Main.java")
                .orElseThrow();

        assertEquals(WorkspaceFileKind.MAIN_SOURCE, record.kind());
        assertEquals(MEMBER, record.member());
        assertEquals(Files.size(source), record.size());
        assertEquals(modifiedNanos(source), record.modifiedNanos());
        assertFalse(record.hash().isBlank());
        assertTrue(record.scope().startsWith(MEMBER));
    }

    private WorkspaceFileSnapshot snapshot(WorkspaceFileState previous, boolean paranoid) {
        return new WorkspaceFileSnapshot(root, previous, paranoid);
    }

    private String sources(WorkspaceFileSnapshot snapshot) {
        return snapshot.javaSources(
                        MEMBER,
                        WorkspaceFileKind.MAIN_SOURCE,
                        root.resolve("modules/core"),
                        List.of("src/main/java"))
                .digest();
    }

    /** The table as a later command would read it: fenced behind a state written just now. */
    private static WorkspaceFileState settled(WorkspaceFileSnapshot snapshot) {
        long fence = snapshot.state().files().values().stream()
                .mapToLong(WorkspaceFileRecord::modifiedNanos)
                .max()
                .orElse(0L);
        return snapshot.state().withFence(fence + 1L);
    }

    private static long modified(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis();
    }

    private static long modifiedNanos(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class)
                .lastModifiedTime()
                .to(TimeUnit.NANOSECONDS);
    }
}

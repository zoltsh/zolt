package sh.zolt.workspace.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkspaceFileSnapshotSymlinkTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"main", "test", "resource"})
    void confinedFileLinkTargetEditsMoveTheSnapshotEvenWithPreservedMetadata(String lane) throws IOException {
        Path project = Files.createDirectories(root.resolve("app"));
        Path target = Files.createDirectories(project.resolve("linked")).resolve(filename(lane));
        Files.writeString(target, "before");
        FileTime modified = Files.getLastModifiedTime(target);
        Path link = link(project, lane, target);
        WorkspaceFileSnapshot first = snapshot(WorkspaceFileState.empty());
        var before = digest(first, project, lane);
        assertEquals(1, before.fileCount());
        assertEquals(1, first.state().files().size());

        Files.writeString(target, "after!");
        Files.setLastModifiedTime(target, modified);
        var after = digest(snapshot(first.state()), project, lane);

        assertNotEquals(before.digest(), after.digest());
        assertEquals(after.digest(), digest(snapshot(WorkspaceFileState.empty()), project, lane).digest());
        assertEquals("after!", Files.readString(link));
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "test", "resource"})
    void retargetingAFileLinkChangesItsIdentityEvenWhenBytesMatch(String lane) throws IOException {
        Path project = Files.createDirectories(root.resolve("app"));
        Path targets = Files.createDirectories(project.resolve("linked"));
        Path firstTarget = Files.writeString(targets.resolve("first-" + filename(lane)), "same");
        Path secondTarget = Files.writeString(targets.resolve("second-" + filename(lane)), "same");
        Path link = link(project, lane, firstTarget);
        var before = digest(snapshot(WorkspaceFileState.empty()), project, lane);
        Files.delete(link);
        Files.createSymbolicLink(link, link.getParent().relativize(secondTarget));

        var after = digest(snapshot(WorkspaceFileState.empty()), project, lane);

        assertNotEquals(before.digest(), after.digest());
        assertEquals(1, after.fileCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "test", "resource"})
    void fileLinksOutsideTheMemberAreRejected(String lane) throws IOException {
        Path project = Files.createDirectories(root.resolve("app"));
        Path outside = Files.writeString(root.resolve(filename(lane)), "outside");
        link(project, lane, outside);

        assertThrows(BuildException.class, () -> digest(snapshot(WorkspaceFileState.empty()), project, lane));

        assertEquals("outside", Files.readString(outside));
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "test", "resource"})
    void escapingFileLinksAreValidatedBeforeExtensionFiltering(String lane) throws IOException {
        Path project = Files.createDirectories(root.resolve("app"));
        Path outside = Files.writeString(root.resolve("outside.data"), "outside");
        String directory = lane.equals("test") ? "src/test/java"
                : lane.equals("resource") ? "src/main/resources" : "src/main/java";
        Path parent = Files.createDirectories(project.resolve(directory));
        String ignoredName = lane.equals("resource") ? "Ignored.java" : "ignored.txt";
        Files.createSymbolicLink(parent.resolve(ignoredName), parent.relativize(outside));

        assertThrows(BuildException.class, () -> digest(snapshot(WorkspaceFileState.empty()), project, lane));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void directoryLinksAreNotRecursivelyFollowed() throws IOException {
        Path project = Files.createDirectories(root.resolve("app"));
        Path target = Files.createDirectories(project.resolve("linked"));
        Files.writeString(target.resolve("Main.java"), "class Main {}\n");
        Path sources = Files.createDirectories(project.resolve("src/main/java"));
        Files.createSymbolicLink(sources.resolve("alias"), sources.relativize(target));

        assertEquals(0, digest(snapshot(WorkspaceFileState.empty()), project, "main").fileCount());
    }

    private WorkspaceFileSnapshot snapshot(WorkspaceFileState previous) {
        return new WorkspaceFileSnapshot(root, previous, false);
    }

    private static WorkspaceFileSnapshot.TreeDigest digest(
            WorkspaceFileSnapshot snapshot, Path project, String lane) {
        return switch (lane) {
            case "main" -> snapshot.mainSources("app", WorkspaceFileKind.MAIN_SOURCE,
                    project, List.of("src/main/java"));
            case "test" -> snapshot.testSources("app", WorkspaceFileKind.TEST_SOURCE,
                    project, List.of("src/test/java"), List.of());
            default -> snapshot.resources("app", WorkspaceFileKind.MAIN_RESOURCE,
                    project, List.of("src/main/resources"));
        };
    }

    private static Path link(Path project, String lane, Path target) throws IOException {
        String directory = switch (lane) {
            case "main" -> "src/main/java";
            case "test" -> "src/test/java";
            default -> "src/main/resources";
        };
        Path parent = Files.createDirectories(project.resolve(directory));
        return Files.createSymbolicLink(parent.resolve(filename(lane)), parent.relativize(target));
    }

    private static String filename(String lane) {
        return lane.equals("resource") ? "message.txt" : "Main.java";
    }
}

package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProjectPathsTest {
    @TempDir
    private Path projectDir;

    @Test
    void resolvesProjectRelativeInputPath() {
        Path path = ProjectPaths.input(ProjectPaths.root(projectDir), "[build].sources", "src/main/java");

        assertEquals(projectDir.resolve("src/main/java"), path);
    }

    @Test
    void rejectsParentEscapesWithConfigKeyAndResolvedPath() {
        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> ProjectPaths.input(ProjectPaths.root(projectDir), "[build].sources", "../outside"));

        assertTrue(exception.getMessage().contains("[build].sources"));
        assertTrue(exception.getMessage().contains("../outside"));
        assertTrue(exception.getMessage().contains(projectDir.getParent().resolve("outside").toString()));
    }

    @Test
    void rejectsUnixAbsolutePaths() {
        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> ProjectPaths.input(ProjectPaths.root(projectDir), "[resources].main", "/tmp/outside"));

        assertTrue(exception.getMessage().contains("[resources].main"));
        assertTrue(exception.getMessage().contains("/tmp/outside"));
    }

    @Test
    void rejectsWindowsAbsolutePathsOnEveryHost() {
        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> ProjectPaths.input(ProjectPaths.root(projectDir), "[build].sources", "C:\\outside\\src"));

        assertTrue(exception.getMessage().contains("[build].sources"));
        assertTrue(exception.getMessage().contains("C:\\outside\\src"));
    }

    @Test
    void rejectsUnsafeFilenameComponents() {
        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> ProjectPaths.filenameComponent("[project].name", "../outside"));

        assertTrue(exception.getMessage().contains("[project].name"));
        assertTrue(exception.getMessage().contains("../outside"));
        assertTrue(exception.getMessage().contains("derived file names"));
    }

    @Test
    void acceptsNormalFilenameComponents() {
        assertEquals("demo-0.1.0-SNAPSHOT", ProjectPaths.filenameComponent(
                "[project].version",
                "demo-0.1.0-SNAPSHOT"));
    }

    @Test
    void inputMayPointAtProjectRootButOutputMayNot() {
        Path root = ProjectPaths.root(projectDir);

        assertEquals(root, ProjectPaths.input(root, "[build].sources", "."));
        assertThrows(ProjectPathException.class, () -> ProjectPaths.output(root, "[build.output].main", "."));
    }

    @Test
    void rejectsExistingRootSymlinkThatEscapesProject() throws IOException {
        Path outside = Files.createTempDirectory(projectDir.getParent(), "outside-root-");
        Path link = projectDir.resolve("src/main/java");
        createSymlink(link, outside);

        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> ProjectPaths.existingRoot(ProjectPaths.root(projectDir), "[build].sources", "src/main/java"));

        assertTrue(exception.getMessage().contains("[build].sources"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
    }

    @Test
    void rejectsOutputWithExistingSymlinkedParentThatEscapesProject() throws IOException {
        Path outside = Files.createTempDirectory(projectDir.getParent(), "outside-output-");
        createSymlink(projectDir.resolve("target"), outside);

        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> ProjectPaths.output(ProjectPaths.root(projectDir), "[build.output].main", "target/classes"));

        assertTrue(exception.getMessage().contains("[build.output].main"));
        assertTrue(exception.getMessage().contains("target/classes"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
    }

    @Test
    void rejectsRegularFileSymlinkThatEscapesProject() throws IOException {
        Path outside = Files.createTempFile(projectDir.getParent(), "outside-file-", ".txt");
        Files.writeString(outside, "outside\n");
        Path link = projectDir.resolve("src/main/resources/outside.txt");
        createSymlink(link, outside);

        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> ProjectPaths.isRegularFileInsideProject(
                        ProjectPaths.root(projectDir),
                        "[resources].main",
                        link));

        assertTrue(exception.getMessage().contains("[resources].main"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
    }

    @Test
    void ignoresMissingRegularFiles() {
        assertFalse(ProjectPaths.isRegularFileInsideProject(
                ProjectPaths.root(projectDir),
                "[resources].main",
                projectDir.resolve("missing.txt")));
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}

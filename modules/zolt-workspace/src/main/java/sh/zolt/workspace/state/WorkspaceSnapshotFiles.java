package sh.zolt.workspace.state;

import sh.zolt.build.BuildException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.project.ProjectPathException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Non-following walks with the same confined file-link policy as source discovery. */
final class WorkspaceSnapshotFiles {
    private WorkspaceSnapshotFiles() {
    }

    static List<Entry> walk(Path root, Predicate<Path> included, Path projectRoot) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Path normalized = file.toAbsolutePath().normalize();
                    boolean acceptedLink = projectRoot != null && attributes.isSymbolicLink()
                            && ProjectPaths.isRegularFileInsideProject(projectRoot, "workspace input", normalized);
                    if (!included.test(normalized)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attributes.isRegularFile()) {
                        entries.add(new Entry(normalized, attributes));
                    } else if (acceptedLink) {
                        Path realRoot = projectRoot.toRealPath();
                        Path target = normalized.toRealPath();
                        String identity = realRoot.relativize(target).toString().replace('\\', '/');
                        // Supplied walk attributes describe the link; ask the hasher to stat its target.
                        entries.add(new Entry(normalized, null, identity));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    throw exception;
                }
            });
        } catch (IOException | ProjectPathException exception) {
            throw new BuildException("Could not snapshot workspace files under " + root + ".", exception);
        }
        return entries;
    }

    record Entry(Path path, BasicFileAttributes attributes, String linkTarget) {
        Entry(Path path, BasicFileAttributes attributes) {
            this(path, attributes, null);
        }
    }
}

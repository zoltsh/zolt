package sh.zolt.workspace.state;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/**
 * Reads and commits {@code .zolt/workspace-state-v1}.
 *
 * <p>The store owns the racy-clean fence. It is the state file's own modification time, taken from
 * the same filesystem clock as the inputs it will be compared against, so no wall-clock reading and
 * no clock skew between processes can enter the decision. A file whose modification time is not
 * strictly older than the state file's has its recorded hash refused; see {@link WorkspaceFileState}.
 *
 */
public final class WorkspaceStateStore {
    private static final String FILE_NAME = "workspace-state-v1";
    private final WorkspaceStateCodec codec = new WorkspaceStateCodec();

    public WorkspaceState read(Path workspaceRoot) {
        Path path = path(workspaceRoot);
        Optional<BasicFileAttributes> attributes = attributes(path);
        if (attributes.isEmpty() || !attributes.orElseThrow().isRegularFile()) {
            return WorkspaceState.empty();
        }
        try {
            long fence = WorkspaceFileRecord.modifiedNanos(attributes.orElseThrow());
            return codec.parse(Files.readString(path, StandardCharsets.UTF_8))
                    .map(state -> state.withFiles(state.files().withFence(fence)))
                    .orElseGet(WorkspaceState::empty);
        } catch (NoSuchFileException exception) {
            return WorkspaceState.empty();
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not read workspace state at " + path + ".",
                    exception);
        }
    }

    public void write(Path workspaceRoot, WorkspaceState state) {
        Path path = path(workspaceRoot);
        String content = codec.format(state);
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(
                    path.getParent(),
                    path.getFileName() + ".",
                    ".tmp");
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8);
            move(temporary, path);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not write workspace state at " + path + ".",
                    exception);
        } finally {
            deleteTemporary(temporary);
        }
    }

    public Path path(Path workspaceRoot) {
        return workspaceRoot.toAbsolutePath().normalize()
                .resolve(".zolt")
                .resolve(FILE_NAME);
    }

    private static Optional<BasicFileAttributes> attributes(Path path) {
        try {
            return Optional.of(Files.readAttributes(path, BasicFileAttributes.class));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // The committed state is authoritative; a unique orphan is harmless.
        }
    }
}

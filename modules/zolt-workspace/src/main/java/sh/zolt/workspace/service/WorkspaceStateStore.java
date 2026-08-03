package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class WorkspaceStateStore {
    private static final String FILE_NAME = "workspace-state-v1";
    private final WorkspaceStateCodec codec = new WorkspaceStateCodec();

    WorkspaceState read(Path workspaceRoot) {
        Path path = path(workspaceRoot);
        if (!Files.isRegularFile(path)) {
            return WorkspaceState.empty();
        }
        try {
            return codec.parse(Files.readString(path, StandardCharsets.UTF_8))
                    .orElseGet(WorkspaceState::empty);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not read workspace state at " + path + ".",
                    exception);
        }
    }

    void write(Path workspaceRoot, WorkspaceState state) {
        Path path = path(workspaceRoot);
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(
                    path.getParent(),
                    path.getFileName() + ".",
                    ".tmp");
            Files.writeString(
                    temporary,
                    codec.format(state),
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

    Path path(Path workspaceRoot) {
        return workspaceRoot.toAbsolutePath().normalize()
                .resolve(".zolt")
                .resolve(FILE_NAME);
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

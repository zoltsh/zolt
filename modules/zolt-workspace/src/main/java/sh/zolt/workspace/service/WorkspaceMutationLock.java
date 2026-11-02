package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import sh.zolt.build.ProjectMutationLock;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Serializes workspace mutations using the same lease as standalone project operations. */
public final class WorkspaceMutationLock implements AutoCloseable {
    private static final int ROOT_CONFIRMATION_ATTEMPTS = 3;
    private final ProjectMutationLock lease;

    private WorkspaceMutationLock(ProjectMutationLock lease) {
        this.lease = lease;
    }

    public static WorkspaceMutationLock acquire(Path workspaceRoot) {
        return new WorkspaceMutationLock(ProjectMutationLock.acquireWorkspace(workspaceRoot));
    }

    public static <T> T withLock(Path workspaceRoot, Supplier<T> action) {
        try (WorkspaceMutationLock ignored = acquire(workspaceRoot)) {
            return action.get();
        }
    }

    /**
     * Locates the root only to choose a lock, then confirms it under the lease before running the
     * authoritative discovery and planning supplied by {@code action}.
     */
    public static <T> T withWorkspaceLock(
            Path startDirectory,
            Supplier<T> action) {
        ManifestWorkspaceLoader discovery = new ManifestWorkspaceLoader();
        for (int attempt = 0; attempt < ROOT_CONFIRMATION_ATTEMPTS; attempt++) {
            var discoveredRoot = discovery.discoverRoot(startDirectory);
            if (discoveredRoot.isEmpty()) {
                return action.get();
            }
            Path root = discoveredRoot.orElseThrow();
            try (WorkspaceMutationLock ignored = acquire(root)) {
                if (discovery.discoverRoot(startDirectory)
                        .filter(root::equals)
                        .isPresent()) {
                    return action.get();
                }
            }
        }
        throw new BuildException(
                "Workspace root changed repeatedly while acquiring its mutation lock. Retry the command.");
    }

    public static <T> T withLockIfWorkspace(
            Path startDirectory,
            Supplier<T> action) {
        if (new ManifestWorkspaceLoader().discoverRoot(startDirectory).isEmpty()) {
            return action.get();
        }
        return withWorkspaceLock(startDirectory, action);
    }

    static Path path(Path workspaceRoot) {
        return workspaceRoot.toAbsolutePath().normalize().resolve(".zolt/workspace-mutation.lock");
    }

    @Override
    public void close() {
        lease.close();
    }
}

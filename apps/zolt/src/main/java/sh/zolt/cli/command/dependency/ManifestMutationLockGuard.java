package sh.zolt.cli.command.dependency;

import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/** Acquires a mutation lock only after its discovered workspace root stabilizes. */
final class ManifestMutationLockGuard {
    private static final int ROOT_CONFIRMATION_ATTEMPTS = 3;

    private ManifestMutationLockGuard() {
    }

    static <T> T withLock(Path projectRoot, Function<Path, T> action) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(action, "action");
        WorkspaceDiscoveryService discovery = new WorkspaceDiscoveryService();
        Path standaloneRoot = projectRoot.toAbsolutePath().normalize();
        for (int attempt = 0; attempt < ROOT_CONFIRMATION_ATTEMPTS; attempt++) {
            Path lockRoot = discovery.discoverRoot(projectRoot)
                    .map(path -> path.toAbsolutePath().normalize())
                    .orElse(standaloneRoot);
            try (WorkspaceMutationLock ignored = WorkspaceMutationLock.acquire(lockRoot)) {
                Path confirmedRoot = discovery.discoverRoot(projectRoot)
                        .map(path -> path.toAbsolutePath().normalize())
                        .orElse(standaloneRoot);
                if (lockRoot.equals(confirmedRoot)) {
                    return action.apply(lockRoot);
                }
            }
        }
        throw new ZoltConfigException(
                "Workspace root changed repeatedly while acquiring the manifest mutation lock. Retry the command.");
    }
}

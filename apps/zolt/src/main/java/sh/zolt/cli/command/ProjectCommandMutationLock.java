package sh.zolt.cli.command;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import sh.zolt.build.ProjectMutationLock;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;

/** Locks command configuration and output together after confirming the owning workspace. */
public final class ProjectCommandMutationLock {
    private ProjectCommandMutationLock() {}

    public static <T> T withLock(Path projectRoot, Function<Path, T> action) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(action, "action");
        ManifestWorkspaceLoader discovery = new ManifestWorkspaceLoader();
        Path standaloneRoot = projectRoot.toAbsolutePath().normalize();
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<Path> workspace = discovery.discoverRoot(projectRoot);
            Path lockRoot = workspace.orElse(standaloneRoot);
            try (ProjectMutationLock ignored = workspace.isPresent()
                    ? ProjectMutationLock.acquireWorkspace(lockRoot)
                    : ProjectMutationLock.acquire(lockRoot)) {
                if (workspace.equals(discovery.discoverRoot(projectRoot))) {
                    return action.apply(lockRoot);
                }
            }
        }
        throw new ZoltConfigException(
                "Workspace root changed repeatedly while acquiring its mutation lock. Retry the command.");
    }
}

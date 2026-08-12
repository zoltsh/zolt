package sh.zolt.cli.command.dependency;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.update.UpdateTargetKey;
import sh.zolt.workspace.WorkspaceConfig;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Catalog scope for dependency policy owned directly by the workspace manifest. */
record ResolvedWorkspaceUpdateScope(
        Path mutationRoot,
        Path projectDirectory,
        String label,
        String manifestPath,
        String lockfilePath,
        WorkspaceConfig config,
        Optional<ZoltLockfile> lockfile,
        Map<UpdateTargetKey, String> targetBlockers) implements CatalogUpdateScope {
    ResolvedWorkspaceUpdateScope {
        mutationRoot = normalize(mutationRoot, "mutationRoot");
        projectDirectory = normalize(projectDirectory, "projectDirectory");
        label = Objects.requireNonNull(label, "label");
        manifestPath = Objects.requireNonNull(manifestPath, "manifestPath");
        lockfilePath = Objects.requireNonNull(lockfilePath, "lockfilePath");
        config = Objects.requireNonNull(config, "config");
        lockfile = lockfile == null ? Optional.empty() : lockfile;
        targetBlockers = targetBlockers == null ? Map.of() : Map.copyOf(targetBlockers);
    }

    ResolvedWorkspaceUpdateScope(
            Path mutationRoot,
            Path projectDirectory,
            String label,
            String manifestPath,
            String lockfilePath,
            WorkspaceConfig config,
            Optional<ZoltLockfile> lockfile) {
        this(mutationRoot, projectDirectory, label, manifestPath, lockfilePath, config, lockfile, Map.of());
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}

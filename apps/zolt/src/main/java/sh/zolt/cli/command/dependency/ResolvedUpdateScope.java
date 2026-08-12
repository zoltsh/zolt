package sh.zolt.cli.command.dependency;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.update.UpdateTargetId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One catalogable project plus its canonical paths under the selected mutation root. */
record ResolvedUpdateScope(
        Path mutationRoot,
        Path projectDirectory,
        String label,
        String manifestPath,
        String lockfilePath,
        ProjectConfig config,
        Optional<ZoltLockfile> lockfile,
        Map<UpdateTargetId, String> targetBlockers) implements CatalogUpdateScope {
    ResolvedUpdateScope {
        mutationRoot = normalize(mutationRoot, "mutationRoot");
        projectDirectory = normalize(projectDirectory, "projectDirectory");
        label = Objects.requireNonNull(label, "label");
        manifestPath = Objects.requireNonNull(manifestPath, "manifestPath");
        lockfilePath = Objects.requireNonNull(lockfilePath, "lockfilePath");
        config = Objects.requireNonNull(config, "config");
        lockfile = lockfile == null ? Optional.empty() : lockfile;
        targetBlockers = targetBlockers == null ? Map.of() : Map.copyOf(targetBlockers);
    }

    ResolvedUpdateScope(
            Path mutationRoot,
            Path projectDirectory,
            String label,
            String manifestPath,
            String lockfilePath,
            ProjectConfig config,
            Optional<ZoltLockfile> lockfile) {
        this(mutationRoot, projectDirectory, label, manifestPath, lockfilePath, config, lockfile, Map.of());
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}

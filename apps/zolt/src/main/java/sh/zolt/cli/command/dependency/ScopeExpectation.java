package sh.zolt.cli.command.dependency;

import sh.zolt.project.ProjectConfig;
import sh.zolt.update.UpdateTargetId;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authoritative manifest and lockfile paths selected before transaction execution. */
record ScopeExpectation(
        Path manifestPath,
        Path lockfilePath,
        List<UpdateTargetId> targetIds,
        Optional<ProjectConfig> discoveryConfig) {
    ScopeExpectation {
        manifestPath = normalize(manifestPath, "manifestPath");
        lockfilePath = normalize(lockfilePath, "lockfilePath");
        targetIds = targetIds == null ? List.of() : List.copyOf(targetIds);
        discoveryConfig = discoveryConfig == null ? Optional.empty() : discoveryConfig;
    }

    ScopeExpectation(Path manifestPath, Path lockfilePath, List<UpdateTargetId> targetIds) {
        this(manifestPath, lockfilePath, targetIds, Optional.empty());
    }

    ScopeExpectation(Path manifestPath, Path lockfilePath) {
        this(manifestPath, lockfilePath, List.of(), Optional.empty());
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}

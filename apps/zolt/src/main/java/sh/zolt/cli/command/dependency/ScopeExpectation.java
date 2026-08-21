package sh.zolt.cli.command.dependency;

import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Authoritative manifest and lockfile paths selected before transaction execution. */
record ScopeExpectation(
        Path manifestPath,
        Path lockfilePath,
        Optional<ProjectConfig> discoveryConfig) {
    ScopeExpectation {
        manifestPath = normalize(manifestPath, "manifestPath");
        lockfilePath = normalize(lockfilePath, "lockfilePath");
        discoveryConfig = discoveryConfig == null ? Optional.empty() : discoveryConfig;
    }

    ScopeExpectation(Path manifestPath, Path lockfilePath) {
        this(manifestPath, lockfilePath, Optional.empty());
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}

package sh.zolt.cli.command.dependency;

import sh.zolt.update.UpdateTargetId;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Authoritative manifest and lockfile paths selected before transaction execution. */
record ScopeExpectation(
        Path manifestPath,
        Path lockfilePath,
        Optional<UpdateTargetId> targetId) {
    ScopeExpectation {
        manifestPath = normalize(manifestPath, "manifestPath");
        lockfilePath = normalize(lockfilePath, "lockfilePath");
        targetId = targetId == null ? Optional.empty() : targetId;
    }

    ScopeExpectation(Path manifestPath, Path lockfilePath) {
        this(manifestPath, lockfilePath, Optional.empty());
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}

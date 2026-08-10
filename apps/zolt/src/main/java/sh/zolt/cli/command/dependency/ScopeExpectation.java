package sh.zolt.cli.command.dependency;

import java.nio.file.Path;
import java.util.Objects;

/** Authoritative manifest and lockfile paths selected before transaction execution. */
record ScopeExpectation(Path manifestPath, Path lockfilePath) {
    ScopeExpectation {
        manifestPath = normalize(manifestPath, "manifestPath");
        lockfilePath = normalize(lockfilePath, "lockfilePath");
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}

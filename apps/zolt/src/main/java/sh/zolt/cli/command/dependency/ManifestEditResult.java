package sh.zolt.cli.command.dependency;

import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Actual byte-level effects of a completed manifest edit transaction. */
record ManifestEditResult(
        ProjectConfig original,
        ProjectConfig updated,
        ResolveResult resolveResult,
        Path manifestPath,
        Path lockfilePath,
        boolean manifestChanged,
        boolean lockfileChanged) {
    ManifestEditResult {
        manifestPath = normalize(manifestPath, "manifestPath");
        lockfilePath = normalize(lockfilePath, "lockfilePath");
    }

    boolean changed() {
        return manifestChanged || lockfileChanged;
    }

    List<Path> changedPaths() {
        List<Path> paths = new ArrayList<>();
        if (manifestChanged) {
            paths.add(manifestPath);
        }
        if (lockfileChanged) {
            paths.add(lockfilePath);
        }
        return List.copyOf(paths);
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
